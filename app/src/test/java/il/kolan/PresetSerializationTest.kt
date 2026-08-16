package il.kolan

import il.kolan.data.BuiltInPresets
import il.kolan.data.CustomPreset
import il.kolan.data.PresetParams
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preset storage is the one place where a silent regression costs the user their own work, so
 * the round trip and the forward-compatibility behaviour are both pinned down here.
 */
class PresetSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `params survive a round trip`() {
        val original = PresetParams(
            pitchSemitones = -5.5f,
            formantSemitones = 3.25f,
            ringModHz = 55f,
            ringModDepth = 0.92f,
            whisper = 0.5f,
            drive = 0.62f,
            reverbMix = 0.33f,
            reverbSize = 0.75f,
            telephone = 1f,
            gateThresholdDb = -48f,
            compressorAmount = 0.55f,
            outputGainDb = 2.5f,
        )

        val restored = json.decodeFromString<PresetParams>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `custom preset survives a round trip`() {
        val original = CustomPreset(
            id = "custom.abc",
            name = "הקול שלי",
            iconKey = "robot",
            params = PresetParams(pitchSemitones = 7f, formantSemitones = 5f),
        )

        val restored = json.decodeFromString<CustomPreset>(json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals("הקול שלי", restored.name)
        assertTrue(!restored.isBuiltIn)
    }

    @Test
    fun `a list of custom presets survives a round trip`() {
        val presets = listOf(
            CustomPreset("custom.1", "אחד", "custom", PresetParams(pitchSemitones = 1f)),
            CustomPreset("custom.2", "שתיים", "demon", PresetParams(drive = 0.9f)),
        )

        val restored = json.decodeFromString<List<CustomPreset>>(json.encodeToString(presets))
        assertEquals(presets, restored)
    }

    @Test
    fun `unknown fields from a newer version are ignored rather than fatal`() {
        // A preset written by a future build that added a parameter must still load here,
        // otherwise upgrading and then downgrading would wipe the user's saved presets.
        val futureJson = """
            {"id":"custom.9","name":"עתידי","iconKey":"alien",
             "params":{"pitchSemitones":4.0,"someFutureKnob":0.5}}
        """.trimIndent()

        val restored = json.decodeFromString<CustomPreset>(futureJson)
        assertEquals("custom.9", restored.id)
        assertEquals(4.0f, restored.params.pitchSemitones, 0.001f)
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val sparse = """{"id":"custom.3","name":"חלקי","iconKey":"child","params":{}}"""
        val restored = json.decodeFromString<CustomPreset>(sparse)
        assertEquals(PresetParams(), restored.params)
    }

    @Test
    fun `built-in presets have unique ids and are marked built in`() {
        val ids = BuiltInPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(8, BuiltInPresets.all.size)
        assertTrue(BuiltInPresets.all.all { it.isBuiltIn })
        assertNotNull(BuiltInPresets.byId(BuiltInPresets.default.id))
    }

    @Test
    fun `built-in presets stay inside the ranges the editor exposes`() {
        // A preset outside the slider range would snap to a different value the moment the user
        // opened the editor, silently changing a voice they had chosen.
        for (preset in BuiltInPresets.all) {
            val p = preset.params
            assertTrue(
                "${preset.id} pitch out of range",
                p.pitchSemitones in -PresetParams.PITCH_RANGE_SEMITONES..
                    PresetParams.PITCH_RANGE_SEMITONES,
            )
            assertTrue(
                "${preset.id} formant out of range",
                p.formantSemitones in -PresetParams.FORMANT_RANGE_SEMITONES..
                    PresetParams.FORMANT_RANGE_SEMITONES,
            )
            assertTrue("${preset.id} ringModHz out of range", p.ringModHz in 0f..PresetParams.RING_MOD_MAX_HZ)
            assertTrue("${preset.id} ringModDepth out of range", p.ringModDepth in 0f..1f)
            assertTrue("${preset.id} whisper out of range", p.whisper in 0f..1f)
            assertTrue("${preset.id} drive out of range", p.drive in 0f..1f)
            assertTrue("${preset.id} reverbMix out of range", p.reverbMix in 0f..1f)
            assertTrue("${preset.id} reverbSize out of range", p.reverbSize in 0f..1f)
            assertTrue("${preset.id} telephone out of range", p.telephone in 0f..1f)
            assertTrue("${preset.id} compressor out of range", p.compressorAmount in 0f..1f)
            assertTrue(
                "${preset.id} gate out of range",
                p.gateThresholdDb in PresetParams.GATE_MIN_DB..PresetParams.GATE_MAX_DB,
            )
            assertTrue(
                "${preset.id} output gain out of range",
                p.outputGainDb in -PresetParams.OUTPUT_GAIN_RANGE_DB..
                    PresetParams.OUTPUT_GAIN_RANGE_DB,
            )
        }
    }

    @Test
    fun `presets that raise pitch do not raise formants by as much`() {
        // Moving formants in lockstep with pitch is exactly what produces the chipmunk sound the
        // engine exists to avoid, so the shipped presets must not do it.
        for (preset in listOf(BuiltInPresets.female, BuiltInPresets.child)) {
            val p = preset.params
            assertTrue("${preset.id} should raise pitch", p.pitchSemitones > 0f)
            assertTrue(
                "${preset.id} formant shift should be smaller than its pitch shift",
                p.formantSemitones < p.pitchSemitones,
            )
        }
    }
}
