package com.example.fm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** File properties, checksums, EXIF text. */
object Props {
    fun mime(name: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /** Total bytes and file count below [dir]; stops early when [active] turns false. */
    fun dirStats(dir: File, active: () -> Boolean): Pair<Long, Int> {
        var size = 0L
        var count = 0
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty() && active()) {
            val d = stack.removeLast()
            val list = d.listFiles() ?: continue
            for (f in list) {
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) stack.add(f) else { size += f.length(); count++ }
            }
        }
        return size to count
    }

    /** algo: MD5, SHA-1, SHA-256 or CRC32. Streams the file; cancellable. */
    fun hash(f: File, algo: String, active: () -> Boolean, onProgress: (Float) -> Unit): String {
        val total = f.length().coerceAtLeast(1L)
        val md = if (algo == "CRC32") null else MessageDigest.getInstance(algo)
        val crc = java.util.zip.CRC32()
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastPct = -1
        f.inputStream().use { i ->
            while (active()) {
                val r = i.read(buf)
                if (r < 0) break
                if (md != null) md.update(buf, 0, r) else crc.update(buf, 0, r)
                done += r
                val pct = (done * 100 / total).toInt()
                if (pct != lastPct) { lastPct = pct; onProgress(done.toFloat() / total) }
            }
        }
        if (!active()) throw java.util.concurrent.CancellationException("cancelled")
        return if (md != null) md.digest().joinToString("") { "%02x".format(it) } else "%08x".format(crc.value)
    }

    @Suppress("DEPRECATION")
    fun exif(path: String): String = runCatching {
        val e = android.media.ExifInterface(path)
        val tags = listOf(
            "Hãng" to android.media.ExifInterface.TAG_MAKE,
            "Máy" to android.media.ExifInterface.TAG_MODEL,
            "Phần mềm" to android.media.ExifInterface.TAG_SOFTWARE,
            "Ngày chụp" to android.media.ExifInterface.TAG_DATETIME,
            "Rộng" to android.media.ExifInterface.TAG_IMAGE_WIDTH,
            "Cao" to android.media.ExifInterface.TAG_IMAGE_LENGTH,
            "Phơi sáng" to android.media.ExifInterface.TAG_EXPOSURE_TIME,
            "Khẩu độ" to android.media.ExifInterface.TAG_F_NUMBER,
            "ISO" to "ISOSpeedRatings",
            "Tiêu cự" to android.media.ExifInterface.TAG_FOCAL_LENGTH,
            "Flash" to android.media.ExifInterface.TAG_FLASH,
            "Xoay" to android.media.ExifInterface.TAG_ORIENTATION,
        )
        buildString {
            for ((label, tag) in tags) e.getAttribute(tag)?.let { append(label).append(": ").append(it).append('\n') }
            val ll = FloatArray(2)
            if (e.getLatLong(ll)) append("GPS: ${ll[0]}, ${ll[1]}\n")
        }.trimEnd().ifBlank { "Không có dữ liệu EXIF." }
    }.getOrElse { "Không đọc được EXIF." }
}

