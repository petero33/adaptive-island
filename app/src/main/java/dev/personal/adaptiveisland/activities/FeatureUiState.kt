package dev.personal.adaptiveisland.activities

/** Merged static definition plus runtime state for one control-centre row. */
data class FeatureUiState(
    val definition: FeatureDefinition,
    val enabled: Boolean,
    val ownership: Ownership,
    val capability: Capability,
    val permission: PermissionState,
)
