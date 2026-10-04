package com.pocketpad.network

import com.pocketpad.protocol.ProtocolCodec
import com.pocketpad.protocol.ControllerState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
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
        val serverThread = echoServer(server, shouldReply = { true })
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
        val serverThread = echoServer(server, shouldReply = { replyToEveryPacket.getAndSet(false) })
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

    @Test
    fun lowPowerInputTransmitsFewerUnchangedFrames() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val received = AtomicInteger()
        val serverThread = echoServer(server, { true }, received)
        val transport = WifiTransport()

        try {
            transport.connect("127.0.0.1", server.localPort, pairingKey)
            val fullRate = countFramesOver(transport, received, changing = false)
            transport.lowPower = true
            val lowPowerRate = countFramesOver(transport, received, changing = false)

            assertTrue(
                "Low-power input must transmit less often than the 125 Hz loop (was $lowPowerRate vs $fullRate)",
                lowPowerRate * 2 < fullRate
            )
        } finally {
            transport.disconnect()
            server.close()
            serverThread.join(500)
        }
    }

    @Test
    fun lowPowerInputStillTransmitsEveryStateChange() = runBlocking {
        val server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val received = AtomicInteger()
        val serverThread = echoServer(server, { true }, received)
        val transport = WifiTransport()

        try {
            transport.connect("127.0.0.1", server.localPort, pairingKey)
            val fullRate = countFramesOver(transport, received, changing = false)
            transport.lowPower = true
            // A held state may be throttled, but a changed state must never be dropped:
            // otherwise a button release would wait for the next low-power window.
            val changingRate = countFramesOver(transport, received, changing = true)
            val heldRate = countFramesOver(transport, received, changing = false)

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
            serverThread.join(500)
        }
    }

    /**
     * Counts datagrams the Companion received over a fixed window. When [changing] is set, the
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
        transport: WifiTransport,
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

    private fun echoServer(
        server: DatagramSocket,
        shouldReply: () -> Boolean,
        received: AtomicInteger? = null
    ) = thread(isDaemon = true) {
        val buffer = ByteArray(ProtocolCodec.PACKET_SIZE)
        try {
            while (!server.isClosed) {
                val request = DatagramPacket(buffer, buffer.size)
                server.receive(request)
                val packet = buffer.copyOf(request.length)
                ProtocolCodec.decode(packet, ProtocolCodec.parseKey(pairingKey))
                received?.incrementAndGet()
                if (shouldReply()) {
                    server.send(DatagramPacket(packet, packet.size, request.address, request.port))
                }
            }
        } catch (_: Exception) {
            if (!server.isClosed) throw AssertionError("Loopback Companion failed unexpectedly.")
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
