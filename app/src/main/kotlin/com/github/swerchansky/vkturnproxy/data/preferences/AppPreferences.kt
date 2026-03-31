package com.github.swerchansky.vkturnproxy.data.preferences

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferences @Inject constructor(context: Context) {

    private val prefs = context.getSharedPreferences("proxy_settings", Context.MODE_PRIVATE)

    var link: String
        get() = prefs.getString("link", "") ?: ""
        set(v) = prefs.edit { putString("link", v) }

    var peer: String
        get() = prefs.getString("peer", "") ?: ""
        set(v) = prefs.edit { putString("peer", v) }

    var listenPort: Int
        get() = readInt("port", 9000)
        set(v) = prefs.edit { putInt("port", v) }

    var nConnections: Int
        get() = readInt("connections", 0)
        set(v) = prefs.edit { putInt("connections", v) }

    var serverHistory: List<String>
        get() = prefs.getString("server_history", "")
            ?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = prefs.edit { putString("server_history", v.joinToString(",")) }

    var favorites: Map<String, String>
        get() = prefs.getString("favorites", "")
            ?.split("|")?.filter { "~" in it }
            ?.associate { it.substringBefore("~") to it.substringAfter("~") } ?: emptyMap()
        set(v) = prefs.edit {
            putString("favorites", v.entries.joinToString("|") { "${it.key}~${it.value}" })
        }

    var autoScroll: Boolean
        get() = prefs.getBoolean("auto_scroll", true)
        set(v) = prefs.edit { putBoolean("auto_scroll", v) }

    var saveLogs: Boolean
        get() = prefs.getBoolean("save_logs", false)
        set(v) = prefs.edit { putBoolean("save_logs", v) }

    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(v) = prefs.edit { putBoolean("notifications", v) }

    var themeMode: Int
        get() = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(v) = prefs.edit { putInt("theme_mode", v) }

    // Handles migration from old String-stored int values
    private fun readInt(key: String, default: Int): Int {
        return try {
            prefs.getInt(key, default)
        } catch (e: ClassCastException) {
            val v = prefs.getString(key, null)?.toIntOrNull() ?: default
            prefs.edit { putInt(key, v) }
            v
        }
    }

    fun addToServerHistory(address: String) {
        serverHistory = (listOf(address) + serverHistory.filter { it != address }).take(5)
    }
}
