package cn.zhundian.core.schedule

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TravelTemplateTest {
    @Test fun `travel subtracts user durations over midnight and produces independent editable nodes`() {
        val nodes = TravelTemplate.build(TravelRequest(
            title = "上海出发", departureLocal = "2026-10-08T01:00", preparationMinutes = 30,
            travelMinutes = 60, earlyArrivalMinutes = 30, bufferMinutes = 15,
        ))
        assertEquals(listOf("2026-10-07T22:45", "2026-10-07T23:15", "2026-10-07T23:30", "2026-10-08T00:30"), nodes.map { it.startLocal })
        assertEquals(4, nodes.map { it.id }.toSet().size)
        assertTrue(nodes.none { "检票" in it.title })
        val modified = nodes[1].copy(title = "拿好证件出门")
        assertEquals("拿好证件出门", modified.title)
        assertEquals("上海出发 · 最晚出门", nodes[2].title)
        assertEquals(3, nodes.drop(1).flatMap { ScheduleEngine.nextOccurrences(it, 0) }.size)
    }

    @Test fun `elapsed travel durations are preserved over DST transition`() {
        val nodes = TravelTemplate.build(TravelRequest(
            departureLocal = "2026-03-08T04:00", zoneId = "America/New_York",
            preparationMinutes = 30, travelMinutes = 60, earlyArrivalMinutes = 0, bufferMinutes = 0,
        ))
        assertEquals(Instant.parse("2026-03-08T06:30:00Z").toEpochMilli(), nodes[0].fixedStartEpochMillis)
        assertEquals(Instant.parse("2026-03-08T08:00:00Z").toEpochMilli(), nodes[3].fixedStartEpochMillis)
    }

    @Test(expected = IllegalArgumentException::class) fun `negative durations are rejected`() {
        TravelTemplate.build(TravelRequest(departureLocal = "2026-10-08T09:00", preparationMinutes = -1,
            travelMinutes = 10, earlyArrivalMinutes = 10, bufferMinutes = 10))
    }
}
