package dev.personal.adaptiveisland.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.personal.adaptiveisland.activities.Capability
import dev.personal.adaptiveisland.activities.FeatureGroup
import dev.personal.adaptiveisland.activities.FeatureUiState
import dev.personal.adaptiveisland.activities.Ownership
import dev.personal.adaptiveisland.capabilities.DeviceProfile
import dev.personal.adaptiveisland.overlay.IslandGeometry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureControlScreen(
    profile: DeviceProfile,
    features: List<FeatureUiState>,
    topGapDp: Int = IslandGeometry.TOP_GAP_DP,
    pillHeightDp: Int = IslandGeometry.PILL_HEIGHT_DP,
    idleWidthDp: Int = IslandGeometry.IDLE_WIDTH_DP,
    onToggle: (featureId: String, enabled: Boolean) -> Unit,
    onOwnership: (featureId: String, ownership: Ownership) -> Unit,
    onSetTopGapDp: (Int) -> Unit = {},
    onSetPillHeightDp: (Int) -> Unit = {},
    onSetIdleWidthDp: (Int) -> Unit = {},
    onResetGeometry: () -> Unit = {},
    onRequestOverlay: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Feature control centre") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ProfileCard(profile) }

            item {
                PositioningCard(
                    topGapDp = topGapDp,
                    pillHeightDp = pillHeightDp,
                    idleWidthDp = idleWidthDp,
                    onSetTopGapDp = onSetTopGapDp,
                    onSetPillHeightDp = onSetPillHeightDp,
                    onSetIdleWidthDp = onSetIdleWidthDp,
                    onResetGeometry = onResetGeometry,
                )
            }

            val master = features.firstOrNull { it.definition.isMaster }
            if (master != null) {
                item {
                    MasterCard(
                        state = master,
                        overlayGranted = profile.hasOverlayPermission,
                        onToggle = { onToggle(master.definition.id, it) },
                        onRequestOverlay = onRequestOverlay,
                    )
                }
            }

            FeatureGroup.entries.forEach { group ->
                val groupItems = features.filter {
                    it.definition.group == group && !it.definition.isMaster
                }
                if (groupItems.isNotEmpty()) {
                    item { SectionHeader(group.label) }
                    items(groupItems, key = { it.definition.id }) { state ->
                        FeatureRow(
                            state = state,
                            onToggle = { onToggle(state.definition.id, it) },
                            onOwnership = { onOwnership(state.definition.id, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: DeviceProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Device profile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            KeyValueRow("Device", profile.hardwareLabel)
            KeyValueRow("System", profile.sdkLabel)
            KeyValueRow("Resolution", profile.resolutionLabel)
            KeyValueRow("Density", profile.densityLabel)
            KeyValueRow("Size", "${profile.dpLabel} · smallest ${profile.smallestWidthLabel}")
            KeyValueRow("Cutout", profile.cutoutSummary)
        }
    }
}

@Composable
private fun PositioningCard(
    topGapDp: Int,
    pillHeightDp: Int,
    idleWidthDp: Int,
    onSetTopGapDp: (Int) -> Unit,
    onSetPillHeightDp: (Int) -> Unit,
    onSetIdleWidthDp: (Int) -> Unit,
    onResetGeometry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Island Alignment & Cutout Tuning",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Calibrated for TECNO SPARK 50 punch-hole camera. Adjusting values updates the overlay in real time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            StepperRow(
                label = "Top Margin",
                value = topGapDp,
                unit = "dp",
                min = 0,
                max = 24,
                onDecrease = { onSetTopGapDp(topGapDp - 1) },
                onIncrease = { onSetTopGapDp(topGapDp + 1) },
            )
            Spacer(Modifier.height(8.dp))

            StepperRow(
                label = "Pill Height",
                value = pillHeightDp,
                unit = "dp",
                min = 30,
                max = 48,
                onDecrease = { onSetPillHeightDp(pillHeightDp - 1) },
                onIncrease = { onSetPillHeightDp(pillHeightDp + 1) },
            )
            Spacer(Modifier.height(8.dp))

            StepperRow(
                label = "Idle Width",
                value = idleWidthDp,
                unit = "dp",
                min = 60,
                max = 120,
                onDecrease = { onSetIdleWidthDp(idleWidthDp - 4) },
                onIncrease = { onSetIdleWidthDp(idleWidthDp + 4) },
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onResetGeometry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reset to Calibrated Defaults")
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    unit: String,
    min: Int,
    max: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = "$value $unit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onDecrease,
                enabled = value > min,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("-")
            }
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(28.dp),
            )
            FilledTonalButton(
                onClick = onIncrease,
                enabled = value < max,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun MasterCard(
    state: FeatureUiState,
    overlayGranted: Boolean,
    onToggle: (Boolean) -> Unit,
    onRequestOverlay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.definition.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.definition.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = onToggle)
            }
            if (state.enabled && !overlayGranted) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Overlay access is required before the pill can appear over other apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRequestOverlay) { Text("Grant overlay access") }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    state: FeatureUiState,
    onToggle: (Boolean) -> Unit,
    onOwnership: (Ownership) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.definition.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.definition.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = statusLabel(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Takeover",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ownership.entries.forEach { option ->
                    FilterChip(
                        selected = state.ownership == option,
                        onClick = { onOwnership(option) },
                        label = { Text(optionLabel(option)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.65f),
        )
    }
}

private fun statusLabel(state: FeatureUiState): String = when {
    state.ownership == Ownership.DYNAMIC_PORT -> "Handled by Dynamic Port — not taking over"
    state.capability == Capability.UNKNOWN -> "Awaiting device testing"
    state.capability == Capability.UNSUPPORTED -> "Not supported on this device"
    state.enabled -> "Enabled"
    else -> "Available to configure"
}

private fun optionLabel(ownership: Ownership): String = when (ownership) {
    Ownership.ADAPTIVE_ISLAND -> "Adaptive Island"
    Ownership.DYNAMIC_PORT -> "Dynamic Port"
    Ownership.UNDECIDED -> "Undecided"
}
