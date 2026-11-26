package dev.minimal.scrcpyclient.control

import java.nio.ByteBuffer

/** scrcpy control-socket messages (big-endian). Layouts from scrcpy's serialize tests. */
object ControlMessages {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2

    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_POWER = 26
    const val KEYCODE_APP_SWITCH = 187

    private const val TYPE_KEYCODE = 0
    private const val TYPE_TOUCH = 2
    private const val TYPE_BACK_OR_SCREEN_ON = 4
    private const val TYPE_SET_DISPLAY_POWER = 10

    /** 32 bytes: type, action, pointerId(8), x, y, screenW(2), screenH(2), pressure(2), actionButton, buttons. */
    fun touch(action: Int, pointerId: Long, x: Int, y: Int, screenW: Int, screenH: Int): ByteArray =
        ByteBuffer.allocate(32)
            .put(TYPE_TOUCH.toByte())
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x).putInt(y)
            .putShort(screenW.toShort()).putShort(screenH.toShort())
            .putShort(if (action == ACTION_UP) 0 else 0xFFFF.toShort())   // pressure 0..1 as u16 fixed point
            .putInt(0)    // action button
            .putInt(0)    // buttons
            .array()

    /** 14 bytes: type, action, keycode, repeat, metaState. */
    fun key(action: Int, keycode: Int): ByteArray =
        ByteBuffer.allocate(14)
            .put(TYPE_KEYCODE.toByte()).put(action.toByte())
            .putInt(keycode).putInt(0).putInt(0)
            .array()

    fun backOrScreenOn(action: Int): ByteArray =
        byteArrayOf(TYPE_BACK_OR_SCREEN_ON.toByte(), action.toByte())

    /** on=false turns the target's display off while the server keeps capturing. */
    fun displayPower(on: Boolean): ByteArray =
        byteArrayOf(TYPE_SET_DISPLAY_POWER.toByte(), if (on) 1 else 0)
}
