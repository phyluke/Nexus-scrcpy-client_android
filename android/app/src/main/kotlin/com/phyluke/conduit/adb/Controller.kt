package com.phyluke.conduit.adb

import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/** Sends control messages on a background thread with low-latency coalescing. */
class Controller(private val out: OutputStream) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var alive = true

    private val thread = Thread {
        try {
            while (alive) {
                val msg = queue.take()
                out.write(msg)
                while (true) {
                    val next = queue.poll() ?: break
                    out.write(next)
                }
                out.flush()
            }
        } catch (_: Exception) { }
    }.apply { isDaemon = true; start() }

    /** [droppable] messages (touch moves) are skipped as soon as 4+ packets are queued. */
    fun send(msg: ByteArray, droppable: Boolean = false) {
        if (!alive) return
        if (droppable && queue.size >= 4) return
        queue.offer(msg)
    }

    fun close() {
        alive = false
        thread.interrupt()
    }
}
