package com.glassify.launcher

import android.content.pm.ApplicationInfo
import com.glassify.launcher.data.AppCategorizer
import com.glassify.launcher.data.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class AppCategorizerTest {

    @Test
    fun `the platform category wins when the developer declared one`() {
        val info = ApplicationInfo().apply { category = ApplicationInfo.CATEGORY_GAME }

        // The package name says "social", the manifest says "game" — the
        // manifest is first-hand information and must win.
        assertEquals(
            AppCategory.GAMES,
            AppCategorizer.categorize("com.example.socialthing", info),
        )
    }

    @Test
    fun `package keywords classify apps that declared nothing`() {
        assertEquals(AppCategory.SOCIAL, AppCategorizer.categorize("com.whatsapp", null))
        assertEquals(AppCategory.TRAVEL, AppCategorizer.categorize("com.waze", null))
        assertEquals(AppCategory.ENTERTAINMENT, AppCategorizer.categorize("com.spotify.music", null))
    }

    @Test
    fun `israeli apps that a keyword list would otherwise miss are covered`() {
        assertEquals(AppCategory.FINANCE, AppCategorizer.categorize("com.leumi.leumiwallet", null))
        assertEquals(AppCategory.SHOPPING, AppCategorizer.categorize("com.wolt.android", null))
        assertEquals(AppCategory.HEALTH, AppCategorizer.categorize("com.clalit.mobile", null))
        assertEquals(AppCategory.INFORMATION, AppCategorizer.categorize("com.goldtouch.ynet", null))
    }

    @Test
    fun `an unrecognised package falls through to other rather than guessing`() {
        assertEquals(
            AppCategory.OTHER,
            AppCategorizer.categorize("com.acme.zzzqqq", null),
        )
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(AppCategory.SOCIAL, AppCategorizer.categorize("com.WhatsApp.Messenger", null))
    }

    @Test
    fun `an undeclared platform category does not short-circuit the keywords`() {
        // CATEGORY_UNDEFINED is -1 and is what the vast majority of apps report.
        val info = ApplicationInfo().apply { category = ApplicationInfo.CATEGORY_UNDEFINED }

        assertEquals(AppCategory.SOCIAL, AppCategorizer.categorize("com.whatsapp", info))
    }
}
