package cn.zhundian.app

import cn.zhundian.app.data.*
import cn.zhundian.app.ui.UiSettings
import cn.zhundian.core.backup.Backup
import cn.zhundian.core.schedule.Schedule
import cn.zhundian.core.session.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ZhundianApplication::class)
class PersistenceTest {
    private val app get() = (RuntimeEnvironment.getApplication() as ZhundianApplication)

    @Test fun roomSnapshotKeepsChallengeAndOneShotClaim() = runBlocking {
        var session = SessionEngine.create("persist", Intensity.STRONG, 0, fallbackEnabled = true)
        session = SessionEngine.reduce(session, Event.OpenChallenge(1))
        session = SessionEngine.reduce(session, Event.TapNumber(2, 1))
        session = session.copy(failures = 2, callClaimed = true, callStatus = CallStatus.CLAIMED_UNCERTAIN)
        val encoded = AppJson.encodeToString(Session.serializer(), session)
        app.database.dao().putSession(SessionRow("persist", "{}", encoded,
            AppJson.encodeToString(UiSettings.serializer(), UiSettings()), 1, 123))
        val restored = AppJson.decodeFromString<Session>(app.database.dao().session("persist")!!.sessionJson)
        assertEquals(session.grid, restored.grid)
        assertEquals(2, restored.targetNumber)
        assertEquals(2, restored.failures)
        assertTrue(restored.callClaimed)
        assertEquals(CallStatus.CLAIMED_UNCERTAIN, restored.callStatus)
        assertEquals(Stage.SCHULTE, restored.stage)
    }

    @Test fun importingExpiredBackupDisablesExistingContactAndRegistersNoPastAlarm() = runBlocking {
        app.settings.save(UiSettings(contactEnabled = true, contactConfirmed = true, contactNumber = "5550100"))
        val old = Schedule(id = "old", title = "历史行程", startLocal = "2000-01-01T06:00", endLocal = "2000-01-01T07:00")
        val result = app.coordinator.restoreBackup(Backup(exportedAt = 1, schedules = listOf(old)))
        assertEquals(1, app.database.dao().schedules().size)
        assertTrue(app.database.dao().occurrences().isEmpty())
        assertFalse(app.settings.flow.first().contactEnabled)
        assertFalse(app.settings.flow.first().contactConfirmed)
        assertTrue(result.contains("没有未来触发时间"))
    }

    @Test fun invalidImportedParametersDoNotPartiallyChangeDataOrConsent() = runBlocking {
        app.settings.save(UiSettings(contactEnabled = true, contactConfirmed = true, contactNumber = "5550100"))
        val invalid = Schedule(title = "无效窗口", startLocal = "2030-01-01T06:00", endLocal = "2030-01-01T07:00", reminderParameters = mapOf("mediumWindows" to 1))
        assertTrue(runCatching { app.coordinator.restoreBackup(Backup(exportedAt = 1, schedules = listOf(invalid))) }.isFailure)
        assertTrue(app.database.dao().schedules().isEmpty())
        assertTrue(app.settings.flow.first().contactEnabled)
    }
}
