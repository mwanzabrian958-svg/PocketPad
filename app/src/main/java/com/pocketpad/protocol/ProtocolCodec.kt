package com.pocketpad.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ProtocolCodec {
    const val VERSION = 1
    const val PACKET_SIZE = 79
    private const val BODY_SIZE = 47
    private val magic = byteArrayOf('P'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), '1'.code.toByte())

    data class Frame(val sessionId: ByteArray, val sequence: Int, val timestampMillis: Long, val state: ControllerState)

    fun encode(state: ControllerState, sequence: Int, timestampMillis: Long, key: ByteArray): ByteArray {
        require(key.size == 32) { "Pairing key must be 32 bytes." }
        val body = ByteBuffer.allocate(BODY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        body.put(magic)
        body.put(VERSION.toByte())
        body.put(sessionId(key))
        body.putInt(sequence)
        body.putLong(timestampMillis)
        body.putInt(state.buttons)
        body.putShort(axis(state.leftX))
        body.putShort(axis(state.leftY))
        body.putShort(axis(state.rightX))
        body.putShort(axis(state.rightY))
        body.put(trigger(state.leftTrigger))
        body.put(trigger(state.rightTrigger))
        val bytes = body.array()
        return bytes + hmac(key, bytes)
    }

    fun decode(packet: ByteArray, key: ByteArray): Frame {
        require(key.size == 32) { "Pairing key must be 32 bytes." }
        require(packet.size == PACKET_SIZE) { "Invalid packet size." }
        val body = packet.copyOfRange(0, BODY_SIZE)
        val signature = packet.copyOfRange(BODY_SIZE, PACKET_SIZE)
        require(MessageDigest.isEqual(signature, hmac(key, body))) { "Invalid packet signature." }
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val packetMagic = ByteArray(4).also(buffer::get)
        require(packetMagic.contentEquals(magic)) { "Invalid packet header." }
        require(buffer.get().toInt() and 0xFF == VERSION) { "Unsupported protocol version." }
        val session = ByteArray(16).also(buffer::get)
        require(MessageDigest.isEqual(session, sessionId(key))) { "Session does not match." }
        val sequence = buffer.int
        val timestamp = buffer.long
        val buttons = buffer.int
        val state = ControllerState(
            buttons = buttons,
            leftX = buffer.short / 32767f,
            leftY = buffer.short / 32767f,
            rightX = buffer.short / 32767f,
            rightY = buffer.short / 32767f,
            leftTrigger = (buffer.get().toInt() and 0xFF) / 255f,
            rightTrigger = (buffer.get().toInt() and 0xFF) / 255f
        )
        return Frame(session, sequence, timestamp, state)
    }

    fun parseKey(hex: String): ByteArray {
        require(hex.length == 64 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Pairing key must be exactly 64 hexadecimal characters."
        }
        return ByteArray(32) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    fun sessionId(key: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(key).copyOfRange(0, 16)

    private fun axis(value: Float): Short =
        (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private fun trigger(value: Float): Byte =
        (value.coerceIn(0f, 1f) * 255f).toInt().toByte()

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(input)
        }
}
