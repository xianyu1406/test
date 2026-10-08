package cn.zhundian.core.schedule

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class ReminderIntensity { NORMAL, MEDIUM, STRONG }

/** Local date-times use ISO-8601. Weekdays use ISO numbering: Monday=1, Sunday=7. */
@Serializable
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startLocal: String,
    val endLocal: String,
    val zoneId: String = "Asia/Shanghai",
    val weeklyDays: Set<Int> = emptySet(),
    val repeatUntil: String? = null,
    val location: String = "",
    val notes: String = "",
    val important: Boolean = false,
    val enabled: Boolean = true,
    val leadMinutes: Int = 0,
    val intensity: ReminderIntensity = ReminderIntensity.NORMAL,
    val reminderParameters: Map<String, Long> = emptyMap(),
    val fixedStartEpochMillis: Long? = null,
    val fixedEndEpochMillis: Long? = null,
    val excludedDates: Set<String> = emptySet(),
    val overrides: List<OccurrenceOverride> = emptyList(),
)

/** Overrides are keyed by the original occurrence date, even when moved to another day. */
@Serializable
data class OccurrenceOverride(
    val dateKey: String,
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    val title: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val important: Boolean? = null,
    val leadMinutes: Int? = null,
    val intensity: ReminderIntensity? = null,
    val reminderParameters: Map<String, Long>? = null,
)

@Serializable
data class Occurrence(
    val instanceId: String,
    val scheduleId: String,
    val dateKey: String,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val triggerEpochMillis: Long,
    val zoneId: String,
    val location: String,
    val notes: String,
    val important: Boolean,
    val intensity: ReminderIntensity,
    val reminderParameters: Map<String, Long>,
)

data class ScheduleSplit(val previous: Schedule?, val future: Schedule)
