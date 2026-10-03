package com.pocketpad.network

import com.pocketpad.protocol.ControllerState
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveConnection(
    val connected: Boolean = false,
    val method: ConnectionMethod? = null,
    val host: String = "",
    val latencyMillis: Int? = null,
    val error: String? = null,
    val notice: String? = null
)

@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val wifiTransport = WifiTransport()
    private val usbTransport = UsbTransport()
    private val bluetoothTransport = BluetoothHidTransport(context)
    @Volatile private var activeTransport: ControllerTransport = wifiTransport
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Target(val method: ConnectionMethod, val host: String, val port: Int, val key: String)
    @Volatile private var autoMode = false
    @Volatile private var autoCandidates: List<Target> = emptyList()
    @Volatile private var failoverJob: Job? = null
    private val _connection = MutableStateFlow(LiveConnection())
    val connection: StateFlow<LiveConnection> = _connection.asStateFlow()

    init {
        val onFailure: (Exception) -> Unit = ::handleTransportFailure
        val onLatency: (Int) -> Unit = { latency ->
            val current = _connection.value
            if (current.connected) _connection.value = current.copy(latencyMillis = latency)
        }
        wifiTransport.onFailure = onFailure
        usbTransport.onFailure = onFailure
        bluetoothTransport.onFailure = onFailure
        wifiTransport.onLatency = onLatency
        usbTransport.onLatency = onLatency
    }

    suspend fun connectWifi(host: String, port: Int, key: String) {
        try {
            disconnect()
            activeTransport = wifiTransport
            val latency = wifiTransport.connect(host.trim(), port, key.trim())
            _connection.value = LiveConnection(
                connected = true,
                method = ConnectionMethod.WIFI,
                host = host.trim(),
                latencyMillis = latency
            )
        } catch (cancelled: CancellationException) {
            wifiTransport.disconnect()
            throw cancelled
        } catch (error: Exception) {
            wifiTransport.disconnect()
            _connection.value = LiveConnection(error = error.message ?: "Connection failed.")
            throw error
        }
    }

    suspend fun connectUsb(key: String) {
        try {
            disconnect()
            activeTransport = usbTransport
            val latency = usbTransport.connect("127.0.0.1", 26762, key.trim())
            _connection.value = LiveConnection(
                connected = true,
                method = ConnectionMethod.USB,
                host = "USB Companion",
                latencyMillis = latency
            )
        } catch (cancelled: CancellationException) {
            usbTransport.disconnect()
            throw cancelled
        } catch (error: Exception) {
            usbTransport.disconnect()
            _connection.value = LiveConnection(error = error.message ?: "USB Companion connection failed.")
            throw error
        }
    }

    suspend fun connectBluetooth(hostAddress: String) {
        try {
            disconnect()
            activeTransport = bluetoothTransport
            bluetoothTransport.connect(hostAddress.trim(), 0, "")
            _connection.value = LiveConnection(
                connected = true,
                method = ConnectionMethod.BLUETOOTH,
                host = hostAddress.trim()
            )
        } catch (cancelled: CancellationException) {
            bluetoothTransport.disconnect()
            throw cancelled
        } catch (error: Exception) {
            bluetoothTransport.disconnect()
            _connection.value = LiveConnection(error = error.message ?: "Bluetooth gamepad connection failed.")
            throw error
        }
    }

    suspend fun connectAuto(
        wifiHost: String,
        wifiPort: Int,
        pairingKey: String,
        bluetoothHost: String?
    ) {
        disconnect()
        val candidates = buildList {
            add(Target(ConnectionMethod.USB, "127.0.0.1", 26762, pairingKey))
            if (wifiHost.isNotBlank() && wifiPort in 1..65535 && pairingKey.isNotBlank()) {
                add(Target(ConnectionMethod.WIFI, wifiHost.trim(), wifiPort, pairingKey.trim()))
            }
            if (!bluetoothHost.isNullOrBlank()) {
                add(Target(ConnectionMethod.BLUETOOTH, bluetoothHost, 0, ""))
            }
        }
        require(candidates.isNotEmpty()) { "Choose a Companion host or pair a Bluetooth gamepad host first." }
        autoMode = true
        val failures = mutableListOf<String>()
        for ((index, target) in candidates.withIndex()) {
            try {
                connectTarget(target)
                autoCandidates = candidates.drop(index + 1)
                return
            } catch (cancelled: CancellationException) {
                disconnectTransports()
                autoMode = false
                autoCandidates = emptyList()
                throw cancelled
            } catch (error: Exception) {
                failures += "${target.method.label}: ${error.message ?: "not available"}"
                disconnectTransports()
            }
        }
        autoMode = false
        autoCandidates = emptyList()
        val detail = failures.joinToString("; ")
        _connection.value = LiveConnection(error = "No automatic connection method succeeded. $detail")
        throw IllegalStateException("No automatic connection method succeeded. $detail")
    }

    private suspend fun connectTarget(target: Target) {
        when (target.method) {
            ConnectionMethod.USB -> {
                activeTransport = usbTransport
                val latency = usbTransport.connect(target.host, target.port, target.key)
                _connection.value = LiveConnection(true, ConnectionMethod.USB, "USB Companion", latency)
            }
            ConnectionMethod.WIFI -> {
                activeTransport = wifiTransport
                val latency = wifiTransport.connect(target.host, target.port, target.key)
                _connection.value = LiveConnection(true, ConnectionMethod.WIFI, target.host, latency)
            }
            ConnectionMethod.BLUETOOTH -> {
                activeTransport = bluetoothTransport
                bluetoothTransport.connect(target.host, 0, "")
                _connection.value = LiveConnection(true, ConnectionMethod.BLUETOOTH, target.host)
            }
            ConnectionMethod.AUTO -> error("Auto is a selection policy, not a transport.")
        }
    }

    @Synchronized
    private fun handleTransportFailure(error: Exception) {
        if (autoMode && failoverJob?.isActive != true) {
            val job = scope.launch {
                val priorMethod = _connection.value.method
                val failures = mutableListOf("${priorMethod?.label ?: "Active"}: ${error.message}")
                try {
                    while (autoMode && autoCandidates.isNotEmpty()) {
                        val target = autoCandidates.first()
                        autoCandidates = autoCandidates.drop(1)
                        disconnectTransports()
                        try {
                            connectTarget(target)
                            if (!autoMode) {
                                disconnectTransports()
                                return@launch
                            }
                            _connection.value = _connection.value.copy(
                                notice = "Connection switched to ${target.method.label}."
                            )
                            scope.launch {
                                delay(2_000)
                                if (_connection.value.notice == "Connection switched to ${target.method.label}.") {
                                    _connection.value = _connection.value.copy(notice = null)
                                }
                            }
                            return@launch
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (fallbackError: Exception) {
                            failures += "${target.method.label}: ${fallbackError.message ?: "not available"}"
                        }
                    }
                    if (autoMode) {
                        autoMode = false
                        _connection.value = LiveConnection(
                            error = "Connection lost and no automatic fallback succeeded. ${failures.joinToString("; ")}"
                        )
                    }
                } finally {
                    if (failoverJob === coroutineContext[Job]) failoverJob = null
                }
            }
            failoverJob = job
        } else {
            if (!autoMode) {
                val method = _connection.value.method?.label ?: "gamepad"
                _connection.value = LiveConnection(error = error.message ?: "$method connection was lost.")
            }
        }
    }

    suspend fun prepareBluetoothPairing() {
        bluetoothTransport.prepareForPairing()
    }

    fun updateInput(state: ControllerState) {
        activeTransport.send(state)
    }

    fun disconnect() {
        autoMode = false
        autoCandidates = emptyList()
        failoverJob?.cancel()
        failoverJob = null
        disconnectTransports()
        _connection.value = LiveConnection()
    }

    private fun disconnectTransports() {
        wifiTransport.disconnect()
        usbTransport.disconnect()
        bluetoothTransport.disconnect()
    }
}
