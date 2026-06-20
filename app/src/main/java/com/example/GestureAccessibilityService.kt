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
import coil.load
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import android.net.Uri
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

class GestureAccessibilityService : AccessibilityService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var windowManager: WindowManager? = null
    
    // Bubble UI
    private var overlayRoot: LinearLayout? = null // For the Menu / Handle
    private var visualizerOverlayRoot: FrameLayout? = null // Transparent full-width
    private var mainBubble: TextView? = null
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
            visualizerOverlayRoot?.visibility = View.GONE
            dancerOverlayRoot?.visibility = View.GONE
            stopAudioVisualizer()
        } else {
            if (isAppSupported) {
                overlayRoot?.visibility = View.VISIBLE
                // visualizerOverlayRoot & dancerOverlayRoot are managed by onPlayingStateChanged
                startAudioVisualizer()
            } else {
                overlayRoot?.visibility = View.GONE
                visualizerOverlayRoot?.visibility = View.GONE
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
                            visualizerView?.updateFft(fft)
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
            visualizerView?.updateFft(null)
            animateBubbleScale(1f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun animateBubbleScale(scale: Float) {
        // removed scale for full width
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

    private var flashOverlayRoot: FrameLayout? = null

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        flashOverlayRoot = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#44FFFFFF")) // Semi-transparent white flash
        }
        val flashParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try { windowManager?.addView(flashOverlayRoot, flashParams) } catch (e: Exception) {}

        overlayRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val density = resources.displayMetrics.density
        val scale = prefs.getFloat("bubbleScale", 1.0f)
        val visSize = (140 * density * scale).toInt()
        
        visualizerView = VisualizerBubbleView(this)
        
        visualizerView?.onPlayingStateChanged = { isPlaying ->
            handler.post {
                val showDancer = prefs.getBoolean("showDancer", true)
                if (isPlaying) {
                    visualizerOverlayRoot?.visibility = View.VISIBLE
                    if (showDancer) dancerOverlayRoot?.visibility = View.VISIBLE
                } else {
                    visualizerOverlayRoot?.visibility = View.GONE
                    dancerOverlayRoot?.visibility = View.GONE
                }
            }
        }
        
        visualizerView?.onBeatListener = {
            if (dancerImageView != null && dancerImageView?.visibility == View.VISIBLE) {
                val currentScaleX = dancerImageView?.scaleX ?: 1f
                val signX = if (currentScaleX < 0) -1f else 1f
                // Intense jump and pump
                dancerImageView?.animate()?.cancel()
                dancerImageView?.animate()
                    ?.scaleX(signX * 1.3f) 
                    ?.scaleY(0.8f) // Squat
                    ?.translationY(-80f) // Jump up
                    ?.setDuration(50)
                    ?.withEndAction {
                        dancerImageView?.animate()
                            ?.scaleX(signX * 1.0f)
                            ?.scaleY(1f)
                            ?.translationY(0f)
                            ?.setDuration(150)
                            ?.start()
                    }?.start()
            }
        }

        visualizerView?.onDropListener = {
            handler.post {
                flashOverlayRoot?.visibility = View.VISIBLE
                flashOverlayRoot?.alpha = 1f
                flashOverlayRoot?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
                    flashOverlayRoot?.visibility = View.GONE
                }?.start()
                vibrate(150)
            }
        }

        visualizerOverlayRoot = FrameLayout(this).apply {
            visibility = View.GONE
            clipChildren = false
            addView(visualizerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val visParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (150 * density * scale).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            y = 0 // Keep right at the bottom
        }
        try { windowManager?.addView(visualizerOverlayRoot, visParams) } catch (e: Exception) {}

        mainBubble = TextView(this).apply {
            visibility = View.GONE
            text = ""
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams((60 * density).toInt(), (60 * density).toInt()).apply {
                setMargins(8, 8, 8, 8)
            }
        }

        var clickDelay = 300L
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        mainBubble?.setOnTouchListener { _, event ->
            val p = overlayRoot?.layoutParams as? WindowManager.LayoutParams
            if (p == null) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x
                    initialY = p.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastClickTime < clickDelay) {
                        toggleMenu()
                    }
                    lastClickTime = currentTime
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - initialTouchX) > 10 || abs(event.rawY - initialTouchY) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val deltaY = (initialTouchY - event.rawY).toInt()
                        p.x = initialX + (event.rawX - initialTouchX).toInt()
                        p.y = initialY + deltaY
                        windowManager?.updateViewLayout(overlayRoot, p)
                        
                        // Sync Dancer Y (optional if user wants it draggable, else keep fixed)
                        // Removed to keep dancer fixed on bottom
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    true
                }
                else -> false
            }
        }



        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
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
            visibility = View.GONE
        }
        
        dancerImageView = android.widget.ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size * 2, Gravity.CENTER)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        
        dancerOverlayRoot?.addView(dancerImageView)
        
        // Draggable logic for Dancer (removed manual drag, syncs with base)

        val dancerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 100
            y = 200 // Starts above visualizer
        }
        
        try {
            windowManager?.addView(dancerOverlayRoot, dancerParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        startDancerRoutine()

        loadDancerImage()
    }

    private var currentGifPath: String? = null

    private fun loadDancerImage() {
        val customGifPath = prefs.getString("customGifPath", null)
        if (customGifPath == currentGifPath && dancerImageView?.drawable != null) return
        currentGifPath = customGifPath
        val imageFile = if (customGifPath != null) File(customGifPath) else null

        dancerImageView?.load(imageFile ?: R.drawable.anime_dancer_1781574906468) {
            decoderFactory { result, options, _ ->
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    ImageDecoderDecoder(result.source, options)
                } else {
                    GifDecoder(result.source, options)
                }
            }
            crossfade(true)
        }
    }

    private fun startDancerRoutine() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val maxTranslateX = screenWidth / 2.5f
        
        val runAnim = object : Runnable {
            var direction = 1f
            var currentX = 0f
            
            override fun run() {
                if (dancerImageView?.visibility == View.VISIBLE && !isPaused) {
                    val bpm = visualizerView?.currentBpm ?: 0
                    val speedMultiplier = if (bpm > 0) (bpm / 120f).coerceIn(0.5f, 2.0f) else 1f
                    currentX += direction * 2f * speedMultiplier
                    
                    if (currentX > maxTranslateX) {
                        direction = -1f
                        dancerImageView?.scaleX = -(dancerImageView?.scaleX?.let { abs(it) } ?: 1f)
                    } else if (currentX < -10f) { // don't go too far off screen left
                        direction = 1f
                        dancerImageView?.scaleX = (dancerImageView?.scaleX?.let { abs(it) } ?: 1f)
                    }
                    
                    dancerImageView?.translationX = currentX
                    val bob = sin((currentX / 20f).toDouble()).toFloat() * 10f
                    // Only bob if we aren't currently jumping from a beat
                    if (dancerImageView?.translationY?.compareTo(-40f)!! > 0) {
                        dancerImageView?.translationY = bob
                    }
                }
                handler.postDelayed(this, 30)
            }
        }
        handler.post(runAnim)
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
        val visHeight = (150 * density * scale).toInt()
        
        val vp = visualizerOverlayRoot?.layoutParams as? WindowManager.LayoutParams
        if (vp != null) {
            vp.height = visHeight
            windowManager?.updateViewLayout(visualizerOverlayRoot, vp)
        }
        
        // Dancer scale
        val dScale = prefs.getFloat("dancerScale", 1.0f)
        val dSize = (150 * density * dScale).toInt()
        dancerImageView?.layoutParams = FrameLayout.LayoutParams(dSize, dSize * 2, Gravity.CENTER)
        
        val showDancer = prefs.getBoolean("showDancer", true)
        val isPlaying = visualizerView?.isPlaying ?: false
        dancerOverlayRoot?.visibility = if (isAppSupported && showDancer && isScreenOn && isPlaying) View.VISIBLE else View.GONE
    }

    private fun updateOverlaySettings() {
        updateOverlayText()
        loadDancerImage()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (isPaused || !isAppSupported) return
        if (!prefs.getBoolean("sensorEnabled", true)) return

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
            visualizerOverlayRoot?.let { windowManager?.removeView(it) }
            dancerOverlayRoot?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
