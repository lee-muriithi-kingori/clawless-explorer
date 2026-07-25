package com.example.clawlessexplorer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.clawlessexplorer.databinding.ActivityPdfViewerBinding
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private lateinit var pdfiumCore: PdfiumCore
    private var pdfDocument: PdfDocument? = null
    private var totalPages = 0
    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.setPadding(0, systemBars.top, 0, 0)
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                binding.bottomBar.paddingTop,
                binding.bottomBar.paddingRight,
                systemBars.bottom + 6
            )
            insets
        }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
        val file = File(filePath)
        if (!file.exists()) {
            finish()
            return
        }

        binding.titleText.text = file.name
        pdfiumCore = PdfiumCore(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPrev.setOnClickListener { goToPage(currentPage - 1) }
        binding.btnNext.setOnClickListener { goToPage(currentPage + 1) }
        binding.btnGo.setOnClickListener { goToPageFromInput() }
        binding.btnZoomIn.setOnClickListener { binding.pdfView.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.pdfView.zoomOut() }

        binding.pageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                goToPageFromInput()
                true
            } else false
        }

        binding.pdfView.onRenderComplete = {
            binding.progressBar.visibility = View.GONE
        }

        openDocument(file)
    }

    private fun openDocument(file: File) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) {
                    val fd = ParcelFileDescriptor.open(
                        file,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    pdfiumCore.newDocument(fd)
                }
                pdfDocument = doc
                totalPages = withContext(Dispatchers.IO) {
                    pdfiumCore.getPageCount(doc)
                }
                currentPage = 0
                updatePageCounter()
                loadCurrentPage()
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@PdfViewerActivity,
                    "Failed to open PDF: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun loadCurrentPage() {
        val doc = pdfDocument ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.pdfView.loadPage(pdfiumCore, doc, currentPage)
        updatePageCounter()
    }

    private fun goToPage(page: Int) {
        if (page < 0 || page >= totalPages) return
        currentPage = page
        loadCurrentPage()
    }

    private fun goToPageFromInput() {
        val input = binding.pageInput.text.toString().trim()
        if (input.isEmpty()) return
        val page = input.toIntOrNull() ?: return
        if (page < 1 || page > totalPages) {
            Toast.makeText(this, "Page must be 1-$totalPages", Toast.LENGTH_SHORT).show()
            return
        }
        goToPage(page - 1)

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.pageInput.windowToken, 0)
        binding.pageInput.clearFocus()
    }

    private fun updatePageCounter() {
        binding.pageCounterText.text = "Page ${currentPage + 1} of $totalPages"
        binding.btnPrev.alpha = if (currentPage > 0) 1.0f else 0.3f
        binding.btnNext.alpha = if (currentPage < totalPages - 1) 1.0f else 0.3f
        binding.pageInput.hint = "1–$totalPages"
    }

    override fun onDestroy() {
        binding.pdfView.onRenderComplete = null
        pdfDocument?.let { doc ->
            try {
                pdfiumCore.closeDocument(doc)
            } catch (_: Exception) {
            }
        }
        pdfDocument = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_pdf_file_path"

        fun intent(context: Context, filePath: String): Intent =
            Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
            }
    }
}
