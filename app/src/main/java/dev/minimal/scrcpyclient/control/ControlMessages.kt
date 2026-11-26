package dev.minimal.scrcpyclient.control

import java.nio.ByteBuffer

/** scrcpy control-socket messages (big-endian, verified against scrcpy-server bytecode). */
object ControlMessages {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    private const val TYPE_TOUCH = 2
    private const val TYPE_BACK_OR_SCREEN_ON = 4
    private const val TYPE_SET_DISPLAY_POWER = 10

    /** 32 bytes: type(1), action(1), pointerId(8), x(4), y(4), screenW(2), screenH(2), pressure(2), actionButton(4), buttons(4). */
    fun touch(action: Int, pointerId: Long, x: Int, y: Int, screenW: Int, screenH: Int): ByteArray {
        val isUp = action == ACTION_UP
        val pressure: Short = if (isUp) 0 else 0xFFFF.toShort()
        return ByteBuffer.allocate(32)
            .put(TYPE_TOUCH.toByte())
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x)
            .putInt(y)
            .putShort(screenW.toShort())
            .putShort(screenH.toShort())
            .putShort(pressure)
            .putInt(0)
            .putInt(0)
            .array()
    }

    fun backOrScreenOn(action: Int): ByteArray =
        byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), action.toByte())

    fun displayPower(on: Boolean): ByteArray =
        byteArrayOf(TYPE_SET_DISPLAY_POWER.toByte(), if (on) 1 else 0)
}
