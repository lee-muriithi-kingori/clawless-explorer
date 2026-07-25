package com.example.clawlessexplorer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.clawlessexplorer.databinding.ActivityJsonFormatterBinding
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class JsonFormatterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJsonFormatterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJsonFormatterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnFormat.setOnClickListener { formatJson() }
        binding.btnMinify.setOnClickListener { minifyJson() }
        binding.btnClear.setOnClickListener { clearAll() }
        binding.btnCopy.setOnClickListener { copyOutput() }
    }

    private fun formatJson() {
        val input = binding.inputText.text?.toString() ?: return
        if (input.isBlank()) {
            binding.outputText.text = "Please enter JSON"
            return
        }

        try {
            val trimmed = input.trim()
            val formatted = if (trimmed.startsWith("[")) {
                val jsonArray = JSONArray(trimmed)
                jsonArray.toString(2)
            } else {
                val jsonObject = JSONObject(trimmed)
                jsonObject.toString(2)
            }
            binding.outputText.text = formatted
        } catch (e: JSONException) {
            binding.outputText.text = "Invalid JSON:\n${e.message}"
        }
    }

    private fun minifyJson() {
        val input = binding.inputText.text?.toString() ?: return
        if (input.isBlank()) {
            binding.outputText.text = "Please enter JSON"
            return
        }

        try {
            val trimmed = input.trim()
            val minified = if (trimmed.startsWith("[")) {
                val jsonArray = JSONArray(trimmed)
                jsonArray.toString()
            } else {
                val jsonObject = JSONObject(trimmed)
                jsonObject.toString()
            }
            binding.outputText.text = minified
        } catch (e: JSONException) {
            binding.outputText.text = "Invalid JSON:\n${e.message}"
        }
    }

    private fun clearAll() {
        binding.inputText.text?.clear()
        binding.outputText.text = ""
    }

    private fun copyOutput() {
        val text = binding.outputText.text?.toString() ?: return
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("json", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, JsonFormatterActivity::class.java)
    }
}
