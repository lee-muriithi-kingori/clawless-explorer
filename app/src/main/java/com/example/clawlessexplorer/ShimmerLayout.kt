package com.example.clawlessexplorer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * A custom [FrameLayout] that draws a shimmer/shine sweep effect over its children.
 *
 * The shimmer is a linear gradient that sweeps from left to right, going from
 * transparent → semi-transparent highlight → transparent. This is commonly used
 * to indicate loading states in modern UIs.
 *
 * Usage:
 * ```xml
 * <com.example.clawlessexplorer.ShimmerLayout
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content">
 *
 *     <!-- Child views that appear to shimmer -->
 *
 * </com.example.clawlessexplorer.ShimmerLayout>
 * ```
 *
 * Programmatic control:
 * ```kotlin
 * shimmerLayout.startShimmer()
 * shimmerLayout.stopShimmer()
 * shimmerLayout.setShimmerColor(0x40FFFFFF)
 * ```
 */
class ShimmerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        /** One full sweep every 1.5 seconds. */
        private const val SWEEP_DURATION_MS = 1500L

        /**
         * Fraction of the view width that the shimmer gradient spans.
         * Keeps the highlight band narrow for a natural "shine" look.
         */
        private const val SHIMMER_WIDTH_RATIO = 0.5f

        /** Default shimmer highlight color: semi-transparent white. */
        private const val DEFAULT_SHIMMER_COLOR = 0x40FFFFFF
    }

    // ── State ──────────────────────────────────────────────────────────────

    /** Whether the shimmer animation is currently active. */
    var isShimmering: Boolean = false
        private set

    /** Current shimmer highlight color (ARGB). */
    private var shimmerColor: Int = DEFAULT_SHIMMER_COLOR

    /** Animated fraction [0, 1] representing the shimmer's horizontal position. */
    private var shimmerFraction: Float = 0f

    // ── Drawing objects (pre-allocated, recreated only on size change) ─────

    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }

    private var shimmerShader: LinearGradient? = null

    /** Last known width used to build the shader — avoids per-frame realloc. */
    private var cachedWidth: Int = 0

    /** Last known height used to build the shader. */
    private var cachedHeight: Int = 0

    // ── Animator ───────────────────────────────────────────────────────────

    private var shimmerAnimator: ValueAnimator? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != cachedWidth || h != cachedHeight) {
            cachedWidth = w
            cachedHeight = h
            rebuildShader()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isShimmering || cachedWidth <= 0 || cachedHeight <= 0) return

        // Ensure shader is up to date with the current animated position.
        rebuildShader()
        shimmerPaint.shader = shimmerShader

        canvas.drawRect(0f, 0f, cachedWidth.toFloat(), cachedHeight.toFloat(), shimmerPaint)
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Start the shimmer animation. No-op if already shimmering. */
    fun startShimmer() {
        if (isShimmering) return
        isShimmering = true

        // Hardware layer caches the rasterised children so only the shimmer
        // overlay needs to be recomposed each frame.
        setLayerType(LAYER_TYPE_HARDWARE, null)

        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = null // linear sweep

            addUpdateListener { animator ->
                shimmerFraction = animator.animatedValue as Float
                invalidate()
            }

            start()
        }
    }

    /** Stop the shimmer animation and restore the default layer type. */
    fun stopShimmer() {
        if (!isShimmering) return
        isShimmering = false

        shimmerAnimator?.cancel()
        shimmerAnimator = null

        setLayerType(LAYER_TYPE_NONE, null)
        invalidate()
    }

    /**
     * Change the shimmer highlight color.
     *
     * @param color ARGB color int (e.g. `0x40FFFFFF` for semi-transparent white).
     */
    fun setShimmerColor(color: Int) {
        shimmerColor = color
        // Force shader rebuild on next draw so the new color takes effect.
        cachedWidth = 0
        if (isShimmering) invalidate()
    }

    // ── Internal ───────────────────────────────────────────────────────────

    /**
     * Rebuild [shimmerShader] based on the current view dimensions and
     * animated [shimmerFraction].
     *
     * The gradient is positioned so that the centre of the highlight band
     * sweeps from just left of the view to just right of it:
     *
     * ```
     * ┌──────────────────────────────┐
     * │  transparent                 │
     * │       ▓▓▓ highlight ▓▓▓     │  ← sweeps left → right
     * │  transparent                 │
     * └──────────────────────────────┘
     * ```
     *
     * At fraction 0 the peak is at `-shimmerWidth` (off-screen left).
     * At fraction 1 the peak is at `viewWidth + shimmerWidth` (off-screen right).
     */
    private fun rebuildShader() {
        val w = cachedWidth
        val h = cachedHeight
        if (w <= 0 || h <= 0) return

        val shimmerWidth = w * SHIMMER_WIDTH_RATIO

        // The peak of the gradient travels across the full extent plus one
        // shimmer-width on each side so it fully enters and exits the view.
        val totalTravel = w + shimmerWidth * 2f
        val peakX = -shimmerWidth + totalTravel * shimmerFraction

        val left = peakX - shimmerWidth
        val right = peakX + shimmerWidth

        // 0x00 in the leading/trailing edges → shimmerColor at the centre → 0x00 again.
        val transparent = 0x00000000

        shimmerShader = LinearGradient(
            left, 0f,
            right, 0f,
            intArrayOf(transparent, shimmerColor, transparent),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }
}
