package cn.zhundian.app.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

data class ScheduleResult(
    val scheduled: Boolean,
    val reason: String,
    val triggerAtMillis: Long? = null,
)

/**
 * Each occurrence has its own OS alarm identity. setAlarmClock is intentionally used for
 * user-visible wake-up alarms, including a two-minute snooze; repeating/inexact alarms and
 * setExactAndAllowWhileIdle do not provide the required short-interval Doze semantics.
 * A successful return proves registration, not eventual delivery after force-stop/power-off.
 */
class AlarmScheduler(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)

    fun schedule(occurrenceId: String, triggerAtMillis: Long): ScheduleResult {
        if (occurrenceId.isBlank()) return ScheduleResult(false, "提醒实例不存在")
        if (triggerAtMillis <= System.currentTimeMillis()) {
            return ScheduleResult(false, "提醒时间已过；请重新选择未来时间")
        }
        if (!canScheduleExact()) return ScheduleResult(false, "安排已保存，需允许精确闹钟才能准点提醒")
        return try {
            val operation = requireNotNull(alarmIntent(occurrenceId, PendingIntent.FLAG_UPDATE_CURRENT))
            val show = PendingIntent.getActivity(
                context,
                0,
                Intent().setClassName(context, "cn.zhundian.app.MainActivity")
                    .setAction(ACTION_SHOW_ALARM)
                    .setData(identity(occurrenceId))
                    .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), operation)
            ScheduleResult(true, "提醒已成功调度", triggerAtMillis)
        } catch (_: SecurityException) {
            ScheduleResult(false, "精确闹钟授权已被撤销，请在设置中恢复")
        } catch (_: RuntimeException) {
            ScheduleResult(false, "系统未接受提醒，请检查权限并重新调度")
        }
    }

    fun cancel(occurrenceId: String) {
        alarmIntent(occurrenceId, PendingIntent.FLAG_NO_CREATE)?.let {
            manager.cancel(it)
            it.cancel()
        }
    }

    private fun alarmIntent(id: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_ALARM)
            .setClassName(context, "cn.zhundian.app.runtime.AlarmReceiver")
            .setData(identity(id))
            .putExtra(EXTRA_OCCURRENCE_ID, id),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun identity(id: String): Uri = Uri.Builder()
        .scheme("zhundian").authority("occurrence").appendPath(id).build()

    companion object {
        const val ACTION_ALARM = "cn.zhundian.ALARM"
        const val ACTION_SHOW_ALARM = "cn.zhundian.SHOW_ALARM"
        const val EXTRA_OCCURRENCE_ID = "occurrenceId"
    }
}
