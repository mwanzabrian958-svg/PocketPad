package com.pocketpad.network

import com.pocketpad.protocol.ProtocolCodec
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransportTest {
    private val pairingKey = ByteArray(32) { it.toByte() }.joinToString("") {
        "%02x".format(it.toInt() and 0xFF)
    }

    @Test
    fun receivesLatencyFeedbackOverAuthenticatedTcpFrames() = runBlocking {
        val server = ServerSocket(0)
        val echoThread = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    val frame = ByteArray(ProtocolCodec.PACKET_SIZE)
                    while (!socket.isClosed) {
                        input.readFully(frame)
                        output.write(frame)
                        output.flush()
                    }

                    @Test
                    fun reportsWhenUsbCompanionClosesTheTcpConnection() = runBlocking {
                        val server = ServerSocket(0)
                        val serverThread = thread(isDaemon = true) {
                            try {
                                server.accept().use { socket ->
                                    val input = DataInputStream(socket.getInputStream())
                                    val frame = ByteArray(ProtocolCodec.PACKET_SIZE)
                                    input.readFully(frame)
                                    socket.getOutputStream().write(frame)
                                    socket.getOutputStream().flush()
                                }
                            } catch (_: Exception) {
                                if (!server.isClosed) throw AssertionError("Loopback USB Companion failed unexpectedly.")
                            }
                        }
                        val transport = UsbTransport()
                        val failureReceived = CountDownLatch(1)
                        transport.onFailure = { failureReceived.countDown() }

                        try {
                            transport.connect("127.0.0.1", server.localPort, pairingKey)
                            assertTrue(
                                "Expected the USB transport to report the closed Companion connection",
                                failureReceived.await(2, TimeUnit.SECONDS)
                            )
                        } finally {
                            transport.disconnect()
                            server.close()
                            serverThread.join(500)
                        }
                    }
                }
            } catch (_: Exception) {
                if (!server.isClosed) throw AssertionError("Loopback USB Companion failed unexpectedly.")
            }
        }
        val transport = UsbTransport()
        val feedbackReceived = CountDownLatch(1)
        transport.onLatency = { feedbackReceived.countDown() }

        try {
            transport.connect("127.0.0.1", server.localPort, pairingKey)
            assertTrue(
                "Expected an echoed authenticated frame to update measured latency",
                feedbackReceived.await(2, TimeUnit.SECONDS)
            )
        } finally {
            transport.disconnect()
            server.close()
            echoThread.join(500)
        }
    }
}
