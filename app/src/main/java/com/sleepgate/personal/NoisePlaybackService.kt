package com.sleepgate.personal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class NoisePlaybackService : Service() {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        val minutes = (intent?.getIntExtra(EXTRA_DURATION_MINUTES, 30) ?: 30).coerceIn(1, 180)
        createNotificationChannel()
        val notification = buildNotification(minutes)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startPlayback(minutes)
        return START_NOT_STICKY
    }

    private fun startPlayback(minutes: Int) {
        running.set(false)
        stopAudioResources()
        val totalMs = minutes * 60_000L
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_DURATION, minutes)
            .remove(KEY_ERROR)
            .apply()

        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            recordError("Audio output is unavailable on this device.")
            stopSelf()
            return
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBytes, BLOCK_FRAMES * 2 * 4))
            .build()

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            audioTrack.release()
            recordError("Could not initialize audio output.")
            stopSelf()
            return
        }

        track = audioTrack
        running.set(true)
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:SleepGateAudio").apply {
                setReferenceCounted(false)
                acquire(totalMs + 60_000L)
            }
            audioTrack.play()
        } catch (e: Exception) {
            recordError(e.message ?: "Audio start failed.")
            stopPlayback()
            stopSelf()
            return
        }

        worker = Thread({ render(audioTrack, totalMs) }, "SleepGateAudioRender").apply {
            isDaemon = true
            start()
        }
    }

    private fun render(audioTrack: AudioTrack, totalMs: Long) {
        val random = Random()
        val buffer = ShortArray(BLOCK_FRAMES)
        var filtered = 0f
        val startMs = SystemClock.elapsedRealtime()
        val fadeMs = 45_000L
        try {
            while (running.get()) {
                val elapsed = SystemClock.elapsedRealtime() - startMs
                if (elapsed >= totalMs) break
                for (i in buffer.indices) {
                    val t = elapsed + i * 1000L / SAMPLE_RATE
                    val white = random.nextFloat() * 2f - 1f
                    // Stable low-pass noise; intentionally kept at low digital gain.
                    filtered += 0.035f * (white - filtered)
                    val fadeIn = (t / 2_000f).coerceIn(0f, 1f)
                    val fadeOut = if (t < totalMs - fadeMs) 1f
                        else ((totalMs - t).toFloat() / fadeMs.toFloat()).coerceIn(0f, 1f)
                    val gain = BASE_GAIN * fadeIn * fadeOut
                    buffer[i] = (filtered * gain * Short.MAX_VALUE)
                        .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                val written = audioTrack.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) throw IllegalStateException("AudioTrack write failed ($written).")
            }
        } catch (e: Exception) {
            if (running.get()) recordError(e.message ?: "Audio playback stopped unexpectedly.")
        } finally {
            running.set(false)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
            stopSelf()
        }
    }

    private fun stopPlayback() {
        running.set(false)
        stopAudioResources()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
    }

    private fun stopAudioResources() {
        val current = track
        if (current != null) {
            try { current.pause() } catch (_: Exception) { }
            try { current.flush() } catch (_: Exception) { }
            try { current.stop() } catch (_: Exception) { }
        }
        val thread = worker
        if (thread != null && thread !== Thread.currentThread()) {
            try { thread.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        try { current?.release() } catch (_: Exception) { }
        track = null
        worker = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wakeLock = null
    }

    private fun recordError(message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_ERROR, message)
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "SleepGate audio", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(minutes: Int): Notification {
        val stopIntent = Intent(this, NoisePlaybackService::class.java).apply { action = ACTION_STOP }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val stopPending = PendingIntent.getService(this, 12, stopIntent, flags)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SleepGate audio is playing")
            .setContentText("$minutes-minute timer · low-level filtered noise")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    override fun onDestroy() {
        stopPlayback()
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.sleepgate.personal.STOP"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val PREFS = "sleepgate_playback"
        const val KEY_ACTIVE = "active"
        const val KEY_DURATION = "duration"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "sleepgate_audio"
        private const val NOTIFICATION_ID = 1042
        private const val SAMPLE_RATE = 48_000
        private const val BLOCK_FRAMES = 480
        private const val BASE_GAIN = 0.08f
    }
}
