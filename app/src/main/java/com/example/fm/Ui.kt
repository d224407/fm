package com.example.fm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------ helpers

private val FolderOrange = Color(0xFFFFB02E)
private val StorageGreen = Color(0xFF5BA11E)

// Popup (drop-down) palette: bright black, fixed in light and dark mode
private val MenuBg = Color(0xFF45454B)
private val MenuText = Color(0xFFFFFFFF)
private val MenuSub = Color(0xFFCACAD2)
private val MenuLine = Color(0xFF66666D)
private val MenuAccent = Color(0xFF9CC2FF)
private val MenuDanger = Color(0xFFFF7B72)

internal fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("fm", text))
    Toast.makeText(ctx, "Đã sao chép", Toast.LENGTH_SHORT).show()
}

/** ADB-style grouped list: big outer corners (24dp), small inner corners (4dp). */
private fun groupShape(i: Int, n: Int): Shape {
    val big = 24.dp
    val small = 4.dp
    return when {
        n == 1 -> RoundedCornerShape(big)
        i == 0 -> RoundedCornerShape(big, big, small, small)
        i == n - 1 -> RoundedCornerShape(small, small, big, big)
        else -> RoundedCornerShape(small)
    }
}

/** Glyph for well-known folders; [path] lets us tell /data from Android/data. */
private fun folderGlyph(path: String): ImageVector? {
    val f = File(path)
    val name = f.name.lowercase()
    val parent = f.parentFile?.name?.lowercase() ?: ""
    if (f.parent == "/") when (name) {
        "system" -> return Icons.Default.Settings
        "data" -> return Icons.Default.Storage
        "sdcard", "storage" -> return Icons.Default.SdCard
        "etc" -> return Icons.Default.Tune
        "vendor", "odm", "oem" -> return Icons.Default.Business
        "product", "system_ext" -> return Icons.Default.Inventory2
        "proc", "sys" -> return Icons.Default.Memory
        "dev" -> return Icons.Default.Usb
        "mnt" -> return Icons.Default.Dns
        "cache" -> return Icons.Default.Cached
        "apex" -> return Icons.Default.Extension
        "bin", "sbin" -> return Icons.Default.Terminal
        "lib", "lib64" -> return Icons.Default.Layers
        "acct", "config", "debug_ramdisk" -> return Icons.Default.Settings
        "metadata", "linkerconfig" -> return Icons.Default.Info
    }
    if (parent == "android") when (name) {
        "data" -> return Icons.Default.Storage
        "obb" -> return Icons.Default.Inventory2
        "media" -> return Icons.Default.PermMedia
    }
    return when (name) {
        "download", "downloads" -> Icons.Default.Download
        "documents" -> Icons.Default.Description
        "dcim" -> Icons.Default.PhotoCamera
        "pictures" -> Icons.Default.Image
        "movies" -> Icons.Default.Movie
        "music" -> Icons.Default.MusicNote
        "android" -> Icons.Default.Android
        "alarms" -> Icons.Default.Alarm
        "notifications" -> Icons.Default.Notifications
        "ringtones" -> Icons.Default.NotificationsActive
        "podcasts" -> Icons.Default.Radio
        "recordings" -> Icons.Default.Mic
        "screenshots" -> Icons.Default.PhoneAndroid
        "audiobooks" -> Icons.Default.Headphones
        else -> null
    }
}

