package com.example

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class VisualizerBubbleView(context: Context) : View(context) {
    private var magnitudes = FloatArray(128)
    private var isPlaying = false
    private var idlePhase = 0f
    
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
        Color.parseColor("#FF0055"), // Pink/Red
        Color.parseColor("#FF00FF"), // Magenta
        Color.parseColor("#0055FF"), // Blue
        Color.parseColor("#00FFFF"), // Cyan
        Color.parseColor("#00FF55"), // Green
        Color.parseColor("#FFFF00"), // Yellow
        Color.parseColor("#FF5500"), // Orange
        Color.parseColor("#FF0055")  // back to Pink/Red
    )

    // Particle System
    class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var maxLife: Float, var color: Int, var size: Float)
    private val particles = mutableListOf<Particle>()
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // BPM & Time Tracking
    private var lastBeatTime = 0L
    private val beatIntervals = mutableListOf<Long>()
    private var currentBpm = 0
    private var trackStartTime = 0L
    private var lastAudioTime = 0L
    private var displaySeconds = 0

    fun updateVisualizer(fft: ByteArray?) {
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
            if (maxMag > 50f && currentTime - lastBeatTime > 300) {
                if (lastBeatTime > 0) {
                    val interval = currentTime - lastBeatTime
                    if (interval < 2000) { // filter out long gaps
                        beatIntervals.add(interval)
                        if (beatIntervals.size > 8) beatIntervals.removeAt(0)
                        val avg = beatIntervals.average()
                        if (avg > 0) currentBpm = (60000 / avg).toInt().coerceIn(60, 200)
                    }
                }
                lastBeatTime = currentTime
                
                // Spawn particles on beat
                spawnParticles(maxMag)
            }
        } else {
            if (currentTime - lastAudioTime > 2000) {
                isPlaying = false
                currentBpm = 0
                trackStartTime = 0L
            }
        }
        
        if (trackStartTime > 0) {
            displaySeconds = ((currentTime - trackStartTime) / 1000).toInt()
        } else {
            displaySeconds = 0
        }
        
        invalidate()
    }
    
    private fun spawnParticles(intensity: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) * 0.45f
        
        val count = (intensity / 10f).toInt().coerceIn(3, 15)
        for (i in 0 until count) {
            val angle = Random.nextFloat() * Math.PI * 2
            val speed = Random.nextFloat() * 4f + 2f
            val life = Random.nextFloat() * 20f + 15f
            val px = cx + cos(angle).toFloat() * radius
            val py = cy + sin(angle).toFloat() * radius
            val vx = cos(angle).toFloat() * speed
            val vy = sin(angle).toFloat() * speed
            val color = colors[Random.nextInt(colors.size)]
            val size = Random.nextFloat() * 4f + 2f
            particles.add(Particle(px, py, vx, vy, life, life, color, size))
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height) / 2f) * 0.45f
        
        // Draw inner dark circle
        textPaint.color = Color.parseColor("#CC121212")
        textPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, baseRadius * 0.9f, textPaint)
        
        // Rotate gradient slowly
        val matrix = Matrix()
        matrix.postRotate(idlePhase * 15f, cx, cy)
        val sweepGradient = SweepGradient(cx, cy, colors, null)
        sweepGradient.setLocalMatrix(matrix)
        
        paint.shader = sweepGradient
        glowPaint.shader = sweepGradient
        
        if (isPlaying) {
            val path = Path()
            val points = 60
            val angleStep = Math.PI * 2 / points
            
            for (i in 0..points) {
                val idx = i % points
                val magIndex = (idx * 2).coerceAtMost(magnitudes.size - 1)
                var mag = magnitudes[magIndex] * 0.5f
                mag = mag.coerceIn(0f, baseRadius * 0.6f)
                
                val radius = baseRadius + mag
                
                val angle = i * angleStep - Math.PI / 2
                val x = cx + cos(angle).toFloat() * radius
                val y = cy + sin(angle).toFloat() * radius
                
                if (i == 0) path.moveTo(x, y)
                else path.lineTo(x, y)
            }
            path.close()
            
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, paint)
            
            // Draw particles
            val iter = particles.iterator()
            while(iter.hasNext()) {
                val p = iter.next()
                p.x += p.vx
                p.y += p.vy
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
        } else {
            // Idle breathing animation
            idlePhase += 0.03f
            val breath = (sin(idlePhase.toDouble()).toFloat() + 1f) / 2f
            val radius = baseRadius + breath * 6f
            
            val path = Path()
            path.addCircle(cx, cy, radius, Path.Direction.CW)
            
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, paint)
            
            // clear particles in idle
            particles.clear()
        }
        
        // Draw Text inside
        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        
        if (isPlaying || displaySeconds > 0) {
            // BPM
            textPaint.textSize = baseRadius * 0.5f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            val bpmText = if (currentBpm > 0) "$currentBpm" else "--"
            canvas.drawText(bpmText, cx, cy - baseRadius * 0.1f, textPaint)
            
            textPaint.textSize = baseRadius * 0.2f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            canvas.drawText("BPM", cx, cy + baseRadius * 0.2f, textPaint)
            
            // Time
            val min = displaySeconds / 60
            val sec = displaySeconds % 60
            val timeText = String.format("%d:%02d", min, sec)
            textPaint.textSize = baseRadius * 0.25f
            textPaint.color = Color.LTGRAY
            canvas.drawText(timeText, cx, cy + baseRadius * 0.6f, textPaint)
        } else {
            // Idle state text
            textPaint.textSize = baseRadius * 0.4f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            canvas.drawText("Zzz", cx, cy, textPaint)
            
            textPaint.textSize = baseRadius * 0.2f
            textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textPaint.color = Color.LTGRAY
            canvas.drawText("Idle", cx, cy + baseRadius * 0.4f, textPaint)
        }
        
        if (isPlaying || particles.isNotEmpty() || !isPlaying) {
            postInvalidateOnAnimation()
        }
    }
}
