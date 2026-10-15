package dev.personal.adaptiveisland.overlay

/**
 * A single live "activity" the island can present. Each activity becomes its own
 * tile in the expanded panel; the newest/primary one drives the compact pill.
 */
sealed interface IslandActivity {
    val id: String

    data class Charging(
        val level: Int,
        val charging: Boolean,
        val full: Boolean,
    ) : IslandActivity {
        override val id: String = "charging"
    }

    data class Bluetooth(
        val deviceName: String?,
        val batteryPercent: Int?,
    ) : IslandActivity {
        override val id: String = "bluetooth"
    }

    data class Notification(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val icon: android.graphics.Bitmap?,
        val category: String? = null,
        val hasActions: Boolean = false,
    ) : IslandActivity {
        override val id: String = "notification:$key"
    }

    data class Media(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val artist: String?,
        val icon: android.graphics.Bitmap?,
        val isPlaying: Boolean,
    ) : IslandActivity {
        override val id: String = "media"
    }
}
