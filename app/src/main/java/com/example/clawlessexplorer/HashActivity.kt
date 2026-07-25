package com.example.clawlessexplorer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.clawlessexplorer.databinding.ActivityHashBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class HashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHashBinding
    private var filePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath != null) {
            val file = File(filePath!!)
            if (file.exists()) {
                binding.fileInfoCard.visibility = View.VISIBLE
                binding.fileInfoText.text = "${file.name} (${Formatter.formatShortFileSize(this, file.length())})"
                binding.inputLayout.visibility = View.GONE
            }
        }

        binding.btnGenerate.setOnClickListener { generateHashes() }
        binding.btnCopy.setOnClickListener { copyOutput() }
    }

    private fun generateHashes() {
        val algorithms = mutableListOf<String>()
        if (binding.chipMD5.isChecked) algorithms.add("MD5")
        if (binding.chipSHA1.isChecked) algorithms.add("SHA-1")
        if (binding.chipSHA256.isChecked) algorithms.add("SHA-256")
        if (binding.chipSHA512.isChecked) algorithms.add("SHA-512")

        if (algorithms.isEmpty()) {
            Toast.makeText(this, "Select at least one algorithm", Toast.LENGTH_SHORT).show()
            return
        }

        val fileHash = filePath
        val inputText = binding.inputText.text?.toString() ?: ""
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val results = LinkedHashMap<String, String>()
                    for (algo in algorithms) {
                        val digest = MessageDigest.getInstance(algo)
                        if (fileHash != null) {
                            val file = File(fileHash)
                            file.inputStream().use { input ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    digest.update(buffer, 0, read)
                                }
                            }
                        } else {
                            digest.update(inputText.toByteArray(Charsets.UTF_8))
                        }
                        val hashBytes = digest.digest()
                        results[algo] = hashBytes.joinToString("") { "%02x".format(it) }
                    }
                    results as Map<String, String>
                } catch (e: Exception) {
                    mapOf("Error" to (e.message ?: "Unknown error"))
                }
            }

            val output = StringBuilder()
            for ((algo, hash) in result) {
                output.appendLine("$algo:")
                output.appendLine(hash)
                output.appendLine()
            }
            binding.outputText.text = output.toString().trimEnd()
        }
    }

    private fun copyOutput() {
        val text = binding.outputText.text?.toString() ?: return
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("hash", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"

        fun intent(context: Context, filePath: String? = null): Intent =
            Intent(context, HashActivity::class.java).apply {
                filePath?.let { putExtra(EXTRA_FILE_PATH, it) }
            }
    }
}
