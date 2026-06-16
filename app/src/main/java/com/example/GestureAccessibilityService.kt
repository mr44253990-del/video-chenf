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
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlin.math.abs

class GestureAccessibilityService : AccessibilityService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var windowManager: WindowManager? = null
    
    // Bubble UI
    private var overlayRoot: LinearLayout? = null
    private var bubbleContainer: FrameLayout? = null
    private var visualizerView: VisualizerBubbleView? = null
    private var expandedControls: LinearLayout? = null
    private var isMenuExpanded = false

    // Dancer UI
    private var dancerOverlayRoot: FrameLayout? = null
    private var dancerImageView: android.widget.ImageView? = null

    private var isPaused = false
    private var lastClickTime = 0L

    // Smart Gesture State
    private var nearStartTime = 0L
    private var waveCount = 0
    private var longPressTriggered = false
    private val handler = Handler(Looper.getMainLooper())

    private var isScreenOn = true
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    updateOverlayVisibility()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOn = true
                    updateOverlayVisibility()
                }
            }
        }
    }

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

        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })

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
                updateOverlayVisibility()
                updateOverlayText()
            }
        }
    }

    private fun updateOverlayVisibility() {
        if (!isScreenOn) {
            overlayRoot?.visibility = View.GONE
            dancerOverlayRoot?.visibility = View.GONE
            stopAudioVisualizer()
        } else {
            if (isAppSupported) {
                overlayRoot?.visibility = View.VISIBLE
                val showDancer = prefs.getBoolean("showDancer", true)
                dancerOverlayRoot?.visibility = if (showDancer) View.VISIBLE else View.GONE
                startAudioVisualizer()
            } else {
                overlayRoot?.visibility = View.GONE
                dancerOverlayRoot?.visibility = View.GONE
                stopAudioVisualizer()
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

    private var dimView: View? = null
    private var isAutoScrollEnabled = false
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (isAutoScrollEnabled && isAppSupported && !isPaused) {
                performSwipeUp()
                handler.postDelayed(this, 10000L) // every 10 seconds
            }
        }
    }
    
    private fun toggleDimMode() {
        if (dimView != null) {
            try { windowManager?.removeView(dimView) } catch (e: Exception){}
            dimView = null
        } else {
            dimView = View(this).apply {
                setBackgroundColor(Color.parseColor("#99000000"))
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            try { windowManager?.addView(dimView, params) } catch (e: Exception){}
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
        val scale = prefs.getFloat("bubbleScale", 1.0f)
        val visSize = (140 * density * scale).toInt()
        
        visualizerView = VisualizerBubbleView(this)
        
        visualizerView?.onBeatListener = {
            if (dancerImageView != null && dancerImageView?.visibility == View.VISIBLE) {
                dancerImageView?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(50)?.withEndAction {
                    dancerImageView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(150)?.start()
                }?.start()
            }
        }

        bubbleContainer = FrameLayout(this).apply {
            addView(visualizerView, FrameLayout.LayoutParams(visSize, visSize, Gravity.CENTER))

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
            
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            var isMuted = false
            val muteBtn = createControlButton("🔇", "Mute") {
                isMuted = !isMuted
                val direction = if (isMuted) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE
                audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, android.media.AudioManager.FLAG_SHOW_UI)
                vibrate(20)
            }
            
            val brightnessBtn = createControlButton("🔆", "Dim") {
                toggleDimMode()
                vibrate(20)
            }
            
            val autoScrollBtn = createControlButton("⏱️", "Auto") {
                isAutoScrollEnabled = !isAutoScrollEnabled
                if (isAutoScrollEnabled) {
                    handler.postDelayed(autoScrollRunnable, 5000L)
                } else {
                    handler.removeCallbacks(autoScrollRunnable)
                }
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
        
        setupDancerOverlay()
    }
    
    private fun setupDancerOverlay() {
        val density = resources.displayMetrics.density
        val scale = prefs.getFloat("dancerScale", 1.0f)
        val size = (150 * density * scale).toInt()
        
        dancerOverlayRoot = FrameLayout(this).apply {
            val showDancer = prefs.getBoolean("showDancer", true)
            visibility = if (isAppSupported && showDancer && isScreenOn) View.VISIBLE else View.GONE
        }
        
        dancerImageView = android.widget.ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size * 2, Gravity.CENTER)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        
        dancerOverlayRoot?.addView(dancerImageView)
        
        // Draggable logic for Dancer
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dancerOverlayRoot?.setOnTouchListener { _, event ->
            val params = dancerOverlayRoot?.layoutParams as? WindowManager.LayoutParams
            if (params == null) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(dancerOverlayRoot, params)
                    true
                }
                else -> false
            }
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 50
            y = 200
        }
        
        try {
            windowManager?.addView(dancerOverlayRoot, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Load and process image asynchronously
        Thread {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.anime_dancer_1781574906468)
                if (bitmap != null) {
                    val transparentBitmap = ImageUtils.removeGreenScreen(bitmap)
                    handler.post {
                        dancerImageView?.setImageBitmap(transparentBitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun createControlButton(icon: String, label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = icon
            textSize = 20f
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#99000000"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onClick()
                android.widget.Toast.makeText(context, "$label Option Activated", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleMenu() {
        isMenuExpanded = !isMenuExpanded
        expandedControls?.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
        vibrate(30)
    }

    private fun updateOverlayText() {
        val scale = prefs.getFloat("bubbleScale", 1.0f)
        val density = resources.displayMetrics.density
        val visSize = (140 * density * scale).toInt()
        
        visualizerView?.layoutParams = FrameLayout.LayoutParams(visSize, visSize, Gravity.CENTER)
        
        val dScale = prefs.getFloat("dancerScale", 1.0f)
        val dSize = (150 * density * dScale).toInt()
        dancerImageView?.layoutParams = FrameLayout.LayoutParams(dSize, dSize * 2, Gravity.CENTER)
        
        val showDancer = prefs.getBoolean("showDancer", true)
        dancerOverlayRoot?.visibility = if (isAppSupported && showDancer && isScreenOn) View.VISIBLE else View.GONE
    }

    private fun updateOverlaySettings() {
        updateOverlayText()
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
        visualizerView?.resetTimer()
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
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}
        try {
            overlayRoot?.let { windowManager?.removeView(it) }
            dancerOverlayRoot?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
