package dev.personal.adaptiveisland.overlay

import android.app.Notification

/**
 * Ranks island activities so the island can decide what deserves attention
 * instead of blindly showing whatever arrived last.
 *
 * Higher score = more important = shown higher (and drives the compact pill).
 *
 * The ordering is the one Apple can't match on Android:
 *   call > timer/alarm > navigation > media > action-enabled app > plain
 *   notification > charging > bluetooth.
 *
 * Action-enabled sources (media transport, rich notifications with actions)
 * outrank passive ones so interactive experiences sit at the top.
 */
object ActivityPriority {

    const val CALL = 100
    const val TIMER = 90
    const val NAVIGATION = 80
    const val MEDIA = 70
    const val ACTIONABLE = 50
    const val NOTIFICATION = 30
    const val CHARGING = 20
    const val BLUETOOTH = 10

    /** Score a fully-built activity. */
    fun score(activity: IslandActivity): Int = when (activity) {
        is IslandActivity.Charging -> CHARGING
        is IslandActivity.Bluetooth -> BLUETOOTH
        is IslandActivity.Media -> MEDIA
        is IslandActivity.Notification -> rankNotification(activity.category, activity.hasActions)
    }

    /** Score a raw notification before it becomes an activity. */
    fun rankNotification(category: String?, hasActions: Boolean): Int = when (category) {
        Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL -> CALL
        Notification.CATEGORY_ALARM, Notification.CATEGORY_STOPWATCH -> TIMER
        Notification.CATEGORY_NAVIGATION -> NAVIGATION
        else -> if (hasActions) ACTIONABLE else NOTIFICATION
    }
}
