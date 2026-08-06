package com.example.clawlessexplorer

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.clawlessexplorer.databinding.ActivityAppManagerBinding
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppManagerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } // User apps only
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        if (apps.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.recycler.layoutManager = LinearLayoutManager(this)
            binding.recycler.adapter = AppAdapter(apps)
        }
    }

    inner class AppAdapter(private val apps: List<ApplicationInfo>) : RecyclerView.Adapter<AppAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val name: TextView = view.findViewById(R.id.appName)
            val pkg: TextView = view.findViewById(R.id.appPackage)
            val size: TextView = view.findViewById(R.id.appSize)
            val extract: View = view.findViewById(R.id.btnExtract)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = 
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.loadIcon(packageManager))
            holder.name.text = app.loadLabel(packageManager)
            holder.pkg.text = app.packageName

            val apkFile = File(app.sourceDir)
            val sizeStr = formatFileSize(apkFile.length())
            holder.size.text = sizeStr

            holder.extract.setOnClickListener {
                extractApk(app, apkFile)
            }
        }

        override fun getItemCount() = apps.size

        private fun extractApk(app: ApplicationInfo, apkFile: File) {
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val destDir = File(downloadsDir, "ExtractedAPKs")
                destDir.mkdirs()
                val label = app.loadLabel(packageManager).toString().replace(" ", "_")
                val destFile = File(destDir, "${label}_${app.packageName}.apk")
                apkFile.copyTo(destFile, overwrite = true)
                Snackbar.make(
                    binding.root,
                    "APK saved to ${destFile.absolutePath}",
                    Snackbar.LENGTH_LONG
                ).setAction("Share") {
                    val uri = FileProvider.getUriForFile(
                        this@AppManagerActivity,
                        "${packageName}.provider",
                        destFile
                    )
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Share APK"))
                }.show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }
    }
}
