package com.example.fm

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/** Compress: .tar.zst only. Extract: zstd, zip, 7z, tar, gz, xz, bz2. */
object Archives {
    private val exts = listOf(
        ".tar.zst", ".tzst", ".zst", ".zip", ".apk", ".jar", ".7z", ".tar",
        ".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.bz2", ".tbz2",
    )

    /** File name without its archive extension (a.tar.gz -> a). */
    fun baseName(name: String): String {
        val l = name.lowercase()
        val e = exts.filter { l.endsWith(it) }.maxByOrNull { it.length } ?: return name.substringBeforeLast('.')
        return name.dropLast(e.length).ifEmpty { name }
    }

    fun isArchive(name: String) = exts.any { name.lowercase().endsWith(it) }

    /** All [srcs] (same parent folder) go into one .tar.zst; returns the file written. */
    fun compress(srcs: List<File>, level: Int = 3): File {
        val base = srcs.first().parentFile ?: File("/")
        val stem = if (srcs.size == 1) srcs[0].name else base.name.ifEmpty { "archive" }
        var out = File(base, "$stem.tar.zst")
        var n = 1
        while (out.exists()) out = File(base, "$stem (${n++}).tar.zst")
        try {
            val zs = ZstdOutputStream(FileOutputStream(out).buffered(256 * 1024), level)
            // multithreaded compression when the native lib supports it
            runCatching { zs.setWorkers(Runtime.getRuntime().availableProcessors().coerceIn(1, 8)) }
            TarArchiveOutputStream(zs).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                for (src in srcs) src.walkTopDown().forEach { f ->
                    Work.check()
                    tar.putArchiveEntry(TarArchiveEntry(f, f.relativeTo(base).path))
                    if (f.isFile) f.inputStream().use { Work.copy(it, tar) }
                    tar.closeArchiveEntry()
                }
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        return out
    }

    fun extract(archive: File, dest: File) {
        dest.mkdirs()
        val n = archive.name.lowercase()
        when {
            n.endsWith(".7z") -> SevenZFile(archive).use { z ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val e = z.nextEntry ?: break
                    val t = safe(dest, e.name)
                    if (e.isDirectory) t.mkdirs() else {
                        t.parentFile?.mkdirs()
                        t.outputStream().use { o ->
                            while (true) { Work.check(); val r = z.read(buf); if (r < 0) break; o.write(buf, 0, r) }
                        }
                    }
                }
            }
            n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar") -> unzip(archive, dest)
            else -> {
                val raw = BufferedInputStream(FileInputStream(archive), 256 * 1024)
                val dec: InputStream = when {
                    n.endsWith(".zst") || n.endsWith(".tzst") -> ZstdInputStream(raw)
                    n.endsWith(".gz") || n.endsWith(".tgz") -> GzipCompressorInputStream(raw)
                    n.endsWith(".xz") || n.endsWith(".txz") -> XZCompressorInputStream(raw)
                    n.endsWith(".bz2") || n.endsWith(".tbz2") -> BZip2CompressorInputStream(raw)
                    else -> raw
                }
                val isTar = n.endsWith(".tar") || n.contains(".tar.") || listOf(".tgz", ".tzst", ".txz", ".tbz2").any { n.endsWith(it) }
                dec.use { s ->
                    if (isTar) TarArchiveInputStream(s).use { copyEntries(it, dest) }
                    else File(dest, archive.name.substringBeforeLast('.')).outputStream().use { Work.copy(s, it) }
                }
            }
        }
    }

    /** Random-access zip (central directory); files are written by a small thread pool. */
    fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        ZipFile(zip).use { z ->
            val files = ArrayList<Pair<java.util.zip.ZipEntry, File>>()
            val made = HashSet<File>()
            for (e in z.entries()) {
                Work.check()
                val t = safe(dest, e.name)
                if (e.isDirectory) t.mkdirs() else { t.parentFile?.let { if (made.add(it)) it.mkdirs() }; files += e to t }
            }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            try {
                files.map { (e, t) ->
                    pool.submit {
                        Work.check()
                        z.getInputStream(e).use { i -> t.outputStream().use { o -> Work.copy(i, o) } }
                    }
                }.forEach { it.get() }
            } catch (ex: java.util.concurrent.ExecutionException) {
                throw ex.cause ?: ex
            } finally {
                pool.shutdownNow()
            }
        }
    }

    private fun copyEntries(ais: ArchiveInputStream<*>, dest: File) {
        while (true) {
            Work.check()
            val e = ais.nextEntry ?: break
            val t = safe(dest, e.name)
            if (e.isDirectory) t.mkdirs() else {
                t.parentFile?.mkdirs()
                t.outputStream().use { Work.copy(ais, it) }
            }
        }
    }

    /** Zip-slip guard. */
    private fun safe(dest: File, name: String): File {
        // pure path math (no syscalls per entry)
        val root = dest.absoluteFile.toPath().normalize()
        val t = root.resolve(name.trimStart('/')).normalize()
        require(t.startsWith(root)) { "Đường dẫn không hợp lệ: $name" }
        return t.toFile()
    }
}
