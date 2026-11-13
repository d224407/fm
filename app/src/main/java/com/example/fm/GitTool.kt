package com.example.fm

import org.eclipse.jgit.api.Git
import java.io.File

/** Repo detection plus init/clone; no other git features. */
object GitTool {
    /** Nearest ancestor (or self) that is a git repo. */
    fun findRoot(path: String): File? {
        var d: File? = File(path)
        while (d != null) {
            if (Fs.isRepo(d)) return d
            d = d.parentFile
        }
        return null
    }

    fun init(dir: File) {
        val existed = File(dir, ".git").exists()
        try {
            Git.init().setDirectory(dir).call().close()
            check(File(dir, ".git/HEAD").exists()) { "Git init không tạo được repo" }
        } catch (t: Throwable) {
            if (!existed) File(dir, ".git").deleteRecursively()
            throw t
        }
    }

    /** Credentials may be embedded in the URL (https://user:token@host/repo.git). */
    fun clone(url: String, dest: File) {
        try {
            Git.cloneRepository().setURI(url).setDirectory(dest).call().close()
        } catch (t: Throwable) {
            if (dest.list().isNullOrEmpty()) dest.deleteRecursively()
            throw t
        }
    }
}
