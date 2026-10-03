package com.pocketpad.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiscoveredCompanion(
    val name: String,
    val host: String,
    val port: Int
)

object CompanionDiscovery {
    const val DISCOVERY_PORT = 26761
    const val REQUEST = "POCKETPAD_DISCOVER_V1"
    private const val RESPONSE_PREFIX = "POCKETPAD_HOST_V1|"

    fun encodeResponse(name: String, port: Int): String {
        require(name.isNotBlank() && '|' !in name) { "Companion name is invalid." }
        require(port in 1..65535) { "Companion port is invalid." }
        return "$RESPONSE_PREFIX$name|$port"
    }

    fun decodeResponse(payload: String, sourceHost: String): DiscoveredCompanion? {
        val fields = payload.split('|')
        if (fields.size != 3 || fields[0] != "POCKETPAD_HOST_V1" || sourceHost.isBlank()) return null
        val port = fields[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val name = fields[1].takeIf { it.isNotBlank() } ?: return null
        return DiscoveredCompanion(name, sourceHost, port)
    }

    suspend fun scan(context: Context, timeoutMillis: Int = 1800): List<DiscoveredCompanion> = withContext(Dispatchers.IO) {
        require(timeoutMillis in 250..5000) { "Discovery timeout must be between 250 and 5000 ms." }
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: error("Wi-Fi service is unavailable on this device.")
        val multicastLock = wifiManager.createMulticastLock("PocketPad-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 200
            val request = REQUEST.toByteArray(Charsets.UTF_8)
            socket.send(
                DatagramPacket(
                    request,
                    request.size,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT
                )
            )
            val found = linkedMapOf<String, DiscoveredCompanion>()
            val buffer = ByteArray(256)
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (System.nanoTime() < deadline) {
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val payload = String(response.data, response.offset, response.length, Charsets.UTF_8)
                decodeResponse(payload, response.address.hostAddress.orEmpty())?.let { companion ->
                    found["${companion.host}:${companion.port}"] = companion
                }
            }
            found.values.toList()
        }
        } finally {
            if (multicastLock.isHeld) multicastLock.release()
        }
    }
}
