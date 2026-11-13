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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    class Summary(
        val label: String, val pkg: String, val versionName: String, val versionCode: Long,
        val minSdk: Int, val targetSdk: Int, val installed: String?, val cert: String?, val perms: List<String>,
    )

    @Suppress("DEPRECATION")
    fun summary(ctx: Context, path: String): Summary {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
            (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val pi = pm.getPackageArchiveInfo(path, flags) ?: error("Không đọc được APK")
        val ai = pi.applicationInfo
        ai?.sourceDir = path
        ai?.publicSourceDir = path
        val code = PackageInfoCompat.getLongVersionCode(pi)
        val inst = runCatching { pm.getPackageInfo(pi.packageName, 0) }.getOrNull()
        val installed = inst?.let {
            val ic = PackageInfoCompat.getLongVersionCode(it)
            "${it.versionName} ($ic)" + when { code < ic -> " · hạ cấp"; code > ic -> " · nâng cấp"; else -> " · cùng bản" }
        }
        val sig: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo
            val arr = if (si == null) null else if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            arr?.firstOrNull()?.toByteArray()
        } else pi.signatures?.firstOrNull()?.toByteArray()
        val cert = sig?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString(":") { b -> "%02X".format(b) } }
        return Summary(
            label = ai?.loadLabel(pm)?.toString() ?: "?", pkg = pi.packageName, versionName = pi.versionName ?: "",
            versionCode = code, minSdk = ai?.minSdkVersion ?: 0, targetSdk = ai?.targetSdkVersion ?: 0,
            installed = installed, cert = cert,
            perms = (pi.requestedPermissions?.toList() ?: emptyList()).map { it.removePrefix("android.permission.") }.sorted(),
        )
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

