package com.pocketpad.network

import com.pocketpad.protocol.ControllerState
import com.pocketpad.protocol.ProtocolCodec
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
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
        val server = UsbEchoServer()
        val transport = UsbTransport()
        val feedbackReceived = CountDownLatch(1)
        transport.onLatency = { feedbackReceived.countDown() }

        try {
            transport.connect("127.0.0.1", server.port, pairingKey)
            assertTrue(
                "Expected an echoed authenticated frame to update measured latency",
                feedbackReceived.await(2, TimeUnit.SECONDS)
            )
        } finally {
            transport.disconnect()
            server.close()
        }
    }

    @Test
    fun reportsWhenUsbCompanionClosesTheTcpConnection() = runBlocking {
        val server = UsbEchoServer(echoEveryFrame = false)
        val transport = UsbTransport()
        val failureReceived = CountDownLatch(1)
        transport.onFailure = { failureReceived.countDown() }

        try {
            transport.connect("127.0.0.1", server.port, pairingKey)
            assertTrue(
                "Expected the USB transport to report the closed Companion connection",
                failureReceived.await(2, TimeUnit.SECONDS)
            )
        } finally {
            transport.disconnect()
            server.close()
        }
    }

    @Test
    fun lowPowerInputTransmitsFewerUnchangedFrames() = runBlocking {
        val server = UsbEchoServer()
        val transport = UsbTransport()

        try {
            transport.connect("127.0.0.1", server.port, pairingKey)
            val fullRate = countFramesOver(transport, server.received, changing = false)
            transport.lowPower = true
            val lowPowerRate = countFramesOver(transport, server.received, changing = false)

            assertTrue(
                "Low-power input must transmit less often than the 125 Hz loop (was $lowPowerRate vs $fullRate)",
                lowPowerRate * 2 < fullRate
            )
        } finally {
            transport.disconnect()
            server.close()
        }
    }

    @Test
    fun lowPowerInputStillTransmitsEveryStateChange() = runBlocking {
        val server = UsbEchoServer()
        val transport = UsbTransport()

        try {
            transport.connect("127.0.0.1", server.port, pairingKey)
            val fullRate = countFramesOver(transport, server.received, changing = false)
            transport.lowPower = true
            // A held state may be throttled, but a changed state must never be dropped:
            // otherwise a button release would wait for the next low-power window.
            val changingRate = countFramesOver(transport, server.received, changing = true)
            val heldRate = countFramesOver(transport, server.received, changing = false)

            assertTrue(
                "Constantly changing input must not be throttled (was $changingRate vs full $fullRate)",
                changingRate > fullRate / 2
            )
            assertTrue(
                "Changing input must outrun the throttled held rate ($changingRate vs $heldRate)",
                changingRate > heldRate * 2
            )
        } finally {
            transport.disconnect()
            server.close()
        }
    }

    /**
     * Counts frames the Companion received over a fixed window. When [changing] is set, the
     * controller state is moved faster than the transport's 8 ms tick so that every tick
     * observes a change, which is exactly the case the throttle must never suppress.
     *
     * The driver walks a strictly increasing button mask rather than alternating between two
     * values. An alternating pattern lines up with the 8 ms tick often enough to hand the
     * transport back the value it already sent, which the throttle is right to suppress, so the
     * test would measure the driver's own timing instead of the transport's behaviour.
     *
     * Both phases pace with the same [LockSupport.parkNanos] loop and differ only in whether
     * [send] is called, so they carry the same CPU cost and their rates stay comparable. An
     * earlier version spun here instead: that saturated a core, starved the input thread, and
     * made the scheduler catch up in bursts where several ticks read one unchanged state and
     * were throttled, which showed up as the transport looking broken.
     */
    private fun countFramesOver(
        transport: UsbTransport,
        received: AtomicInteger,
        changing: Boolean
    ): Int {
        Thread.sleep(SETTLE_MILLIS)
        received.set(0)
        val deadline = System.nanoTime() + SAMPLE_MILLIS * 1_000_000L
        var step = 0
        while (System.nanoTime() < deadline) {
            if (changing) transport.send(ControllerState(buttons = ++step))
            LockSupport.parkNanos(CHANGE_INTERVAL_NANOS)
        }
        Thread.sleep(SETTLE_MILLIS)
        return received.get()
    }

    /**
     * Minimal stand-in for the PC Companion's USB loopback listener. It answers the
     * handshake, then either echoes every frame or closes the link after the handshake.
     */
    private class UsbEchoServer(echoEveryFrame: Boolean = true) {
        private val server = ServerSocket(0)
        val received = AtomicInteger()
        private val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    val frame = ByteArray(ProtocolCodec.PACKET_SIZE)
                    while (!socket.isClosed) {
                        input.readFully(frame)
                        received.incrementAndGet()
                        // The handshake must always be answered so connect() succeeds; a
                        // non-echoing Companion drops the link straight after it, which is
                        // the disconnect this mode is meant to simulate.
                        output.write(frame)
                        output.flush()
                        if (!echoEveryFrame) return@use
                    }
                }
            } catch (_: Exception) {
                // Expected once the transport disconnects and closes the socket.
            }
        }

        val port: Int get() = server.localPort

        fun close() {
            server.close()
            worker.join(SETTLE_MILLIS)
        }
    }

    private companion object {
        /** Long enough to drain frames still in flight from the previous phase. */
        const val SETTLE_MILLIS = 60L

        /** Sampling window; at 125 Hz this is ~62 frames versus ~12 when throttled. */
        const val SAMPLE_MILLIS = 500L

        /** Drives the state changes faster than the transports' 8 ms tick. */
        const val CHANGE_INTERVAL_NANOS = 1_000_000L
    }
}
