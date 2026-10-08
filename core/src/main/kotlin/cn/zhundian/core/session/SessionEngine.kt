package cn.zhundian.core.session

import kotlin.math.max
import kotlin.random.Random

/** A deterministic, side-effect-free coordinator. The host serializes events and persists results. */
object SessionEngine {
    fun create(
        id: String,
        intensity: Intensity,
        nowMs: Long,
        parameters: ReminderParameters = ReminderParameters(),
        fallbackEnabled: Boolean = false,
    ): Session = Session(
        id = id,
        intensity = intensity,
        t0Ms = nowMs,
        lastEventMs = nowMs,
        parameters = parameters,
        grid = grid(id, intensity, 0),
        fallbackEnabled = fallbackEnabled && intensity == Intensity.STRONG,
        callStatus = if (fallbackEnabled && intensity == Intensity.STRONG) CallStatus.ARMED else CallStatus.DISABLED,
    )

    fun reduce(session: Session, event: Event): Session {
        if (session.stage.terminal) return session
        // Stop is handled before all timeouts, including the last check before claiming a call.
        when (event) {
            is Event.EmergencyStop -> return end(session, Stage.ABORTED, event.nowMs, "已紧急中止；未记为成功。已提交系统的电话无法由应用保证撤回")
            is Event.Cancel -> return end(session, Stage.CANCELLED, event.nowMs, "已取消本次提醒")
            is Event.StopReminder -> if (session.intensity == Intensity.NORMAL) {
                return end(session, Stage.COMPLETED, event.nowMs, "已停止本次提醒，其他安排和提醒保持不变")
            }
            is Event.RestoreAfterReboot -> return reboot(session, event)
            else -> Unit
        }
        if (event.nowMs < session.lastEventMs) return session

        // A sensor sample at a boundary describes actual collection before that boundary.
        val sampled = if (event is Event.SensorStatus) sensorStatus(session, event) else session
        var s = advance(sampled, event.nowMs)
        if (s.stage.terminal) return s
        s = when (event) {
            is Event.Tick, is Event.SensorStatus -> s
            is Event.OpenChallenge -> if (s.stage == Stage.RINGING && s.intensity != Intensity.NORMAL) {
                s.copy(stage = Stage.SCHULTE, message = "按顺序点击 1 到 ${s.challengeSize}")
            } else s
            is Event.TapNumber -> tap(s, event)
            is Event.PauseSound -> if (s.stage == Stage.RINGING || s.stage == Stage.SCHULTE || s.stage == Stage.TECHNICAL_FAULT) {
                s.copy(soundPausedUntilMs = event.nowMs + s.parameters.soundPauseMs,
                    message = "当前声音已短暂停止；运动和联系人期限仍继续计时")
            } else s
            is Event.Snooze -> if (s.intensity == Intensity.NORMAL && (s.stage == Stage.RINGING || s.stage == Stage.SNOOZED)) {
                s.copy(stage = Stage.SNOOZED, snoozeUntilMs = event.nowMs + s.parameters.snoozeMs,
                    soundPausedUntilMs = null, message = "稍后将再次提醒")
            } else s
            is Event.Motion -> motion(s, event)
            is Event.RetrySensors -> if (s.stage == Stage.TECHNICAL_FAULT) beginObservation(s, event.nowMs, resetMedium = true) else s
            is Event.UseGrace -> grace(s, event.nowMs)
            is Event.ConfirmFinish -> if (s.canFinish(event.nowMs)) {
                end(s, Stage.COMPLETED, event.nowMs, "已完成本次起床检查，后续提醒保持不变")
            } else s
            is Event.FallbackAvailability -> s.copy(
                callAvailability = event.availability,
                callStatus = if (s.callClaimed || !s.fallbackEnabled) s.callStatus else when (event.availability) {
                    CallAvailability.READY -> CallStatus.ARMED
                    CallAvailability.MANUAL_ONLY -> CallStatus.WAITING_USER
                    else -> CallStatus.BLOCKED
                },
            )
            is Event.ClaimCall -> if (s.fallbackEnabled && s.fallbackTriggered && !s.callClaimed &&
                s.callAvailability == CallAvailability.READY && s.stage != Stage.CALL_PAUSED) {
                pauseForCall(s, event.nowMs).copy(callClaimed = true, callStatus = CallStatus.CLAIMED_UNCERTAIN,
                    callMessage = "请求资格已持久化；若提交前中断，不自动补拨")
            } else s
            is Event.CallSubmitted -> if (s.callClaimed && s.callStatus == CallStatus.CLAIMED_UNCERTAIN) {
                s.copy(callStatus = CallStatus.SUBMITTED, callMessage = "请求已提交系统；接通状态未知，免提仅为请求")
            } else s
            is Event.CallFailed -> if (s.callClaimed) {
                resumeCall(s, event.nowMs).copy(callStatus = CallStatus.FAILED, callMessage = event.reason)
            } else s
            is Event.ExternalCallStarted -> pauseForCall(s, event.nowMs)
            is Event.ResumeAfterCall -> resumeCall(s, event.nowMs)
            is Event.StopReminder, is Event.Cancel, is Event.EmergencyStop, is Event.RestoreAfterReboot -> s
        }
        return s.copy(lastEventMs = event.nowMs)
    }

