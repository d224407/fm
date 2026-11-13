package com.example.fm

import android.app.Application
import android.content.Intent
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An archive opened as a virtual folder: [root] is the extracted copy of [file] in cache. */
data class ArchiveCtx(val file: String, val root: String)

/** Password prompt for an encrypted archive; [wrong] = the previous password failed. */
class PwdAsk(val label: String, val wrong: Boolean, val onOk: (CharArray) -> Unit)

class PaneState(start: String) {
    var path by mutableStateOf(start)
    var items by mutableStateOf<List<FItem>>(emptyList())
    var selected by mutableStateOf<Set<String>>(emptySet())
    var archive by mutableStateOf<ArchiveCtx?>(null)
    var gitRoot by mutableStateOf<String?>(null)
    val back = ArrayDeque<String>()
    val fwd = ArrayDeque<String>()
    var canBack by mutableStateOf(false)
    var canFwd by mutableStateOf(false)
}

/** Messages of a throwable and its causes, one per line. */
fun Throwable.chain(): String =
    generateSequence(this) { it.cause }.map { it.message ?: it.javaClass.simpleName }.distinct().joinToString("\n<- ")

/** Copy/cut buffer shared by both tabs. */
data class Clip(val items: List<FItem>, val cut: Boolean)

/** Folder chooser request (extract to…). */
class PickTarget(val start: String, val onPick: (String) -> Unit)

class FmViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val panes = List(2) { PaneState(Environment.getExternalStorageDirectory().path) }

    var favorites by mutableStateOf<List<String>>(emptyList()); private set
    var message by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var output by mutableStateOf<Pair<String, String>?>(null)
    var picker by mutableStateOf<PickTarget?>(null)
    var viewing by mutableStateOf<FItem?>(null)
    var rooted by mutableStateOf(false); private set
    var showLog by mutableStateOf(false)
    var clip by mutableStateOf<Clip?>(null)
    var editing by mutableStateOf<FItem?>(null)
    var propsItem by mutableStateOf<FItem?>(null)
    var batch by mutableStateOf<List<FItem>?>(null)
    var viewSiblings by mutableStateOf<List<FItem>>(emptyList())
    var search by mutableStateOf<SearchState?>(null)
    var quick by mutableStateOf<Pair<FItem, ApkOps.Info>?>(null)
    var pwdAsk by mutableStateOf<PwdAsk?>(null)
    var compressAsk by mutableStateOf<List<FItem>?>(null)
    private var searchJob: Job? = null
    private val cachePrefix = File(app.cacheDir, "archive_view").path

    init {
        viewModelScope.launch(Dispatchers.IO) {
            File(app.cacheDir, "archive_view").deleteRecursively()
            rooted = Fs.hasRoot()
            DLog.i("root=$rooted")
            favorites = prefs.favorites()
            panes.indices.forEach { refresh(it) }
        }
    }

    // ---- Listing / navigation ----

    fun refresh(i: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = panes[i]
            val path = p.path
            val list = runCatching { Fs.list(path) }
                .onFailure { DLog.e("list failed: $path", it) }.getOrDefault(emptyList())
            val root = GitTool.findRoot(path)?.path
            if (p.path != path) return@launch // user navigated away meanwhile: drop the stale result
            val paths = list.mapTo(HashSet(list.size)) { it.path }
            p.items = list
            p.selected = p.selected.filterTo(HashSet()) { it in paths }
            p.gitRoot = root
        }
    }

    fun navigate(i: Int, path: String, record: Boolean = true) {
        val p = panes[i]
        val a = p.archive
        if (record && a == null && p.path != path && !path.startsWith(cachePrefix) && !p.path.startsWith(cachePrefix)) {
            p.back.addLast(p.path)
            if (p.back.size > 100) p.back.removeFirst()
            p.fwd.clear()
        }
        if (a != null && path != a.root && !path.startsWith(a.root + "/")) p.archive = null
        p.selected = emptySet()
        p.path = path
        p.canBack = p.back.isNotEmpty()
        p.canFwd = p.fwd.isNotEmpty()
        DLog.d("navigate pane=$i path=$path")
        refresh(i)
    }

    fun goBack(i: Int) {
        val p = panes[i]
        val t = p.back.removeLastOrNull() ?: return
        p.fwd.addLast(p.path)
        navigate(i, t, record = false)
    }

    fun goForward(i: Int) {
        val p = panes[i]
        val t = p.fwd.removeLastOrNull() ?: return
        p.back.addLast(p.path)
        navigate(i, t, record = false)
    }

    /** Open this pane's folder in the other pane. */
    fun syncOther(i: Int) {
        val o = 1 - i
        if (panes[o].archive != null) panes[o].archive = null
        navigate(o, panes[i].path)
    }

    fun up(i: Int) {
        val p = panes[i]
        val a = p.archive
        if (a != null && p.path == a.root) {
            p.archive = null
            navigate(i, File(a.file).parent ?: "/")
            return
        }
        var parent = File(p.path).parent ?: return
        while (parent != "/" && !rooted && File(parent).list() == null) parent = File(parent).parent ?: break
        navigate(i, parent)
    }

    fun displayPath(i: Int): String {
        val p = panes[i]
        val a = p.archive ?: return p.path
        return a.file + "!" + p.path.removePrefix(a.root).ifEmpty { "/" }
    }

    fun tabTitle(i: Int): String {
        val p = panes[i]
        val a = p.archive
        return if (a != null) File(a.file).name else File(p.path).name.ifEmpty { "/" }
    }

    fun open(i: Int, item: FItem) {
        if (panes[i].selected.isNotEmpty()) return toggleSelect(i, item)
        if (item.isDir) return navigate(i, item.path)
        val ext = item.name.substringAfterLast('.', "").lowercase()
        when {
            Archives.isArchive(item.name) -> openArchive(i, item)
            ext in imageExt -> {
                viewSiblings = panes[i].items.filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in imageExt }
                viewing = item
            }
            ext in mediaExt -> { viewSiblings = emptyList(); viewing = item }
            ext in textExt -> editing = item
            else -> openExternal(item)
        }
    }

    private fun openExternal(item: FItem) {
        runCatching {
            val f = File(item.path)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            app.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure { DLog.e("open external failed", it); toast = "Không có ứng dụng mở file này." }
    }

    /** Extract the archive into cache and browse it like a folder. */
    fun openArchive(i: Int, item: FItem, pw: CharArray? = null): Unit = run {
        try {
            val f = File(item.path)
            val key = (item.path + f.lastModified()).hashCode().toUInt().toString(16)
            val dest = File(app.cacheDir, "archive_view/$key")
            val done = File(dest.parentFile, "$key.done")
            if (!done.exists()) {
                dest.deleteRecursively()
                try {
                    Archives.extract(f, dest, pw)
                    done.createNewFile()
                } catch (t: Throwable) {
                    dest.deleteRecursively()
                    throw t
                }
            }
            panes[i].archive = ArchiveCtx(item.path, dest.path)
            navigate(i, dest.path)
        } catch (e: PasswordNeeded) {
            askPassword(item.name, e.wrong) { openArchive(i, item, it) }
        }
    }

    private fun askPassword(label: String, wrong: Boolean, onOk: (CharArray) -> Unit) {
        pwdAsk = PwdAsk(label, wrong, onOk)
    }

    // ---- Selection ----

    fun selectedItems(i: Int): List<FItem> = panes[i].let { p -> p.items.filter { it.path in p.selected } }

    fun toggleSelect(i: Int, item: FItem) {
        val p = panes[i]
        p.selected = if (item.path in p.selected) p.selected - item.path else p.selected + item.path
    }

    fun selectAll(i: Int) { panes[i].selected = panes[i].items.map { it.path }.toSet() }
    fun clearSelection(i: Int) { panes[i].selected = emptySet() }

    // ---- Shortcuts ----

    fun isDefaultShortcut(path: String) = prefs.isDefault(path)

    fun toggleFavorite(path: String) {
        favorites = if (path in favorites) favorites - path else favorites + path
        prefs.save(favorites)
    }

    // ---- Actions ----

    /** Runs [block] off the main thread; only failures are reported to the user. */
    private fun run(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            Work.cancelled = false
            busy = true
            try {
                block()
            } catch (ex: java.util.concurrent.CancellationException) {
                DLog.i("cancelled")
            } catch (ex: Throwable) {
                DLog.e("action failed", ex)
                message = ex.chain()
            } finally {
                busy = false
                panes.indices.forEach { refresh(it) }
            }
        }
    }

    /** Back button: stop the running task. */
    fun cancel() { Work.cancelled = true }

    /** Like [run] but shows the returned text in a read-only dialog. */
    private fun show(title: String, block: () -> String) = run { output = title to block() }

    private fun check(fail: List<FItem>, what: String) {
        if (fail.isNotEmpty()) error("$what thất bại: ${fail.joinToString { it.name }}")
    }

    fun copy(i: Int, items: List<FItem>) { clip = Clip(items, false); clearSelection(i) }
    fun cut(i: Int, items: List<FItem>) { clip = Clip(items, true); clearSelection(i) }

    fun paste(i: Int) {
        val c = clip ?: return
        val dst = panes[i].path
        run {
            val fail = c.items.filterNot { if (c.cut) Fs.move(it.path, dst) else Fs.copy(it.path, dst) }
            if (c.cut || fail.isEmpty()) clip = null // the paste button goes away once done
            check(fail, "Dán")
        }
    }

    fun delete(items: List<FItem>) = run {
        val fail = items.filterNot { Fs.delete(it.path) }
        panes.indices.forEach { clearSelection(it) }
        check(fail, "Xóa")
    }

    /** A single path component: no separators, not "." / "..". */
    private fun validName(n: String) = n.isNotBlank() && '/' !in n && n != "." && n != ".."

    fun rename(item: FItem, name: String) = run {
        if (!validName(name)) error("Tên không hợp lệ")
        if (!Fs.rename(item.path, name)) error("Đổi tên thất bại (tên đã tồn tại?)")
    }

    fun mkdir(i: Int, name: String) = run {
        if (name.isBlank() || name.split('/').any { it == ".." }) error("Tên không hợp lệ")
        if (!Fs.mkdir(panes[i].path, name)) error("Tạo thư mục thất bại")
    }

    /** Empty file; a name without extension becomes .txt. */
    fun newFile(i: Int, name: String) = run {
        val n = name.trim().let { if ('.' in it.trimStart('.')) it else "$it.txt" }
        if (!validName(n)) error("Tên không hợp lệ")
        if (!Fs.createFile(panes[i].path, n)) error("Không tạo được tệp (đã tồn tại?)")
    }

    /** Opens the compress dialog (format / level / password). */
    fun compress(items: List<FItem>) { if (items.isNotEmpty()) compressAsk = items }

    fun doCompress(items: List<FItem>, fmt: ArcFormat, level: ArcLevel, pw: CharArray?) = run {
        Archives.compress(items.map { File(it.path) }, fmt, level, pw)
        panes.indices.forEach { clearSelection(it) }
    }

    fun extractTo(item: FItem, dir: File, pw: CharArray? = null): Unit = run {
        try {
            Archives.extract(File(item.path), dir, pw)
        } catch (e: PasswordNeeded) {
            askPassword(item.name, e.wrong) { extractTo(item, dir, it) }
        }
    }

    /** Integrity test: reads every entry and reports OK or the first error. */
    fun testArchive(item: FItem, pw: CharArray? = null): Unit = run {
        val title = "Kiểm tra: ${item.name}"
        try {
            val r = Archives.test(File(item.path), pw)
            output = title to "✔ Không phát hiện lỗi\n${r.files} tệp · ${android.text.format.Formatter.formatFileSize(app, r.bytes)} sau giải nén"
        } catch (e: PasswordNeeded) {
            askPassword(item.name, e.wrong) { testArchive(item, it) }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Throwable) {
            DLog.e("test archive failed", e)
            output = title to "✘ Lỗi / file hỏng\n${e.chain()}"
        }
        Unit
    }

    fun extractHere(item: FItem) = extractTo(item, File(item.path).parentFile ?: File("/"))

    fun extractNamed(item: FItem) {
        val a = File(item.path)
        extractTo(item, File(a.parentFile, Archives.baseName(a.name)))
    }

    fun pickAndExtract(item: FItem, from: String) {
        picker = PickTarget(from) { extractTo(item, File(it)) }
    }

    // ---- Search / batch rename / APK / ZIP ----

    fun startSearch(root: String, o: SearchOpts) {
        searchJob?.cancel()
        val st = SearchState(root, o.query)
        search = st
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                Searcher.run(File(root), o, st) { isActive }
            } catch (t: Throwable) {
                if (t !is java.util.concurrent.CancellationException) {
                    DLog.e("search failed", t)
                    st.error = t.message ?: t.javaClass.simpleName
                }
            } finally {
                st.running = false
            }
        }
    }

    fun stopSearch() { searchJob?.cancel() }

    fun batchRename(items: List<FItem>, names: List<String>) = run {
        if (items.isEmpty() || items.size != names.size) error("Danh sách không hợp lệ")
        if (names.any { !validName(it) } || names.toSet().size != names.size) error("Tên mới không hợp lệ hoặc trùng nhau")
        val dir = File(items[0].path).parentFile ?: error("Không có thư mục cha")
        val own = items.map { it.name }.toSet()
        names.forEach { if (it !in own && File(dir, it).exists()) error("Đã tồn tại: $it") }
        val tmp = ArrayList<File>()
        try {
            items.forEachIndexed { k, it ->
                val t = File(dir, ".fm-tmp-$k-${System.nanoTime()}")
                if (!Fs.rename(it.path, t.name)) error("Đổi tên thất bại: ${it.name}")
                tmp += t
            }
        } catch (ex: Throwable) {
            tmp.forEachIndexed { k, t -> Fs.rename(t.path, items[k].name) } // roll back
            throw ex
        }
        val fail = ArrayList<String>()
        tmp.forEachIndexed { k, t -> if (!Fs.rename(t.path, names[k])) { fail += names[k]; Fs.rename(t.path, items[k].name) } }
        panes.indices.forEach { clearSelection(it) }
        if (fail.isNotEmpty()) error("Đổi tên thất bại: ${fail.joinToString()}")
    }

    fun apkInfo(item: FItem) { propsItem = item }

    private fun signApk(unsigned: File, out: File) {
        app.assets.open("fm_key.pk8").use { k -> app.assets.open("fm_cert.der").use { c -> ApkSign.sign(k, c, unsigned, out) } }
    }

    private fun freeName(dir: File?, base: String): File {
        var f = File(dir, "$base.apk")
        var n = 1
        while (f.exists()) f = File(dir, "${base}_${n++}.apk")
        return f
    }

    fun startQuickEdit(item: FItem) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { ApkOps.read(File(item.path)) } }
                .onSuccess { quick = item to it }
                .onFailure { DLog.e("quick edit read failed", it); message = "Không đọc được APK: ${it.chain()}" }
        }
    }

    /** Edits the manifest, zip-aligns, signs; the result is written next to the source as *_edited.apk. */
    fun applyQuickEdit(item: FItem, n: ApkOps.Info) = run {
        val src = File(item.path)
        val out = freeName(src.parentFile, src.name.substringBeforeLast('.') + "_edited")
        val t1 = File(app.cacheDir, "qe_${System.nanoTime()}.tmp")
        val t2 = File(app.cacheDir, "qe_${System.nanoTime()}_al.tmp")
        try {
            ApkOps.edit(src, t2, n, t1)
            signApk(t2, out)
        } finally {
            t1.delete(); t2.delete()
        }
        toast = "Đã tạo ${out.name}"
    }

    /** Anti-split: merges split APKs (.apks/.xapk/.apkm, a folder of APKs, or several selected .apk files) into one signed APK next to the source */
    fun antiSplit(items: List<FItem>) = run {
        val first = File(items[0].path)
        val work = File(app.cacheDir, "antisplit_${System.nanoTime()}")
        val t1 = File(app.cacheDir, "as_${System.nanoTime()}.tmp")
        val t2 = File(app.cacheDir, "as_${System.nanoTime()}_al.tmp")
        try {
            val dir: File
            val base: String
            if (items.size == 1 && first.isDirectory) {
                dir = first
                base = first.name
            } else {
                work.mkdirs()
                dir = work
                base = if (items.size == 1) first.name.substringBeforeLast('.') else "merged"
                var k = 0
                if (items.size == 1) {
                    java.util.zip.ZipFile(first).use { z ->
                        for (e in z.entries()) {
                            Work.check()
                            if (e.isDirectory || !e.name.endsWith(".apk", ignoreCase = true)) continue
                            z.getInputStream(e).use { i ->
                                File(work, "${k++}_${File(e.name).name}").outputStream().use { o -> Work.copy(i, o) }
                            }
                        }
                    }
                } else {
                    items.forEach { File(it.path).copyTo(File(work, "${k++}_${it.name}"), overwrite = true) }
                }
            }
            if (dir.listFiles { f -> f.name.endsWith(".apk", ignoreCase = true) }.isNullOrEmpty()) error("Không tìm thấy file .apk để gộp")
            val out = freeName(first.parentFile.takeIf { !first.isDirectory } ?: first.parentFile, "${base}_merged")
            ApkOps.merge(dir, t2, t1)
            signApk(t2, out)
            toast = "Đã gộp thành ${out.name}"
        } finally {
            work.deleteRecursively(); t1.delete(); t2.delete()
        }
    }

    fun installApk(item: FItem) {
        runCatching { ApkTools.install(app, item.path) }.onFailure { message = it.message ?: it.javaClass.simpleName }
    }

    fun addClipToZip(zip: FItem) {
        val c = clip ?: return
        run { ZipEdit.add(File(zip.path), c.items.map { File(it.path) }) }
    }

    // ---- Git ----

    fun gitInit(i: Int) = run { GitTool.init(File(panes[i].path)) }

    fun gitClone(i: Int, url: String) = run {
        val u = url.trim()
        val name = u.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        GitTool.clone(u, File(panes[i].path, name))
    }

    companion object {
        val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "avif")
        val mediaExt = setOf("mp4", "mkv", "webm", "3gp", "mp3", "m4a", "ogg", "opus", "flac", "wav", "aac")
        val textExt = setOf(
            "txt", "md", "json", "xml", "log", "csv", "yml", "yaml", "toml", "ini", "conf", "properties",
            "kt", "java", "js", "ts", "py", "c", "cpp", "h", "sh", "gradle", "kts", "html", "css", "smali",
        )
        val viewable = imageExt + mediaExt + textExt
    }
}
