package cn.zhundian.core.session

import kotlinx.serialization.Serializable

@Serializable
enum class Intensity { NORMAL, MEDIUM, STRONG }

@Serializable
enum class Stage {
    RINGING, SCHULTE, MOTION_VERIFY, MONITORING, SNOOZED, TECHNICAL_FAULT,
    CALL_PAUSED, COMPLETED, CANCELLED, ABORTED;
    val terminal: Boolean get() = this == COMPLETED || this == CANCELLED || this == ABORTED
}

@Serializable
enum class SensorQuality { NORMAL, INSUFFICIENT, UNAVAILABLE }

@Serializable
enum class CallAvailability { UNKNOWN, READY, NO_PERMISSION, NO_LINE, ONGOING_CALL, MANUAL_ONLY }

@Serializable
enum class CallStatus { DISABLED, ARMED, BLOCKED, WAITING_USER, CLAIMED_UNCERTAIN, SUBMITTED, FAILED }

enum class SoundMode { OFF, RING, TASK }

/** All durations are milliseconds; these are conservative, uncalibrated device-test defaults. */
@Serializable
data class ReminderParameters(
    val snoozeMs: Long = 120_000,
    val mediumWindowMs: Long = 120_000,
    val mediumWindows: Int = 3,
    val strongWindowMs: Long = 120_000,
    val initialVerificationMs: Long = 120_000,
    val noMotionMs: Long = 120_000,
    val sustainedTargetMs: Long = 600_000,
    val requiredStrongWindows: Int = 3,
    val maxFailures: Int = 3,
    val unconfirmedMs: Long = 480_000,
    val graceMs: Long = 120_000,
    val graceEnabled: Boolean = true,
    val soundPauseMs: Long = 30_000,
    val minimumFragmentMs: Long = 15_000,
    val sensorFreshnessMs: Long = 5_000,
    val requiredHealthyFraction: Double = 0.75,
) {
    init {
        require(snoozeMs >= 1_000 && mediumWindowMs >= 30_000 && mediumWindows >= 3)
        require(initialVerificationMs >= 30_000 && noMotionMs >= 30_000)
        require(strongWindowMs >= 30_000 && sustainedTargetMs >= 3 * strongWindowMs && requiredStrongWindows >= 3)
        require(maxFailures >= 1 && unconfirmedMs >= 60_000 && graceMs > 0)
        require(minimumFragmentMs >= 1_000 && sensorFreshnessMs > 0)
        require(requiredHealthyFraction in 0.1..1.0)
    }
}

