package com.example.fm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.regex.Pattern

// ------------------------------------------------------------------ search engine

data class SearchOpts(
    val query: String, val recursive: Boolean, val matchCase: Boolean, val regex: Boolean,
    val content: Boolean, val minKb: Long, val maxKb: Long,
)

data class Hit(val path: String, val isDir: Boolean, val info: String)

class SearchState(val root: String, val query: String) {
    val hits = mutableStateListOf<Hit>()
    var running by mutableStateOf(true)
    var scanned by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var truncated by mutableStateOf(false)
}

object Searcher {
    private const val MAX_HITS = 3000
    private const val MAX_CONTENT = 20L * 1024 * 1024

    private fun human(n: Long): String = when {
        n >= 1L shl 30 -> "%.1f GB".format(n / (1024.0 * 1024 * 1024))
        n >= 1L shl 20 -> "%.1f MB".format(n / (1024.0 * 1024))
        n >= 1024 -> "%.1f KB".format(n / 1024.0)
        else -> "$n B"
    }

    private fun isSymlink(f: File) = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    private fun looksBinary(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val head = ByteArray(4096)
            val n = ins.read(head)
            n > 0 && (0 until n).any { head[it] == 0.toByte() }
        }
    }.getOrDefault(true)

    private fun contentHit(f: File, p: Pattern): Hit? {
        if (looksBinary(f)) return null
        var count = 0
        var first = ""
        var firstNo = 0
        var no = 0
        runCatching {
            f.bufferedReader().use { r ->
                r.forEachLine { line ->
                    no++
                    val m = p.matcher(line)
                    if (m.find()) {
                        if (count == 0) { first = line.trim().take(120); firstNo = no }
                        count++
                        while (m.find()) count++
                    }
                }
            }
        }
        return if (count == 0) null else Hit(f.path, false, "$count khớp · L$firstNo: $first")
    }

    /** Blocking; results are appended to [st]. Throws PatternSyntaxException for a bad regex. */
    fun run(root: File, o: SearchOpts, st: SearchState, active: () -> Boolean) {
        val flags = if (o.matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val p = Pattern.compile(if (o.regex) o.query else Pattern.quote(o.query), flags)
        val minB = o.minKb * 1024
        val maxB = if (o.maxKb > 0) o.maxKb * 1024 else Long.MAX_VALUE
        val stack = ArrayDeque<File>()
        stack.add(root)
        var n = 0
        while (stack.isNotEmpty() && active()) {
            val dir = stack.removeLast()
            val list: Array<File> = dir.listFiles()
                ?: (if (Fs.hasRoot()) Fs.list(dir.path).map { File(it.path) }.toTypedArray() else null)
                ?: continue
            for (f in list) {
                if (!active()) return
                if (st.hits.size >= MAX_HITS) { st.truncated = true; return }
                if (++n % 100 == 0) st.scanned = n
                if (f.isDirectory) {
                    if (o.recursive && !isSymlink(f)) stack.add(f)
                    if (!o.content && p.matcher(f.name).find()) st.hits.add(Hit(f.path, true, ""))
                    continue
                }
                val len = f.length()
                if (len < minB || len > maxB) continue
                if (!o.content) {
                    if (p.matcher(f.name).find()) st.hits.add(Hit(f.path, false, human(len)))
                } else if (len <= MAX_CONTENT) {
                    contentHit(f, p)?.let { st.hits.add(it) }
                }
            }
        }
        st.scanned = n
    }
}

// ------------------------------------------------------------------ search dialog

@Composable
private fun CheckRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onChange(it) })
        Text(label, fontSize = 14.sp)
    }
}