/** Properties in tabs: [APK] · Thông tin · Băm · Quyền (like MP Manager's dialogs). */
@Composable
fun PropertiesDialog(item: FItem, vm: FmViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val file = remember(item.path) { File(item.path) }
    val scope = rememberCoroutineScope()
    val isApk = !item.isDir && item.name.lowercase().endsWith(".apk")
    val tabs = remember(item.path) {
        buildList { if (isApk) add("APK"); add("Thông tin"); if (!item.isDir) add("Băm"); add("Quyền") }
    }
    var tab by remember { mutableIntStateOf(0) }
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
    var apk by remember { mutableStateOf<ApkTools.Summary?>(null) }
    var apkError by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableIntStateOf(-1) }
    var icon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var permsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(item.path) {
        if (item.isDir) {
            val r = withContext(Dispatchers.IO) { Props.dirStats(file) { isActive } }
            size = r.first
            count = r.second
        }
        perm = withContext(Dispatchers.IO) { Fs.statPerm(item.path) }
        modeText = perm?.substringBefore(' ') ?: ""
    }
    LaunchedEffect(item.path) {
        if (isApk) {
            runCatching { withContext(Dispatchers.IO) { ApkTools.summary(ctx, item.path) } }
                .onSuccess { apk = it }.onFailure { apkError = it.message ?: it.javaClass.simpleName }
            entries = withContext(Dispatchers.IO) { runCatching { ZipFile(file).use { it.size() } }.getOrDefault(-1) }
            icon = withContext(Dispatchers.IO) { Thumbs.load(ctx, item, 96) }
        }
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

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(max = 620.dp),
            shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    val bmp = icon
                    if (bmp != null) {
                        androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.size(56.dp))
                    } else {
                        Icon(
                            if (item.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile, null,
                            Modifier.size(48.dp), tint = cs.primary,
                        )
                    }
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(
                            apk?.label?.takeIf { it.isNotBlank() } ?: item.name, fontSize = 20.sp,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        val sub = apk?.versionName?.takeIf { it.isNotBlank() }
                            ?: if (item.isDir) (if (item.isRepo) "Thư mục (Git repo)" else "Thư mục") else Props.mime(item.name)
                        if (sub != null) Text(sub, color = cs.onSurfaceVariant, fontSize = 14.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(12.dp))
                PrimaryTabRow(selectedTabIndex = tab, containerColor = cs.surfaceContainerHigh, contentColor = cs.primary) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1, fontSize = 14.sp) })
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    when (tabs[tab]) {
                        "APK" -> {
                            val a = apk
                            when {
                                apkError != null -> Text("Không đọc được APK: $apkError", color = cs.error)
                                a == null -> Text("Đang đọc…", color = cs.onSurfaceVariant)
                                else -> {
                                    InfoRow("Tên gói", a.pkg)
                                    InfoRow("Mã phiên bản", a.versionCode.toString())
                                    InfoRow(
                                        "Kích thước",
                                        Formatter.formatFileSize(ctx, item.size) + if (entries >= 0) " · $entries mục" else "",
                                    )
                                    InfoRow("SDK đích", a.targetSdk.toString())
                                    InfoRow("SDK tối thiểu", a.minSdk.toString())
                                    a.cert?.let { InfoRow("Chứng chỉ SHA-256", it, mono = true) }
                                    InfoRow("Đã cài", a.installed ?: "Chưa cài")
                                    Row(
                                        Modifier.fillMaxWidth().clickable { permsOpen = !permsOpen }.padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("Quyền (${a.perms.size})", Modifier.weight(1f), fontSize = 15.sp)
                                        Icon(if (permsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                                    }
                                    if (permsOpen) a.perms.forEach {
                                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        "Thông tin" -> {
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
                        }
                        "Băm" -> {
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
                        else -> { // Quyền
                            InfoRow(
                                "Quyền",
                                perm ?: ((if (file.canRead()) "r" else "-") + (if (file.canWrite()) "w" else "-") + (if (file.canExecute()) "x" else "-")),
                            )
                            if (vm.rooted && perm != null) {
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
                            } else {
                                Text("Cần root để đổi quyền.", color = cs.onSurfaceVariant, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    if (isApk && tabs[tab] == "APK") {
                        TextButton(onClick = { onClose(); vm.startQuickEdit(item) }) { Text("Sửa nhanh") }
                        TextButton(onClick = { onClose(); vm.installApk(item) }) { Text("Cài đặt") }
                    }
                    TextButton(onClick = onClose) { Text("Đóng") }
                }
            }
        }
    }
}

/** Quick edit: change the main manifest attributes; blank / unchanged fields stay as they are. */
@Composable
fun QuickEditDialog(info: ApkOps.Info, onDismiss: () -> Unit, onApply: (ApkOps.Info) -> Unit) {
    var pkg by remember { mutableStateOf(info.pkg ?: "") }
    var label by remember { mutableStateOf(info.label ?: "") }
    var vName by remember { mutableStateOf(info.versionName ?: "") }
    var vCode by remember { mutableStateOf(info.versionCode?.toString() ?: "") }
    var minSdk by remember { mutableStateOf(info.minSdk?.toString() ?: "") }
    var tgtSdk by remember { mutableStateOf(info.targetSdk?.toString() ?: "") }
    fun num(s: String) = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sửa nhanh thuộc tính") },
        confirmButton = {
            TextButton(onClick = {
                val n = ApkOps.Info()
                if (pkg.isNotBlank() && pkg != info.pkg) n.pkg = pkg.trim()
                if (label.isNotBlank() && label != info.label) n.label = label
                if (vName.isNotBlank() && vName != info.versionName) n.versionName = vName
                vCode.toIntOrNull()?.let { if (it != info.versionCode) n.versionCode = it }
                minSdk.toIntOrNull()?.let { if (it != info.minSdk) n.minSdk = it }
                tgtSdk.toIntOrNull()?.let { if (it != info.targetSdk) n.targetSdk = it }
                onApply(n)
            }) { Text("Lưu thành APK mới") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(pkg, { pkg = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Tên gói") })
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Tên ứng dụng") })
                OutlinedTextField(vCode, { vCode = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Mã phiên bản") }, keyboardOptions = num(vCode))
                OutlinedTextField(vName, { vName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Tên phiên bản") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minSdk, { minSdk = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), singleLine = true, label = { Text("SDK tối thiểu") }, keyboardOptions = num(minSdk))
                    OutlinedTextField(tgtSdk, { tgtSdk = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), singleLine = true, label = { Text("SDK đích") }, keyboardOptions = num(tgtSdk))
                }
                Text(
                    "Kết quả được ký bằng khóa của app này (khác chữ ký gốc) và lưu thành file *_edited.apk cạnh file gốc.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
