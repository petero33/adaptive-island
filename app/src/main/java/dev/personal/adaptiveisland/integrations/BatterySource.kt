package dev.personal.adaptiveisland.integrations

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** Snapshot of battery/charging state delivered by the platform. */
data class BatteryState(
    val levelPercent: Int,
    val plugged: Boolean,
    val charging: Boolean,
    val full: Boolean,
)

/**
 * Observes ACTION_BATTERY_CHANGED (a sticky, protected broadcast) so we always
 * receive the current value immediately on subscription, with no polling.
 */
object BatterySource {

    fun observe(context: Context): Flow<BatteryState> = callbackFlow {
        val appContext = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let { trySend(it.toBatteryState()) }
            }
        }
        val sticky = ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let { trySend(it.toBatteryState()) }
        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged().conflate()

    private fun Intent.toBatteryState(): BatteryState {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 0
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return BatteryState(
            levelPercent = percent.coerceIn(0, 100),
            plugged = plugged,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            full = status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }
}
