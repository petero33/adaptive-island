package dev.personal.adaptiveisland.integrations

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** Snapshot of the device's telephony call state. */
data class CallState(
    val active: Boolean = false,
    val callState: Int = TelephonyManager.CALL_STATE_IDLE,
    val handle: String? = null,
)

object CallSource {

    /** Call state constants (mirrors TelephonyManager for readability). */
    private const val CALL_STATE_IDLE = 0
    private const val CALL_STATE_RINGING = 1
    private const val CALL_STATE_OFFHOOK = 2

    @Suppress("DEPRECATION")
    fun observe(context: Context): Flow<CallState> = callbackFlow {
        val telephonyManager =
            context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return@callbackFlow

        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                trySend(
                    CallState(
                        active = state == CALL_STATE_RINGING || state == CALL_STATE_OFFHOOK,
                        callState = state,
                        handle = incomingNumber,
                    )
                )
            }
        }

        // LISTEN_CALL_STATE requires READ_PHONE_STATE on API 23+. Without it the
        // listener simply receives no callbacks and the island reports idle.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (android.content.pm.PackageManager.PERMISSION_GRANTED !=
                context.applicationContext.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
            ) {
                trySend(CallState())
                awaitClose {}
                return@callbackFlow
            }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        trySend(CallState())
        awaitClose {
            runCatching { telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE) }
        }
    }.distinctUntilChanged().conflate()
}
