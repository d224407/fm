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
import kotlinx.coroutines.launch
import java.io.File

/** An archive opened as a virtual folder: [root] is the extracted copy of [file] in cache. */
data class ArchiveCtx(val file: String, val root: String)

class PaneState(start: String) {
    var path by mutableStateOf(start)
    var items by mutableStateOf<List<FItem>>(emptyList())
    var selected by mutableStateOf<Set<String>>(emptySet())
    var archive by mutableStateOf<ArchiveCtx?>(null)
    var gitRoot by mutableStateOf<String?>(null)
}

/** Copy/cut buffer shared by both tabs. */
data class Clip(val items: List<FItem>, val cut: Boolean)

/** Folder chooser request (extract to…). */
class PickTarget(val start: String, val onPick: (String) -> Unit)

class FmViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val panes = List(2) { PaneState(Environment.getExternalStorageDirectory().path) }

    var favorites by mutableStateOf<List<String>>(emptyList()); private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var output by mutableStateOf<Pair<String, String>?>(null)
    var picker by mutableStateOf<PickTarget?>(null)
    var viewing by mutableStateOf<FItem?>(null)
    var rooted by mutableStateOf(false); private set
    var showLog by mutableStateOf(false)
    var clip by mutableStateOf<Clip?>(null)

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

    fun navigate(i: Int, path: String) {
        val p = panes[i]
        val a = p.archive
        if (a != null && path != a.root && !path.startsWith(a.root + "/")) p.archive = null
        p.selected = emptySet()
        p.path = path
        DLog.d("navigate pane=$i path=$path")
        refresh(i)
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
        // skip ancestors we cannot list (e.g. /storage/emulated) unless root can
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
            ext in viewable -> viewing = item
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
        }.onFailure { DLog.e("open external failed", it); message = "Không có ứng dụng mở file này." }
    }

    /** Extract the archive into cache and browse it like a folder. */
    fun openArchive(i: Int, item: FItem) = run {
        val f = File(item.path)
        val key = (item.path + f.lastModified()).hashCode().toUInt().toString(16)
        val dest = File(app.cacheDir, "archive_view/$key")
        val done = File(dest.parentFile, "$key.done")
        if (!done.exists()) {
            dest.deleteRecursively()
            try {
                Archives.extract(f, dest)
                done.createNewFile()
            } catch (t: Throwable) {
                dest.deleteRecursively()
                throw t
            }
        }
        panes[i].archive = ArchiveCtx(item.path, dest.path)
        navigate(i, dest.path)
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
                message = ex.message ?: ex.javaClass.simpleName
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
            if (c.cut) clip = null
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

    fun compress(items: List<FItem>) = run {
        Archives.compress(items.map { File(it.path) })
        panes.indices.forEach { clearSelection(it) }
    }

    fun extractTo(item: FItem, dir: File) = run { Archives.extract(File(item.path), dir) }

    fun extractHere(item: FItem) = extractTo(item, File(item.path).parentFile ?: File("/"))

    fun extractNamed(item: FItem) {
        val a = File(item.path)
        extractTo(item, File(a.parentFile, Archives.baseName(a.name)))
    }

    fun pickAndExtract(item: FItem, from: String) {
        picker = PickTarget(from) { extractTo(item, File(it)) }
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
