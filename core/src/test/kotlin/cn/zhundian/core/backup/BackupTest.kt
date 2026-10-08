package cn.zhundian.core.backup

import cn.zhundian.core.schedule.Schedule
import cn.zhundian.core.schedule.ScheduleEngine
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val schedule = Schedule(id = "train", title = "出门", startLocal = "2025-01-01T06:00", endLocal = "2025-01-01T06:15")
    @Test fun roundTripDoesNotContainSessionsOrPhoneBindings() {
        val text = BackupCodec.encode(listOf(schedule), 123)
        assertEquals(schedule, BackupCodec.decode(text).schedules.single())
        assertFalse(text.contains("contactNumber"))
        assertFalse(text.contains("callClaimed"))
    }
    @Test fun oldScheduleDoesNotProduceExpiredAlarms() {
        val imported = BackupCodec.decode(BackupCodec.encode(listOf(schedule), 123)).schedules.single()
        assertTrue(ScheduleEngine.nextOccurrences(imported, 1_800_000_000_000).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownVersion() {
        BackupCodec.decode(BackupCodec.encode(listOf(schedule), 123).replace("\"version\": 1", "\"version\": 2"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateIds() {
        BackupCodec.decode(BackupCodec.encode(listOf(schedule, schedule), 123))
    }
}
