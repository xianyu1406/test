package cn.zhundian.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import cn.zhundian.app.data.AppJson
import cn.zhundian.app.data.SessionRow
import cn.zhundian.app.platform.AndroidCallGateway
import cn.zhundian.app.platform.AndroidMotionSource
import cn.zhundian.app.platform.CallEligibilityCode
import cn.zhundian.app.platform.PermissionStatus
import cn.zhundian.app.runtime.ReminderCoordinator
import cn.zhundian.app.runtime.ReminderService
import cn.zhundian.app.ui.*
import cn.zhundian.core.backup.Backup
import cn.zhundian.core.backup.BackupCodec
import cn.zhundian.core.motion.MotionMode
import cn.zhundian.core.schedule.Occurrence
import cn.zhundian.core.schedule.ReminderIntensity
import cn.zhundian.core.schedule.Schedule
import cn.zhundian.core.schedule.ScheduleEngine
import cn.zhundian.core.session.CallAvailability
import cn.zhundian.core.session.CallStatus
import cn.zhundian.core.session.Session
import cn.zhundian.core.session.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Activity is a presentation owner only; the application coordinator persists all session writes. */
class AppController(private val activity: ComponentActivity) : UiController {
    private val app = activity.application as ZhundianApplication
    private val dao = app.database.dao()
    private val coordinator = app.coordinator
    private val mutable = MutableStateFlow(UiState())
    override val state = mutable.asStateFlow()
    private var lastSession: SessionRow? = null
    private var pendingImport: Backup? = null
    private var importing = false
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshCapabilities()
        runAction { coordinator.rebuild("权限设置返回后重新检查提醒") }
    }
    private val createBackup = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runAction {
            val schedules = dao.schedules().map { AppJson.decodeFromString<Schedule>(it.json) }
            val content = BackupCodec.encode(schedules, System.currentTimeMillis())
            require(content.toByteArray(Charsets.UTF_8).size <= BackupCodec.MAX_BYTES) { "备份超过2MB，请减少安排后重试" }
            withContext(Dispatchers.IO) {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法写入选择的文件")
            }
            "已导出 ${schedules.size} 个安排；不含联系人、提醒会话或电话资格"
        }
    }
    private val openBackup = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectImport(uri)
    }

    init {
        activity.lifecycleScope.launch {
            dao.watchSchedules().collect { rows ->
                val schedules = rows.mapNotNull { runCatching { AppJson.decodeFromString<Schedule>(it.json) }.getOrNull() }
                mutable.update { it.copy(schedules = schedules) }
            }
        }
        activity.lifecycleScope.launch {
            dao.watchLastSession().collect { row -> lastSession = row; refreshSession() }
        }
        activity.lifecycleScope.launch {
            app.settings.flow.collect { value -> mutable.update { it.copy(settings = value) }; refreshSession() }
        }
        activity.lifecycleScope.launch {
            dao.watchHistory().collect { rows -> mutable.update { it.copy(history = rows.map { row -> UiHistory(row.at, row.message) }) } }
        }
        activity.lifecycleScope.launch {
            while (isActive) { refreshSession(); delay(1_000) }
        }
        refreshCapabilities()
    }

    fun onResume() {
        refreshCapabilities()
        app.applicationScope.launch {
            try {
                coordinator.rebuild("应用打开后重建未来提醒")
                // Creating t0 belongs to the successfully foregrounded service, never this screen.
                if (dao.activeSession() != null || dao.nextQueued() != null) ReminderService.start(app)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }

    override fun saveSchedule(draft: ScheduleDraft, scope: EditScope) = runAction {
        coordinator.saveSchedule(withParameters(draft), scope)
    }
    override fun skipOccurrence(scheduleId: String, dateKey: String) = runAction { coordinator.skip(scheduleId, dateKey) }
    override fun deleteSchedule(scheduleId: String) = runAction { coordinator.delete(scheduleId) }
    override fun setEnabled(scheduleId: String, enabled: Boolean) = runAction { coordinator.enable(scheduleId, enabled) }
    override fun saveTravel(drafts: List<ScheduleDraft>) = runAction {
        require(drafts.isNotEmpty()) { "请至少保留一个出行节点" }
        val prepared = drafts.map(::withParameters)
        prepared.forEach { draft ->
            val errors = ScheduleEngine.validate(draft.toSchedule())
            require(errors.isEmpty()) { errors.joinToString("；") }
            ReminderCoordinator.parameters(draft.reminderParameters, state.value.settings)
        }
        coordinator.saveTravel(prepared)
    }

    override fun saveSettings(settings: UiSettings) = runAction {
        require(settings.volume.isFinite() && settings.taskVolume.isFinite()) { "音量无效" }
        require(settings.mediumWindowSeconds in 30..3600 && settings.mediumWindows in 3..12) { "中档观察窗口为30–3600秒，至少3个且不超过12个" }
        require(settings.strongInitialSeconds in 30..3600 && settings.strongIdleSeconds in 30..3600) { "运动验证和无运动期限为30–3600秒" }
        require(settings.strongTargetSeconds in 360..21600) { "持续目标必须至少覆盖三个独立的120秒观察窗口，最多6小时" }
        require(settings.strongMaxFailures in 1..20 && settings.unconfirmedSeconds in 60..7200) { "失败上限为1–20轮，未确认期限为60–7200秒" }
        require(!settings.contactEnabled || settings.contactConfirmed) { "启用电话兜底前，请在本机明确确认联系人用途" }
        if (settings.contactEnabled || settings.contactConfirmed) {
            require(AndroidCallGateway.manualDialIntent(settings.contactNumber) != null) { "请填写有效的普通联系人电话号码" }
        }
        val normalized = settings.copy(volume = settings.volume.coerceIn(.1f, 1f), taskVolume = settings.taskVolume.coerceIn(.1f, 1f),
            contactName = settings.contactName.trim(), contactNumber = settings.contactNumber.trim(),
            contactEnabled = settings.contactEnabled && settings.contactConfirmed)
        ReminderCoordinator.parameters(emptyMap(), normalized)
        app.settings.save(normalized)
        coordinator.log("设置已保存；联系人兜底${if (normalized.contactEnabled) "经本机确认后启用" else "关闭"}；没有发起电话")
        "设置已保存；新安排使用这些参数，已有安排保留创建时的参数"
    }

    override fun preview(draft: ScheduleDraft, scope: EditScope): UiPreview = try {
        val replacement = ScheduleEngine.freezeOneOff(withParameters(draft).toSchedule())
        val old = state.value.schedules.firstOrNull { it.id == draft.id }
        val schedule = when {
            old != null && scope == EditScope.THIS && draft.sourceDateKey != null -> {
                val fixed = ScheduleEngine.freezeOneOff(replacement.copy(weeklyDays = emptySet()))
                ScheduleEngine.modifyOccurrence(old, cn.zhundian.core.schedule.OccurrenceOverride(
                    dateKey = draft.sourceDateKey, startEpochMillis = fixed.fixedStartEpochMillis,
                    endEpochMillis = fixed.fixedEndEpochMillis, title = replacement.title,
                    location = replacement.location, notes = replacement.notes, important = replacement.important,
                    leadMinutes = replacement.leadMinutes, intensity = replacement.intensity,
                    reminderParameters = replacement.reminderParameters))
            }
            old != null && scope == EditScope.FUTURE && draft.sourceDateKey != null ->
                ScheduleEngine.splitFuture(old, draft.sourceDateKey, replacement).future
            else -> replacement.copy(excludedDates = old?.excludedDates.orEmpty(), overrides = old?.overrides.orEmpty())
        }
        val errors = ScheduleEngine.validate(schedule)
        if (errors.isNotEmpty()) UiPreview(error = errors.joinToString("；")) else {
            ReminderCoordinator.parameters(schedule.reminderParameters, state.value.settings)
            val format = DateTimeFormatter.ofPattern("yyyy-MM-dd E HH:mm:ss z").withLocale(java.util.Locale.SIMPLIFIED_CHINESE)
            val times = ScheduleEngine.nextOccurrences(ScheduleEngine.freezeOneOff(schedule), System.currentTimeMillis(), 3)
                .map { format.format(Instant.ofEpochMilli(it.triggerEpochMillis).atZone(ZoneId.of(it.zoneId))) }
            UiPreview(times)
        }
    } catch (e: Exception) { UiPreview(error = e.message?.take(180) ?: "日期、重复规则或参数无效") }

    override fun sessionAction(action: String, number: Int?) {
        val expectedId = state.value.session?.id ?: return
        coordinator.requestStop(action, expectedId)
        runAction(showSuccess = false) {
            if (action == SessionActions.MANUAL_DIAL) {
                val row = dao.activeSession()?.takeIf { it.id == expectedId } ?: error("该提醒已结束，请查看当前提醒")
                val session = AppJson.decodeFromString<Session>(row.sessionJson)
                require(!session.stage.terminal && session.stage != Stage.CALL_PAUSED) { "当前正在通话暂停中，请先结束通话后继续检查" }
                val settings = AppJson.decodeFromString<UiSettings>(row.settingsJson)
                val dial = AndroidCallGateway.manualDialIntent(settings.contactNumber) ?: error("没有可用的联系人号码")
                require(AndroidCallGateway(app).preflight(settings.contactNumber).code != CallEligibilityCode.ALREADY_IN_CALL) { "已有通话，等待结束后再操作" }
                coordinator.action(action, expectedSessionId = expectedId)
                try {
                    activity.startActivity(dial)
                    coordinator.log("已打开本机拨号界面；等待用户拨打，未记录呼叫提交或接通")
                } catch (e: Exception) {
                    coordinator.action(SessionActions.RESUME_AFTER_CALL, expectedSessionId = expectedId)
                    throw IllegalStateException("设备无法打开拨号界面；本地检查已恢复", e)
                }
                "等待用户在拨号界面确认；返回后选择通话后继续检查"
            } else {
                coordinator.action(action, number, expectedId)
                if (dao.activeSession() != null) ReminderService.start(app)
                ""
            }
        }
    }

    override fun scheduleTest(intensity: ReminderIntensity) = runAction { coordinator.test(intensity) }
    override fun exportBackup() { runCatching { createBackup.launch("准点备份-${java.time.LocalDate.now(ZoneId.of("Asia/Shanghai"))}.json") }.onFailure(::showError) }
    override fun chooseImport() {
        dismissImport()
        runCatching { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.onFailure(::showError)
    }
    override fun confirmImport() {
        if (importing) return
        val backup = pendingImport ?: return
        importing = true
        runAction {
            try {
                val result = coordinator.restoreBackup(backup)
                pendingImport = null
                mutable.update { it.copy(importPreview = null) }
                result
            } finally { importing = false }
        }
    }
    override fun dismissImport() { pendingImport = null; mutable.update { it.copy(importPreview = null) } }
    override fun dismissMessage() { mutable.update { it.copy(message = null) } }

    override fun openPermission(key: String) {
        try {
            val status = PermissionStatus.read(activity)
            when (key) {
                "activity_recognition" -> if (Build.VERSION.SDK_INT >= 29 && !status.activityRecognition) permissions.launch(PermissionStatus.motionPermissions()) else activity.startActivity(PermissionStatus.appSettings(activity))
                "notifications" -> if (Build.VERSION.SDK_INT >= 33 && !status.notifications) permissions.launch(PermissionStatus.notificationPermissions()) else activity.startActivity(PermissionStatus.notificationSettings(activity))
                "call", "call_phone" -> permissions.launch(PermissionStatus.callPermissions())
                "phone_state" -> permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
                "exact", "exact_alarm" -> activity.startActivity(PermissionStatus.exactAlarmSettings(activity))
                "fullscreen", "full_screen" -> activity.startActivity(PermissionStatus.fullScreenSettings(activity))
                "battery" -> activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                else -> activity.startActivity(PermissionStatus.appSettings(activity))
            }
        } catch (e: Exception) { showError(IllegalStateException("无法打开该系统设置，请从系统应用信息中调整权限", e)) }
    }

    fun snoozeFromVolumeKey(): Boolean {
        val reminder = state.value.session ?: return false
        if (reminder.intensity != ReminderIntensity.NORMAL || reminder.terminal || reminder.stage != Stage.RINGING.name) return false
        sessionAction(SessionActions.SNOOZE)
        return true
    }

    private fun inspectImport(uri: Uri) = runAction(showSuccess = false) {
        val content = withContext(Dispatchers.IO) {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= BackupCodec.MAX_BYTES) { "备份超过2MB限制" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            } ?: error("无法读取选择的文件")
        }
        val backup = BackupCodec.decode(content)
        val now = System.currentTimeMillis()
        val futureCount = backup.schedules.count { ScheduleEngine.nextOccurrences(it, now, 1).isNotEmpty() }
        pendingImport = backup
        mutable.update { it.copy(importPreview = UiImportPreview(
            "将新增 ${backup.schedules.size} 个独立安排副本，不覆盖现有安排。其中 $futureCount 个有未来触发时间。联系人兜底会关闭，需要在本机重新确认；过期提醒和电话不会补触发。",
            backup.schedules.size)) }
        ""
    }

    private fun withParameters(draft: ScheduleDraft) = if (draft.reminderParameters.isEmpty()) draft.copy(reminderParameters = state.value.settings.parameterSnapshot()) else draft

    private fun refreshCapabilities() {
        val permission = PermissionStatus.read(activity)
        val capability = AndroidMotionSource(activity) {}.capabilities()
        val missing = capability.missingFor(MotionMode.FULL)
        val ready = permission.strongReminderReady && missing.isEmpty()
        mutable.update { it.copy(capabilities = listOf(
            UiCapability("readiness", "强提醒准备状态", if (ready) "所需授权及运动硬件可用；仍需完成锁屏和真实步行验证" else "准备未完成，请查看下列缺少的授权或硬件", ready, false),
            UiCapability("exact", "精确闹钟", if (permission.exactAlarm) "允许注册准点闹钟；保存后仍会显示实际调度结果" else "未授权，无法注册准点闹钟", permission.exactAlarm),
            UiCapability("notifications", "提醒通知", if (permission.notifications) "通知已允许" else "通知未允许，锁屏提示可能不可见", permission.notifications),
            UiCapability("fullscreen", "锁屏全屏提醒", if (permission.fullScreen) "系统允许全屏提醒；显示仍受锁屏和系统策略影响" else "全屏未允许，降级为系统允许的高优先级通知", permission.fullScreen),
            UiCapability("activity_recognition", "身体活动权限", if (permission.activityRecognition) "已允许系统计步" else "未授权，无法验证步行，可明确退回普通档", permission.activityRecognition),
            UiCapability("motion", "本机运动能力", if (missing.isEmpty()) "计步、加速度、方向和陀螺仪可用；识别参数待真机校准" else missing.joinToString("；") + "。请使用普通档或修复权限", missing.isEmpty(), false),
            UiCapability("call", "可选联系人电话", when { !permission.hasTelephony -> "设备没有本机电话能力"; permission.callPhone && permission.readPhoneState -> "通话权限已允许；每次拨号前仍检查SIM、默认线路和已有通话；免提只是请求"; else -> "自动电话需要通话和电话状态权限；默认关闭，缺少条件时等待用户拨打" }, permission.hasTelephony && permission.callPhone && permission.readPhoneState),
            UiCapability("battery", "厂商省电与勿扰", "省电、勿扰、强制停止和关机可能影响提醒，请在真机检查；应用不能无条件绕过系统限制", true),
            UiCapability("app_settings", "系统应用信息", "权限被永久拒绝时，可在系统应用信息中重新授权", true),
        )) }
    }

    private fun refreshSession() {
        val projected = lastSession?.let { row -> runCatching {
            val s = AppJson.decodeFromString<Session>(row.sessionJson)
            val occurrence = AppJson.decodeFromString<Occurrence>(row.occurrenceJson)
            val settings = AppJson.decodeFromString<UiSettings>(row.settingsJson)
            val now = SystemClock.elapsedRealtime()
            fun remaining(deadline: Long) = ((deadline - now).coerceAtLeast(0) + 999) / 1000
            val next = s.nextDeadlineMs?.let { "下一次检查/提醒：${remaining(it)}秒后" }.orEmpty()
            val pause = s.soundPausedUntilMs?.takeIf { it > now }?.let { "；声音暂停剩余${remaining(it)}秒" }.orEmpty()
            val target = s.targetDeadlineMs?.let { "；持续目标${if (it <= now) "已到，仍须满足条件并明确结束" else "剩余${remaining(it)}秒"}" }.orEmpty()
            val fallback = when {
                !s.fallbackEnabled -> "联系人兜底关闭；继续本机提醒"
                s.fallbackTriggered -> "联系人兜底已触发：${s.fallbackReason.orEmpty()}；请求提交和接通须看下方状态"
                else -> "联系人兜底已启用：累计${s.parameters.maxFailures}轮失败，或${s.nextFallbackDeadlineMs?.let { "${remaining(it)}秒后仍未确认" } ?: "后续轮次未确认超时"}可能触发"
            }
            UiReminder(s.id, occurrence.title, ReminderIntensity.valueOf(s.intensity.name), s.stage.name,
                s.message + if (s.needsSensors || s.stage == Stage.TECHNICAL_FAULT) "；${s.sensorReason}" else "",
                s.grid, s.targetNumber, s.failures,
                if (s.intensity.name == "MEDIUM") "运动窗口 ${if (s.stage == Stage.COMPLETED) s.parameters.mediumWindows else (s.mediumWindow + 1).coerceAtMost(s.parameters.mediumWindows)}/${s.parameters.mediumWindows}；${if (s.mediumWindowPassed) "本窗口已通过，等待完整窗口结束" else "等待本窗口新步行"}"
                else if (s.intensity.name == "STRONG") "不同窗口有效步行 ${s.successfulWindows.size}/${s.parameters.requiredStrongWindows}$target" else "",
                next + pause, fallback, ReminderCoordinator.callLabel(s.callStatus) + s.callMessage?.let { "；$it" }.orEmpty(),
                canFinish = s.canFinish(now),
                canGrace = s.stage == Stage.MONITORING && s.t1Ms != null && s.parameters.graceEnabled && !s.graceUsed,
                canRetry = s.stage == Stage.TECHNICAL_FAULT,
                canResumeCall = s.stage == Stage.CALL_PAUSED,
                canManualDial = !s.stage.terminal && s.stage != Stage.CALL_PAUSED && s.fallbackTriggered &&
                    s.callStatus in setOf(CallStatus.BLOCKED, CallStatus.WAITING_USER, CallStatus.FAILED) &&
                    s.callAvailability != CallAvailability.ONGOING_CALL && settings.contactNumber.isNotBlank(),
                canDowngrade = s.stage == Stage.TECHNICAL_FAULT,
                terminal = s.stage.terminal)
        }.getOrNull() }
        mutable.update { it.copy(session = projected) }
    }

    private fun runAction(showSuccess: Boolean = true, block: suspend () -> String) {
        app.applicationScope.launch {
            try { val result = block(); if (showSuccess && result.isNotBlank()) mutable.update { it.copy(message = result) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }
    private fun showError(error: Throwable) { mutable.update { it.copy(message = error.message?.take(220) ?: "操作未完成，请检查设置后重试") } }
}
