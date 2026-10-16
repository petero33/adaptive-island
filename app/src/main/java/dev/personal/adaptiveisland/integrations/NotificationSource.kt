package dev.personal.adaptiveisland.integrations

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import dev.personal.adaptiveisland.overlay.ActivityPriority
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
    val category: String? = null,
    val hasActions: Boolean = false,
    val hasReply: Boolean = false,
    val sensitive: Boolean = false,
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
 * A quick action a notification exposes (e.g. Reply, Mute, Mark read).
 * [requiresText] is true for RemoteInput actions, which need a typed reply.
 */
data class QuickAction(val title: String, val requiresText: Boolean = false)

/**
 * Process-wide store of the notifications the island is currently showing.
 * Populated by [IslandNotificationListener] (a NotificationListenerService).
 */
object NotificationSource {

    private const val TAG = "IslandQuickAction"

    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    /** A live, fire-able action captured from a posting app's notification. */
    internal class RegisteredAction(
        val label: String,
        val intent: PendingIntent,
        val remoteInputs: List<RemoteInput>,
    )

    /** Live, fire-able actions keyed by notification key. Not part of state. */
    private class Registration(
        val actions: List<RegisteredAction>,
        val open: PendingIntent?,
    )

    private val registry =
        java.util.concurrent.ConcurrentHashMap<String, Registration>()

    internal fun register(
        key: String,
        actions: List<RegisteredAction>,
        open: PendingIntent?,
    ) {
        registry[key] = Registration(actions, open)
    }

    /** The labels of the actions the given notification currently exposes. */
    fun actionsFor(key: String): List<QuickAction> =
        registry[key]?.actions?.map {
            QuickAction(it.label, requiresText = it.remoteInputs.isNotEmpty())
        }.orEmpty()

