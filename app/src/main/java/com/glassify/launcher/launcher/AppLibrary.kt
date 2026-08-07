package com.glassify.launcher.launcher

import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glassify.launcher.R
import com.glassify.launcher.data.AppCategory
import com.glassify.launcher.data.LaunchableApp
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.liquidGlass

/**
 * The App Library: every installed app, grouped, on one scrolling page.
 *
 * Each category is a single glass pod holding a 2x2 preview. Three slots show
 * the three most likely apps at full size and the fourth holds the rest as a
 * mini grid — the same trick iOS uses to keep a pod one tap from anything while
 * still fitting a category of ninety apps into one tile.
 */
@Composable
fun AppLibrary(
    apps: List<LaunchableApp>,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = remember(apps) {
        apps.groupBy { it.category }
            .toList()
            .sortedByDescending { (_, list) -> list.size }
    }

    Column(modifier.fillMaxSize()) {
        IosStatusBar()

        Text(
            text = stringResource(R.string.app_library),
            style = GlassTheme.type.largeTitle,
            color = GlassTheme.colors.onGlass,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(grouped, key = { it.first.name }) { (category, list) ->
                CategoryPod(category, list, onLaunch)
            }
        }
    }

    // Anywhere outside a pod dismisses, matching the swipe-back feel.
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onClose() }) }
    )
}

@Composable
private fun CategoryPod(
    category: AppCategory,
    apps: List<LaunchableApp>,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.86f)
            .liquidGlass(
                shape = GlassShapes.panel,
                cornerRadius = 34.dp,
                spec = GlassSpec(blur = 22.dp, refraction = 7.dp, tintAmount = 0.20f),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(category.labelRes()),
            style = GlassTheme.type.footnote,
            color = GlassTheme.colors.onGlassSecondary,
            maxLines = 1,
        )

        val featured = apps.take(3)
        val overflow = apps.drop(3)

        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                featured.getOrNull(0)?.let { LibraryIcon(it, Modifier.weight(1f), onLaunch) }
                    ?: Box(Modifier.weight(1f))
                featured.getOrNull(1)?.let { LibraryIcon(it, Modifier.weight(1f), onLaunch) }
                    ?: Box(Modifier.weight(1f))
            }
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                featured.getOrNull(2)?.let { LibraryIcon(it, Modifier.weight(1f), onLaunch) }
                    ?: Box(Modifier.weight(1f))
                if (overflow.isNotEmpty()) {
                    OverflowCluster(overflow, Modifier.weight(1f))
                } else {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LibraryIcon(
    app: LaunchableApp,
    modifier: Modifier = Modifier,
    onLaunch: (LaunchableApp, Rect?) -> Unit,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(GlassShapes.icon)
                .pointerInput(app.key) { detectTapGestures { onLaunch(app, null) } },
        ) {
            app.icon?.let {
                Image(
                    bitmap = it,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = app.label,
            style = GlassTheme.type.caption,
            color = GlassTheme.colors.onGlass,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The fourth slot: everything else, shrunk into a 2x2 of mini icons. */
@Composable
private fun OverflowCluster(apps: List<LaunchableApp>, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(GlassShapes.icon)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(7.dp),
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            apps.take(4).chunked(2).forEach { row ->
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    row.forEach { app ->
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            app.icon?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(GlassShapes.icon),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                    repeat(2 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

fun AppCategory.labelRes(): Int = when (this) {
    AppCategory.SOCIAL -> R.string.category_social
    AppCategory.ENTERTAINMENT -> R.string.category_entertainment
    AppCategory.CREATIVITY -> R.string.category_creativity
    AppCategory.PRODUCTIVITY -> R.string.category_productivity
    AppCategory.UTILITIES -> R.string.category_utilities
    AppCategory.INFORMATION -> R.string.category_information
    AppCategory.TRAVEL -> R.string.category_travel
    AppCategory.SHOPPING -> R.string.category_shopping
    AppCategory.FINANCE -> R.string.category_finance
    AppCategory.HEALTH -> R.string.category_health
    AppCategory.GAMES -> R.string.category_games
    AppCategory.OTHER -> R.string.category_other
}
