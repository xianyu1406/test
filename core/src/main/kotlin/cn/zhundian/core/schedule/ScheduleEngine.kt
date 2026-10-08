package cn.zhundian.core.schedule

import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Pure date calculations; never reads the phone's current time zone or clock. */
object ScheduleEngine {
    private const val MINUTE_MILLIS = 60_000L

    fun validate(schedule: Schedule): List<String> = buildList {
        if (schedule.id.isBlank()) add("安排编号不能为空")
        if (schedule.title.isBlank()) add("请输入标题")
        val start = runCatching { LocalDateTime.parse(schedule.startLocal) }.getOrNull()
        val end = runCatching { LocalDateTime.parse(schedule.endLocal) }.getOrNull()
        if (start == null) add("开始时间格式错误")
        if (end == null) add("结束时间格式错误")
        if (start != null && end != null && !end.isAfter(start) &&
            (schedule.weeklyDays.isNotEmpty() || schedule.fixedStartEpochMillis == null)) add("结束时间必须晚于开始时间（跨天请修改日期）")
        if (runCatching { ZoneId.of(schedule.zoneId) }.isFailure) add("时区无效")
        if (schedule.weeklyDays.any { it !in 1..7 }) add("重复星期必须为 1 至 7")
        if (schedule.leadMinutes !in 0..525_600) add("提前量须在 0 分钟至 365 天之间")
        val until = schedule.repeatUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (schedule.repeatUntil != null && until == null) add("重复结束日期格式错误")
        if (until != null && start != null && until.isBefore(start.toLocalDate())) add("重复结束日期早于开始日期")
        if ((schedule.fixedStartEpochMillis == null) != (schedule.fixedEndEpochMillis == null)) add("固定行程的开始和结束时刻必须同时保存")
        if (schedule.fixedStartEpochMillis != null && schedule.fixedEndEpochMillis != null &&
            schedule.fixedEndEpochMillis <= schedule.fixedStartEpochMillis) add("固定行程结束时刻必须晚于开始时刻")
        if (schedule.excludedDates.any { runCatching { LocalDate.parse(it) }.isFailure }) add("跳过日期格式错误")
        if (schedule.overrides.map { it.dateKey }.distinct().size != schedule.overrides.size) add("同一天存在重复的本次修改")
        schedule.overrides.forEach { override ->
            if (runCatching { LocalDate.parse(override.dateKey) }.isFailure) add("本次修改日期格式错误")
            if (override.title != null && override.title.isBlank()) add("本次修改标题不能为空")
            if (override.leadMinutes != null && override.leadMinutes !in 0..525_600) add("本次提前量无效")
            if ((override.startEpochMillis == null) != (override.endEpochMillis == null)) add("本次修改必须同时提供开始和结束时刻")
            if (override.startEpochMillis != null && override.endEpochMillis != null &&
                override.endEpochMillis <= override.startEpochMillis) add("本次修改的结束时间必须晚于开始时间")
        }
    }

    /** Gap: move forward by the gap. Overlap: choose the earlier offset. */
    fun resolve(local: LocalDateTime, zone: ZoneId): Long =
        local.atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()

    /** If moving a start through a DST gap collapses the interval, keep its local duration. */
    private fun resolveInterval(start: LocalDateTime, end: LocalDateTime, zone: ZoneId): Pair<Long, Long> {
        val startMillis = resolve(start, zone)
        val endMillis = resolve(end, zone)
        return startMillis to if (endMillis > startMillis) endMillis else startMillis + Duration.between(start, end).toMillis()
    }

    /** Persist one-off instants once, so later phone time-zone changes never move a train. */
    fun freezeOneOff(schedule: Schedule): Schedule {
        requireValid(schedule)
        if (schedule.weeklyDays.isNotEmpty()) return schedule.copy(fixedStartEpochMillis = null, fixedEndEpochMillis = null)
        if (schedule.fixedStartEpochMillis != null && schedule.fixedEndEpochMillis != null) return schedule
        val interval = resolveInterval(LocalDateTime.parse(schedule.startLocal), LocalDateTime.parse(schedule.endLocal), ZoneId.of(schedule.zoneId))
        return schedule.copy(
            fixedStartEpochMillis = interval.first,
            fixedEndEpochMillis = interval.second,
        )
    }

