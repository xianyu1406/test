package cn.zhundian.app

import android.app.AlarmManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.zhundian.app.data.AppJson
import cn.zhundian.app.platform.AlarmScheduler
import cn.zhundian.app.ui.SessionActions
import cn.zhundian.core.schedule.ReminderIntensity
import cn.zhundian.core.session.Intensity
import cn.zhundian.core.session.Session
import cn.zhundian.core.session.Stage
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run on an isolated emulator installation. Tests never grant CALL_PHONE or request a real call. */
@RunWith(AndroidJUnit4::class)
class ReminderSmokeTest {
    private val app get() = ApplicationProvider.getApplicationContext<ZhundianApplication>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun isolatedReminderFixture() = runBlocking {
        assumeTrue("Use a clean emulator install: existing user schedules are never cleared", app.database.dao().schedules().isEmpty())
        assumeTrue("Use a clean emulator with no existing active reminder", app.database.dao().activeSession() == null)
        if (Build.VERSION.SDK_INT >= 31) shell("appops set cn.zhundian.app SCHEDULE_EXACT_ALARM allow")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant cn.zhundian.app android.permission.POST_NOTIFICATIONS")
        assumeTrue("Device policy must permit exact alarms", AlarmScheduler(app).canScheduleExact())
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun stopOnlyTestReminder() {
        runBlocking {
            app.coordinator.active.value?.takeIf { it.id.startsWith("test-") }?.let {
                app.coordinator.action(SessionActions.EMERGENCY_STOP)
            }
            app.database.dao().occurrences().filter { it.id.startsWith("test-") && it.status == "PENDING" }.forEach {
                AlarmScheduler(app).cancel(it.id)
                app.database.dao().putOccurrence(it.copy(status = "CANCELLED"))
            }
        }
        scenario?.close()
    }

    @Test fun nativeAlarmIsRegisteredDeliveredAndEmergencyStopPersistsAbort() = runBlocking {
        val before = System.currentTimeMillis()
        val message = app.coordinator.test(ReminderIntensity.NORMAL)
        assertTrue(message, message.contains("已注册"))
        val registered = app.getSystemService(AlarmManager::class.java).nextAlarmClock
        assertNotNull("setAlarmClock must produce a real Android alarm registration", registered)
        assertTrue(requireNotNull(registered).triggerTime in (before + 9_000)..(before + 15_000))

        val row = withTimeout(35_000) {
            app.coordinator.active.filterNotNull().first { it.id.startsWith("test-") }
        }
        val session = AppJson.decodeFromString<Session>(row.sessionJson)
        assertEquals(Intensity.NORMAL, session.intensity)
        assertFalse(session.fallbackEnabled)
        assertFalse(session.callClaimed)
        assertEquals(Stage.RINGING, session.stage)
        assertEquals("ACTIVE", app.database.dao().occurrence(row.id)?.status)
        app.coordinator.action(SessionActions.EMERGENCY_STOP)
        assertNull(app.coordinator.active.value)
        val saved = requireNotNull(app.database.dao().lastSession())
        assertEquals(Stage.ABORTED, AppJson.decodeFromString<Session>(saved.sessionJson).stage)
        assertFalse(saved.active)
    }

    @Test fun activityRecreationKeepsPersistedChallengeAndDoesNotCompleteReminder() = runBlocking {
        app.coordinator.test(ReminderIntensity.MEDIUM)
        withTimeout(35_000) { app.coordinator.active.filterNotNull().first { it.id.startsWith("test-") } }
        app.coordinator.action(SessionActions.START_TASK)
        app.coordinator.action(SessionActions.TAP_NUMBER, 1)
        app.coordinator.action(SessionActions.TAP_NUMBER, 2)
        val before = AppJson.decodeFromString<Session>(requireNotNull(app.coordinator.active.value).sessionJson)
        assertEquals(Stage.SCHULTE, before.stage)
        assertEquals(3, before.targetNumber)
        scenario?.recreate()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val after = AppJson.decodeFromString<Session>(requireNotNull(app.coordinator.active.value).sessionJson)
        assertEquals(before.grid, after.grid)
        assertEquals(before.targetNumber, after.targetNumber)
        assertEquals(Stage.SCHULTE, after.stage)
        assertFalse(after.fallbackEnabled)
        assertFalse(after.callClaimed)
    }

    @Test fun normalSnoozeRegistersAndActuallyRingsAfter120Seconds() = runBlocking {
        app.coordinator.test(ReminderIntensity.NORMAL)
        withTimeout(35_000) { app.coordinator.active.filterNotNull().first { it.id.startsWith("test-") } }
        val startedElapsed = android.os.SystemClock.elapsedRealtime()
        val startedWall = System.currentTimeMillis()
        app.coordinator.action(SessionActions.SNOOZE)
        val alarm = requireNotNull(app.getSystemService(AlarmManager::class.java).nextAlarmClock)
        assertTrue("Snooze must use a new user-visible alarm clock", alarm.triggerTime in (startedWall + 119_000)..(startedWall + 125_000))
        withTimeout(160_000) {
            app.coordinator.active.filterNotNull().first {
                AppJson.decodeFromString<Session>(it.sessionJson).stage == Stage.RINGING
            }
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedElapsed
        android.util.Log.i("ZhundianEvidence", "nativeSnoozeElapsedMs=$elapsed")
        assertTrue("Observed snooze interval=$elapsed ms", elapsed in 119_000..160_000)
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
