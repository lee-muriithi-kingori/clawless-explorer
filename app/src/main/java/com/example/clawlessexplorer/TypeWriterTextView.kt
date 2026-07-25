package com.example.clawlessexplorer

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import kotlinx.coroutines.*

class TypeWriterTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var mText: CharSequence? = null
    private var mIndex: Int = 0
    private var mDelay: Long = 100 
    private var mJob: Job? = null
    private var mCursorVisible = true

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mJob?.cancel()
        scope.cancel()
    }

    fun animateText(text: CharSequence) {
        mText = text
        mIndex = 0
        mJob?.cancel()
        mJob = scope.launch {
            delay(500)
            val text = mText ?: return@launch
            while (mIndex <= text.length) {
                val currentText = text.subSequence(0, mIndex++)
                setText(StringBuilder().append(currentText).append("|"))
                delay(mDelay)
            }
            
            // Blinking cursor effect
            while (isActive) {
                mCursorVisible = !mCursorVisible
                setText(StringBuilder().append(mText).append(if (mCursorVisible) "|" else " "))
                delay(500)
            }
        }
    }

    fun setCharacterDelay(millis: Long) {
        mDelay = millis
    }
}