/** Full-screen search over the pane's current folder; results stay in the ViewModel while closed. */
@Composable
fun SearchDialog(vm: FmViewModel, idx: Int, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val root = remember { vm.panes[idx].path }
    var query by remember { mutableStateOf(vm.search?.query ?: "") }
    var recursive by remember { mutableStateOf(true) }
    var matchCase by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf(false) }
    var minKb by remember { mutableStateOf("") }
    var maxKb by remember { mutableStateOf("") }
    val st = vm.search

    fun go() {
        if (query.isBlank()) return
        vm.startSearch(
            root,
            SearchOpts(query, recursive, matchCase, regex, content, minKb.toLongOrNull() ?: 0L, maxKb.toLongOrNull() ?: 0L),
        )
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.surface) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Đóng") }
                    Text("Tìm kiếm", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text("Trong: $root", color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            query, { query = it }, Modifier.weight(1f), singleLine = true,
                            label = { Text(if (content) "Nội dung cần tìm" else "Tên file/thư mục") },
                        )
                        if (st?.running == true) Button(onClick = { vm.stopSearch() }) { Text("Dừng") }
                        else Button(onClick = { go() }, enabled = query.isNotBlank()) { Text("Tìm") }
                    }
                    Row {
                        CheckRow("Thư mục con", recursive, Modifier.weight(1f)) { recursive = it }
                        CheckRow("Hoa/thường", matchCase, Modifier.weight(1f)) { matchCase = it }
                    }
                    Row {
                        CheckRow("Regex", regex, Modifier.weight(1f)) { regex = it }
                        CheckRow("Trong nội dung", content, Modifier.weight(1f)) { content = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            minKb, { minKb = it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine = true,
                            label = { Text("Tối thiểu (KB)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            maxKb, { maxKb = it.filter(Char::isDigit) }, Modifier.weight(1f), singleLine = true,
                            label = { Text("Tối đa (KB)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                if (st != null) {
                    val status = buildString {
                        append(st.hits.size).append(" kết quả")
                        if (st.running) append(" · đang quét ").append(st.scanned).append(" mục…")
                        else append(" · đã quét ").append(st.scanned).append(" mục")
                        if (st.truncated) append(" · đã cắt bớt")
                    }
                    Text(status, Modifier.padding(12.dp), color = cs.onSurfaceVariant, fontSize = 13.sp)
                    st.error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = cs.error, fontSize = 13.sp) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(st.hits, key = { it.path }) { h ->
                            val f = File(h.path)
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    vm.navigate(idx, if (h.isDir) h.path else (f.parent ?: "/"))
                                    if (!h.isDir) vm.panes[idx].selected = setOf(h.path)
                                    onClose()
                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(f.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    h.path.removePrefix(root).trimStart('/').ifEmpty { "." },
                                    color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (h.info.isNotEmpty()) {
                                    Text(h.info, color = cs.primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ batch rename

data class RenameOpts(
    val find: String, val replace: String, val regex: Boolean,
    val prefix: String, val suffix: String, val number: Boolean, val start: Int, val digits: Int,
)

object BatchRename {
    /** New names in the order of [items]; throws on a bad regex. */
    fun names(items: List<FItem>, o: RenameOpts): List<String> {
        val re = if (o.regex && o.find.isNotEmpty()) Regex(o.find) else null
        return items.mapIndexed { i, it ->
            val dot = if (it.isDir) -1 else it.name.lastIndexOf('.')
            val hasExt = dot > 0
            var base = if (hasExt) it.name.substring(0, dot) else it.name
            val ext = if (hasExt) it.name.substring(dot) else ""
            if (o.find.isNotEmpty()) base = if (re != null) re.replace(base, o.replace) else base.replace(o.find, o.replace)
            base = o.prefix + base + o.suffix
            if (o.number) base += "_" + (o.start + i).toString().padStart(o.digits, '0')
            base + ext
        }
    }
}

@Composable
fun BatchRenameDialog(items: List<FItem>, onDismiss: () -> Unit, onApply: (List<FItem>, List<String>) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sorted = remember(items) { items.sortedBy { it.name.lowercase() } }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var number by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf("1") }
    var digits by remember { mutableStateOf("2") }

    val result = runCatching {
        BatchRename.names(
            sorted,
            RenameOpts(find, replace, regex, prefix, suffix, number, start.toIntOrNull() ?: 1, (digits.toIntOrNull() ?: 1).coerceIn(1, 9)),
        )
    }
    val names = result.getOrNull()
    val problem = when {
        names == null -> "Regex không hợp lệ"
        names.any { it.isBlank() || '/' in it || it == "." || it == ".." } -> "Có tên không hợp lệ"
        names.toSet().size != names.size -> "Có tên bị trùng"
        names == sorted.map { it.name } -> "Chưa có thay đổi"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đổi tên ${items.size} mục") },
        confirmButton = { TextButton(onClick = { if (names != null) onApply(sorted, names) }, enabled = problem == null) { Text("Đổi tên") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(find, { find = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Tìm") })
                OutlinedTextField(replace, { replace = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Thay bằng") })
                CheckRow("Dùng regex", regex) { regex = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(prefix, { prefix = it }, Modifier.weight(1f), singleLine = true, label = { Text("Tiền tố") })
                    OutlinedTextField(suffix, { suffix = it }, Modifier.weight(1f), singleLine = true, label = { Text("Hậu tố") })
                }
                CheckRow("Đánh số cuối tên", number) { number = it }
                if (number) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        start, { start = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), singleLine = true,
                        label = { Text("Bắt đầu") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        digits, { digits = it.filter(Char::isDigit).take(1) }, Modifier.weight(1f), singleLine = true,
                        label = { Text("Số chữ số") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                if (problem != null) Text(problem, color = cs.error, fontSize = 13.sp)
                names?.take(6)?.forEachIndexed { i, n ->
                    Text("${sorted[i].name}\n→ $n", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                if ((names?.size ?: 0) > 6) Text("…", color = cs.onSurfaceVariant)
            }
        },
    )
}
