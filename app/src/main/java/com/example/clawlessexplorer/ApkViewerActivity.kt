package com.example.clawlessexplorer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.clawlessexplorer.databinding.ActivityApkViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApkViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApkViewerBinding
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApkViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val file = File(path)
        if (!file.exists()) { finish(); return }

        binding.appName.text = file.name
        binding.packageName.text = file.absolutePath

        loadApkInfo(file)
    }

    private fun loadApkInfo(file: File) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val archiveInfo = packageManager.getPackageArchiveInfo(
                        file.absolutePath,
                        PackageManager.GET_PERMISSIONS
                    )

                    val appInfo = archiveInfo?.applicationInfo?.apply {
                        sourceDir = file.absolutePath
                        publicSourceDir = file.absolutePath
                    }

                    val icon: Drawable? = try {
                        appInfo?.let { packageManager.getApplicationIcon(it) }
                    } catch (e: Exception) {
                        null
                    }

                    val appName = try {
                        appInfo?.let { packageManager.getApplicationLabel(it).toString() }
                            ?: file.nameWithoutExtension
                    } catch (e: Exception) {
                        file.nameWithoutExtension
                    }

                    val packageName = archiveInfo?.packageName ?: "Unknown"

                    val versionName = try {
                        archiveInfo?.versionName ?: "Unknown"
                    } catch (e: Exception) {
                        "Unknown"
                    }

                    val versionCode = try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            archiveInfo?.longVersionCode?.toString() ?: "Unknown"
                        } else {
                            @Suppress("DEPRECATION")
                            (archiveInfo?.versionCode?.toString() ?: "Unknown")
                        }
                    } catch (e: Exception) {
                        "Unknown"
                    }

                    val minSdk = try {
                        appInfo?.minSdkVersion?.toString() ?: "Unknown"
                    } catch (e: Exception) {
                        "Unknown"
                    }

                    val targetSdk = try {
                        appInfo?.targetSdkVersion?.toString() ?: "Unknown"
                    } catch (e: Exception) {
                        "Unknown"
                    }

                    val permissions = archiveInfo?.requestedPermissions?.toList() ?: emptyList()

                    // Apktool-style structure peek: dex files plus entry count,
                    // read straight from the zip without decoding anything.
                    val contents: Pair<List<Pair<String, Long>>, Int> = try {
                        java.util.zip.ZipFile(file).use { zip ->
                            val entries = java.util.Collections.list(zip.entries())
                            val dex = entries
                                .filter { it.name.endsWith(".dex") }
                                .sortedBy { it.name }
                                .map { it.name to it.size }
                            dex to entries.size
                        }
                    } catch (_: Exception) {
                        emptyList<Pair<String, Long>>() to -1
                    }
                    val dexEntries = contents.first
                    val totalEntries = contents.second

                    val signerInfo = try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            val signingInfo = archiveInfo?.signingInfo
                            val certs = signingInfo?.apkContentsSigners
                            certs?.map { it.toString() }?.joinToString("\n") ?: ""
                        } else {
                            @Suppress("DEPRECATION")
                            val certs = archiveInfo?.signatures
                            certs?.map { it.toString() }?.joinToString("\n") ?: ""
                        }
                    } catch (e: Exception) {
                        ""
                    }

                    ApkData(
                        icon = icon,
                        appName = appName,
                        packageName = packageName,
                        versionName = versionName,
                        versionCode = versionCode,
                        minSdk = minSdk,
                        targetSdk = targetSdk,
                        fileSize = file.length(),
                        lastModified = Date(file.lastModified()),
                        permissions = permissions,
                        dexEntries = dexEntries,
                        totalEntries = totalEntries,
                        signerInfo = signerInfo
                    )
                } catch (e: Exception) {
                    ApkData(
                        appName = file.nameWithoutExtension,
                        packageName = "Unable to parse",
                        fileSize = file.length(),
                        lastModified = Date(file.lastModified())
                    )
                }
            }

            displayApkData(result)
        }
    }

    private fun displayApkData(data: ApkData) {
        binding.appName.text = data.appName
        binding.packageName.text = data.packageName

        data.icon?.let {
            binding.appIcon.setImageDrawable(it)
            binding.appIcon.background = null
            binding.appIcon.clipToOutline = true
        }

        binding.versionName.text = data.versionName
        binding.versionCode.text = data.versionCode
        binding.minSdk.text = data.minSdk
        binding.targetSdk.text = data.targetSdk
        binding.fileSize.text = Formatter.formatShortFileSize(this, data.fileSize)
        binding.lastModified.text = dateFormat.format(data.lastModified)

        if (data.permissions.isNotEmpty()) {
            binding.permissionsCard.visibility = View.VISIBLE
            binding.permissionsList.text = data.permissions.joinToString("\n")
        } else {
            binding.permissionsCard.visibility = View.GONE
        }

        if (data.totalEntries >= 0) {
            binding.contentsCard.visibility = View.VISIBLE
            val header = "${data.totalEntries} entries"
            val dexLines = if (data.dexEntries.isEmpty()) {
                "No dex files found"
            } else {
                data.dexEntries.joinToString("\n") { (name, size) ->
                    "$name (${Formatter.formatShortFileSize(this, size)})"
                }
            }
            binding.contentsList.text = "$header\n$dexLines"
        } else {
            binding.contentsCard.visibility = View.GONE
        }

        if (data.signerInfo.isNotBlank()) {
            binding.signerCard.visibility = View.VISIBLE
            binding.signerInfo.text = data.signerInfo
        }
    }

    private data class ApkData(
        val icon: Drawable? = null,
        val appName: String = "",
        val packageName: String = "",
        val versionName: String = "Unknown",
        val versionCode: String = "Unknown",
        val minSdk: String = "Unknown",
        val targetSdk: String = "Unknown",
        val fileSize: Long = 0,
        val lastModified: Date = Date(),
        val permissions: List<String> = emptyList(),
        val dexEntries: List<Pair<String, Long>> = emptyList(),
        val totalEntries: Int = -1,
        val signerInfo: String = ""
    )

    companion object {
        const val EXTRA_PATH = "extra_apk_path"

        fun intent(context: Context, path: String): Intent =
            Intent(context, ApkViewerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
            }
    }
}
