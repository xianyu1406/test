package cn.zhundian.core.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScheduleEngineTest {
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private fun sample() = Schedule(id = "morning", title = "起床", startLocal = "2026-10-08T07:00", endLocal = "2026-10-08T07:30")

    @Test fun `one off produces explicit frozen instants and expires after trigger`() {
        val schedule = ScheduleEngine.freezeOneOff(sample().copy(leadMinutes = 15))
        assertEquals(millis("2026-10-07T23:00:00Z"), schedule.fixedStartEpochMillis)
        val occurrence = ScheduleEngine.nextOccurrences(schedule, millis("2026-10-07T00:00:00Z")).single()
        assertEquals(millis("2026-10-07T22:45:00Z"), occurrence.triggerEpochMillis)
        assertTrue(ScheduleEngine.nextOccurrences(schedule, occurrence.triggerEpochMillis).isEmpty())
    }

    @Test fun `phone timezone cannot change a train instant`() {
        val original = TimeZone.getDefault()
        try {
            val schedule = ScheduleEngine.freezeOneOff(sample())
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val one = ScheduleEngine.occurrenceOn(schedule, "2026-10-08")!!
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            assertEquals(one, ScheduleEngine.occurrenceOn(schedule, "2026-10-08"))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun `weekly occurrences keep local time and inclusive end date`() {
        val schedule = sample().copy(weeklyDays = setOf(1, 4), repeatUntil = "2026-10-15")
        val next = ScheduleEngine.nextOccurrences(schedule, millis("2026-10-07T00:00:00Z"))
        assertEquals(listOf("2026-10-08", "2026-10-12", "2026-10-15"), next.map { it.dateKey })
        assertEquals(3, next.map { it.instanceId }.toSet().size)
        assertNull(ScheduleEngine.occurrenceOn(schedule, "2026-10-19"))
    }

    @Test fun `next preview excludes reminder already expired even if event has not started`() {
        val schedule = sample().copy(weeklyDays = setOf(4), leadMinutes = 60)
        val next = ScheduleEngine.nextOccurrences(schedule, millis("2026-10-07T22:30:00Z"))
        assertEquals("2026-10-15", next.first().dateKey)
    }

    @Test fun `cross midnight duration and lead can belong to previous day`() {
        val schedule = sample().copy(startLocal = "2026-10-08T00:10", endLocal = "2026-10-09T01:10", leadMinutes = 30, weeklyDays = setOf(4))
        val next = ScheduleEngine.nextOccurrences(schedule, millis("2026-10-06T00:00:00Z")).first()
        assertEquals(millis("2026-10-07T15:40:00Z"), next.triggerEpochMillis)
        assertEquals(25 * 3_600_000L, next.endEpochMillis - next.startEpochMillis)
    }

    @Test fun `spring gap moves local time forward by gap`() {
        val resolved = ScheduleEngine.resolve(LocalDateTime.parse("2026-03-08T02:30"), ZoneId.of("America/New_York"))
        assertEquals(millis("2026-03-08T07:30:00Z"), resolved)
    }

    @Test fun `fall overlap chooses earlier offset`() {
        val resolved = ScheduleEngine.resolve(LocalDateTime.parse("2026-11-01T01:30"), ZoneId.of("America/New_York"))
        assertEquals(millis("2026-11-01T05:30:00Z"), resolved)
    }

    @Test fun `spring gap that collapses end preserves nominal interval rather than losing reminder`() {
        val schedule = sample().copy(startLocal = "2026-03-01T02:45", endLocal = "2026-03-01T03:15",
            zoneId = "America/New_York", weeklyDays = setOf(7))
        val occurrence = ScheduleEngine.occurrenceOn(schedule, "2026-03-08")!!
        assertEquals(millis("2026-03-08T07:45:00Z"), occurrence.startEpochMillis)
        assertEquals(30 * 60_000L, occurrence.endEpochMillis - occurrence.startEpochMillis)
    }

    @Test fun `fixed instants remain valid when end wall time falls back earlier`() {
        val schedule = sample().copy(startLocal = "2026-11-01T01:59", endLocal = "2026-11-01T01:00",
            zoneId = "America/New_York", fixedStartEpochMillis = millis("2026-11-01T05:59:00Z"),
            fixedEndEpochMillis = millis("2026-11-01T06:00:00Z"))
        assertTrue(ScheduleEngine.validate(schedule).isEmpty())
        assertEquals(60_000L, ScheduleEngine.nextOccurrences(schedule, 0).single().let { it.endEpochMillis - it.startEpochMillis })
    }

    @Test fun `weekly wall clock follows rule zone through daylight saving`() {
        val schedule = sample().copy(startLocal = "2026-03-01T07:00", endLocal = "2026-03-01T08:00", zoneId = "America/New_York", weeklyDays = setOf(7))
        val next = ScheduleEngine.nextOccurrences(schedule, millis("2026-02-28T00:00:00Z"))
        assertEquals(listOf("2026-03-01T12:00:00Z", "2026-03-08T11:00:00Z", "2026-03-15T11:00:00Z").map(::millis), next.map { it.startEpochMillis })
    }

    @Test fun `skip affects only one instance and rebuild keeps exclusion`() {
        val schedule = sample().copy(weeklyDays = setOf(4))
        val skipped = ScheduleEngine.skip(schedule, "2026-10-08")
        val restored = Json.decodeFromString<Schedule>(Json.encodeToString(skipped))
        assertNull(ScheduleEngine.occurrenceOn(restored, "2026-10-08"))
        assertNotNull(ScheduleEngine.occurrenceOn(restored, "2026-10-15"))
        assertEquals("2026-10-15", ScheduleEngine.nextOccurrences(restored, millis("2026-10-07T00:00:00Z")).first().dateKey)
    }

    @Test fun `single modification preserves identity and other occurrences`() {
        val schedule = sample().copy(weeklyDays = setOf(4))
        val original = ScheduleEngine.occurrenceOn(schedule, "2026-10-08")!!
        val changed = ScheduleEngine.modifyOccurrence(schedule, OccurrenceOverride("2026-10-08",
            startEpochMillis = millis("2026-10-08T02:00:00Z"), endEpochMillis = millis("2026-10-08T03:00:00Z"), title = "晚点起床", intensity = ReminderIntensity.STRONG))
        val modified = ScheduleEngine.occurrenceOn(changed, "2026-10-08")!!
        assertEquals(original.instanceId, modified.instanceId)
        assertEquals("晚点起床", modified.title)
        assertEquals(ReminderIntensity.STRONG, modified.intensity)
        assertEquals(ScheduleEngine.occurrenceOn(schedule, "2026-10-15"), ScheduleEngine.occurrenceOn(changed, "2026-10-15"))
    }

    @Test fun `moved past occurrence still appears at future trigger`() {
        val schedule = ScheduleEngine.modifyOccurrence(sample().copy(weeklyDays = setOf(4)), OccurrenceOverride("2026-10-08",
            startEpochMillis = millis("2027-03-01T02:00:00Z"), endEpochMillis = millis("2027-03-01T03:00:00Z")))
        assertEquals("2026-10-08", ScheduleEngine.nextOccurrences(schedule, millis("2027-03-01T01:00:00Z")).first().dateKey)
    }

    @Test fun `moved future occurrence is ordered by actual trigger`() {
        val schedule = ScheduleEngine.modifyOccurrence(sample().copy(weeklyDays = setOf(4)), OccurrenceOverride("2027-10-07",
            startEpochMillis = millis("2026-10-08T01:00:00Z"), endEpochMillis = millis("2026-10-08T02:00:00Z")))
        val next = ScheduleEngine.nextOccurrences(schedule, millis("2026-10-07T23:30:00Z"))
        assertEquals("2027-10-07", next.first().dateKey)
    }

    @Test fun `split future leaves earlier series intact and uses independent identity`() {
        val original = sample().copy(weeklyDays = setOf(4))
        val split = ScheduleEngine.splitFuture(original, "2026-10-15", original.copy(
            startLocal = "2026-10-15T09:00", endLocal = "2026-10-15T10:00", title = "新规则"))
        assertEquals("2026-10-14", split.previous!!.repeatUntil)
        assertNull(ScheduleEngine.occurrenceOn(split.previous, "2026-10-15"))
        assertNotEquals(original.id, split.future.id)
        assertNotNull(ScheduleEngine.occurrenceOn(split.future, "2026-10-15"))
        assertEquals("起床", ScheduleEngine.occurrenceOn(split.previous, "2026-10-08")!!.title)
    }

    @Test fun `split on first day retires whole old series`() {
        val original = sample().copy(weeklyDays = setOf(4))
        assertNull(ScheduleEngine.splitFuture(original, "2026-10-08", original).previous)
    }

    @Test fun `delete one schedule cannot suppress another node`() {
        val one = sample()
        val two = sample().copy(id = "leave", title = "出门")
        assertTrue(ScheduleEngine.nextOccurrences(ScheduleEngine.delete(one), 0).isEmpty())
        assertEquals("出门", ScheduleEngine.nextOccurrences(two, 0).single().title)
    }

    @Test fun `importance and intensity remain independent`() {
        val important = sample().copy(important = true, intensity = ReminderIntensity.NORMAL)
        val strong = sample().copy(important = false, intensity = ReminderIntensity.STRONG)
        assertEquals(ReminderIntensity.NORMAL, ScheduleEngine.nextOccurrences(important, 0).single().intensity)
        assertFalse(ScheduleEngine.nextOccurrences(strong, 0).single().important)
    }

    @Test fun `invalid rules produce user readable validation`() {
        assertTrue(ScheduleEngine.validate(sample().copy(endLocal = "2026-10-08T06:00")).isNotEmpty())
        assertTrue(ScheduleEngine.validate(sample().copy(weeklyDays = setOf(8))).isNotEmpty())
        assertTrue(ScheduleEngine.validate(sample().copy(zoneId = "invalid/zone")).isNotEmpty())
    }

    @Test fun `long lead is included in rebuild lookahead`() {
        val schedule = sample().copy(weeklyDays = setOf(4), leadMinutes = 60 * 24 * 30)
        val next = ScheduleEngine.nextOccurrences(schedule, millis("2026-10-07T00:00:00Z"))
        assertEquals("2026-11-12", next.first().dateKey)
        assertTrue(next.all { it.triggerEpochMillis > millis("2026-10-07T00:00:00Z") })
    }

    @Test fun `repeat end is inclusive in rule timezone`() {
        val schedule = sample().copy(weeklyDays = setOf(4), repeatUntil = "2026-10-08")
        assertEquals(1, ScheduleEngine.nextOccurrences(schedule, 0).size)
    }

    @Test fun `calendar follows moved instance actual day while keeping original identity`() {
        val schedule = ScheduleEngine.modifyOccurrence(sample().copy(weeklyDays = setOf(4)), OccurrenceOverride("2026-10-08",
            startEpochMillis = millis("2026-10-09T02:00:00Z"), endEpochMillis = millis("2026-10-09T03:00:00Z")))
        assertTrue(ScheduleEngine.onCalendarDate(schedule, "2026-10-08").isEmpty())
        assertEquals("morning@2026-10-08", ScheduleEngine.onCalendarDate(schedule, "2026-10-09").single().instanceId)
    }

    @Test fun `calendar shows multi day overlap but not touching exclusive end`() {
        val schedule = sample().copy(startLocal = "2026-10-08T22:00", endLocal = "2026-10-10T00:00", weeklyDays = setOf(4))
        assertEquals("2026-10-08", ScheduleEngine.onCalendarDate(schedule, "2026-10-09").single().dateKey)
        assertTrue(ScheduleEngine.onCalendarDate(schedule, "2026-10-10").isEmpty())
    }

    @Test fun `calendar converts fixed trip instant to display zone`() {
        val schedule = sample().copy(startLocal = "2026-10-08T23:00", endLocal = "2026-10-08T23:30", zoneId = "America/New_York")
        assertTrue(ScheduleEngine.onCalendarDate(schedule, "2026-10-08", "Asia/Shanghai").isEmpty())
        assertEquals("2026-10-08", ScheduleEngine.onCalendarDate(schedule, "2026-10-09", "Asia/Shanghai").single().dateKey)
    }
}
