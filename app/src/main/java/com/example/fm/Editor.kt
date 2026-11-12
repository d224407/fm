package com.example.fm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Large-file friendly text editor: the document is a list of lines shown in a LazyColumn
 * (only visible lines are composed) and only the line being edited is a real text field.
 */

private const val MAX_EDIT_BYTES = 8L * 1024 * 1024 // bigger files open read-only (first part only)
private const val PREVIEW_BYTES = 2L * 1024 * 1024
private const val MAX_LINE = 20_000 // longer lines make text layout explode: read-only
private const val SHOW_CAP = 4_000

private class Doc(val lines: SnapshotStateList<String>, val crlf: Boolean, val readOnly: Boolean, val note: String?)

private fun loadDoc(path: String): Doc {
    val f = File(path)
    val len = f.length()
    val partial = len > MAX_EDIT_BYTES
    val bytes = f.inputStream().use { i ->
        val want = if (partial) PREVIEW_BYTES.toInt() else len.toInt()
        val buf = ByteArray(want)
        var n = 0
        while (n < want) {
            val r = i.read(buf, n, want - n)
            if (r < 0) break
            n += r
        }
        if (n == want) buf else buf.copyOf(n)
    }
    val binary = bytes.take(4096).any { it == 0.toByte() }
    val text = String(bytes, Charsets.UTF_8)
    val crlf = text.contains("\r\n")
    val parts = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val longLine = parts.any { it.length > MAX_LINE }
    val note = when {
        partial -> "File lớn: chỉ hiển thị ${PREVIEW_BYTES / 1024 / 1024}MB đầu, chỉ đọc."
        binary -> "File nhị phân: chỉ đọc."
        longLine -> "Có dòng quá dài: chỉ đọc."
        else -> null
    }
    val lines = mutableStateListOf<String>()
    lines.addAll(parts)
    return Doc(lines, crlf, note != null, note)
}

@Composable
fun TextEditor(item: FItem, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<Doc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableIntStateOf(-1) }
    var tfv by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    LaunchedEffect(item.path) {
        runCatching { withContext(Dispatchers.IO) { loadDoc(item.path) } }
            .onSuccess { doc = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    fun requestClose() { if (dirty) confirmClose = true else onClose() }

    fun save() {
        val d = doc ?: return
        if (d.readOnly || saving) return
        val snap = d.lines.toList()
        saving = true
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    Fs.writeText(item.path, snap.joinToString(if (d.crlf) "\r\n" else "\n"))
                }
            }
            saving = false
            r.onSuccess { dirty = false; status = "Đã lưu" }
                .onFailure { status = "Lưu thất bại: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    fun activate(i: Int, caret: Int = -1) {
        val d = doc ?: return
        if (d.readOnly || i !in d.lines.indices) return
        val t = d.lines[i]
        active = i
        tfv = TextFieldValue(t, TextRange(if (caret < 0) t.length else caret.coerceAtMost(t.length)))
    }

    /** Applies an edit of the active line; newlines (typed or pasted) split it into several lines. */
    fun onEdit(i: Int, nv: TextFieldValue) {
        val d = doc ?: return
        val t = nv.text
        if ('\n' !in t && '\r' !in t) {
            if (t != d.lines[i]) { d.lines[i] = t; dirty = true; status = null }
            tfv = nv
            return
        }
        val parts = t.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        d.lines[i] = parts[0]
        d.lines.addAll(i + 1, parts.drop(1))
        val last = parts.last()
        val caret = (nv.selection.start - (t.lastIndexOf('\n') + 1)).coerceIn(0, last.length)
        active = i + parts.size - 1
        tfv = TextFieldValue(last, TextRange(caret))
        dirty = true
        status = null
    }

    /** Backspace at column 0 joins the line with the previous one. */
    fun joinWithPrevious(i: Int): Boolean {
        val d = doc ?: return false
        if (i <= 0 || i >= d.lines.size) return false
        val prev = d.lines[i - 1]
        d.lines[i - 1] = prev + d.lines[i]
        d.lines.removeAt(i)
        active = i - 1
        tfv = TextFieldValue(d.lines[i - 1], TextRange(prev.length))
        dirty = true
        status = null
        return true
    }

    Dialog(onDismissRequest = { requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.surface) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { requestClose() }) { Icon(Icons.Default.Close, "Đóng") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (dirty) "● " else "") + item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = cs.onSurface, fontSize = 16.sp,
                        )
                        val sub = status ?: doc?.note ?: doc?.let { "${it.lines.size} dòng" }
                        if (sub != null) Text(sub, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                    }
                    if (doc?.readOnly == false) {
                        IconButton(onClick = { save() }, enabled = dirty && !saving) {
                            Icon(Icons.Default.Save, "Lưu")
                        }
                    }
                }
                val d = doc
                when {
                    error != null -> Text("Không đọc được: $error", Modifier.padding(16.dp), color = cs.error)
                    d == null -> Text("Đang đọc…", Modifier.padding(16.dp), color = cs.onSurfaceVariant)
                    else -> {
                        val mono = FontFamily.Monospace
                        val numW = (d.lines.size.toString().length * 8 + 12).dp
                        LazyColumn(Modifier.fillMaxSize(), state = listState) {
                            itemsIndexed(d.lines) { i, line ->
                                Row(Modifier.fillMaxWidth().padding(end = 8.dp)) {
                                    Text(
                                        "${i + 1}", Modifier.width(numW).padding(end = 6.dp), color = cs.outline,
                                        fontFamily = mono, fontSize = 11.sp, lineHeight = 16.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    )
                                    if (i == active && !d.readOnly) {
                                        val fr = remember { FocusRequester() }
                                        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                                        BasicTextField(
                                            value = tfv,
                                            onValueChange = { onEdit(i, it) },
                                            textStyle = TextStyle(
                                                fontFamily = mono, fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface,
                                            ),
                                            cursorBrush = SolidColor(cs.primary),
                                            modifier = Modifier.weight(1f).background(cs.surfaceContainerHigh)
                                                .focusRequester(fr)
                                                .onPreviewKeyEvent { ev ->
                                                    ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace &&
                                                        tfv.selection.collapsed && tfv.selection.start == 0 &&
                                                        joinWithPrevious(i)
                                                },
                                        )
                                    } else {
                                        val shown = if (line.length > SHOW_CAP) line.take(SHOW_CAP) + "…" else line
                                        Text(
                                            shown.ifEmpty { " " },
                                            Modifier.weight(1f).clickable(enabled = !d.readOnly) { activate(i) },
                                            color = cs.onSurface, fontFamily = mono, fontSize = 12.sp, lineHeight = 16.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            text = { Text("Có thay đổi chưa lưu.") },
            confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("Bỏ thay đổi") } },
            dismissButton = {
                TextButton(onClick = { confirmClose = false; save() }) { Text("Lưu") }
            },
        )
    }
}
