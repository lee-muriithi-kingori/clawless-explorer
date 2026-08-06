package com.example.clawlessexplorer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.sin

/**
 * A custom [View] that renders three layered, animated morphing waves
 * using cubic Bézier paths. Designed to sit at the bottom of a hero
 * header area in a file explorer app.
 *
 * Each wave layer has distinct amplitude, frequency, speed, phase and
 * colour, producing a rich, flowing effect that runs at ~60 fps.
 *
 * ## Usage
 * ```xml
 * <com.example.clawlessexplorer.MorphingWaveView
 *     android:layout_width="match_parent"
 *     android:layout_height="60dp" />
 * ```
 *
 * ## Contract
 * - **Zero allocations in [onDraw]** — all [Paint] and [Path] objects are
 *   pre-allocated during construction / colour changes.
 * - Animation is stopped in [onDetachedFromWindow] to avoid leaked
 *   invalidate callbacks.
 * - Colours can be swapped at runtime via [setWaveColors].
 * - Supports both light and dark mode via configuration-aware defaults.
 */
class MorphingWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ────────────────────────────────────────────────────────────────
    //  Pre-allocated drawing objects (never created inside onDraw)
    // ────────────────────────────────────────────────────────────────

    private val wavePath1 = Path()
    private val wavePath2 = Path()
    private val wavePath3 = Path()

    private val wavePaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wavePaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wavePaint3 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ────────────────────────────────────────────────────────────────
    //  Wave layer configuration
    // ────────────────────────────────────────────────────────────────

    /** Amplitude as a fraction of the view height. */
    private var amp1 = 0.38f   // back – large sweep
    private var amp2 = 0.26f   // middle
    private var amp3 = 0.18f   // front – subtle ripple

    /** Spatial frequency (number of full sine cycles across the view width). */
    private var freq1 = 1.0f
    private var freq2 = 1.5f
    private var freq3 = 2.0f

    /** Temporal speed multiplier (radians per frame). */
    private var speed1 = 0.020f
    private var speed2 = 0.035f
    private var speed3 = 0.050f

    /** Phase offsets – distinct to avoid visual symmetry. */
    private var phase1 = 0.0f
    private var phase2 = PI.toFloat() / 3f
    private var phase3 = PI.toFloat() * 2f / 3f

    /** Vertical baseline offset as fraction of height (0 = top, 1 = bottom). */
    private var base1 = 0.30f
    private var base2 = 0.48f
    private var base3 = 0.64f

    // ────────────────────────────────────────────────────────────────
    //  Animation state
    // ────────────────────────────────────────────────────────────────

    private var time = 0.0f
    private var animating = false

    /** Frame interval for ~60 fps. */
    private val frameDelayMs = 16L

    // ────────────────────────────────────────────────────────────────
    //  Default colours (light-mode aware)
    // ────────────────────────────────────────────────────────────────

    /** Semi-transparent pink – back wave. */
    private var color1 = 0x22FF6B9D
    /** Semi-transparent teal – middle wave. */
    private var color2 = 0x3300C8B4
    /** Semi-transparent indigo – front wave. */
    private var color3 = 0x445B5BF6

    // ────────────────────────────────────────────────────────────────
    //  Init
    // ────────────────────────────────────────────────────────────────

    init {
        applyWaveColors()
        applyDarkModeIfNeeded()
    }

    // ────────────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────────────

    /**
     * Replace all three wave colours at runtime.
     *
     * @param c1 Back wave colour (ARGB int).
     * @param c2 Middle wave colour (ARGB int).
     * @param c3 Front wave colour (ARGB int).
     */
    fun setWaveColors(c1: Int, c2: Int, c3: Int) {
        color1 = c1
        color2 = c2
        color3 = c3
        applyWaveColors()
        invalidate()
    }

    // ────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────────────────────────

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
        if (visibility == VISIBLE && isAttachedToWindow) startAnimation()
        else stopAnimation()
    }

    // ────────────────────────────────────────────────────────────────
    //  Measurement
    // ────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (60 * resources.displayMetrics.density).toInt()

        val w = resolveSize(0, widthMeasureSpec)
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ────────────────────────────────────────────────────────────────
    //  Drawing
    // ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // ── Back wave ──────────────────────────────────────────────
        drawWave(
            canvas = canvas,
            path = wavePath1,
            paint = wavePaint1,
            width = w,
            height = h,
            amplitude = amp1,
            frequency = freq1,
            speed = speed1,
            phase = phase1,
            baseline = base1,
        )

        // ── Middle wave ────────────────────────────────────────────
        drawWave(
            canvas = canvas,
            path = wavePath2,
            paint = wavePaint2,
            width = w,
            height = h,
            amplitude = amp2,
            frequency = freq2,
            speed = speed2,
            phase = phase2,
            baseline = base2,
        )

        // ── Front wave ─────────────────────────────────────────────
        drawWave(
            canvas = canvas,
            path = wavePath3,
            paint = wavePaint3,
            width = w,
            height = h,
            amplitude = amp3,
            frequency = freq3,
            speed = speed3,
            phase = phase3,
            baseline = base3,
        )

        // ── Advance time & schedule next frame ─────────────────────
        time += 1f
        if (animating) {
            postInvalidateDelayed(frameDelayMs)
        }
    }

    /**
     * Draws a single morphing wave as a filled shape using four cubic
     * Bézier segments that approximate a sine curve.
     *
     * The path starts at the left edge on the baseline, undulates across
     * the view width following a sine function whose phase shifts over
     * time, then closes along the bottom edge to form a fillable shape.
     *
     * **No allocations occur inside this method.**
     */
    private fun drawWave(
        canvas: Canvas,
        path: Path,
        paint: Paint,
        width: Float,
        height: Float,
        amplitude: Float,
        frequency: Float,
        speed: Float,
        phase: Float,
        baseline: Float,
    ) {
        path.reset()

        val amp = amplitude * height
        val twoPiF = 2f * PI.toFloat() * frequency
        val timeAngle = speed * time
        val baseY = baseline * height

        // ── Number of cubic segments (one half-sine per segment) ───
        //  Using 4 segments gives a visually smooth result at low cost.
        val segments = 4
        val segWidth = width / segments

        // Starting point – left edge, on the sine curve
        val startY = baseY + amp * sin(twoPiF * (0f / width) + phase + timeAngle)
        path.moveTo(0f, startY)

        // ── Cubic Bézier approximation of each sine half-cycle ─────
        //  For a half-sine from 0→π, the control-point offset that
        //  minimises RMS error is ≈ 4/(3π) ≈ 0.4244 of the amplitude.
        val k = 4f / (3f * PI.toFloat())  // ≈ 0.4244

        for (i in 0 until segments) {
            val x0 = i * segWidth
            val x1 = (i + 1) * segWidth
            val midX = (x0 + x1) * 0.5f

            val angle0 = twoPiF * (x0 / width) + phase + timeAngle
            val angle1 = twoPiF * (x1 / width) + phase + timeAngle

            val y0 = baseY + amp * sin(angle0)
            val y1 = baseY + amp * sin(angle1)

            // Derivative of sin → cos, scaled by (twoPiF / width) * amp
            val dydx0 = amp * twoPiF / width * kotlin.math.cos(angle0)
            val dydx1 = amp * twoPiF / width * kotlin.math.cos(angle1)

            // Control points derived from tangent slopes
            val cp0x = x0 + segWidth / 3f
            val cp0y = y0 + dydx0 * segWidth / 3f
            val cp1x = x1 - segWidth / 3f
            val cp1y = y1 - dydx1 * segWidth / 3f

            path.cubicTo(cp0x, cp0y, cp1x, cp1y, x1, y1)
        }

        // ── Close along the bottom to form a fillable area ─────────
        path.lineTo(width, height)
        path.lineTo(0f, height)
        path.close()

        canvas.drawPath(path, paint)
    }

    // ────────────────────────────────────────────────────────────────
    //  Animation control
    // ────────────────────────────────────────────────────────────────

    private fun startAnimation() {
        if (animating) return
        animating = true
        invalidate()
    }

    private fun stopAnimation() {
        animating = false
    }

    // ────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────

    /** Push the current colour ints into the pre-allocated Paints. */
    private fun applyWaveColors() {
        wavePaint1.color = color1
        wavePaint2.color = color2
        wavePaint3.color = color3
    }

    /**
     * Adjust default colours for dark mode by boosting alpha so the
     * waves remain visible against dark surfaces.
     */
    private fun applyDarkModeIfNeeded() {
        val nightMode = context.resources?.configuration?.uiMode?.and(0x30) == 0x20
        if (nightMode) {
            // Preserve hue, boost alpha for visibility on dark bg
            color1 = boostAlpha(color1)
            color2 = boostAlpha(color2)
            color3 = boostAlpha(color3)
            applyWaveColors()
        }
    }

    /** Doubles the alpha channel (capped at 0xFF) while preserving RGB. */
    private fun boostAlpha(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val newA = minOf(a * 2, 0xFF)
        return (newA shl 24) or (argb and 0x00FFFFFF)
    }
}
