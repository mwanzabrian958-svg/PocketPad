package com.pocketpad.network

import com.pocketpad.protocol.ControllerState
import com.pocketpad.protocol.ProtocolCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WifiTransport : ControllerTransport {
    override val method = ConnectionMethod.WIFI
    @Volatile private var currentState = ControllerState()
    /** When true the input loop transmits one tick in [LOW_POWER_TICK_DIVISOR], saving battery. */
    @Volatile var lowPower: Boolean = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var key: ByteArray? = null
    @Volatile var onFailure: ((Exception) -> Unit)? = null
    @Volatile var onLatency: ((Int) -> Unit)? = null
    override var onRumble: ((largeMotor: Int, smallMotor: Int) -> Unit)? = null
    private val sequence = AtomicInteger()
    private val sentAtNanos = ConcurrentHashMap<Int, Long>()
    private var sender: ScheduledExecutorService? = null
    @Volatile private var receiver: Thread? = null
    @Volatile private var lastResponseNanos = 0L
    private val tickCounter = AtomicInteger()
    @Volatile private var lastSentState = ControllerState()

    override suspend fun connect(host: String, port: Int, pairingKey: String): Int = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "Port must be between 1 and 65535." }
        val secret = ProtocolCodec.parseKey(pairingKey)
        disconnect()
        val address = InetAddress.getByName(host)
        val udpSocket = DatagramSocket().apply {
            soTimeout = 1500
            connect(address, port)
        }
        socket = udpSocket
        key = secret
        sequence.set(0)
        currentState = ControllerState()
        val handshakeSequence = transmit(udpSocket, secret, currentState)
        val response = ByteArray(ProtocolCodec.PACKET_SIZE)
        val packet = DatagramPacket(response, response.size)
        udpSocket.receive(packet)
        val echo = response.copyOf(packet.length)
        val handshakeFrame = ProtocolCodec.decode(echo, secret)
        require(handshakeFrame.sequence == handshakeSequence) { "Companion handshake response did not match the request." }
        val latency = requireNotNull(roundTripMillis(echo, System.nanoTime())) {
            "Companion handshake response did not contain a valid round-trip timestamp."
        }
        udpSocket.soTimeout = 500
        lastResponseNanos = System.nanoTime()
        sender = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PocketPad-Input").apply { priority = Thread.MAX_PRIORITY }
        }.also { executor ->
            executor.scheduleAtFixedRate({
                val activeSocket = socket
                val activeKey = key
                if (activeSocket != null && activeKey != null && !activeSocket.isClosed) {
                    try {
                        if (!lowPower || shouldSendThisTick()) {
                            transmit(activeSocket, activeKey, currentState)
                        }
                    } catch (error: Exception) {
                        fail(error)
                    }
                }
            }, 8L, 8L, TimeUnit.MILLISECONDS)
        }
        receiver = Thread({ receiveReplies(udpSocket, secret) }, "PocketPad-Feedback").apply {
            isDaemon = true
            start()
        }
        latency
    }

    override fun send(state: ControllerState) {
        currentState = state
    }

    override fun disconnect() {
        sender?.shutdownNow()
        sender = null
        receiver = null
        socket?.close()
        socket = null
        key = null
        sentAtNanos.clear()
        currentState = ControllerState()
        // A reconnect must not inherit the previous session's throttle phase, or the first
        // frames of the new link could be skipped while the state still looks unchanged.
        lastSentState = ControllerState()
        tickCounter.set(0)
    }

    private fun receiveReplies(activeSocket: DatagramSocket, activeKey: ByteArray) {
        activeSocket.soTimeout = 250
        val response = ByteArray(128)
        val packet = DatagramPacket(response, response.size)
        var lastLatencyUpdateNanos = 0L
        while (!activeSocket.isClosed && socket === activeSocket) {
            try {
                packet.length = response.size
                activeSocket.receive(packet)
                val bytes = response.copyOf(packet.length)
                val validFrameBytes = if (bytes.size >= ProtocolCodec.PACKET_SIZE) {
                    bytes.copyOf(ProtocolCodec.PACKET_SIZE)
                } else bytes
                val frame = ProtocolCodec.decode(validFrameBytes, activeKey)
                val receivedAt = System.nanoTime()
                lastResponseNanos = receivedAt
                if (bytes.size >= 81) {
                    val large = bytes[79].toInt() and 0xFF
                    val small = bytes[80].toInt() and 0xFF
                    onRumble?.invoke(large, small)
                }
                if (receivedAt - lastLatencyUpdateNanos >= TimeUnit.MILLISECONDS.toNanos(250)) {
                    roundTripMillis(validFrameBytes, receivedAt)?.let { onLatency?.invoke(it) }
                    lastLatencyUpdateNanos = receivedAt
                }
            } catch (_: SocketTimeoutException) {
                if (System.nanoTime() - lastResponseNanos >= TimeUnit.SECONDS.toNanos(2)) {
                    fail(SocketTimeoutException("The Companion stopped responding for more than 2 seconds."))
                    return
                }
            } catch (error: SocketException) {
                if (!activeSocket.isClosed) fail(error)
                return
            } catch (error: Exception) {
                fail(error)
                return
            }
        }
    }

    private fun fail(error: Exception) {
        if (socket == null) return
        disconnect()
        onFailure?.invoke(error)
    }

    /**
     * Low-power mode keeps a base tick running but only transmits every fifth tick
     * (25 Hz instead of 125 Hz). A changed state is always transmitted on the very next
     * tick, so a button release is never delayed.
     */
    private fun shouldSendThisTick(): Boolean {
        val tick = tickCounter.incrementAndGet()
        return tick % LOW_POWER_TICK_DIVISOR == 0 || currentState != lastSentState
    }

    private fun transmit(socket: DatagramSocket, key: ByteArray, state: ControllerState): Int {
        val now = System.currentTimeMillis()
        val nextSequence = sequence.getAndIncrement()
        sentAtNanos[nextSequence] = System.nanoTime()
        if (sentAtNanos.size > 256) {
            val oldest = nextSequence - 256
            sentAtNanos.remove(oldest)
        }
        val bytes = ProtocolCodec.encode(state, nextSequence, now, key)
        lastSentState = state
        socket.send(DatagramPacket(bytes, bytes.size))
        return nextSequence
    }

    private companion object {
        /** Transmit 1-in-5 ticks in low-power mode: 125 Hz base tick to 25 Hz output. */
        const val LOW_POWER_TICK_DIVISOR = 5
    }

    private fun roundTripMillis(packet: ByteArray, receivedAtNanos: Long): Int? {
        val buffer = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.position(21)
        val responseSequence = buffer.int
        val sentAt = sentAtNanos.remove(responseSequence) ?: return null
        return TimeUnit.NANOSECONDS.toMillis((receivedAtNanos - sentAt).coerceAtLeast(0)).toInt()
    }
}
