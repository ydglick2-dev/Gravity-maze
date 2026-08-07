package com.glassify.launcher.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One notification, flattened into what the UI needs.
 *
 * Holding on to the whole [StatusBarNotification] would keep the posting app's
 * bundle alive for as long as our panel is open, so only the parts we draw are
 * copied out.
 */
@Immutable
data class GlassNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val whenMillis: Long,
    val smallIcon: Icon?,
    val largeIcon: Bitmap?,
    val contentIntent: PendingIntent?,
    val clearable: Boolean,
    val ongoing: Boolean,
)

/**
 * Live notifications, shared between the Notification Centre and the Dynamic
 * Island.
 *
 * A singleton because [GlassNotificationListener] is constructed by the system
 * and cannot be handed dependencies — everything that wants notifications has to
 * find them at a known address.
 */
object NotificationRepository {

    private val _notifications = MutableStateFlow<List<GlassNotification>>(emptyList())
    val notifications: StateFlow<List<GlassNotification>> = _notifications.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var listener: GlassNotificationListener? = null

    internal fun attach(service: GlassNotificationListener) {
        listener = service
        _connected.value = true
    }

    internal fun detach() {
        listener = null
        _connected.value = false
        _notifications.value = emptyList()
    }

    internal fun publish(items: List<GlassNotification>) {
        // Newest first, but anything ongoing (music, navigation, downloads) is
        // pinned below the dismissible ones the way iOS separates them.
        _notifications.value = items.sortedWith(
            compareBy<GlassNotification> { it.ongoing }.thenByDescending { it.whenMillis }
        )
    }

    fun dismiss(key: String) {
        listener?.cancelNotification(key)
    }

    fun dismissAll() {
        val clearable = _notifications.value.filter { it.clearable }.map { it.key }
        if (clearable.isEmpty()) return
        listener?.cancelNotifications(clearable.toTypedArray())
    }

    /** Whether the user has granted notification access in system settings. */
    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val component = ComponentName(context, GlassNotificationListener::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == component
        }
    }
}

/**
 * Receives notifications from the system.
 *
 * The whole active set is re-read on every change rather than maintaining an
 * incremental list. That is a little more work per event and much harder to get
 * wrong: notification keys are reused, groups summarise and re-summarise, and
 * ranking changes without a post or a removal.
 */
class GlassNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationRepository.attach(this)
        republish()
    }

    override fun onListenerDisconnected() {
        NotificationRepository.detach()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = republish()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = republish()

    private fun republish() {
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            return
        } ?: return

        NotificationRepository.publish(
            active.asSequence()
                .filterNot { it.isGroupSummary() }
                .mapNotNull { it.toGlassNotification() }
                .toList()
        )
    }

    /**
     * Group summaries duplicate their children — showing both is how an Android
     * shade ends up with "3 new messages" directly above those same 3 messages.
     */
    private fun StatusBarNotification.isGroupSummary(): Boolean =
        (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

    private fun StatusBarNotification.toGlassNotification(): GlassNotification? {
        val extras = notification?.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            )?.toString().orEmpty()

        // A notification with neither a title nor a body is a background service
        // marker, not something a person is meant to read.
        if (title.isBlank() && text.isBlank()) return null

        return GlassNotification(
            key = key,
            packageName = packageName,
            appLabel = appLabel(packageName),
            title = title,
            text = text,
            whenMillis = if (notification.`when` > 0) notification.`when` else postTime,
            smallIcon = notification.smallIcon,
            largeIcon = extras.largeIconCompat(),
            contentIntent = notification.contentIntent,
            clearable = isClearable,
            ongoing = isOngoing,
        )
    }

    /**
     * The typed `getParcelable` overload only exists from API 33; on 31 and 32
     * the deprecated one is the only option.
     */
    @Suppress("DEPRECATION")
    private fun android.os.Bundle.largeIconCompat(): Bitmap? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelable(Notification.EXTRA_LARGE_ICON, Bitmap::class.java)
        } else {
            getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap
        }

    private fun appLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }
}
