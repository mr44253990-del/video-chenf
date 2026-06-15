package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
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

    private var lastNearTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val doubleWaveTimeout = 700L

    private val singleWaveRunnable = Runnable {
        performSwipeUp()
        vibrate(50)
        lastNearTime = 0L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Setup Proximity Sensor
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Setup Overlay
        setupOverlay()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = TextView(this).apply {
            text = "⬤ Active"
            setTextColor(Color.GREEN)
            textSize = 14f
            setPadding(32, 64, 32, 32)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            
            // Assume < 3.0f or less than maxRange is "NEAR"
            val isNear = distance < maxRange && distance < 3.0f
            
            if (isNear) {
                val now = System.currentTimeMillis()
                if (lastNearTime > 0 && (now - lastNearTime) < doubleWaveTimeout) {
                    // Double wave detected
                    handler.removeCallbacks(singleWaveRunnable)
                    performDoubleTap()
                    vibrate(100)
                    lastNearTime = 0L // Reset
                } else {
                    lastNearTime = now
                    handler.postDelayed(singleWaveRunnable, doubleWaveTimeout)
                }
            }
        }
    }

    private fun performSwipeUp() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val path = Path()
        path.moveTo(width / 2f, height * 0.8f)
        path.lineTo(width / 2f, height * 0.2f)

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
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
        sensorManager.unregisterListener(this)
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
