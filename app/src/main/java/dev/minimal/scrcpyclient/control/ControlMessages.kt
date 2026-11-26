package dev.minimal.scrcpyclient.control

import java.nio.ByteBuffer

/** scrcpy control-socket messages (big-endian). */
object ControlMessages {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    private const val BUTTON_PRIMARY = 1

    private const val TYPE_TOUCH = 2
    private const val TYPE_SET_DISPLAY_POWER = 10

    /** 32 bytes: type(1), action(1), pointerId(8), x(4), y(4), screenW(2), screenH(2), pressure(2), actionButton(4), buttons(4). */
    fun touch(action: Int, pointerId: Long, x: Int, y: Int, screenW: Int, screenH: Int): ByteArray {
        val isUp = action == ACTION_UP
        val pressure: Short = if (isUp) 0 else 0xFFFF.toShort()
        val actionButton = if (action == ACTION_DOWN || isUp) BUTTON_PRIMARY else 0
        val buttons = if (isUp) 0 else BUTTON_PRIMARY

        return ByteBuffer.allocate(32)
            .put(TYPE_TOUCH.toByte())
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x)
            .putInt(y)
            .putShort(screenW.toShort())
            .putShort(screenH.toShort())
            .putShort(pressure)
            .putInt(actionButton)
            .putInt(buttons)
            .array()
    }

    /** on=false turns the target's display off while the server keeps capturing. */
    fun displayPower(on: Boolean): ByteArray =
        byteArrayOf(TYPE_SET_DISPLAY_POWER.toByte(), if (on) 1 else 0)
}
