package com.pocketpad.ui

import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketpad.R
import com.pocketpad.network.ConnectionMethod
import com.pocketpad.network.LiveConnection
import com.pocketpad.network.MethodStatus
import com.pocketpad.network.OptionReadiness
import com.pocketpad.protocol.ControllerState
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import kotlinx.coroutines.delay

private val Cyan = Color(0xFF64E7F2)
private val Purple = Color(0xFFB996FF)
private val Green = Color(0xFF5EE2A0)
private val Panel = Color(0xD91A2033)
private val Background = Color(0xFF080B18)

@Composable
fun PocketPadApp(
    navController: NavHostController = rememberNavController(),
    viewModel: PocketPadViewModel,
    lowPower: Boolean
) {
    val connection by viewModel.connection.collectAsState()
    val picker by viewModel.picker.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var showPicker by rememberSaveable { mutableStateOf(true) }
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "connect"
    val activity = LocalContext.current as? Activity

    DisposableEffect(activity, currentRoute) {
        if (activity != null) {
            activity.requestedOrientation = if (currentRoute == "controller") {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (currentRoute == "controller") {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (activity != null && currentRoute == "controller") {
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    LaunchedEffect(connection.error) {
        connection.error?.let { message ->
            viewModel.reportConnectionFailure(message)
            showPicker = true
        }
    }

    LaunchedEffect(connection.connected) {
        if (connection.connected) {
            delay(1500)
            showPicker = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        Background,
                        Color(0xFF101629),
                        if (lowPower) Background else Color(0xFF17112B),
                        Background
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.navigationBars,
            bottomBar = {
                if (currentRoute != "controller") {
                NavigationBar(containerColor = Color(0xF20F1425)) {
                    val tabs = listOf(
                        Triple("connect", stringResource(R.string.tab_connect), Icons.Default.Link),
                        Triple("controller", stringResource(R.string.tab_controller), Icons.Default.SportsEsports),
                        Triple("settings", stringResource(R.string.tab_settings), Icons.Default.Settings)
                    )
                    tabs.forEach { (route, title, icon) ->
                        NavigationBarItem(
                            selected = currentRoute == route,
                            onClick = {
                                if (route == "controller" && !connection.connected) showPicker = true
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = title) },
                            label = { Text(title) }
                        )
                    }
                }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "connect",
                modifier = Modifier.padding(padding)
            ) {
                composable("connect") {
                    ConnectScreen(
                        connection = connection,
                        onOpenPicker = { showPicker = true },
                        onDisconnect = viewModel::disconnect
                    )
                }
                composable("controller") {
                    ControllerScreen(
                        connection = connection,
                        state = viewModel.input.collectAsState().value,
                        settings = settings,
                        onOpenPicker = { showPicker = true },
                        onButton = viewModel::button,
                        onAxis = viewModel::axis,
                        onTrigger = viewModel::setTrigger,
                        updateSettings = viewModel::updateSettings
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        settings = settings,
                        profiles = viewModel.profiles.collectAsState().value,
                        activeProfile = viewModel.activeProfile.collectAsState().value,
                        buttonMapping = viewModel.buttonMapping.collectAsState().value,
                        updateSettings = viewModel::updateSettings,
                        onApplyProfile = viewModel::applyProfile,
                        onSaveProfile = viewModel::saveProfile,
                        onDeleteProfile = viewModel::deleteProfile,
                        onRemapButton = viewModel::remapButton
                    )
                }
            }
        }

        if (showPicker) {
            ConnectionPickerSheet(
                state = picker,
                connection = connection,
                onDismiss = { showPicker = false },
                onSelect = viewModel::selectMethod,
                onHostChanged = viewModel::updateHost,
                onPortChanged = viewModel::updatePort,
                onKeyChanged = viewModel::updatePairingKey,
                onQrTextChanged = viewModel::updateQrText,
                onFindHosts = viewModel::findHosts,
                onSelectHost = viewModel::selectDiscoveredHost,
                onRememberChanged = viewModel::setRememberChoice,
                onConnect = viewModel::connect,
                onCancel = viewModel::cancelConnection,
                onDismissFailure = viewModel::dismissFailure,
                onLoadBluetoothHosts = viewModel::loadPairedBluetoothHosts,
                onSelectBluetoothHost = viewModel::selectBluetoothHost,
                onPrepareBluetoothPairing = viewModel::prepareBluetoothPairing
            )
        }
    }
}

@Composable
private fun ConnectScreen(
    connection: LiveConnection,
    onOpenPicker: () -> Unit,
    onDisconnect: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.pocketpad_logo),
                    contentDescription = "PocketPad logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.connect_to_pc), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("One gamepad for your gaming setup", color = Color(0xFFBEC8DF), fontSize = 13.sp)
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color(0x774FD3E4), RoundedCornerShape(20.dp))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Icon(Icons.Default.SportsEsports, null, tint = Cyan, modifier = Modifier.size(36.dp))
                        Text("········", color = Purple, fontSize = 22.sp)
                        Icon(Icons.Default.Computer, null, tint = Purple, modifier = Modifier.size(38.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (connection.connected) stringResource(R.string.connection_success, connection.host)
                        else "Ready when you are",
                        color = if (connection.connected) Green else Color(0xFFEAF1FF),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (connection.connected) "${connection.method?.label} · ${connection.latencyMillis ?: "—"} ms"
                        else "Pair with a compatible game host",
                        color = Color(0xFFBEC8DF),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ConnectionMethod.AUTO, ConnectionMethod.USB, ConnectionMethod.WIFI, ConnectionMethod.BLUETOOTH).forEach { method ->
                    Surface(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenPicker),
                        color = Color(0xAA161D30),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x553F526E))
                    ) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(method.label, color = Color(0xFFEAF1FF), fontSize = 11.sp, maxLines = 1)
                            Text(
                                if (method == ConnectionMethod.WIFI && connection.connected) "Ready" else "Setup",
                                color = if (method == ConnectionMethod.WIFI && connection.connected) Green else Color(0xFFFFC66D),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
        item {
            if (connection.connected) {
                Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Text(stringResource(R.string.disconnect))
                }
            } else {
                Button(onClick = onOpenPicker, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                    Icon(Icons.Default.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connect to a gaming system")
                }
            }
        }
        item {
            OutlinedButton(onClick = onOpenPicker, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("Scan a setup QR or enter host details")
            }
        }
        item {
            Text(
                "PocketPad sends controller input to supported gaming setups. PC Companion Wi-Fi and USB, plus Android Bluetooth HID on supported phones, are available; direct console connections are not provided.",
                color = Color(0xFFB8C3DA),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPickerSheet(
    state: ConnectionPickerState,
    connection: LiveConnection,
    onDismiss: () -> Unit,
    onSelect: (ConnectionMethod) -> Unit,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onKeyChanged: (String) -> Unit,
    onQrTextChanged: (String) -> Unit,
    onFindHosts: () -> Unit,
    onSelectHost: (com.pocketpad.network.DiscoveredCompanion) -> Unit,
    onRememberChanged: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    onLoadBluetoothHosts: () -> Unit,
    onSelectBluetoothHost: (String, String) -> Unit,
    onPrepareBluetoothPairing: ((Boolean) -> Unit) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var showChooser by rememberSaveable { mutableStateOf(false) }
    var companionInstalled by rememberSaveable { mutableStateOf(false) }
    var cableAvailable by rememberSaveable { mutableStateOf(false) }
    var sameNetwork by rememberSaveable { mutableStateOf(false) }
    var qrScanMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var bluetoothPermissionAction by rememberSaveable { mutableStateOf("") }
    val openSystemSettings: (String) -> Unit = { action ->
        try {
            context.startActivity(Intent(action))
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            when (bluetoothPermissionAction) {
                "visible" -> onPrepareBluetoothPairing { ready ->
                    if (ready) {
                        context.startActivity(
                            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                        )
                    }
                }
                "connect" -> {
                    onLoadBluetoothHosts()
                    onConnect()
                }
                "paired" -> onLoadBluetoothHosts()
                "auto" -> onLoadBluetoothHosts()
            }
        } else {
            if (bluetoothPermissionAction == "visible") {
                openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            } else if (bluetoothPermissionAction != "auto") {
                onLoadBluetoothHosts()
            }
        }
    }
    fun withBluetoothPermission(action: String) {
        bluetoothPermissionAction = action
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && action == "visible") {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray<String>()
        }
        if (required.isEmpty() || required.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            when (action) {
                "visible" -> onPrepareBluetoothPairing { ready ->
                    if (ready) {
                        context.startActivity(
                            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                        )
                    }
                }
                else -> {
                    onLoadBluetoothHosts()
                }
            }
        } else {
            bluetoothPermissionLauncher.launch(required)
        }
    }
    fun connectWithRequiredPermissions() {
        if ((state.selectedMethod == ConnectionMethod.BLUETOOTH || state.selectedMethod == ConnectionMethod.AUTO) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermissionAction = "connect"
            bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            onConnect()
        }
    }
    val scanSetupCode: () -> Unit = {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                if (rawValue.isNullOrBlank()) {
                    qrScanMessage = "The QR code did not contain a PocketPad setup link."
                } else {
                    onQrTextChanged(rawValue)
                    qrScanMessage = null
                }
            }
            .addOnCanceledListener {
                qrScanMessage = null
            }
            .addOnFailureListener { error ->
                qrScanMessage = error.message ?: "QR scanning failed. Paste the setup link instead."
            }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF101629),
        contentWindowInsets = { WindowInsets.navigationBars }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(stringResource(R.string.link_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.link_subtitle), color = Color(0xFFBEC8DF))
            }
            if (connection.connected) {
                item {
                    MethodStatusCard(connection)
                }
                item {
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.connection_success, connection.host))
                    }
                }
                item {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue to controller")
                    }
                }
            } else {
                ConnectionMethod.entries.forEach { method ->
                    val methodStatus = state.statuses.firstOrNull { it.method == method }
                        ?: MethodStatus(method, OptionReadiness.NEEDS_SETUP, "Checking availability")
                    item(key = method.name) {
                        MethodCard(
                            method = method,
                            status = methodStatus,
                            selected = state.selectedMethod == method,
                            onSelect = {
                                onSelect(method)
                                if (method == ConnectionMethod.AUTO) withBluetoothPermission("auto")
                            }
                        )
                    }
                    if (state.selectedMethod == method && methodStatus.readiness != OptionReadiness.UNSUPPORTED) {
                        item(key = "${method.name}-details") {
                            when (method) {
                                ConnectionMethod.WIFI, ConnectionMethod.AUTO -> WifiSetupFields(
                                    state, onHostChanged, onPortChanged, onKeyChanged, onQrTextChanged,
                                    onFindHosts, onSelectHost, scanSetupCode, qrScanMessage
                                )
                                ConnectionMethod.USB -> UsbSetupFields(
                                    state, onKeyChanged, onQrTextChanged, scanSetupCode, qrScanMessage,
                                    onOpenDeveloperSettings = {
                                        openSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    }
                                )
                                ConnectionMethod.BLUETOOTH -> BluetoothSetupFields(
                                    state = state,
                                    onMakeVisible = { withBluetoothPermission("visible") },
                                    onOpenSettings = { openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
                                    onRefreshHosts = { withBluetoothPermission("paired") },
                                    onSelectHost = onSelectBluetoothHost
                                )
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.rememberChoice, onCheckedChange = onRememberChanged)
                        Text(stringResource(R.string.remember_choice), color = Color(0xFFEAF1FF), fontSize = 13.sp)
                    }
                }
                item {
                    TextButton(
                        onClick = { showChooser = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.help_me_choose)) }
                }
                if (state.error != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF321F2B))) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(state.error, color = Color(0xFFFFC1BD))
                                state.fixAction?.let { Text("${stringResource(R.string.fix_it)}: $it", color = Cyan) }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onDismissFailure, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.try_another_method))
                            }
                            Button(onClick = { connectWithRequiredPermissions() }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.fix_it))
                            }
                        }
                    }
                } else if (state.connecting) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Searching · Pairing · Connected", color = Color(0xFFEAF1FF))
                        }
                    }
                    item {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                } else {
                    item {
                        Button(
                            onClick = { connectWithRequiredPermissions() },
                            enabled = state.selectedMethod != null,
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text(stringResource(R.string.connect)) }
                    }
                    item {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
    if (showChooser) {
        val recommendation = when {
            companionInstalled && sameNetwork ->
                "Wi-Fi is ready to use with the PC Companion on this network."
            companionInstalled && cableAvailable ->
                "USB can be used with the Companion after you enable debugging and set up the ADB tunnel."
            companionInstalled && !sameNetwork ->
                "Bluetooth may work without the Companion on supported phones. Otherwise use Wi-Fi or USB ADB."
            !companionInstalled ->
                "Bluetooth does not require the Companion. Wi-Fi and USB ADB do; USB needs debugging enabled."
            else ->
                "Wi-Fi works on the same network or hotspot. USB needs debugging enabled and the Companion's ADB tunnel."
        }
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text("Help me choose") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(companionInstalled, { companionInstalled = it })
                        Text("PocketPad PC Companion is installed")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cableAvailable, { cableAvailable = it })
                        Text("I have a USB cable")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sameNetwork, { sameNetwork = it })
                        Text("The PC and phone share Wi-Fi")
                    }
                    Text(recommendation, color = Color(0xFFBEC8DF))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSelect(
                        when {
                            cableAvailable && companionInstalled -> ConnectionMethod.USB
                            !companionInstalled -> ConnectionMethod.BLUETOOTH
                            else -> ConnectionMethod.WIFI
                        }
                    )
                    showChooser = false
                }) {
                    Text(
                        when {
                            cableAvailable && companionInstalled -> "Choose USB"
                            !companionInstalled -> "Choose Bluetooth"
                            else -> "Choose Wi-Fi"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showChooser = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun MethodCard(
    method: ConnectionMethod,
    status: MethodStatus,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val enabled = status.readiness != OptionReadiness.UNSUPPORTED
    val badgeColor = when (status.readiness) {
        OptionReadiness.READY -> Green
        OptionReadiness.NEEDS_SETUP -> Color(0xFFFFC66D)
        OptionReadiness.UNSUPPORTED -> Color(0xFF9BA5B9)
    }
    val icon = when (method) {
        ConnectionMethod.USB -> Icons.Default.Cable
        ConnectionMethod.WIFI, ConnectionMethod.AUTO -> Icons.Default.Wifi
        ConnectionMethod.BLUETOOTH -> Icons.Default.Bluetooth
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) Cyan else Color(0x553F526E),
                RoundedCornerShape(16.dp)
            )
            .semantics { contentDescription = "${method.label}, ${status.detail}" },
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF1C2940) else Panel)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = if (enabled) Cyan else Color.Gray, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (method == ConnectionMethod.AUTO) "Auto (Recommended)" else method.label,
                    color = if (enabled) Color(0xFFF4F6FF) else Color(0xFFA0A7B6),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when (method) {
                        ConnectionMethod.AUTO -> "Tries USB, Wi-Fi, then a paired Bluetooth host"
                        ConnectionMethod.USB -> "USB-C Companion connection using ADB reverse"
                        ConnectionMethod.WIFI -> "Wireless over your router or hotspot"
                        ConnectionMethod.BLUETOOTH -> "Native gamepad; device and OS support varies"
                    },
                    color = Color(0xFFBEC8DF),
                    fontSize = 12.sp
                )
                if (status.readiness == OptionReadiness.UNSUPPORTED) {
                    Text(status.detail, color = Color(0xFF9BA5B9), fontSize = 11.sp)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when (status.readiness) {
                        OptionReadiness.READY -> stringResource(R.string.ready)
                        OptionReadiness.NEEDS_SETUP -> stringResource(R.string.needs_setup)
                        OptionReadiness.UNSUPPORTED -> stringResource(R.string.not_supported)
                    },
                    color = badgeColor,
                    fontSize = if (status.readiness == OptionReadiness.UNSUPPORTED) 10.sp else 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (selected) {
            Text(status.detail, color = Color(0xFFBEC8DF), fontSize = 12.sp, modifier = Modifier.padding(start = 54.dp, end = 12.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun UsbSetupFields(
    state: ConnectionPickerState,
    onKeyChanged: (String) -> Unit,
    onQrTextChanged: (String) -> Unit,
    onScanQr: () -> Unit,
    qrScanMessage: String?,
    onOpenDeveloperSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (state.usbCableConnected) {
                "USB cable detected. Enable USB debugging and authorize this PC, then choose “Set up USB cable (ADB)” in the PC Companion."
            } else {
                "Connect the phone to this PC with a data-capable USB cable. Cable status is monitored while PocketPad is open."
            },
            color = Color(0xFFBEC8DF),
            fontSize = 13.sp
        )
        OutlinedButton(
            onClick = onOpenDeveloperSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Open Developer options") }
        Text(
            if (state.usbCableConnected) "Cable connected · reconnects automatically if USB was selected"
            else "No USB data cable detected",
            color = if (state.usbCableConnected) Green else Color(0xFFFFC66D),
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = state.qrText,
            onValueChange = onQrTextChanged,
            label = { Text("Paste Companion pairing link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan Companion QR code")
        }
        qrScanMessage?.let { Text(it, color = Color(0xFFFFC1BD), fontSize = 12.sp) }
        OutlinedTextField(
            value = state.pairingKey,
            onValueChange = onKeyChanged,
            label = { Text(stringResource(R.string.pairing_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BluetoothSetupFields(
    state: ConnectionPickerState,
    onMakeVisible: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshHosts: () -> Unit,
    onSelectHost: (String, String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "PocketPad registers as a Bluetooth gamepad. Make the phone visible, pair it from the PC's Bluetooth settings, then select the paired PC below.",
            color = Color(0xFFBEC8DF),
            fontSize = 13.sp
        )
        OutlinedButton(
            onClick = onMakeVisible,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Register gamepad and make phone visible") }
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Open Bluetooth settings to pair a new PC") }
        OutlinedButton(
            onClick = onRefreshHosts,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Refresh paired PCs") }
        state.pairedBluetoothHosts.forEach { (name, address) ->
            OutlinedButton(
                onClick = { onSelectHost(name, address) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(address, color = Color(0xFFBEC8DF), fontSize = 11.sp)
                }
            }
        }
        if (state.selectedMethod == ConnectionMethod.BLUETOOTH) {
            OutlinedTextField(
                value = state.bluetoothHostAddress,
                onValueChange = {},
                label = { Text("Selected host address") },
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WifiSetupFields(
    state: ConnectionPickerState,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onKeyChanged: (String) -> Unit,
    onQrTextChanged: (String) -> Unit,
    onFindHosts: () -> Unit,
    onSelectHost: (com.pocketpad.network.DiscoveredCompanion) -> Unit,
    onScanQr: () -> Unit,
    qrScanMessage: String?
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Works over a shared Wi-Fi network. For a cable network, enable USB tethering on the phone, connect the PC to it, and use the Companion's setup link or enter the PC address manually.",
            color = Color(0xFFBEC8DF),
            fontSize = 12.sp
        )
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Open hotspot and USB tethering settings") }
        OutlinedTextField(
            value = state.qrText,
            onValueChange = onQrTextChanged,
            label = { Text(stringResource(R.string.scan_qr)) },
            supportingText = { Text("Paste the PocketPad setup link shown by a compatible host.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan Companion QR code")
        }
        qrScanMessage?.let { Text(it, color = Color(0xFFFFC1BD), fontSize = 12.sp) }
        OutlinedButton(
            onClick = onFindHosts,
            enabled = !state.scanningHosts,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            if (state.scanningHosts) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Finding PocketPad hosts…")
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_for_devices))
            }
        }
        state.discoveredHosts.forEach { host ->
            OutlinedButton(
                onClick = { onSelectHost(host) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(host.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${host.host}:${host.port} · paste the pairing link below", color = Color(0xFFBEC8DF), fontSize = 11.sp)
                }
            }
        }
        OutlinedTextField(
            value = state.host,
            onValueChange = onHostChanged,
            label = { Text(stringResource(R.string.host_address)) },
            placeholder = { Text("192.168.1.25") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.port,
            onValueChange = onPortChanged,
            label = { Text(stringResource(R.string.host_port)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.pairingKey,
            onValueChange = onKeyChanged,
            label = { Text(stringResource(R.string.pairing_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MethodStatusCard(connection: LiveConnection) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF102B29))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = Green)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Connected to ${connection.host}", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("${connection.method?.label} · ${connection.latencyMillis ?: "—"} ms", color = Green)
            }
        }
    }
}

@Composable
private fun ControllerScreen(
    connection: LiveConnection,
    state: ControllerState,
    settings: com.pocketpad.data.settings.AppSettings,
    onOpenPicker: () -> Unit,
    onButton: (Int, Boolean) -> Unit,
    onAxis: (Boolean, Float, Float) -> Unit,
    onTrigger: (Boolean, Float) -> Unit,
    updateSettings: (suspend com.pocketpad.data.settings.SettingsRepository.() -> Unit) -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                listOf(Color(0xFF071722), Color(0xFF111729), Color(0xFF151025))
            )
        )
    ) {
        val scale = minOf(maxWidth.value / 900f, maxHeight.value / 430f).coerceIn(0.72f, 1.35f)
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val edge = availableWidth * 0.055f

        Box(Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = availableWidth * 0.025f)
                    .width(availableWidth * 0.46f)
                    .height(availableHeight * 0.64f),
                shape = RoundedCornerShape(54.dp * scale),
                color = Color(0x301A4A5C),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4464E7F2))
            ) {}
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(end = availableWidth * 0.025f)
                    .width(availableWidth * 0.46f)
                    .height(availableHeight * 0.64f),
                shape = RoundedCornerShape(54.dp * scale),
                color = Color(0x301D1738),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44B996FF))
            ) {}

            Row(
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(start = edge, top = availableHeight * 0.065f),
                horizontalArrangement = Arrangement.spacedBy(10.dp * scale)
            ) {
                ShoulderButton("L1", 8, state.buttons, onButton, scale)
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(end = edge, top = availableHeight * 0.065f),
                horizontalArrangement = Arrangement.spacedBy(10.dp * scale)
            ) {
                ShoulderButton("R1", 9, state.buttons, onButton, scale)
            }

            TriggerButton(
                "L2",
                state.leftTrigger,
                { onTrigger(true, it) },
                scale,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = edge, bottom = availableHeight * 0.035f)
            )
            TriggerButton(
                "R2",
                state.rightTrigger,
                { onTrigger(false, it) },
                scale,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = edge, bottom = availableHeight * 0.035f)
            )

            Column(
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = availableHeight * 0.025f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("CONTROLLER", color = Color(0xFFF4F6FF), fontSize = 17.sp * scale, fontWeight = FontWeight.Bold)
                Surface(
                    modifier = Modifier.padding(top = 3.dp * scale).clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onOpenPicker),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xDD10182B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x554F657D))
                ) {
                    Row(
                        Modifier.padding(horizontal = 13.dp * scale, vertical = 5.dp * scale),
                        horizontalArrangement = Arrangement.spacedBy(8.dp * scale),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (connection.connected) "● CONNECTED · ${connection.host.uppercase()}" else "○ READY TO CONNECT",
                            color = if (connection.connected) Green else Cyan,
                            fontSize = 9.sp * scale,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (connection.connected) {
                            Text(
                                "${connection.latencyMillis ?: "—"} ms",
                                color = Color(0xFFCAD6EB),
                                fontSize = 9.sp * scale
                            )
                        }
                    }
                }
                connection.notice?.let { notice ->
                    Text(
                        notice,
                        color = Green,
                        fontSize = 10.sp * scale,
                        modifier = Modifier.padding(top = 2.dp * scale)
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = availableHeight * 0.285f),
                horizontalArrangement = Arrangement.spacedBy(12.dp * scale)
            ) {
                ControllerButton("START", 10, state.buttons, onButton, scale, 70.dp, 32.dp, accent = Cyan)
                ControllerButton("SELECT", 11, state.buttons, onButton, scale, 70.dp, 32.dp, accent = Purple)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = availableWidth * 0.105f)
                    .align(Alignment.Center).offset(y = availableHeight * 0.005f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp * scale)) {
                    Dpad(state.buttons, onButton, scale)
                    Stick("L3", state.leftX, state.leftY, { x, y -> onAxis(true, x, y) }, onButton, 1 shl 13, scale)
                }

                HomeButton(state.buttons, onButton, scale)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp * scale)) {
                    Stick("R3", state.rightX, state.rightY, { x, y -> onAxis(false, x, y) }, onButton, 1 shl 14, scale)
                    FaceButtons(state.buttons, onButton, scale, settings.controllerMode)
                }
            }

            ControllerOptionsPanel(
                settings = settings,
                scale = scale,
                onTurboChanged = { enabled -> updateSettings { setTurboMode(enabled) } },
                onHapticsChanged = { enabled -> updateSettings { setHaptics(enabled) } },
                onSensitivityChanged = { value -> updateSettings { setSensitivity(value) } },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = availableHeight * 0.035f)
                    .width(minOf(availableWidth * 0.46f, 440.dp * scale))
            )
        }
    }
}

