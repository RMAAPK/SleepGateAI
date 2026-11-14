package com.sleepgate.personal

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private val durations = listOf(15, 30, 45, 60)
    private lateinit var statusView: TextView
    private lateinit var durationSpinner: Spinner
    private var queuedMinutes: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 24, 43)
        window.navigationBarColor = Color.rgb(16, 24, 43)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildUi() {
        val bg = Color.rgb(16, 24, 43)
        val card = Color.rgb(27, 39, 64)
        val white = Color.rgb(242, 246, 255)
        val muted = Color.rgb(174, 188, 211)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(24))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply { addView(page) }

        val title = TextView(this).apply {
            text = "SleepGate"
            textSize = 32f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        page.addView(title)
        page.addView(text("Personal audio prototype", 16f, muted).withTop(4))

        val note = TextView(this).apply {
            text = "Offline, soft filtered noise with a timer and gentle fade-out. This is not a sleep-stage detector, medical treatment, or calibrated sound-level limiter."
            textSize = 14f
            setTextColor(muted)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(card)
        }
        page.addView(note.withTop(22))

        page.addView(text("Timer", 17f, white).withTop(28))
        durationSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                durations.map { "$it minutes (fade during final 45 sec)" }
            )
            setSelection(1)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        page.addView(durationSpinner.withTop(8))

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(muted)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(card)
        }
        page.addView(statusView.withTop(20))

        val start = Button(this).apply {
            text = "Start soft noise"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(57, 105, 163))
            setOnClickListener { requestNotificationPermissionOrStart() }
        }
        page.addView(start.withTop(20, matchWidth = true))

        val stop = Button(this).apply {
            text = "Stop"
            textSize = 16f
            setTextColor(white)
            setBackgroundColor(Color.rgb(61, 71, 91))
            setOnClickListener { sendStop() }
        }
        page.addView(stop.withTop(10, matchWidth = true))

        page.addView(text("Use your device volume carefully; actual SPL varies by device and speaker. No microphone, camera, or biometric data is collected by this audio-only prototype.", 13f, muted).withTop(16))
        setContentView(scroll)
    }

    private fun requestNotificationPermissionOrStart() {
        val minutes = durations.getOrElse(durationSpinner.selectedItemPosition) { 30 }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            queuedMinutes = minutes
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        startPlayback(minutes)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            val minutes = queuedMinutes ?: 30
            queuedMinutes = null
            // Foreground playback can still start if notification permission is declined;
            // Android may show the service notification in its task manager instead.
            startPlayback(minutes)
        }
    }

    private fun startPlayback(minutes: Int) {
        val intent = Intent(this, NoisePlaybackService::class.java)
            .putExtra(NoisePlaybackService.EXTRA_DURATION_MINUTES, minutes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        statusView.text = "Starting… $minutes-minute session."
    }

    private fun sendStop() {
        val intent = Intent(this, NoisePlaybackService::class.java).apply {
            action = NoisePlaybackService.ACTION_STOP
        }
        startService(intent)
        statusView.text = "Stopping…"
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(NoisePlaybackService.PREFS, MODE_PRIVATE)
        val active = prefs.getBoolean(NoisePlaybackService.KEY_ACTIVE, false)
        val minutes = prefs.getInt(NoisePlaybackService.KEY_DURATION, 30)
        val error = prefs.getString(NoisePlaybackService.KEY_ERROR, null)
        statusView.text = when {
            active -> "Playing soft filtered noise · $minutes-minute timer · fade to digital silence during the final 45 seconds."
            !error.isNullOrBlank() -> "Audio unavailable: $error"
            else -> "Idle · nothing is recording or being uploaded."
        }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun android.view.View.withTop(top: Int, matchWidth: Boolean = false): android.view.View {
        val width = if (matchWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            if (this@withTop is TextView && this@withTop.textSize >= 30f) gravity = Gravity.CENTER_VERTICAL
        }
        return this
    }

    companion object { private const val REQUEST_NOTIFICATIONS = 7001 }
}
