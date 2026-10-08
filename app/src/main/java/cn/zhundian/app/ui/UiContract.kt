package cn.zhundian.app.ui

import cn.zhundian.core.schedule.ReminderIntensity
import cn.zhundian.core.schedule.Schedule
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Presentation contract; no Activity owns reminder lifecycle or progress. */
interface UiController {
    val state: StateFlow<UiState>
    fun saveSchedule(draft: ScheduleDraft, scope: EditScope)
    fun skipOccurrence(scheduleId: String, dateKey: String)
    fun deleteSchedule(scheduleId: String)
    fun setEnabled(scheduleId: String, enabled: Boolean)
    fun saveSettings(settings: UiSettings)
    fun preview(draft: ScheduleDraft, scope: EditScope = EditScope.ALL): UiPreview
    fun saveTravel(drafts: List<ScheduleDraft>)
    /** See SessionActions. TAP_NUMBER supplies number; every other action uses null. */
    fun sessionAction(action: String, number: Int? = null)
    fun openPermission(key: String)
    fun scheduleTest(intensity: ReminderIntensity)
    fun exportBackup()
    fun chooseImport()
    fun confirmImport()
    fun dismissImport()
    fun dismissMessage()
}

enum class EditScope { ALL, THIS, FUTURE }

object SessionActions {
    const val PAUSE_SOUND = "pause_sound"
    const val STOP_REMINDER = "stop_reminder"
    const val SNOOZE = "snooze"
    const val START_TASK = "start_task"
    const val TAP_NUMBER = "tap_number"
    const val EMERGENCY_STOP = "emergency_stop"
    const val CANCEL_OCCURRENCE = "cancel_occurrence"
    const val COMPLETE_SCHEDULE = "complete_schedule"
    const val RETRY_MOTION = "retry_motion"
    const val PREPARATION_GRACE = "preparation_grace"
    const val CONFIRM_FINISH = "confirm_finish"
    const val RESUME_AFTER_CALL = "resume_after_call"
    const val MANUAL_DIAL = "manual_dial"
    const val RESUME_SESSION = "resume_session"
    const val DOWNGRADE_NORMAL = "downgrade_normal"
}

@Serializable
data class UiSettings(
    val volume: Float = .85f,
    val taskVolume: Float = .25f,
    val tts: Boolean = false,
    val mediumWindowSeconds: Long = 120,
    val mediumWindows: Int = 3,
    val strongInitialSeconds: Long = 120,
    val strongIdleSeconds: Long = 120,
    val strongTargetSeconds: Long = 600,
    val strongMaxFailures: Int = 3,
    val unconfirmedSeconds: Long = 480,
    val graceEnabled: Boolean = true,
    val contactName: String = "",
    val contactNumber: String = "",
    val contactEnabled: Boolean = false,
    val contactConfirmed: Boolean = false,
) {
    fun parameterSnapshot(): Map<String, Long> = mapOf(
        "mediumWindowMs" to mediumWindowSeconds * 1000,
        "mediumWindows" to mediumWindows.toLong(),
        "initialVerificationMs" to strongInitialSeconds * 1000,
        "noMotionMs" to strongIdleSeconds * 1000,
        "sustainedTargetMs" to strongTargetSeconds * 1000,
        "maxFailures" to strongMaxFailures.toLong(),
        "unconfirmedMs" to unconfirmedSeconds * 1000,
        "graceEnabled" to if (graceEnabled) 1L else 0L,
        "volumePermille" to (volume * 1000).toLong(),
        "taskVolumePermille" to (taskVolume.coerceIn(.1f, 1f) * 1000).toLong(),
        "tts" to if (tts) 1L else 0L,
    )
}

