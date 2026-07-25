package com.example.clawlessexplorer

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class BookmarkManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Bookmark(
        val path: String,
        val name: String,
        val addedTimestamp: Long
    )

    fun addBookmark(path: String) {
        if (path.isBlank()) return
        val current = getBookmarks().toMutableList()
        current.removeAll { it.path == path }
        val file = java.io.File(path)
        current.add(0, Bookmark(path, file.name, System.currentTimeMillis()))
        saveBookmarks(current)
    }

    fun removeBookmark(path: String) {
        val current = getBookmarks().toMutableList()
        current.removeAll { it.path == path }
        saveBookmarks(current)
    }

    fun isBookmarked(path: String): Boolean {
        return getBookmarks().any { it.path == path }
    }

    fun getBookmarks(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Bookmark>>() {}.type
            Gson().fromJson(raw, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getBookmarkCount(): Int {
        return getBookmarks().size
    }

    fun updateLastAccessed(path: String) {
        val current = getBookmarks().toMutableList()
        val index = current.indexOfFirst { it.path == path }
        if (index >= 0) {
            current[index] = current[index].copy(addedTimestamp = System.currentTimeMillis())
            saveBookmarks(current)
        }
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val raw = Gson().toJson(list)
        prefs.edit().putString(KEY_BOOKMARKS, raw).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_BOOKMARKS = "bookmarks_json"
    }
}