    /** Returns strictly future reminder triggers, never replaying expired reminders. */
    fun nextOccurrences(schedule: Schedule, nowEpochMillis: Long, limit: Int = 3): List<Occurrence> {
        require(limit in 0..1000) { "查询数量必须为 0 至 1000" }
        requireValid(schedule)
        if (!schedule.enabled || limit == 0) return emptyList()
        val start = LocalDateTime.parse(schedule.startLocal)
        if (schedule.weeklyDays.isEmpty()) return listOfNotNull(occurrenceOn(schedule, start.toLocalDate().toString()))
            .filter { it.triggerEpochMillis > nowEpochMillis }.take(limit)

        // Include moved exceptions separately: their original day can be years before/after now.
        val overrides = schedule.overrides.mapNotNull { occurrenceOn(schedule, it.dateKey) }
            .filter { it.triggerEpochMillis > nowEpochMillis }
        val overrideDates = schedule.overrides.map { it.dateKey }.toSet()
        val zone = ZoneId.of(schedule.zoneId)
        val earliestRelevant = Instant.ofEpochMilli(nowEpochMillis)
            .plusSeconds(schedule.leadMinutes * 60L).atZone(zone).toLocalDate().minusDays(2)
        var day = maxOf(start.toLocalDate(), earliestRelevant)
        val until = schedule.repeatUntil?.let(LocalDate::parse) ?: LocalDate.MAX.minusDays(2)
        val ordinary = mutableListOf<Occurrence>()
        while (!day.isAfter(until) && ordinary.size < limit) {
            if (day.toString() !in overrideDates) {
                occurrenceOn(schedule, day.toString())?.takeIf { it.triggerEpochMillis > nowEpochMillis }?.let(ordinary::add)
            }
            day = day.plusDays(1)
        }
        return (ordinary + overrides).distinctBy { it.instanceId }
            .sortedWith(compareBy<Occurrence> { it.triggerEpochMillis }.thenBy { it.instanceId }).take(limit)
    }

    /** Lookup keeps identity stable; cancelling one ID cannot cancel another reminder node. */
    fun occurrenceOn(schedule: Schedule, dateKey: String): Occurrence? {
        if (!schedule.enabled || dateKey in schedule.excludedDates) return null
        val date = LocalDate.parse(dateKey)
        val originalStart = LocalDateTime.parse(schedule.startLocal)
        val originalEnd = LocalDateTime.parse(schedule.endLocal)
        val isWeekly = schedule.weeklyDays.isNotEmpty()
        if (date.isBefore(originalStart.toLocalDate())) return null
        if (!isWeekly && date != originalStart.toLocalDate()) return null
        if (isWeekly && date.dayOfWeek.value !in schedule.weeklyDays) return null
        if (isWeekly && schedule.repeatUntil?.let { date.isAfter(LocalDate.parse(it)) } == true) return null
        val zone = ZoneId.of(schedule.zoneId)
        val daySpan = ChronoUnit.DAYS.between(originalStart.toLocalDate(), originalEnd.toLocalDate())
        val interval = if (isWeekly) resolveInterval(date.atTime(originalStart.toLocalTime()), date.plusDays(daySpan).atTime(originalEnd.toLocalTime()), zone)
            else if (schedule.fixedStartEpochMillis != null && schedule.fixedEndEpochMillis != null)
                schedule.fixedStartEpochMillis to schedule.fixedEndEpochMillis
            else resolveInterval(originalStart, originalEnd, zone)
        val rawStart = interval.first
        val rawEnd = interval.second
        val override = schedule.overrides.firstOrNull { it.dateKey == dateKey }
        val actualStart = override?.startEpochMillis ?: rawStart
        val actualEnd = override?.endEpochMillis ?: rawEnd
        // Defensive against malformed imported data when this direct lookup is used without validate.
        if (actualEnd <= actualStart) return null
        return Occurrence(
            instanceId = "${schedule.id}@$dateKey", scheduleId = schedule.id, dateKey = dateKey,
            title = override?.title ?: schedule.title,
            startEpochMillis = actualStart, endEpochMillis = actualEnd,
            triggerEpochMillis = actualStart - (override?.leadMinutes ?: schedule.leadMinutes) * MINUTE_MILLIS,
            zoneId = schedule.zoneId, location = override?.location ?: schedule.location,
            notes = override?.notes ?: schedule.notes, important = override?.important ?: schedule.important,
            intensity = override?.intensity ?: schedule.intensity,
            reminderParameters = override?.reminderParameters ?: schedule.reminderParameters,
        )
    }

