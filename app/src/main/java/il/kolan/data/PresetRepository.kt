package il.kolan.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.presetDataStore: DataStore<Preferences> by preferencesDataStore("kolan_presets")

/**
 * Persists user presets and the current selection.
 *
 * Built-ins are code, not data: they are never written to disk, so a future version can retune
 * them without migrating anyone's storage. Editing a built-in produces a custom copy instead of
 * mutating it, which is also why the edit screen can always offer "revert".
 */
class PresetRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private object Keys {
        val CUSTOM_PRESETS = stringPreferencesKey("custom_presets")
        val SELECTED_ID = stringPreferencesKey("selected_id")
        val WORKING_PARAMS = stringPreferencesKey("working_params")
    }

    val customPresets: Flow<List<CustomPreset>> = context.presetDataStore.data.map { prefs ->
        val raw = prefs[Keys.CUSTOM_PRESETS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<CustomPreset>>(raw) }.getOrDefault(emptyList())
    }

    val allPresets: Flow<List<Preset>> = customPresets.map { customs ->
        BuiltInPresets.all + customs
    }

    val selectedPresetId: Flow<String> = context.presetDataStore.data.map { prefs ->
        prefs[Keys.SELECTED_ID] ?: BuiltInPresets.default.id
    }

    /**
     * The parameters actually driving the engine.
     *
     * They start as a copy of the selected preset and diverge as soon as a slider moves, so an
     * in-progress edit survives the app being backgrounded without silently overwriting the
     * preset it came from.
     */
    val workingParams: Flow<PresetParams> = context.presetDataStore.data.map { prefs ->
        val raw = prefs[Keys.WORKING_PARAMS]
        if (raw != null) {
            runCatching { json.decodeFromString<PresetParams>(raw) }
                .getOrDefault(BuiltInPresets.default.params)
        } else {
            BuiltInPresets.default.params
        }
    }

    /** Emits the selected preset resolved against the current custom list. */
    val selectedPreset: Flow<Preset> =
        combine(allPresets, selectedPresetId) { presets, id ->
            presets.firstOrNull { it.id == id } ?: BuiltInPresets.default
        }

    suspend fun selectPreset(preset: Preset) {
        context.presetDataStore.edit { prefs ->
            prefs[Keys.SELECTED_ID] = preset.id
            prefs[Keys.WORKING_PARAMS] = json.encodeToString(preset.params)
        }
    }

    suspend fun updateWorkingParams(params: PresetParams) {
        context.presetDataStore.edit { prefs ->
            prefs[Keys.WORKING_PARAMS] = json.encodeToString(params)
        }
    }

    /** Saves the current working parameters as a new custom preset and selects it. */
    suspend fun saveAsCustom(name: String, iconKey: String, params: PresetParams): CustomPreset {
        val preset = CustomPreset(
            id = "custom.${UUID.randomUUID()}",
            name = name,
            iconKey = iconKey,
            params = params,
        )
        context.presetDataStore.edit { prefs ->
            val existing = prefs[Keys.CUSTOM_PRESETS]?.let { raw ->
                runCatching { json.decodeFromString<List<CustomPreset>>(raw) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            prefs[Keys.CUSTOM_PRESETS] = json.encodeToString(existing + preset)
            prefs[Keys.SELECTED_ID] = preset.id
            prefs[Keys.WORKING_PARAMS] = json.encodeToString(params)
        }
        return preset
    }

    /** Overwrites an existing custom preset in place. */
    suspend fun updateCustom(preset: CustomPreset) {
        context.presetDataStore.edit { prefs ->
            val existing = prefs[Keys.CUSTOM_PRESETS]?.let { raw ->
                runCatching { json.decodeFromString<List<CustomPreset>>(raw) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            prefs[Keys.CUSTOM_PRESETS] =
                json.encodeToString(existing.map { if (it.id == preset.id) preset else it })
            prefs[Keys.WORKING_PARAMS] = json.encodeToString(preset.params)
        }
    }

    suspend fun deleteCustom(id: String) {
        context.presetDataStore.edit { prefs ->
            val existing = prefs[Keys.CUSTOM_PRESETS]?.let { raw ->
                runCatching { json.decodeFromString<List<CustomPreset>>(raw) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            prefs[Keys.CUSTOM_PRESETS] = json.encodeToString(existing.filterNot { it.id == id })
            if (prefs[Keys.SELECTED_ID] == id) {
                prefs[Keys.SELECTED_ID] = BuiltInPresets.default.id
                prefs[Keys.WORKING_PARAMS] = json.encodeToString(BuiltInPresets.default.params)
            }
        }
    }
}