    private fun tap(s: Session, e: Event.TapNumber): Session {
        if (s.stage != Stage.SCHULTE) return s
        if (e.number != s.targetNumber) return s.copy(wrongTaps = s.wrongTaps + 1, message = "请点击 ${s.targetNumber}")
        if (e.number == s.challengeSize) return beginObservation(s.copy(targetNumber = e.number + 1), e.nowMs, resetMedium = true)
        return s.copy(targetNumber = s.targetNumber + 1, message = "请点击 ${s.targetNumber + 1}")
    }

    private fun beginObservation(s: Session, now: Long, resetMedium: Boolean): Session {
        val medium = s.intensity == Intensity.MEDIUM
        return s.copy(
            stage = if (medium) Stage.MONITORING else Stage.MOTION_VERIFY,
            motionDeadlineMs = now + if (medium) s.parameters.mediumWindowMs else s.parameters.initialVerificationMs,
            observationStartMs = now,
            mediumWindow = if (medium && resetMedium) 0 else s.mediumWindow,
            mediumWindowPassed = false,
            sensorQuality = SensorQuality.INSUFFICIENT,
            sensorReason = "等待新的有效步行；请随身携带手机",
            lastSensorMs = null,
            healthyDataMs = 0,
            soundPausedUntilMs = null,
            resumeStage = null,
            frozenMotionRemainingMs = null,
            graceUntilMs = null,
            graceRemainingMs = null,
            message = if (medium) "第 1 个运动观察窗口" else "请完成一段有效步行",
        )
    }

    private fun sensorStatus(s: Session, e: Event.SensorStatus): Session {
        if (!s.needsSensors) return s
        val delta = s.lastSensorMs?.let { e.nowMs - it } ?: 0
        val increment = if (s.sensorQuality == SensorQuality.NORMAL && e.quality == SensorQuality.NORMAL &&
            delta in 0..s.parameters.sensorFreshnessMs) delta else 0
        val updated = s.copy(sensorQuality = e.quality, sensorReason = e.reason,
            lastSensorMs = e.nowMs, healthyDataMs = s.healthyDataMs + increment)
        return if (e.quality == SensorQuality.UNAVAILABLE) fault(updated, e.nowMs, e.reason.ifBlank { "传感器不可用或权限已撤销" }) else updated
    }

