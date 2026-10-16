package dev.personal.adaptiveisland.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.personal.adaptiveisland.app.theme.AdaptiveIslandTheme
import dev.personal.adaptiveisland.capabilities.DeviceProfile
import dev.personal.adaptiveisland.capabilities.DeviceProfileReader
import dev.personal.adaptiveisland.integrations.NotificationSource
import dev.personal.adaptiveisland.overlay.IslandOverlayService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: FeatureControlViewModel by viewModels()
    private var profile by mutableStateOf<DeviceProfile?>(null)
    private var askedNotifications = false

    private val notificationPermission =
        ActivityResultContracts.RequestPermission().let { contract ->
            registerForActivityResult(contract) { }
        }
    private val bluetoothPermission =
        ActivityResultContracts.RequestPermission().let { contract ->
            registerForActivityResult(contract) { }
        }
    private val phoneStatePermission =
        ActivityResultContracts.RequestPermission().let { contract ->
            registerForActivityResult(contract) { }
        }
    private var askedBluetooth = false
    private var askedPhoneState = false
    private var askedNotificationAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshProfile()
        setContent {
            AdaptiveIslandTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val current = profile
                    if (current != null) {
                        AdaptiveIslandApp(profile = current, viewModel = viewModel)
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        window.decorView.post { refreshProfile(withCutout = true) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.overlayShouldRun.collect { syncOverlayService() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (profile != null) refreshProfile(withCutout = true)
        viewModel.refreshPermissions()
        syncOverlayService()
        maybeRequestNotificationPermission()
        maybeRequestBluetoothPermission()
        maybeRequestPhoneStatePermission()
        maybeRequestNotificationAccess()
    }
    private fun syncOverlayService() {
        val enabled = viewModel.overlayShouldRun.value
        if (enabled && Settings.canDrawOverlays(this)) {
            IslandOverlayService.start(this)
        } else {
            IslandOverlayService.stop(this)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (askedNotifications) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!viewModel.overlayShouldRun.value) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybeRequestBluetoothPermission() {
        if (askedBluetooth) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val enabled = viewModel.features.value
            .firstOrNull { it.definition.id == "bluetooth" }?.enabled == true
        if (!enabled) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            askedBluetooth = true
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun maybeRequestPhoneStatePermission() {
        if (askedPhoneState) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val enabled = viewModel.features.value
            .firstOrNull { it.definition.id == "calls" }?.enabled == true
        if (!enabled) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            askedPhoneState = true
            phoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    /**
     * Notification-listener access is a Settings toggle, not a runtime permission,
     * so send the user to the listener settings screen once.
     */
    private fun maybeRequestNotificationAccess() {
        if (askedNotificationAccess) return
        val enabled = viewModel.features.value
            .firstOrNull { it.definition.id == "notifications" }?.enabled == true
        if (!enabled) return
        if (NotificationSource.hasAccess(this)) return
        askedNotificationAccess = true
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun refreshProfile(withCutout: Boolean = false) {
        val previousCutout = profile?.cutoutSummary ?: "pending"
        val base = DeviceProfileReader.read(this, cutoutSummary = previousCutout)
        profile = if (withCutout) {
            base.copy(cutoutSummary = DeviceProfileReader.readCutout(this))
        } else {
            base
        }
    }
}
