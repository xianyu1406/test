package cn.zhundian.app.runtime

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.zhundian.app.MainActivity
import cn.zhundian.app.R
import cn.zhundian.core.session.Session
import cn.zhundian.core.session.Intensity
import cn.zhundian.app.ui.SessionActions

object ReminderNotifications {
    const val ID = 7101
    const val CHANNEL = "active_reminder_v1"
    private const val STATUS = "status_v1"
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.runtime_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = "响铃、运动检查与紧急停止"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        manager.createNotificationChannel(NotificationChannel(STATUS, context.getString(R.string.runtime_channel_status), NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun notification(context: Context, title: String, session: Session?): Notification {
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).putExtra("reminder", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm).setContentTitle(title)
            .setContentText(session?.message ?: context.getString(R.string.runtime_preparing))
            .setStyle(NotificationCompat.BigTextStyle().bigText(session?.message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, context.getString(R.string.runtime_open), open)
            .addAction(0, context.getString(R.string.runtime_abort), action(context, SessionActions.EMERGENCY_STOP, session?.id))
        if (session?.intensity == Intensity.NORMAL) builder.addAction(0, context.getString(R.string.runtime_snooze), action(context, SessionActions.SNOOZE, session?.id))
        val manager = context.getSystemService(NotificationManager::class.java)
        if ((session == null || session.stage == cn.zhundian.core.session.Stage.RINGING || session.stage == cn.zhundian.core.session.Stage.TECHNICAL_FAULT) &&
            (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent())) builder.setFullScreenIntent(open, true)
        return builder.build()
    }
    private fun action(context: Context, action: String, sessionId: String?) = PendingIntent.getBroadcast(context, (action + sessionId).hashCode(),
        Intent(context, AlarmReceiver::class.java).setAction("cn.zhundian.ACTION")
            .setData(android.net.Uri.Builder().scheme("zhundian").authority("session-action").appendPath(sessionId ?: "preparing").appendPath(action).build())
            .putExtra("sessionAction", action).putExtra("sessionId", sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun blocked(context: Context, reason: String) {
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching { context.getSystemService(NotificationManager::class.java).notify(7102,
            NotificationCompat.Builder(context, STATUS).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(context.getString(R.string.runtime_permission)).setContentText(reason)
                .setContentIntent(open).setAutoCancel(true).build()) }
    }
}
