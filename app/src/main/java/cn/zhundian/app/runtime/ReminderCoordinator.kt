package cn.zhundian.app.runtime

import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import cn.zhundian.app.ZhundianApplication
import cn.zhundian.app.data.*
import cn.zhundian.app.platform.*
import cn.zhundian.app.ui.*
import cn.zhundian.core.backup.Backup
import cn.zhundian.core.call.DurableCallGate
import cn.zhundian.core.motion.MotionReport
import cn.zhundian.core.motion.MotionStatus
import cn.zhundian.core.schedule.*
import cn.zhundian.core.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** One process-wide serial owner. A durable write precedes every external call request. */
class ReminderCoordinator(private val app: ZhundianApplication) {
    private val db get() = app.database
    private val dao get() = db.dao()
    private val mutex = Mutex()
    private val callGate = DurableCallGate()
    private val stopRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    var immediateSilence: (() -> Unit)? = null
    fun isStopRequested(id: String) = id in stopRequests
    fun requestStop(action: String) {
        if (action in setOf(SessionActions.EMERGENCY_STOP, SessionActions.CANCEL_OCCURRENCE, SessionActions.COMPLETE_SCHEDULE)) {
            mutableActive.value?.id?.let { stopRequests.add(it) }
            immediateSilence?.invoke()
        }
    }
    private val alarms = AlarmScheduler(app)
    private var loaded = false
    private val deliveredHere = mutableSetOf<String>()
    private var current: SessionRow? = null
    private val mutableActive = MutableStateFlow<SessionRow?>(null)
    val active = mutableActive.asStateFlow()
    private fun now() = SystemClock.elapsedRealtime()
    private fun wall() = System.currentTimeMillis()
    private fun boot() = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun decode(row: SessionRow) = AppJson.decodeFromString<Session>(row.sessionJson)
    private fun occurrence(row: SessionRow) = AppJson.decodeFromString<Occurrence>(row.occurrenceJson)
    suspend fun log(message: String) { dao.log(HistoryRow(at = wall(), message = message)); dao.trimHistory() }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        current = dao.activeSession()
        current?.let { row ->
            var s = decode(row)
            if (row.bootCount != boot() || now() < s.lastEventMs) {
                s = SessionEngine.reduce(s, Event.RestoreAfterReboot(now(), (wall() - row.savedAtWall).coerceAtLeast(0)))
                log("设备重启：会话等待明确恢复，本次自动电话已关闭；没有补记步行或失败")
            } else if (s.needsSensors) {
                s = SessionEngine.reduce(s, Event.SensorStatus(now(), SensorQuality.UNAVAILABLE, "进程曾中断，请重新检测运动"))
            }
            persistLocked(s)
        }
        mutableActive.value = current
    }

    private suspend fun persistLocked(s: Session) {
        val row = current ?: return
        val old = decode(row)
        val updated = row.copy(sessionJson = AppJson.encodeToString(Session.serializer(), s), bootCount = boot(), savedAtWall = wall(), active = !s.stage.terminal)
        dao.putSession(updated)
        if (s.stage != old.stage || s.failures != old.failures || s.callStatus != old.callStatus || s.message != old.message && s.stage.terminal)
            log("${occurrence(row).title}：${s.message}；失败 ${s.failures} 轮；电话 ${callLabel(s.callStatus)}")
        current = if (s.stage.terminal) null else updated
        mutableActive.value = current
        if (s.stage.terminal) {
            alarms.cancel("session:${s.id}")
            dao.occurrence(s.id)?.let { dao.putOccurrence(it.copy(status = s.stage.name)) }
        } else if (s.stage == Stage.SNOOZED && s.snoozeUntilMs != old.snoozeUntilMs) {
            val result = alarms.schedule("session:${s.id}", wall() + ((s.snoozeUntilMs ?: now()) - now()).coerceAtLeast(1))
            log(if (result.scheduled) "已注册120秒后的用户可见闹钟" else "稍后闹钟未注册：${result.reason}；活动服务仍计时")
        }
    }

    suspend fun rebuild(reason: String = "重新检查未来提醒"): String = mutex.withLock {
        loadLocked()
        rebuildLocked().also { log("$reason；$it") }
    }
    private suspend fun rebuildLocked(): String {
        val time = wall()
        val expected = dao.schedules().flatMap { row ->
            runCatching { ScheduleEngine.nextOccurrences(AppJson.decodeFromString<Schedule>(row.json), time, 3) }.getOrElse { emptyList() }
        }.associateBy { it.instanceId }
        val existing = dao.occurrences().associateBy { it.id }
        existing.values.filter { it.status == "PENDING" && it.id !in expected && !(it.id.startsWith("test-") && it.triggerAt > time) }.forEach {
            alarms.cancel(it.id)
            dao.putOccurrence(it.copy(status = if (it.triggerAt <= time) "MISSED" else "WITHDRAWN", schedulingMessage = "已撤销或已过期，不补触发"))
        }
        var success = 0
        var blocked = 0
        expected.values.forEach { item ->
            val old = existing[item.instanceId]
            if (old != null && old.status !in setOf("PENDING", "WITHDRAWN")) return@forEach
            val result = alarms.schedule(item.instanceId, item.triggerEpochMillis)
            dao.putOccurrence(OccurrenceRow(item.instanceId, item.scheduleId, item.triggerEpochMillis,
                AppJson.encodeToString(Occurrence.serializer(), item), "PENDING", result.reason))
            if (result.scheduled) success++ else blocked++
        }
        return when {
            blocked > 0 -> "安排已保存；$success 个提醒已调度，$blocked 个未调度，请检查精确闹钟权限"
            success > 0 -> "安排已保存；$success 个未来提醒已成功调度"
            else -> "安排已保存；没有未来触发时间，未注册闹钟"
        }
    }

    suspend fun saveSchedule(draft: ScheduleDraft, scope: EditScope): String = mutex.withLock {
        loadLocked()
        val old = dao.schedule(draft.id)?.let { AppJson.decodeFromString<Schedule>(it.json) }
        val replacement = ScheduleEngine.freezeOneOff(draft.toSchedule())
        parameters(replacement.reminderParameters, app.settings.flow.first()) // reject invalid configuration before storage
        db.withTransaction {
            when {
                old != null && scope == EditScope.THIS && draft.sourceDateKey != null -> {
                    val fixed = ScheduleEngine.freezeOneOff(replacement.copy(weeklyDays = emptySet()))
                    val override = OccurrenceOverride(draft.sourceDateKey,
                        fixed.fixedStartEpochMillis, fixed.fixedEndEpochMillis, replacement.title,
                        replacement.location, replacement.notes, replacement.important, replacement.leadMinutes,
                        replacement.intensity, replacement.reminderParameters)
                    putSchedule(ScheduleEngine.modifyOccurrence(old, override))
                }
                old != null && scope == EditScope.FUTURE && draft.sourceDateKey != null -> {
                    val split = ScheduleEngine.splitFuture(old, draft.sourceDateKey, replacement)
                    val previous = split.previous
                    if (previous == null) dao.deleteSchedule(old.id) else putSchedule(previous)
                    putSchedule(split.future)
                }
                else -> putSchedule(replacement.copy(excludedDates = old?.excludedDates.orEmpty(), overrides = old?.overrides.orEmpty()))
            }
        }
        cancelAffectedLocked(draft.id, if (scope == EditScope.ALL) null else draft.sourceDateKey, scope == EditScope.FUTURE)
        // An explicit edit moving an instance into the future can re-arm it, preserving any
        // durable automatic-call claim when the same instance is activated again.
        val changed = dao.schedule(draft.id)?.let { AppJson.decodeFromString<Schedule>(it.json) }
        changed?.let { edited ->
            ScheduleEngine.nextOccurrences(edited, wall(), 3).forEach { item ->
                val affected = scope == EditScope.ALL || draft.sourceDateKey == item.dateKey ||
                    scope == EditScope.FUTURE && draft.sourceDateKey?.let { item.dateKey >= it } == true
                if (affected) dao.occurrence(item.instanceId)?.takeIf { it.status in setOf("CANCELLED", "ABORTED", "MISSED") }?.let {
                    dao.putOccurrence(it.copy(status = "WITHDRAWN")); stopRequests.remove(item.instanceId)
                }
            }
        }
        rebuildLocked()
    }
    private suspend fun putSchedule(s: Schedule) = dao.putSchedule(ScheduleRow(s.id, AppJson.encodeToString(Schedule.serializer(), s)))
    suspend fun saveTravel(drafts: List<ScheduleDraft>): String = mutex.withLock {
        val defaults = app.settings.flow.first()
        val schedules = drafts.map { ScheduleEngine.freezeOneOff(it.toSchedule()).also { item -> parameters(item.reminderParameters, defaults) } }
        db.withTransaction { schedules.forEach { putSchedule(it) } }
        rebuildLocked()
    }
    private suspend fun cancelAffectedLocked(scheduleId: String, dateKey: String?, future: Boolean = false) {
        current?.let { row ->
            val o = occurrence(row)
            if (o.scheduleId == scheduleId && (dateKey == null || o.dateKey == dateKey || future && o.dateKey >= dateKey))
                persistLocked(SessionEngine.reduce(decode(row), Event.Cancel(now())))
        }
        dao.occurrences().filter { it.scheduleId == scheduleId && it.status == "QUEUED" }.forEach { row ->
            val o = AppJson.decodeFromString<Occurrence>(row.json)
            if (dateKey == null || o.dateKey == dateKey || future && o.dateKey >= dateKey) dao.putOccurrence(row.copy(status = "CANCELLED"))
        }
    }
    suspend fun skip(id: String, date: String): String = mutex.withLock {
        loadLocked()
        dao.schedule(id)?.let { putSchedule(ScheduleEngine.skip(AppJson.decodeFromString(it.json), date)) }
        cancelAffectedLocked(id, date)
        rebuildLocked()
    }
    suspend fun delete(id: String): String = mutex.withLock {
        loadLocked(); dao.deleteSchedule(id); cancelAffectedLocked(id, null); rebuildLocked()
    }
    suspend fun enable(id: String, enabled: Boolean): String = mutex.withLock {
        loadLocked()
        dao.schedule(id)?.let { putSchedule(AppJson.decodeFromString<Schedule>(it.json).copy(enabled = enabled)) }
        if (!enabled) cancelAffectedLocked(id, null)
        rebuildLocked()
    }
    suspend fun restoreBackup(backup: Backup): String = mutex.withLock {
        loadLocked()
        val defaults = app.settings.flow.first()
        backup.schedules.forEach { item ->
            parameters(item.reminderParameters, defaults)
            item.overrides.forEach { override -> parameters(override.reminderParameters ?: item.reminderParameters, defaults) }
        }
        // A backup never re-arms a contact, even if that contact exists on the destination.
        app.settings.save(app.settings.flow.first().copy(contactEnabled = false, contactConfirmed = false))
        current?.let { row -> persistLocked(decode(row).copy(fallbackEnabled = false, callStatus = if (decode(row).callClaimed) decode(row).callStatus else CallStatus.DISABLED)) }
        db.withTransaction { backup.schedules.forEach { putSchedule(ScheduleEngine.freezeOneOff(it.copy(id = UUID.randomUUID().toString()))) } }
        log("导入 ${backup.schedules.size} 个安排为独立副本；电话兜底已关闭；过期提醒不补触发")
        rebuildLocked()
    }

    suspend fun alarm(id: String) = mutex.withLock {
        loadLocked()
        if (id.startsWith("session:")) {
            current?.takeIf { it.id == id.removePrefix("session:") }?.let { persistLocked(SessionEngine.reduce(decode(it), Event.Tick(now()))) }
            return@withLock
        }
        val row = dao.occurrence(id) ?: return@withLock
        if (row.status != "PENDING") return@withLock
        val lateness = wall() - row.triggerAt
        if (lateness < -1_000) return@withLock // stale delivery after editing this alarm
        if (lateness > 5 * 60_000) {
            dao.putOccurrence(row.copy(status = "MISSED")); log("过期提醒未补触发：超过5分钟，未创建电话会话")
        } else {
            dao.putOccurrence(row.copy(status = "QUEUED")); deliveredHere.add(id); log("实际收到闹钟：${AppJson.decodeFromString<Occurrence>(row.json).title}，进入提醒队列")
        }
        rebuildLocked()
    }
    suspend fun test(intensity: ReminderIntensity): String = mutex.withLock {
        require(alarms.canScheduleExact()) { "请先允许精确闹钟，再运行10秒测试提醒" }
        val at = wall() + 10_000
        val id = "test-${UUID.randomUUID()}"
        val o = Occurrence(id, id, "test", "测试提醒（不会自动拨号）", at, at + 60_000, at, "Asia/Shanghai", "", "", false, intensity, mapOf("testMode" to 1L))
        val result = alarms.schedule(id, at)
        dao.putOccurrence(OccurrenceRow(id, id, at, AppJson.encodeToString(Occurrence.serializer(), o), schedulingMessage = result.reason))
        if (result.scheduled) "测试提醒已注册，10秒后触发；自动电话始终禁用" else result.reason
    }
    suspend fun pump(): SessionRow? = mutex.withLock {
        loadLocked()
        if (current == null) {
            var next = dao.nextQueued()
            while (next != null && next.id !in deliveredHere && wall() - next.triggerAt > 5 * 60_000) {
                dao.putOccurrence(next.copy(status = "MISSED"))
                log("队列中的过期提醒已跳过，不补响铃或自动电话")
                next = dao.nextQueued()
            }
            if (next != null) {
                val o = AppJson.decodeFromString<Occurrence>(next.json)
                val defaults = app.settings.flow.first()
                val settings = snapshotSettings(defaults, o.reminderParameters)
                val fallback = settings.contactEnabled && settings.contactConfirmed && settings.contactNumber.isNotBlank() && o.reminderParameters["testMode"] != 1L
                var s = SessionEngine.create(o.instanceId, Intensity.valueOf(o.intensity.name), now(), parameters(o.reminderParameters, settings), fallback)
                dao.session(o.instanceId)?.let { previous ->
                    val prior = decode(previous)
                    if (prior.callClaimed) s = s.copy(callClaimed = true, callStatus = prior.callStatus,
                        fallbackEnabled = false, callMessage = "本实例曾领取过电话请求资格，重新编辑不重拨")
                }
                val row = SessionRow(o.instanceId, next.json, AppJson.encodeToString(Session.serializer(), s), AppJson.encodeToString(UiSettings.serializer(), settings), boot(), wall())
                db.withTransaction { dao.putSession(row); dao.putOccurrence(next.copy(status = "ACTIVE")) }
                current = row; mutableActive.value = row
                log("${o.title}：首次实际启动响铃会话；电话兜底${if (fallback) "已启用" else "关闭"}")
            }
        }
        current?.let { persistLocked(SessionEngine.reduce(decode(it), Event.Tick(now()))) }
        current
    }
    suspend fun motion(report: MotionReport) = mutex.withLock {
        val row = current ?: return@withLock
        var s = decode(row)
        if (!s.needsSensors) return@withLock
        val quality = when { report.unavailable -> SensorQuality.UNAVAILABLE; report.status == MotionStatus.INSUFFICIENT_DATA -> SensorQuality.INSUFFICIENT; else -> SensorQuality.NORMAL }
        s = SessionEngine.reduce(s, Event.SensorStatus(now(), quality, report.reason))
        report.fragment?.let { f -> s = SessionEngine.reduce(s, Event.Motion(now(), f.id, f.startElapsedMs, f.endElapsedMs)) }
        persistLocked(s)
    }
    suspend fun action(action: String, number: Int? = null) {
        requestStop(action)
        mutex.withLock {
        loadLocked()
        val row = current ?: return@withLock
        val s = decode(row)
        val time = now()
        val event: Event? = when (action) {
            SessionActions.PAUSE_SOUND -> Event.PauseSound(time)
            SessionActions.STOP_REMINDER -> Event.StopReminder(time)
            SessionActions.SNOOZE -> Event.Snooze(time)
            SessionActions.START_TASK -> Event.OpenChallenge(time)
            SessionActions.TAP_NUMBER -> number?.let { Event.TapNumber(time, it) }
            SessionActions.EMERGENCY_STOP -> Event.EmergencyStop(time)
            SessionActions.CANCEL_OCCURRENCE -> Event.Cancel(time)
            SessionActions.RETRY_MOTION, SessionActions.RESUME_SESSION -> Event.RetrySensors(time)
            SessionActions.PREPARATION_GRACE -> Event.UseGrace(time)
            SessionActions.CONFIRM_FINISH -> Event.ConfirmFinish(time)
            SessionActions.RESUME_AFTER_CALL -> Event.ResumeAfterCall(time)
            SessionActions.MANUAL_DIAL -> Event.ExternalCallStarted(time)
            SessionActions.COMPLETE_SCHEDULE -> Event.Cancel(time)
            else -> null
        }
        if (action == SessionActions.DOWNGRADE_NORMAL) {
            // Explicit weaker path terminates this verification rather than claiming it passed.
            persistLocked(s.copy(intensity = Intensity.NORMAL, stage = Stage.RINGING, fallbackEnabled = false,
                motionDeadlineMs = null, callStatus = if (s.callClaimed) s.callStatus else CallStatus.DISABLED,
                message = "用户选择普通档：不再验证运动，本次自动电话关闭"))
            log("用户明确退回普通档；未记录运动成功")
        } else if (event != null) {
            persistLocked(SessionEngine.reduce(s, event))
            if (action == SessionActions.COMPLETE_SCHEDULE) {
                val o = occurrence(row)
                dao.schedule(o.scheduleId)?.let { putSchedule(ScheduleEngine.skip(AppJson.decodeFromString(it.json), o.dateKey)) }
                dao.occurrence(o.instanceId)?.let { dao.putOccurrence(it.copy(status = "COMPLETED_SCHEDULE")) }
                log("用户明确完成整个本次安排；独立出门等节点保留")
                rebuildLocked()
            }
        }
    }
    }
    suspend fun phoneState(state: ObservedCallState, endedObservedCall: Boolean) = mutex.withLock {
        current?.let { row ->
            val event = when {
                state == ObservedCallState.RINGING || state == ObservedCallState.OFF_HOOK -> Event.ExternalCallStarted(now())
                state == ObservedCallState.IDLE && endedObservedCall -> Event.ResumeAfterCall(now())
                else -> null
            }
            if (event != null) {
                val message = when (state) {
                    ObservedCallState.RINGING -> "系统报告电话响铃，本地检查暂停"
                    ObservedCallState.OFF_HOOK -> "系统报告线路离钩；不等于确认对方接听"
                    ObservedCallState.IDLE -> "系统报告线路空闲，恢复检查；结束前仍需新运动证据"
                    ObservedCallState.UNKNOWN -> "通话状态未知，需明确选择通话后继续检查"
                }
                persistLocked(SessionEngine.reduce(decode(row), event).copy(callMessage = message))
                log(message)
            }
        }
    }

    /** Called only by foreground service. No preview/test may call this gateway. */
    suspend fun attemptFallback(gateway: CallGateway, pauseAudio: () -> Unit) = mutex.withLock {
        val row = current ?: return@withLock
        var latest = decode(row)
        if (latest.stage.terminal || latest.callClaimed || !latest.fallbackEnabled) return@withLock
        val settings = AppJson.decodeFromString<UiSettings>(row.settingsJson)
        val global = app.settings.flow.first()
        val consentValid = settings.contactConfirmed && settings.contactEnabled &&
            global.contactEnabled && global.contactConfirmed &&
            settings.contactNumber == global.contactNumber &&
            occurrence(row).reminderParameters["testMode"] != 1L
        if (!consentValid) {
            // Revocation affects this running snapshot permanently, even if defaults are enabled
            // again later. A new reminder can take a newly confirmed contact snapshot.
            current = row.copy(settingsJson = AppJson.encodeToString(UiSettings.serializer(),
                settings.copy(contactEnabled = false, contactConfirmed = false)))
            persistLocked(latest.copy(fallbackEnabled = false, callStatus = CallStatus.DISABLED,
                callMessage = "联系人设置已关闭或变更；本次尚未提交的自动电话计划已取消"))
            return@withLock
        }
        if (!latest.fallbackTriggered) return@withLock
        var eligibilityMessage = "电话状态未知；等待用户拨打"
        var submissionMessage: String? = null
        callGate.attempt(
            load = {
                val saved = current?.takeIf { it.id == row.id }?.let(::decode) ?: latest
                if (row.id in stopRequests) SessionEngine.reduce(saved, Event.EmergencyStop(now())) else saved
            },
            save = { proposed ->
                val enriched = when {
                    !proposed.callClaimed && proposed.callAvailability != CallAvailability.READY ->
                        proposed.copy(callMessage = eligibilityMessage)
                    proposed.callStatus == CallStatus.FAILED && submissionMessage != null ->
                        proposed.copy(callMessage = submissionMessage)
                    else -> proposed
                }
                persistLocked(enriched)
                latest = enriched
            },
            eligibility = {
                val check = gateway.preflight(settings.contactNumber)
                eligibilityMessage = check.reason
                when (check.code) {
                    CallEligibilityCode.READY -> CallAvailability.READY
                    CallEligibilityCode.ALREADY_IN_CALL -> CallAvailability.ONGOING_CALL
                    CallEligibilityCode.MISSING_PERMISSION -> CallAvailability.NO_PERMISSION
                    CallEligibilityCode.NO_LINE, CallEligibilityCode.LINE_NOT_SELECTED,
                    CallEligibilityCode.NO_TELEPHONY -> CallAvailability.NO_LINE
                    CallEligibilityCode.STATE_UNKNOWN -> CallAvailability.UNKNOWN
                    CallEligibilityCode.INVALID_NUMBER -> CallAvailability.MANUAL_ONLY
                }
            },
            submit = {
                val result = gateway.submit(settings.contactNumber, true)
                submissionMessage = result.reason
                if (!result.apiInvoked) {
                    // This explicit result proves placeCall was never invoked. Releasing the
                    // claim is safe here only; exceptions and process recovery never release it.
                    val blocked = when (result.blockedEligibility?.code) {
                        CallEligibilityCode.ALREADY_IN_CALL -> CallAvailability.ONGOING_CALL
                        CallEligibilityCode.MISSING_PERMISSION -> CallAvailability.NO_PERMISSION
                        CallEligibilityCode.NO_LINE, CallEligibilityCode.LINE_NOT_SELECTED,
                        CallEligibilityCode.NO_TELEPHONY -> CallAvailability.NO_LINE
                        CallEligibilityCode.INVALID_NUMBER -> CallAvailability.MANUAL_ONLY
                        else -> CallAvailability.UNKNOWN
                    }
                    val saved = current?.takeIf { it.id == row.id }?.let(::decode) ?: latest
                    latest = SessionEngine.reduce(saved, Event.ResumeAfterCall(now())).copy(
                        callClaimed = false, callAvailability = blocked,
                        callStatus = if (blocked == CallAvailability.MANUAL_ONLY) CallStatus.WAITING_USER else CallStatus.BLOCKED,
                        callMessage = result.reason,
                    )
                    persistLocked(latest)
                }
                result.submitted
            },
            now = ::now,
            beforeSubmit = {
                val confirmed = app.settings.flow.first()
                if (!confirmed.contactEnabled || !confirmed.contactConfirmed ||
                    confirmed.contactNumber != settings.contactNumber) {
                    current?.let { latestRow ->
                        current = latestRow.copy(settingsJson = AppJson.encodeToString(UiSettings.serializer(),
                            settings.copy(contactEnabled = false, contactConfirmed = false)))
                        latest = SessionEngine.reduce(decode(latestRow), Event.ResumeAfterCall(now())).copy(
                            fallbackEnabled = false, callStatus = CallStatus.DISABLED,
                            callMessage = "提交前联系人授权已撤销，本次自动电话计划已取消")
                        persistLocked(latest)
                    }
                } else pauseAudio()
            },
        )
    }

    companion object {
        fun parameters(p: Map<String, Long>, s: UiSettings) = ReminderParameters(
            mediumWindowMs = p["mediumWindowMs"] ?: s.mediumWindowSeconds * 1000,
            mediumWindows = (p["mediumWindows"] ?: s.mediumWindows.toLong()).toInt(),
            initialVerificationMs = p["initialVerificationMs"] ?: s.strongInitialSeconds * 1000,
            noMotionMs = p["noMotionMs"] ?: s.strongIdleSeconds * 1000,
            sustainedTargetMs = p["sustainedTargetMs"] ?: s.strongTargetSeconds * 1000,
            maxFailures = (p["maxFailures"] ?: s.strongMaxFailures.toLong()).toInt(),
            unconfirmedMs = p["unconfirmedMs"] ?: s.unconfirmedSeconds * 1000,
            graceEnabled = p["graceEnabled"]?.let { it != 0L } ?: s.graceEnabled,
        )
        fun snapshotSettings(s: UiSettings, p: Map<String, Long>) = s.copy(
            volume = p["volumePermille"]?.let { (it / 1000f).coerceIn(.1f, 1f) } ?: s.volume,
            taskVolume = p["taskVolumePermille"]?.let { (it / 1000f).coerceIn(.1f, 1f) } ?: s.taskVolume,
            tts = p["tts"]?.let { it != 0L } ?: s.tts,
        )
        fun callLabel(status: CallStatus) = when (status) {
            CallStatus.DISABLED -> "未启用"
            CallStatus.ARMED -> "已启用，未触发"
            CallStatus.BLOCKED -> "条件不足，等待用户拨打"
            CallStatus.WAITING_USER -> "等待用户拨打"
            CallStatus.CLAIMED_UNCERTAIN -> "请求资格已领取，提交状态不确定，不重拨"
            CallStatus.SUBMITTED -> "请求已提交，接通状态未知"
            CallStatus.FAILED -> "请求失败，不自动重拨"
        }
    }
}
