package dev.personal.adaptiveisland.app

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.personal.adaptiveisland.activities.FeatureCatalog
import dev.personal.adaptiveisland.activities.FeatureDefinition
import dev.personal.adaptiveisland.activities.FeatureUiState
import dev.personal.adaptiveisland.activities.Ownership
import dev.personal.adaptiveisland.activities.PermissionState
import dev.personal.adaptiveisland.overlay.IslandGeometry
import dev.personal.adaptiveisland.persistence.FeaturePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FeatureControlViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = FeaturePreferences(application)
    private val overlayGranted = MutableStateFlow(Settings.canDrawOverlays(application))

    val features: StateFlow<List<FeatureUiState>> =
        combine(FeatureCatalog.definitions.map(::stateFlow)) { it.toList() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = FeatureCatalog.definitions.map(::defaultState),
            )

    val topGapDp: StateFlow<Int> = prefs.topGapDpFlow()
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = IslandGeometry.TOP_GAP_DP)

    val pillHeightDp: StateFlow<Int> = prefs.pillHeightDpFlow()
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = IslandGeometry.PILL_HEIGHT_DP)

    val idleWidthDp: StateFlow<Int> = prefs.idleWidthDpFlow()
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = IslandGeometry.IDLE_WIDTH_DP)

    /** Whether the master pill toggle is on. */
    val masterEnabled: StateFlow<Boolean> = features
        .map { list -> list.firstOrNull { it.definition.isMaster }?.enabled == true }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = false)

    /**
     * Whether the overlay should be running: any enabled feature that needs the
     * overlay window, gated by the overlay permission. Drives the service
     * lifecycle so features like charging can show themselves independently of
     * the master pill.
     */
    val overlayShouldRun: StateFlow<Boolean> = combine(
        features.map { list -> list.any { it.enabled && it.definition.requiresOverlay } },
        overlayGranted,
    ) { wanted, granted -> wanted && granted }
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = false)

    fun refreshPermissions() {
        overlayGranted.value = Settings.canDrawOverlays(getApplication())
    }

    fun setEnabled(featureId: String, enabled: Boolean) {
        viewModelScope.launch { prefs.setEnabled(featureId, enabled) }
    }

    fun setOwnership(featureId: String, ownership: Ownership) {
        viewModelScope.launch { prefs.setOwnership(featureId, ownership.name) }
    }

    fun setTopGapDp(value: Int) {
        viewModelScope.launch { prefs.setTopGapDp(value.coerceIn(0, 30)) }
    }

    fun setPillHeightDp(value: Int) {
        viewModelScope.launch { prefs.setPillHeightDp(value.coerceIn(28, 50)) }
    }

    fun setIdleWidthDp(value: Int) {
        viewModelScope.launch { prefs.setIdleWidthDp(value.coerceIn(60, 140)) }
    }

    fun resetGeometry() {
        viewModelScope.launch { prefs.resetGeometry() }
    }

    private fun stateFlow(definition: FeatureDefinition): Flow<FeatureUiState> =
        combine(
            prefs.enabledFlow(definition.id),
            prefs.ownershipFlow(definition.id),
            overlayGranted,
        ) { enabled, ownership, granted ->
            FeatureUiState(
                definition = definition,
                enabled = enabled ?: definition.isMaster,
                ownership = ownership
                    ?.let { stored -> runCatching { Ownership.valueOf(stored) }.getOrNull() }
                    ?: definition.defaultOwnership,
                capability = definition.defaultCapability,
                permission = permissionFor(definition, granted),
            )
        }

    private fun defaultState(definition: FeatureDefinition) = FeatureUiState(
        definition = definition,
        enabled = definition.isMaster,
        ownership = definition.defaultOwnership,
        capability = definition.defaultCapability,
        permission = permissionFor(definition, overlayGranted.value),
    )

    private fun permissionFor(definition: FeatureDefinition, granted: Boolean): PermissionState = when {
        !definition.requiresOverlay -> PermissionState.NOT_REQUIRED
        granted -> PermissionState.GRANTED
        else -> PermissionState.DENIED
    }
}
