package com.pocketpad.network

import com.pocketpad.protocol.ControllerState
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothHidTransportTest {
    @Test
    fun encodesButtonsAxesAndTriggersIntoTwelveByteHidReport() {
        val report = BluetoothHidTransport.encodeReport(
            ControllerState(
                buttons = 0x1234,
                leftX = -1f,
                leftY = 0.5f,
                rightX = 0f,
                rightY = 1f,
                leftTrigger = 0.25f,
                rightTrigger = 1f
            )
        )

        assertEquals(12, report.size)
        assertEquals(0x34, report[0].toInt() and 0xFF)
        assertEquals(0x12, report[1].toInt() and 0xFF)
        assertEquals(0x01, report[2].toInt() and 0xFF)
        assertEquals(0x80, report[3].toInt() and 0xFF)
        assertEquals(0x3F, report[5].toInt() and 0xFF)
        assertEquals(63, report[10].toInt() and 0xFF)
        assertEquals(255, report[11].toInt() and 0xFF)
    }

    @Test
    fun clampsHidReportFieldsToDescriptorRanges() {
        val report = BluetoothHidTransport.encodeReport(
            ControllerState(
                buttons = -1,
                leftX = 3f,
                leftY = -3f,
                rightX = Float.NaN,
                rightY = 0f,
                leftTrigger = -2f,
                rightTrigger = 2f
            )
        )

        assertEquals(0xFF, report[0].toInt() and 0xFF)
        assertEquals(0xFF, report[1].toInt() and 0xFF)
        assertEquals(0xFF, report[2].toInt() and 0xFF)
        assertEquals(0x7F, report[3].toInt() and 0xFF)
        assertEquals(0x00, report[10].toInt() and 0xFF)
        assertEquals(0xFF, report[11].toInt() and 0xFF)
    }
}
