package com.glassify.launcher

import com.glassify.launcher.data.AppCategory
import com.glassify.launcher.data.HomeItem
import com.glassify.launcher.data.HomeLayout
import com.glassify.launcher.data.HomePage
import com.glassify.launcher.data.LaunchableApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HomeLayout.reconcile] is the one piece of launcher logic where a bug is
 * invisible until it has already lost the user's arrangement, so it carries the
 * bulk of the tests.
 */
class HomeLayoutTest {

    private fun app(key: String) = LaunchableApp(
        key = key,
        packageName = key.substringBefore('/'),
        activityName = key.substringAfter('/'),
        label = key,
        icon = null,
        category = AppCategory.OTHER,
    )

    @Test
    fun `new apps fill the first page with room before starting a new one`() {
        val layout = HomeLayout(
            pages = listOf(
                HomePage(listOf(HomeItem.App("a/a"))),
                HomePage(listOf(HomeItem.App("b/b"))),
            )
        )
        val available = listOf("a/a", "b/b", "c/c").map(::app)

        val result = layout.reconcile(available, perPage = 2)

        assertEquals(
            listOf(HomeItem.App("a/a"), HomeItem.App("c/c")),
            result.pages[0].items,
        )
        assertEquals(listOf(HomeItem.App("b/b")), result.pages[1].items)
    }

    @Test
    fun `apps overflow onto fresh pages once every page is full`() {
        val layout = HomeLayout(pages = listOf(HomePage(listOf(HomeItem.App("a/a")))))
        val available = listOf("a/a", "b/b", "c/c").map(::app)

        val result = layout.reconcile(available, perPage = 1)

        assertEquals(3, result.pages.size)
        assertEquals(setOf("a/a", "b/b", "c/c"), result.placedKeys())
    }

    @Test
    fun `uninstalled apps are dropped and the gap closes`() {
        val layout = HomeLayout(
            pages = listOf(
                HomePage(listOf(HomeItem.App("a/a"), HomeItem.App("gone/gone"), HomeItem.App("b/b")))
            )
        )

        val result = layout.reconcile(listOf(app("a/a"), app("b/b")), perPage = 6)

        assertEquals(listOf(HomeItem.App("a/a"), HomeItem.App("b/b")), result.pages[0].items)
    }

    @Test
    fun `a folder left with one app collapses back into that app`() {
        val layout = HomeLayout(
            pages = listOf(
                HomePage(listOf(HomeItem.Folder("f1", "Stuff", listOf("a/a", "gone/gone"))))
            )
        )

        val result = layout.reconcile(listOf(app("a/a")), perPage = 6)

        assertEquals(listOf(HomeItem.App("a/a")), result.pages[0].items)
    }

    @Test
    fun `a folder whose apps are all gone disappears`() {
        val layout = HomeLayout(
            pages = listOf(
                HomePage(listOf(HomeItem.Folder("f1", "Stuff", listOf("x/x", "y/y"))))
            )
        )

        val result = layout.reconcile(listOf(app("a/a")), perPage = 6)

        assertEquals(listOf(HomeItem.App("a/a")), result.pages[0].items)
    }

    @Test
    fun `apps in the dock are not duplicated onto a page`() {
        val layout = HomeLayout(pages = listOf(HomePage()), dock = listOf("a/a"))

        val result = layout.reconcile(listOf(app("a/a"), app("b/b")), perPage = 6)

        assertEquals(listOf(HomeItem.App("b/b")), result.pages[0].items)
        assertEquals(listOf("a/a"), result.dock)
    }

    @Test
    fun `reconciling is stable - running it twice changes nothing`() {
        val available = (1..9).map { app("p$it/a") }
        val once = HomeLayout().reconcile(available, perPage = 4)
        val twice = once.reconcile(available, perPage = 4)

        assertEquals(once, twice)
    }

    @Test
    fun `empty pages are removed but the first page always survives`() {
        val layout = HomeLayout(
            pages = listOf(HomePage(), HomePage(listOf(HomeItem.App("a/a"))), HomePage())
        )

        val result = layout.withoutEmptyPages()

        assertEquals(2, result.pages.size)
        assertTrue(result.pages[0].items.isEmpty())
        assertEquals(listOf(HomeItem.App("a/a")), result.pages[1].items)
    }

    @Test
    fun `a layout with no pages at all is repaired rather than left empty`() {
        val result = HomeLayout(pages = emptyList()).reconcile(listOf(app("a/a")), perPage = 4)

        assertEquals(1, result.pages.size)
        assertEquals(listOf(HomeItem.App("a/a")), result.pages[0].items)
    }
}
