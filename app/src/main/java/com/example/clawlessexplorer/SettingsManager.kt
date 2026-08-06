package com.example.clawlessexplorer

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Lightweight wrapper around SharedPreferences for app settings + recent files list.
 *
 * Settings live in the "app_settings" prefs file. The recent files list is
 * capped at MAX_RECENTS and stored as a JSON array of paths.
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ============ Theme ============

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.key))
        set(value) = prefs.edit().putString(KEY_THEME, value.key).apply()

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(themeMode.nightMode)
    }

    // ============ Defaults ============

    var defaultSort: SortPref
        get() = SortPref.fromKey(prefs.getString(KEY_SORT, SortPref.NAME.key))
        set(value) = prefs.edit().putString(KEY_SORT, value.key).apply()

    var showHiddenByDefault: Boolean
        get() = prefs.getBoolean(KEY_HIDDEN, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDDEN, value).apply()

    var serverEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVER, false)  // Default OFF for security
        set(value) = prefs.edit().putBoolean(KEY_SERVER, value).apply()

    var storageCardCollapsed: Boolean
        get() = prefs.getBoolean(KEY_STORAGE_COLLAPSED, false)
        set(value) = prefs.edit().putBoolean(KEY_STORAGE_COLLAPSED, value).apply()

    // ============ Root Mode ============

    /** Whether the user has chosen root (superuser) mode. */
    var rootMode: Boolean
        get() = prefs.getBoolean(KEY_ROOT_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOT_MODE, value).apply()

    /** Whether the first-launch root dialog has been shown. */
    var hasShownRootDialog: Boolean
        get() = prefs.getBoolean(KEY_ROOT_DIALOG_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOT_DIALOG_SHOWN, value).apply()

    /** Whether root (su) is available on this device. */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    // ============ Animations ============

    /** Whether particle/wave animations are enabled. */
    var animationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANIMATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_ANIMATIONS, value).apply()

    // ============ Recents ============

    /**
     * Add a file path to the recents list, keeping the most recent first.
     * Duplicates are moved to the top. Capped at [MAX_RECENTS].
     */
    fun addRecent(path: String) {
        if (path.isBlank()) return
        val current = recents.toMutableList()
        current.removeAll { it == path }
        current.add(0, path)
        if (current.size > MAX_RECENTS) {
            current.subList(MAX_RECENTS, current.size).clear()
        }
        saveRecents(current)
    }

    fun clearRecents() = saveRecents(emptyList())

    val recents: List<String>
        get() {
            val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                Gson().fromJson(raw, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun saveRecents(list: List<String>) {
        val raw = Gson().toJson(list)
        prefs.edit().putString(KEY_RECENTS, raw).apply()
    }

    /** Resolve a list of recent paths to actual files, dropping any that no longer exist. */
    fun validRecents(): List<File> =
        recents.map { File(it) }.filter { it.exists() }

    // ============ Types ============

    enum class ThemeMode(val key: String, val nightMode: Int, val label: String) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "System default"),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, "Light"),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, "Dark");

        companion object {
            fun fromKey(key: String?): ThemeMode = values().firstOrNull { it.key == key } ?: SYSTEM
        }
    }

    enum class SortPref(val key: String, val label: String) {
        NAME("name", "Name"),
        DATE("date", "Date"),
        SIZE("size", "Size");

        companion object {
            fun fromKey(key: String?): SortPref = values().firstOrNull { it.key == key } ?: NAME
        }
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_SORT = "default_sort"
        private const val KEY_HIDDEN = "show_hidden_default"
        private const val KEY_SERVER = "server_enabled"
        private const val KEY_RECENTS = "recents_json"
        private const val KEY_STORAGE_COLLAPSED = "storage_card_collapsed"
        private const val KEY_ROOT_MODE = "root_mode"
        private const val KEY_ROOT_DIALOG_SHOWN = "root_dialog_shown"
        private const val KEY_ANIMATIONS = "animations_enabled"
        const val MAX_RECENTS = 20
    }
}
