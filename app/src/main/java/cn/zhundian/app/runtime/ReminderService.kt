package cn.zhundian.app.runtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.zhundian.app.ZhundianApplication
import cn.zhundian.app.data.AppJson
import cn.zhundian.app.data.SessionRow
import cn.zhundian.app.platform.*
import cn.zhundian.app.ui.UiSettings
import cn.zhundian.core.schedule.Occurrence
import cn.zhundian.core.session.*
import kotlinx.coroutines.*

/** Exact-alarm eligible foreground session; never masquerades as fitness or media playback. */
class ReminderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as ZhundianApplication
    private val coordinator get() = app.coordinator
    private lateinit var audio: ReminderAudio
    private lateinit var motion: AndroidMotionSource
    private lateinit var gateway: AndroidCallGateway
    private var phoneObserver: AutoCloseable? = null
    private var phoneWasBusy = false
    private var sensorsRunning = false
    private var currentId: String? = null
    private var sound: SoundMode? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLease = 0L
    private var ticker: Job? = null
    private var permissionSnapshot = false
    private var foregroundReady = false

    override fun onCreate() {
        super.onCreate()
        audio = ReminderAudio(this)
        gateway = AndroidCallGateway(this)
        motion = AndroidMotionSource(this) { report -> scope.launch { coordinator.motion(report) } }
        coordinator.immediateSilence = { audio.stop(); motion.stop(); sensorsRunning = false; sound = SoundMode.OFF }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        try {
            ServiceCompat.startForeground(this, ReminderNotifications.ID,
                ReminderNotifications.notification(this, "准点", null), type)
            foregroundReady = true
        } catch (e: RuntimeException) {
            ReminderNotifications.blocked(this, "系统拒绝后台提醒服务，请检查精确闹钟授权")
            scope.launch { coordinator.log("服务启动被系统拒绝：${e.javaClass.simpleName}") }
            stopSelf(); return
        }
        scope.launch { coordinator.active.collect { render(it) } }
        observePhone()
    }
    private fun observePhone() {
        phoneObserver?.close()
        permissionSnapshot = PermissionStatus.read(this).readPhoneState
        phoneObserver = gateway.observeCallState { state ->
            val ended = phoneWasBusy && state == ObservedCallState.IDLE
            if (state == ObservedCallState.RINGING || state == ObservedCallState.OFF_HOOK) phoneWasBusy = true
            if (state == ObservedCallState.IDLE) phoneWasBusy = false
            scope.launch { coordinator.phoneState(state, ended) }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady || !AlarmScheduler(this).canScheduleExact()) {
            ReminderNotifications.blocked(this, "精确闹钟权限不可用；当前不能运行强提醒，请打开应用修复")
            stopSelf(); return START_NOT_STICKY
        }
        if (ticker?.isActive != true) ticker = scope.launch {
            try {
                while (isActive) {
                    if (!AlarmScheduler(this@ReminderService).canScheduleExact()) {
                        coordinator.log("精确闹钟授权已撤销，停止前台服务；打开应用修复后恢复")
                        ReminderNotifications.blocked(this@ReminderService, "精确闹钟授权已撤销")
                        stopSelf(); break
                    }
                    val row = coordinator.pump()
                    if (row == null) { stopSelf(); break }
                    if (phoneWasBusy && AppJson.decodeFromString<Session>(row.sessionJson).stage != Stage.CALL_PAUSED) coordinator.phoneState(ObservedCallState.OFF_HOOK, false)
                    if (PermissionStatus.read(this@ReminderService).readPhoneState != permissionSnapshot) observePhone()
                    coordinator.attemptFallback(gateway) { audio.pause(); sound = SoundMode.OFF }
                    delay(1_000)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                coordinator.log("后台会话中断：${e.javaClass.simpleName}；打开应用恢复，未记录成功")
                ReminderNotifications.blocked(this@ReminderService, "会话暂时中断，请打开应用恢复")
                stopSelf()
            }
        }
        return START_STICKY
    }
    private fun render(row: SessionRow?) {
        if (row == null) {
            audio.stop(); motion.stop(); sensorsRunning = false; sound = null; currentId = null
            releaseWakeLock(); return
        }
        val s = AppJson.decodeFromString<Session>(row.sessionJson)
        val settings = AppJson.decodeFromString<UiSettings>(row.settingsJson)
        val occurrence = AppJson.decodeFromString<Occurrence>(row.occurrenceJson)
        if (currentId != s.id) {
            audio.stop(); motion.stop(); sensorsRunning = false; currentId = s.id; sound = null
        }
        val mode = if (phoneWasBusy || coordinator.isStopRequested(s.id)) SoundMode.OFF else s.soundMode(SystemClock.elapsedRealtime())
        if (mode != sound) {
            when (mode) {
                SoundMode.RING -> {
                    if (sound == null || sound == SoundMode.OFF) audio.start(occurrence.title, (settings.volume * 100).toInt(), settings.tts)
                    else audio.resume()
                }
                SoundMode.TASK -> {
                    if (sound == null || sound == SoundMode.OFF) audio.start(occurrence.title, (settings.volume * 100).toInt(), false)
                    audio.lowerForTask((settings.taskVolume * 100).toInt())
                }
                SoundMode.OFF -> audio.pause()
            }
            sound = mode
        }
        if (s.needsSensors && !sensorsRunning) { sensorsRunning = true; motion.start() }
        if (!s.needsSensors && sensorsRunning) { motion.stop(); sensorsRunning = false }
        val time = SystemClock.elapsedRealtime()
        if (s.stage != Stage.SNOOZED && (wakeLock?.isHeld != true || time - lastLease >= 120_000)) {
            releaseWakeLock()
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zhundian:active-reminder").apply {
                setReferenceCounted(false); acquire(180_000)
            }
            lastLease = time
        } else if (s.stage == Stage.SNOOZED) releaseWakeLock()
        runCatching { getSystemService(android.app.NotificationManager::class.java).notify(ReminderNotifications.ID,
            ReminderNotifications.notification(this, occurrence.title, s)) }
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onDestroy() {
        coordinator.immediateSilence = null
        phoneObserver?.close(); motion.stop(); audio.close(); releaseWakeLock(); scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        fun start(context: Context): Boolean {
            if (!AlarmScheduler(context).canScheduleExact()) {
                ReminderNotifications.blocked(context, "需要精确闹钟授权以启动持续提醒")
                return false
            }
            return runCatching { ContextCompat.startForegroundService(context, Intent(context, ReminderService::class.java)); true }
                .getOrElse { ReminderNotifications.blocked(context, "后台启动受到系统限制，请打开应用恢复提醒"); false }
        }
    }
}
