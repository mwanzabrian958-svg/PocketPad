package com.pocketpad.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolCodecTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun fullStateRoundTrips() {
        val input = ControllerState(
            buttons = 0x1234,
            leftX = -1f,
            leftY = 0.5f,
            rightX = 0.25f,
            rightY = 1f,
            leftTrigger = 0.75f,
            rightTrigger = 1f
        )
        val packet = ProtocolCodec.encode(input, 42, 1_700_000_000_000, key)
        assertEquals(ProtocolCodec.PACKET_SIZE, packet.size)
        val decoded = ProtocolCodec.decode(packet, key)
        assertEquals(42, decoded.sequence)
        assertEquals(1_700_000_000_000, decoded.timestampMillis)
        assertEquals(input.buttons, decoded.state.buttons)
        assertEquals(-1f, decoded.state.leftX, 0.0001f)
        assertEquals(0.5f, decoded.state.leftY, 0.0001f)
        assertEquals(0.75f, decoded.state.leftTrigger, 0.01f)
    }

    @Test
    fun rejectsModifiedPacket() {
        val packet = ProtocolCodec.encode(ControllerState(buttons = 4), 2, 100, key)
        packet[30] = (packet[30].toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.decode(packet, key)
        }
    }

    @Test
    fun deadZoneRemovesSmallMotionAndRescalesTheRest() {
        val state = ControllerState(leftX = 0.1f, leftY = 0.575f).normalized(0.15f)
        assertEquals(0f, state.leftX, 0f)
        assertEquals(0.5f, state.leftY, 0.0001f)
    }
}