    private fun advance(initial: Session, now: Long): Session {
        var s = initial
        if (s.intensity == Intensity.STRONG && !s.fallbackTriggered) {
            val firstExpired = s.t1Ms == null && now >= s.t0Ms + s.parameters.unconfirmedMs
            val roundExpired = s.roundStartedMs?.let { now >= it + s.parameters.unconfirmedMs } == true
            if (firstExpired || roundExpired) s = triggerFallback(s, now,
                if (firstExpired) "首次运动验证超过未确认期限" else "本轮重响后超过未确认期限")
        }
        if (s.stage == Stage.SNOOZED && s.snoozeUntilMs?.let { now >= it } == true) {
            s = s.copy(stage = Stage.RINGING, snoozeUntilMs = null, soundPausedUntilMs = null, message = "稍后提醒已到")
        }
        val graceEnd = s.graceUntilMs
        if (graceEnd != null && now >= graceEnd && s.stage == Stage.MONITORING) {
            s = s.copy(motionDeadlineMs = graceEnd + (s.graceRemainingMs ?: 0), graceUntilMs = null,
                graceRemainingMs = null, message = "静止准备已结束，请继续随身携带手机")
        }
        val deadline = s.motionDeadlineMs
        if (s.needsSensors && deadline != null && now >= deadline) {
            if (s.intensity == Intensity.MEDIUM && s.mediumWindowPassed) {
                val next = s.mediumWindow + 1
                if (next >= s.parameters.mediumWindows) {
                    return end(s, Stage.COMPLETED, now, "三个完整运动窗口已通过；后续提醒保持不变")
                }
                s = s.copy(mediumWindow = next, mediumWindowPassed = false, observationStartMs = deadline,
                    motionDeadlineMs = deadline + s.parameters.mediumWindowMs, healthyDataMs = 0,
                    lastSensorMs = null, sensorQuality = SensorQuality.INSUFFICIENT,
                    message = "第 ${next + 1} 个运动观察窗口")
                // A delayed process cannot invent evidence for windows elapsed while it was absent.
                if (now >= requireNotNull(s.motionDeadlineMs)) return fault(s, now, "提醒服务中断，缺少完整窗口数据，请重新检测")
            } else {
                s = if (sufficientData(s, deadline)) failRound(s, deadline) else
                    fault(s, now, "暂时无法验证运动：有效采集数据不足，请检查权限或重新检测")
            }
        }
        return s.copy(lastEventMs = now)
    }

    private fun sufficientData(s: Session, deadline: Long): Boolean {
        val start = s.observationStartMs ?: return false
        val availableMs = max(1L, deadline - start)
        return s.sensorQuality == SensorQuality.NORMAL &&
            s.lastSensorMs?.let { deadline - it in 0..s.parameters.sensorFreshnessMs } == true &&
            s.healthyDataMs >= max(s.parameters.minimumFragmentMs, (availableMs * s.parameters.requiredHealthyFraction).toLong())
    }

    private fun motion(s: Session, e: Event.Motion): Session {
        if (!s.needsSensors || e.endMs > e.nowMs || e.endMs < e.startMs ||
            e.endMs - e.startMs < s.parameters.minimumFragmentMs || e.fragmentId == s.lastMotionId ||
            e.endMs <= s.lastMotionEndMs ||
            s.observationStartMs?.let { e.startMs < it } != false) return s
        val accepted = s.copy(lastMotionEndMs = e.endMs, lastMotionId = e.fragmentId,
            lastValidMotionMs = e.endMs, requireFreshMotion = false,
            sensorReason = "识别到一段新的有效步行")
        if (s.intensity == Intensity.MEDIUM) {
            return accepted.copy(mediumWindowPassed = true, message = "本窗口已识别步行，等待窗口结束")
        }
        if (s.stage == Stage.MOTION_VERIFY) {
            val first = s.t1Ms == null
            return accepted.copy(stage = Stage.MONITORING, t1Ms = s.t1Ms ?: e.endMs,
                targetDeadlineMs = s.targetDeadlineMs ?: (e.endMs + s.parameters.sustainedTargetMs),
                motionDeadlineMs = e.endMs + s.parameters.noMotionMs, observationStartMs = e.endMs,
                roundStartedMs = null, roundId = null, healthyDataMs = 0, lastSensorMs = null,
                successfulWindows = if (first) s.successfulWindows else addWindow(s, e.endMs),
                message = if (first) "开始持续运动检查" else "已恢复原持续检查；累计失败和目标时间保持不变")
        }
        return accepted.copy(
            motionDeadlineMs = if (s.graceUntilMs != null) null else e.endMs + s.parameters.noMotionMs,
            observationStartMs = if (s.graceUntilMs != null) s.observationStartMs else e.endMs,
            healthyDataMs = if (s.graceUntilMs != null) s.healthyDataMs else 0,
            successfulWindows = addWindow(s, e.endMs),
            message = "已记录新步行；达到目标后请明确结束本次检查",
        )
    }