    /**
     * Fires the notification action at [index]. For RemoteInput ("Reply")
     * actions, [text] is attached as the reply result if provided.
     */
    fun fireAction(context: Context, key: String, index: Int, text: String? = null): Boolean {
        val action = registry[key]?.actions?.getOrNull(index) ?: return false
        val fillInIntent = Intent()
        if (!text.isNullOrBlank() && action.remoteInputs.isNotEmpty()) {
            val bundle = Bundle()
            action.remoteInputs.forEach { remoteInput ->
                bundle.putCharSequence(remoteInput.resultKey, text)
            }
            RemoteInput.addResultsToIntent(action.remoteInputs.toTypedArray(), fillInIntent, bundle)
        }
        val options = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode =
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }.toBundle()
        } else {
            null
        }
        return runCatching {
            action.intent.send(context, 0, fillInIntent, null, null, null, options)
        }.onFailure { Log.w(TAG, "fireAction(${action.label}) failed", it) }.isSuccess
    }

    /** Opens the notification (its content intent, deep-linking into the app). */
    fun open(context: Context, key: String): Boolean {
        val pi = registry[key]?.open ?: return false
        val options = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            android.app.ActivityOptions.makeBasic().apply {
                pendingIntentBackgroundActivityStartMode =
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }.toBundle()
        } else {
            null
        }
        return runCatching {
            pi.send(context, 0, null, null, null, null, options)
        }.onFailure { Log.w(TAG, "open failed", it) }.isSuccess
    }

    /**
     * Dismisses the notification from the system status bar and immediately
     * removes it from local island state.
     */
    fun dismiss(key: String) {
        IslandNotificationListener.dismiss(key)
        val current = _notifications.value
        val updated = current.filterNot { it.key == key }
        if (updated != current) {
            publish(updated)
        }
    }

    internal fun publish(items: List<NotificationItem>) {
        val live = items.mapTo(HashSet()) { it.key }
        registry.keys.retainAll(live)
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
    internal var controller: MediaController? = null
        private set

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val playing = state?.state == PlaybackState.STATE_PLAYING ||
                state?.state == PlaybackState.STATE_BUFFERING
            val currentItem = _nowPlaying.value ?: return
            if (currentItem.isPlaying != playing) {
                _nowPlaying.value = currentItem.copy(isPlaying = playing)
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val currentItem = _nowPlaying.value ?: return
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val icon = metadata?.let {
                it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
            }
            _nowPlaying.value = currentItem.copy(
                title = title ?: currentItem.title,
                artist = artist ?: currentItem.artist,
                icon = icon ?: currentItem.icon,
            )
        }
    }

    internal fun attach(item: MediaItem?, controller: MediaController?) {
        if (this.controller !== controller) {
            runCatching { this.controller?.unregisterCallback(mediaCallback) }
            runCatching { controller?.registerCallback(mediaCallback) }
        }
        this.controller = controller
        if (_nowPlaying.value == item) return
        _nowPlaying.value = item
    }

    fun play() {
        controller?.transportControls?.play()
    }

    fun pause() {
        controller?.transportControls?.pause()
    }

    fun toggle() {
        val c = controller ?: return
        val state = c.playbackState?.state
        val tc = c.transportControls
        when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> tc.pause()
            else -> tc.play()
        }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
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
        instance = this
        schedule(immediate = true)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        schedule()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        schedule()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
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
            .filterNot { it.notification?.category == Notification.CATEGORY_TRANSPORT }
            .filter { it.hasVisibleText() }
            // One notification per app: keep the most important (then newest)
            // from each package so a chatty app can't crowd out the island.
            .groupBy { it.packageName }
            .map { (_, group) -> group.maxWith(compareBy({ rankOf(it) }, { it.postTime })) }
            .sortedWith(
                compareByDescending<StatusBarNotification> { rankOf(it) }
                    .thenByDescending { it.postTime },
            )
            .take(MAX_NOTIFICATION_TILES)
            .map { toItem(it) }
            .filterNotNull()
            .toList()
        NotificationSource.publish(items)
    }

    private fun rankOf(sbn: StatusBarNotification): Int =
        ActivityPriority.rankNotification(
            category = sbn.notification?.category,
            hasActions = !sbn.notification?.actions.isNullOrEmpty(),
        )

    private fun publishMedia(all: List<StatusBarNotification>) {
        val sbn = all.firstOrNull { it.notification?.category == Notification.CATEGORY_TRANSPORT }
        if (sbn == null) {
            if (MediaSource.current != null || MediaSource.controller != null) {
                MediaSource.attach(null, null)
            }
            return
        }
        val pair: kotlin.Pair<MediaItem?, MediaController?>? = toMediaWithController(sbn)
        val item = pair?.first
        val controller = pair?.second
        MediaSource.attach(item, controller)
    }

    private fun toMediaWithController(sbn: StatusBarNotification): kotlin.Pair<MediaItem?, MediaController?>? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        val packageName = sbn.packageName ?: return null

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

        val item = MediaItem(
            packageName = packageName,
            appLabel = appLabel(packageName),
            title = title,
            artist = artist,
            icon = icon,
            isPlaying = playing,
            postedAt = sbn.postTime,
        )
        return Pair(item, session)
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
        val icon = largeIcon(notification) ?: appIcon(packageName)

        val key = sbn.key ?: "$packageName-${sbn.postTime}"
        val registered = notification.actions?.mapNotNull { action ->
            val label = action.title?.toString()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val intent = action.actionIntent ?: return@mapNotNull null
            val remoteInputs = action.remoteInputs?.toList().orEmpty()
            NotificationSource.RegisteredAction(label, intent, remoteInputs)
        }.orEmpty()
        val hasReply = registered.any { it.remoteInputs.isNotEmpty() }
        NotificationSource.register(key, registered, notification.contentIntent)

        return NotificationItem(
            key = key,
            packageName = packageName,
            appLabel = appLabel,
            title = title.ifBlank { appLabel },
            text = text,
            postedAt = sbn.postTime,
            icon = icon,
            category = notification.category,
            hasActions = registered.isNotEmpty(),
            hasReply = hasReply,
            sensitive = notification.visibility == Notification.VISIBILITY_SECRET,
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

    companion object {
        private const val DEBOUNCE_MS = 90L
        private var instance: IslandNotificationListener? = null

        fun dismiss(key: String) {
            runCatching { instance?.cancelNotification(key) }
        }
    }
}
