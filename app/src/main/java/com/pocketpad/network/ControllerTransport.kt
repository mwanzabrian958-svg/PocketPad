package com.pocketpad.network

import com.pocketpad.protocol.ControllerState

interface ControllerTransport {
    val method: ConnectionMethod
    var onRumble: ((largeMotor: Int, smallMotor: Int) -> Unit)?
    suspend fun connect(host: String, port: Int, pairingKey: String): Int
    fun send(state: ControllerState)
    fun disconnect()
}

enum class ConnectionMethod(val label: String) {
    AUTO("Auto"),
    USB("USB Cable"),
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth")
}

enum class OptionReadiness { READY, NEEDS_SETUP, UNSUPPORTED }

data class MethodStatus(val method: ConnectionMethod, val readiness: OptionReadiness, val detail: String)