private fun fileIcon(item: FItem): ImageVector = when {
    item.name.lowercase().endsWith(".apk") -> Icons.Default.Android
    Archives.isArchive(item.name) -> Icons.Default.Archive
    else -> when (item.name.substringAfterLast('.', "").lowercase()) {
        in FmViewModel.imageExt -> Icons.Default.Image
        in FmViewModel.mediaExt -> Icons.Default.PlayCircle
        "pdf" -> Icons.Default.PictureAsPdf
        in FmViewModel.textExt -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}

/** Accent per file type (icon tint; background is the same color, faded). */
private fun fileColor(item: FItem): Color = when {
    item.name.lowercase().endsWith(".apk") -> Color(0xFF3DDC84)
    Archives.isArchive(item.name) -> Color(0xFFFFC107)
    else -> when (item.name.substringAfterLast('.', "").lowercase()) {
        in FmViewModel.imageExt -> Color(0xFF2EC4B6)
        in FmViewModel.mediaExt -> Color(0xFFC25BD6)
        "pdf" -> Color(0xFFFF5F5C)
        "dex", "smali", "kt", "java", "js", "ts", "py", "c", "cpp", "h", "sh", "kts", "gradle" -> Color(0xFF9170E0)
        "xml", "html", "css", "json", "yml", "yaml", "toml" -> Color(0xFFFF8A5C)
        in FmViewModel.textExt -> Color(0xFF55B3FF)
        else -> Color(0xFF90A4AE)
    }
}

@Composable
private fun FolderIcon(path: String, size: Dp = 40.dp, glyph: ImageVector? = folderGlyph(path), empty: Boolean = false) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(if (empty) Icons.Outlined.Folder else Icons.Default.Folder, null, tint = FolderOrange, modifier = Modifier.fillMaxSize())
        if (glyph != null) {
            // empty folders keep their glyph, drawn in the folder color inside the outline
            Icon(glyph, null, tint = if (empty) FolderOrange else Color.White,
                modifier = Modifier.padding(top = size * 0.15f).size(size * 0.4f))
        }
    }
}

