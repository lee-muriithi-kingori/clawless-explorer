package com.example.clawlessexplorer

import java.io.File

/**
 * Singleton clipboard for file cut/copy/paste operations.
 * Supports copy and move (cut) modes with conflict resolution.
 */
object FileClipboard {
    
    enum class Mode { COPY, MOVE }
    
    var files: List<File> = emptyList()
        private set
    
    var mode: Mode = Mode.COPY
        private set
    
    var sourcePath: File? = null
        private set
    
    val hasContent: Boolean get() = files.isNotEmpty()
    
    fun copy(files: List<File>, source: File) {
        this.files = files.toList()
        this.mode = Mode.COPY
        this.sourcePath = source
    }
    
    fun move(files: List<File>, source: File) {
        this.files = files.toList()
        this.mode = Mode.MOVE
        this.sourcePath = source
    }
    
    fun clear() {
        files = emptyList()
        sourcePath = null
    }
    
    /**
     * Paste files into the target directory.
     * Returns list of successfully pasted files and list of failures.
     */
    fun paste(targetDir: File): Pair<List<File>, List<String>> {
        if (!targetDir.isDirectory || !targetDir.canWrite()) {
            return emptyList<File>() to listOf("Target directory is not writable")
        }
        
        val success = mutableListOf<File>()
        val failures = mutableListOf<String>()
        
        for (source in files) {
            if (!source.exists()) {
                failures.add("${source.name}: Source no longer exists")
                continue
            }
            
            val dest = File(targetDir, source.name)
            if (dest.exists() && source != dest) {
                failures.add("${source.name}: Already exists in target")
                continue
            }
            
            try {
                when (mode) {
                    Mode.COPY -> {
                        if (source.isDirectory) {
                            source.copyRecursively(dest, overwrite = false)
                        } else {
                            source.copyTo(dest, overwrite = false)
                        }
                        success.add(dest)
                    }
                    Mode.MOVE -> {
                        val moved = source.renameTo(dest)
                        if (moved) {
                            success.add(dest)
                        } else {
                            // Fallback: copy then delete
                            if (source.isDirectory) {
                                source.copyRecursively(dest, overwrite = false)
                            } else {
                                source.copyTo(dest, overwrite = false)
                            }
                            source.deleteRecursively()
                            success.add(dest)
                        }
                    }
                }
            } catch (e: Exception) {
                failures.add("${source.name}: ${e.message}")
            }
        }
        
        // Clear clipboard after move (cut) operation
        if (mode == Mode.MOVE) clear()
        
        return success to failures
    }
}