/** APK info and install. */
object ApkTools {
    @Suppress("DEPRECATION")
    fun info(ctx: Context, path: String): String {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val pi = pm.getPackageArchiveInfo(path, flags) ?: error("Không đọc được APK")
        val ai = pi.applicationInfo
        ai?.sourceDir = path
        ai?.publicSourceDir = path
        val sb = StringBuilder()
        sb.append("Tên: ").append(ai?.loadLabel(pm) ?: "?").append('\n')
        sb.append("Gói: ").append(pi.packageName).append('\n')
        sb.append("Phiên bản: ").append(pi.versionName).append(" (").append(PackageInfoCompat.getLongVersionCode(pi)).append(")\n")
        if (ai != null) sb.append("SDK: min ").append(ai.minSdkVersion).append(", target ").append(ai.targetSdkVersion).append('\n')
        val installed = runCatching { pm.getPackageInfo(pi.packageName, 0) }.getOrNull()
        if (installed != null) {
            sb.append("Đã cài: ").append(installed.versionName)
                .append(" (").append(PackageInfoCompat.getLongVersionCode(installed)).append(")\n")
        }
        val sig: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo
            val arr = if (si == null) null else if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            arr?.firstOrNull()?.toByteArray()
        } else pi.signatures?.firstOrNull()?.toByteArray()
        if (sig != null) {
            sb.append("Chữ ký SHA-256:\n")
                .append(MessageDigest.getInstance("SHA-256").digest(sig).joinToString(":") { "%02X".format(it) }).append('\n')
        }
        val perms = pi.requestedPermissions
        if (perms != null && perms.isNotEmpty()) {
            sb.append("\nQuyền (").append(perms.size).append("):\n")
            perms.sorted().forEach { sb.append("  ").append(it.removePrefix("android.permission.")).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    fun install(ctx: Context, path: String) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            error("Hãy cho phép cài ứng dụng từ nguồn này, rồi thử lại.")
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(path))
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}

/** Rewrites a .zip with extra files/folders (same-named entries are replaced). */
object ZipEdit {
    fun add(zip: File, srcs: List<File>) {
        val added = LinkedHashMap<String, File>()
        for (s in srcs) {
            val base = s.parentFile ?: File("/")
            s.walkTopDown().forEach { f ->
                if (f.canonicalFile == zip.canonicalFile) return@forEach
                val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                added[if (f.isDirectory) "$rel/" else rel] = f
            }
        }
        val tmp = File(zip.parentFile, ".${zip.name}.tmp")
        try {
            ZipFile(zip).use { z ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp), 256 * 1024)).use { out ->
                    for (e in z.entries()) {
                        Work.check()
                        if (e.name in added) continue
                        val ne = ZipEntry(e.name)
                        ne.time = e.time
                        if (e.method == ZipEntry.STORED) {
                            ne.method = ZipEntry.STORED
                            ne.size = e.size
                            ne.compressedSize = e.size
                            ne.crc = e.crc
                        }
                        out.putNextEntry(ne)
                        if (!e.isDirectory) z.getInputStream(e).use { Work.copy(it, out) }
                        out.closeEntry()
                    }
                    for ((name, f) in added) {
                        Work.check()
                        val ne = ZipEntry(name)
                        ne.time = f.lastModified()
                        out.putNextEntry(ne)
                        if (f.isFile) f.inputStream().use { Work.copy(it, out) }
                        out.closeEntry()
                    }
                }
            }
            java.nio.file.Files.move(tmp.toPath(), zip.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable { copyText(ctx, value) }.padding(vertical = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(
            value, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

/** Properties of one file/folder: info, checksums (+ verify a pasted hash), root permissions. */
@Composable
fun PropertiesDialog(item: FItem, rooted: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val file = remember(item.path) { File(item.path) }
    val scope = rememberCoroutineScope()
    var size by remember { mutableLongStateOf(if (item.isDir) -1L else item.size) }
    var count by remember { mutableIntStateOf(0) }
    var perm by remember { mutableStateOf<String?>(null) }
    var modeText by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val hashes = remember { mutableStateMapOf<String, String>() }
    var running by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var verify by remember { mutableStateOf("") }

    LaunchedEffect(item.path) {
        if (item.isDir) {
            val r = withContext(Dispatchers.IO) { Props.dirStats(file) { isActive } }
            size = r.first
            count = r.second
        }
        perm = withContext(Dispatchers.IO) { Fs.statPerm(item.path) }
        modeText = perm?.substringBefore(' ') ?: ""
    }

    fun compute(algo: String) {
        if (running != null) return
        running = algo
        progress = 0f
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) { Props.hash(file, algo, { isActive }) { progress = it } }
            }
            r.onSuccess { hashes[algo] = it }
                .onFailure { if (it !is java.util.concurrent.CancellationException) hashes[algo] = "Lỗi: ${it.message}" }
            running = null
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(item.name, maxLines = 2) },
        confirmButton = { TextButton(onClick = onClose) { Text("Đóng") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoRow("Đường dẫn", item.path)
                InfoRow("Loại", if (item.isDir) (if (item.isRepo) "Thư mục (Git repo)" else "Thư mục") else Props.mime(item.name) ?: "Không rõ")
                InfoRow(
                    "Kích thước",
                    when {
                        size < 0 -> "Đang tính…"
                        item.isDir -> Formatter.formatFileSize(ctx, size) + " · $count tệp"
                        else -> Formatter.formatFileSize(ctx, size) + " ($size byte)"
                    },
                )
                InfoRow("Sửa đổi", DateFormat.format("dd/MM/yyyy HH:mm:ss", item.modified).toString())
                InfoRow(
                    "Quyền",
                    perm ?: ((if (file.canRead()) "r" else "-") + (if (file.canWrite()) "w" else "-") + (if (file.canExecute()) "x" else "-")),
                )

                if (rooted && perm != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            modeText, { modeText = it.filter { c -> c in '0'..'7' }.take(4) }, Modifier.weight(1f),
                            label = { Text("chmod") }, singleLine = true,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Fs.chmod(item.path, modeText, recursive) }
                                note = if (ok) "Đã đổi quyền" else "Đổi quyền thất bại"
                                perm = withContext(Dispatchers.IO) { Fs.statPerm(item.path) }
                            }
                        }) { Text("Áp dụng") }
                    }
                    if (item.isDir) Row(
                        Modifier.clickable { recursive = !recursive }, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(recursive, { recursive = it })
                        Text("Áp dụng cho cả thư mục con")
                    }
                    note?.let { Text(it, color = cs.primary, fontSize = 13.sp) }
                }

                if (!item.isDir) {
                    Text("Checksum", Modifier.padding(top = 12.dp), color = cs.primary, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf("MD5", "SHA-1", "SHA-256", "CRC32").forEach { a ->
                            TextButton(onClick = { compute(a) }, enabled = running == null) { Text(a, fontSize = 12.sp) }
                        }
                    }
                    if (running != null) {
                        Text("Đang tính ${running}…", fontSize = 13.sp, color = cs.onSurfaceVariant)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    hashes.forEach { (a, h) -> InfoRow(a, h, mono = true) }
                    OutlinedTextField(
                        verify, { verify = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Dán hash để so sánh") }, singleLine = true,
                    )
                    if (verify.isNotBlank() && hashes.isNotEmpty()) {
                        val ok = hashes.values.any { it.equals(verify.trim(), ignoreCase = true) }
                        Text(
                            if (ok) "✓ Khớp" else "✗ Không khớp (với các hash đã tính)",
                            color = if (ok) cs.primary else cs.error, fontSize = 14.sp,
                        )
                    }
                }
            }
        },
    )
}