@Composable
private fun SmallChip(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text, color = cs.onSecondaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(cs.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun Pill(
    text: String, icon: ImageVector, modifier: Modifier = Modifier,
    danger: Boolean = false, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (danger) cs.errorContainer else cs.secondaryContainer
    val fg = if (danger) cs.onErrorContainer else cs.onSecondaryContainer
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(bg)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun BarIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(46.dp)) {
        Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ root

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FmRoot(vm: FmViewModel) {
    val cs = MaterialTheme.colorScheme
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val cur = vm.panes[pager.currentPage]

    BackHandler(enabled = vm.busy || cur.selected.isNotEmpty() || cur.path != "/") {
        when {
            vm.busy -> vm.cancel() // stop the running task first
            cur.selected.isNotEmpty() -> vm.clearSelection(pager.currentPage)
            else -> vm.up(pager.currentPage)
        }
    }

    Scaffold(
        containerColor = cs.surface,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                PrimaryTabRow(selectedTabIndex = pager.currentPage, containerColor = cs.surface, contentColor = cs.primary) {
                    vm.panes.indices.forEach { i ->
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = { Text(vm.tabTitle(i), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalPager(pager, Modifier.weight(1f)) { page -> PaneScreen(vm, page) }
        }
    }

    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("Đóng") } },
            dismissButton = if (DLog.enabled) {
                { TextButton(onClick = { vm.message = null; vm.showLog = true }) { Text("Log") } }
            } else null,
            text = {
                SelectionContainer {
                    Text(
                        msg, Modifier.verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                }
            },
        )
    }

    vm.output?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { vm.output = null },
            title = { Text(title) },
            confirmButton = { TextButton(onClick = { vm.output = null }) { Text("Đóng") } },
            text = {
                SelectionContainer {
                    Text(text, Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
        )
    }

    vm.pwdAsk?.let { a -> PasswordDialog(a, { vm.pwdAsk = null }) { pw -> vm.pwdAsk = null; a.onOk(pw) } }
    vm.compressAsk?.let { list ->
        CompressDialog(
            if (list.size == 1) list[0].name else "${list.size} m\u1ee5c", { vm.compressAsk = null },
        ) { fmt, level, pw -> vm.compressAsk = null; vm.doCompress(list, fmt, level, pw) }
    }

    vm.picker?.let { p -> FolderPicker(p.start, { vm.picker = null }) { vm.picker = null; p.onPick(it) } }


    vm.viewing?.let { Viewer(it, vm.viewSiblings) { vm.viewing = null } }
    vm.editing?.let { item -> TextEditor(item) { vm.editing = null } }
    vm.propsItem?.let { item -> PropertiesDialog(item, vm) { vm.propsItem = null } }
    vm.quick?.let { (item, info) ->
        QuickEditDialog(info, { vm.quick = null }) { n -> vm.quick = null; vm.applyQuickEdit(item, n) }
    }
    vm.batch?.let { list ->
        BatchRenameDialog(list, { vm.batch = null }) { sorted, names -> vm.batch = null; vm.batchRename(sorted, names) }
    }
    if (DLog.enabled && vm.showLog) LogDialog { vm.showLog = false }
}

// ------------------------------------------------------------------ pane

@Composable
fun PaneScreen(vm: FmViewModel, idx: Int) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val pane = vm.panes[idx]
    val selecting = pane.selected.isNotEmpty()
    var renaming by remember { mutableStateOf<FItem?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var cloneDlg by remember { mutableStateOf(false) }
    var gitMenu by remember { mutableStateOf(false) }
    var favOpen by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    // a new folder must start at the top (the old scroll offset left the first rows hidden until a swipe)
    val listState = rememberLazyListState()
    LaunchedEffect(pane.path) { listState.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // One list: folders first, files after (no separation)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val hasParent = pane.path != "/"
                val total = pane.items.size + if (hasParent) 1 else 0
                if (hasParent) {
                    item(key = "..") { ParentRow(groupShape(0, total), { vm.up(idx) }, { vm.selectAll(idx) }) }
                }
                itemsIndexed(pane.items, key = { _, it -> it.path }) { i, it ->
                    FileEntry(
                        vm, idx, it, groupShape(if (hasParent) i + 1 else i, total),
                        selected = it.path in pane.selected, selecting = selecting,
                        onRename = { renaming = it },
                    )
                }
                if (pane.items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Trống hoặc không có quyền đọc.", color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }

            if (selecting) Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill("Chép", Icons.Default.ContentCopy, Modifier.weight(1f)) { vm.copy(idx, vm.selectedItems(idx)) }
                Pill("Cắt", Icons.Default.ContentCut, Modifier.weight(1f)) { vm.cut(idx, vm.selectedItems(idx)) }
                Pill("Nén", Icons.Default.Archive, Modifier.weight(1f)) { vm.compress(vm.selectedItems(idx)) }
                Pill("Xóa", Icons.Default.Delete, Modifier.weight(1f), danger = true) { vm.delete(vm.selectedItems(idx)) }
                Box(Modifier.weight(1f)) {
                    Pill("Thêm", Icons.Default.MoreHoriz, Modifier.fillMaxWidth()) { moreMenu = true }
                    DropdownMenu(
                        expanded = moreMenu, onDismissRequest = { moreMenu = false },
                        modifier = Modifier.widthIn(min = 240.dp), shape = RoundedCornerShape(20.dp), containerColor = MenuBg,
                    ) {
                        val sel = vm.selectedItems(idx)
                        if (sel.size == 1) MenuRow(Icons.Default.Info, "Thuộc tính", MenuAccent) { moreMenu = false; vm.propsItem = sel[0] }
                        if (sel.size == 1 && !sel[0].isDir) {
                            MenuRow(Icons.Default.Edit, "Mở bằng trình soạn thảo", MenuAccent) { moreMenu = false; vm.editing = sel[0] }
                        }
                        if (sel.size >= 2) {
                            MenuRow(Icons.Default.DriveFileRenameOutline, "Đổi tên hàng loạt", MenuAccent) { moreMenu = false; vm.batch = sel }
                        }
                        if (sel.all { !it.isDir && it.name.lowercase().endsWith(".apk") } && sel.size >= 2) {
                            MenuRow(Icons.Default.Layers, "Gộp APK đã chọn (Anti-split)", MenuAccent) { moreMenu = false; vm.antiSplit(sel) }
                        }
                    }
                }
            }

            // Bottom address bar (tap = shortcuts, selectable text) or selection summary
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
                    .heightIn(min = 60.dp).clip(RoundedCornerShape(30.dp))
                    .background(if (selecting) cs.primaryContainer else cs.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    BarIcon(Icons.Default.Close, "Bỏ chọn") { vm.clearSelection(idx) }
                    Text(
                        "${pane.selected.size} đã chọn", Modifier.weight(1f).padding(start = 6.dp),
                        color = cs.onPrimaryContainer, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    )
                    BarIcon(Icons.Default.SelectAll, "Chọn tất cả") { vm.selectAll(idx) }
                } else {
                    if (vm.rooted) SmallChip("root")
                    // Tap the path to open the favorites menu
                    Box(Modifier.weight(1f)) {
                        SelectionContainer(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { favOpen = true }
                                .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 12.dp),
                        ) {
                            Text(
                                vm.displayPath(idx), maxLines = 1, softWrap = false,
                                color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                        DropdownMenu(
                            expanded = favOpen, onDismissRequest = { favOpen = false },
                            modifier = Modifier.widthIn(min = 360.dp), shape = RoundedCornerShape(24.dp), containerColor = MenuBg,
                        ) {
                            Text(
                                "LỐI TẮT", color = MenuAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                letterSpacing = 0.08.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                            )
                            vm.favorites.forEach { p ->
                                MenuFav(p, removable = !vm.isDefaultShortcut(p), onRemove = { vm.toggleFavorite(p) }) { favOpen = false; vm.navigate(idx, p) }
                            }
                            if (vm.favorites.isEmpty()) {
                                Text("Chưa có. Bấm ngôi sao để thêm.", color = MenuSub, fontSize = 14.sp,
                                    modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                    val clip = vm.clip
                    if (clip != null && pane.archive == null) {
                        BarIcon(Icons.Default.ContentPaste, "Dán ${clip.items.size}") { vm.paste(idx) }
                    }
                    if (DLog.enabled) BarIcon(Icons.Default.BugReport, "Log") { vm.showLog = true }
                    BarIcon(Icons.Default.ContentCopy, "Sao chép địa chỉ") { copyText(ctx, vm.displayPath(idx)) }
                    if (pane.archive == null) {
                        val fav = pane.path in vm.favorites
                        BarIcon(if (fav) Icons.Default.Star else Icons.Outlined.StarBorder, "Lối tắt") {
                            vm.toggleFavorite(pane.path)
                        }
                    }
                }
            }

        }

        if (!selecting && pane.archive == null) {
            // "+" menu: new folder / file, git init (outside repos) and clone
            val root = pane.gitRoot
            Box(Modifier.align(Alignment.BottomEnd).padding(start = 16.dp, end = 16.dp, bottom = 88.dp)) {
                FloatingActionButton(
                    onClick = { gitMenu = !gitMenu },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = cs.primary, contentColor = cs.onPrimary,
                ) { Icon(if (gitMenu) Icons.Default.Close else Icons.Default.Add, "Thêm") }
                DropdownMenu(
                    expanded = gitMenu, onDismissRequest = { gitMenu = false },
                    modifier = Modifier.widthIn(min = 240.dp),
                    shape = RoundedCornerShape(20.dp), containerColor = MenuBg,
                ) {
                    fun go(f: () -> Unit) { gitMenu = false; f() }
                    MenuRow(Icons.Default.CreateNewFolder, "Thư mục mới", MenuAccent) { go { newFolder = true } }
                    MenuRow(Icons.Default.NoteAdd, "Tệp mới", MenuAccent) { go { newFile = true } }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MenuLine)
                    MenuRow(Icons.Default.Search, "Tìm kiếm", MenuAccent) { go { searchOpen = true } }
                    MenuRow(Icons.Default.Refresh, "Làm mới", MenuAccent) { go { vm.refresh(idx) } }
                    if (pane.canBack) MenuRow(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", MenuAccent) { go { vm.goBack(idx) } }
                    if (pane.canFwd) MenuRow(Icons.AutoMirrored.Filled.ArrowForward, "Tiến tới", MenuAccent) { go { vm.goForward(idx) } }
                    MenuRow(Icons.Default.SyncAlt, "Mở thư mục này ở tab kia", MenuAccent) { go { vm.syncOther(idx) } }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MenuLine)
                    if (root == null) MenuRow(Icons.Default.Source, "Git init", MenuAccent) { go { vm.gitInit(idx) } }
                    MenuRow(Icons.Default.CloudDownload, "Git clone", MenuAccent) { go { cloneDlg = true } }
                }
            }
        }
    }

    if (searchOpen) SearchDialog(vm, idx) { searchOpen = false }
    renaming?.let { item ->
        NameDialog("Đổi tên", item.name, { renaming = null }) { vm.rename(item, it); renaming = null }
    }
    if (newFolder) {
        NameDialog("Thư mục mới", "", { newFolder = false }) { vm.mkdir(idx, it); newFolder = false }
    }
    if (newFile) {
        NameDialog("Tệp mới (mặc định .txt)", "", { newFile = false }) { vm.newFile(idx, it); newFile = false }
    }
    if (cloneDlg) {
        NameDialog("Git clone (URL https, có thể kèm token)", "", { cloneDlg = false }) { vm.gitClone(idx, it); cloneDlg = false }
    }
}

/** A list row plus its long-press drop-down menu. */
@Composable
private fun FileEntry(
    vm: FmViewModel, idx: Int, item: FItem, shape: Shape,
    selected: Boolean, selecting: Boolean, onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        FileRow(
            item, shape, selected,
            onClick = { vm.open(idx, item) },
            onLong = { if (selecting) vm.toggleSelect(idx, item) else menu = true },
            onIcon = { vm.toggleSelect(idx, item) },
        )
        DropdownMenu(
            expanded = menu, onDismissRequest = { menu = false },
            modifier = Modifier.widthIn(min = 240.dp, max = 300.dp),
            shape = RoundedCornerShape(20.dp), containerColor = MenuBg,
        ) {
            ItemMenuContent(vm, idx, item, close = { menu = false }, onRename = onRename)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FItem, shape: Shape, selected: Boolean,
    onClick: () -> Unit, onLong: () -> Unit, onIcon: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val nameColor = if (selected) cs.onPrimaryContainer else cs.onSurface
    val descColor = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .heightIn(min = 56.dp).padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tapping the icon toggles selection
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onIcon),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Box(Modifier.fillMaxSize().background(cs.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = cs.onPrimary)
                }
                item.isDir -> FolderIcon(item.path, 40.dp, if (item.isRepo) Icons.Default.Source else folderGlyph(item.path), item.empty)
                Thumbs.supports(item.name) -> Thumb(item) { TypeIcon(item) }
                else -> TypeIcon(item)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name, color = nameColor, fontSize = 16.5.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val date = DateFormat.format("dd/MM/yyyy HH:mm", item.modified).toString()
            val info = when {
                item.isRepo -> "Git repo · $date"
                item.isDir -> date
                else -> Formatter.formatShortFileSize(ctx, item.size) + " · " + date
            }
            Text(info, color = descColor, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TypeIcon(item: FItem) {
    val cs = MaterialTheme.colorScheme
    val c = fileColor(item)
    Box(Modifier.fillMaxSize().background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Icon(fileIcon(item), null, tint = c)
    }
}

/** Thumbnail (image, video, apk icon, pdf page 1); shows [fallback] while loading or on failure. */
@Composable
private fun Thumb(item: FItem, fallback: @Composable () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val bmp by produceState<ImageBitmap?>(Thumbs.peek(item), item.path, item.size, item.modified) {
        // re-resolve on every key change so a modified file never keeps its old thumbnail
        value = Thumbs.peek(item)
            ?: withContext(Thumbs.dispatcher) { Thumbs.load(ctx, item, 128) }?.asImageBitmap()
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else fallback()
}

// ------------------------------------------------------------------ drop-down menu

private class Act(
    val icon: ImageVector, val label: String,
    val danger: Boolean = false, val dismiss: Boolean = true, val run: () -> Unit,
)

@Composable
private fun ColumnScope.ItemMenuContent(
    vm: FmViewModel, idx: Int, item: FItem, close: () -> Unit, onRename: () -> Unit,
) {
    val isArchive = !item.isDir && Archives.isArchive(item.name)

    val lower = item.name.lowercase()
    val isApk = !item.isDir && lower.endsWith(".apk")
    val isBundle = !item.isDir && (lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm"))
    val dirHasApks = remember(item.path) {
        item.isDir && (File(item.path).list { _, n -> n.lowercase().endsWith(".apk") }?.size ?: 0) >= 2
    }

    // main actions are always shown, the rest sit behind "Thêm" so the menu stays short
    val main = mutableListOf<Act>()
    main += Act(Icons.Default.CheckCircle, "Chọn") { vm.toggleSelect(idx, item) }
    main += Act(Icons.Default.Edit, "Đổi tên") { onRename() }
    main += Act(Icons.Default.ContentCopy, "Sao chép") { vm.copy(idx, listOf(item)) }
    main += Act(Icons.Default.ContentCut, "Cắt") { vm.cut(idx, listOf(item)) }
    if (isArchive) {
        main += Act(Icons.Default.Unarchive, "Giải nén tại đây") { vm.extractHere(item) }
    }
    if (isApk) {
        main += Act(Icons.Default.Android, "Thông tin APK") { vm.apkInfo(item) }
        main += Act(Icons.Default.InstallMobile, "Cài đặt APK") { vm.installApk(item) }
    }
    main += Act(Icons.Default.Delete, "Xóa", danger = true) { vm.delete(listOf(item)) }

    val more = mutableListOf<Act>()
    if (isApk) more += Act(Icons.Default.Tune, "Sửa nhanh (Quick Edit)") { vm.startQuickEdit(item) }
    if (isBundle || dirHasApks) more += Act(Icons.Default.Layers, "Gộp split APK (Anti-split)") { vm.antiSplit(listOf(item)) }
    more += Act(Icons.Default.Archive, "Nén…") { vm.compress(listOf(item)) }
    if (isArchive) {
        more += Act(Icons.Default.DriveFileMove, "Giải nén vào…") { vm.pickAndExtract(item, vm.panes[idx].path) }
        more += Act(Icons.Default.FolderZip, "Giải nén vào thư mục «${Archives.baseName(item.name)}»") { vm.extractNamed(item) }
    }
    if (item.isDir) {
        val fav = item.path in vm.favorites
        more += Act(if (fav) Icons.Outlined.StarBorder else Icons.Default.Star,
            if (fav) "Bỏ khỏi lối tắt" else "Thêm vào lối tắt") { vm.toggleFavorite(item.path) }
    }
    more += Act(Icons.Default.Info, "Thuộc tính") { vm.propsItem = item }
    if (!item.isDir) more += Act(Icons.Default.Edit, "Mở bằng trình soạn thảo") { vm.editing = item }
    val c = vm.clip
    if (!item.isDir && lower.endsWith(".zip") && c != null) {
        more += Act(Icons.Default.FolderZip, "Thêm ${c.items.size} mục đã chép vào ZIP") { vm.addClipToZip(item) }
    }

    var expanded by remember { mutableStateOf(false) }
    fun row(a: Act) = @Composable {
        MenuRow(a.icon, a.label, if (a.danger) MenuDanger else MenuAccent) {
            if (a.dismiss) close()
            a.run()
        }
    }
    main.forEach { a -> row(a)() }
    MenuRow(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Ít hơn" else "Thêm…", MenuSub) {
        expanded = !expanded
    }
    if (expanded) more.forEach { a -> row(a)() }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (tint == MenuDanger) MenuDanger else MenuText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Favorite folder entry: icon, title, path, and used/total for storage roots. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MenuFav(path: String, removable: Boolean, onRemove: () -> Unit, onClick: () -> Unit) {
    val usage = remember(path) { Places.usage(path) }
    // long-press (non-default shortcuts only) reveals a delete button
    var showDel by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { if (removable) showDel = true }).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (Places.isStorage(path)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(StorageGreen), contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp, 26.dp).clip(RoundedCornerShape(3.dp)).background(Color.White),
                    contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.padding(bottom = 4.dp).size(10.dp, 2.dp).background(StorageGreen))
                }
            }
        } else {
            FolderIcon(path, 52.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(Places.title(path), color = MenuText, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, color = MenuSub, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showDel) {
            IconButton(onClick = { showDel = false; onRemove() }) { Icon(Icons.Default.Delete, "Xóa lối tắt", tint = MenuDanger) }
        } else if (usage != null) {
            Text(usage, color = MenuText, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, singleLine = true, enabled = enabled, label = { Text(label) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { show = !show }, enabled = enabled) {
                Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
            }
        },
    )
}

/** Asks for the password of an encrypted archive. */
@Composable
private fun PasswordDialog(ask: PwdAsk, onDismiss: () -> Unit, onOk: (CharArray) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nh\u1eadp m\u1eadt kh\u1ea9u") },
        text = {
            Column {
                Text(ask.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (ask.wrong) Text("Sai m\u1eadt kh\u1ea9u, th\u1eed l\u1ea1i.", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(8.dp))
                PasswordField(pw, { pw = it }, "M\u1eadt kh\u1ea9u")
            }
        },
        confirmButton = { TextButton(onClick = { if (pw.isNotEmpty()) onOk(pw.toCharArray()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("H\u1ee7y") } },
    )
}

/** Format + level (+ password for zip). Defaults: tar.zst, normal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressDialog(title: String, onDismiss: () -> Unit, onOk: (ArcFormat, ArcLevel, CharArray?) -> Unit) {
    var fmt by remember { mutableStateOf(ArcFormat.TAR_ZST) }
    var level by remember { mutableStateOf(ArcLevel.NORMAL) }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("N\u00e9n: $title", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\u0110\u1ecbnh d\u1ea1ng")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArcFormat.entries.forEach { f -> FilterChip(fmt == f, { fmt = f }, { Text(f.label) }) }
                }
                Text("M\u1ee9c n\u00e9n")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArcLevel.entries.forEach { l -> FilterChip(level == l, { level = l }, { Text(l.label) }) }
                }
                PasswordField(
                    pw, { pw = it }, if (fmt.canPassword) "M\u1eadt kh\u1ea9u (AES-256, \u0111\u1ec3 tr\u1ed1ng = kh\u00f4ng)" else "M\u1eadt kh\u1ea9u: ch\u1ec9 d\u00f9ng v\u1edbi zip",
                    enabled = fmt.canPassword,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOk(fmt, level, if (fmt.canPassword && pw.isNotEmpty()) pw.toCharArray() else null) }) { Text("N\u00e9n") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("H\u1ee7y") } },
    )
}

/** In-app log viewer: refresh, copy all, clear. The same text is written to the log file. */
@Composable
private fun LogDialog(onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val text = remember(tick) { DLog.text() }
    val scroll = rememberScrollState()
    LaunchedEffect(text, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.surface) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Đóng", tint = cs.onSurfaceVariant) }
                    Text("Log", Modifier.weight(1f), color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "Làm mới", tint = cs.onSurfaceVariant) }
                    IconButton(onClick = { copyText(ctx, DLog.text(3000)) }) {
                        Icon(Icons.Default.ContentCopy, "Sao chép", tint = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { DLog.clear(); tick++ }) {
                        Icon(Icons.Default.Delete, "Xóa", tint = cs.onSurfaceVariant)
                    }
                }
                Text("File: ${DLog.path}", color = cs.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        text, color = cs.onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(12.dp),
                    )
                }
            }
        }
    }
}

/** Built-in viewer: images (swipe between the folder's pictures, EXIF) and audio/video (Media3). */
@Composable
private fun Viewer(item: FItem, siblings: List<FItem>, onClose: () -> Unit) {
    val ext = item.name.substringAfterLast('.', "").lowercase()
    var exif by remember { mutableStateOf<String?>(null) }
    val imgs = remember(item.path, siblings) { siblings.ifEmpty { listOf(item) } }
    val pager = rememberPagerState(
        initialPage = imgs.indexOfFirst { it.path == item.path }.coerceAtLeast(0), pageCount = { imgs.size },
    )
    val isImage = ext in FmViewModel.imageExt
    val shown = if (isImage) imgs[pager.currentPage.coerceIn(0, imgs.size - 1)] else item
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Đóng") }
                    Text(shown.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isImage) {
                        if (imgs.size > 1) Text("${pager.currentPage + 1}/${imgs.size}", fontSize = 13.sp)
                        IconButton(onClick = { exif = Props.exif(shown.path) }) { Icon(Icons.Default.Info, "EXIF") }
                    }
                }
                when {
                    isImage -> HorizontalPager(pager, Modifier.fillMaxSize()) { p ->
                        AsyncImage(File(imgs[p].path), imgs[p].name, Modifier.fillMaxSize())
                    }
                    else -> {
                        val ctx = LocalContext.current
                        val player = remember {
                            ExoPlayer.Builder(ctx).build().apply {
                                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(item.path))))
                                prepare(); playWhenReady = true
                            }
                        }
                        DisposableEffect(Unit) { onDispose { player.release() } }
                        AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    exif?.let { t ->
        AlertDialog(
            onDismissRequest = { exif = null },
            title = { Text("EXIF") },
            confirmButton = { TextButton(onClick = { exif = null }) { Text("Đóng") } },
            text = { SelectionContainer { Text(t, Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 12.sp) } },
        )
    }
}

/** ".." entry: tap = parent folder, long-press = select everything in this folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParentRow(shape: Shape, onUp: () -> Unit, onSelectAll: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(shape).background(cs.surfaceContainerHighest)
            .combinedClickable(onClick = onUp, onLongClick = onSelectAll)
            .heightIn(min = 56.dp).padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ArrowUpward, null, tint = cs.onSecondaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("..", color = cs.onSurface, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
            Text("Thư mục cha · giữ để chọn tất cả", color = cs.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

/** Folder chooser: browse folders, pick the one currently shown. */
@Composable
private fun FolderPicker(start: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var path by remember { mutableStateOf(start) }
    val dirs by produceState(emptyList<FItem>(), path) {
        value = withContext(Dispatchers.IO) { runCatching { Fs.list(path).filter { it.isDir } }.getOrDefault(emptyList()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chọn thư mục") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { File(path).parent?.let { path = it } }, enabled = path != "/") {
                        Icon(Icons.Default.ArrowUpward, "Lên")
                    }
                    Text(path, Modifier.weight(1f).horizontalScroll(rememberScrollState()), maxLines = 1, softWrap = false,
                        color = cs.onSurfaceVariant, fontSize = 13.sp)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(dirs, key = { it.path }) { d ->
                        Row(Modifier.fillMaxWidth().clickable { path = d.path }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            FolderIcon(d.path, 32.dp, empty = d.empty)
                            Spacer(Modifier.width(12.dp))
                            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text("Chọn thư mục này") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}
