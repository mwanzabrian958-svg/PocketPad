package com.pocketpad.data.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonMappingTest {
    @Test
    fun remappingSwapsOutputsAndKeepsMappingBijective() {
        val remapped = ButtonMapping.reassign(ButtonMapping.identity(), 4, 7)

        assertEquals(7, remapped[4])
        assertEquals(4, remapped[7])
        assertEquals(15, remapped.values.toSet().size)
    }

    @Test
    fun translatesMultipleSimultaneouslyPressedButtons() {
        val mapping = ButtonMapping.reassign(ButtonMapping.identity(), 4, 7)

        assertEquals((1 shl 7) or (1 shl 5), ButtonMapping.translate((1 shl 4) or (1 shl 5), mapping))
    }

    @Test
    fun profileMappingRoundTrips() {
        val mapping = ButtonMapping.reassign(ButtonMapping.identity(), 10, 12)

        assertEquals(mapping, ButtonMapping.decode(ButtonMapping.encode(mapping)))
    }
}
