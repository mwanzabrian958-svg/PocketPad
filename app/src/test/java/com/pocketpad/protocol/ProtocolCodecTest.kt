package com.pocketpad.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        val state = ControllerState(leftX = 0.1f, leftY = 0.575f).shaped(0.15f, 1f)
        assertEquals(0f, state.leftX, 0f)
        assertEquals(0.5f, state.leftY, 0.0001f)
    }

    @Test
    fun fullDeflectionReachesFullScaleAtEverySensitivity() {
        listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f).forEach { sensitivity ->
            val state = ControllerState(rightX = 1f, leftY = -1f).shaped(0.15f, sensitivity)
            assertEquals("sensitivity $sensitivity must still reach full right", 1f, state.rightX, 0.0001f)
            assertEquals("sensitivity $sensitivity must still reach full left", -1f, state.leftY, 0.0001f)
        }
    }

    @Test
    fun lowSensitivityDoesNotFallInsideTheDeadZone() {
        // Scaling before the dead zone used to zero the whole stick at low sensitivity.
        listOf(0f, 0.05f, 0.1f, 0.15f).forEach { sensitivity ->
            val state = ControllerState(leftX = 1f).shaped(0.15f, sensitivity)
            assertTrue("stick dead at sensitivity $sensitivity", state.leftX > 0.5f)
        }
    }

    @Test
    fun motionInsideTheDeadZoneStaysZeroAtAnySensitivity() {
        listOf(0f, 0.5f, 1f).forEach { sensitivity ->
            assertEquals(0f, ControllerState(leftX = 0.05f).shaped(0.15f, sensitivity).leftX, 0f)
        }
    }

    @Test
    fun shapingIsMonotonicAndPreservesStickDirection() {
        val shaped = (0..10).map { step ->
            ControllerState(rightX = step / 10f).shaped(0.15f, 0.75f).rightX
        }
        shaped.zipWithNext().forEach { (lower, higher) ->
            assertTrue("response must not decrease: $lower -> $higher", higher >= lower - 1e-6f)
        }
        val negative = ControllerState(rightX = -0.5f).shaped(0.15f, 0.75f).rightX
        val positive = ControllerState(rightX = 0.5f).shaped(0.15f, 0.75f).rightX
        assertTrue("stick direction must be preserved", negative < 0f && positive > 0f)
        assertEquals("response must be symmetric", positive, -negative, 0.0001f)
    }

    @Test
    fun shapingClampsTriggersIntoRange() {
        val state = ControllerState(leftTrigger = 1.4f, rightTrigger = -0.3f).shaped(0.15f, 0.75f)
        assertEquals(1f, state.leftTrigger, 0f)
        assertEquals(0f, state.rightTrigger, 0f)
    }
}
