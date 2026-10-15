package dev.personal.adaptiveisland.integrations

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Down-sampled size for every rendered icon. Keeps each bitmap at ~36 KB. */
private const val ICON_PX = 96

/** How many notifications we ever surface. Nothing beyond this is rasterised. */
const val MAX_NOTIFICATION_TILES = 3

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

/** The current media session, surfaced as an island activity. */
data class MediaItem(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String?,
    val icon: Bitmap?,
    val isPlaying: Boolean,
    val postedAt: Long,
)

/**
 * Caching helpers shared by the notification listener. Icons and labels are
 * expensive to resolve and almost never change, so we memoise them.
 */
internal object MediaCache {
    private val icons = LruCache<String, Bitmap>(16)
    private val labels = LruCache<String, String>(32)

    fun icon(packageName: String, loader: () -> Bitmap?): Bitmap? {
        icons.get(packageName)?.let { return it }
        val rendered = loader() ?: return null
        icons.put(packageName, rendered)
        return rendered
    }

    fun label(packageName: String, loader: () -> String): String {
        labels.get(packageName)?.let { return it }
        val value = loader()
        labels.put(packageName, value)
        return value
    }
}

/**
 * Process-wide store of the notifications the island is currently showing.
 * Populated by [IslandNotificationListener] (a NotificationListenerService).
 */
object NotificationSource {

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    internal fun publish(items: List<NotificationItem>) {
        // Reference-equality on the cached bitmaps makes this cheap and stops
        // identical refreshes from triggering recomposition.
        if (_notifications.value == items) return
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

/** Process-wide store of the currently playing media session. */
object MediaSource {

    private val _nowPlaying = MutableStateFlow<MediaItem?>(null)
    val nowPlaying: StateFlow<MediaItem?> = _nowPlaying.asStateFlow()

    internal val current: MediaItem? get() = _nowPlaying.value

    internal fun publish(item: MediaItem?) {
        if (_nowPlaying.value == item) return
        _nowPlaying.value = item
    }
}

/**
 * Keeps [NotificationSource] and [MediaSource] in sync with the active
 * notifications. Ongoing/system notifications (music players, etc.) are filtered
 * out of the notification list; the media one is promoted to [MediaSource].
 *
 * Refreshes are debounced and only the visible tiles ever get their icons
 * rasterised, so bursts of notifications stay cheap.
 */
class IslandNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pending: Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        schedule(immediate = true)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        schedule()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        schedule()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun schedule(immediate: Boolean = false) {
        pending?.cancel()
        pending = scope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            refresh()
        }
    }

    private fun refresh() {
        val all = runCatching { activeNotifications.orEmpty().toList() }
            .getOrDefault(emptyList())
        publishMedia(all)
        publishNotifications(all)
    }

    private fun publishNotifications(all: List<StatusBarNotification>) {
        val items = all.asSequence()
            .filterNot { it.isOngoing }
            .filter { it.hasVisibleText() }
            .sortedByDescending { it.postTime }
            .take(MAX_NOTIFICATION_TILES)
            .map { toItem(it) }
            .filterNotNull()
            .toList()
        NotificationSource.publish(items)
    }

    private fun publishMedia(all: List<StatusBarNotification>) {
        val sbn = all.firstOrNull { it.notification?.category == Notification.CATEGORY_TRANSPORT }
        val item = sbn?.let { toMedia(it) }
        MediaSource.publish(item)
    }

    private fun toItem(sbn: StatusBarNotification): NotificationItem? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
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

    private fun toMedia(sbn: StatusBarNotification): MediaItem? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        val packageName = sbn.packageName ?: return null

        // Prefer the notification's own text; fall back to the live media
        // session metadata, which is all some players (and system cast UIs)
        // expose — their notifications carry only a session token.
        val session = resolveSession(notification)
        val metadata = session?.metadata

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: return null

        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }

        val icon = largeIcon(notification)
            ?: metadata?.let { it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART) }
            ?: appIcon(packageName)

        val playing = session?.playbackState?.state == PlaybackState.STATE_PLAYING ||
            notification.isPlaying()

        return MediaItem(
            packageName = packageName,
            appLabel = appLabel(packageName),
            title = title,
            artist = artist,
            icon = icon,
            isPlaying = playing,
            postedAt = sbn.postTime,
        )
    }

    /**
     * Resolves the [MediaController] a transport notification points at, so we
     * can read its metadata and playback state. A NotificationListenerService is
     * allowed to query active sessions; we look the token up among them.
     */
    private fun resolveSession(notification: Notification): MediaController? {
        @Suppress("DEPRECATION")
        val token = notification.extras
            ?.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            ?: return null
        val manager = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        val component = ComponentName(this, IslandNotificationListener::class.java)
        return runCatching {
            manager.getActiveSessions(component).firstOrNull { it.sessionToken == token }
        }.getOrNull()
    }

    private fun StatusBarNotification.hasVisibleText(): Boolean {
        val extras = notification?.extras ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        return title.isNotBlank() || text.isNotBlank()
    }

    /** A transport notification exposes a "Pause" action while it is playing. */
    private fun Notification.isPlaying(): Boolean {
        val actions = actions ?: return true
        for (action in actions) {
            val label = action.title?.toString()?.lowercase() ?: continue
            if ("pause" in label) return true
            if ("play" in label || "resume" in label) return false
        }
        return true
    }

    private fun appLabel(packageName: String): String =
        MediaCache.label(packageName) {
            runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(packageName)
        }

    /**
     * The real launcher icon of the posting app, rasterised once to [ICON_PX].
     *
     * Adaptive icons report an intrinsic size of -1 (no intrinsic bounds), which
     * previously produced a 1x1 bitmap and looked like the default Android icon.
     * Always rasterising to a fixed size solves that and keeps memory tiny.
     */
    private fun appIcon(packageName: String): Bitmap? =
        MediaCache.icon(packageName) {
            runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationIcon(info).toBitmap(
                    ICON_PX,
                    ICON_PX,
                    Bitmap.Config.ARGB_8888,
                )
            }.getOrNull()
        }

    /** The notification's own large icon (often the app mark or a contact photo). */
    @Suppress("DEPRECATION")
    private fun largeIcon(notification: Notification): Bitmap? = try {
        notification.extras?.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val DEBOUNCE_MS = 90L
    }
}
