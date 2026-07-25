package com.example.clawlessexplorer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.clawlessexplorer.databinding.ActivityEncodeDecodeBinding
import com.google.android.material.tabs.TabLayout
import java.net.URLDecoder
import java.net.URLEncoder

class EncodeDecodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEncodeDecodeBinding
    private var currentMode: Mode = Mode.BASE64

    private enum class Mode { BASE64, URL, HTML }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEncodeDecodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent.getBooleanExtra(EXTRA_INITIAL_TAB, false)) {
            currentMode = Mode.URL
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Base64"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("URL"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("HTML"))

        val initialIndex = if (currentMode == Mode.URL) 1 else 0
        binding.tabLayout.selectTab(binding.tabLayout.getTabAt(initialIndex))
        updateHint()

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentMode = when (tab?.position) {
                    1 -> Mode.URL
                    2 -> Mode.HTML
                    else -> Mode.BASE64
                }
                updateHint()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnEncode.setOnClickListener { performEncode() }
        binding.btnDecode.setOnClickListener { performDecode() }
        binding.btnCopy.setOnClickListener { copyOutput() }
    }

    private fun updateHint() {
        binding.inputLayout.hint = when (currentMode) {
            Mode.BASE64 -> "Text to encode/decode (Base64)"
            Mode.URL -> "URL string to encode/decode"
            Mode.HTML -> "HTML string to encode/decode"
        }
    }

    private fun performEncode() {
        val input = binding.inputText.text?.toString() ?: return
        if (input.isBlank()) {
            binding.outputText.text = "Please enter text"
            return
        }

        try {
            val result = when (currentMode) {
                Mode.BASE64 -> android.util.Base64.encodeToString(
                    input.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                Mode.URL -> URLEncoder.encode(input, "UTF-8")
                Mode.HTML -> {
                    val encoded = StringBuilder()
                    for (c in input) {
                        when {
                            c.code > 127 -> encoded.append("&#${c.code};")
                            c == '<' -> encoded.append("&lt;")
                            c == '>' -> encoded.append("&gt;")
                            c == '&' -> encoded.append("&amp;")
                            c == '"' -> encoded.append("&quot;")
                            c == '\'' -> encoded.append("&#39;")
                            else -> encoded.append(c)
                        }
                    }
                    encoded.toString()
                }
            }
            binding.outputText.text = result
        } catch (e: Exception) {
            binding.outputText.text = "Error: ${e.message}"
        }
    }

    private fun performDecode() {
        val input = binding.inputText.text?.toString() ?: return
        if (input.isBlank()) {
            binding.outputText.text = "Please enter text"
            return
        }

        try {
            val result = when (currentMode) {
                Mode.BASE64 -> {
                    val bytes = android.util.Base64.decode(input, android.util.Base64.DEFAULT)
                    String(bytes, Charsets.UTF_8)
                }
                Mode.URL -> URLDecoder.decode(input, "UTF-8")
                Mode.HTML -> {
                    val decoded: Spanned = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        Html.fromHtml(input, Html.FROM_HTML_MODE_LEGACY)
                    } else {
                        @Suppress("DEPRECATION")
                        Html.fromHtml(input)
                    }
                    decoded.toString()
                }
            }
            binding.outputText.text = result
        } catch (e: Exception) {
            binding.outputText.text = "Error: ${e.message}"
        }
    }

    private fun copyOutput() {
        val text = binding.outputText.text?.toString() ?: return
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("output", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_INITIAL_TAB = "extra_initial_tab_url"

        fun intent(context: Context, startWithUrl: Boolean = false): Intent =
            Intent(context, EncodeDecodeActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_TAB, startWithUrl)
            }
    }
}
