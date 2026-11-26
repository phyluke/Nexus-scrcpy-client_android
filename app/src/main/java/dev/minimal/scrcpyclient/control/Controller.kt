package dev.minimal.scrcpyclient.control

import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/** Sends control messages on a background thread so the UI thread never touches the network. */
class Controller(private val out: OutputStream) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var alive = true

    private val thread = Thread {
        try {
            while (alive) {
                val m = queue.take()
                out.write(m)
                out.flush()
            }
        } catch (_: Exception) { }
    }.apply { isDaemon = true; start() }

    /** [droppable] messages (touch moves) are skipped when the link is congested. */
    fun send(msg: ByteArray, droppable: Boolean = false) {
        if (!alive) return
        if (droppable && queue.size > 40) return
        queue.offer(msg)
    }

    fun close() { alive = false; thread.interrupt() }
}
