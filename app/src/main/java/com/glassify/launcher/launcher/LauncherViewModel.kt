package com.glassify.launcher.launcher

import android.app.Application
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glassify.launcher.data.AppRepository
import com.glassify.launcher.data.GlassifySettings
import com.glassify.launcher.data.HomeItem
import com.glassify.launcher.data.HomeLayout
import com.glassify.launcher.data.HomePage
import com.glassify.launcher.data.LaunchableApp
import com.glassify.launcher.data.LauncherPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home screen state.
 *
 * The layout the UI renders is always the *reconciled* one — saved order merged
 * with what is actually installed — so newly installed apps appear without any
 * explicit install handling in the UI, and an app uninstalled from the Play
 * Store simply vanishes on the next emission.
 */
class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = LauncherPrefs(app)
    val repository = AppRepository(app, viewModelScope)

    /** In-flight layout edits, so a drag is not written to disk on every frame. */
    private val pendingLayout = MutableStateFlow<HomeLayout?>(null)

    val settings: StateFlow<GlassifySettings> = prefs.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, GlassifySettings(),
    )

    val apps: StateFlow<List<LaunchableApp>> = repository.apps
    val loading: StateFlow<Boolean> = repository.loading

    val layout: StateFlow<HomeLayout> = combine(
        prefs.layout,
        repository.apps,
        prefs.settings,
        pendingLayout,
    ) { saved, apps, settings, pending ->
        val base = pending ?: saved
        if (apps.isEmpty()) base else base.reconcile(apps, settings.itemsPerPage)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout())

    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    private val _openFolder = MutableStateFlow<String?>(null)
    val openFolder: StateFlow<String?> = _openFolder.asStateFlow()

    init {
        repository.start()
    }

    override fun onCleared() {
        repository.stop()
    }

    fun appsByKey(): Map<String, LaunchableApp> = apps.value.associateBy { it.key }

    fun launch(app: LaunchableApp, bounds: Rect?) = repository.launch(app, bounds)

    fun openAppInfo(app: LaunchableApp, bounds: Rect?) = repository.openAppInfo(app, bounds)

    fun setEditMode(editing: Boolean) {
        _editMode.value = editing
        if (!editing) commitPending()
    }

    fun openFolder(id: String?) {
        _openFolder.value = id
    }

    /**
     * Applies a layout change immediately in memory and writes it once the drag
     * settles. Persisting on every reorder would put a DataStore write on the
     * drag's frame budget.
     */
    fun mutateLayout(transform: (HomeLayout) -> HomeLayout) {
        pendingLayout.value = transform(layout.value)
    }

    fun commitPending() {
        val pending = pendingLayout.value ?: return
        val cleaned = pending.withoutEmptyPages()
        pendingLayout.value = null
        viewModelScope.launch { prefs.saveLayout(cleaned) }
    }

    /** Moves an item within a page, or across pages when [toPage] differs. */
    fun moveItem(fromPage: Int, fromIndex: Int, toPage: Int, toIndex: Int) = mutateLayout { current ->
        if (fromPage !in current.pages.indices) return@mutateLayout current
        val pages = current.pages.toMutableList()
        val source = pages[fromPage].items.toMutableList()
        if (fromIndex !in source.indices) return@mutateLayout current
        val item = source.removeAt(fromIndex)
        pages[fromPage] = HomePage(source)

        while (pages.size <= toPage) pages += HomePage()
        val target = pages[toPage].items.toMutableList()
        target.add(toIndex.coerceIn(0, target.size), item)
        pages[toPage] = HomePage(target)

        current.copy(pages = pages)
    }

    /** Drops [key] onto the item at [targetIndex], making or growing a folder. */
    fun combineIntoFolder(page: Int, draggedIndex: Int, targetIndex: Int) = mutateLayout { current ->
        if (page !in current.pages.indices) return@mutateLayout current
        val items = current.pages[page].items.toMutableList()
        if (draggedIndex !in items.indices || targetIndex !in items.indices) return@mutateLayout current

        val dragged = items[draggedIndex]
        val target = items[targetIndex]
        val draggedKeys = dragged.keys()
        if (draggedKeys.isEmpty()) return@mutateLayout current

        val merged = when (target) {
            is HomeItem.Folder -> target.copy(keys = target.keys + draggedKeys)
            is HomeItem.App -> HomeItem.Folder(
                id = "folder-${target.key.hashCode()}-${draggedKeys.first().hashCode()}",
                name = "",
                keys = listOf(target.key) + draggedKeys,
            )
        }

        items[targetIndex] = merged
        items.removeAt(draggedIndex)
        current.copy(pages = current.pages.toMutableList().also { it[page] = HomePage(items) })
    }

    fun renameFolder(id: String, name: String) = mutateLayout { current ->
        current.copy(
            pages = current.pages.map { page ->
                HomePage(
                    page.items.map { item ->
                        if (item is HomeItem.Folder && item.id == id) item.copy(name = name) else item
                    }
                )
            }
        )
    }.also { commitPending() }

    /** Pulls one app out of a folder and back onto the page holding it. */
    fun removeFromFolder(folderId: String, key: String) = mutateLayout { current ->
        val pages = current.pages.toMutableList()
        for (pageIndex in pages.indices) {
            val items = pages[pageIndex].items.toMutableList()
            val index = items.indexOfFirst { it is HomeItem.Folder && it.id == folderId }
            if (index < 0) continue

            val folder = items[index] as HomeItem.Folder
            val remaining = folder.keys - key
            when {
                remaining.size <= 1 -> {
                    items[index] = HomeItem.App(remaining.firstOrNull() ?: key)
                    if (remaining.isNotEmpty()) items.add(index + 1, HomeItem.App(key))
                }
                else -> {
                    items[index] = folder.copy(keys = remaining)
                    items.add(index + 1, HomeItem.App(key))
                }
            }
            pages[pageIndex] = HomePage(items)
            break
        }
        current.copy(pages = pages)
    }.also { commitPending() }

    fun setDock(keys: List<String>) = mutateLayout { it.copy(dock = keys) }

    /**
     * Seeds the dock on first run with whatever the user's phone actually has,
     * matched by role rather than by package, since the dialer and browser
     * differ between a Samsung and a Pixel.
     */
    fun seedDockIfEmpty() = viewModelScope.launch {
        val current = prefs.layout.first()
        if (current.dock.isNotEmpty()) return@launch
        val installed = repository.apps.value
        if (installed.isEmpty()) return@launch

        val picks = DOCK_SEEDS.mapNotNull { patterns ->
            installed.firstOrNull { app ->
                patterns.any { app.packageName.contains(it, ignoreCase = true) }
            }?.key
        }.distinct().take(4)

        if (picks.isNotEmpty()) prefs.saveLayout(current.copy(dock = picks))
    }

    private fun HomeItem.keys(): List<String> = when (this) {
        is HomeItem.App -> listOf(key)
        is HomeItem.Folder -> keys
    }

    private companion object {
        /** Phone, browser, messages, camera — the iOS dock, by role. */
        val DOCK_SEEDS = listOf(
            listOf("dialer", "incallui", "contacts.phone", "phone"),
            listOf("chrome", "sbrowser", "firefox", "browser"),
            listOf("whatsapp", "messaging", "messages", "telegram"),
            listOf("camera"),
        )
    }
}
