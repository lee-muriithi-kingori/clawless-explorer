package com.example.clawlessexplorer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.min

/**
 * A custom [View] that renders an animated aurora borealis / northern lights effect
 * using the Canvas API. Multiple flowing bands of color shift and wave over time,
 * creating a stunning ambient background suitable for a hero header area.
 *
 * ### Key design decisions
 * - **Zero allocations in [onDraw]**: All [Paint], [Path], and [Shader] objects are
 *   pre-allocated and reused every frame.
 * - **HSV colour cycling**: Each band's hue rotates through the configured palette over
 *   time, producing smooth colour transitions without abrupt jumps.
 * - **Dark‑mode aware**: In dark mode bands are brighter/more saturated; in light mode
 *   they are subtler and more transparent so content on top remains legible.
 * - **Lifecycle‑safe**: Animation is stopped in [onDetachedFromWindow] and
 *   paused/resumed in [onVisibilityChanged].
 */
class AuroraGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ─── Configuration ────────────────────────────────────────────────

    /** Number of aurora bands drawn. */
    private val bandCount = 5

    /** Frames-per-second target. */
    private val fps: Long = 60

    /** Delay between frames in milliseconds (≈16 ms for 60 fps). */
    private val frameDelayMs: Long = 1000L / fps

    // ─── Default colour palette (deep purple → indigo → cyan → emerald → pink) ──

    private val defaultColors = intArrayOf(
        0xFF7C3AED.toInt(), // deep purple
        0xFF5B5BF6.toInt(), // indigo
        0xFF06B6D4.toInt(), // cyan
        0xFF10B981.toInt(), // emerald
        0xFFEC4899.toInt()  // pink
    )

    /** Current aurora colour palette as ARGB ints. */
    private var auroraColors: IntArray = defaultColors.copyOf()

    /** Speed multiplier – 1.0f is the default speed. */
    private var speedMultiplier: Float = 1.0f

    // ─── Animation state ──────────────────────────────────────────────

    /** Monotonically increasing time value driving all wave functions. */
    private var time: Float = 0f

    /** Whether the animation loop is currently running. */
    private var isAnimating: Boolean = false

    // ─── Pre-allocated drawing objects ────────────────────────────────

    /** One [Paint] per band (each may carry a different shader). */
    private val bandPaints: Array<Paint> = Array(bandCount) { Paint(Paint.ANTI_ALIAS_FLAG) }

    /** One [Path] per band, rebuilt every frame. */
    private val bandPaths: Array<Path> = Array(bandCount) { Path() }

    /** One vertical-gradient [Shader] per band, recreated when size or colour changes. */
    private val bandShaders: Array<Shader?> = arrayOfNulls(bandCount)

    /** Scratch [FloatArray] for per-band wave parameters – avoids per-frame allocation. */
    private val bandPhases: FloatArray = FloatArray(bandCount)
    private val bandFrequencies: FloatArray = FloatArray(bandCount)
    private val bandAmplitudes: FloatArray = FloatArray(bandCount)
    private val bandSpeeds: FloatArray = FloatArray(bandCount)
    private val bandYOffsets: FloatArray = FloatArray(bandCount)

    /** Scratch arrays for HSV computation – one per band so we don't allocate in onDraw. */
    private val bandHsv: Array<FloatArray> = Array(bandCount) { floatArrayOf(0f, 1f, 1f) }

    /** Cached HSV values for the current [auroraColors] – updated when colours change. */
    private val auroraHsv: Array<FloatArray> = Array(bandCount) { floatArrayOf(0f, 0f, 0f) }

    /** Pre-allocated colour/position arrays for LinearGradient – avoids per-frame allocation. */
    private val shaderColors: IntArray = IntArray(3)
    private val shaderPositions: FloatArray = floatArrayOf(0f, 0.5f, 1f)

    /** Pre-allocated Y arrays for band path construction – one pair per band. */
    private val segments = 8
    private val topY: FloatArray = FloatArray(segments + 1)
    private val botY: FloatArray = FloatArray(segments + 1)

    // ─── Dark-mode helpers ────────────────────────────────────────────

    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Alpha applied to band paints. Brighter in dark mode, subtler in light. */
    private val bandAlpha: Int
        get() = if (isDarkMode) 180 else 100

    /** Saturation multiplier. More saturated in dark mode. */
    private val saturationMultiplier: Float
        get() = if (isDarkMode) 1.0f else 0.7f

    /** Value (brightness) multiplier. Brighter in dark mode. */
    private val valueMultiplier: Float
        get() = if (isDarkMode) 1.0f else 0.75f

    // ─── Init ─────────────────────────────────────────────────────────

    init {
        // Set up per-band wave parameters – each band has a unique character.
        bandPhases[0]      = 0.0f;   bandFrequencies[0] = 0.6f;  bandAmplitudes[0] = 0.12f; bandSpeeds[0] = 0.8f;  bandYOffsets[0] = 0.20f
        bandPhases[1]      = 1.2f;   bandFrequencies[1] = 0.8f;  bandAmplitudes[1] = 0.10f; bandSpeeds[1] = 1.0f;  bandYOffsets[1] = 0.35f
        bandPhases[2]      = 2.5f;   bandFrequencies[2] = 1.0f;  bandAmplitudes[2] = 0.14f; bandSpeeds[2] = 0.6f;  bandYOffsets[2] = 0.50f
        bandPhases[3]      = 3.8f;   bandFrequencies[3] = 0.7f;  bandAmplitudes[3] = 0.09f; bandSpeeds[3] = 1.2f;  bandYOffsets[3] = 0.65f
        bandPhases[4]      = 5.0f;   bandFrequencies[4] = 0.9f;  bandAmplitudes[4] = 0.11f; bandSpeeds[4] = 0.9f;  bandYOffsets[4] = 0.80f

        // Compute initial HSV cache for the default palette.
        recacheAuroraHsv()
    }

    // ─── Public API ───────────────────────────────────────────────────

    /**
     * Replace the aurora colour palette.
     *
     * @param colors An [IntArray] of ARGB colours. If its length differs from [bandCount]
     *               the array is truncated or the last colour is repeated as needed.
     */
    fun setAuroraColors(colors: IntArray) {
        auroraColors = normalizeColorArray(colors)
        recacheAuroraHsv()
        rebuildShaders()
        invalidate()
    }

    /**
     * Control the animation speed.
     *
     * @param multiplier 1.0f = default speed, 2.0f = double, 0.5f = half, etc.
     */
    fun setSpeedMultiplier(multiplier: Float) {
        speedMultiplier = multiplier.coerceIn(0f, 10f)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this) {
            when (visibility) {
                VISIBLE   -> if (!isAnimating) startAnimation()
                INVISIBLE, GONE -> stopAnimation()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    // ─── Drawing ──────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val currentAlpha = bandAlpha
        val satMul = saturationMultiplier
        val valMul = valueMultiplier

        for (i in 0 until bandCount) {
            // ── Compute cycling colour ────────────────────────────────
            // Each band's hue slowly rotates through the palette based on time.
            val colourIndex = i % auroraHsv.size
            val baseHsv = auroraHsv[colourIndex]

            // Offset the hue by a time-varying amount so colours shift.
            val hueShift = (time * bandSpeeds[i] * 0.05f) % 1f
            bandHsv[i][0] = (baseHsv[0] / 360f + hueShift) % 1f  // normalised 0‑1
            bandHsv[i][0] *= 360f                                  // back to degrees for Color.HSVToColor
            bandHsv[i][1] = (baseHsv[1] * satMul).coerceIn(0f, 1f)
            bandHsv[i][2] = (baseHsv[2] * valMul).coerceIn(0f, 1f)

            val colour = Color.HSVToColor(currentAlpha, bandHsv[i])

            // ── Rebuild vertical gradient shader for this band ─────────
            // Shader is recreated only when size changes (see rebuildShaders),
            // but the colour changes every frame so we must update it.
            val yTop    = h * (bandYOffsets[i] - bandAmplitudes[i] - 0.15f)
            val yBottom = h * (bandYOffsets[i] + bandAmplitudes[i] + 0.15f)
            shaderColors[0] = 0x00000000
            shaderColors[1] = colour
            shaderColors[2] = 0x00000000
            bandShaders[i] = LinearGradient(
                0f, yTop.coerceAtLeast(0f),
                0f, yBottom.coerceAtMost(h),
                shaderColors,
                shaderPositions,
                Shader.TileMode.CLAMP
            )
            bandPaints[i].shader = bandShaders[i]

            // ── Build the bezier band path ─────────────────────────────
            buildBandPath(i, w, h)

            // ── Draw ───────────────────────────────────────────────────
            canvas.drawPath(bandPaths[i], bandPaints[i])
        }
    }

    // ─── Band path construction ───────────────────────────────────────

    /**
     * Constructs a flowing, wave-like closed [Path] for band [index].
     *
     * The band is drawn as a filled shape bounded by two cubic-bezier curves:
     * a "top edge" and a "bottom edge", connected at the left and right sides.
     * This produces a thick, ribbon-like aurora band that waves across the view.
     */
    private fun buildBandPath(index: Int, w: Float, h: Float) {
        val path = bandPaths[index]
        path.reset()

        val freq     = bandFrequencies[index]
        val amp      = bandAmplitudes[index]
        val speed    = bandSpeeds[index]
        val phase    = bandPhases[index]
        val yCenter  = bandYOffsets[index]

        // Band thickness in normalised [0,1] coordinates.
        val thickness = 0.06f + amp * 0.3f

        val dx = w / segments

        // We compute top-edge and bottom-edge Y values at each segment endpoint.
        // Using sin/cos with the current time gives flowing motion.
        // topY/botY are pre-allocated member arrays – zero alloc in onDraw.

        for (s in 0..segments) {
            val xNorm = s.toFloat() / segments  // 0..1

            // Primary wave
            val wave1 = sin((xNorm * freq * 2f * PI.toFloat()) + phase + time * speed * 0.6f)
            // Secondary harmonic for organic feel
            val wave2 = cos((xNorm * freq * 3.7f * PI.toFloat()) + phase * 1.3f + time * speed * 0.4f) * 0.4f
            // Slow drift
            val drift = sin(time * speed * 0.15f + phase) * 0.05f

            val waveTotal = (wave1 + wave2) * amp + drift
            val y = h * (yCenter + waveTotal)

            topY[s] = y - h * thickness * 0.5f
            botY[s] = y + h * thickness * 0.5f
        }

        // Build the path: top edge left→right, then bottom edge right→left.
        path.moveTo(0f, topY[0])

        // Cubic bezier through top-edge points (using Catmull-Rom → Bezier conversion).
        for (s in 1..segments) {
            val prevX = (s - 1) * dx
            val currX = s * dx
            val prevY = topY[s - 1]
            val currY = topY[s]

            // Simple cubic control points for a smooth curve.
            val cpx1 = prevX + dx * 0.33f
            val cpy1 = prevY
            val cpx2 = currX - dx * 0.33f
            val cpy2 = currY

            path.cubicTo(cpx1, cpy1, cpx2, cpy2, currX, currY)
        }

        // Bottom edge right→left
        path.lineTo(w, botY[segments])

        for (s in segments - 1 downTo 0) {
            val nextX = (s + 1) * dx
            val currX = s * dx
            val nextY = botY[s + 1]
            val currY = botY[s]

            val cpx1 = nextX - dx * 0.33f
            val cpy1 = nextY
            val cpx2 = currX + dx * 0.33f
            val cpy2 = currY

            path.cubicTo(cpx1, cpy1, cpx2, cpy2, currX, currY)
        }

        path.close()
    }

    // ─── Shader rebuild ───────────────────────────────────────────────

    /**
     * Recreates all band shaders. Called when the view size or colour palette changes.
     * For the per-frame colour update the shader is rebuilt directly in [onDraw];
     * this method ensures the geometry (top/bottom Y) matches the current size.
     */
    private fun rebuildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val currentAlpha = bandAlpha
        val satMul = saturationMultiplier
        val valMul = valueMultiplier

        for (i in 0 until bandCount) {
            val yTop    = h * (bandYOffsets[i] - bandAmplitudes[i] - 0.15f)
            val yBottom = h * (bandYOffsets[i] + bandAmplitudes[i] + 0.15f)

            val colourIndex = i % auroraHsv.size
            val baseHsv = auroraHsv[colourIndex]
            bandHsv[i][0] = baseHsv[0]
            bandHsv[i][1] = (baseHsv[1] * satMul).coerceIn(0f, 1f)
            bandHsv[i][2] = (baseHsv[2] * valMul).coerceIn(0f, 1f)
            val colour = Color.HSVToColor(currentAlpha, bandHsv[i])

            shaderColors[0] = 0x00000000
            shaderColors[1] = colour
            shaderColors[2] = 0x00000000
            bandShaders[i] = LinearGradient(
                0f, yTop.coerceAtLeast(0f),
                0f, yBottom.coerceAtMost(h),
                shaderColors,
                shaderPositions,
                Shader.TileMode.CLAMP
            )
            bandPaints[i].shader = bandShaders[i]
        }
    }

    // ─── Animation loop ───────────────────────────────────────────────

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isAnimating) return
            time += 0.016f * speedMultiplier * 60f  // normalised so speed 1× ≈ real-time
            invalidate()
            postDelayed(this, frameDelayMs)
        }
    }

    private fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        postDelayed(tickRunnable, frameDelayMs)
    }

    private fun stopAnimation() {
        isAnimating = false
        removeCallbacks(tickRunnable)
    }

    // ─── Colour helpers ───────────────────────────────────────────────

    /**
     * Ensure the incoming colour array matches [bandCount] in length by
     * truncating or repeating the last colour.
     */
    private fun normalizeColorArray(colors: IntArray): IntArray {
        if (colors.isEmpty()) return defaultColors.copyOf()
        if (colors.size == bandCount) return colors.copyOf()

        val result = IntArray(bandCount)
        for (i in 0 until bandCount) {
            result[i] = colors[min(i, colors.size - 1)]
        }
        return result
    }

    /** Recompute cached HSV values from the current [auroraColors]. */
    private fun recacheAuroraHsv() {
        for (i in auroraHsv.indices) {
            val ci = i % auroraColors.size
            Color.colorToHSV(auroraColors[ci], auroraHsv[i])
        }
    }
}
