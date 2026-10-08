package cn.zhundian.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * One audio owner for the serialized reminder queue. All player operations run on the main
 * looper. Volume is a per-player multiplier, not a forced system-volume/DND change. Audio
 * focus, alarm volume, output route and manufacturer policy can still prevent audible sound.
 * Calling pause() only pauses this adapter; deadline state remains the coordinator's concern.
 */
class ReminderAudio(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wanted = false
    private var focusPaused = false
    private var userCap = 0.8f
    private var targetVolume = userCap
    private var currentVolume = 0f
    private var rampStart = 0L
    private var label = ""
    private var wantsSpeech = false
    private var speechPending = false
    private var closed = false

    @Volatile
    var statusMessage: String = "声音已停止"
        private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusPaused = wanted
                silence(releaseFocus = false)
                statusMessage = "声音被系统音频或通话中断，提醒仍未完成"
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setPlayerVolume(minOf(currentVolume, 0.1f))
            AudioManager.AUDIOFOCUS_GAIN -> if (wanted && focusPaused) {
                focusPaused = false
                playWithRamp(requestFocus = false)
            }
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(focusListener, main)
        .build()

    private val ramp = object : Runnable {
        override fun run() {
            if (!wanted || focusPaused || closed) return
            val fraction = ((SystemClock.elapsedRealtime() - rampStart) / RAMP_MILLIS.toFloat()).coerceIn(0f, 1f)
            setPlayerVolume((MIN_VOLUME + (targetVolume - MIN_VOLUME) * fraction).coerceIn(0f, targetVolume))
            if (fraction < 1f) main.postDelayed(this, 250)
        }
    }

    fun start(label: String, maxVolumePercent: Int = 80, speak: Boolean = false) = onMain {
        if (closed) return@onMain
        this.label = label.take(120)
        userCap = maxVolumePercent.coerceIn(0, 100) / 100f
        targetVolume = userCap
        wantsSpeech = speak
        speechPending = speak
        wanted = true
        focusPaused = false
        playWithRamp(requestFocus = true)
        if (speak) prepareSpeech()
    }

    /** Lower volume only while actually solving; the coordinator restores ringing on timeout. */
    fun lowerForTask(percent: Int) = onMain {
        targetVolume = minOf(userCap, percent.coerceIn(10, 100) / 100f)
        main.removeCallbacks(ramp)
        setPlayerVolume(targetVolume)
        runCatching { vibrator?.cancel() }
        runCatching { tts?.stop() }
        speechPending = false
    }

    fun pause() = onMain {
        wanted = false
        focusPaused = false
        speechPending = false
        silence(releaseFocus = true)
        statusMessage = "本应用当前声音已暂停，检查及兜底期限继续"
    }

    fun resume() = onMain {
        if (closed) return@onMain
        wanted = true
        focusPaused = false
        targetVolume = userCap
        playWithRamp(requestFocus = true)
    }

    fun stop() = onMain {
        wanted = false
        focusPaused = false
        speechPending = false
        silence(releaseFocus = true)
        releasePlayer()
        statusMessage = "声音已停止"
    }

    override fun close() = onMain {
        wanted = false
        closed = true
        silence(releaseFocus = true)
        releasePlayer()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        statusMessage = "声音资源已释放"
    }

    private fun playWithRamp(requestFocus: Boolean) {
        main.removeCallbacks(ramp)
        if (requestFocus) {
            val result = runCatching { audioManager.requestAudioFocus(focusRequest) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusPaused = true
                statusMessage = "系统未授予音频焦点，请查看通知；提醒未完成"
                vibrate()
                return
            }
        }
        if (player == null) player = createPlayer()
        rampStart = SystemClock.elapsedRealtime()
        setPlayerVolume(minOf(MIN_VOLUME, targetVolume))
        val played = runCatching { player?.start(); player != null }.getOrDefault(false)
        statusMessage = if (played) "响铃中，实际音量受系统闹钟音量及勿扰设置限制" else "铃声不可用，保留振动和通知"
        if (played) main.post(ramp)
        vibrate()
        speakIfReady()
    }

    private fun createPlayer(): MediaPlayer? {
        val uris = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ).distinct()
        for (uri in uris) {
            val candidate = MediaPlayer()
            try {
                candidate.setAudioAttributes(attributes)
                candidate.setDataSource(context, uri)
                candidate.isLooping = true
                candidate.setOnErrorListener { failed, _, _ ->
                    runCatching { failed.release() }
                    if (player === failed) player = null
                    statusMessage = "系统铃声播放失败，保留振动和通知"
                    true
                }
                candidate.prepare()
                return candidate
            } catch (_: Exception) {
                runCatching { candidate.release() }
            }
        }
        return null
    }

    private fun setPlayerVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, userCap)
        runCatching { player?.setVolume(currentVolume, currentVolume) }
    }

    private fun vibrate() {
        runCatching {
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 450, 500, 1_000), 0), attributes)
            }
        }
    }

    private fun silence(releaseFocus: Boolean) {
        main.removeCallbacks(ramp)
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        runCatching { vibrator?.cancel() }
        runCatching { tts?.stop() }
        if (releaseFocus) runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
    }

    private fun prepareSpeech() {
        if (!wantsSpeech || closed) return
        if (tts != null) {
            speakIfReady()
            return
        }
        tts = TextToSpeech(context) { status ->
            onMain {
                if (closed) return@onMain
                val engine = tts
                val language = if (status == TextToSpeech.SUCCESS) {
                    runCatching { engine?.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrNull()
                } else null
                ttsReady = language != null && language >= TextToSpeech.LANG_AVAILABLE
                if (ttsReady) {
                    runCatching { engine?.setAudioAttributes(attributes) }
                    speakIfReady()
                }
                // An unavailable Chinese engine never removes ordinary alarm playback.
            }
        }
    }

    private fun speakIfReady() {
        if (!ttsReady || !speechPending || !wanted || focusPaused || closed) return
        speechPending = false
        runCatching {
            tts?.speak(
                "准点提醒，$label",
                TextToSpeech.QUEUE_FLUSH,
                android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, userCap) },
                "zhundian-current-reminder",
            )
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private companion object {
        const val RAMP_MILLIS = 20_000L
        const val MIN_VOLUME = 0.05f
    }
}
