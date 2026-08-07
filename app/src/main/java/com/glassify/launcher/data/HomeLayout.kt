package com.glassify.launcher.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What sits where on the home screen.
 *
 * Pages hold a packed, ordered list rather than a fixed grid with holes. That is
 * how iOS behaved for its first fifteen years and it removes an entire class of
 * problem: there is no such thing as an invalid layout, uninstalling an app just
 * closes the gap, and a drag is a list reorder instead of a collision search.
 */
@Serializable
data class HomeLayout(
    val pages: List<HomePage> = listOf(HomePage()),
    val dock: List<String> = emptyList(),
) {
    /** Every app key placed anywhere, used to find apps that still need a home. */
    fun placedKeys(): Set<String> = buildSet {
        addAll(dock)
        pages.forEach { page ->
            page.items.forEach { item ->
                when (item) {
                    is HomeItem.App -> add(item.key)
                    is HomeItem.Folder -> addAll(item.keys)
                }
            }
        }
    }

    /**
     * Adds newly installed apps and drops uninstalled ones.
     *
     * New apps land on the first page with room, then on a new page — the same
     * rule iOS uses. Doing this on every load rather than on install events also
     * repairs a layout saved before an app was sideloaded while we were not
     * running.
     */
    fun reconcile(available: List<LaunchableApp>, perPage: Int): HomeLayout {
        val availableKeys = available.mapTo(mutableSetOf()) { it.key }

        val prunedPages = pages.map { page ->
            HomePage(
                page.items.mapNotNull { item ->
                    when (item) {
                        is HomeItem.App -> item.takeIf { it.key in availableKeys }
                        is HomeItem.Folder -> {
                            val kept = item.keys.filter { it in availableKeys }
                            when {
                                kept.isEmpty() -> null
                                // A folder down to one app is just that app.
                                kept.size == 1 -> HomeItem.App(kept.first())
                                else -> item.copy(keys = kept)
                            }
                        }
                    }
                }
            )
        }
        val prunedDock = dock.filter { it in availableKeys }

        val placed = HomeLayout(prunedPages, prunedDock).placedKeys()
        val missing = available.filter { it.key !in placed }.map { HomeItem.App(it.key) }

        if (missing.isEmpty()) {
            return HomeLayout(prunedPages.ifEmpty { listOf(HomePage()) }, prunedDock)
        }

        val pagesOut = prunedPages.toMutableList()
        if (pagesOut.isEmpty()) pagesOut += HomePage()
        var queue = missing

        for (index in pagesOut.indices) {
            if (queue.isEmpty()) break
            val room = perPage - pagesOut[index].items.size
            if (room <= 0) continue
            pagesOut[index] = HomePage(pagesOut[index].items + queue.take(room))
            queue = queue.drop(room)
        }
        while (queue.isNotEmpty()) {
            pagesOut += HomePage(queue.take(perPage))
            queue = queue.drop(perPage)
        }

        return HomeLayout(pagesOut, prunedDock)
    }

    /** Removes pages left empty by a drag, but always keeps the first one. */
    fun withoutEmptyPages(): HomeLayout {
        val kept = pages.filterIndexed { index, page -> index == 0 || page.items.isNotEmpty() }
        return copy(pages = kept.ifEmpty { listOf(HomePage()) })
    }
}

@Serializable
data class HomePage(val items: List<HomeItem> = emptyList())

@Serializable
sealed interface HomeItem {

    @Serializable
    @SerialName("app")
    data class App(val key: String) : HomeItem

    @Serializable
    @SerialName("folder")
    data class Folder(
        val id: String,
        val name: String,
        val keys: List<String>,
    ) : HomeItem
}
