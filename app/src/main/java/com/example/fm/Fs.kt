package com.example.fm

import com.topjohnwu.superuser.Shell
import java.io.File

data class FItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val isRepo: Boolean,
    val empty: Boolean = false,
)

/** File ops; falls back to root (libsu) when access is denied. */
object Fs {
    @Volatile private var rootCache: Boolean? = null

    /** App cache (extracted archives): readable directly, never worth a root shell. */
    @Volatile var localPrefix = "\u0000"

    /** Blocking on first call (spawns su); never call on the main thread. */
    fun hasRoot(): Boolean = rootCache ?: runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        .also { rootCache = it; DLog.i("root check -> $it") }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Git repo: .git dir/file (worktree, submodule) or bare repo. */
    fun isRepo(dir: File): Boolean {
        if (File(dir, ".git").exists()) return true
        return File(dir, "HEAD").isFile && File(dir, "objects").isDirectory && File(dir, "refs").isDirectory
    }

    fun list(path: String): List<FItem> {
        val inStorage = path.startsWith(android.os.Environment.getExternalStorageDirectory().path)
        // Outside shared storage the app sees a filtered view; prefer root when available
        val viaRoot = if (!inStorage && !path.startsWith(localPrefix) && hasRoot()) listRoot(path) else null
        val arr = if (viaRoot != null && viaRoot.isNotEmpty()) null else File(path).listFiles()
        val raw = when {
            viaRoot != null && viaRoot.isNotEmpty() -> viaRoot
            arr != null -> arr.map {
                FItem(it.path, it.name, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified(),
                    it.isDirectory && isRepo(it), it.isDirectory && isEmptyDir(it))
            }
            hasRoot() -> listRoot(path)
            else -> emptyList()
        }
        val res = if (raw.isEmpty()) ancestorEntry(path) else raw
        return res.sortedWith(compareByDescending<FItem> { it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** Reads only the first entry instead of the whole directory. */
    private fun isEmptyDir(d: File): Boolean = runCatching {
        java.nio.file.Files.newDirectoryStream(d.toPath()).use { !it.iterator().hasNext() }
    }.getOrDefault(false)

    /**
     * /storage/emulated is not listable without root, which strands the user above shared storage;
     * show the child that leads back to it.
     */
    private fun ancestorEntry(path: String): List<FItem> {
        val sd = android.os.Environment.getExternalStorageDirectory().path
        val base = path.trimEnd('/')
        if (!sd.startsWith("$base/")) return emptyList()
        val name = sd.removePrefix("$base/").substringBefore('/')
        return listOf(FItem("$base/$name", name, true, 0L, 0L, false, false))
    }

    private fun listRoot(path: String): List<FItem> {
        val base = path.trimEnd('/')
        // one shell round trip; sections: repos, empty dirs, stat lines
        val all = Shell.cmd(
            "( cd ${q(path)} 2>/dev/null && { echo @R; ls -d ./*/.git 2>/dev/null; " +
                "echo @E; find . -maxdepth 1 -mindepth 1 -type d -empty 2>/dev/null; " +
                "echo @S; stat -L -c '%F|%s|%Y|%n' ./* ./.[!.]* 2>/dev/null; } )",
        ).exec().out
        val repos = HashSet<String>()
        val empties = HashSet<String>()
        val out = ArrayList<String>()
        var sec = ' '
        for (l in all) {
            when (l) {
                "@R" -> sec = 'R'
                "@E" -> sec = 'E'
                "@S" -> sec = 'S'
                else -> when (sec) {
                    'R' -> repos += l.removePrefix("./").removeSuffix("/.git")
                    'E' -> empties += l.removePrefix("./")
                    'S' -> out += l
                }
            }
        }
        return out.mapNotNull { line ->
            val p = line.split('|', limit = 4)
            if (p.size < 4) return@mapNotNull null
            val name = p[3].removePrefix("./")
            if (name.isEmpty()) return@mapNotNull null
            val dir = p[0].startsWith("directory")
            FItem("$base/$name", name, dir, p[1].toLongOrNull() ?: 0L, (p[2].toLongOrNull() ?: 0L) * 1000,
                dir && name in repos, dir && name in empties)
        }
    }

    fun mkdir(parent: String, name: String): Boolean {
        val f = File(parent, name)
        return f.mkdirs() || (hasRoot() && Shell.cmd("mkdir -p ${q(f.path)}").exec().isSuccess)
    }

    fun createFile(parent: String, name: String): Boolean {
        val f = File(parent, name)
        if (f.exists()) return false
        return runCatching { f.createNewFile() }.getOrDefault(false) ||
            (hasRoot() && Shell.cmd("touch ${q(f.path)}").exec().isSuccess)
    }

    fun delete(path: String): Boolean {
        val f = File(path)
        // deleteRecursively follows symlinks into the target; remove the link itself only
        val link = runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
        val ok = if (link) f.delete() else f.deleteRecursively()
        return ok || (hasRoot() && Shell.cmd("rm -rf ${q(path)}").exec().isSuccess)
    }

    fun rename(path: String, newName: String): Boolean {
        val src = File(path)
        val dst = File(src.parentFile, newName)
        if (dst.exists() && dst != src) return false // never overwrite silently
        return src.renameTo(dst) || (hasRoot() && Shell.cmd("mv ${q(path)} ${q(dst.path)}").exec().isSuccess)
    }

    /** "a.txt" -> "a (1).txt" (first free name). */
    private fun uniqueName(f: File): File {
        val dot = f.name.lastIndexOf('.').takeIf { it > 0 && !f.isDirectory }
        val stem = if (dot != null) f.name.substring(0, dot) else f.name
        val ext = if (dot != null) f.name.substring(dot) else ""
        var n = 1
        while (true) {
            val r = File(f.parentFile, "$stem (${n++})$ext")
            if (!r.exists()) return r
        }
    }

    fun copy(path: String, destDir: String): Boolean {
        val src = File(path)
        var dst = File(destDir, src.name)
        val s = runCatching { src.canonicalFile }.getOrDefault(src.absoluteFile)
        val dir = runCatching { File(destDir).canonicalFile }.getOrDefault(File(destDir).absoluteFile)
        // a folder cannot be copied into itself (endless recursion)
        if (src.isDirectory && (dir == s || dir.path.startsWith(s.path + File.separator))) return false
        // same folder: copy under a new name (writing onto the source would truncate it)
        if (runCatching { dst.canonicalFile }.getOrDefault(dst.absoluteFile) == s) dst = uniqueName(dst)
        val ok = try {
            copyTree(src, dst)
            true
        } catch (c: java.util.concurrent.CancellationException) {
            throw c
        } catch (_: Throwable) {
            false
        }
        return ok || (hasRoot() && Shell.cmd("cp -a ${q(path)} ${q(dst.path)}").exec().isSuccess)
    }

    private fun copyTree(src: File, dst: File) {
        Work.check()
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else {
            src.inputStream().use { i -> dst.outputStream().use { o -> Work.copy(i, o) } }
        }
    }

    fun move(path: String, destDir: String): Boolean {
        val src = File(path)
        val dst = File(destDir, src.name)
        if (src.renameTo(dst)) return true
        if (hasRoot() && Shell.cmd("mv ${q(path)} ${q(dst.path)}").exec().isSuccess) return true
        return copy(path, destDir) && delete(path)
    }
}
