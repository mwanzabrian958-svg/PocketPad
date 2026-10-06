package com.pocketpad.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import android.content.Context
import android.content.Context.NSD_SERVICE
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.SocketTimeoutException
import kotlin.coroutines.resume

data class DiscoveredCompanion(
    val name: String,
    val host: String,
    val port: Int
)

object CompanionDiscovery {
    const val DISCOVERY_PORT = 26761
    const val REQUEST = "POCKETPAD_DISCOVER_V1"
    private const val RESPONSE_PREFIX = "POCKETPAD_HOST_V1|"
    private const val MDNS_SERVICE_TYPE = "_pocketpad._udp."

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
        val found = linkedMapOf<String, DiscoveredCompanion>()

        val appContext = context.applicationContext
        val nsdManager = appContext.getSystemService(NSD_SERVICE) as? NsdManager
        if (nsdManager != null) {
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val discoveryListener = object : NsdManager.DiscoveryListener {
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onDiscoveryStopped(serviceType: String) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            if (serviceInfo.serviceType.contains("pocketpad")) {
                                runCatching {
                                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                            val host = resolvedInfo.host?.hostAddress
                                            val port = resolvedInfo.port
                                            val name = resolvedInfo.serviceName ?: "PocketPad Host"
                                            if (host != null && port in 1..65535) {
                                                found["$host:$port"] = DiscoveredCompanion(name, host, port)
                                            }
                                        }
                                    })
                                }
                            }
                        }
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                    }
                    nsdManager.discoverServices(MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                    continuation.invokeOnCancellation {
                        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(timeoutMillis.toLong().coerceAtMost(1000L))
                        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            } catch (_: Exception) {}
        }

        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        val multicastLock = wifiManager?.createMulticastLock("PocketPad-discovery")?.apply {
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
                val buffer = ByteArray(256)
                val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
                while (System.nanoTime() < deadline) {
                    val response = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(response)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val payload = String(response.data, response.offset, response.length, Charsets.UTF_8)
                    decodeResponse(payload, response.address.hostAddress.orEmpty())?.let { companion ->
                        found["${companion.host}:${companion.port}"] = companion
                    }
                }
            }
        } finally {
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
        found.values.toList()
    }
}
