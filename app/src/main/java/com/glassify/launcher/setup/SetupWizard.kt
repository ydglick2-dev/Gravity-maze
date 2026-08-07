package com.glassify.launcher.setup

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.glassify.launcher.R
import com.glassify.launcher.data.LauncherPrefs
import com.glassify.launcher.glass.GlassBackdropScope
import com.glassify.launcher.glass.GlassShapes
import com.glassify.launcher.glass.GlassSpec
import com.glassify.launcher.glass.GlassTheme
import com.glassify.launcher.glass.liquidGlass
import com.glassify.launcher.launcher.Wallpaper
import com.glassify.launcher.launcher.importWallpaper
import com.glassify.launcher.notifications.NotificationRepository
import com.glassify.launcher.overlay.OverlayService
import kotlinx.coroutines.launch

/**
 * First-run setup.
 *
 * Every step here is a permission Android will only grant through its own
 * settings screens, which means the user leaves the app and comes back for each
 * one. The whole design follows from that: each card states plainly what the
 * permission buys, the state re-checks itself on resume so returning shows a
 * tick without a refresh, and every step except the first can be skipped —
 * declining notification access should cost the Notification Centre, not the
 * launcher.
 */
@Composable
fun SetupWizard(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { LauncherPrefs(context) }
    var step by remember { mutableIntStateOf(0) }

    // Permissions are granted in another app, so the only reliable moment to
    // re-read them is when we come back to the foreground.
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isHome = remember(permissionEpoch) { isDefaultHome(context) }
    val hasNotifications = remember(permissionEpoch) { NotificationRepository.hasAccess(context) }
    val hasOverlay = remember(permissionEpoch) { OverlayService.canDrawOverlays(context) }
    val batteryExempt = remember(permissionEpoch) { isBatteryExempt(context) }

    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            importWallpaper(context, uri)?.let { path ->
                prefs.update { it.copy(wallpaperUri = path) }
            }
        }
    }

    LaunchedEffect(hasOverlay) {
        if (hasOverlay) OverlayService.syncWithPermissions(context)
    }

    GlassBackdropScope(
        modifier = Modifier.fillMaxSize(),
        background = { Wallpaper(presetIndex = 0, customPath = null) },
        content = {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 3 } + fadeOut())
                    },
                    label = "setup-step",
                ) { current ->
                    when (current) {
                        0 -> StepCard(
                            title = stringResource(R.string.setup_welcome_title),
                            body = stringResource(R.string.setup_welcome_body),
                            granted = false,
                            actionLabel = stringResource(R.string.setup_continue),
                            onAction = { step = 1 },
                        )

                        1 -> StepCard(
                            title = stringResource(R.string.setup_home_title),
                            body = stringResource(R.string.setup_home_body),
                            granted = isHome,
                            actionLabel = stringResource(R.string.setup_grant),
                            onAction = { requestHomeRole(context) },
                            onNext = { step = 2 },
                        )

                        2 -> StepCard(
                            title = stringResource(R.string.setup_notifications_title),
                            body = stringResource(R.string.setup_notifications_body),
                            granted = hasNotifications,
                            actionLabel = stringResource(R.string.setup_grant),
                            onAction = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                )
                            },
                            onNext = { step = 3 },
                        )

                        3 -> StepCard(
                            title = stringResource(R.string.setup_overlay_title),
                            body = stringResource(R.string.setup_overlay_body),
                            granted = hasOverlay,
                            actionLabel = stringResource(R.string.setup_grant),
                            onAction = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            },
                            onNext = { step = 4 },
                        )

                        4 -> StepCard(
                            title = stringResource(R.string.setup_battery_title),
                            body = stringResource(R.string.setup_battery_body),
                            footnote = stringResource(R.string.setup_battery_hint),
                            granted = batteryExempt,
                            actionLabel = stringResource(R.string.setup_grant),
                            onAction = { requestBatteryExemption(context) },
                            onNext = { step = 5 },
                        )

                        else -> StepCard(
                            title = stringResource(R.string.setup_wallpaper_title),
                            body = stringResource(R.string.setup_wallpaper_body),
                            granted = false,
                            actionLabel = stringResource(R.string.setup_wallpaper_pick),
                            onAction = {
                                pickWallpaper.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            nextLabel = stringResource(R.string.setup_finish),
                            onNext = {
                                scope.launch {
                                    prefs.update { it.copy(setupComplete = true) }
                                    OverlayService.syncWithPermissions(context)
                                    onFinished()
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(26.dp))
                StepDots(current = step, total = 6)
            }
        },
    )
}

@Composable
private fun StepCard(
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    footnote: String? = null,
    nextLabel: String = stringResource(R.string.setup_continue),
    onNext: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(
                shape = GlassShapes.panel,
                cornerRadius = 34.dp,
                spec = GlassSpec(blur = 30.dp, refraction = 10.dp, tintAmount = 0.20f),
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = title, style = GlassTheme.type.title, color = GlassTheme.colors.onGlass)
        Text(
            text = body,
            style = GlassTheme.type.callout,
            color = GlassTheme.colors.onGlassSecondary,
        )
        footnote?.let {
            Text(text = it, style = GlassTheme.type.footnote, color = GlassTheme.colors.onGlassTertiary)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WizardButton(
                label = if (granted) stringResource(R.string.setup_granted) else actionLabel,
                filled = !granted,
                enabled = !granted,
                onClick = onAction,
                modifier = Modifier.weight(1f),
            )
            onNext?.let {
                WizardButton(
                    label = if (granted) nextLabel else stringResource(R.string.setup_skip),
                    filled = granted,
                    enabled = true,
                    onClick = it,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WizardButton(
    label: String,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(GlassShapes.pill)
            .background(
                if (filled) GlassTheme.colors.accent else Color.White.copy(alpha = 0.14f)
            )
            .pointerInput(enabled, onClick) {
                if (enabled) detectTapGestures { onClick() }
            }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = GlassTheme.type.headline,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { index ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        Color.White.copy(alpha = if (index == current) 0.95f else 0.3f)
                    )
            )
        }
    }
}

/**
 * Asks to become the home app.
 *
 * `RoleManager` shows a one-tap system dialog; the fallback opens the launcher
 * chooser, which is the only route on devices where the HOME role is
 * unavailable.
 */
private fun requestHomeRole(context: Context) {
    val roleManager = context.getSystemService(RoleManager::class.java)
    if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
        context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
        return
    }
    context.startActivity(
        Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun isDefaultHome(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(
        intent,
        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
    )
    return resolved?.activityInfo?.packageName == context.packageName
}

private fun isBatteryExempt(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Opens the battery-optimisation list.
 *
 * Deliberately the list rather than `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`:
 * that direct request is a Play Store policy violation for an app that does not
 * strictly require it, and the list gets the user to the same switch.
 */
private fun requestBatteryExemption(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
