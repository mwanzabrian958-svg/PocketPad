package com.pocketpad.network

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.pocketpad.protocol.ControllerState
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BluetoothHidNotSupportedException(message: String) : IllegalStateException(message)

class BluetoothHidTransport(context: Context) : ControllerTransport {
    override val method = ConnectionMethod.BLUETOOTH
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val callbackExecutor: Executor = Executor { command ->
        Handler(Looper.getMainLooper()).post(command)
    }
    @Volatile private var profile: BluetoothHidDevice? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var currentState = ControllerState()
    @Volatile var onFailure: ((Exception) -> Unit)? = null
    @Volatile private var failureReported = AtomicBoolean(false)
    @Volatile private var disconnecting = false
    @Volatile private var profileReady = CompletableFuture<BluetoothHidDevice>()
    @Volatile private var registration = CompletableFuture<Unit>()
    @Volatile private var connection = CompletableFuture<Unit>()
    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
            if (profileId != BluetoothProfile.HID_DEVICE) return
            val hid = proxy as BluetoothHidDevice
            profile = hid
            if (!profileReady.complete(hid)) return
            try {
                val accepted = hid.registerApp(
                    SDP_SETTINGS,
                    null,
                    null,
                    callbackExecutor,
                    hidCallback
                )
                if (!accepted) registration.completeExceptionally(
                    BluetoothHidNotSupportedException("This phone rejected Bluetooth gamepad registration.")
                )
            } catch (error: Exception) {
                registration.completeExceptionally(error)
            }
        }

        override fun onServiceDisconnected(profileId: Int) {
            profile = null
            if (!connection.isDone) connection.completeExceptionally(
                IllegalStateException("Bluetooth gamepad service disconnected.")
            )
            else if (!disconnecting && device != null) {
                reportFailure(IllegalStateException("Bluetooth gamepad service disconnected."))
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) registration.complete(Unit)
            else if (!registration.isDone) {
                registration.completeExceptionally(
                    BluetoothHidNotSupportedException(
                        "This phone's Bluetooth service rejected gamepad mode. Use Wi-Fi or USB instead."
                    )
                )
            } else if (!disconnecting && device != null) {
                reportFailure(IllegalStateException("The phone's Bluetooth gamepad service was stopped."))
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> connection.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (this@BluetoothHidTransport.device?.address != device.address) return
                    if (!connection.isDone) connection.completeExceptionally(
                        IllegalStateException("Bluetooth host disconnected before the gamepad was ready.")
                    )
                    else if (!disconnecting && this@BluetoothHidTransport.device != null) {
                        reportFailure(IllegalStateException("Bluetooth host disconnected."))
                    }
                }
            }
        }
    }

    override suspend fun connect(host: String, port: Int, pairingKey: String): Int = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "Bluetooth gamepad mode requires Android 9 or newer."
        }
        requireBluetoothPermission()
        connection = CompletableFuture()
        val adapter = manager ?: error("This phone does not have Bluetooth.")
        check(adapter.isEnabled) { "Turn on Bluetooth, then try connecting again." }
        require(BluetoothAdapter.checkBluetoothAddress(host)) {
            "Select a paired PC using its Bluetooth address."
        }
        check(appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            "Bluetooth is not supported on this phone."
        }
        try {
            prepareForPairingInternal(adapter)
            val hid = requireNotNull(profile)
            val target = adapter.getRemoteDevice(host)
            device = target
            disconnecting = false
            failureReported.set(false)
            check(hid.connect(target)) {
                "Bluetooth could not connect to this host. Pair it in Android Bluetooth settings first."
            }
            await(connection)
            send(currentState)
            0
        } catch (error: Exception) {
            disconnect()
            throw error
        }
    }

    suspend fun prepareForPairing() = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            "Bluetooth gamepad mode requires Android 9 or newer."
        }
        requireBluetoothPermission()
        val adapter = manager ?: error("This phone does not have Bluetooth.")
        check(adapter.isEnabled) { "Turn on Bluetooth, then try pairing again." }
        disconnecting = false
        prepareForPairingInternal(adapter)
    }

    private fun prepareForPairingInternal(adapter: BluetoothAdapter) {
        if (profile != null && registration.isDone && !registration.isCompletedExceptionally) return
        failureReported = AtomicBoolean(false)
        profileReady = CompletableFuture()
        registration = CompletableFuture()
        connection = CompletableFuture()
        if (!adapter.getProfileProxy(appContext, serviceListener, BluetoothProfile.HID_DEVICE)) {
            throw BluetoothHidNotSupportedException(
                "This phone does not expose the Bluetooth gamepad profile. Use Wi-Fi or USB."
            )
        }
        try {
            await(profileReady)
        } catch (_: TimeoutException) {
            throw BluetoothHidNotSupportedException(
                "This phone's Bluetooth system did not provide the gamepad profile. Use Wi-Fi or USB."
            )
        }
        try {
            await(registration)
        } catch (_: TimeoutException) {
            throw BluetoothHidNotSupportedException(
                "This phone did not register as a Bluetooth gamepad. Use Wi-Fi or USB."
            )
        }
    }

    private fun <T> await(future: CompletableFuture<T>): T =
        try {
            future.get(8, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception ?: error)
        }

    private fun reportFailure(error: Exception) {
        if (failureReported.compareAndSet(false, true)) {
            onFailure?.invoke(error)
            disconnect()
        }
    }

    override fun send(state: ControllerState) {
        currentState = state
        val activeProfile = profile ?: return
        val target = device ?: return
        try {
            requireBluetoothPermission()
            if (!activeProfile.sendReport(target, 1, encodeReport(state))) {
                reportFailure(IllegalStateException("Bluetooth host did not accept controller input."))
            }
        } catch (error: Exception) {
            reportFailure(error)
        }
    }

    override fun disconnect() {
        val hid = profile
        val target = device
        disconnecting = true
        profile = null
        device = null
        if (hid != null) {
            try {
                if (target != null) hid.disconnect(target)
                hid.unregisterApp()
            } catch (_: SecurityException) {
                // Runtime permission may have been revoked while disconnecting.
            } finally {
                manager?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            }
        }
        connection.completeExceptionally(IllegalStateException("Bluetooth connection cancelled."))
        currentState = ControllerState()
    }

    private fun requireBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            check(
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            ) { "Grant Nearby devices permission to connect a Bluetooth gamepad." }
        }
    }

    companion object {
        private val REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x10, 0x81.toByte(), 0x02,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
            0x16, 0x01, 0x80.toByte(), 0x26, 0xFF.toByte(), 0x7F, 0x75, 0x10, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
            0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x00,
            0x75, 0x08, 0x95.toByte(), 0x02, 0x81.toByte(), 0x02, 0xC0.toByte()
        )
        private val SDP_SETTINGS = BluetoothHidDeviceAppSdpSettings(
            "PocketPad Gamepad",
            "Android Bluetooth gamepad",
            "PocketPad",
            0x02,
            REPORT_DESCRIPTOR
        )

        fun encodeReport(state: ControllerState): ByteArray {
            val normalized = state.normalized(0f)
            val report = ByteArray(12)
            report[0] = normalized.buttons.toByte()
            report[1] = (normalized.buttons ushr 8).toByte()
            listOf(normalized.leftX, normalized.leftY, normalized.rightX, normalized.rightY)
                .forEachIndexed { index, value ->
                    val axis = (value.coerceIn(-1f, 1f) * 32767f).toInt()
                    report[2 + index * 2] = axis.toByte()
                    report[3 + index * 2] = (axis shr 8).toByte()
                }
            report[10] = (normalized.leftTrigger * 255f).toInt().toByte()
            report[11] = (normalized.rightTrigger * 255f).toInt().toByte()
            return report
        }

    }
}