@Composable
private fun HomeButton(buttons: Int, onButton: (Int, Boolean) -> Unit, scale: Float) {
    val pressed = buttons and (1 shl 12) != 0
    Surface(
        modifier = Modifier.size(76.dp * scale).clip(CircleShape)
            .semantics { contentDescription = "Home" }
            .pressInput(onButton, 1 shl 12),
        shape = CircleShape,
        color = if (pressed) Cyan.copy(alpha = 0.35f) else Color(0xCC202940),
        border = androidx.compose.foundation.BorderStroke(2.dp * scale, Cyan.copy(alpha = 0.9f))
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("⌂", color = Cyan, fontSize = 28.sp * scale, fontWeight = FontWeight.Bold, lineHeight = 28.sp * scale)
            Text("HOME", color = Color.White, fontSize = 8.sp * scale, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ControllerOptionsPanel(
    settings: com.pocketpad.data.settings.AppSettings,
    scale: Float,
    onTurboChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onSensitivityChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp * scale),
        color = Color(0xE51A2033),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x664B5976))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp * scale, vertical = 8.dp * scale),
            verticalArrangement = Arrangement.spacedBy(2.dp * scale)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("TURBO MODE", color = Color.White, fontSize = 12.sp * scale, fontWeight = FontWeight.SemiBold)
                Switch(checked = settings.turboMode, onCheckedChange = onTurboChanged)
                Spacer(Modifier.width(12.dp * scale))
                Text("VIBRATION", color = Color.White, fontSize = 12.sp * scale, fontWeight = FontWeight.SemiBold)
                Switch(checked = settings.haptics, onCheckedChange = onHapticsChanged)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp * scale)
            ) {
                Text("SENSITIVITY", color = Color.White, fontSize = 11.sp * scale, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = settings.sensitivity,
                    onValueChange = onSensitivityChanged,
                    modifier = Modifier.weight(1f).height(28.dp * scale),
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan)
                )
                Text("${(settings.sensitivity * 100).toInt()}%", color = Color.White, fontSize = 11.sp * scale)
            }
        }
    }
}

