package il.kolan.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import il.kolan.R
import il.kolan.ui.components.GradientButton
import il.kolan.ui.components.OutlineButton
import il.kolan.ui.theme.KolanAccentGradient
import il.kolan.ui.theme.KolanBackground
import il.kolan.ui.theme.KolanCyan
import il.kolan.ui.theme.KolanSuccess
import il.kolan.ui.theme.KolanSurface
import il.kolan.ui.theme.KolanTextPrimary
import il.kolan.ui.theme.KolanTextSecondary
import il.kolan.ui.theme.KolanWarning
import il.kolan.util.Permissions

private data class OnboardingPage(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val permission: String?,
    val accent: Color,
)

/**
 * Explains each permission before the system dialog appears, and — just as importantly — is
 * honest up front about what the app cannot do.
 *
 * Telling users on page two that WhatsApp will still hear their real voice avoids the much worse
 * discovery of that fact after they have recorded a call they assumed was disguised.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Notification and Bluetooth permissions do not exist below API 33 and 31 respectively, so
    // those pages are omitted rather than shown with a button that would do nothing.
    val pages = remember {
        buildList {
            add(
                OnboardingPage(
                    icon = Icons.Filled.WavingHand,
                    titleRes = R.string.onboarding_welcome_title,
                    bodyRes = R.string.onboarding_welcome_body,
                    permission = null,
                    accent = KolanCyan,
                ),
            )
            add(
                OnboardingPage(
                    icon = Icons.Filled.Info,
                    titleRes = R.string.onboarding_reality_title,
                    bodyRes = R.string.onboarding_reality_body,
                    permission = null,
                    accent = KolanWarning,
                ),
            )
            add(
                OnboardingPage(
                    icon = Icons.Filled.Mic,
                    titleRes = R.string.onboarding_mic_title,
                    bodyRes = R.string.onboarding_mic_body,
                    permission = Permissions.MICROPHONE,
                    accent = KolanCyan,
                ),
            )
            Permissions.NOTIFICATIONS?.let { permission ->
                add(
                    OnboardingPage(
                        icon = Icons.Filled.Notifications,
                        titleRes = R.string.onboarding_notification_title,
                        bodyRes = R.string.onboarding_notification_body,
                        permission = permission,
                        accent = KolanCyan,
                    ),
                )
            }
            Permissions.BLUETOOTH?.let { permission ->
                add(
                    OnboardingPage(
                        icon = Icons.Filled.Bluetooth,
                        titleRes = R.string.onboarding_bluetooth_title,
                        bodyRes = R.string.onboarding_bluetooth_body,
                        permission = permission,
                        accent = KolanCyan,
                    ),
                )
            }
        }
    }

    var index by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(permissionStates(context, pages)) }
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        granted = permissionStates(context, pages)
        denied = !isGranted
    }

    val page = pages[index]
    val isLast = index == pages.lastIndex
    val permissionGranted = page.permission == null || granted[index]

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KolanBackground)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onFinished) {
                Text(stringResource(R.string.onboarding_skip), color = KolanTextSecondary)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(KolanAccentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    page.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(page.titleRes),
                style = MaterialTheme.typography.headlineMedium,
                color = KolanTextPrimary,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(page.bodyRes),
                style = MaterialTheme.typography.bodyLarge,
                color = KolanTextSecondary,
            )

            AnimatedVisibility(visible = permissionGranted && page.permission != null) {
                Row(
                    modifier = Modifier.padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = KolanSuccess,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(R.string.onboarding_granted),
                        style = MaterialTheme.typography.labelLarge,
                        color = KolanSuccess,
                    )
                }
            }

            AnimatedVisibility(visible = denied && !permissionGranted) {
                Column(modifier = Modifier.padding(top = 18.dp)) {
                    Text(
                        stringResource(R.string.onboarding_denied_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KolanWarning,
                    )
                    TextButton(onClick = { Permissions.openAppSettings(context) }) {
                        Text(stringResource(R.string.onboarding_open_settings), color = KolanCyan)
                    }
                }
            }
        }

        PageDots(count = pages.size, selected = index)

        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (page.permission != null && !permissionGranted) {
                GradientButton(
                    text = stringResource(R.string.onboarding_grant),
                    onClick = {
                        denied = false
                        launcher.launch(page.permission)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlineButton(
                    text = if (isLast) stringResource(R.string.onboarding_finish)
                    else stringResource(R.string.onboarding_next),
                    onClick = { if (isLast) onFinished() else index++ },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                GradientButton(
                    text = if (isLast) stringResource(R.string.onboarding_finish)
                    else stringResource(R.string.onboarding_next),
                    onClick = {
                        denied = false
                        if (isLast) onFinished() else index++
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun permissionStates(context: Context, pages: List<OnboardingPage>): List<Boolean> =
    pages.map { Permissions.isGranted(context, it.permission) }

@Composable
private fun PageDots(count: Int, selected: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = if (i == selected) 22.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (i == selected) KolanCyan else KolanSurface),
            )
        }
    }
}
