package com.example.fm

import android.content.Context
import android.os.Environment

/** Shortcuts (defaults: / if root, sdcard, Download). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fm", Context.MODE_PRIVATE)

    fun favorites(): List<String> {
        val saved = sp.getString("favs", null)
        if (saved != null) {
            val l = saved.split("\n").filter { it.isNotBlank() }
            // one-time: add "/" once root is available
            if (!sp.getBoolean("root_fav", false) && Fs.hasRoot()) {
                sp.edit().putBoolean("root_fav", true).apply()
                if ("/" !in l) return (listOf("/") + l).also { save(it) }
            }
            return l
        }
        val sd = Environment.getExternalStorageDirectory().path
        val defaults = buildList {
            if (Fs.hasRoot()) add("/")
            add(sd)
            add("$sd/Download")
        }
        save(defaults)
        return defaults
    }

    fun isDefault(path: String): Boolean {
        val sd = Environment.getExternalStorageDirectory().path
        return path == "/" || path == sd || path == "$sd/Download"
    }

    fun save(list: List<String>) = sp.edit().putString("favs", list.joinToString("\n")).apply()
}
