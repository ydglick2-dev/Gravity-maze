package com.glassify.launcher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glassify.launcher.glass.GlassTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("glassify")

/** Everything the user can change, plus the saved home screen layout. */
class LauncherPrefs(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settings: Flow<GlassifySettings> = context.dataStore.data.map { it.toSettings() }

    val layout: Flow<HomeLayout> = context.dataStore.data.map { prefs ->
        val raw = prefs[LAYOUT] ?: return@map HomeLayout()
        try {
            json.decodeFromString<HomeLayout>(raw)
        } catch (e: Exception) {
            // A layout we cannot parse is not worth crashing the home screen
            // over — reconcile() will rebuild a default one from scratch.
            Log.w(TAG, "Discarding an unreadable saved layout", e)
            HomeLayout()
        }
    }

    suspend fun saveLayout(layout: HomeLayout) {
        val encoded = json.encodeToString(layout)
        context.dataStore.edit { it[LAYOUT] = encoded }
    }

    private fun Preferences.toSettings() = GlassifySettings(
        blurStrength = this[BLUR] ?: 1f,
        refractionStrength = this[REFRACTION] ?: 1f,
        tierOverride = this[TIER]?.let { runCatching { GlassTier.valueOf(it) }.getOrNull() },
        islandEnabled = this[ISLAND] ?: true,
        islandTopOffsetDp = this[ISLAND_OFFSET] ?: 0,
        notificationCenterEnabled = this[NOTIFICATION_CENTER] ?: true,
        edgeTriggerEnabled = this[EDGE_TRIGGER] ?: true,
        columns = this[COLUMNS] ?: 4,
        rows = this[ROWS] ?: 6,
        showLabels = this[SHOW_LABELS] ?: true,
        darkTheme = this[DARK] ?: true,
        wallpaperUri = this[WALLPAPER],
        wallpaperPresetIndex = this[WALLPAPER_PRESET] ?: 0,
        setupComplete = this[SETUP_DONE] ?: false,
    )

    suspend fun update(block: (GlassifySettings) -> GlassifySettings) {
        context.dataStore.edit { prefs ->
            val next = block(prefs.toSettings())

            prefs[BLUR] = next.blurStrength
            prefs[REFRACTION] = next.refractionStrength
            next.tierOverride?.let { prefs[TIER] = it.name } ?: prefs.remove(TIER)
            prefs[ISLAND] = next.islandEnabled
            prefs[ISLAND_OFFSET] = next.islandTopOffsetDp
            prefs[NOTIFICATION_CENTER] = next.notificationCenterEnabled
            prefs[EDGE_TRIGGER] = next.edgeTriggerEnabled
            prefs[COLUMNS] = next.columns
            prefs[ROWS] = next.rows
            prefs[SHOW_LABELS] = next.showLabels
            prefs[DARK] = next.darkTheme
            next.wallpaperUri?.let { prefs[WALLPAPER] = it } ?: prefs.remove(WALLPAPER)
            prefs[WALLPAPER_PRESET] = next.wallpaperPresetIndex
            prefs[SETUP_DONE] = next.setupComplete
        }
    }

    private companion object {
        const val TAG = "LauncherPrefs"

        val LAYOUT = stringPreferencesKey("layout")
        val BLUR = floatPreferencesKey("blur")
        val REFRACTION = floatPreferencesKey("refraction")
        val TIER = stringPreferencesKey("tier")
        val ISLAND = booleanPreferencesKey("island")
        val ISLAND_OFFSET = intPreferencesKey("island_offset")
        val NOTIFICATION_CENTER = booleanPreferencesKey("notification_center")
        val EDGE_TRIGGER = booleanPreferencesKey("edge_trigger")
        val COLUMNS = intPreferencesKey("columns")
        val ROWS = intPreferencesKey("rows")
        val SHOW_LABELS = booleanPreferencesKey("show_labels")
        val DARK = booleanPreferencesKey("dark")
        val WALLPAPER = stringPreferencesKey("wallpaper")
        val WALLPAPER_PRESET = intPreferencesKey("wallpaper_preset")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
    }
}

data class GlassifySettings(
    /** Multipliers on the design system's defaults, 0.4x to 1.6x. */
    val blurStrength: Float = 1f,
    val refractionStrength: Float = 1f,
    /** Forces a lower glass tier than the device could manage, to save power. */
    val tierOverride: GlassTier? = null,
    val islandEnabled: Boolean = true,
    /** Nudges the Island to line up with this particular phone's camera cutout. */
    val islandTopOffsetDp: Int = 0,
    val notificationCenterEnabled: Boolean = true,
    /** The top-left swipe strip. Off means the panel opens from the home screen only. */
    val edgeTriggerEnabled: Boolean = true,
    val columns: Int = 4,
    val rows: Int = 6,
    val showLabels: Boolean = true,
    val darkTheme: Boolean = true,
    val wallpaperUri: String? = null,
    val wallpaperPresetIndex: Int = 0,
    val setupComplete: Boolean = false,
) {
    val itemsPerPage: Int get() = columns * rows

    fun resolveTier(deviceMax: GlassTier): GlassTier {
        val override = tierOverride ?: return deviceMax
        // An override may only ever lower the tier, never claim capability the
        // device does not have.
        return if (override.ordinal > deviceMax.ordinal) override else deviceMax
    }
}
