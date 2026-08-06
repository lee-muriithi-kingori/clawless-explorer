package com.example.clawlessexplorer

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import java.io.File

class BreadcrumbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : HorizontalScrollView(context, attrs, defStyle) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(8, 4, 8, 4)
    }

    private var onNavigate: ((File) -> Unit)? = null
    private var currentPath: File? = null

    init {
        isHorizontalScrollBarEnabled = false
        addView(container)
    }

    fun setPath(path: File, navigate: (File) -> Unit) {
        onNavigate = navigate
        currentPath = path
        container.removeAllViews()

        val segments = buildSegments(path)
        
        segments.forEachIndexed { index, (name, file) ->
            if (index > 0) {
                val separator = TextView(context).apply {
                    text = "›"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.md_on_surface_variant))
                    setPadding(4, 0, 4, 0)
                }
                container.addView(separator)
            }

            val chip = TextView(context).apply {
                text = name
                textSize = 13f
                maxLines = 1
                isSingleLine = true
                setTextColor(
                    if (index == segments.lastIndex)
                        ContextCompat.getColor(context, R.color.md_primary)
                    else
                        ContextCompat.getColor(context, R.color.md_on_surface_variant)
                )
                setPadding(8, 6, 8, 6)
                isClickable = true
                isFocusable = true
                setOnClickListener { onNavigate?.invoke(file) }
                
                // Subtle ripple
                val attrs = intArrayOf(android.R.attr.selectableItemBackground)
                val ta = context.obtainStyledAttributes(attrs)
                background = ta.getDrawable(0)
                ta.recycle()
            }
            container.addView(chip)
        }

        // Scroll to end
        post { fullScroll(View.FOCUS_RIGHT) }
    }

    private fun buildSegments(path: File): List<Pair<String, File>> {
        val segments = mutableListOf<Pair<String, File>>()
        var current: File? = path
        while (current != null) {
            val name = if (current.parent == null) "/" else current.name
            segments.add(0, name to current)
            current = current.parentFile
        }
        
        // If too many segments (>6), collapse middle
        if (segments.size > 6) {
            val collapsed = segments.take(2) + ("…" to segments[2].second) + segments.takeLast(3)
            return collapsed
        }
        
        return segments
    }
}
