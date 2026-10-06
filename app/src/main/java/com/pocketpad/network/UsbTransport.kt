package com.pocketpad.network

import com.pocketpad.protocol.ControllerState
import com.pocketpad.protocol.ProtocolCodec
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsbTransport : ControllerTransport {
    override val method = ConnectionMethod.USB
    @Volatile private var currentState = ControllerState()
    /** When true the input loop throttles to 25 Hz instead of 125 Hz, saving battery. */
    @Volatile var lowPower: Boolean = false
    @Volatile private var socket: Socket? = null
    @Volatile var onFailure: ((Exception) -> Unit)? = null
    @Volatile var onLatency: ((Int) -> Unit)? = null
    override var onRumble: ((largeMotor: Int, smallMotor: Int) -> Unit)? = null
    private val sequence = AtomicInteger()
    private val sentAtNanos = ConcurrentHashMap<Int, Long>()
    private var sender: java.util.concurrent.ScheduledExecutorService? = null
    @Volatile private var receiver: Thread? = null
    @Volatile private var lastResponseNanos = 0L
    private val writeLock = Any()
    private val tickCounter = AtomicInteger()
    @Volatile private var lastSentState = ControllerState()

    override suspend fun connect(host: String, port: Int, pairingKey: String): Int = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "Port must be between 1 and 65535." }
        val secret = ProtocolCodec.parseKey(pairingKey)
        disconnect()
        val tcpSocket = Socket()
        tcpSocket.connect(InetSocketAddress(host, port), 1500)
        tcpSocket.soTimeout = 500
        socket = tcpSocket
        sequence.set(0)
        currentState = ControllerState()
        writeFrame(tcpSocket, secret, currentState)
        val response = ByteArray(ProtocolCodec.PACKET_SIZE + 2)
        tcpSocket.getInputStream().readFully(response)
        val validFrame = response.copyOf(ProtocolCodec.PACKET_SIZE)
        val frame = ProtocolCodec.decode(validFrame, secret)
        require(frame.sequence == 0) { "USB Companion handshake response did not match the request." }
        val latency = requireNotNull(roundTripMillis(validFrame, System.nanoTime())) {
            "USB Companion handshake response did not contain a valid round-trip timestamp."
        }
        lastResponseNanos = System.nanoTime()
        sender = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "PocketPad-USB-Input").apply { priority = Thread.MAX_PRIORITY }
        }.also { executor ->
            executor.scheduleAtFixedRate({
                val active = socket
                if (active != null && active.isConnected && !active.isClosed) {
                    try {
                        synchronized(writeLock) {
                            if (!lowPower || shouldSendThisTick()) writeFrame(active, secret, currentState)
                        }
                    } catch (error: Exception) {
                        fail(error)
                    }
                }
            }, 8L, 8L, TimeUnit.MILLISECONDS)
        }
        receiver = Thread({ receiveReplies(tcpSocket, secret) }, "PocketPad-USB-Feedback").apply {
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
        sentAtNanos.clear()
        currentState = ControllerState()
        // A reconnect must not inherit the previous session's throttle phase, or the first
        // frames of the new link could be skipped while the state still looks unchanged.
        lastSentState = ControllerState()
        tickCounter.set(0)
    }

    private fun receiveReplies(activeSocket: Socket, activeKey: ByteArray) {
        val response = ByteArray(ProtocolCodec.PACKET_SIZE + 2)
        var lastLatencyUpdateNanos = 0L
        while (!activeSocket.isClosed && socket === activeSocket) {
            try {
                activeSocket.getInputStream().readFully(response)
                val validFrame = response.copyOf(ProtocolCodec.PACKET_SIZE)
                ProtocolCodec.decode(validFrame, activeKey)
                val receivedAt = System.nanoTime()
                lastResponseNanos = receivedAt
                val large = response[79].toInt() and 0xFF
                val small = response[80].toInt() and 0xFF
                if (large > 0 || small > 0) onRumble?.invoke(large, small)
                if (receivedAt - lastLatencyUpdateNanos >= TimeUnit.MILLISECONDS.toNanos(250)) {
                    roundTripMillis(validFrame, receivedAt)?.let { onLatency?.invoke(it) }
                    lastLatencyUpdateNanos = receivedAt
                }
            } catch (_: SocketTimeoutException) {
                if (System.nanoTime() - lastResponseNanos >= TimeUnit.SECONDS.toNanos(2)) {
                    fail(SocketTimeoutException("USB Companion stopped responding for more than 2 seconds."))
                    return
                }
            } catch (error: Exception) {
                if (!activeSocket.isClosed) fail(error)
                return
            }
        }
    }

    private fun writeFrame(socket: Socket, key: ByteArray, state: ControllerState) {
        val nextSequence = sequence.getAndIncrement()
        val sentAt = System.nanoTime()
        sentAtNanos[nextSequence] = sentAt
        if (sentAtNanos.size > 256) sentAtNanos.remove(nextSequence - 256)
        val packet = ProtocolCodec.encode(state, nextSequence, System.currentTimeMillis(), key)
        lastSentState = state
        socket.getOutputStream().write(packet)
        socket.getOutputStream().flush()
    }

    private fun roundTripMillis(packet: ByteArray, receivedAtNanos: Long): Int? {
        val responseSequence = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(21)
        }.int
        val sentAt = sentAtNanos.remove(responseSequence) ?: return null
        return TimeUnit.NANOSECONDS.toMillis((receivedAtNanos - sentAt).coerceAtLeast(0)).toInt()
    }

    private fun fail(error: Exception) {
        if (socket == null) return
        disconnect()
        onFailure?.invoke(error)
    }

    /**
     * Low-power mode transmits 1-in-5 ticks (25 Hz instead of 125 Hz), but always sends
     * immediately when the state changed so a button release is never delayed.
     */
    private fun shouldSendThisTick(): Boolean {
        val tick = tickCounter.incrementAndGet()
        return tick % LOW_POWER_TICK_DIVISOR == 0 || currentState != lastSentState
    }

    private companion object {
        const val LOW_POWER_TICK_DIVISOR = 5
    }

    private fun java.io.InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count < 0) throw EOFException("USB Companion closed the controller connection.")
            offset += count
        }
    }
}
