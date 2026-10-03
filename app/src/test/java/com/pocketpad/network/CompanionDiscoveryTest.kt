package com.pocketpad.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionDiscoveryTest {
    @Test
    fun decodesHostNameAddressAndPortFromDiscoveryResponse() {
        val response = CompanionDiscovery.encodeResponse("Gaming Desktop", 26760)

        assertEquals(
            DiscoveredCompanion("Gaming Desktop", "192.168.1.12", 26760),
            CompanionDiscovery.decodeResponse(response, "192.168.1.12")
        )
    }

    @Test
    fun ignoresMalformedAndOutOfRangeDiscoveryResponses() {
        assertNull(CompanionDiscovery.decodeResponse("UNRELATED|host|26760", "192.168.1.12"))
        assertNull(CompanionDiscovery.decodeResponse("POCKETPAD_HOST_V1|host|0", "192.168.1.12"))
        assertNull(CompanionDiscovery.decodeResponse("POCKETPAD_HOST_V1||26760", "192.168.1.12"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedCompanionNames() {
        CompanionDiscovery.encodeResponse("Desktop|spoof", 26760)
    }
}
