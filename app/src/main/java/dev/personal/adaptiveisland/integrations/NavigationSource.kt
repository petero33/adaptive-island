package dev.personal.adaptiveisland.integrations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow

/** Snapshot of turn-by-turn navigation state. */
data class NavigationState(
    val nextTurn: String? = null,
    val distanceMeters: Int? = null,
    val etaMillis: Long? = null,
    val active: Boolean = false,
)

/**
 * Navigation data source.
 *
 * Holds the current turn-by-turn navigation state. Can be updated from
 * navigation notifications or set directly for testing.
 */
object NavigationSource {

    private val _state = MutableStateFlow(NavigationState())
    val state: Flow<NavigationState> = _state.asStateFlow()

    /** Update the navigation state from the island or a notification parser. */
    fun update(nextTurn: String?, distanceMeters: Int?, etaMillis: Long?, active: Boolean) {
        _state.value = NavigationState(
            nextTurn = nextTurn,
            distanceMeters = distanceMeters,
            etaMillis = etaMillis,
            active = active,
        )
    }

    /** Clear navigation state. */
    fun clear() {
        _state.value = NavigationState()
    }
}