@Composable
private fun ShoulderButton(
    label: String,
    bit: Int,
    buttons: Int,
    onButton: (Int, Boolean) -> Unit,
    scale: Float
) {
    ControllerButton(label, bit, buttons, onButton, scale, 76.dp, 38.dp, accent = Cyan)
}

@Composable
private fun TriggerButton(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val pressed = value > 0f
    Surface(
        modifier = modifier.width(76.dp * scale).height(38.dp * scale)
            .clip(RoundedCornerShape(12.dp * scale))
            .pointerInput(onValue) {
                detectTapGestures(onPress = {
                    onValue(1f)
                    try {
                        tryAwaitRelease()
                    } finally {
                        onValue(0f)
                    }
                })
            }
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(12.dp * scale),
        color = if (pressed) Cyan.copy(alpha = 0.32f) else Color(0xCC202940),
        border = androidx.compose.foundation.BorderStroke(1.dp, Cyan.copy(alpha = 0.82f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp * scale)
        }
    }
}

@Composable
private fun Dpad(buttons: Int, onButton: (Int, Boolean) -> Unit, scale: Float) {
    Box(Modifier.size(148.dp * scale), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.size(142.dp * scale),
            shape = CircleShape,
            color = Color(0x222DE0F5),
            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0x6692A9C8))
        ) {}
        ControllerButton("↑", 0, buttons, onButton, scale, 44.dp, 48.dp, accent = Cyan, modifier = Modifier.align(Alignment.TopCenter))
        ControllerButton("←", 2, buttons, onButton, scale, 48.dp, 44.dp, accent = Cyan, modifier = Modifier.align(Alignment.CenterStart))
        ControllerButton("↓", 1, buttons, onButton, scale, 44.dp, 48.dp, accent = Cyan, modifier = Modifier.align(Alignment.BottomCenter))
        ControllerButton("→", 3, buttons, onButton, scale, 48.dp, 44.dp, accent = Cyan, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun FaceButtons(buttons: Int, onButton: (Int, Boolean) -> Unit, scale: Float, mode: String) {
    val playStation = mode == "PlayStation"
    Box(Modifier.size(148.dp * scale), contentAlignment = Alignment.Center) {
        ControllerButton(if (playStation) "△" else "Y", 7, buttons, onButton, scale, 52.dp, 52.dp, accent = Color(0xFFF4D35E), circular = true, accessibilityLabel = if (playStation) "Triangle button" else "Y button", modifier = Modifier.align(Alignment.TopCenter))
        ControllerButton(if (playStation) "□" else "X", 6, buttons, onButton, scale, 52.dp, 52.dp, accent = Color(0xFF72A8FF), circular = true, accessibilityLabel = if (playStation) "Square button" else "X button", modifier = Modifier.align(Alignment.CenterStart))
        ControllerButton(if (playStation) "○" else "B", 5, buttons, onButton, scale, 52.dp, 52.dp, accent = Color(0xFFFF7178), circular = true, accessibilityLabel = if (playStation) "Circle button" else "B button", modifier = Modifier.align(Alignment.CenterEnd))
        ControllerButton(if (playStation) "×" else "A", 4, buttons, onButton, scale, 52.dp, 52.dp, accent = Color(0xFF5EE2A0), circular = true, accessibilityLabel = if (playStation) "Cross button" else "A button", modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ControllerButton(
    label: String,
    bit: Int,
    buttons: Int,
    onButton: (Int, Boolean) -> Unit,
    scale: Float,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    accent: Color,
    circular: Boolean = false,
    accessibilityLabel: String = label,
    modifier: Modifier = Modifier
) {
    val pressed = buttons and (1 shl bit) != 0
    val shape = if (circular) CircleShape else RoundedCornerShape(12.dp * scale)
    Surface(
        modifier = modifier.width(width * scale).height(height * scale)
            .clip(shape)
            .semantics { contentDescription = accessibilityLabel }
            .pressInput(onButton, 1 shl bit),
        shape = shape,
        color = if (pressed) accent.copy(alpha = 0.4f) else Color(0xD921293D),
        border = androidx.compose.foundation.BorderStroke(1.5.dp * scale, accent.copy(alpha = 0.82f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (pressed) Color.White else accent,
                fontWeight = FontWeight.Bold,
                fontSize = (if (circular) 18 else 12).sp * scale
            )
        }
    }
}

@Composable
private fun Stick(
    label: String,
    x: Float,
    y: Float,
    onAxis: (Float, Float) -> Unit,
    onButton: (Int, Boolean) -> Unit,
    pressBit: Int,
    scale: Float
) {
    Box(
        modifier = Modifier.size(116.dp * scale)
            .clip(CircleShape)
            .background(Color(0x442C3B5C))
            .border(3.dp * scale, Color(0x885B7195), CircleShape)
            .stickInput(onAxis),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(82.dp * scale).clip(CircleShape)
                .border(2.dp * scale, Color(0x665D83AC), CircleShape)
                .background(Color(0x5520273B)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(58.dp * scale)
                    .offset(
                        x = 15.dp * scale * x.coerceIn(-1f, 1f),
                        y = 15.dp * scale * y.coerceIn(-1f, 1f)
                    )
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF405371), Color(0xFF18243A))))
                    .border(2.dp * scale, Cyan.copy(alpha = 0.48f), CircleShape)
                    .semantics { contentDescription = label }
                    .pressInput(onButton, pressBit)
            )
        }
    }
}

