package com.ggsapple.remotear.ui.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ggsapple.remotear.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun rememberBatteryLevel(): String {
    val context = LocalContext.current
    var level by remember { mutableStateOf("—%") }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val batteryPct = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                    BatteryManager.BATTERY_STATUS_CHARGING
                if (batteryPct >= 0 && scale > 0) {
                    val pct = (batteryPct * 100) / scale
                    level = if (charging) "⚡$pct%" else "$pct%"
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    return level
}

@Composable
fun rememberWifiBars(): Int {
    val context = LocalContext.current
    var bars by remember { mutableStateOf(0) }

    DisposableEffect(context) {
        val scope = CoroutineScope(Dispatchers.Main)
        val job = scope.launch {
            while (isActive) {
                bars = readWifiBars(context)
                delay(3000)
            }
        }
        onDispose { job.cancel() }
    }

    return bars
}

private fun readWifiBars(context: Context): Int {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 0
    val network = cm.activeNetwork ?: return 0
    val caps = cm.getNetworkCapabilities(network) ?: return 0
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return 1
    val strength = caps.signalStrength
    return when {
        strength >= -50 -> 4
        strength >= -60 -> 3
        strength >= -70 -> 2
        else -> 1
    }
}

@Composable
fun rememberConnectionReady(runtimeConfigRepository: RuntimeConfigRepository): Boolean {
    var ready by remember { mutableStateOf(false) }
    val context = LocalContext.current

    DisposableEffect(runtimeConfigRepository, context) {
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            while (isActive) {
                ready = runCatching {
                    val url = runtimeConfigRepository.apiUrlBlocking()
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val network = cm.activeNetwork
                    network != null && cm.getNetworkCapabilities(network)?.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET,
                    ) == true && url.isNotBlank()
                }.getOrDefault(false)
                delay(5000)
            }
        }
        onDispose { job.cancel() }
    }

    return ready
}
