package il.kolan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import il.kolan.audio.NativeEngine
import il.kolan.call.CallManager
import il.kolan.data.BuiltInPresets
import il.kolan.data.CustomPreset
import il.kolan.service.VoiceService
import il.kolan.ui.screens.CallScreen
import il.kolan.ui.screens.EditorScreen
import il.kolan.ui.screens.HomeScreen
import il.kolan.ui.screens.OnboardingScreen
import il.kolan.ui.screens.SettingsScreen
import il.kolan.ui.screens.VoiceMessageScreen
import il.kolan.ui.theme.KolanTheme
import il.kolan.util.Permissions
import il.kolan.util.rememberHaptics
import kotlinx.coroutines.launch

private enum class Screen {
    ONBOARDING,
    HOME,
    EDITOR,
    CALL,
    VOICE_MESSAGE,
    SETTINGS,
}

class MainActivity : ComponentActivity() {

    private lateinit var callManager: CallManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        callManager = CallManager(applicationContext, lifecycleScope)

        setContent {
            KolanTheme {
                KolanNavHost(callManager)
            }
        }
    }

    override fun onDestroy() {
        callManager.release()
        super.onDestroy()
    }
}

@Composable
private fun KolanNavHost(callManager: CallManager) {
    val context = LocalContext.current
    val app = context.applicationContext as KolanApp
    val scope = rememberCoroutineScope()

    val onboardingComplete by app.settingsRepository.onboardingComplete
        .collectAsState(initial = true)
    val signalingUrl by app.settingsRepository.signalingUrl.collectAsState(initial = "")
    val hapticsEnabled by app.settingsRepository.hapticsEnabled.collectAsState(initial = true)

    val presets by app.presetRepository.allPresets
        .collectAsState(initial = BuiltInPresets.all)
    val selectedPreset by app.presetRepository.selectedPreset
        .collectAsState(initial = BuiltInPresets.default)
    val workingParams by app.presetRepository.workingParams
        .collectAsState(initial = BuiltInPresets.default.params)

    val engineMode by app.engineController.mode.collectAsStateWithLifecycle()
    val engineFailure by app.engineController.failure.collectAsStateWithLifecycle()
    val routeState by app.engineController.routeState.collectAsStateWithLifecycle()

    val callState by callManager.state.collectAsStateWithLifecycle()
    val callRoomCode by callManager.roomCode.collectAsStateWithLifecycle()
    val callError by callManager.errorReason.collectAsStateWithLifecycle()

    val haptics = rememberHaptics(hapticsEnabled)

    var screen by remember { mutableStateOf(Screen.HOME) }
    var micMuted by remember { mutableStateOf(false) }
    var hasMicPermission by remember { mutableStateOf(Permissions.hasMicrophone(context)) }

    LaunchedEffect(onboardingComplete) {
        if (!onboardingComplete) screen = Screen.ONBOARDING
    }

    LaunchedEffect(screen) {
        hasMicPermission = Permissions.hasMicrophone(context)
    }

    when (screen) {
        Screen.ONBOARDING -> OnboardingScreen(
            onFinished = {
                scope.launch { app.settingsRepository.setOnboardingComplete(true) }
                hasMicPermission = Permissions.hasMicrophone(context)
                screen = Screen.HOME
            },
        )

        Screen.HOME -> HomeScreen(
            presets = presets,
            selectedPresetId = selectedPreset.id,
            engineMode = engineMode,
            failure = engineFailure,
            headphonesConnected = routeState.headphonesConnected,
            onSelectPreset = { preset ->
                haptics.presetChanged()
                scope.launch { app.presetRepository.selectPreset(preset) }
            },
            onStart = { mode ->
                if (!Permissions.hasMicrophone(context)) {
                    hasMicPermission = false
                    Permissions.openAppSettings(context)
                } else {
                    VoiceService.start(context, mode)
                }
            },
            onStop = { VoiceService.stop(context) },
            onEdit = { screen = Screen.EDITOR },
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenCall = { screen = Screen.CALL },
            onOpenVoiceMessage = { screen = Screen.VOICE_MESSAGE },
            onBypassChange = { app.engineController.setBypassed(it) },
            onDismissFailure = { app.engineController.stop() },
        )

        Screen.EDITOR -> EditorScreen(
            preset = selectedPreset,
            params = workingParams,
            onParamsChange = { params ->
                // Applied to the engine immediately as well as stored, so a slider is audible
                // while it moves rather than after it is released.
                app.engineController.applyParams(params)
                scope.launch { app.presetRepository.updateWorkingParams(params) }
            },
            onSaveAsCustom = { name ->
                haptics.presetChanged()
                scope.launch {
                    app.presetRepository.saveAsCustom(
                        name = name,
                        iconKey = selectedPreset.iconKey,
                        params = workingParams,
                    )
                }
                screen = Screen.HOME
            },
            onRevert = {
                scope.launch { app.presetRepository.updateWorkingParams(selectedPreset.params) }
            },
            onDelete = {
                val custom = selectedPreset as? CustomPreset ?: return@EditorScreen
                scope.launch { app.presetRepository.deleteCustom(custom.id) }
                screen = Screen.HOME
            },
            onBack = { screen = Screen.HOME },
        )

        Screen.CALL -> CallScreen(
            signalingUrl = signalingUrl,
            state = callState,
            roomCode = callRoomCode,
            errorReason = callError,
            micMuted = micMuted,
            hasMicPermission = hasMicPermission,
            onRequestMicPermission = {
                Permissions.openAppSettings(context)
            },
            onStartCall = { code ->
                // The Oboe engine and WebRTC cannot both own the microphone, so the live path
                // is torn down before the call takes it over.
                VoiceService.stop(context)
                app.engineController.enterCallMode()
                callManager.start(signalingUrl, code)
            },
            onHangUp = {
                callManager.hangUp()
                app.engineController.exitCallMode()
                micMuted = false
                screen = Screen.HOME
            },
            onToggleMute = {
                micMuted = !micMuted
                callManager.setMicMuted(micMuted)
            },
            onOpenSettings = { screen = Screen.SETTINGS },
            onBack = { screen = Screen.HOME },
        )

        Screen.VOICE_MESSAGE -> VoiceMessageScreen(
            params = workingParams,
            hasMicPermission = hasMicPermission,
            onRequestMicPermission = { Permissions.openAppSettings(context) },
            onBack = { screen = Screen.HOME },
        )

        Screen.SETTINGS -> SettingsScreen(
            signalingUrl = signalingUrl,
            hapticsEnabled = hapticsEnabled,
            xrunCount = NativeEngine.xrunCount(),
            onSignalingUrlChange = { url ->
                scope.launch { app.settingsRepository.setSignalingUrl(url) }
            },
            onHapticsChange = { enabled ->
                scope.launch { app.settingsRepository.setHapticsEnabled(enabled) }
            },
            onBack = { screen = Screen.HOME },
        )
    }
}
