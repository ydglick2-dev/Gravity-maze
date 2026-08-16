package il.kolan

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import il.kolan.audio.EngineController
import il.kolan.data.PresetRepository
import il.kolan.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Application-scoped wiring.
 *
 * There is one microphone, one engine and one preset selection, and the foreground service has
 * to reach all three from outside any Activity, so these live here rather than in a ViewModel.
 */
class KolanApp : Application() {

    lateinit var presetRepository: PresetRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var engineController: EngineController
        private set

    val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this

        presetRepository = PresetRepository(this)
        settingsRepository = SettingsRepository(this)
        engineController = EngineController(this, appScope)

        createNotificationChannel()

        // Keep the engine's parameters in step with whatever the user is editing, from anywhere
        // in the app and from the notification controls alike.
        appScope.launch {
            combine(
                presetRepository.workingParams,
                presetRepository.selectedPresetId,
            ) { params, _ -> params }.collect { params ->
                engineController.applyParams(params)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "kolan_engine"

        lateinit var instance: KolanApp
            private set
    }
}
