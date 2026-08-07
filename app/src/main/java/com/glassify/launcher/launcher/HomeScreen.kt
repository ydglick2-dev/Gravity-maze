package com.glassify.launcher.launcher

import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.glassify.launcher.data.GlassifySettings
import com.glassify.launcher.data.HomeItem
import com.glassify.launcher.data.HomeLayout
import com.glassify.launcher.data.LaunchableApp
import com.glassify.launcher.glass.GlassMotion
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.liquidGlass
import com.glassify.launcher.glass.rememberTiltLight
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The home screen: status bar, paged icon grid, page dots, dock.
 *
 * Everything below the wallpaper layer is drawn inside the backdrop scope, so
 * the dock and the search field refract the actual wallpaper and icons behind
 * them rather than a stand-in.
 */
@Composable
fun HomeScreen(
    layout: HomeLayout,
    appsByKey: Map<String, LaunchableApp>,
    settings: GlassifySettings,
    editMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onMove: (fromPage: Int, fromIndex: Int, toPage: Int, toIndex: Int) -> Unit,
    onCombine: (page: Int, draggedIndex: Int, targetIndex: Int) -> Unit,
    onCommit: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pageCount = layout.pages.size
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val tilt = rememberTiltLight()

    // A single drag is tracked here rather than per-page so an icon can cross a
    // page boundary without the gesture being handed off mid-flight.
    var drag by remember { mutableStateOf<DragState?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootWidth by remember { mutableStateOf(0) }

    /** Applies a finished drag: either a folder merge or a reorder. */
    fun handleDrop(state: DragState) {
        val merge = state.mergeTarget
        when {
            merge != null && state.toPage == state.fromPage ->
                onCombine(state.fromPage, state.fromIndex, merge)

            state.toPage != state.fromPage || state.targetIndex != state.fromIndex ->
                onMove(state.fromPage, state.fromIndex, state.toPage, state.targetIndex)
        }
        onCommit()
    }

    // Holding a dragged icon against either edge turns the page, which is the
    // only way to move an app between pages.
    LaunchedEffect(drag?.position?.x, drag == null) {
        val state = drag ?: return@LaunchedEffect
        if (rootWidth <= 0) return@LaunchedEffect
        val x = state.position.x - rootOrigin.x
        val edge = rootWidth * PAGE_TURN_EDGE
        val target = when {
            x < edge -> pagerState.currentPage - 1
            x > rootWidth - edge -> pagerState.currentPage + 1
            else -> return@LaunchedEffect
        }
        if (target !in 0 until pageCount) return@LaunchedEffect
        kotlinx.coroutines.delay(PAGE_TURN_DWELL_MS)
        if (drag != null) pagerState.animateScrollToPage(target)
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootOrigin = it.positionInWindow()
                rootWidth = it.size.width
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            IosStatusBar()

            BoxWithConstraints(Modifier.weight(1f)) {
                val gridWidth = maxWidth
                val gridHeight = maxHeight

                HorizontalPager(
                    state = pagerState,
                    // Paging is locked during a drag: otherwise the horizontal
                    // component of the drag fights the pager for the gesture.
                    userScrollEnabled = drag == null,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    IconPage(
                        items = layout.pages.getOrNull(page)?.items.orEmpty(),
                        appsByKey = appsByKey,
                        settings = settings,
                        editMode = editMode,
                        pageIndex = page,
                        drag = drag,
                        onDragChange = { drag = it },
                        onLaunch = onLaunch,
                        onDrop = ::handleDrop,
                        onOpenFolder = onOpenFolder,
                        onEnterEditMode = { onEditModeChange(true) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Swiping down anywhere on empty grid space opens search, and
                // up opens the App Library — the two iOS home gestures.
                if (!editMode) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                var total = 0f
                                detectVerticalDragGestures(
                                    onDragStart = { total = 0f },
                                    onDragEnd = {
                                        when {
                                            total > SWIPE_THRESHOLD_PX -> onOpenSearch()
                                            total < -SWIPE_THRESHOLD_PX -> onOpenLibrary()
                                        }
                                    },
                                ) { _, amount -> total += amount }
                            },
                    )
                }
            }

            PageDots(
                count = pageCount,
                current = pagerState.currentPage,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Dock(
                keys = layout.dock,
                appsByKey = appsByKey,
                editMode = editMode,
                onLaunch = onLaunch,
                tilt = tilt,
                blurScale = settings.blurStrength,
                refractionScale = settings.refractionStrength,
                modifier = Modifier.padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 10.dp +
                        WindowInsets.navigationBars.asPaddingValues()
                            .calculateBottomPadding(),
                ),
            )
        }

        // Long-pressing the wallpaper itself is how iOS enters edit mode, and
        // it is also the only way into our settings without an app drawer icon.
        if (!editMode) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { onEditModeChange(true) })
                    },
            )
        }

        AnimatedVisibility(
            visible = editMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp),
        ) {
            EditModeBar(onDone = { onEditModeChange(false) }, onSettings = onOpenSettings)
        }

        // Held-out drag preview, drawn above everything so it is never clipped
        // by a page boundary.
        drag?.let { state ->
            val app = appsByKey[state.key]
            val density = LocalDensity.current
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = state.position.x - rootOrigin.x - state.grabOffset.x
                        translationY = state.position.y - rootOrigin.y - state.grabOffset.y
                        scaleX = 1.12f
                        scaleY = 1.12f
                        alpha = 0.92f
                    }
                    .size(with(density) { state.tileSize.width.toDp() }),
            ) {
                AppTile(
                    label = app?.label.orEmpty(),
                    icon = app?.icon,
                    showLabel = false,
                    jiggling = false,
                )
            }
        }
    }

    LaunchedEffect(pageCount) {
        if (pagerState.currentPage >= pageCount && pageCount > 0) {
            scope.launch { pagerState.scrollToPage(pageCount - 1) }
        }
    }
}

