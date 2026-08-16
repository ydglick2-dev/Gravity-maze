package il.kolan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore("kolan_settings")

/** App-level settings: onboarding state, the signaling server address, and haptics. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val SIGNALING_URL = stringPreferencesKey("signaling_url")
        val HAPTICS = booleanPreferencesKey("haptics")
        val LOUDSPEAKER_WARNING_SEEN = booleanPreferencesKey("loudspeaker_warning_seen")
    }

    val onboardingComplete: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    /**
     * Empty until the user supplies one. Internal calls need a signaling server to introduce the
     * two phones to each other, and there is no default because there is no server we could
     * point at on the user's behalf; see server/README.md for how to run one.
     */
    val signalingUrl: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.SIGNALING_URL] ?: "" }

    val hapticsEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.HAPTICS] ?: true }

    val loudspeakerWarningSeen: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.LOUDSPEAKER_WARNING_SEEN] ?: false }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.settingsDataStore.edit { it[Keys.ONBOARDING_DONE] = complete }
    }

    suspend fun setSignalingUrl(url: String) {
        context.settingsDataStore.edit { it[Keys.SIGNALING_URL] = url.trim() }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HAPTICS] = enabled }
    }

    suspend fun setLoudspeakerWarningSeen(seen: Boolean) {
        context.settingsDataStore.edit { it[Keys.LOUDSPEAKER_WARNING_SEEN] = seen }
    }
}
