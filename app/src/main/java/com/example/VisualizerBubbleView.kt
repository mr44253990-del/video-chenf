package com.example

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class VisualizerBubbleView(context: Context) : View(context) {
    private var magnitudes = FloatArray(128)
    var isPlaying = false
        private set(value) {
            if (field != value) {
                field = value
                onPlayingStateChanged?.invoke(value)
            }
        }
    private var idlePhase = 0f
    
    var onPlayingStateChanged: ((Boolean) -> Unit)? = null
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 15f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private val colors = intArrayOf(
        Color.parseColor("#FF0055"),
        Color.parseColor("#FFFF00"),
        Color.parseColor("#00FF55"),
        Color.parseColor("#00FFFF"),
        Color.parseColor("#FF0055")
    )
    
    private fun randomizeColors() {
        val hsv = FloatArray(3)
        hsv[1] = 1f // saturation
        hsv[2] = 1f // value
        val baseHue = Random.nextFloat() * 360f
        
        for (i in colors.indices) {
            hsv[0] = (baseHue + i * 40f) % 360f
            colors[i] = Color.HSVToColor(hsv)
        }
    }

    class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var maxLife: Float, var color: Int, var size: Float)
    private val particles = mutableListOf<Particle>()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lastBeatTime = 0L
    private val beatIntervals = mutableListOf<Long>()
    var currentBpm = 0
        private set

    private var trackStartTime = 0L
    private var lastAudioTime = 0L
    private var displaySeconds = 0

    var onBeatListener: (() -> Unit)? = null
    var onDropListener: (() -> Unit)? = null
    private var lastDropTime = 0L
    private var intensityMultiplier = 1f

    private val drawPath = Path()
    private val drawMatrix = Matrix()

    fun resetTimer() {
        trackStartTime = System.currentTimeMillis()
        displaySeconds = 0
        currentBpm = 0
        beatIntervals.clear()
        invalidate()
    }

    fun updateFft(fft: ByteArray?) {
        val currentTime = System.currentTimeMillis()
        if (fft == null || fft.isEmpty()) {
            if (isPlaying && currentTime - lastAudioTime > 2000) {
                isPlaying = false
                currentBpm = 0
                trackStartTime = 0L
            }
            invalidate()
            return
        }
        
        var maxMag = 0f
        val n = Math.min(fft.size / 2, 128)
        for (i in 0 until n) {
            val real = fft[i * 2].toFloat()
            val imag = fft[i * 2 + 1].toFloat()
            val mag = kotlin.math.sqrt(real * real + imag * imag)
            magnitudes[i] = mag
            if (mag > maxMag) maxMag = mag
        }
        
        if (maxMag > 15f) {
            if (!isPlaying || trackStartTime == 0L) {
                isPlaying = true
                trackStartTime = currentTime
                beatIntervals.clear()
            }
            lastAudioTime = currentTime
            
            // Beat detection
            if (maxMag > 60f && currentTime - lastBeatTime > 350) {
                if (lastBeatTime > 0) {
                    val interval = currentTime - lastBeatTime
                    if (interval < 2000) {
                        beatIntervals.add(interval)
                        if (beatIntervals.size > 8) beatIntervals.removeAt(0)
                        val avg = beatIntervals.average()
                        if (avg > 0) currentBpm = (60000 / avg).toInt().coerceIn(60, 200)
                    }
                }
                lastBeatTime = currentTime
                intensityMultiplier = 1.6f
                onBeatListener?.invoke()
                spawnParticles(maxMag, true)
                
                if (maxMag > 85f && currentTime - lastDropTime > 2000) {
                    lastDropTime = currentTime
                    randomizeColors()
                    onDropListener?.invoke()
                }
            } else if (Random.nextFloat() > 0.8f && maxMag > 30f) {
                spawnParticles(maxMag, false)
            }
        } else {
            if (currentTime - lastAudioTime > 2000) {
                isPlaying = false
                currentBpm = 0
                trackStartTime = 0L
            }
        }
        
        intensityMultiplier = (intensityMultiplier - 0.05f).coerceAtLeast(1f)
        
        if (trackStartTime > 0) {
            displaySeconds = ((currentTime - trackStartTime) / 1000).toInt()
        } else {
            displaySeconds = 0
        }
        
        invalidate()
    }
    
    private fun spawnParticles(intensity: Float, isBeat: Boolean) {
        val yBase = height - 80f
        val count = if (isBeat) Random.nextInt(15, 30) else Random.nextInt(2, 6)
        
        for (i in 0 until count) {
            val px = Random.nextFloat() * width
            val py = yBase + (Random.nextFloat() * 20f - 10f)
            
            val vx = Random.nextFloat() * 4f - 2f
            val vy = if (isBeat) -(Random.nextFloat() * 15f + 8f) else -(Random.nextFloat() * 5f + 2f)
            
            val life = Random.nextFloat() * 20f + (if (isBeat) 25f else 15f)
            val color = colors[Random.nextInt(colors.size)]
            val size = Random.nextFloat() * 6f + 3f
            
            particles.add(Particle(px, py, vx, vy, life, life, color, size))
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h - 80f // Near bottom
        
        val prefs = context.getSharedPreferences("WaveScrollPrefs", Context.MODE_PRIVATE)
        val style = prefs.getString("visualizerStyle", "Wave") ?: "Wave"

        val linearGradient = LinearGradient(0f, cy - 80f, w, cy + 80f, colors, null, Shader.TileMode.CLAMP)
        paint.shader = linearGradient
        glowPaint.shader = linearGradient
        
        drawPath.reset()
        
        if (style == "Wave") {
            drawWave(canvas, w, h, cy, linearGradient)
        } else if (style == "Bars") {
            drawBars(canvas, w, h, cy, linearGradient)
        } else if (style == "Circle") {
            drawCircle(canvas, w, h, cy)
        }

        val iter = particles.iterator()
        while(iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx
            p.y += p.vy
            p.vy += 0.5f // Gravity
            p.life -= 1f
            
            if (p.life <= 0) {
                iter.remove()
            } else {
                particlePaint.color = p.color
                particlePaint.alpha = ((p.life / p.maxLife) * 255).toInt()
                canvas.drawCircle(p.x, p.y, p.size, particlePaint)
            }
        }
        
        idlePhase += 0.2f
        
        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.RIGHT
        
        if (isPlaying || displaySeconds > 0) {
            val rightMargin = w - 40f
            val baseTextY = h - 20f
            
            val min = displaySeconds / 60
            val sec = displaySeconds % 60
            val timeText = String.format("%d:%02d", min, sec)
            textPaint.textSize = 35f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            canvas.drawText(timeText, rightMargin, baseTextY, textPaint)
            
            val bpmText = if (currentBpm > 0) "$currentBpm" else "--"
            textPaint.textSize = 30f
            val bpmWidth = textPaint.measureText(timeText) + 30f
            canvas.drawText(bpmText, rightMargin - bpmWidth, baseTextY, textPaint)
            
            textPaint.textSize = 20f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textPaint.color = Color.LTGRAY
            canvas.drawText("BPM", rightMargin - bpmWidth, baseTextY - 30f, textPaint)
            
        }
        
        postInvalidateOnAnimation()
    }
    
    private fun drawWave(canvas: Canvas, w: Float, h: Float, cy: Float, gradient: LinearGradient) {
        if (isPlaying) {
            val points = 60
            val dx = w / (points - 1)
            
            drawPath.moveTo(0f, cy)
            var prevX = 0f
            var prevY = cy
            
            for (i in 1 until points) {
                val magIndex = (i * magnitudes.size / points).coerceIn(0, magnitudes.size - 1)
                var mag = magnitudes[magIndex] * 1.5f
                mag = mag.coerceIn(0f, h * 0.6f) * intensityMultiplier
                
                val edgeFade = sin((i.toFloat() / (points - 1)) * Math.PI).toFloat()
                mag *= edgeFade
                
                val sign = if (i % 2 == 0) 1 else -1
                val x = i * dx
                val y = cy - (mag * sign)
                
                val cx = (prevX + x) / 2f
                drawPath.cubicTo(cx, prevY, cx, y, x, y)
                
                prevX = x
                prevY = y
            }
        } else {
            idlePhase += 0.04f
            val breath = (sin(idlePhase.toDouble()).toFloat()) * h * 0.03f
            
            val points = 40
            val dx = w / (points - 1)
            
            drawPath.moveTo(0f, cy)
            var prevX = 0f
            var prevY = cy
            
            for (i in 1 until points) {
                val edgeFade = sin((i.toFloat() / (points - 1)) * Math.PI).toFloat()
                val sign = if (i % 2 == 0) 1 else -1
                val x = i * dx
                val y = cy - (breath * edgeFade * sign)
                
                val cx = (prevX + x) / 2f
                drawPath.cubicTo(cx, prevY, cx, y, x, y)
                
                prevX = x
                prevY = y
            }
        }
        canvas.drawPath(drawPath, glowPaint)
        canvas.drawPath(drawPath, paint)
    }

    private fun drawBars(canvas: Canvas, w: Float, h: Float, cy: Float, gradient: LinearGradient) {
        val count = 20
        val barWidth = w / count * 0.6f
        val gap = w / count * 0.4f
        for (i in 0 until count) {
            val magIndex = (i * magnitudes.size / count).coerceIn(0, magnitudes.size - 1)
            var mag = if (isPlaying) magnitudes[magIndex] * 1.5f * intensityMultiplier else 5f
            mag = mag.coerceIn(5f, h * 0.5f)
            val px = i * (barWidth + gap) + gap/2
            canvas.drawRoundRect(px, cy - mag, px + barWidth, cy, 10f, 10f, glowPaint)
            canvas.drawRoundRect(px, cy - mag, px + barWidth, cy, 10f, 10f, paint)
        }
    }

    private fun drawCircle(canvas: Canvas, w: Float, h: Float, cy: Float) {
        val cx = w / 2
        val radius = 80f
        
        val radialGradient = RadialGradient(cx, cy - radius, radius * 2, colors, null, Shader.TileMode.CLAMP)
        paint.shader = radialGradient
        glowPaint.shader = radialGradient
        
        drawPath.reset()
        val count = 60
        val angleStep = Math.PI * 2 / count
        
        for (i in 0..count) {
            val angle = i * angleStep
            val innerSize = radius + if (!isPlaying) sin(idlePhase).toFloat() * 5f else 0f
            val magIndex = (i * magnitudes.size / count).coerceIn(0, magnitudes.size - 1)
            var mag = if (isPlaying) magnitudes[magIndex] * 1.5f * intensityMultiplier else 0f
            mag = mag.coerceIn(0f, h * 0.4f)
            
            val r = innerSize + mag
            val x = cx + cos(angle).toFloat() * r
            val y = (cy - radius) + sin(angle).toFloat() * r
            
            if (i == 0) drawPath.moveTo(x, y)
            else drawPath.lineTo(x, y)
        }
        canvas.drawPath(drawPath, glowPaint)
        canvas.drawPath(drawPath, paint)
        postInvalidateOnAnimation()
    }
}
