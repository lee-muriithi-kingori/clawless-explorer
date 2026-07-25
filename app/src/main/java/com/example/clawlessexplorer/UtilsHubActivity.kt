package com.example.clawlessexplorer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.SeekBar
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clawlessexplorer.databinding.ActivityUtilsHubBinding
import com.google.android.material.card.MaterialCardView

class UtilsHubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUtilsHubBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUtilsHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val tools = listOf(
            ToolItem(R.drawable.ic_tool_encode, "Base64 Encode/Decode", "Encode and decode Base64 strings", ToolType.BASE64),
            ToolItem(R.drawable.ic_tool_url, "URL Encode/Decode", "Encode and decode URL strings", ToolType.URL),
            ToolItem(R.drawable.ic_tool_json, "JSON Formatter", "Format and minify JSON data", ToolType.JSON),
            ToolItem(R.drawable.ic_tool_hash, "Hash Generator", "MD5, SHA-1, SHA-256 hashing", ToolType.HASH),
            ToolItem(R.drawable.ic_tool_color_picker, "Color Picker", "Pick colors, view hex/RGB values", ToolType.COLOR),
            ToolItem(R.drawable.ic_tool_network, "IP Info", "Show device IP and hostname", ToolType.IP),
            ToolItem(R.drawable.ic_tool_diff, "Text Diff", "Compare two text inputs side by side", ToolType.DIFF),
            ToolItem(R.drawable.ic_tool_regex, "Regex Tester", "Test regex patterns in real time", ToolType.REGEX)
        )

        binding.toolsGrid.layoutManager = GridLayoutManager(this, 2)
        binding.toolsGrid.adapter = ToolAdapter(tools) { tool ->
            when (tool.type) {
                ToolType.BASE64, ToolType.URL -> {
                    startActivity(Intent(this, EncodeDecodeActivity::class.java).apply {
                        putExtra(EncodeDecodeActivity.EXTRA_INITIAL_TAB, tool.type == ToolType.URL)
                    })
                }
                ToolType.JSON -> startActivity(Intent(this, JsonFormatterActivity::class.java))
                ToolType.HASH -> startActivity(Intent(this, HashActivity::class.java))
                ToolType.COLOR -> showColorPickerDialog()
                ToolType.IP -> showIpInfoDialog()
                ToolType.DIFF -> showTextDiffDialog()
                ToolType.REGEX -> showRegexTesterDialog()
            }
        }
    }

    private fun showColorPickerDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_color_picker, null)
        val colorPreview = view.findViewById<View>(R.id.colorPreview)
        val hexText = view.findViewById<TextView>(R.id.hexValue)
        val rgbText = view.findViewById<TextView>(R.id.rgbValue)

        var currentColor = 0xFF5B5BF6.toInt()
        colorPreview.setBackgroundColor(currentColor)
        hexText.text = String.format("#%06X", 0xFFFFFF and currentColor)
        rgbText.text = String.format("R:%d G:%d B:%d",
            (currentColor shr 16) and 0xFF,
            (currentColor shr 8) and 0xFF,
            currentColor and 0xFF
        )

        val sliderR = view.findViewById<SeekBar>(R.id.sliderRed)
        val sliderG = view.findViewById<SeekBar>(R.id.sliderGreen)
        val sliderB = view.findViewById<SeekBar>(R.id.sliderBlue)

        fun updateColor() {
            currentColor = 0xFF000000.toInt() or
                (sliderR.progress shl 16) or
                (sliderG.progress shl 8) or
                sliderB.progress
            colorPreview.setBackgroundColor(currentColor)
            hexText.text = String.format("#%06X", 0xFFFFFF and currentColor)
            rgbText.text = String.format("R:%d G:%d B:%d",
                sliderR.progress, sliderG.progress, sliderB.progress
            )
        }

        sliderR.setOnSeekBarChangeListener(object : SeekBarListener() { override fun onProgressChanged() = updateColor() })
        sliderG.setOnSeekBarChangeListener(object : SeekBarListener() { override fun onProgressChanged() = updateColor() })
        sliderB.setOnSeekBarChangeListener(object : SeekBarListener() { override fun onProgressChanged() = updateColor() })

        sliderR.progress = (currentColor.toInt() shr 16) and 0xFF
        sliderG.progress = (currentColor.toInt() shr 8) and 0xFF
        sliderB.progress = currentColor.toInt() and 0xFF

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Color Picker")
            .setView(view)
            .setPositiveButton("Copy Hex") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("color", hexText.text))
                android.widget.Toast.makeText(this, "Copied ${hexText.text}", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showIpInfoDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_ip_info, null)
        val ipText = view.findViewById<TextView>(R.id.ipAddress)
        val hostnameText = view.findViewById<TextView>(R.id.hostname)

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        ipText.text = address.hostAddress ?: "Unknown"
                        hostnameText.text = java.net.InetAddress.getLocalHost().hostName ?: "Unknown"
                        break
                    }
                }
                if (ipText.text != "Loading...") break
            }
        } catch (e: Exception) {
            ipText.text = "Unable to determine"
            hostnameText.text = "Unknown"
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Network Info")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showTextDiffDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text_diff, null)
        val inputA = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputA)
        val inputB = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputB)
        val resultText = view.findViewById<TextView>(R.id.diffResult)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Text Diff")
            .setView(view)
            .setPositiveButton("Compare") { _, _ ->
                val a = inputA.text?.toString() ?: ""
                val b = inputB.text?.toString() ?: ""
                val linesA = a.lines()
                val linesB = b.lines()
                val maxLines = maxOf(linesA.size, linesB.size)
                val result = StringBuilder()
                for (i in 0 until maxLines) {
                    val lineA = linesA.getOrElse(i) { "" }
                    val lineB = linesB.getOrElse(i) { "" }
                    when {
                        lineA == lineB -> result.appendLine("  $lineA")
                        else -> {
                            result.appendLine("- $lineA")
                            result.appendLine("+ $lineB")
                        }
                    }
                }
                resultText.text = result.toString()
                resultText.visibility = View.VISIBLE
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRegexTesterDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_regex_tester, null)
        val patternInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.regexPattern)
        val testInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.testString)
        val resultText = view.findViewById<TextView>(R.id.regexResult)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Regex Tester")
            .setView(view)
            .setPositiveButton("Test") { _, _ ->
                try {
                    val regex = Regex(patternInput.text?.toString() ?: "")
                    val matches = regex.findAll(testInput.text?.toString() ?: "")
                    val results = matches.map { "\"${it.value}\" at index ${it.range.first}" }.toList()
                    if (results.isEmpty()) {
                        resultText.text = "No matches found"
                    } else {
                        resultText.text = "${results.size} match(es):\n" + results.joinToString("\n")
                    }
                    resultText.visibility = View.VISIBLE
                } catch (e: Exception) {
                    resultText.text = "Invalid regex: ${e.message}"
                    resultText.visibility = View.VISIBLE
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private data class ToolItem(
        val iconRes: Int,
        val name: String,
        val description: String,
        val type: ToolType
    )

    private enum class ToolType {
        BASE64, URL, JSON, HASH, COLOR, IP, DIFF, REGEX
    }

    private class ToolAdapter(
        private val items: List<ToolItem>,
        private val onClick: (ToolItem) -> Unit
    ) : RecyclerView.Adapter<ToolAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.toolCard)
            val iconBg: View = view.findViewById(R.id.iconBackground)
            val icon: ImageView = view.findViewById(R.id.toolIcon)
            val name: TextView = view.findViewById(R.id.toolName)
            val desc: TextView = view.findViewById(R.id.toolDescription)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tool_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.icon.setImageResource(item.iconRes)
            holder.name.text = item.name
            holder.desc.text = item.description

            val colors = intArrayOf(
                0xFF5B5BF6.toInt(), 0xFFFF6B9D.toInt(), 0xFF10B981.toInt(),
                0xFFF59E0B.toInt(), 0xFFEC4899.toInt(), 0xFF06B6D4.toInt(),
                0xFF8B5CF6.toInt(), 0xFFF97316.toInt()
            )
            val bg = holder.iconBg.background
            if (bg is android.graphics.drawable.GradientDrawable) {
                bg.setColor(colors[position % colors.size])
            }

            holder.card.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    private abstract class SeekBarListener : android.widget.SeekBar.OnSeekBarChangeListener {
        abstract fun onProgressChanged()
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgressChanged()
        }
        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, UtilsHubActivity::class.java)
    }
}
