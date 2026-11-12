package com.example.fm

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.SimpleFormatter

/**
 * Debug log: in-memory ring (shown in-app) + file (flushed per line, survives crashes).
 * Also captures stdout/stderr, java.util.logging (JGit via slf4j-jdk14) and uncaught exceptions.
 */
object DLog {
    private const val TAG = "FM"
    private const val MAX_LINES = 3000
    private const val MAX_FILE = 4L * 1024 * 1024
    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var ctx: Context? = null
    private var out: Writer? = null

    /** Logging exists only in debuggable builds. */
    var enabled = false
        private set

    var path: String = "(no file)"
        private set

    fun init(c: Context) {
        ctx = c.applicationContext
        enabled = (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!enabled) return
        retarget()
        i("==== FileManager log start ====")
        i("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString()}")
        i("log file=$path")
        mem()
        installHandlers()
        logPreviousExits(c)
    }

    /** Why the previous process died (OOM kill, native crash, ...). Android 11+. */
    private fun logPreviousExits(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = c.getSystemService(android.app.ActivityManager::class.java)
            val names = mapOf(1 to "EXIT_SELF", 2 to "SIGNALED", 3 to "LOW_MEMORY", 4 to "CRASH", 5 to "CRASH_NATIVE",
                6 to "ANR", 7 to "INIT_FAILURE", 8 to "PERMISSION_CHANGE", 9 to "EXCESSIVE_RESOURCE_USAGE",
                10 to "USER_REQUESTED", 11 to "USER_STOPPED", 12 to "DEPENDENCY_DIED", 13 to "OTHER", 14 to "FREEZER")
            am.getHistoricalProcessExitReasons(c.packageName, 0, 4).forEach {
                i("previous exit: ${names[it.reason] ?: it.reason} status=${it.status} rss=${it.rss / 1024}MB " +
                    "pss=${it.pss / 1024}MB at=${Date(it.timestamp)} desc=${it.description}")
            }
        }.onFailure { w("exit info unavailable", it) }
    }

    /** Pick the best writable location; call again after storage permission is granted. */
    fun retarget() {
        if (!enabled) return
        val c = ctx ?: return
        val candidates = listOfNotNull(
            File(Environment.getExternalStorageDirectory(), "FileManager/fm.log"),
            c.getExternalFilesDir(null)?.let { File(it, "fm.log") },
            File(c.filesDir, "fm.log"),
        )
        synchronized(lock) {
            for (f in candidates) {
                if (f.absolutePath == path && out != null) return
                try {
                    f.parentFile?.mkdirs()
                    if (f.exists() && f.length() > MAX_FILE) f.renameTo(File(f.path + ".1"))
                    val w = BufferedWriter(FileWriter(f, true))
                    runCatching { out?.close() }
                    out = w
                    path = f.absolutePath
                    return
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun mem() {
        if (!enabled) return
        val r = Runtime.getRuntime()
        val mb = 1024 * 1024
        i("heap used=${(r.totalMemory() - r.freeMemory()) / mb}MB total=${r.totalMemory() / mb}MB max=${r.maxMemory() / mb}MB " +
            "native=${android.os.Debug.getNativeHeapAllocatedSize() / mb}MB")
    }

    fun d(msg: String, t: Throwable? = null) = log('D', msg, t)
    fun i(msg: String, t: Throwable? = null) = log('I', msg, t)
    fun w(msg: String, t: Throwable? = null) = log('W', msg, t)
    fun e(msg: String, t: Throwable? = null) = log('E', msg, t)

    fun text(maxLines: Int = 800): String = synchronized(lock) { ring.toList().takeLast(maxLines).joinToString("\n") }

    fun clear() = synchronized(lock) { ring.clear() }

    /** Logs begin/end/duration and the full exception chain on failure. */
    inline fun <T> step(name: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        i("> $name")
        try {
            val r = block()
            i("OK $name (${System.currentTimeMillis() - t0}ms)")
            return r
        } catch (ex: Throwable) {
            e("FAIL $name (${System.currentTimeMillis() - t0}ms)", ex)
            throw ex
        }
    }

    private fun log(level: Char, msg: String, t: Throwable?) {
        if (!enabled) return
        val line: String
        synchronized(lock) {
            val sb = StringBuilder()
            sb.append(time.format(Date())).append(' ').append(level)
                .append(" [").append(Thread.currentThread().name).append("] ").append(msg)
            if (t != null) sb.append('\n').append(t.toString()).append('\n').append(Log.getStackTraceString(t))
            line = sb.toString()
            ring.addLast(line)
            while (ring.size > MAX_LINES) ring.removeFirst()
            try {
                out?.apply { write(line); write("\n"); flush() }
            } catch (_: Throwable) {
            }
        }
        when (level) {
            'E' -> Log.e(TAG, msg, t)
            'W' -> Log.w(TAG, msg, t)
            'I' -> Log.i(TAG, msg, t)
            else -> Log.d(TAG, msg, t)
        }
    }

    private fun installHandlers() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            e("CRASH on thread ${t.name}", ex)
            prev?.uncaughtException(t, ex)
        }
        val root = java.util.logging.Logger.getLogger("")
        root.level = Level.INFO
        root.addHandler(object : Handler() {
            private val fmt = SimpleFormatter()
            override fun publish(r: LogRecord) {
                val lvl = when {
                    r.level.intValue() >= Level.SEVERE.intValue() -> 'E'
                    r.level.intValue() >= Level.WARNING.intValue() -> 'W'
                    else -> 'D'
                }
                val msg = try { fmt.formatMessage(r) } catch (_: Throwable) { r.message ?: "" }
                log(lvl, "[jul ${r.loggerName}] $msg", r.thrown)
            }

            override fun flush() {}
            override fun close() {}
        })
        System.setOut(PrintStream(LineStream('I', "stdout"), true))
        System.setErr(PrintStream(LineStream('E', "stderr"), true))
    }

    private class LineStream(val lvl: Char, val tag: String) : OutputStream() {
        private val buf = ByteArrayOutputStream()
        override fun write(b: Int) {
            if (b == '\n'.code) {
                val s = buf.toString("UTF-8").trimEnd('\r')
                buf.reset()
                if (s.isNotBlank()) log(lvl, "[$tag] $s", null)
            } else buf.write(b)
        }
    }
}
