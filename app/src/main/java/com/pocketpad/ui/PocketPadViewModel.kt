package com.pocketpad.ui

import android.os.Build
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketpad.network.CompanionDiscovery
import com.pocketpad.network.DiscoveredCompanion
import com.pocketpad.network.BluetoothHidNotSupportedException
import com.pocketpad.data.settings.AppSettings
import com.pocketpad.data.settings.SettingsRepository
import com.pocketpad.data.profile.ProfileDao
import com.pocketpad.data.profile.ProfileEntity
import com.pocketpad.data.profile.ButtonMapping
import com.pocketpad.network.ConnectionManager
import com.pocketpad.network.ConnectionMethod
import com.pocketpad.network.LiveConnection
import com.pocketpad.network.MethodStatus
import com.pocketpad.network.OptionReadiness
import com.pocketpad.service.ConnectionService
import com.pocketpad.protocol.ControllerState
import com.pocketpad.protocol.ProtocolCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class ConnectionPickerState(
    val selectedMethod: ConnectionMethod? = null,
    val statuses: List<MethodStatus> = emptyList(),
    val host: String = "",
    val bluetoothHostAddress: String = "",
    val port: String = "26760",
    val pairingKey: String = "",
    val qrText: String = "",
    val discoveredHosts: List<DiscoveredCompanion> = emptyList(),
    val pairedBluetoothHosts: List<Pair<String, String>> = emptyList(),
    val scanningHosts: Boolean = false,
    val usbCableConnected: Boolean = false,
    val rememberChoice: Boolean = true,
    val connecting: Boolean = false,
    val error: String? = null,
    val fixAction: String? = null
)

