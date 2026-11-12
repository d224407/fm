package com.example.fm

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** Cooperative cancel flag: Back sets it, long-running loops call [check]. */
object Work {
    @Volatile var cancelled = false

    fun check() { if (cancelled) throw CancellationException("cancelled") }

    /** Buffered copy that can be cancelled between chunks. */
    fun copy(i: InputStream, o: OutputStream) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            check()
            val r = i.read(buf)
            if (r < 0) break
            o.write(buf, 0, r)
        }
    }
}