/** Fraction of the screen width at each side that turns the page during a drag. */
private const val PAGE_TURN_EDGE = 0.12f
private const val PAGE_TURN_DWELL_MS = 450L

/** A drag in flight. Positions are in window coordinates. */
data class DragState(
    val key: String,
    val fromPage: Int,
    val fromIndex: Int,
    /** The page currently under the finger, which may not be the source page. */
    val toPage: Int,
    val position: Offset,
    val grabOffset: Offset,
    val tileSize: androidx.compose.ui.geometry.Size,
    /** The slot the item would land in if released now. */
    val targetIndex: Int,
    /** Set when releasing here would merge into a folder instead of reordering. */
    val mergeTarget: Int? = null,
)

@Composable
private fun IconPage(
    items: List<HomeItem>,
    appsByKey: Map<String, LaunchableApp>,
    settings: GlassifySettings,
    editMode: Boolean,
    pageIndex: Int,
    drag: DragState?,
    onDragChange: (DragState?) -> Unit,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onDrop: (DragState) -> Unit,
    onOpenFolder: (String) -> Unit,
    onEnterEditMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = settings.columns
    val rows = settings.rows
    val density = LocalDensity.current

    // The gesture callbacks below live for the whole drag, so they must read the
    // live drag state rather than the value captured when they were installed.
    val liveDrag = androidx.compose.runtime.rememberUpdatedState(drag)
    val liveItems = androidx.compose.runtime.rememberUpdatedState(items)

    BoxWithConstraints(modifier.padding(horizontal = 14.dp)) {
        val cellWidth = maxWidth / columns
        val cellHeight = maxHeight / rows
        var pageOrigin by remember { mutableStateOf(Offset.Zero) }

        val cellWidthPx = with(density) { cellWidth.toPx() }
        val cellHeightPx = with(density) { cellHeight.toPx() }

        /**
         * Resolves a drop point into a slot, and decides between reordering and
         * merging into a folder.
         *
         * Landing near a slot's centre means "onto that icon" — make a folder.
         * Landing towards its edges means "between icons" — reorder. Using the
         * pointer's position within the cell keeps this predictable, where a
         * dwell timer would make the same gesture do different things depending
         * on how fast the user moved.
         */
        fun resolveDrop(point: Offset, fromIndex: Int, samePage: Boolean): Pair<Int, Int?> {
            val count = liveItems.value.size
            if (cellWidthPx <= 0f || cellHeightPx <= 0f || count == 0) return 0 to null

            val column = (point.x / cellWidthPx).toInt().coerceIn(0, columns - 1)
            val row = (point.y / cellHeightPx).toInt().coerceIn(0, rows - 1)
            val slot = (row * columns + column).coerceIn(0, count - 1)

            val withinX = (point.x % cellWidthPx) / cellWidthPx - 0.5f
            val withinY = (point.y % cellHeightPx) / cellHeightPx - 0.5f
            val central = kotlin.math.abs(withinX) < MERGE_ZONE && kotlin.math.abs(withinY) < MERGE_ZONE

            val merge = slot.takeIf { central && (!samePage || it != fromIndex) }
            return slot to merge
        }

        Column(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { pageOrigin = it.positionInWindow() },
            verticalArrangement = Arrangement.Top,
        ) {
            items.chunked(columns).forEachIndexed { rowIndex, rowItems ->
                Row(Modifier.fillMaxWidth().height(cellHeight)) {
                    rowItems.forEachIndexed { columnIndex, item ->
                        val index = rowIndex * columns + columnIndex
                        val beingDragged = drag?.fromPage == pageIndex && drag.fromIndex == index
                        val isMergeTarget =
                            drag?.mergeTarget == index && drag.toPage == pageIndex && !beingDragged

                        Box(
                            Modifier
                                .size(width = cellWidth, height = cellHeight)
                                .alpha(if (beingDragged) 0f else 1f)
                                .padding(vertical = 6.dp)
                                // In edit mode the cell owns the gesture; taps
                                // are disabled inside the tile so this sees them.
                                .then(
                                    if (!editMode) {
                                        Modifier
                                    } else {
                                        Modifier.pointerInput(pageIndex, index, columns, rows) {
                                            detectDragGestures(
                                                onDragStart = { start ->
                                                    onDragChange(
                                                        DragState(
                                                            key = item.dragKey(),
                                                            fromPage = pageIndex,
                                                            fromIndex = index,
                                                            toPage = pageIndex,
                                                            position = pageOrigin + Offset(
                                                                columnIndex * cellWidthPx + start.x,
                                                                rowIndex * cellHeightPx + start.y,
                                                            ),
                                                            grabOffset = start,
                                                            tileSize = androidx.compose.ui.geometry.Size(
                                                                cellWidthPx,
                                                                cellHeightPx,
                                                            ),
                                                            targetIndex = index,
                                                        )
                                                    )
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    val current = liveDrag.value
                                                        ?: return@detectDragGestures
                                                    val moved = current.position + amount
                                                    val (slot, merge) = resolveDrop(
                                                        moved - pageOrigin,
                                                        current.fromIndex,
                                                        current.fromPage == pageIndex,
                                                    )
                                                    onDragChange(
                                                        current.copy(
                                                            position = moved,
                                                            toPage = pageIndex,
                                                            targetIndex = slot,
                                                            mergeTarget = merge,
                                                        )
                                                    )
                                                },
                                                onDragEnd = {
                                                    liveDrag.value?.let(onDrop)
                                                    onDragChange(null)
                                                },
                                                onDragCancel = { onDragChange(null) },
                                            )
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            HomeItemTile(
                                item = item,
                                appsByKey = appsByKey,
                                showLabel = settings.showLabels,
                                jiggling = editMode,
                                // Swelling the icon under the cursor is the only
                                // signal that releasing will make a folder rather
                                // than push the icon aside.
                                highlighted = isMergeTarget,
                                onLaunch = onLaunch,
                                onOpenFolder = onOpenFolder,
                                onLongPress = onEnterEditMode,
                            )
                        }
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.size(width = cellWidth, height = cellHeight))
                    }
                }
            }
        }
    }
}

/** How much of a cell counts as "on top of that icon" rather than "beside it". */
private const val MERGE_ZONE = 0.26f

private fun HomeItem.dragKey(): String = when (this) {
    is HomeItem.App -> key
    is HomeItem.Folder -> id
}

@Composable
private fun HomeItemTile(
    item: HomeItem,
    appsByKey: Map<String, LaunchableApp>,
    showLabel: Boolean,
    jiggling: Boolean,
    highlighted: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onOpenFolder: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val swell by animateFloatAsState(
        targetValue = if (highlighted) 1.18f else 1f,
        animationSpec = GlassMotion.gooey(),
        label = "merge-target",
    )

    // `sourceBounds` is what gives the system a rectangle to animate the opening
    // app out of, so the launch grows from the icon the user actually touched.
    val positioned = Modifier
        .graphicsLayer {
            scaleX = swell
            scaleY = swell
        }
        .onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            bounds = Rect(
                position.x.roundToInt(),
                position.y.roundToInt(),
                (position.x + coordinates.size.width).roundToInt(),
                (position.y + coordinates.size.height).roundToInt(),
            )
        }

    when (item) {
        is HomeItem.App -> {
            val app = appsByKey[item.key] ?: return
            AppTile(
                label = app.label,
                icon = app.icon,
                showLabel = showLabel,
                jiggling = jiggling,
                modifier = positioned,
                onClick = { onLaunch(app, bounds) },
                onLongPress = onLongPress,
            )
        }

        is HomeItem.Folder -> {
            val apps = item.keys.mapNotNull { appsByKey[it] }
            FolderTile(
                name = item.name.ifBlank { defaultFolderName(apps) },
                apps = apps,
                showLabel = showLabel,
                jiggling = jiggling,
                modifier = positioned,
                onClick = { onOpenFolder(item.id) },
                onLongPress = onLongPress,
            )
        }
    }
}

/** iOS names a new folder after its contents; the category is the closest we get. */
private fun defaultFolderName(apps: List<LaunchableApp>): String =
    apps.groupingBy { it.category }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key
        ?.name
        ?.lowercase()
        ?.replaceFirstChar { it.uppercase() }
        ?: "Folder"

/** The dock: four slots on a single pane of glass. */
@Composable
private fun Dock(
    keys: List<String>,
    appsByKey: Map<String, LaunchableApp>,
    editMode: Boolean,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    tilt: androidx.compose.runtime.State<Offset>,
    blurScale: Float,
    refractionScale: Float,
    modifier: Modifier = Modifier,
) {
    val apps = keys.mapNotNull { appsByKey[it] }
    if (apps.isEmpty()) {
        Spacer(modifier.fillMaxWidth().height(1.dp))
        return
    }

    Row(
        modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = GlassShapes.dock,
                cornerRadius = 32.dp,
                spec = GlassSpec(
                    blur = (30 * blurScale).dp,
                    refraction = (11 * refractionScale).dp,
                    thickness = 14.dp,
                    specular = 0.6f,
                    tintAmount = 0.16f,
                ),
                tiltLight = tilt,
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        apps.take(4).forEach { app ->
            var bounds by remember(app.key) { mutableStateOf<Rect?>(null) }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInWindow()
                        bounds = Rect(
                            position.x.roundToInt(),
                            position.y.roundToInt(),
                            (position.x + coordinates.size.width).roundToInt(),
                            (position.y + coordinates.size.height).roundToInt(),
                        )
                    },
            ) {
                AppTile(
                    label = app.label,
                    icon = app.icon,
                    showLabel = false,
                    jiggling = editMode,
                    onClick = { onLaunch(app, bounds) },
                )
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count <= 1) {
        Spacer(modifier.height(8.dp))
        return
    }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val active = index == current
            val alpha by animateFloatAsState(
                targetValue = if (active) 1f else 0.35f,
                animationSpec = GlassMotion.snappy(),
                label = "dot",
            )
            Box(
                Modifier
                    .padding(horizontal = 3.5.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun EditModeBar(onDone: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .liquidGlass(shape = GlassShapes.pill, cornerRadius = 999.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = androidx.compose.ui.res.stringResource(com.glassify.launcher.R.string.settings),
            style = GlassTheme.type.callout,
            color = GlassTheme.colors.accent,
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { onSettings() } },
        )
        androidx.compose.material3.Text(
            text = androidx.compose.ui.res.stringResource(com.glassify.launcher.R.string.done),
            style = GlassTheme.type.headline,
            color = GlassTheme.colors.onGlass,
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { onDone() } },
        )
    }
}

private const val SWIPE_THRESHOLD_PX = 90f