@HiltViewModel
class PocketPadViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    private val profileDao: ProfileDao,
    private val connectionManager: ConnectionManager
) : ViewModel() {
    private val appContext = application.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Vibrator::class.java)
    }
    private var toneGenerator: ToneGenerator? = null
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings()
    )
    val connection: StateFlow<LiveConnection> = connectionManager.connection
    val profiles: StateFlow<List<ProfileEntity>> = profileDao.observeProfiles().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val _picker = MutableStateFlow(ConnectionPickerState())
    val picker: StateFlow<ConnectionPickerState> = _picker.asStateFlow()

    private val _input = MutableStateFlow(ControllerState())
    val input: StateFlow<ControllerState> = _input.asStateFlow()
    private val turboJobs = mutableMapOf<Int, Job>()
    private var connectJob: Job? = null
    private val _activeProfile = MutableStateFlow("Default")
    val activeProfile: StateFlow<String> = _activeProfile.asStateFlow()
    private val _buttonMapping = MutableStateFlow(ButtonMapping.identity())
    val buttonMapping: StateFlow<Map<Int, Int>> = _buttonMapping.asStateFlow()
    private var pressedSourceButtons = 0
    private val turboReleased = mutableMapOf<Int, AtomicBoolean>()

    init {
        refreshMethodStatuses(false, false)
        connectionManager.onRumble = ::playRumble
        viewModelScope.launch {
            // Keep the transports in sync with the saved low-power preference.
            settings.map { it.lowPower }.distinctUntilChanged().collect { enabled ->
                connectionManager.setLowPower(enabled)
            }
        }
        viewModelScope.launch {
            connection.map { it.connected to it.method }
                .distinctUntilChanged()
                .collect {
                    refreshMethodStatuses(connection.value.method == ConnectionMethod.WIFI, false)
                }
        }
        viewModelScope.launch {
            connection.map { it.connected }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
                        runCatching { ConnectionService.startService(appContext) }
                    } else {
                        runCatching { ConnectionService.stopService(appContext) }
                    }
                }
        }
        viewModelScope.launch {
            var previousConnected = false
            connection.map { it.connected }.distinctUntilChanged().collect { connected ->
                if (connected && !previousConnected) {
                    playConnectSuccessSound()
                }
                previousConnected = connected
            }
        }
        viewModelScope.launch {
            listOf(
                ProfileEntity(name = "Default", sensitivity = 0.75f, deadZone = 0.15f),
                ProfileEntity(name = "FPS", sensitivity = 0.85f, deadZone = 0.08f),
                ProfileEntity(name = "Racing", sensitivity = 0.65f, deadZone = 0.05f)
            ).forEach { profile ->
                if (profileDao.getProfile(profile.name) == null) profileDao.save(profile)
            }
            val current = settingsRepository.settings.first()
            val remembered = ConnectionMethod.entries.firstOrNull {
                it.label == current.rememberedMethod
            }
            _picker.value = _picker.value.copy(
                selectedMethod = remembered,
                rememberChoice = current.rememberConnection,
                host = current.rememberedHost,
                port = current.rememberedPort.takeIf { it in 1..65535 }?.toString() ?: "26760"
            )
            if (current.rememberConnection &&
                (remembered == ConnectionMethod.WIFI || remembered == ConnectionMethod.USB ||
                    remembered == ConnectionMethod.AUTO || remembered == ConnectionMethod.BLUETOOTH)
            ) {
                val saved = settingsRepository.loadRememberedConnection()
                if (saved != null) {
                    val useUsb = remembered == ConnectionMethod.USB
                    _picker.value = _picker.value.copy(
                        selectedMethod = remembered,
                        host = saved.host,
                        bluetoothHostAddress = if (remembered == ConnectionMethod.BLUETOOTH) saved.host else "",
                        port = saved.port.takeIf { it in 1..65535 }?.toString() ?: "26760",
                        pairingKey = saved.pairingKey,
                        connecting = true
                    )
                    try {
                        if (useUsb) connectionManager.connectUsb(saved.pairingKey)
                        else if (remembered == ConnectionMethod.BLUETOOTH) {
                            connectionManager.connectBluetooth(saved.host)
                        }
                        else if (remembered == ConnectionMethod.AUTO) {
                            connectionManager.connectAuto(
                                saved.host,
                                saved.port,
                                saved.pairingKey,
                                _picker.value.bluetoothHostAddress.ifBlank {
                                    _picker.value.pairedBluetoothHosts.firstOrNull()?.second.orEmpty()
                                }.ifBlank { null }
                            )
                        } else connectionManager.connectWifi(saved.host, saved.port, saved.pairingKey)
                        _picker.value = _picker.value.copy(connecting = false)
                        refreshMethodStatuses(connection.value.method == ConnectionMethod.WIFI, false)
                    } catch (error: Exception) {
                        _picker.value = _picker.value.copy(
                            connecting = false,
                            error = error.message ?: "Could not reconnect to the saved gaming host.",
                            fixAction = "Check that the host is online and on the same network."
                        )
                    }
                }
            }
        }
    }

    fun refreshMethodStatuses(wifiConnected: Boolean, bluetoothEnabled: Boolean) {
        val bluetoothAdapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val bluetoothSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
            bluetoothAdapter != null
        val isBluetoothEnabled = runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)
        val statuses = listOf(
            MethodStatus(
                ConnectionMethod.AUTO,
                if (wifiConnected || connection.value.connected) OptionReadiness.READY else OptionReadiness.NEEDS_SETUP,
                "Tries USB, then Wi-Fi, then a paired Bluetooth host; switches if the active link drops."
            ),
            MethodStatus(
                ConnectionMethod.USB,
                if (connection.value.method == ConnectionMethod.USB) OptionReadiness.READY else OptionReadiness.NEEDS_SETUP,
                "Use the PC Companion's Set up USB cable (ADB) button, then paste its pairing link."
            ),
            MethodStatus(
                ConnectionMethod.WIFI,
                if (connection.value.method == ConnectionMethod.WIFI) OptionReadiness.READY else OptionReadiness.NEEDS_SETUP,
                if (wifiConnected) "Wi-Fi is connected. Enter your PC details to pair."
                else "Join the PC's network and start the PocketPad PC Companion."
            ),
            MethodStatus(
                ConnectionMethod.BLUETOOTH,
                if (!bluetoothSupported) OptionReadiness.UNSUPPORTED
                else if (connection.value.method == ConnectionMethod.BLUETOOTH) OptionReadiness.READY
                else OptionReadiness.NEEDS_SETUP,
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> "Bluetooth gamepad HID requires Android 9 or newer."
                    !bluetoothSupported -> "This phone does not expose the Bluetooth gamepad profile."
                    !isBluetoothEnabled -> "Turn on Bluetooth, make the phone visible, then pair the PC."
                    else -> "Pair the gaming PC in Bluetooth settings, then select its saved device below."
                }
            )
        )
        _picker.value = _picker.value.copy(statuses = statuses)
    }

    fun selectMethod(method: ConnectionMethod) {
        val status = _picker.value.statuses.firstOrNull { it.method == method }
        if (status?.readiness == OptionReadiness.UNSUPPORTED) return
        _picker.value = _picker.value.copy(selectedMethod = method, error = null, fixAction = null)
    }

    fun updateHost(value: String) {
        _picker.value = _picker.value.copy(host = value, error = null)
    }

    fun updatePort(value: String) {
        if (value.all(Char::isDigit) && value.length <= 5) {
            _picker.value = _picker.value.copy(port = value, error = null)
        }
    }

    fun updatePairingKey(value: String) {
        _picker.value = _picker.value.copy(pairingKey = value.trim(), error = null)
    }

    fun updateQrText(value: String) {
        _picker.value = _picker.value.copy(qrText = value)
        parseCompanionText(value)
    }

    fun findHosts() {
        if (_picker.value.scanningHosts) return
        _picker.value = _picker.value.copy(scanningHosts = true, discoveredHosts = emptyList(), error = null)
        viewModelScope.launch {
            try {
                val found = CompanionDiscovery.scan(appContext)
                _picker.value = _picker.value.copy(
                    scanningHosts = false,
                    discoveredHosts = found,
                    error = if (found.isEmpty()) "No PocketPad hosts found. Check Wi-Fi and the host firewall." else null,
                    fixAction = if (found.isEmpty()) "Keep both devices on the same network, then scan again." else null
                )
            } catch (error: Exception) {
                _picker.value = _picker.value.copy(
                    scanningHosts = false,
                    error = error.message ?: "Network discovery failed.",
                    fixAction = "Check Wi-Fi permission and the local network."
                )
            }
        }
    }

    fun selectDiscoveredHost(host: DiscoveredCompanion) {
        _picker.value = _picker.value.copy(
            host = host.host,
            port = host.port.toString(),
            selectedMethod = ConnectionMethod.WIFI,
            error = null,
            fixAction = null
        )
    }

    fun loadPairedBluetoothHosts() {
        try {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?: error("This phone does not have Bluetooth.")
            val paired = adapter.bondedDevices
                .map { device -> (device.name ?: device.address) to device.address }
                .sortedBy { it.first.lowercase() }
            _picker.value = _picker.value.copy(pairedBluetoothHosts = paired, error = null)
        } catch (error: SecurityException) {
            _picker.value = _picker.value.copy(
                error = "Grant Nearby devices permission to list paired Bluetooth hosts.",
                fixAction = "Allow Nearby devices access"
            )
        } catch (error: Exception) {
            _picker.value = _picker.value.copy(
                error = error.message ?: "Could not read paired Bluetooth hosts.",
                fixAction = "Check Bluetooth settings and permissions."
            )
        }
    }

    fun prepareBluetoothPairing(onReady: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                connectionManager.prepareBluetoothPairing()
                onReady(true)
            } catch (error: Exception) {
                if (error is BluetoothHidNotSupportedException) markBluetoothUnsupported(error)
                _picker.value = _picker.value.copy(
                    error = error.message ?: "Could not register this phone as a Bluetooth gamepad.",
                    fixAction = "Enable Bluetooth and allow Nearby devices access."
                )
                onReady(false)
            }
        }
    }

    fun selectBluetoothHost(name: String, address: String) {
        _picker.value = _picker.value.copy(
            host = address,
            bluetoothHostAddress = address,
            selectedMethod = ConnectionMethod.BLUETOOTH,
            error = null,
            fixAction = null
        )
    }

    private fun parseCompanionText(value: String) {
        val parsed = runCatching {
            val uri = android.net.Uri.parse(value.trim())
            Triple(
                uri.getQueryParameter("host") ?: uri.host.orEmpty(),
                uri.getQueryParameter("port") ?: "26760",
                uri.getQueryParameter("key").orEmpty()
            )
        }.getOrNull() ?: return
        if (parsed.first.isNotBlank() && parsed.third.isNotBlank()) {
            _picker.value = _picker.value.copy(
                host = parsed.first,
                port = parsed.second,
                pairingKey = parsed.third,
                selectedMethod = when (_picker.value.selectedMethod) {
                    ConnectionMethod.USB -> ConnectionMethod.USB
                    ConnectionMethod.AUTO -> ConnectionMethod.AUTO
                    else -> ConnectionMethod.WIFI
                }
            )
        }
    }

    fun setRememberChoice(remember: Boolean) {
        _picker.value = _picker.value.copy(rememberChoice = remember)
        viewModelScope.launch {
            settingsRepository.setRememberConnection(remember)
            if (!remember) settingsRepository.clearRememberedConnection()
        }
    }

    fun setUsbCableConnected(connected: Boolean) {
        val previous = _picker.value.usbCableConnected
        if (previous == connected) return
        _picker.value = _picker.value.copy(usbCableConnected = connected)
        if (connected && _picker.value.selectedMethod in setOf(ConnectionMethod.USB, ConnectionMethod.AUTO)) {
            val active = connection.value
            if (!active.connected || active.method != ConnectionMethod.USB) connect()
        }
    }

    fun applyProfile(name: String) {
        viewModelScope.launch {
            val profile = profileDao.getProfile(name)
                ?: throw IllegalArgumentException("Profile '$name' does not exist.")
            settingsRepository.setSensitivity(profile.sensitivity)
            settingsRepository.setDeadZone(profile.deadZone)
            settingsRepository.setControllerMode(
                if (profile.controllerMode == "PlayStation") "PlayStation" else "Xbox"
            )
            _buttonMapping.value = ButtonMapping.decode(profile.layoutJson)
            _activeProfile.value = profile.name
        }
    }

    fun saveProfile(name: String) {
        val safeName = name.trim()
        require(safeName.isNotEmpty()) { "Profile name must not be empty." }
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            val profile = ProfileEntity(
                name = safeName,
                controllerMode = current.controllerMode,
                sensitivity = current.sensitivity,
                deadZone = current.deadZone,
                layoutJson = ButtonMapping.encode(_buttonMapping.value)
            )
            profileDao.save(profile)
            _activeProfile.value = safeName
        }
    }

    fun deleteProfile(name: String) {
        require(name !in setOf("Default", "FPS", "Racing")) { "Built-in profiles cannot be deleted." }
        viewModelScope.launch {
            profileDao.delete(name)
            if (_activeProfile.value == name) _activeProfile.value = "Default"
        }
    }

    fun connect() {
        val method = _picker.value.selectedMethod ?: return
        if (method == ConnectionMethod.BLUETOOTH) {
            connectBluetooth()
            return
        }
        if (method == ConnectionMethod.USB) {
            connectOverUsb()
            return
        }
        if (method != ConnectionMethod.WIFI && method != ConnectionMethod.AUTO) {
            val message = when (method) {
                ConnectionMethod.USB -> "USB needs an active ADB reverse tunnel and a Companion pairing key."
                ConnectionMethod.BLUETOOTH -> "Select a paired Bluetooth host using its address."
                else -> "Choose Wi-Fi to connect to the Companion."
            }
            _picker.value = _picker.value.copy(error = message, fixAction = "Use Wi-Fi")
            return
        }
        val selection = _picker.value
        val parsedPort = selection.port.toIntOrNull()
        val companionDetailsValid = selection.host.isNotBlank() &&
            parsedPort != null && parsedPort in 1..65535 && selection.pairingKey.isNotBlank()
        val hasBluetoothFallback = selection.bluetoothHostAddress.isNotBlank() ||
            selection.pairedBluetoothHosts.isNotEmpty()
        if (method == ConnectionMethod.AUTO && !companionDetailsValid && !hasBluetoothFallback) {
            _picker.value = selection.copy(
                error = "Paste the Companion pairing link or pair a Bluetooth host before using Auto.",
                fixAction = "Set up USB/Wi-Fi, or grant Nearby devices access for Bluetooth."
            )
            return
        }
        if (method == ConnectionMethod.WIFI && !companionDetailsValid) {
            _picker.value = selection.copy(
                error = "Enter the PC's network address and a port from 1 to 65535.",
                fixAction = "Enter PC details"
            )
            return
        }
        val effectivePort = parsedPort?.takeIf { it in 1..65535 } ?: 26760
        _picker.value = selection.copy(connecting = true, error = null, fixAction = null)
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                if (method == ConnectionMethod.AUTO) {
                    connectionManager.connectAuto(
                        selection.host,
                        effectivePort,
                        selection.pairingKey,
                        selection.bluetoothHostAddress.ifBlank {
                            _picker.value.pairedBluetoothHosts.firstOrNull()?.second.orEmpty()
                        }.ifBlank { null }
                    )
                } else {
                    connectionManager.connectWifi(selection.host, effectivePort, selection.pairingKey)
                }
                if (_picker.value.rememberChoice) {
                    settingsRepository.setRememberedMethod(method.label)
                    if (selection.host.isNotBlank() && selection.pairingKey.isNotBlank()) {
                        settingsRepository.saveRememberedConnection(
                            selection.host,
                            effectivePort,
                            selection.pairingKey
                        )
                    }
                }
                _picker.value = _picker.value.copy(connecting = false)
                refreshMethodStatuses(connection.value.method == ConnectionMethod.WIFI, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                connectionManager.disconnect()
                _picker.value = _picker.value.copy(
                    connecting = false,
                    error = error.message ?: "The PC did not respond. Check the network and pairing key.",
                    fixAction = "Check the Companion, firewall, address, port, and pairing key."
                )
            }
        }
    }

    private fun connectBluetooth() {
        val selection = _picker.value
        val address = selection.bluetoothHostAddress.ifBlank { selection.host }
        if (address.isBlank()) {
            _picker.value = selection.copy(
                error = "Select a paired Bluetooth host before connecting.",
                fixAction = "Pair a host in Bluetooth settings, then choose it below."
            )
            loadPairedBluetoothHosts()
            return
        }
        _picker.value = selection.copy(connecting = true, error = null, fixAction = null)
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                connectionManager.connectBluetooth(address)
                if (_picker.value.rememberChoice) {
                    // Bluetooth HID needs no shared secret; remember only the bonded host.
                    settingsRepository.saveRememberedBluetoothHost(address)
                    settingsRepository.setRememberedMethod(ConnectionMethod.BLUETOOTH.label)
                }
                _picker.value = _picker.value.copy(connecting = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                connectionManager.disconnect()
                if (error is BluetoothHidNotSupportedException) {
                    markBluetoothUnsupported(error)
                    _picker.value = _picker.value.copy(connecting = false, error = error.message, fixAction = "Use Wi-Fi or USB.")
                    return@launch
                }
                _picker.value = _picker.value.copy(
                    connecting = false,
                    error = error.message ?: "Bluetooth gamepad connection failed.",
                    fixAction = "Turn on Bluetooth, grant Nearby devices permission, pair the PC, then retry."
                )
            }
        }
    }

    private fun markBluetoothUnsupported(error: BluetoothHidNotSupportedException) {
        _picker.value = _picker.value.copy(
            statuses = _picker.value.statuses.map { status ->
                if (status.method == ConnectionMethod.BLUETOOTH) {
                    status.copy(
                        readiness = OptionReadiness.UNSUPPORTED,
                        detail = error.message ?: "Bluetooth gamepad mode is not supported on this phone."
                    )
                } else status
            }
        )
    }

    private fun connectOverUsb() {
        val selection = _picker.value
        if (selection.pairingKey.isBlank()) {
            _picker.value = selection.copy(
                error = "Paste the pairing link from the PC Companion to load the USB pairing key.",
                fixAction = "Paste the Companion pairing link"
            )
            return
        }
        // USB reuses the same authenticated PPD1 framing as Wi-Fi, so reject a malformed key here.
        // Without this the failure surfaces later as a raw socket/handshake error and the generic
        // "check the cable and ADB tunnel" advice blames hardware for what is really a bad key.
        val keyError = runCatching { ProtocolCodec.parseKey(selection.pairingKey) }.exceptionOrNull()
        if (keyError != null) {
            _picker.value = selection.copy(
                error = keyError.message ?: "The pairing key is not valid.",
                fixAction = "Re-paste the pairing link from the PC Companion"
            )
            return
        }
        _picker.value = selection.copy(connecting = true, error = null, fixAction = null)
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                connectionManager.connectUsb(selection.pairingKey)
                if (_picker.value.rememberChoice) {
                    settingsRepository.saveRememberedConnection("127.0.0.1", 26762, selection.pairingKey)
                    settingsRepository.setRememberedMethod(ConnectionMethod.USB.label)
                }
                _picker.value = _picker.value.copy(connecting = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                connectionManager.disconnect()
                _picker.value = _picker.value.copy(
                    connecting = false,
                    error = error.message ?: "USB Companion connection failed.",
                    fixAction = "Keep the phone connected, enable USB debugging, set up the ADB tunnel, and check the pairing link."
                )
            }
        }
    }

    fun cancelConnection() {
        connectJob?.cancel()
        connectJob = null
        connectionManager.disconnect()
        _picker.value = _picker.value.copy(connecting = false)
    }

    fun dismissFailure() {
        _picker.value = _picker.value.copy(error = null, fixAction = null, selectedMethod = null)
    }

    fun reportConnectionFailure(message: String) {
        _picker.value = _picker.value.copy(
            connecting = false,
            error = message,
            fixAction = "Check the connection, then retry or choose another method."
        )
    }

    fun disconnect() {
        releaseAllButtons()
        connectionManager.disconnect()
        refreshMethodStatuses(false, false)
    }

    fun button(mask: Int, pressed: Boolean) {
        turboJobs.remove(mask)?.cancel()
        // Drop the per-mask turbo flag so a later press cannot inherit a stale "released".
        turboReleased.remove(mask)
        if (pressed) {
            pressedSourceButtons = pressedSourceButtons or mask
            playFeedback()
        } else {
            pressedSourceButtons = pressedSourceButtons and mask.inv()
        }
        updateMappedButtons()
        if (pressed && settings.value.turboMode) {
            val released = AtomicBoolean(false)
            turboJobs[mask] = viewModelScope.launch {
                delay(300)
                while (isActive) {
                    released.set(true)
                    updateMappedButtons()
                    delay(60)
                    released.set(false)
                    updateMappedButtons()
                    delay(60)
                }
            }
            turboReleased[mask] = released
        }
    }

    fun remapButton(source: Int, target: Int) {
        val mapping = ButtonMapping.reassign(_buttonMapping.value, source, target)
        _buttonMapping.value = mapping
        updateMappedButtons()
        viewModelScope.launch {
            val profile = profileDao.getProfile(_activeProfile.value) ?: return@launch
            profileDao.save(profile.copy(layoutJson = ButtonMapping.encode(mapping)))
        }
    }

    fun axis(leftStick: Boolean, x: Float, y: Float) {
        val current = _input.value
        val next = if (leftStick) current.copy(leftX = x, leftY = y)
        else current.copy(rightX = x, rightY = y)
        _input.value = next
        sendInput(next)
    }

    fun setTrigger(left: Boolean, value: Float) {
        val current = _input.value
        val next = if (left) current.copy(leftTrigger = value) else current.copy(rightTrigger = value)
        _input.value = next
        sendInput(next)
    }

    fun releaseAllButtons() {
        turboJobs.values.forEach(Job::cancel)
        turboJobs.clear()
        turboReleased.clear()
        pressedSourceButtons = 0
        _input.value = ControllerState()
        sendInput(_input.value)
    }

    private fun updateMappedButtons() {
        val effectiveButtons = pressedSourceButtons and turboReleased.entries
            .filter { it.value.get() }
            .fold(0) { mask, entry -> mask or entry.key }
            .inv()
        val next = _input.value.copy(buttons = ButtonMapping.translate(effectiveButtons, _buttonMapping.value))
        _input.value = next
        sendInput(next)
    }

    private fun sendInput(state: ControllerState) {
        val current = settings.value
        connectionManager.updateInput(state.shaped(current.deadZone, current.sensitivity))
    }

    private fun playFeedback() {
        if (settings.value.haptics && vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        if (settings.value.audioFeedback) {
            val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_SYSTEM, 25).also {
                toneGenerator = it
            }
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, 28)
        }
    }

    private fun playConnectSuccessSound() {
        if (!settings.value.audioFeedback) return
        viewModelScope.launch {
            val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_SYSTEM, 30).also {
                toneGenerator = it
            }
            try {
                generator.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                delay(140)
                generator.startTone(ToneGenerator.TONE_PROP_PROMPT, 160)
            } catch (_: Exception) {}
        }
    }

    private fun playRumble(largeMotor: Int, smallMotor: Int) {
        if (!settings.value.haptics || vibrator?.hasVibrator() != true) return
        val maxMotor = maxOf(largeMotor, smallMotor)
        if (maxMotor <= 0) {
            vibrator.cancel()
            return
        }
        val amplitude = (maxMotor.coerceIn(1, 255) * (VibrationEffect.DEFAULT_AMPLITUDE.toFloat() / 255f))
            .toInt().coerceIn(1, 255)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(120, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(120)
        }
    }

    fun updateSettings(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { settingsRepository.block() }
    }

    override fun onCleared() {
        toneGenerator?.release()
        toneGenerator = null
        super.onCleared()
    }
}
