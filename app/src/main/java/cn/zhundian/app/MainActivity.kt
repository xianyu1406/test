package cn.zhundian.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import cn.zhundian.app.ui.ZhundianApp

class MainActivity : ComponentActivity() {
    private lateinit var controller: AppController
    private var consumedVolumeKey: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateLockScreen(intent)
        // Activity-result registration occurs before STARTED, including on configuration recreation.
        controller = AppController(this)
        setContent { ZhundianApp(controller) }
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) controller.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateLockScreen(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == consumedVolumeKey) return true
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            event?.repeatCount == 0 && ::controller.isInitialized && controller.snoozeFromVolumeKey()) {
            consumedVolumeKey = keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == consumedVolumeKey) { consumedVolumeKey = null; return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun updateLockScreen(intent: Intent?) {
        val reminder = intent?.getBooleanExtra("reminder", false) == true
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(reminder)
            setTurnScreenOn(reminder)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (reminder) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}
