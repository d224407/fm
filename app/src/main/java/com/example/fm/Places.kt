package com.example.fm

import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.Locale

/** Names and storage usage for favorite folders. */
object Places {
    val sd: String get() = Environment.getExternalStorageDirectory().path

    fun isStorage(path: String) = path == "/" || path == sd

    private val storageRoot = Regex("/storage/emulated/\\d+|/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}|/data/media/\\d+")
    private val pkgName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** Root of a real shared-storage volume (not a folder inside an extracted archive). */
    fun isStorageRoot(p: String) = p == sd || p == "/sdcard" || storageRoot.matches(p)

    /** Package name for Android/{data,obb,media}/<pkg> on a storage volume, else null. */
    fun pkgOf(path: String): String? {
        val f = File(path)
        val t = f.parentFile ?: return null
        val a = t.parentFile ?: return null
        if (a.name != "Android" || t.name !in setOf("data", "obb", "media")) return null
        if (!isStorageRoot(a.parent ?: return null)) return null
        return f.name.takeIf { pkgName.matches(it) }
    }

    fun title(path: String): String = when (path) {
        "/" -> "Thư mục gốc"
        sd -> "Bộ nhớ thiết bị"
        "$sd/Download" -> "Tải về"
        "$sd/Documents" -> "Tài liệu"
        "$sd/DCIM" -> "Camera"
        "$sd/Pictures" -> "Ảnh"
        else -> File(path).name.ifEmpty { path }
    }

    /** "used/\ntotal" for storage roots, null otherwise. */
    fun usage(path: String): String? {
        if (!isStorage(path)) return null
        return try {
            val s = StatFs(path)
            val total = s.blockCountLong * s.blockSizeLong
            val used = total - s.availableBlocksLong * s.blockSizeLong
            val gb = 1024.0 * 1024 * 1024
            String.format(Locale.US, "%.2fGB/\n%.2fGB", used / gb, total / gb)
        } catch (_: Throwable) {
            null
        }
    }
}
