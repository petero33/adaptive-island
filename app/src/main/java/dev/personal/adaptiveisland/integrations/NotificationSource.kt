package dev.personal.adaptiveisland.integrations

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val ICON_PX = 128

/** A single notification surfaced as an island activity. */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val icon: Bitmap?,
)

/**
 * Process-wide store of the notifications the island is currently showing.
 * Populated by [IslandNotificationListener] (a NotificationListenerService).
 */
object NotificationSource {

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    internal fun publish(items: List<NotificationItem>) {
        _notifications.value = items
    }

    /** True when the user has granted notification-listener access to this app. */
    fun hasAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val component = ComponentName(context, IslandNotificationListener::class.java)
        return flat.split(":").any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }
}

/**
 * Keeps [NotificationSource] in sync with the active notifications.
 * Ongoing/system notifications (music players, etc.) are filtered out.
 */
class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh()
    }

    private fun refresh() {
        val items = runCatching {
            activeNotifications.orEmpty()
                .asSequence()
                .filterNot { it.isOngoing }
                .mapNotNull { toItem(it) }
                .sortedByDescending { it.postedAt }
                .toList()
        }.getOrDefault(emptyList())
        NotificationSource.publish(items)
    }

    private fun toItem(sbn: StatusBarNotification): NotificationItem? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
                .orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        val packageName = sbn.packageName ?: return null
        val appLabel = appLabel(packageName)
        val icon = appIcon(packageName) ?: largeIcon(notification)

        return NotificationItem(
            key = sbn.key ?: "$packageName-${sbn.postTime}",
            packageName = packageName,
            appLabel = appLabel,
            title = title.ifBlank { appLabel },
            text = text,
            postedAt = sbn.postTime,
            icon = icon,
        )
    }

    private fun appLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /**
     * The real launcher icon of the posting app.
     *
     * Adaptive icons report an intrinsic size of -1 (no intrinsic bounds), which
     * previously produced a 1x1 bitmap and looked like the default Android icon.
     * We therefore always rasterise to a fixed, generous size.
     */
    private fun appIcon(packageName: String): Bitmap? = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationIcon(info).renderIcon()
    } catch (_: Exception) {
        null
    }

    /** The notification's own large icon (often the app mark or a contact photo). */
    @Suppress("DEPRECATION")
    private fun largeIcon(notification: Notification): Bitmap? = try {
        notification.extras?.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap
    } catch (_: Exception) {
        null
    }

    private fun Drawable.renderIcon(size: Int = ICON_PX): Bitmap =
        if (this is BitmapDrawable && bitmap != null) {
            bitmap
        } else {
            toBitmap(size, size, Bitmap.Config.ARGB_8888)
        }
}
