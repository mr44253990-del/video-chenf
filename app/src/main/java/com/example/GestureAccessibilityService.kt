package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlin.math.abs

class GestureAccessibilityService : AccessibilityService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var windowManager: WindowManager? = null
    
    // Bubble UI
    private var overlayRoot: LinearLayout? = null
    private var mainBubble: TextView? = null
    private var bubbleContainer: FrameLayout? = null
    private var visualizerView: VisualizerBubbleView? = null
    private var expandedControls: LinearLayout? = null
    private var isMenuExpanded = false

    private var isPaused = false
    private var lastClickTime = 0L

    // Smart Gesture State
    private var nearStartTime = 0L
    private var waveCount = 0
    private var longPressTriggered = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: SharedPreferences

    // App-Specific Detection
    private var isAppSupported = true // Auto-hide disabled per user request
    private var currentPackage = ""
    private var targetPackages = setOf<String>()
    
    // Audio Visualizer
    private var visualizer: Visualizer? = null

    private val waveActionRunnable = Runnable {
        if (!isPaused && isAppSupported && waveCount == 1) {
            performSwipeUp()
            vibrate(50)
        }
        waveCount = 0
    }

    private val longPressRunnable = Runnable {
        if (!isPaused && isAppSupported) {
            longPressTriggered = true
            performCenterTap() // Center tap for play/pause
            vibrate(100)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        updateOverlaySettings()
        targetPackages = getSupportedApps()
    }

    private fun getSupportedApps(): Set<String> {
        val base = setOf(
            "com.zhiliaoapp.musically", // TikTok (Asia)
            "com.zhiliaoapp.musically.go", // TikTok Lite
            "com.ss.android.ugc.trill", // TikTok (Global)
            "com.google.android.youtube", // YouTube
            "com.instagram.android", // Instagram
            "com.facebook.katana" // Facebook
        )
        val custom = prefs.getStringSet("customApps", emptySet()) ?: emptySet()
        return base + custom
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("WaveScrollPrefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        targetPackages = getSupportedApps()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        setupOverlay()
        updateOverlaySettings()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null && packageName != "com.android.systemui") {
                currentPackage = packageName
                targetPackages = getSupportedApps()
                // Auto-hide disabled so the bubble is always visible:
                isAppSupported = true 
                
                if (isAppSupported) {
                    overlayRoot?.visibility = View.VISIBLE
                    startAudioVisualizer()
                } else {
                    overlayRoot?.visibility = View.GONE
                    stopAudioVisualizer()
                }
                updateOverlayText()
            }
        }
    }

    private fun startAudioVisualizer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (visualizer == null) {
                    visualizer = Visualizer(0)
                    visualizer?.captureSize = Visualizer.getCaptureSizeRange()[1]
                    visualizer?.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                            if (waveform != null && waveform.isNotEmpty()) {
                                var magnitude = 0f
                                for (byte in waveform) {
                                    magnitude += abs(byte.toInt())
                                }
                                magnitude /= waveform.size
                                val scale = 1f + (magnitude / 128f) * 0.4f // subtle bounce
                                animateBubbleScale(scale)
                            }
                        }
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                            visualizerView?.updateVisualizer(fft)
                        }
                    }, Visualizer.getMaxCaptureRate() / 2, false, true)
                    visualizer?.enabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopAudioVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            visualizerView?.updateVisualizer(null)
            animateBubbleScale(1f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun animateBubbleScale(scale: Float) {
        handler.post {
            bubbleContainer?.scaleX = scale
            bubbleContainer?.scaleY = scale
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        overlayRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val density = resources.displayMetrics.density
        val visSize = (140 * density).toInt()
        val bubbleSize = (60 * density).toInt()
        
        visualizerView = VisualizerBubbleView(this)
        
        mainBubble = TextView(this).apply {
            text = "🖐"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6000000")) // Semi-transparent black center
            }
        }

        bubbleContainer = FrameLayout(this).apply {
            addView(visualizerView, FrameLayout.LayoutParams(visSize, visSize, Gravity.CENTER))
            addView(mainBubble, FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER))

            var clickDelay = 300L
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            setOnTouchListener { _, event ->
                val params = overlayRoot?.layoutParams as? WindowManager.LayoutParams
                if (params == null) return@setOnTouchListener false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        
                        val clickTime = System.currentTimeMillis()
                        if (clickTime - lastClickTime < clickDelay) {
                            // Double tap
                            isPaused = !isPaused
                            updateOverlayText()
                            vibrate(50)
                            lastClickTime = 0L
                        } else {
                            lastClickTime = clickTime
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(overlayRoot, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY
                        if (abs(diffX) < 10 && abs(diffY) < 10) {
                            // Simple click
                            if (System.currentTimeMillis() - lastClickTime < clickDelay) {
                                // Waiting for double tap, maybe toggle menu, but we already handled double tap in down if it was fast enough
                                // For single tap responsiveness, we might toggle menu here if not double clicked.
                                handler.postDelayed({
                                    if (lastClickTime != 0L) {
                                        toggleMenu()
                                        lastClickTime = 0L
                                    }
                                }, clickDelay)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        expandedControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(16, 0, 0, 0)
            
            val muteBtn = createControlButton("🔇", "Mute") {
                // Future Implementation for Volume Control
                vibrate(20)
            }
            val brightnessBtn = createControlButton("🔆", "Dim") {
                // Future Implementation for Brightness
                vibrate(20)
            }
            val autoScrollBtn = createControlButton("⏱️", "Auto") {
                // Future Implementation for Auto-scroll
                vibrate(20)
            }
            
            addView(muteBtn)
            addView(brightnessBtn)
            addView(autoScrollBtn)
        }

        overlayRoot?.addView(bubbleContainer)
        overlayRoot?.addView(expandedControls)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }
        try {
            windowManager?.addView(overlayRoot, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createControlButton(icon: String, label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = icon
            textSize = 18f
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#44FFFFFF"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun toggleMenu() {
        isMenuExpanded = !isMenuExpanded
        expandedControls?.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
        vibrate(30)
    }

    private fun updateOverlayText() {
        val colorScheme = prefs.getString("btnColor", "Green/Red") ?: "Green/Red"
        val activeColor = if (colorScheme == "Blue/Yellow") Color.parseColor("#00E5FF") else Color.GREEN
        val pausedColor = if (colorScheme == "Blue/Yellow") Color.YELLOW else Color.RED
        val idleColor = Color.GRAY
        
        var borderColor = activeColor
        var bubbleText = "🖐"
        
        if (!isAppSupported) {
            borderColor = idleColor
            bubbleText = "💤"
        } else if (isPaused) {
            borderColor = pausedColor
            bubbleText = "⏸"
        }
        
        mainBubble?.text = bubbleText
    }

    private fun updateOverlaySettings() {
        updateOverlayText()
        val sizeStr = prefs.getString("btnSize", "Medium") ?: "Medium"
        // Removing explicit gravity positions so dragging isn't restricted by START/END constraints
        
        mainBubble?.textSize = when(sizeStr) {
            "Small" -> 10f
            "Large" -> 18f
            else -> 14f
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (isPaused || !isAppSupported) return

        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val isNear = distance < maxRange

            val doubleWaveTimeout = prefs.getLong("doubleWaveTime", 250L)
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
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        sensorManager.unregisterListener(this)
        try {
            overlayRoot?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
