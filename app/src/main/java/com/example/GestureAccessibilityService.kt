package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

class GestureAccessibilityService : AccessibilityService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null

    private var isPaused = false
    private var lastClickTime = 0L

    // Smart Gesture State
    private var nearStartTime = 0L
    private var waveCount = 0
    private var longPressTriggered = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: SharedPreferences

    private val waveActionRunnable = Runnable {
        if (!isPaused && waveCount == 1) {
            performSwipeUp()
            vibrate(50)
        }
        waveCount = 0
    }

    private val longPressRunnable = Runnable {
        if (!isPaused) {
            longPressTriggered = true
            performCenterTap() // Center tap for play/pause
            vibrate(100)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateOverlaySettings()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("WaveScrollPrefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        setupOverlay()
        updateOverlaySettings()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val clickDelay = 400L
        overlayView = TextView(this).apply {
            text = "⬤ Active"
            setTextColor(Color.GREEN)
            setPadding(32, 64, 32, 32)
            setBackgroundColor(Color.TRANSPARENT)
            
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    val clickTime = System.currentTimeMillis()
                    if (clickTime - lastClickTime < clickDelay) {
                        isPaused = !isPaused
                        updateOverlayText()
                        vibrate(50)
                        lastClickTime = 0L
                    } else {
                        lastClickTime = clickTime
                    }
                }
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // allow touch events but not focus
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateOverlayText() {
        val colorScheme = prefs.getString("btnColor", "Green/Red") ?: "Green/Red"
        val activeColor = if (colorScheme == "Blue/Yellow") Color.BLUE else Color.GREEN
        val pausedColor = if (colorScheme == "Blue/Yellow") Color.YELLOW else Color.RED
        
        if (isPaused) {
            overlayView?.text = "⬤ Paused"
            overlayView?.setTextColor(pausedColor)
        } else {
            overlayView?.text = "⬤ Active"
            overlayView?.setTextColor(activeColor)
        }
    }

    private fun updateOverlaySettings() {
        updateOverlayText()
        val sizeStr = prefs.getString("btnSize", "Medium") ?: "Medium"
        val posStr = prefs.getString("btnPosition", "Top-End") ?: "Top-End"
        
        overlayView?.textSize = when(sizeStr) {
            "Small" -> 10f
            "Large" -> 18f
            else -> 14f
        }
        
        var gravity = Gravity.TOP or Gravity.END
        when(posStr) {
            "Top-Start" -> gravity = Gravity.TOP or Gravity.START
            "Bottom-End" -> gravity = Gravity.BOTTOM or Gravity.END
            "Bottom-Start" -> gravity = Gravity.BOTTOM or Gravity.START
        }
        
        val params = overlayView?.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            params.gravity = gravity
            try {
                windowManager?.updateViewLayout(overlayView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (isPaused) return

        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val isNear = distance < maxRange

            val doubleWaveTimeout = prefs.getLong("doubleWaveTime", 600L)
            val longPressTimeout = prefs.getLong("longPressTime", 1000L)

            if (isNear) {
                if (nearStartTime == 0L) {
                    nearStartTime = System.currentTimeMillis()
                    longPressTriggered = false
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                }
            } else {
                // FAR
                if (nearStartTime != 0L) {
                    val duration = System.currentTimeMillis() - nearStartTime
                    handler.removeCallbacks(longPressRunnable)

                    if (!longPressTriggered) {
                        // It was a short wave
                        waveCount++
                        if (waveCount == 1) {
                            handler.postDelayed(waveActionRunnable, doubleWaveTimeout)
                        } else if (waveCount == 2) {
                            handler.removeCallbacks(waveActionRunnable)
                            performDoubleTap()
                            vibrate(100)
                            waveCount = 0
                        }
                    }
                    nearStartTime = 0L // Reset
                }
            }
        }
    }

    private fun performSwipeUp() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        
        val scrollSpeed = prefs.getLong("scrollSpeed", 300L)

        val path = Path()
        path.moveTo(width / 2f, height * 0.8f)
        path.lineTo(width / 2f, height * 0.2f)

        val stroke = GestureDescription.StrokeDescription(path, 0, scrollSpeed)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    private fun performDoubleTap() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val cx = width / 2f
        val cy = height / 2f

        val path1 = Path().apply { moveTo(cx, cy) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 100)

        val path2 = Path().apply { moveTo(cx, cy) }
        val stroke2 = GestureDescription.StrokeDescription(path2, 200, 100)

        val builder = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)

        dispatchGesture(builder.build(), null, null)
    }

    private fun performCenterTap() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val cx = width / 2f
        val cy = height / 2f

        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)

        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    private fun vibrate(duration: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        sensorManager.unregisterListener(this)
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
