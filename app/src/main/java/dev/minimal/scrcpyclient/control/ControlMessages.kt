package dev.minimal.scrcpyclient.control

import java.nio.ByteBuffer

/** scrcpy control-socket messages (big-endian). */
object ControlMessages {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    const val KEYCODE_WAKEUP = 224

    // scrcpy special pointer IDs: -1L = mouse, -2L = generic finger touch
    private const val POINTER_ID_GENERIC_FINGER = -2L

    private const val TYPE_KEYCODE = 0
    private const val TYPE_TOUCH = 2
    private const val TYPE_SET_DISPLAY_POWER = 10

    /** 32 bytes: type(1), action(1), pointerId(8), x(4), y(4), screenW(2), screenH(2), pressure(2), actionButton(4), buttons(4). */
    fun touch(action: Int, pointerIndex: Int, x: Int, y: Int, screenW: Int, screenH: Int): ByteArray {
        val isUp = action == ACTION_UP
        val pressure: Short = if (isUp) 0 else 0xFFFF.toShort()
        val ptrId = if (pointerIndex == 0) POINTER_ID_GENERIC_FINGER else pointerIndex.toLong()

        return ByteBuffer.allocate(32)
            .put(TYPE_TOUCH.toByte())
            .put(action.toByte())
            .putLong(ptrId)
            .putInt(x)
            .putInt(y)
            .putShort(screenW.toShort())
            .putShort(screenH.toShort())
            .putShort(pressure)
            .putInt(0) // actionButton (0 for finger touch)
            .putInt(0) // buttons (0 for finger touch)
            .array()
    }

    /** 14 bytes: type(1), action(1), keycode(4), repeat(4), metaState(4). */
    fun key(action: Int, keycode: Int): ByteArray =
        ByteBuffer.allocate(14)
            .put(TYPE_KEYCODE.toByte())
            .put(action.toByte())
            .putInt(keycode)
            .putInt(0)
            .putInt(0)
            .array()

    /** on=false turns the target's display off while the server keeps capturing. */
    fun displayPower(on: Boolean): ByteArray =
        byteArrayOf(TYPE_SET_DISPLAY_POWER.toByte(), if (on) 1 else 0)
}
