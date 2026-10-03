package com.pocketpad.network

import com.pocketpad.protocol.ProtocolCodec
import com.pocketpad.protocol.ControllerState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiTransportTest {
    private val pairingKey = ByteArray(32) { it.toByte() }.joinToString("") {
        "%02x".format(it.toInt() and 0xFF)
    }

    @Test
    fun receivesLiveLatencyFeedback() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val serverThread = echoServer(server) { true }
        val transport = WifiTransport()
        val feedbackReceived = CountDownLatch(1)
        transport.onLatency = { feedbackReceived.countDown() }

        try {
            transport.connect("127.0.0.1", server.localPort, pairingKey)
            assertTrue("Expected a measured controller round-trip", feedbackReceived.await(2, TimeUnit.SECONDS))
        } finally {
            transport.disconnect()
            server.close()
            serverThread.join(500)
        }
    }

    @Test
    fun reportsConnectionLostWhenCompanionStopsReplying() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val replyToEveryPacket = AtomicBoolean(true)
        val serverThread = echoServer(server) { replyToEveryPacket.getAndSet(false) }
        val transport = WifiTransport()
        val failureReceived = CountDownLatch(1)
        transport.onFailure = { failureReceived.countDown() }

        try {
            transport.connect("127.0.0.1", server.localPort, pairingKey)
            assertTrue(
                "Expected the dropped host to be reported within the failover window",
                failureReceived.await(3, TimeUnit.SECONDS)
            )
        } finally {
            transport.disconnect()
            server.close()
            serverThread.join(500)
        }
    }

    private fun echoServer(server: DatagramSocket, shouldReply: () -> Boolean) = thread(isDaemon = true) {
        val buffer = ByteArray(ProtocolCodec.PACKET_SIZE)
        try {
            while (!server.isClosed) {
                val request = DatagramPacket(buffer, buffer.size)
                server.receive(request)
                val packet = buffer.copyOf(request.length)
                ProtocolCodec.decode(packet, ProtocolCodec.parseKey(pairingKey))
                if (shouldReply()) {
                    server.send(DatagramPacket(packet, packet.size, request.address, request.port))
                }
            }
        } catch (_: Exception) {
            if (!server.isClosed) throw AssertionError("Loopback Companion failed unexpectedly.")
        }
    }
}
