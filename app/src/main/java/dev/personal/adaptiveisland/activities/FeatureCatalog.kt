package dev.personal.adaptiveisland.activities

/** Whether the host device is able to run a given feature at all. */
enum class Capability { SUPPORTED, UNSUPPORTED, UNKNOWN }

/** Which component is responsible for showing this feature's UI. */
enum class Ownership { ADAPTIVE_ISLAND, DYNAMIC_PORT, UNDECIDED }

/** Whether the runtime permission a feature needs has been granted. */
enum class PermissionState { NOT_REQUIRED, GRANTED, DENIED }

/**
 * A coarse, honest estimate of the background work a feature costs. Shown in the
 * diagnostics card so users can weigh usefulness against resource use rather
 * than trusting a vague "battery saver" claim.
 */
enum class FeatureCost(val label: String, val detail: String) {
    LOW("Low", "Negligible background work"),
    MEDIUM("Medium", "Occasional listeners or image decoding"),
    HIGH("High", "Continuous listeners, icons and artwork"),
}

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
    val cost: FeatureCost = FeatureCost.LOW,
    val resourceNote: String = "",
)

enum class FeatureGroup(val label: String) {
    CORE("Core island"),
    SYSTEM("System events"),
    MEDIA("Media, calls and comms"),
    LIVE("Live activities"),
    PRIVACY("Privacy and power"),
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
            cost = FeatureCost.LOW,
            resourceNote = "Draws a single static window; it never polls.",
        ),
        FeatureDefinition(
            id = "activity_prioritization",
            title = "Intelligent activity prioritization",
            description = "Let the island decide what deserves attention: calls over music, navigation stays visible, timers rise as they near zero, action-enabled apps on top.",
            group = FeatureGroup.CORE,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            defaultEnabled = true,
            cost = FeatureCost.LOW,
            resourceNote = "Ranks a handful of activities in memory; no extra listeners.",
        ),
        FeatureDefinition(
            id = "activity_stack",
            title = "Activity stack",
            description = "Keep several activities side by side and swipe between them in the pill. Remembers the last one you used.",
            group = FeatureGroup.CORE,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            defaultEnabled = true,
            cost = FeatureCost.LOW,
            resourceNote = "Keeps a small ordered list; no additional observers.",
        ),
        FeatureDefinition(
            id = "low_power_mode",
            title = "Adaptive low-power mode",
            description = "Scale the island's own work to what's needed: no idle animation, lighter effects on low battery, no redraws for unchanged information, and optional integrations paused while the island is off.",
            group = FeatureGroup.CORE,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            defaultEnabled = true,
            cost = FeatureCost.LOW,
            resourceNote = "Pauses animations and optional listeners, saving power rather than spending it.",
        ),
        FeatureDefinition(
            id = "quick_actions",
            title = "Contextual quick actions",
            description = "Show only the controls relevant to the current activity: media transport, call actions, navigation hand-off and notification shortcuts.",
            group = FeatureGroup.LIVE,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            defaultEnabled = true,
            cost = FeatureCost.MEDIUM,
            resourceNote = "Holds each notification's live actions so chips can fire them.",
        ),
        FeatureDefinition(
            id = "privacy_shield",
            title = "Notification privacy shield",
            description = "Hide message previews on the island while keeping the app icon. Shows a neutral indicator instead, keeps sensitive alerts out of the expanded card, and applies to every notification source at once.",
            group = FeatureGroup.PRIVACY,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            defaultEnabled = false,
            cost = FeatureCost.LOW,
            resourceNote = "Rewrites text before it is drawn; no extra listeners.",
        ),
        FeatureDefinition(
            id = "discreet_mode",
            title = "Discreet mode",
            description = "Temporarily replace every activity's details — notifications and media alike — with a neutral label until you turn it back off. Handy when someone is looking over your shoulder.",
            group = FeatureGroup.PRIVACY,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            defaultEnabled = false,
            cost = FeatureCost.LOW,
            resourceNote = "Rewrites text before it is drawn; no extra listeners.",
        ),
        FeatureDefinition(
            id = "charging",
            title = "Charging status",
            description = "Show charging state and battery level.",
            group = FeatureGroup.SYSTEM,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.UNDECIDED,
            requiresOverlay = true,
            cost = FeatureCost.LOW,
            resourceNote = "Uses the sticky battery broadcast; no polling.",
        ),
        FeatureDefinition(
            id = "bluetooth",
            title = "Bluetooth audio",
            description = "Show connected earbuds/headset and battery.",
            group = FeatureGroup.SYSTEM,
            defaultCapability = Capability.SUPPORTED,
            defaultOwnership = Ownership.ADAPTIVE_ISLAND,
            requiresOverlay = true,
            cost = FeatureCost.MEDIUM,
            resourceNote = "Reads connected audio devices when they change.",
        ),
        FeatureDefinition(
            id = "music_playback",
            title = "Media playback",
            description = "Track, artwork and transport controls from media sessions.",
            group = FeatureGroup.MEDIA,
            defaultCapability = Capability.UNKNOWN,
            defaultOwnership = Ownership.DYNAMIC_PORT,
            cost = FeatureCost.HIGH,
            resourceNote = "Subscribes to media sessions and decodes artwork.",
        ),
        FeatureDefinition(
            id = "calls",
            title = "Call activity",
            description = "Incoming and ongoing call awareness.",
            group = FeatureGroup.MEDIA,
            defaultCapability = Capability.UNKNOWN,
            defaultOwnership = Ownership.DYNAMIC_PORT,
            cost = FeatureCost.MEDIUM,
            resourceNote = "Parses call notifications only.",
        ),
        FeatureDefinition(
            id = "notifications",
            title = "Notification activities",
            description = "Surface selected app notifications as activities.",
            group = FeatureGroup.MEDIA,
            defaultCapability = Capability.UNKNOWN,
            defaultOwnership = Ownership.UNDECIDED,
            cost = FeatureCost.HIGH,
            resourceNote = "Listens for notifications and rasterises app icons.",
        ),
        FeatureDefinition(
            id = "navigation",
            title = "Navigation updates",
            description = "Next turn, distance and ETA where exposed.",
            group = FeatureGroup.LIVE,
            defaultCapability = Capability.UNKNOWN,
            defaultOwnership = Ownership.UNDECIDED,
            cost = FeatureCost.MEDIUM,
            resourceNote = "Reads navigation notifications only.",
        ),
        FeatureDefinition(
            id = "delivery",
            title = "Delivery tracking",
            description = "Order and package progress from supported notifications.",
            group = FeatureGroup.LIVE,
            defaultCapability = Capability.UNKNOWN,
            defaultOwnership = Ownership.UNDECIDED,
            cost = FeatureCost.LOW,
            resourceNote = "Reads delivery notifications only.",
        ),
    )

    val masters: List<FeatureDefinition> = definitions.filter { it.isMaster }
    val featureDefs: List<FeatureDefinition> = definitions.filterNot { it.isMaster }
}