private fun Modifier.pressInput(
    onButton: (Int, Boolean) -> Unit,
    mask: Int
): Modifier = pointerInput(mask) {
    detectTapGestures(onPress = {
        onButton(mask, true)
        try {
            tryAwaitRelease()
        } finally {
            onButton(mask, false)
        }
    })
}

private fun Modifier.stickInput(onAxis: (Float, Float) -> Unit): Modifier = pointerInput(Unit) {
    fun update(position: Offset, radius: Float) {
        val x = ((position.x - radius) / radius).coerceIn(-1f, 1f)
        val y = ((position.y - radius) / radius).coerceIn(-1f, 1f)
        onAxis(x, y)
    }
    detectDragGestures(
        onDragStart = { offset ->
            val radius = size.width / 2f
            update(offset, radius)
        },
        onDragEnd = { onAxis(0f, 0f) },
        onDragCancel = { onAxis(0f, 0f) },
        onDrag = { change, _ ->
            val radius = size.width / 2f
            update(change.position, radius)
        }
    )
}

@Composable
private fun SettingsScreen(
    settings: com.pocketpad.data.settings.AppSettings,
    profiles: List<com.pocketpad.data.profile.ProfileEntity>,
    activeProfile: String,
    buttonMapping: Map<Int, Int>,
    updateSettings: (suspend com.pocketpad.data.settings.SettingsRepository.() -> Unit) -> Unit,
    onApplyProfile: (String) -> Unit,
    onSaveProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onRemapButton: (Int, Int) -> Unit
) {
    var newProfileName by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        }
        item { SettingSwitch("Turbo Mode", settings.turboMode) { updateSettings { setTurboMode(it) } } }
        item { SettingSwitch("Vibration / Haptics", settings.haptics) { updateSettings { setHaptics(it) } } }
        item { SettingSwitch("Audio Feedback", settings.audioFeedback) { updateSettings { setAudioFeedback(it) } } }
        item { SettingSwitch("Low-power effects", settings.lowPower) { updateSettings { setLowPower(it) } } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Button Remapping", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Choose a different gamepad output for each on-screen button. Changing one mapping swaps the affected output.", color = Color(0xFFBEC8DF), fontSize = 12.sp)
                    com.pocketpad.data.profile.ButtonMapping.labels.forEachIndexed { source, label ->
                        var expanded by remember(source) { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = Color.White, modifier = Modifier.weight(1f))
                            Box {
                                TextButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(com.pocketpad.data.profile.ButtonMapping.labels[buttonMapping[source] ?: source], color = Cyan)
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    com.pocketpad.data.profile.ButtonMapping.labels.forEachIndexed { target, targetLabel ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(targetLabel) },
                                            onClick = {
                                                onRemapButton(source, target)
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Stick Sensitivity", color = Color.White)
                    Slider(
                        value = settings.sensitivity,
                        onValueChange = { value -> updateSettings { setSensitivity(value) } }
                    )
                    Text("${(settings.sensitivity * 100).toInt()}%", color = Cyan)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Dead Zone", color = Color.White)
                    Slider(
                        value = settings.deadZone,
                        onValueChange = { value -> updateSettings { setDeadZone(value) } },
                        valueRange = 0f..0.5f
                    )
                    Text("${(settings.deadZone * 100).toInt()}%", color = Cyan)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Face Button Labels", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Changes the labels only; button signals stay in the same positions.", color = Color(0xFFBEC8DF), fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Xbox", "PlayStation").forEach { mode ->
                            if (settings.controllerMode == mode) {
                                Button(
                                    onClick = { updateSettings { setControllerMode(mode) } },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) { Text(mode) }
                            } else {
                                OutlinedButton(
                                    onClick = { updateSettings { setControllerMode(mode) } },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) { Text(mode) }
                            }
                        }
                    }
                    Text("Appearance", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Neon dark theme", modifier = Modifier.weight(1f), color = Color(0xFFEAF1FF))
                        Switch(
                            checked = settings.darkTheme == true,
                            onCheckedChange = { value -> updateSettings { setDarkTheme(value) } }
                        )
                    }
                    HorizontalDivider(color = Color(0x443F526E))
                    Text("Controller input can use the PC Companion over Wi-Fi or USB, or Bluetooth HID on supported phones. Direct console connections are not provided.", color = Color(0xFFBEC8DF), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saved Profiles", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Profiles include button remapping, labels, sensitivity, and dead zone.", color = Color(0xFFBEC8DF), fontSize = 12.sp)
                    profiles.forEach { profile ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onApplyProfile(profile.name) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        if (activeProfile == profile.name) "${profile.name} · Active" else profile.name,
                                        color = if (activeProfile == profile.name) Cyan else Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "${profile.controllerMode} · sensitivity ${(profile.sensitivity * 100).toInt()}% · dead zone ${(profile.deadZone * 100).toInt()}%",
                                        color = Color(0xFFBEC8DF),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (profile.name !in setOf("Default", "FPS", "Racing")) {
                                TextButton(onClick = { onDeleteProfile(profile.name) }) {
                                    Text("Delete", color = Color(0xFFFF9F9F))
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newProfileName,
                            onValueChange = { newProfileName = it },
                            label = { Text("New profile name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                onSaveProfile(newProfileName)
                                newProfileName = ""
                            },
                            enabled = newProfileName.isNotBlank()
                        ) { Text("Save") }
                    }
                }
            }
        }
        item {
            Text("PocketPad sends local controller input only. It does not stream games or collect analytics.", color = Color(0xFFBEC8DF), fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color(0xFFEAF1FF), modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChanged)
        }
    }
}