/** A copy of numeric defaults is stored on each schedule, so later settings don't alter it. */
@Serializable
data class ScheduleDraft(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val startDate: String = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),
    val startTime: String = LocalTime.now(java.time.ZoneId.of("Asia/Shanghai")).withSecond(0).withNano(0).toString(),
    val endDate: String = startDate,
    val endTime: String = startTime,
    val zoneId: String = "Asia/Shanghai",
    val weeklyDays: Set<Int> = emptySet(),
    val repeatUntil: String = "",
    val location: String = "",
    val notes: String = "",
    val important: Boolean = false,
    val enabled: Boolean = true,
    val leadMinutes: Int = 0,
    val intensity: ReminderIntensity = ReminderIntensity.NORMAL,
    val reminderParameters: Map<String, Long> = emptyMap(),
    /** Original occurrence date used when modifying this occurrence or the series from here. */
    val sourceDateKey: String? = null,
) {
    fun toSchedule(): Schedule = Schedule(
        id = id, title = title.trim(), startLocal = "${startDate}T${startTime}",
        endLocal = "${endDate}T${endTime}", zoneId = zoneId, weeklyDays = weeklyDays,
        repeatUntil = repeatUntil.takeIf { it.isNotBlank() }, location = location, notes = notes,
        important = important, enabled = enabled, leadMinutes = leadMinutes,
        intensity = intensity, reminderParameters = reminderParameters,
    )

    companion object {
        fun from(schedule: Schedule, dateKey: String? = null): ScheduleDraft {
            val occurrence = dateKey?.let {
                runCatching { cn.zhundian.core.schedule.ScheduleEngine.occurrenceOn(schedule, it) }.getOrNull()
            }
            val zone = java.time.ZoneId.of(schedule.zoneId)
            val start = occurrence?.let { java.time.Instant.ofEpochMilli(it.startEpochMillis).atZone(zone).toLocalDateTime() }
                ?: java.time.LocalDateTime.parse(schedule.startLocal)
            val end = occurrence?.let { java.time.Instant.ofEpochMilli(it.endEpochMillis).atZone(zone).toLocalDateTime() }
                ?: java.time.LocalDateTime.parse(schedule.endLocal)
            return ScheduleDraft(
                id = schedule.id, title = occurrence?.title ?: schedule.title,
                startDate = start.toLocalDate().toString(), startTime = start.toLocalTime().toString(),
                endDate = end.toLocalDate().toString(), endTime = end.toLocalTime().toString(),
                zoneId = schedule.zoneId, weeklyDays = schedule.weeklyDays, repeatUntil = schedule.repeatUntil.orEmpty(),
                location = occurrence?.location ?: schedule.location, notes = occurrence?.notes ?: schedule.notes,
                important = occurrence?.important ?: schedule.important, enabled = schedule.enabled,
                leadMinutes = occurrence?.let { ((it.startEpochMillis - it.triggerEpochMillis) / 60_000).toInt() } ?: schedule.leadMinutes,
                intensity = occurrence?.intensity ?: schedule.intensity,
                reminderParameters = occurrence?.reminderParameters ?: schedule.reminderParameters,
                sourceDateKey = dateKey,
            )
        }
    }
}

data class UiPreview(val times: List<String> = emptyList(), val error: String? = null)
data class UiCapability(val key: String, val title: String, val detail: String, val granted: Boolean, val actionable: Boolean = true)
data class UiHistory(val timestamp: Long, val message: String)
data class UiImportPreview(val summary: String, val count: Int, val error: String? = null)

/** Derived exclusively from the durable session, including persisted challenge order. */
data class UiReminder(
    val id: String,
    val title: String,
    val intensity: ReminderIntensity,
    val stage: String,
    val detail: String,
    val taskNumbers: List<Int> = emptyList(),
    val nextNumber: Int = 1,
    val failures: Int = 0,
    val windowProgress: String = "",
    val nextCheckText: String = "",
    val fallbackText: String = "",
    val callStatus: String = "",
    val canFinish: Boolean = false,
    val canGrace: Boolean = false,
    val canRetry: Boolean = false,
    val canResume: Boolean = false,
    val canResumeCall: Boolean = false,
    val canManualDial: Boolean = false,
    val canDowngrade: Boolean = false,
    val terminal: Boolean = false,
)

data class UiState(
    val schedules: List<Schedule> = emptyList(),
    val session: UiReminder? = null,
    val settings: UiSettings = UiSettings(),
    val capabilities: List<UiCapability> = emptyList(),
    val history: List<UiHistory> = emptyList(),
    val importPreview: UiImportPreview? = null,
    val message: String? = null,
    val loading: Boolean = false,
)
