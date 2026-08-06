package com.example.clawlessexplorer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.ImageLoader
import coil.load
import com.example.clawlessexplorer.databinding.ActivityGalleryBinding
import java.io.File

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var images: List<File>
    private var startIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val dirPath = intent.getStringExtra("extra_dir_path")
        val startName = intent.getStringExtra("extra_start_file")
        
        val dir = if (dirPath != null) File(dirPath) else null
        if (dir == null || !dir.isDirectory) {
            finish()
            return
        }

        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "svg", "avif")
        images = dir.listFiles()?.filter { 
            it.isFile && it.extension.lowercase() in imageExts 
        }?.sortedBy { it.name } ?: emptyList()

        if (images.isEmpty()) {
            finish()
            return
        }

        startIndex = if (startName != null) {
            images.indexOfFirst { it.name == startName }.coerceAtLeast(0)
        } else 0

        val adapter = GalleryAdapter(images)
        binding.pager.adapter = adapter
        binding.pager.setCurrentItem(startIndex, false)
        
        updateCounter(startIndex)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCounter(position)
            }
        })
    }

    private fun updateCounter(position: Int) {
        binding.counter.text = "${position + 1} / ${images.size}"
    }

    inner class GalleryAdapter(private val files: List<File>) : RecyclerView.Adapter<GalleryAdapter.VH>() {
        
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view as ImageView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.image.load(files[position]) {
                crossfade(true)
            }
        }

        override fun getItemCount() = files.size
    }
}
