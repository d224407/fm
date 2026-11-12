package com.example.fm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/** Thumbnails for image, video, apk (app icon) and pdf (first page), cached in memory. */
object Thumbs {
    val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()

    private val videoExt = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private fun kind(name: String): String? {
        val e = name.substringAfterLast('.', "").lowercase()
        return when {
            e in FmViewModel.imageExt -> "img"
            e in videoExt -> "vid"
            e == "apk" -> "apk"
            e == "pdf" -> "pdf"
            else -> null
        }
    }

    fun supports(name: String) = kind(name) != null

    private fun key(i: FItem) = "${i.path}|${i.size}|${i.modified}"

    fun peek(i: FItem): ImageBitmap? = cache.get(key(i))?.asImageBitmap()

    /** Blocking; call off the main thread. */
    fun load(ctx: Context, i: FItem, px: Int): Bitmap? {
        val k = key(i)
        cache.get(k)?.let { return it }
        if (k in failed) return null
        val bmp = try {
            when (kind(i.name)) {
                "img" -> image(i.path, px)
                "vid" -> video(i.path, px)
                "apk" -> apk(ctx, i.path, px)
                "pdf" -> pdf(i.path, px)
                else -> null
            }
        } catch (t: Throwable) {
            DLog.d("thumb failed ${i.name}: $t")
            null
        }
        if (bmp != null) cache.put(k, bmp) else failed.add(k)
        return bmp
    }

    private fun scale(b: Bitmap, px: Int): Bitmap {
        val r = px.toFloat() / maxOf(b.width, b.height)
        if (r >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * r).toInt().coerceAtLeast(1), (b.height * r).toInt().coerceAtLeast(1), true)
    }

    private fun image(path: String, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun video(path: String, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val f = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0) ?: return null
            return scale(f, px)
        } finally {
            runCatching { r.release() }
        }
    }

    private fun apk(ctx: Context, path: String, px: Int): Bitmap? {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(path, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = path
        ai.publicSourceDir = path
        val d = ai.loadIcon(pm)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        return bmp
    }

    private fun pdf(path: String, px: Int): Bitmap? {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val r = PdfRenderer(fd)
            try {
                if (r.pageCount == 0) return null
                val p = r.openPage(0)
                try {
                    val h = (px * p.height.toFloat() / p.width).toInt().coerceIn(1, px * 2)
                    val b = Bitmap.createBitmap(px, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return b
                } finally {
                    p.close()
                }
            } finally {
                r.close()
            }
        }
    }
}
