package dev.personal.adaptiveisland.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.personal.adaptiveisland.overlay.IslandGeometry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by
    preferencesDataStore(name = "adaptive_island_settings")

/**
 * Local, account-free persistence for the feature control centre.
 * Every feature stores an enable flag and an ownership choice keyed by its id.
 */
class FeaturePreferences(private val context: Context) {

    fun enabledFlow(featureId: String): Flow<Boolean?> =
        data().map { it[booleanPreferencesKey("enabled_$featureId")] }

    fun ownershipFlow(featureId: String): Flow<String?> =
        data().map { it[stringPreferencesKey("ownership_$featureId")] }

    fun topGapDpFlow(): Flow<Int> =
        data().map { it[intPreferencesKey("top_gap_dp")] ?: IslandGeometry.TOP_GAP_DP }

    fun pillHeightDpFlow(): Flow<Int> =
        data().map {
            val stored = it[intPreferencesKey("pill_height_dp")] ?: IslandGeometry.PILL_HEIGHT_DP
            stored.coerceAtLeast(IslandGeometry.PILL_HEIGHT_DP)
        }

    fun idleWidthDpFlow(): Flow<Int> =
        data().map { it[intPreferencesKey("idle_width_dp")] ?: IslandGeometry.IDLE_WIDTH_DP }

    suspend fun setEnabled(featureId: String, enabled: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("enabled_$featureId")] = enabled }
    }

    suspend fun setOwnership(featureId: String, ownership: String) {
        context.dataStore.edit { it[stringPreferencesKey("ownership_$featureId")] = ownership }
    }

    suspend fun setTopGapDp(value: Int) {
        context.dataStore.edit { it[intPreferencesKey("top_gap_dp")] = value }
    }

    suspend fun setPillHeightDp(value: Int) {
        context.dataStore.edit { it[intPreferencesKey("pill_height_dp")] = value }
    }

    suspend fun setIdleWidthDp(value: Int) {
        context.dataStore.edit { it[intPreferencesKey("idle_width_dp")] = value }
    }

    suspend fun resetGeometry() {
        context.dataStore.edit {
            it.remove(intPreferencesKey("top_gap_dp"))
            it.remove(intPreferencesKey("pill_height_dp"))
            it.remove(intPreferencesKey("idle_width_dp"))
        }
    }

    private fun data(): Flow<Preferences> = context.dataStore.data.catch { throwable ->
        if (throwable is IOException) emit(emptyPreferences()) else throw throwable
    }
}
