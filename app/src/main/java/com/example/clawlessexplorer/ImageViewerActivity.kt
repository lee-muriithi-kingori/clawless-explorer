package com.example.clawlessexplorer

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import coil.load
import com.example.clawlessexplorer.databinding.ActivityImageViewerBinding
import java.io.File
import java.text.DecimalFormat

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private var overlayVisible = true
    private var imageWidth = 0
    private var imageHeight = 0
    private var fileSize = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()

        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topOverlay.updatePadding(top = systemBars.top + 8)
            binding.bottomOverlay.updatePadding(bottom = systemBars.bottom + 8)
            insets
        }

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val file = File(path)
        if (!file.exists()) { finish(); return }

        binding.filenameText.text = file.name
        binding.infoNameText.text = file.name
        fileSize = file.length()

        binding.imageView.load(file) {
            crossfade(true)
            allowHardware(false)
            listener(onSuccess = { _, result ->
                imageWidth = result.size.width.toInt()
                imageHeight = result.size.height.toInt()
                updateInfoText()
            })
        }

        if (imageWidth == 0 || imageHeight == 0) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            imageWidth = opts.outWidth
            imageHeight = opts.outHeight
            updateInfoText()
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.btnShare.setOnClickListener {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.provider", file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share image"))
        }

        binding.btnDelete.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete image?")
                .setMessage("This action cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    if (file.delete()) {
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleOverlays()
                return true
            }
        })

        binding.imageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        scheduleOverlayHide()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toggleOverlays() {
        if (overlayVisible) hideOverlays() else showOverlays()
    }

    private fun showOverlays() {
        overlayVisible = true
        ObjectAnimator.ofFloat(binding.topOverlay, View.ALPHA, 0f, 1f).setDuration(200).start()
        ObjectAnimator.ofFloat(binding.bottomOverlay, View.ALPHA, 0f, 1f).setDuration(200).start()
        binding.topOverlay.visibility = View.VISIBLE
        binding.bottomOverlay.visibility = View.VISIBLE
        scheduleOverlayHide()
    }

    private fun hideOverlays() {
        overlayVisible = false
        ObjectAnimator.ofFloat(binding.topOverlay, View.ALPHA, 1f, 0f).setDuration(200).start()
        ObjectAnimator.ofFloat(binding.bottomOverlay, View.ALPHA, 1f, 0f).setDuration(200).start()
        binding.topOverlay.postDelayed({
            if (!overlayVisible) {
                binding.topOverlay.visibility = View.GONE
                binding.bottomOverlay.visibility = View.GONE
            }
        }, 210)
    }

    private fun scheduleOverlayHide() {
        binding.topOverlay.removeCallbacks(hideOverlayRunnable)
        binding.bottomOverlay.removeCallbacks(hideOverlayRunnable)
        binding.topOverlay.postDelayed(hideOverlayRunnable, 3000)
    }

    private val hideOverlayRunnable = Runnable { hideOverlays() }

    private fun updateInfoText() {
        val sizeStr = formatFileSize(fileSize)
        val dimStr = if (imageWidth > 0 && imageHeight > 0) {
            "$imageWidth × $imageHeight"
        } else {
            "Loading…"
        }
        binding.infoDetailsText.text = "$dimStr · $sizeStr"
    }

    private fun formatFileSize(bytes: Long): String {
        val df = DecimalFormat("#.#")
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${df.format(bytes / 1024.0)} KB"
            bytes < 1024L * 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024))} MB"
            else -> "${df.format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    companion object {
        const val EXTRA_PATH = "extra_image_path"

        fun intent(context: Context, path: String): Intent =
            Intent(context, ImageViewerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
            }
    }
}