    /** Calendar lookup uses actual instants, including moved exceptions and cross-midnight events. */
    fun onCalendarDate(schedule: Schedule, dateKey: String, calendarZoneId: String = "Asia/Shanghai"): List<Occurrence> {
        requireValid(schedule)
        if (!schedule.enabled) return emptyList()
        val visibleDate = LocalDate.parse(dateKey)
        val visibleZone = ZoneId.of(calendarZoneId)
        val from = visibleDate.atStartOfDay(visibleZone).toInstant().toEpochMilli()
        val until = visibleDate.plusDays(1).atStartOfDay(visibleZone).toInstant().toEpochMilli()
        fun overlaps(item: Occurrence) = item.startEpochMillis < until && item.endEpochMillis > from
        val originalStart = LocalDateTime.parse(schedule.startLocal)
        if (schedule.weeklyDays.isEmpty()) return listOfNotNull(occurrenceOn(schedule, originalStart.toLocalDate().toString())).filter(::overlaps)
        val originalEnd = LocalDateTime.parse(schedule.endLocal)
        val spanDays = ChronoUnit.DAYS.between(originalStart.toLocalDate(), originalEnd.toLocalDate()).coerceAtLeast(0)
        val ruleZone = ZoneId.of(schedule.zoneId)
        var date = maxOf(originalStart.toLocalDate(), Instant.ofEpochMilli(from).atZone(ruleZone).toLocalDate().minusDays(spanDays + 1))
        val last = Instant.ofEpochMilli(until).atZone(ruleZone).toLocalDate().plusDays(1)
        val candidates = schedule.overrides.mapNotNull { occurrenceOn(schedule, it.dateKey) }.toMutableList()
        while (!date.isAfter(last)) {
            occurrenceOn(schedule, date.toString())?.let(candidates::add)
            date = date.plusDays(1)
        }
        return candidates.distinctBy { it.instanceId }.filter(::overlaps).sortedBy { it.startEpochMillis }
    }

    fun skip(schedule: Schedule, dateKey: String): Schedule {
        requireNotNull(occurrenceOn(schedule, dateKey)) { "本次安排不存在或已跳过" }
        return schedule.copy(excludedDates = schedule.excludedDates + dateKey)
    }

    fun modifyOccurrence(schedule: Schedule, override: OccurrenceOverride): Schedule {
        val unskipped = schedule.copy(excludedDates = schedule.excludedDates - override.dateKey)
        requireNotNull(occurrenceOn(unskipped, override.dateKey)) { "本次安排不存在" }
        val changed = unskipped.copy(overrides = schedule.overrides.filterNot { it.dateKey == override.dateKey } + override)
        requireValid(changed)
        return changed
    }

    /** Previous series ends the day before the split; future series receives a fresh identity. */
    fun splitFuture(schedule: Schedule, fromDate: String, replacement: Schedule): ScheduleSplit {
        requireValid(schedule)
        requireValid(replacement)
        require(schedule.weeklyDays.isNotEmpty()) { "只有重复安排可以修改本次及之后" }
        val split = LocalDate.parse(fromDate)
        require(!split.isBefore(LocalDateTime.parse(schedule.startLocal).toLocalDate())) { "分割日期早于安排开始日期" }
        require(!LocalDateTime.parse(replacement.startLocal).toLocalDate().isBefore(split)) { "新规则必须从分割日期或之后开始" }
        val previous = if (split == LocalDateTime.parse(schedule.startLocal).toLocalDate()) null else {
            val previousUntil = minOf(schedule.repeatUntil?.let(LocalDate::parse) ?: split.minusDays(1), split.minusDays(1))
            schedule.copy(repeatUntil = previousUntil.toString(),
                excludedDates = schedule.excludedDates.filter { LocalDate.parse(it).isBefore(split) }.toSet(),
                overrides = schedule.overrides.filter { LocalDate.parse(it.dateKey).isBefore(split) })
        }
        val future = replacement.copy(
            id = if (replacement.id == schedule.id) UUID.randomUUID().toString() else replacement.id,
            excludedDates = replacement.excludedDates.filter { !LocalDate.parse(it).isBefore(split) }.toSet(),
            overrides = replacement.overrides.filter { !LocalDate.parse(it.dateKey).isBefore(split) },
            fixedStartEpochMillis = null, fixedEndEpochMillis = null,
        )
        return ScheduleSplit(previous, freezeOneOff(future))
    }

    /** Persistence may remove the row instead; disabling also makes schedule rebuilding safe. */
    fun delete(schedule: Schedule): Schedule = schedule.copy(enabled = false)

    private fun requireValid(schedule: Schedule) {
        val errors = validate(schedule)
        require(errors.isEmpty()) { errors.joinToString("；") }
    }
}
