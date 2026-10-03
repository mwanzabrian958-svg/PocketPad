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
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var key: ByteArray? = null
    @Volatile var onFailure: ((Exception) -> Unit)? = null
    @Volatile var onLatency: ((Int) -> Unit)? = null
    private val sequence = AtomicInteger()
    private val sentAtNanos = ConcurrentHashMap<Int, Long>()
    private var sender: ScheduledExecutorService? = null
    @Volatile private var receiver: Thread? = null
    @Volatile private var lastResponseNanos = 0L

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
                        transmit(activeSocket, activeKey, currentState)
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
    }

    private fun receiveReplies(activeSocket: DatagramSocket, activeKey: ByteArray) {
        activeSocket.soTimeout = 250
        val response = ByteArray(ProtocolCodec.PACKET_SIZE)
        val packet = DatagramPacket(response, response.size)
        var lastLatencyUpdateNanos = 0L
        while (!activeSocket.isClosed && socket === activeSocket) {
            try {
                packet.length = response.size
                activeSocket.receive(packet)
                val bytes = response.copyOf(packet.length)
                val frame = ProtocolCodec.decode(bytes, activeKey)
                val receivedAt = System.nanoTime()
                lastResponseNanos = receivedAt
                if (receivedAt - lastLatencyUpdateNanos >= TimeUnit.MILLISECONDS.toNanos(250)) {
                    roundTripMillis(bytes, receivedAt)?.let { onLatency?.invoke(it) }
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

    private fun transmit(socket: DatagramSocket, key: ByteArray, state: ControllerState): Int {
        val now = System.currentTimeMillis()
        val nextSequence = sequence.getAndIncrement()
        sentAtNanos[nextSequence] = System.nanoTime()
        if (sentAtNanos.size > 256) {
            val oldest = nextSequence - 256
            sentAtNanos.remove(oldest)
        }
        val bytes = ProtocolCodec.encode(state, nextSequence, now, key)
        socket.send(DatagramPacket(bytes, bytes.size))
        return nextSequence
    }

    private fun roundTripMillis(packet: ByteArray, receivedAtNanos: Long): Int? {
        val buffer = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.position(21)
        val responseSequence = buffer.int
        val sentAt = sentAtNanos.remove(responseSequence) ?: return null
        return TimeUnit.NANOSECONDS.toMillis((receivedAtNanos - sentAt).coerceAtLeast(0)).toInt()
    }
}
