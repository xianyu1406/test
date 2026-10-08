package cn.zhundian.core.backup

import cn.zhundian.core.schedule.Schedule
import cn.zhundian.core.schedule.ScheduleEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only user schedules travel across devices. Sessions, call claims and credentials never do. */
@Serializable
data class Backup(val version: Int = 1, val exportedAt: Long, val schedules: List<Schedule>)

object BackupCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    fun encode(schedules: List<Schedule>, now: Long): String = json.encodeToString(Backup.serializer(), Backup(exportedAt = now, schedules = schedules))
    fun decode(text: String): Backup {
        require(text.toByteArray().size <= MAX_BYTES) { "备份超过 2 MB 限制" }
        val backup = json.decodeFromString<Backup>(text)
        require(backup.version == 1) { "不支持的备份版本" }
        require(backup.schedules.size <= 1000) { "备份安排过多" }
        require(backup.schedules.map { it.id }.distinct().size == backup.schedules.size) { "备份包含重复安排编号" }
        backup.schedules.forEach { schedule ->
            val errors = ScheduleEngine.validate(schedule)
            require(errors.isEmpty()) { errors.joinToString("；") }
            // Freeze verifies offsets and durations as well as all recurrence exception data.
            ScheduleEngine.freezeOneOff(schedule)
        }
        return backup
    }
}
