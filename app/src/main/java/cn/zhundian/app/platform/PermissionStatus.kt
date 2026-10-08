package cn.zhundian.app.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Permission/capability snapshots are read again at the point of use, never treated as grants. */
data class PermissionStatus(
    val exactAlarm: Boolean,
    val notifications: Boolean,
    val fullScreen: Boolean,
    val activityRecognition: Boolean,
    val callPhone: Boolean,
    val readPhoneState: Boolean,
    val hasTelephony: Boolean,
) {
    val strongReminderReady: Boolean get() = exactAlarm && notifications && fullScreen && activityRecognition

    companion object {
        fun read(context: Context): PermissionStatus {
            val alarm = context.getSystemService(AlarmManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            return PermissionStatus(
                exactAlarm = Build.VERSION.SDK_INT < 31 ||
                    runCatching { alarm.canScheduleExactAlarms() }.getOrDefault(false),
                notifications = notifications.areNotificationsEnabled() &&
                    (Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)),
                fullScreen = Build.VERSION.SDK_INT < 34 ||
                    runCatching { notifications.canUseFullScreenIntent() }.getOrDefault(false),
                activityRecognition = Build.VERSION.SDK_INT < 29 ||
                    granted(context, Manifest.permission.ACTIVITY_RECOGNITION),
                callPhone = granted(context, Manifest.permission.CALL_PHONE),
                readPhoneState = granted(context, Manifest.permission.READ_PHONE_STATE),
                hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            )
        }

        fun motionPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 29) {
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
        } else emptyArray()

        fun notificationPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else emptyArray()

        fun callPermissions(): Array<String> = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
        )

        fun exactAlarmSettings(context: Context): Intent = if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri(context))
        } else appSettings(context)

        fun fullScreenSettings(context: Context): Intent = if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri(context))
        } else notificationSettings(context)

        fun notificationSettings(context: Context): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        fun appSettings(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

        private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

        private fun granted(context: Context, permission: String): Boolean =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
