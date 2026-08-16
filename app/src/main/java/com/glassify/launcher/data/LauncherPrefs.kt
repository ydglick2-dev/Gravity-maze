package com.glassify.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glassify.launcher.glass.GlassTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("glassify")

/** Everything the user can change. */
class LauncherPrefs(private val context: Context) {

    val settings: Flow<GlassifySettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = GlassifySettings(
        tierOverride = this[TIER]?.let { runCatching { GlassTier.valueOf(it) }.getOrNull() },
        blurRadiusPx = this[BLUR_RADIUS] ?: 12,
        glassOpacityPercent = this[GLASS_OPACITY] ?: 4,
        islandEnabled = this[ISLAND] ?: true,
        islandTopOffsetDp = this[ISLAND_OFFSET] ?: 0,
        dockEnabled = this[DOCK] ?: true,
        dockBottomOffsetDp = this[DOCK_OFFSET] ?: 12,
        dockKeys = this[DOCK_KEYS]?.split('|')?.filter { it.isNotBlank() }.orEmpty(),
        clockEnabled = this[CLOCK] ?: false,
        clockTopOffsetDp = this[CLOCK_OFFSET] ?: 80,
        notificationCenterEnabled = this[NOTIFICATION_CENTER] ?: false,
        edgeTriggerEnabled = this[EDGE_TRIGGER] ?: false,
        darkTheme = this[DARK] ?: true,
        overlaysPaused = this[PAUSED] ?: false,
        setupComplete = this[SETUP_DONE] ?: false,
    )

    suspend fun update(block: (GlassifySettings) -> GlassifySettings) {
        context.dataStore.edit { prefs ->
            val next = block(prefs.toSettings())

            next.tierOverride?.let { prefs[TIER] = it.name } ?: prefs.remove(TIER)
            prefs[BLUR_RADIUS] = next.blurRadiusPx
            prefs[GLASS_OPACITY] = next.glassOpacityPercent
            prefs[ISLAND] = next.islandEnabled
            prefs[ISLAND_OFFSET] = next.islandTopOffsetDp
            prefs[DOCK] = next.dockEnabled
            prefs[DOCK_OFFSET] = next.dockBottomOffsetDp
            prefs[DOCK_KEYS] = next.dockKeys.joinToString("|")
            prefs[CLOCK] = next.clockEnabled
            prefs[CLOCK_OFFSET] = next.clockTopOffsetDp
            prefs[NOTIFICATION_CENTER] = next.notificationCenterEnabled
            prefs[EDGE_TRIGGER] = next.edgeTriggerEnabled
            prefs[DARK] = next.darkTheme
            prefs[PAUSED] = next.overlaysPaused
            prefs[SETUP_DONE] = next.setupComplete
        }
    }

    private companion object {
        val TIER = stringPreferencesKey("tier")
        // The "2" keys retire the pre-calibration values: 1.7 and earlier
        // stored defaults tuned for the old dark-frost look (blur 80, opacity
        // 14), and the reference design wants clear glass (blur ~4-12, fill 4%).
        // Abandoning the old keys re-baselines every install onto the design.
        val BLUR_RADIUS = intPreferencesKey("blur_radius2")
        val GLASS_OPACITY = intPreferencesKey("glass_opacity2")
        val ISLAND = booleanPreferencesKey("island")
        val ISLAND_OFFSET = intPreferencesKey("island_offset")
        val DOCK = booleanPreferencesKey("dock")
        val DOCK_OFFSET = intPreferencesKey("dock_offset")

        /**
         * Pipe-separated rather than a string set: order is the dock's whole
         * point, and DataStore's set type does not keep one.
         */
        val DOCK_KEYS = stringPreferencesKey("dock_keys")
        val CLOCK = booleanPreferencesKey("clock")
        val CLOCK_OFFSET = intPreferencesKey("clock_offset")
        val NOTIFICATION_CENTER = booleanPreferencesKey("notification_center")
        val EDGE_TRIGGER = booleanPreferencesKey("edge_trigger")
        val DARK = booleanPreferencesKey("dark")
        val PAUSED = booleanPreferencesKey("overlays_paused")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
    }
}

data class GlassifySettings(
    /** Forces a lower glass tier than the device could manage, to save power. */
    val tierOverride: GlassTier? = null,

    /**
     * How far the compositor blurs behind the panels, in pixels.
     *
     * The reference design's frost is light (its slider runs 0-20px around a
     * 4px default): the glass is meant to be clear, with legibility coming from
     * tint plates rather than frost. 12 keeps a little more separation over a
     * busy home screen while staying in the design's family. Not density-scaled
     * — blur is a property of the image, not the layout.
     */
    val blurRadiusPx: Int = 12,

    /**
     * Fill opacity of the glass interior, as a percentage.
     *
     * Design value: 4 (`rgba(255,255,255,.04)`). Exposed because the right
     * amount still depends on the wallpaper underneath.
     */
    val glassOpacityPercent: Int = 4,

    val islandEnabled: Boolean = true,
    /** Nudges the Island to line up with this particular phone's camera cutout. */
    val islandTopOffsetDp: Int = 0,

    val dockEnabled: Boolean = true,
    /** Lifts the dock clear of the gesture bar, or drops it over the vendor dock. */
    val dockBottomOffsetDp: Int = 12,
    val dockKeys: List<String> = emptyList(),

    val clockEnabled: Boolean = false,
    val clockTopOffsetDp: Int = 80,

    val notificationCenterEnabled: Boolean = false,
    /** The top-left swipe strip, off by default since it competes with One UI. */
    val edgeTriggerEnabled: Boolean = false,

    val darkTheme: Boolean = true,

    /**
     * Set by the home screen off switch. Persistent on purpose: an off switch
     * that the next reboot undoes is not an off switch.
     */
    val overlaysPaused: Boolean = false,

    val setupComplete: Boolean = false,
) {
    val glassOpacity: Float get() = glassOpacityPercent / 100f

    fun resolveTier(deviceMax: GlassTier): GlassTier {
        val override = tierOverride ?: return deviceMax
        // An override may only ever lower the tier, never claim capability the
        // device does not have.
        return if (override.ordinal > deviceMax.ordinal) override else deviceMax
    }
}
