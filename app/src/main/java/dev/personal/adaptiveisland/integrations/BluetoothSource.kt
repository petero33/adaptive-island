package dev.personal.adaptiveisland.integrations

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Snapshot of the currently connected Bluetooth audio device.
 *
 * Note: Android only exposes a single overall battery level (API 31+) and only
 * when the accessory reports it. Per-bud (L/R) battery is not available through
 * public APIs.
 */
data class BluetoothState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
)

object BluetoothSource {

    fun observe(context: Context): Flow<BluetoothState> = callbackFlow {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                trySend(current(app))
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(current(app))
        awaitClose { app.unregisterReceiver(receiver) }
    }.distinctUntilChanged().conflate()

    fun current(context: Context): BluetoothState {
        if (!hasConnectPermission(context)) return BluetoothState()
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return BluetoothState()
        val device = firstAudioDevice(manager) ?: return BluetoothState()
        val name = runCatching { device.name }.getOrNull()
        val battery = readBattery(device)
        return BluetoothState(connected = true, deviceName = name, batteryPercent = battery)
    }

    /**
     * BluetoothDevice#getBatteryLevel() is @hide, so we call it reflectively and
     * treat any failure / out-of-range value as "unknown" (null).
     */
    private fun readBattery(device: BluetoothDevice): Int? = runCatching {
        val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
        val value = method.invoke(device) as? Int
        value?.takeIf { it in 0..100 }
    }.getOrNull()

    private fun firstAudioDevice(manager: BluetoothManager): BluetoothDevice? {
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        for (profile in profiles) {
            val devices = runCatching { manager.getConnectedDevices(profile) }.getOrNull().orEmpty()
            devices.firstOrNull { it.bondState == BluetoothDevice.BOND_BONDED }?.let { return it }
            devices.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
