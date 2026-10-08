package cn.zhundian.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.zhundian.app.ZhundianApplication
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as ZhundianApplication
        app.applicationScope.launch {
            try {
                val action = intent.getStringExtra("sessionAction")
                if (action != null) intent.getStringExtra("sessionId")?.let { app.coordinator.action(action, expectedSessionId = it) }
                else intent.getStringExtra("occurrenceId")?.let { app.coordinator.alarm(it) }
                ReminderService.start(context)
            } finally { pending.finish() }
        }
    }
}

class RebuildReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val pending = goAsync()
        val app = context.applicationContext as ZhundianApplication
        app.applicationScope.launch {
            try { app.coordinator.rebuild("系统时间、授权或启动变化：重新调度未来实例") }
            finally { pending.finish() }
        }
    }
}
