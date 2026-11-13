package com.drivelog.app.core.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.drivelog.app.BuildConfig
import com.drivelog.app.R

enum class SoundCue(val resource: Int, val durationMs: Long) {
    START(R.raw.sound_start, 320),
    SAVED(R.raw.sound_saved, 440),
    CONFIRM(R.raw.sound_confirm, 180),
    ERROR(R.raw.sound_error, 300),
}

/** One short cue at a time. Never queues late sounds or changes the user's volume. Main thread only. */
class SoundEffects(context: Context) {
    private val prefs = context.getSharedPreferences("drivelog", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
    private val ids = mutableMapOf<SoundCue, Int>()
    private val loaded = mutableSetOf<Int>()
    private var stream = 0
    private var completion: Runnable? = null
    private var lastPlayed = -1000L
    private var closed = false
    private var hasFocus = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) stop()
        }, handler).build()

    var enabled: Boolean
        get() = prefs.getBoolean("sound_effects_enabled", true)
        set(value) {
            prefs.edit().putBoolean("sound_effects_enabled", value).apply()
            if (!value) stop()
        }

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            handler.post { if (!closed && status == 0) loaded.add(id) }
        }
        SoundCue.entries.forEach { cue -> ids[cue] = pool.load(context, cue.resource, 1) }
    }

    /** Missing samples/focus never hold up spoken guidance. Focus loss cancels the entire pending cue. */
    fun play(cue: SoundCue, after: () -> Unit = {}) {
        if (closed || !enabled || ids[cue] !in loaded || SystemClock.uptimeMillis() - lastPlayed < 250) {
            after()
            return
        }
        stop()
        val granted = runCatching { audio.requestAudioFocus(focus) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            after()
            return
        }
        hasFocus = true
        stream = runCatching { pool.play(ids.getValue(cue), .55f, .55f, 1, 0, 1f) }.getOrDefault(0)
        if (stream == 0) {
            stop()
            after()
            return
        }
        lastPlayed = SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG) Log.d("DriveLogSound", "playing ${cue.name}; stream=$stream")
        completion = Runnable {
            completion = null
            stop()
            after()
        }.also { handler.postDelayed(it, cue.durationMs + 40) }
    }

    fun stop() {
        completion?.let(handler::removeCallbacks)
        completion = null
        if (stream != 0) pool.stop(stream)
        stream = 0
        if (hasFocus) {
            hasFocus = false
            audio.abandonAudioFocusRequest(focus)
        }
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
        pool.release()
        loaded.clear()
    }
}
