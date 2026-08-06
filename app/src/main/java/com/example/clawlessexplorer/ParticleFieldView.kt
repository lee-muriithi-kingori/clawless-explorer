package com.example.clawlessexplorer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A custom [View] that renders an animated particle network / constellation effect.
 *
 * Particles drift along Lissajous-like curves and are connected by translucent lines
 * when they are within a configurable proximity threshold. The view adapts its palette
 * to the current light / dark mode and is optimised for zero allocations inside
 * [onDraw] so that it can sustain 60 fps on mid-range devices.
 *
 * Typical usage – place as a background behind a hero / header area:
 * ```xml
 * <com.example.clawlessexplorer.ParticleFieldView
 *     android:layout_width="match_parent"
 *     android:layout_height="240dp" />
 * ```
 */
class ParticleFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ──────────────────────────────────────────────────────────────
    // Brand colours (ARGB ints)
    // ──────────────────────────────────────────────────────────────

    private var colorPrimary: Int   = 0xFF5B5BF6.toInt() // indigo
    private var colorSecondary: Int = 0xFFFF6B9D.toInt() // pink
    private var colorTertiary: Int  = 0xFF00C8B4.toInt() // teal

    // ──────────────────────────────────────────────────────────────
    // Configuration
    // ──────────────────────────────────────────────────────────────

    /** Number of particles rendered. Clamped to [MIN_PARTICLES]..[MAX_PARTICLES]. */
    var particleCount: Int = DEFAULT_PARTICLE_COUNT
        private set

    /** Maximum distance (in px) between two particles for a connecting line to appear. */
    private var connectionDistancePx: Float = 150f * resources.displayMetrics.density

    /** Whether the device is currently in dark mode. */
    private val isNightMode: Boolean
        get() = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // ──────────────────────────────────────────────────────────────
    // Particle data (pre-allocated, never GC'd inside onDraw)
    // ──────────────────────────────────────────────────────────────

    private data class Particle(
        /** Normalised centre of the Lissajous figure (0..1). */
        var cx: Float = 0f,
        var cy: Float = 0f,
        /** Amplitude of the Lissajous figure as fraction of view size. */
        var ax: Float = 0f,
        var ay: Float = 0f,
        /** Frequency multipliers. */
        var fx: Float = 0f,
        var fy: Float = 0f,
        /** Phase offsets. */
        var px: Float = 0f,
        var py: Float = 0f,
        /** Colour index (0 = primary, 1 = secondary, 2 = tertiary). */
        var colorIndex: Int = 0,
        /** Base radius in px. */
        var radius: Float = 0f,
        /** Current screen position – updated every frame. */
        var x: Float = 0f,
        var y: Float = 0f,
    )

    private var particles: Array<Particle> = emptyArray()

    // ──────────────────────────────────────────────────────────────
    // Paints (pre-allocated, mutated but never re-created in onDraw)
    // ──────────────────────────────────────────────────────────────

    private val particlePaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Scratch objects reused every frame to avoid allocations
    private val particleColors = IntArray(3)
    private var glowAlphaShift: Int = 0

    // ──────────────────────────────────────────────────────────────
    // Animation state
    // ──────────────────────────────────────────────────────────────

    private var time: Float = 0f
    private val timeStep: Float = 0.016f      // ~1 frame at 60 fps
    private var running: Boolean = false
    private val FRAME_DELAY_MS = 16L          // ≈ 60 fps

    // ──────────────────────────────────────────────────────────────
    // Init
    // ──────────────────────────────────────────────────────────────

    init {
        initParticles(DEFAULT_PARTICLE_COUNT)
    }

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /**
     * Change the number of particles at runtime.
     * The new set is created immediately; the old array is discarded.
     */
    fun setParticleCount(count: Int) {
        val clamped = count.coerceIn(MIN_PARTICLES, MAX_PARTICLES)
        if (clamped != particleCount) {
            particleCount = clamped
            initParticles(clamped)
        }
    }

    /**
     * Override the three brand colours used for particles.
     * Each argument must be an ARGB int (e.g. `0xFF5B5BF6.toInt()`).
     */
    fun setColors(primary: Int, secondary: Int, tertiary: Int) {
        colorPrimary   = primary
        colorSecondary = secondary
        colorTertiary  = tertiary
    }

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postInvalidateDelayed(FRAME_DELAY_MS)
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Re-scatter particles so they fit the new dimensions.
        initParticles(particleCount)
    }

    // ──────────────────────────────────────────────────────────────
    // Particle initialisation
    // ──────────────────────────────────────────────────────────────

    private fun initParticles(count: Int) {
        val w = if (width > 0) width.toFloat() else 1080f
        val h = if (height > 0) height.toFloat() else 400f

        particles = Array(count) { i ->
            Particle(
                cx = 0.15f + rng() * 0.70f,
                cy = 0.15f + rng() * 0.70f,
                ax = 0.03f + rng() * 0.08f,
                ay = 0.03f + rng() * 0.08f,
                fx = 0.4f + rng() * 1.2f,
                fy = 0.4f + rng() * 1.2f,
                px = rng() * TWO_PI,
                py = rng() * TWO_PI,
                colorIndex = i % 3,
                radius = (2f + rng() * 2.5f) * resources.displayMetrics.density,
                x = w * (0.1f + rng() * 0.8f),
                y = h * (0.1f + rng() * 0.8f),
            )
        }
    }

    /** Deterministic-ish pseudo-random in [0, 1) – cheap, no allocations. */
    private var seed: Long = 42
    private fun rng(): Float {
        seed = (seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_003_341L)
        return ((seed ushr 33) and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
    }

    // ──────────────────────────────────────────────────────────────
    // Drawing
    // ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (!running) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val night = isNightMode
        preparePaints(night)
        drawBackground(canvas, w, h, night)
        updatePositions(w, h)
        drawConnections(canvas, night)
        drawParticles(canvas, night)

        time += timeStep
        if (running) postInvalidateDelayed(FRAME_DELAY_MS)
    }

    // ── Paint preparation ──────────────────────────────────────

    private fun preparePaints(night: Boolean) {
        // Resolve the three particle colours once per frame.
        if (night) {
            // Dark mode – brighter, more vivid
            particleColors[0] = brighten(colorPrimary,   0.35f)
            particleColors[1] = brighten(colorSecondary, 0.35f)
            particleColors[2] = brighten(colorTertiary,  0.35f)
            glowAlphaShift    = 40  // stronger glow
        } else {
            // Light mode – semi-transparent
            particleColors[0] = setAlpha(colorPrimary,   160)
            particleColors[1] = setAlpha(colorSecondary, 160)
            particleColors[2] = setAlpha(colorTertiary,  160)
            glowAlphaShift    = 20  // subtler glow
        }

        linePaint.style    = Paint.Style.STROKE
        linePaint.strokeWidth = 1.2f * resources.displayMetrics.density

        glowPaint.style = Paint.Style.FILL
    }

    // ── Background gradient ────────────────────────────────────

    private fun drawBackground(canvas: Canvas, w: Float, h: Float, night: Boolean) {
        val topColor    = if (night) 0xFF0D0D2B.toInt() else 0xFFF5F5FF.toInt()
        val bottomColor = if (night) 0xFF1A1A3E.toInt() else 0xFFEAEAFF.toInt()

        backgroundPaint.shader = RadialGradient(
            w * 0.5f, h * 0.3f, min(w, h) * 0.9f,
            topColor, bottomColor, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)
    }

    // ── Position update (Lissajous drift) ─────────────────────

    private fun updatePositions(w: Float, h: Float) {
        val t = time
        for (p in particles) {
            p.x = w * (p.cx + p.ax * sin(p.fx * t + p.px))
            p.y = h * (p.cy + p.ay * cos(p.fy * t + p.py))
        }
    }

    // ── Connection lines ──────────────────────────────────────

    private fun drawConnections(canvas: Canvas, night: Boolean) {
        val maxDist   = connectionDistancePx
        val maxDistSq = maxDist * maxDist
        val ps = particles
        val n  = ps.size

        val baseAlpha = if (night) 100 else 55

        for (i in 0 until n - 1) {
            val pi = ps[i]
            val xi = pi.x
            val yi = pi.y
            for (j in i + 1 until n) {
                val pj = ps[j]
                val dx = xi - pj.x
                val dy = yi - pj.y
                val distSq = dx * dx + dy * dy
                if (distSq < maxDistSq) {
                    val dist = sqrt(distSq)
                    val alpha = (baseAlpha * (1f - dist / maxDist)).toInt()
                            .coerceIn(0, 255)
                    // Blend the two particle colours for the line
                    val lineColor = blendColors(
                        particleColors[pi.colorIndex],
                        particleColors[pj.colorIndex],
                        0.5f,
                    )
                    linePaint.color = setAlpha(lineColor, alpha)
                    canvas.drawLine(xi, yi, pj.x, pj.y, linePaint)
                }
            }
        }
    }

    // ── Particles (circle + glow) ─────────────────────────────

    private fun drawParticles(canvas: Canvas, night: Boolean) {
        for (p in particles) {
            val color = particleColors[p.colorIndex]
            val r     = p.radius

            // Glow: a larger, translucent radial-gradient circle behind the dot.
            val glowRadius = r * (if (night) 4.5f else 3.0f)
            glowPaint.shader = RadialGradient(
                p.x, p.y, glowRadius,
                setAlpha(color, glowAlphaShift),
                setAlpha(color, 0),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(p.x, p.y, glowRadius, glowPaint)

            // Solid dot
            particlePaint.color = color
            particlePaint.style = Paint.Style.FILL
            canvas.drawCircle(p.x, p.y, r, particlePaint)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Colour utilities (pure functions, no allocations)
    // ──────────────────────────────────────────────────────────────

    private fun setAlpha(argb: Int, alpha: Int): Int {
        return (alpha.coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)
    }

    private fun brighten(argb: Int, amount: Float): Int {
        val a = (argb ushr 24) and 0xFF
        var r = ((argb ushr 16) and 0xFF)
        var g = ((argb ushr 8)  and 0xFF)
        var b = (argb and 0xFF)
        r = (r + (255 - r) * amount).toInt().coerceIn(0, 255)
        g = (g + (255 - g) * amount).toInt().coerceIn(0, 255)
        b = (b + (255 - b) * amount).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val ir = 1f - ratio
        val r = (((c1 ushr 16) and 0xFF) * ir + ((c2 ushr 16) and 0xFF) * ratio).toInt()
        val g = (((c1 ushr 8)  and 0xFF) * ir + ((c2 ushr 8)  and 0xFF) * ratio).toInt()
        val b = ((c1 and 0xFF) * ir + (c2 and 0xFF) * ratio).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ──────────────────────────────────────────────────────────────
    // Companion constants
    // ──────────────────────────────────────────────────────────────

    companion object {
        private const val TWO_PI = 6.2831855f
        private const val DEFAULT_PARTICLE_COUNT = 50
        private const val MIN_PARTICLES = 10
        private const val MAX_PARTICLES = 120
    }
}
