package dev.personal.adaptiveisland.activities

/** Whether the host device is able to run a given feature at all. */
enum class Capability { SUPPORTED, UNSUPPORTED, UNKNOWN }

/** Which component is responsible for showing this feature's UI. */
enum class Ownership { ADAPTIVE_ISLAND, DYNAMIC_PORT, UNDECIDED }

/** Whether the runtime permission a feature needs has been granted. */
enum class PermissionState { NOT_REQUIRED, GRANTED, DENIED }

/**
 * Static description of a feature in the control centre. Runtime state
 * (enabled, ownership, permission) is stored separately and merged in.
 */
data class FeatureDefinition(
    val id: String,
    val title: String,
    val description: String,
    val group: FeatureGroup,
    val defaultCapability: Capability,
    val defaultOwnership: Ownership,
    val requiresOverlay: Boolean = false,
    val isMaster: Boolean = false,
    val defaultEnabled: Boolean = false,
)

enum class FeatureGroup(val label: String) {
    CORE("Core island"),
    SYSTEM("System events"),
    MEDIA("Media, calls and comms"),
    LIVE("Live activities"),
}

object FeatureCatalog {

    val definitions: List<FeatureDefinition> = listOf(
        FeatureDefinition(
            id = "persistent_pill",
            title = "Persistent black pill",
            description = "Keep the idle island visible while enabled.",
            group = FeatureGroup.CORE,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            requiresOverlay = true,
            isMaster = true,
        ),
        FeatureDefinition(
            id = "activity_prioritization",
            title = "Intelligent activity prioritization",
            description = "Let the island decide what deserves attention: calls over music, navigation stays visible, timers rise as they near zero, action-enabled apps on top.",
            group = FeatureGroup.CORE,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            defaultEnabled = true,
        ),
        FeatureDefinition(
            id = "charging",
            title = "Charging status",
            description = "Show charging state and battery level.",
            group = FeatureGroup.SYSTEM,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.UNDECIDED,
            requiresOverlay = true,
        ),
        FeatureDefinition(
            id = "bluetooth",
            title = "Bluetooth audio",
            description = "Show connected earbuds/headset and battery.",
            group = FeatureGroup.SYSTEM,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            requiresOverlay = true,
        ),
        FeatureDefinition(
            id = "music_playback",
            title = "Media playback",
            description = "Track, artwork and transport controls from media sessions.",
            group = FeatureGroup.MEDIA,
            defaultCapability = Capability.UNKNOWN,
            defaultOwnership = Ownership.DYNAMIC_PORT,
        ),
        FeatureDefinition(
            id = "calls",
            title = "Call activity",
            description = "Incoming and ongoing call awareness via TelecomManager.",
            group = FeatureGroup.MEDIA,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            requiresOverlay = true,
        ),
        FeatureDefinition(
            id = "notifications",
            title = "Notification activities",
            description = "Surface selected app notifications as activities.",
            group = FeatureGroup.MEDIA,
            defaultCapability = Capability.UNKNOWN,
            defaultOwnership = Ownership.UNDECIDED,
        ),
        FeatureDefinition(
            id = "timer",
            title = "Timer / Stopwatch",
            description = "Countdown timer and stopwatch with animated digit changes.",
            group = FeatureGroup.SYSTEM,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            requiresOverlay = true,
        ),
        FeatureDefinition(
            id = "navigation",
            title = "Navigation updates",
            description = "Next turn, distance and ETA where exposed.",
            group = FeatureGroup.LIVE,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            requiresOverlay = true,
        ),
        FeatureDefinition(
            id = "delivery",
            title = "Delivery tracking",
            description = "Order and package progress from supported notifications.",
            group = FeatureGroup.LIVE,
            defaultCapability = Capability.UNKNOWN,
            defaultOwnership = Ownership.UNDECIDED,
        ),
    )

    val masters: List<FeatureDefinition> = definitions.filter { it.isMaster }
    val featureDefs: List<FeatureDefinition> = definitions.filterNot { it.isMaster }
}
