package com.glassify.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassify.launcher.data.HomeItem
import com.glassify.launcher.glass.GlassBackdropScope
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.GlassTier
import com.glassify.launcher.launcher.AppLibrary
import com.glassify.launcher.launcher.FolderView
import com.glassify.launcher.launcher.HomeScreen
import com.glassify.launcher.launcher.LauncherViewModel
import com.glassify.launcher.launcher.SearchOverlay
import com.glassify.launcher.launcher.Wallpaper
import com.glassify.launcher.overlay.OverlayService
import com.glassify.launcher.settings.SettingsScreen
import com.glassify.launcher.setup.SetupWizard

/**
 * The home screen activity.
 *
 * Declared as a HOME activity in the manifest, so this *is* the launcher once
 * the user picks it. It hides the system status bar and draws its own — see
 * [com.glassify.launcher.launcher.IosStatusBar] for why — and it never finishes:
 * pressing Home returns here, and Back only ever unwinds our own overlays.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // The system status bar is replaced by ours; the navigation bar stays,
        // since hiding it would break the gesture area the user navigates with.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent { GlassifyApp(viewModel) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.seedDockIfEmpty()
        OverlayService.syncWithPermissions(this)
    }
}

private enum class Screen { HOME, LIBRARY, SEARCH, SETTINGS }

@Composable
private fun GlassifyApp(viewModel: LauncherViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val editMode by viewModel.editMode.collectAsStateWithLifecycle()
    val openFolderId by viewModel.openFolder.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceMax = remember { GlassTier.deviceMax(context) }
    var screen by remember { mutableStateOf(Screen.HOME) }

    val appsByKey = remember(apps) { apps.associateBy { it.key } }
    val openFolder = remember(openFolderId, layout) {
        openFolderId?.let { id ->
            layout.pages
                .asSequence()
                .flatMap { it.items.asSequence() }
                .filterIsInstance<HomeItem.Folder>()
                .firstOrNull { it.id == id }
        }
    }

    // Back unwinds our own layers only, and is disabled once there is nothing
    // left to unwind — a launcher that finishes on Back leaves the user staring
    // at a blank screen.
    BackHandler(enabled = openFolderId != null || screen != Screen.HOME || editMode) {
        when {
            openFolderId != null -> viewModel.openFolder(null)
            screen != Screen.HOME -> screen = Screen.HOME
            editMode -> viewModel.setEditMode(false)
        }
    }

    GlassTheme(
        dark = settings.darkTheme,
        tier = settings.resolveTier(deviceMax),
    ) {
        if (!settings.setupComplete) {
            SetupWizard(onFinished = { screen = Screen.HOME })
            return@GlassTheme
        }

        GlassBackdropScope(
            modifier = Modifier.fillMaxSize(),
            background = {
                // Only the wallpaper is recorded as backdrop. The icon grid
                // stays out of it deliberately: it is interactive, and a panel
                // that sampled the layer it is drawn into would feed back into
                // itself.
                Wallpaper(
                    presetIndex = settings.wallpaperPresetIndex,
                    customPath = settings.wallpaperUri,
                )
            },
            content = {
                Box(Modifier.fillMaxSize()) {
                    HomeScreen(
                        layout = layout,
                        appsByKey = appsByKey,
                        settings = settings,
                        editMode = editMode,
                        onEditModeChange = viewModel::setEditMode,
                        onLaunch = viewModel::launch,
                        onMove = viewModel::moveItem,
                        onCombine = viewModel::combineIntoFolder,
                        onCommit = viewModel::commitPending,
                        onOpenFolder = { viewModel.openFolder(it) },
                        onOpenLibrary = { screen = Screen.LIBRARY },
                        onOpenSearch = { screen = Screen.SEARCH },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )

                    AnimatedVisibility(
                        visible = screen == Screen.LIBRARY,
                        enter = fadeIn() + slideInVertically { it / 6 },
                        exit = fadeOut() + slideOutVertically { it / 6 },
                    ) {
                        AppLibrary(
                            apps = apps,
                            onLaunch = viewModel::launch,
                            onClose = { screen = Screen.HOME },
                        )
                    }

                    AnimatedVisibility(
                        visible = screen == Screen.SEARCH,
                        enter = fadeIn() + scaleIn(initialScale = 1.04f),
                        exit = fadeOut() + scaleOut(targetScale = 1.04f),
                    ) {
                        SearchOverlay(
                            apps = apps,
                            onLaunch = viewModel::launch,
                            onDismiss = { screen = Screen.HOME },
                        )
                    }

                    AnimatedVisibility(
                        visible = screen == Screen.SETTINGS,
                        enter = fadeIn() + slideInVertically { it / 4 },
                        exit = fadeOut() + slideOutVertically { it / 4 },
                    ) {
                        SettingsScreen(onClose = { screen = Screen.HOME })
                    }

                    openFolder?.let { folder ->
                        FolderView(
                            folder = folder,
                            appsByKey = appsByKey,
                            editMode = editMode,
                            onLaunch = viewModel::launch,
                            onRename = { viewModel.renameFolder(folder.id, it) },
                            onDismiss = { viewModel.openFolder(null) },
                        )
                    }
                }
            },
        )
    }
}