    private fun addWindow(s: Session, endMs: Long): Set<Int> {
        val start = s.t1Ms ?: return s.successfulWindows
        if (endMs < start) return s.successfulWindows
        return s.successfulWindows + ((endMs - start) / s.parameters.strongWindowMs).toInt()
    }

    private fun failRound(s: Session, now: Long): Session {
        val nextRound = s.round + 1
        var failed = s.copy(stage = Stage.RINGING, failures = s.failures + 1, round = nextRound,
            grid = grid(s.id, s.intensity, nextRound), targetNumber = 1, wrongTaps = 0,
            motionDeadlineMs = null, observationStartMs = null, mediumWindow = 0, mediumWindowPassed = false,
            roundId = "${s.id}:$nextRound", roundStartedMs = now,
            soundPausedUntilMs = null, graceUntilMs = null, graceRemainingMs = null,
            sensorQuality = SensorQuality.INSUFFICIENT, healthyDataMs = 0, lastSensorMs = null,
            message = "本轮未识别有效步行，已重新响铃")
        if (failed.intensity == Intensity.STRONG && failed.failures >= failed.parameters.maxFailures) {
            failed = triggerFallback(failed, now, "累计运动验证失败达到 ${failed.parameters.maxFailures} 轮")
        }
        return failed
    }

    private fun fault(s: Session, now: Long, reason: String): Session = s.copy(
        stage = Stage.TECHNICAL_FAULT, motionDeadlineMs = null, observationStartMs = null,
        soundPausedUntilMs = null, graceUntilMs = null, graceRemainingMs = null,
        sensorReason = reason, message = "暂时无法验证运动；未增加行为失败次数",
        roundStartedMs = if (s.intensity == Intensity.STRONG && s.t1Ms != null) s.roundStartedMs ?: now else s.roundStartedMs,
        roundId = if (s.intensity == Intensity.STRONG && s.t1Ms != null) s.roundId ?: "${s.id}:recovery:$now" else s.roundId,
    )

    private fun triggerFallback(s: Session, now: Long, reason: String): Session {
        if (s.fallbackTriggered) return s
        return s.copy(fallbackTriggered = true, fallbackReason = reason, fallbackAtMs = now,
            message = if (s.fallbackEnabled) "联系人兜底已触发；等待核查电话权限和线路" else
                "已到联系人兜底条件；本次未启用自动电话，继续本地提醒")
    }

    private fun grace(s: Session, now: Long): Session {
        if (s.intensity != Intensity.STRONG || s.stage != Stage.MONITORING || s.t1Ms == null ||
            s.graceUsed || !s.parameters.graceEnabled) return s
        return s.copy(graceUsed = true, graceUntilMs = now + s.parameters.graceMs,
            graceRemainingMs = max(0, (s.motionDeadlineMs ?: now) - now), motionDeadlineMs = null,
            message = "静止准备中；本次仅一次，不生成步行记录")
    }

    private fun pauseForCall(s: Session, now: Long): Session {
        if (s.stage == Stage.CALL_PAUSED || s.stage.terminal) return s
        val graceRemaining = s.graceUntilMs?.let { max(0, it - now) } ?: 0
        return s.copy(stage = Stage.CALL_PAUSED, resumeStage = s.stage,
            frozenMotionRemainingMs = s.motionDeadlineMs?.let { max(0, it - now) }
                ?: s.graceRemainingMs?.plus(graceRemaining),
            motionDeadlineMs = null, graceUntilMs = null, graceRemainingMs = null,
            soundPausedUntilMs = null, requireFreshMotion = true,
            message = "通话期间暂停本应用声音和运动期限；通话后继续检查")
    }