/** Persist the entire value after every event, before invoking an external side effect. */
@Serializable
data class Session(
    val id: String,
    val intensity: Intensity,
    val parameters: ReminderParameters = ReminderParameters(),
    val stage: Stage = Stage.RINGING,
    val t0Ms: Long,
    val lastEventMs: Long,
    val grid: List<Int> = emptyList(),
    val targetNumber: Int = 1,
    val round: Int = 0,
    val wrongTaps: Int = 0,
    val failures: Int = 0,
    val t1Ms: Long? = null,
    val targetDeadlineMs: Long? = null,
    val motionDeadlineMs: Long? = null,
    val observationStartMs: Long? = null,
    val mediumWindow: Int = 0,
    val mediumWindowPassed: Boolean = false,
    val successfulWindows: Set<Int> = emptySet(),
    val lastMotionEndMs: Long = -1,
    val lastMotionId: String? = null,
    val lastValidMotionMs: Long? = null,
    val sensorQuality: SensorQuality = SensorQuality.INSUFFICIENT,
    val sensorReason: String = "等待传感器数据",
    val lastSensorMs: Long? = null,
    val healthyDataMs: Long = 0,
    val roundId: String? = null,
    val roundStartedMs: Long? = null,
    val fallbackEnabled: Boolean = false,
    val fallbackTriggered: Boolean = false,
    val fallbackReason: String? = null,
    val fallbackAtMs: Long? = null,
    val callAvailability: CallAvailability = CallAvailability.UNKNOWN,
    val callStatus: CallStatus = CallStatus.DISABLED,
    val callClaimed: Boolean = false,
    val callMessage: String? = null,
    val resumeStage: Stage? = null,
    val frozenMotionRemainingMs: Long? = null,
    val requireFreshMotion: Boolean = false,
    val graceUsed: Boolean = false,
    val graceUntilMs: Long? = null,
    val graceRemainingMs: Long? = null,
    val soundPausedUntilMs: Long? = null,
    val snoozeUntilMs: Long? = null,
    val message: String = "提醒已响铃",
) {
    val challengeSize: Int get() = when (intensity) {
        Intensity.NORMAL -> 0
        Intensity.MEDIUM -> 10
        Intensity.STRONG -> 20
    }

    val needsSensors: Boolean get() = stage == Stage.MOTION_VERIFY || stage == Stage.MONITORING

    /** Independent eight-minute deadlines continue while sound, motion, or calls are paused. */
    val nextFallbackDeadlineMs: Long? get() = if (intensity != Intensity.STRONG || stage.terminal || fallbackTriggered) null
        else listOfNotNull(
            if (t1Ms == null) t0Ms + parameters.unconfirmedMs else null,
            roundStartedMs?.plus(parameters.unconfirmedMs),
        ).minOrNull()

    val nextDeadlineMs: Long? get() = if (stage.terminal) null else
        listOfNotNull(motionDeadlineMs, snoozeUntilMs, graceUntilMs, nextFallbackDeadlineMs).minOrNull()

    fun canFinish(nowMs: Long): Boolean = intensity == Intensity.STRONG && stage == Stage.MONITORING &&
        !requireFreshMotion && targetDeadlineMs?.let { nowMs >= it } == true &&
        successfulWindows.size >= parameters.requiredStrongWindows &&
        lastValidMotionMs?.let { nowMs >= it && nowMs - it < parameters.noMotionMs } == true

    fun soundMode(nowMs: Long): SoundMode = when {
        stage.terminal || stage == Stage.CALL_PAUSED || stage == Stage.SNOOZED -> SoundMode.OFF
        soundPausedUntilMs?.let { nowMs < it } == true -> SoundMode.OFF
        stage == Stage.RINGING || stage == Stage.TECHNICAL_FAULT -> SoundMode.RING
        stage == Stage.SCHULTE -> SoundMode.TASK
        else -> SoundMode.OFF
    }
}

sealed interface Event {
    val nowMs: Long
    data class Tick(override val nowMs: Long) : Event
    data class OpenChallenge(override val nowMs: Long) : Event
    data class TapNumber(override val nowMs: Long, val number: Int) : Event
    data class PauseSound(override val nowMs: Long) : Event
    data class Snooze(override val nowMs: Long) : Event
    data class StopReminder(override val nowMs: Long) : Event
    data class EmergencyStop(override val nowMs: Long) : Event
    data class Cancel(override val nowMs: Long) : Event
    /** Only the production motion adapter may emit this event; replay belongs in tests. */
    data class Motion(override val nowMs: Long, val fragmentId: String, val startMs: Long, val endMs: Long) : Event
    /** Send periodically during active collection, including explicit permission/data faults. */
    data class SensorStatus(override val nowMs: Long, val quality: SensorQuality, val reason: String = "") : Event
    data class RetrySensors(override val nowMs: Long) : Event
    data class UseGrace(override val nowMs: Long) : Event
    data class ConfirmFinish(override val nowMs: Long) : Event
    data class FallbackAvailability(override val nowMs: Long, val availability: CallAvailability) : Event
    /** The serial coordinator must persist this result BEFORE calling TelecomManager. */
    data class ClaimCall(override val nowMs: Long) : Event
    data class CallSubmitted(override val nowMs: Long) : Event
    data class CallFailed(override val nowMs: Long, val reason: String) : Event
    data class ExternalCallStarted(override val nowMs: Long) : Event
    data class ResumeAfterCall(override val nowMs: Long) : Event
    /** Wall gap must be nonnegative and derived by the host; never carry old elapsed times across boots. */
    data class RestoreAfterReboot(override val nowMs: Long, val interruptedForMs: Long = 0) : Event
}
