package cn.zhundian.app

import android.app.Application
import androidx.room.Room
import cn.zhundian.app.data.AppDatabase
import cn.zhundian.app.data.SettingsStore
import cn.zhundian.app.runtime.ReminderCoordinator
import cn.zhundian.app.runtime.ReminderNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ZhundianApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "zhundian.db").build() }
    val settings by lazy { SettingsStore(this) }
    val coordinator by lazy { ReminderCoordinator(this) }
    override fun onCreate() {
        super.onCreate()
        ReminderNotifications.createChannels(this)
    }
}
