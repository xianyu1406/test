package cn.zhundian.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "schedules")
data class ScheduleRow(@PrimaryKey val id: String, val json: String)

@Entity(tableName = "occurrences")
data class OccurrenceRow(
    @PrimaryKey val id: String,
    val scheduleId: String,
    val triggerAt: Long,
    val json: String,
    val status: String = "PENDING",
    val schedulingMessage: String = "尚未调度",
)

@Entity(tableName = "sessions")
data class SessionRow(
    @PrimaryKey val id: String,
    val occurrenceJson: String,
    val sessionJson: String,
    val settingsJson: String,
    val bootCount: Int,
    val savedAtWall: Long,
    val active: Boolean = true,
)

@Entity(tableName = "history")
data class HistoryRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long, val message: String)

@Dao
interface AppDao {
    @Query("SELECT * FROM schedules") fun watchSchedules(): Flow<List<ScheduleRow>>
    @Query("SELECT * FROM schedules") suspend fun schedules(): List<ScheduleRow>
    @Query("SELECT * FROM schedules WHERE id = :id") suspend fun schedule(id: String): ScheduleRow?
    @Upsert suspend fun putSchedule(row: ScheduleRow)
    @Query("DELETE FROM schedules WHERE id = :id") suspend fun deleteSchedule(id: String)
    @Query("SELECT * FROM occurrences") suspend fun occurrences(): List<OccurrenceRow>
    @Query("SELECT * FROM occurrences WHERE id = :id") suspend fun occurrence(id: String): OccurrenceRow?
    @Upsert suspend fun putOccurrence(row: OccurrenceRow)
    @Query("SELECT * FROM occurrences WHERE status = 'QUEUED' ORDER BY triggerAt, id LIMIT 1") suspend fun nextQueued(): OccurrenceRow?
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): SessionRow?
    @Query("SELECT * FROM sessions WHERE active = 1 ORDER BY savedAtWall LIMIT 1") suspend fun activeSession(): SessionRow?
    @Query("SELECT * FROM sessions ORDER BY savedAtWall DESC LIMIT 1") suspend fun lastSession(): SessionRow?
    @Query("SELECT * FROM sessions ORDER BY savedAtWall DESC LIMIT 1") fun watchLastSession(): Flow<SessionRow?>
    @Upsert suspend fun putSession(row: SessionRow)
    @Insert suspend fun log(row: HistoryRow)
    @Query("SELECT * FROM history ORDER BY id DESC LIMIT 150") fun watchHistory(): Flow<List<HistoryRow>>
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 500)") suspend fun trimHistory()
}

@Database(entities = [ScheduleRow::class, OccurrenceRow::class, SessionRow::class, HistoryRow::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() { abstract fun dao(): AppDao }
