package com.example.fm

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.progress.ProgressMonitor
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.tukaani.xz.LZMA2Options
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

/** The archive needs a password ([wrong] = a password was given but did not work). */
class PasswordNeeded(val wrong: Boolean) : Exception(if (wrong) "Sai m\u1eadt kh\u1ea9u" else "C\u1ea7n m\u1eadt kh\u1ea9u")

/** Output formats; only zip can be encrypted (AES-256). */
enum class ArcFormat(val label: String, val ext: String, val canPassword: Boolean) {
    TAR_ZST("tar.zst", ".tar.zst", false),
    ZIP("zip", ".zip", true),
    SEVEN_Z("7z", ".7z", false),
    TAR_GZ("tar.gz", ".tar.gz", false),
    TAR_XZ("tar.xz", ".tar.xz", false),
}

enum class ArcLevel(val label: String) { FAST("Nhanh"), NORMAL("Th\u01b0\u1eddng"), MAX("T\u1ed1i \u0111a") }

/** Compress: tar.zst, zip (optional AES password), 7z, tar.gz, tar.xz. Extract: zstd, zip, 7z, tar, gz, xz, bz2. */
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

    /** All [srcs] (same parent folder) go into one archive; returns the file written. */
    fun compress(
        srcs: List<File>, fmt: ArcFormat = ArcFormat.TAR_ZST, level: ArcLevel = ArcLevel.NORMAL, password: CharArray? = null,
    ): File {
        val base = srcs.first().parentFile ?: File("/")
        val stem = if (srcs.size == 1) srcs[0].name else base.name.ifEmpty { "archive" }
        var out = File(base, "$stem${fmt.ext}")
        var n = 1
        while (out.exists()) out = File(base, "$stem (${n++})${fmt.ext}")
        try {
            when (fmt) {
                ArcFormat.ZIP -> writeZip(srcs, base, out, level, password)
                ArcFormat.SEVEN_Z -> write7z(srcs, base, out, level)
                else -> writeTar(srcs, base, out, fmt, level)
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        return out
    }

    // xz/lzma presets stay at or below 6: preset 9 needs ~670 MB of heap, too much for a phone
    private fun xzPreset(l: ArcLevel) = when (l) { ArcLevel.FAST -> 1; ArcLevel.NORMAL -> 4; ArcLevel.MAX -> 6 }

    private fun writeTar(srcs: List<File>, base: File, out: File, fmt: ArcFormat, level: ArcLevel) {
        val fos = FileOutputStream(out).buffered(256 * 1024)
        val comp: java.io.OutputStream = when (fmt) {
            ArcFormat.TAR_GZ -> GzipCompressorOutputStream(fos, GzipParameters().also {
                it.setCompressionLevel(when (level) { ArcLevel.FAST -> 1; ArcLevel.NORMAL -> 6; ArcLevel.MAX -> 9 })
            })
            ArcFormat.TAR_XZ -> XZCompressorOutputStream(fos, xzPreset(level))
            else -> {
                val zl = when (level) { ArcLevel.FAST -> 1; ArcLevel.NORMAL -> 3; ArcLevel.MAX -> 19 }
                val zs = ZstdOutputStream(fos, zl)
                // multithreaded compression when the native lib supports it (fewer workers at max level: memory)
                val w = Runtime.getRuntime().availableProcessors().coerceIn(1, if (level == ArcLevel.MAX) 4 else 8)
                runCatching { zs.setWorkers(w) }
                zs
            }
        }
        TarArchiveOutputStream(comp).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            for (src in srcs) src.walkTopDown().forEach { f ->
                Work.check()
                tar.putArchiveEntry(TarArchiveEntry(f, f.relativeTo(base).path))
                if (f.isFile) f.inputStream().use { Work.copy(it, tar) }
                tar.closeArchiveEntry()
            }
        }
    }

    private fun write7z(srcs: List<File>, base: File, out: File, level: ArcLevel) {
        SevenZOutputFile(out).use { z ->
            z.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(xzPreset(level)))))
            val buf = ByteArray(256 * 1024)
            for (src in srcs) src.walkTopDown().forEach { f ->
                Work.check()
                z.putArchiveEntry(z.createArchiveEntry(f, f.relativeTo(base).path))
                if (f.isFile) f.inputStream().use { i ->
                    while (true) { Work.check(); val r = i.read(buf); if (r < 0) break; z.write(buf, 0, r) }
                }
                z.closeArchiveEntry()
            }
            z.finish()
        }
    }

    private fun writeZip(srcs: List<File>, base: File, out: File, level: ArcLevel, password: CharArray?) {
        val enc = password != null && password.isNotEmpty()
        val zf = if (enc) net.lingala.zip4j.ZipFile(out, password) else net.lingala.zip4j.ZipFile(out)
        try {
            val p = ZipParameters()
            p.setCompressionMethod(CompressionMethod.DEFLATE)
            p.setCompressionLevel(when (level) {
                ArcLevel.FAST -> CompressionLevel.FASTEST
                ArcLevel.NORMAL -> CompressionLevel.NORMAL
                ArcLevel.MAX -> CompressionLevel.MAXIMUM
            })
            if (enc) {
                p.setEncryptFiles(true)
                p.setEncryptionMethod(EncryptionMethod.AES)
                p.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
            }
            zf.setRunInThread(true)
            for (src in srcs) {
                Work.check()
                if (src.isDirectory) zf.addFolder(src, p)
                else zf.addFile(src, ZipParameters(p).also { it.setFileNameInZip(src.name) })
                awaitZip(zf)
            }
        } finally {
            zf.close()
        }
    }

    /** Waits for a zip4j background task, forwarding Back (cancel) and errors. */
    private fun awaitZip(zf: net.lingala.zip4j.ZipFile) {
        val pm = zf.getProgressMonitor()
        while (pm.getState() == ProgressMonitor.State.BUSY) {
            if (Work.cancelled) pm.setCancelAllTasks(true)
            Thread.sleep(50)
        }
        when (pm.getResult()) {
            ProgressMonitor.Result.ERROR -> throw pm.getException() ?: java.io.IOException("zip error")
            ProgressMonitor.Result.CANCELLED -> throw java.util.concurrent.CancellationException("cancelled")
            else -> {}
        }
    }

    /** zip4j reads both plain and encrypted (ZipCrypto / AES) zips. */
    private fun unzipWithPassword(zip: File, dest: File, password: CharArray) {
        val zf = net.lingala.zip4j.ZipFile(zip, password)
        try {
            zf.setRunInThread(true)
            zf.extractAll(dest.path)
            try {
                awaitZip(zf)
            } catch (e: net.lingala.zip4j.exception.ZipException) {
                if (e.getType() == net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD) throw PasswordNeeded(true)
                throw e
            }
        } finally {
            zf.close()
        }
    }

    fun extract(archive: File, dest: File, password: CharArray? = null) {
        dest.mkdirs()
        val n = archive.name.lowercase()
        when {
            n.endsWith(".7z") -> extract7z(archive, dest, password)
            n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar") -> {
                if (password != null) unzipWithPassword(archive, dest, password)
                else try {
                    unzip(archive, dest)
                } catch (e: java.util.zip.ZipException) {
                    // java.util.zip / Android's ZipFile reject encrypted entries (message differs per runtime)
                    val enc = runCatching { net.lingala.zip4j.ZipFile(archive).use { it.isEncrypted() } }.getOrDefault(false)
                    if (enc) throw PasswordNeeded(false)
                    throw e
                }
            }
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

    private fun extract7z(archive: File, dest: File, password: CharArray?) {
        try {
            @Suppress("DEPRECATION")
            val sz = if (password != null) SevenZFile(archive, password) else SevenZFile(archive)
            sz.use { z ->
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
        } catch (e: PasswordRequiredException) {
            throw PasswordNeeded(false)
        } catch (e: java.io.IOException) {
            // wrong key -> garbage after decryption -> decoder/CRC errors
            if (password != null) throw PasswordNeeded(true)
            throw e
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
