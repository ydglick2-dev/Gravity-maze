package com.glassify.launcher

import com.glassify.launcher.data.GlassifySettings
import com.glassify.launcher.glass.GlassTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTierTest {

    @Test
    fun `no override means the device decides`() {
        val settings = GlassifySettings(tierOverride = null)

        assertEquals(GlassTier.FULL, settings.resolveTier(GlassTier.FULL))
        assertEquals(GlassTier.BLUR, settings.resolveTier(GlassTier.BLUR))
    }

    @Test
    fun `an override may lower the tier to save power`() {
        val settings = GlassifySettings(tierOverride = GlassTier.FLAT)

        assertEquals(GlassTier.FLAT, settings.resolveTier(GlassTier.FULL))
    }

    @Test
    fun `an override cannot claim capability the device does not have`() {
        // A stale preference from a previous device must not turn on a shader
        // path that would crash on an older one.
        val settings = GlassifySettings(tierOverride = GlassTier.FULL)

        assertEquals(GlassTier.BLUR, settings.resolveTier(GlassTier.BLUR))
    }

    @Test
    fun `each tier reports the right capabilities`() {
        assertTrue(GlassTier.FULL.samplesBackdrop)
        assertTrue(GlassTier.FULL.blursBackdrop)

        assertFalse(GlassTier.BLUR.samplesBackdrop)
        assertTrue(GlassTier.BLUR.blursBackdrop)

        assertFalse(GlassTier.FLAT.samplesBackdrop)
        assertFalse(GlassTier.FLAT.blursBackdrop)
    }

    @Test
    fun `the dock keeps the order the user picked`() {
        // Order is the arrangement, so it has to survive being stored and read
        // back — a set would silently reorder it.
        val settings = GlassifySettings(dockKeys = listOf("c/c", "a/a", "b/b"))

        assertEquals(listOf("c/c", "a/a", "b/b"), settings.dockKeys)
    }
}
