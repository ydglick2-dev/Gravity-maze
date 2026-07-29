package com.gravity.phonefinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerMatcherTest {

    @Test
    fun `the wake word fires on its own`() {
        assertTrue(TriggerMatcher.isFindCommand("גלידה"))
        assertTrue(TriggerMatcher.isFindCommand("אמא כתבה גלידה"))
    }

    @Test
    fun `the exact phrase fires`() {
        assertTrue(TriggerMatcher.isFindCommand("מצא טלפון"))
    }

    @Test
    fun `natural variations fire`() {
        assertTrue(TriggerMatcher.isFindCommand("מצא את הטלפון"))
        assertTrue(TriggerMatcher.isFindCommand("תמצא את הפלאפון שלי"))
        assertTrue(TriggerMatcher.isFindCommand("איפה הטלפון?"))
        assertTrue(TriggerMatcher.isFindCommand("תצלצל לטלפון"))
    }

    @Test
    fun `chat message with surrounding text fires`() {
        assertTrue(TriggerMatcher.isFindCommand("אמא: מצא טלפון עכשיו בבקשה"))
    }

    @Test
    fun `english fallback fires`() {
        assertTrue(TriggerMatcher.isFindCommand("find my phone"))
        assertTrue(TriggerMatcher.isFindCommand("Where is the phone"))
    }

    @Test
    fun `unrelated speech does not fire`() {
        assertFalse(TriggerMatcher.isFindCommand("מה השעה"))
        assertFalse(TriggerMatcher.isFindCommand("הטלפון שלי חדש"))
        assertFalse(TriggerMatcher.isFindCommand("מצא לי מסעדה טובה"))
        assertFalse(TriggerMatcher.isFindCommand(""))
        assertFalse(TriggerMatcher.isFindCommand(null))
    }

    @Test
    fun `saying you found it stops instead of triggering`() {
        assertFalse(TriggerMatcher.isFindCommand("מצאתי את הטלפון"))
        assertTrue(TriggerMatcher.isStopCommand("מצאתי את הטלפון"))
    }

    @Test
    fun `stop phrases are recognised`() {
        assertTrue(TriggerMatcher.isStopCommand("עצור"))
        assertTrue(TriggerMatcher.isStopCommand("תפסיק כבר"))
        assertTrue(TriggerMatcher.isStopCommand("stop"))
        assertFalse(TriggerMatcher.isStopCommand("מצא טלפון"))
    }

    @Test
    fun `normalisation folds final letters punctuation and niqqud`() {
        assertEquals("מצא טלפונ", TriggerMatcher.normalize("מְצָא טֶלֶפוֹן!"))
        assertEquals("מצא טלפונ", TriggerMatcher.normalize("  מצא   טלפון,  "))
    }

    /**
     * The recogniser reports partial results, so half a sentence arrives before the
     * whole one. Half must not fire — the device word has to be there too.
     */
    @Test
    fun `partial results only fire once the whole phrase arrived`() {
        assertFalse(TriggerMatcher.isFindCommand("מצא"))
        assertFalse(TriggerMatcher.isFindCommand("מצא טל"))
        assertTrue(TriggerMatcher.isFindCommand("מצא טלפון"))
    }
}
