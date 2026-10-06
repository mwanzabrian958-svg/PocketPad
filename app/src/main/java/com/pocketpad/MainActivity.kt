package com.pocketpad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.pocketpad.ui.PocketPadApp
import com.pocketpad.ui.PocketPadViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: PocketPadViewModel by viewModels()
    private var cableReceiver: BroadcastReceiver? = null

    override fun onStart() {
        super.onStart()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                viewModel.setUsbCableConnected(
                    (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0
                )
            }
        }
        cableReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        if (stickyIntent != null) receiver.onReceive(this, stickyIntent)
    }

    override fun onStop() {
        cableReceiver?.let(::unregisterReceiver)
        cableReceiver = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val settings by viewModel.settings.collectAsState()
            val dark = settings.darkTheme ?: isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (settings.highContrast) {
                    darkColorScheme(
                        primary = Color(0xFFFFFF00),
                        secondary = Color(0xFF00FFFF),
                        background = Color(0xFF000000),
                        surface = Color(0xFF000000),
                        onSurface = Color(0xFFFFFFFF),
                        onPrimary = Color(0xFF000000)
                    )
                } else if (dark) {
                    darkColorScheme(
                        primary = Color(0xFF64E7F2),
                        secondary = Color(0xFFB996FF),
                        background = Color(0xFF080B18),
                        surface = Color(0xFF141A2B),
                        onSurface = Color(0xFFF4F6FF)
                    )
                } else {
                    lightColorScheme(
                        primary = Color(0xFF087E8B),
                        secondary = Color(0xFF6941C6)
                    )
                }
            ) {
                PocketPadApp(
                    navController = rememberNavController(),
                    viewModel = viewModel,
                    lowPower = settings.lowPower
                )
            }
        }
    }
}