    private fun resumeCall(s: Session, now: Long): Session {
        if (s.stage != Stage.CALL_PAUSED) return s
        val resume = s.resumeStage ?: Stage.RINGING
        if (s.intensity == Intensity.MEDIUM && (resume == Stage.MONITORING || resume == Stage.MOTION_VERIFY)) {
            return s.copy(stage = Stage.MONITORING, observationStartMs = now,
                motionDeadlineMs = now + s.parameters.mediumWindowMs, mediumWindowPassed = false,
                sensorQuality = SensorQuality.INSUFFICIENT, healthyDataMs = 0, lastSensorMs = null,
                resumeStage = null, frozenMotionRemainingMs = null,
                message = "通话结束，当前运动窗口重新完整观察")
        }
        return s.copy(stage = resume, motionDeadlineMs = s.frozenMotionRemainingMs?.let { now + it },
            observationStartMs = if (resume == Stage.MONITORING || resume == Stage.MOTION_VERIFY) now else s.observationStartMs,
            sensorQuality = SensorQuality.INSUFFICIENT, healthyDataMs = 0, lastSensorMs = null,
            resumeStage = null, frozenMotionRemainingMs = null,
            message = "已恢复检查；结束前仍需新的有效运动")
    }

    private fun reboot(s: Session, e: Event.RestoreAfterReboot): Session {
        val oldNow = s.lastEventMs
        val delta = e.nowMs - oldNow - max(0, e.interruptedForMs)
        fun mapped(value: Long?): Long? = value?.plus(delta)
        // Keep logical elapsed ages but never reuse a previous boot's elapsedRealtime deadlines.
        val mapped = s.copy(t0Ms = s.t0Ms + delta, lastEventMs = e.nowMs,
            t1Ms = mapped(s.t1Ms), targetDeadlineMs = mapped(s.targetDeadlineMs),
            roundStartedMs = mapped(s.roundStartedMs), fallbackAtMs = mapped(s.fallbackAtMs),
            lastMotionEndMs = -1, lastMotionId = null, lastValidMotionMs = null,
            motionDeadlineMs = null, observationStartMs = null, soundPausedUntilMs = null,
            snoozeUntilMs = null, graceUntilMs = null, graceRemainingMs = null,
            resumeStage = null, frozenMotionRemainingMs = null, requireFreshMotion = true,
            lastSensorMs = null, healthyDataMs = 0, sensorQuality = SensorQuality.INSUFFICIENT,
            // No automatic phone after boot: the user must explicitly resume this interrupted session.
            callAvailability = CallAvailability.MANUAL_ONLY,
            callStatus = if (s.callClaimed) s.callStatus else if (s.fallbackEnabled) CallStatus.WAITING_USER else CallStatus.DISABLED,
            fallbackEnabled = false,
            stage = if (s.intensity == Intensity.NORMAL) Stage.RINGING else Stage.TECHNICAL_FAULT,
            sensorReason = "设备重启导致采集与单调时钟中断；请明确重新检测",
            message = "已恢复中断记录；没有补造运动或失败，自动电话已关闭")
        return mapped
    }

    private fun end(s: Session, stage: Stage, now: Long, message: String): Session = s.copy(
        stage = stage, lastEventMs = max(now, s.lastEventMs), motionDeadlineMs = null,
        observationStartMs = null, snoozeUntilMs = null, graceUntilMs = null, graceRemainingMs = null,
        soundPausedUntilMs = null, resumeStage = null, frozenMotionRemainingMs = null,
        message = message,
    )

    private fun grid(id: String, intensity: Intensity, round: Int): List<Int> {
        val count = when (intensity) { Intensity.NORMAL -> 0; Intensity.MEDIUM -> 10; Intensity.STRONG -> 20 }
        return (1..count).shuffled(Random(id.hashCode() xor (round * 0x45d9f3b)))
    }
}
