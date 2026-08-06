package com.example.clawlessexplorer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.annotation.RequiresApi
import androidx.core.animation.addListener
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.R as MaterialR

/**
 * A modern floating search bar with expand/collapse animation,
 * glass-morphism background, and pill shape — inspired by the
 * Google Pixel launcher search bar.
 *
 * Features:
 *  - Pill shape (corner radius = height / 2)
 *  - Glass-morphism semi-transparent gradient background
 *  - Smooth expand / collapse via [ValueAnimator]
 *  - Elevation shadow (0 dp unfocused → 4 dp focused)
 *  - Light / dark mode aware
 *  - Callbacks for query changes and voice-search taps
 */
class FloatingSearchBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    // ──────────────────────────────────────────────
    // Callback interfaces
    // ──────────────────────────────────────────────

    /** Fired on every character change in the search field. */
    fun interface OnSearchQueryListener {
        fun onQueryChanged(query: String)
    }

    /** Fired when the microphone button is tapped. */
    fun interface OnVoiceSearchClickedListener {
        fun onVoiceSearchClicked()
    }

    // ──────────────────────────────────────────────
    // Dimensions (dp → px, lazily resolved)
    // ──────────────────────────────────────────────

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float) = (value * density + 0.5f).toInt()
    private fun dpF(value: Float) = value * density

    private val BAR_HEIGHT_DP = 56f
    private val BAR_HEIGHT_PX = dp(BAR_HEIGHT_DP)
    private val ICON_SIZE_DP = 24f
    private val ICON_SIZE_PX = dp(ICON_SIZE_DP)
    private val HORIZONTAL_PADDING_DP = 16f
    private val HORIZONTAL_PADDING_PX = dp(HORIZONTAL_PADDING_DP)
    private val ICON_GAP_DP = 12f
    private val ICON_GAP_PX = dp(ICON_GAP_DP)
    private val ELEVATION_FOCUSED_DP = 4f
    private val ELEVATION_FOCUSED_PX = dpF(ELEVATION_FOCUSED_DP)
    private val EXPAND_WIDTH_RATIO = 1.12f  // 12 % wider when expanded
    private val ANIM_DURATION = 280L

    // ──────────────────────────────────────────────
    // Theme colours (resolved once, updated on theme change)
    // ──────────────────────────────────────────────

    private var colorSurface: Int = 0
    private var colorOnSurfaceVariant: Int = 0
    private var colorAccent: Int = 0
    private var colorHint: Int = 0
    private var isDarkTheme: Boolean = false

    // ──────────────────────────────────────────────
    // Pre-allocated Paint / Rect / RectF objects
    // ──────────────────────────────────────────────

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.5f)
    }

    private val tempRect = Rect()
    private val tempRectF = RectF()

    // ──────────────────────────────────────────────
    // Child views
    // ──────────────────────────────────────────────

    private val searchIcon: ImageView
    private val searchEditText: EditText
    private val micIcon: ImageView

    // ──────────────────────────────────────────────
    // Drawables (pre-allocated, mutated per state)
    // ──────────────────────────────────────────────

    private val pillBackground = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = BAR_HEIGHT_PX / 2f
    }

    private val unfocusedBorder = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = BAR_HEIGHT_PX / 2f
    }

    private val focusedBorder = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = BAR_HEIGHT_PX / 2f
    }

    // ──────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────

    private var isExpanded = false
    private var currentExpandFraction = 0f  // 0 = collapsed, 1 = expanded

    private var expandAnimator: ValueAnimator? = null
    private var collapseAnimator: ValueAnimator? = null

    private var queryListener: OnSearchQueryListener? = null
    private var voiceListener: OnVoiceSearchClickedListener? = null

    // ──────────────────────────────────────────────
    // Initialisation
    // ──────────────────────────────────────────────

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        resolveThemeColors()
        buildBackgroundDrawables()

        // — Search icon —
        searchIcon = ImageView(context).apply {
            val drawable = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_search)
                ?: ResourcesCompat.getDrawable(resources, android.R.drawable.ic_menu_search, null)
            if (drawable != null) {
                val tinted = DrawableCompat.wrap(drawable.mutate())
                DrawableCompat.setTint(tinted, colorOnSurfaceVariant)
                setImageDrawable(tinted)
            } else {
                setImageResource(android.R.drawable.ic_menu_search)
            }
            layoutParams = LayoutParams(ICON_SIZE_PX, ICON_SIZE_PX).apply {
                marginEnd = ICON_GAP_PX
            }
            setPadding(0, 0, 0, 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Search"
        }

        // — EditText —
        searchEditText = EditText(context).apply {
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = null  // no underline
            setTextColor(colorOnSurfaceVariant)
            setHintTextColor(colorHint)
            hint = "Search files..."
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            setPadding(0, 0, 0, 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    queryListener?.onQueryChanged(s?.toString().orEmpty())
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) expand() else collapse()
            }
        }

        // — Mic icon —
        micIcon = ImageView(context).apply {
            val drawable = ContextCompat.getDrawable(context, android.R.drawable.ic_btn_speak_now)
                ?: ResourcesCompat.getDrawable(resources, android.R.drawable.ic_btn_speak_now, null)
            if (drawable != null) {
                val tinted = DrawableCompat.wrap(drawable.mutate())
                DrawableCompat.setTint(tinted, colorOnSurfaceVariant)
                setImageDrawable(tinted)
            } else {
                setImageResource(android.R.drawable.ic_btn_speak_now)
            }
            layoutParams = LayoutParams(ICON_SIZE_PX, ICON_SIZE_PX).apply {
                marginStart = ICON_GAP_PX
            }
            setPadding(0, 0, 0, 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Voice search"
            setOnClickListener {
                voiceListener?.onVoiceSearchClicked()
            }
        }

        // — Assemble —
        updatePadding(
            left = HORIZONTAL_PADDING_PX,
            right = HORIZONTAL_PADDING_PX,
            top = 0,
            bottom = 0,
        )
        minimumHeight = BAR_HEIGHT_PX
        elevation = 0f

        addView(searchIcon)
        addView(searchEditText)
        addView(micIcon)

        background = pillBackground

        // Apply initial collapsed appearance
        applyExpandFraction(0f)
    }

    // ──────────────────────────────────────────────
    // Theme resolution
    // ──────────────────────────────────────────────

    private fun resolveThemeColors() {
        val tv = TypedValue()
        val theme = context.theme

        // Detect dark mode
        isDarkTheme = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                (context.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            else -> {
                theme.resolveAttribute(android.R.attr.isLightTheme, tv, true) &&
                        tv.data == 0
            }
        }

        // Surface color
        colorSurface = if (isDarkTheme) {
            Color.argb(0xBB, 0x1E, 0x1E, 0x1E)  // 73 % opaque dark surface
        } else {
            Color.argb(0xDD, 0xFF, 0xFF, 0xFF)  // 87 % opaque white surface
        }

        // On-surface-variant (icon + text tint)
        colorOnSurfaceVariant = if (isDarkTheme) {
            Color.argb(0xFF, 0xC4, 0xC4, 0xC4)  // Material grey 300
        } else {
            Color.argb(0xFF, 0x49, 0x49, 0x49)  // Material grey 700
        }

        // Accent colour (focused border)
        if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)) {
            colorAccent = tv.data
        } else if (theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) {
            colorAccent = tv.data
        } else {
            colorAccent = if (isDarkTheme) Color.argb(0xFF, 0xBB, 0x86, 0xFC)
            else Color.argb(0xFF, 0x62, 0x00, 0xEE)
        }

        // Hint colour
        colorHint = if (isDarkTheme) {
            Color.argb(0x99, 0xC4, 0xC4, 0xC4)
        } else {
            Color.argb(0x99, 0x49, 0x49, 0x49)
        }
    }

    // ──────────────────────────────────────────────
    // Background drawable construction
    // ──────────────────────────────────────────────

    private fun buildBackgroundDrawables() {
        // Glass-morphism: vertical gradient from lighter top to darker bottom
        val topColor = if (isDarkTheme) {
            Color.argb(0xCC, 0x2C, 0x2C, 0x2C)
        } else {
            Color.argb(0xEE, 0xF5, 0xF5, 0xF5)
        }
        val bottomColor = if (isDarkTheme) {
            Color.argb(0x99, 0x1E, 0x1E, 0x1E)
        } else {
            Color.argb(0xBB, 0xE0, 0xE0, 0xE0)
        }

        pillBackground.colors = intArrayOf(topColor, bottomColor)
        pillBackground.gradientType = GradientDrawable.LINEAR_GRADIENT
        pillBackground.orientation = GradientDrawable.Orientation.TOP_BOTTOM

        // Unfocused border — subtle surface outline
        val unfocusedBorderColor = if (isDarkTheme) {
            Color.argb(0x33, 0xFF, 0xFF, 0xFF)  // 20 % white
        } else {
            Color.argb(0x1A, 0x00, 0x00, 0x00)  // 10 % black
        }
        unfocusedBorder.setColor(Color.TRANSPARENT)
        unfocusedBorder.setStroke(dp(1f), unfocusedBorderColor)

        // Focused border — accent colour
        focusedBorder.setColor(Color.TRANSPARENT)
        focusedBorder.setStroke(dp(2f), colorAccent)
    }

    // ──────────────────────────────────────────────
    // Expand / collapse with ValueAnimator
    // ──────────────────────────────────────────────

    /**
     * Expand the search bar with an overshoot animation.
     * Safe to call repeatedly — a running collapse will be cancelled.
     */
    fun expand() {
        if (isExpanded) return
        isExpanded = true

        cancelAnimators()
        requestFocusForSearch()

        expandAnimator = ValueAnimator.ofFloat(currentExpandFraction, 1f).apply {
            duration = ((1f - currentExpandFraction) * ANIM_DURATION).toLong()
                .coerceAtLeast(80L)
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                applyExpandFraction(fraction)
            }
            addListener(onEnd = { expandAnimator = null })
            start()
        }
    }

    /**
     * Collapse the search bar with a decelerate animation.
     * Safe to call repeatedly — a running expand will be cancelled.
     */
    fun collapse() {
        if (!isExpanded) return
        isExpanded = false

        cancelAnimators()
        searchEditText.clearFocus()

        collapseAnimator = ValueAnimator.ofFloat(currentExpandFraction, 0f).apply {
            duration = (currentExpandFraction * ANIM_DURATION).toLong()
                .coerceAtLeast(80L)
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                applyExpandFraction(fraction)
            }
            addListener(onEnd = { collapseAnimator = null })
            start()
        }
    }

    private fun cancelAnimators() {
        expandAnimator?.cancel()
        expandAnimator = null
        collapseAnimator?.cancel()
        collapseAnimator = null
    }

    // ──────────────────────────────────────────────
    // Apply a 0…1 expand fraction to all visual properties
    // ──────────────────────────────────────────────

    private fun applyExpandFraction(fraction: Float) {
        currentExpandFraction = fraction

        // --- Scale (width) ---
        val scale = 1f + (EXPAND_WIDTH_RATIO - 1f) * fraction
        scaleX = scale

        // --- Alpha (fade icons in slightly) ---
        val iconAlpha = 0.6f + 0.4f * fraction
        searchIcon.alpha = iconAlpha
        micIcon.alpha = iconAlpha

        // --- Elevation ---
        elevation = ELEVATION_FOCUSED_PX * fraction

        // --- Border colour interpolation ---
        val unfocusedStroke = if (isDarkTheme) {
            Color.argb(0x33, 0xFF, 0xFF, 0xFF)
        } else {
            Color.argb(0x1A, 0x00, 0x00, 0x00)
        }
        val borderArgb = interpolateColor(unfocusedStroke, colorAccent, fraction)
        val strokeWidthPx = dpF(1f) + dpF(1f) * fraction  // 1→2 dp
        pillBackground.setStroke(strokeWidthPx.toInt(), borderArgb)

        // --- Background alpha shift (slightly more opaque when focused) ---
        val bgAlphaShift = fraction * 0.1f  // up to 10 % more opaque
        pillBackground.alpha = ((1f + bgAlphaShift) * 255).toInt().coerceIn(0, 255)
    }

    // ──────────────────────────────────────────────
    // Colour interpolation helper
    // ──────────────────────────────────────────────

    private fun interpolateColor(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.argb(a, r, g, b)
    }

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    /** Programmatically set the query text; triggers [OnSearchQueryListener]. */
    fun setQuery(query: String) {
        searchEditText.setText(query)
        searchEditText.setSelection(query.length)
    }

    /** Clear the query text and collapse the bar. */
    fun clearQuery() {
        searchEditText.text.clear()
        collapse()
    }

    /** Register a query-change callback. */
    fun setOnSearchQueryListener(listener: OnSearchQueryListener?) {
        this.queryListener = listener
    }

    /** Register a voice-search-click callback. */
    fun setOnVoiceSearchClickedListener(listener: OnVoiceSearchClickedListener?) {
        this.voiceListener = listener
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun requestFocusForSearch() {
        searchEditText.post {
            searchEditText.requestFocus()
            val imm = ContextCompat.getSystemService(context, android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(searchEditText, 0)
        }
    }

    // ──────────────────────────────────────────────
    // Theme re-application (e.g. dark mode toggle at runtime)
    // ──────────────────────────────────────────────

    /**
     * Call after a runtime theme / night-mode change to refresh all
     * colours and drawables.
     */
    fun refreshTheme() {
        resolveThemeColors()
        buildBackgroundDrawables()
        tintIcons()
        searchEditText.setTextColor(colorOnSurfaceVariant)
        searchEditText.setHintTextColor(colorHint)
        applyExpandFraction(currentExpandFraction)
    }

    private fun tintIcons() {
        searchIcon.drawable?.let { d ->
            val tinted = DrawableCompat.wrap(d.mutate())
            DrawableCompat.setTint(tinted, colorOnSurfaceVariant)
            searchIcon.setImageDrawable(tinted)
        }
        micIcon.drawable?.let { d ->
            val tinted = DrawableCompat.wrap(d.mutate())
            DrawableCompat.setTint(tinted, colorOnSurfaceVariant)
            micIcon.setImageDrawable(tinted)
        }
    }

    // ──────────────────────────────────────────────
    // Measure / layout — enforce pill height
    // ──────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hSpec = MeasureSpec.makeMeasureSpec(BAR_HEIGHT_PX, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, hSpec)
    }
}
