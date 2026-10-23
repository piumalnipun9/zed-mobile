package dev.zed.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.zeddesktop.mobile.BuildConfig
import dev.zeddesktop.mobile.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a direct, authenticated connection to the paired desktop for ephemeral Agent completion
 * events. It is deliberately non-sticky: process death and offline intervals drop events instead
 * of causing a later replay.
 */
class GatewayEventService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val credentialStore: CredentialStore by lazy { AndroidCredentialStore(applicationContext) }
    private val settingsStore: AndroidConnectionSettingsStore by lazy {
        AndroidConnectionSettingsStore(applicationContext)
    }
    private val projectCatalogStore: ProjectCatalogStore by lazy {
        AndroidProjectCatalogStore(applicationContext)
    }
    private var connectionJob: Job? = null
    private var settingsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        settingsJob = serviceScope.launch {
            settingsStore.stayConnected.collect { enabled ->
                if (enabled) {
                    startConnectionIfNeeded()
                } else {
                    stopConnectionAndSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        settingsJob?.cancel()
        connectionJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun startConnectionIfNeeded() {
        if (connectionJob?.isActive == true) return
        connectionJob = serviceScope.launch {
            val credential = try {
                credentialStore.load()
            } catch (_: Exception) {
                null
            }
            if (credential == null) {
                clearProjectCatalog()
                stopSelf()
                return@launch
            }

            val eventClient = try {
                GatewayEventClient(credential, BuildConfig.DEBUG)
            } catch (_: Exception) {
                stopSelf()
                return@launch
            }
            var reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS
            while (isActive) {
                try {
                    eventClient.collect(::showCompletionNotification)
                    reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS
                } catch (error: CancellationException) {
                    throw error
                } catch (_: GatewayEventAuthenticationException) {
                    clearPersistentPairingData()
                    stopSelf()
                    return@launch
                } catch (_: GatewayEventException) {
                    // Invalid, unavailable, or closed streams only trigger a future-events reconnect.
                } catch (_: Exception) {
                    // Do not expose connection details or desktop payloads from the background service.
                }

                delay(reconnectDelayMillis)
                reconnectDelayMillis = (reconnectDelayMillis * 2).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
            }
        }
    }

    private fun stopConnectionAndSelf() {
        connectionJob?.cancel()
        connectionJob = null
        stopSelf()
    }

    private suspend fun clearProjectCatalog() {
        try {
            projectCatalogStore.clear()
        } catch (_: Exception) {
            // The catalog is UI-only and neither it nor credential material is logged.
        }
    }

    private suspend fun clearPersistentPairingData() {
        clearProjectCatalog()
        try {
            credentialStore.clear()
        } catch (_: Exception) {
            // The credential is never logged. The user can disconnect or pair again.
        }
    }

    private fun showCompletionNotification(event: AgentCompletionEvent) {
        if (!canPostNotifications()) return
        // Opaque event IDs stay local to the transport. The desktop supplies only bounded project
        // and thread display labels for this private, user-visible notification.
        val (title, detail, category) = when (event.type) {
            AgentCompletionEventType.COMPLETED -> Triple(
                "Agent task completed",
                "${event.threadTitle} completed in ${event.projectName} on your paired desktop.",
                Notification.CATEGORY_STATUS,
            )

            AgentCompletionEventType.FAILED -> Triple(
                "Agent task failed",
                "${event.threadTitle} needs attention in ${event.projectName} on your paired desktop.",
                Notification.CATEGORY_ERROR,
            )
        }
        val notificationId = nextNotificationId()
        val notification = Notification.Builder(this, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mascot)
            .setContentTitle(title)
            .setContentText("${event.projectName} · ${event.threadTitle}")
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(detail)
                    .setBigContentTitle(title)
                    .setSummaryText("Zed Desktop"),
            )
            .setContentIntent(contentIntentFor(notificationId))
            .setAutoCancel(true)
            .setCategory(category)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun contentIntentFor(requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, CONNECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mascot)
            .setContentTitle("Connected to Zed-Desktop for Agent updates")
            .setContentText("Listening directly to your paired desktop.")
            .setContentIntent(appContentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun appContentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        FOREGROUND_NOTIFICATION_ID,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL_ID,
                "Agent connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing direct connection to your paired Zed desktop"
                setShowBadge(false)
            },
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                EVENT_CHANNEL_ID,
                "Agent task updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Completion and failure updates from all Agent tasks on your paired Zed desktop"
            },
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun nextNotificationId(): Int {
        nextNotificationId =
            if (nextNotificationId == Int.MAX_VALUE) EVENT_NOTIFICATION_ID_START else nextNotificationId + 1
        return nextNotificationId
    }

    companion object {

        private const val CONNECTION_CHANNEL_ID = "agent_connection"
        private const val EVENT_CHANNEL_ID = "agent_completion_events"
        private const val FOREGROUND_NOTIFICATION_ID = 4001
        private const val EVENT_NOTIFICATION_ID_START = 4100
        private const val INITIAL_RECONNECT_DELAY_MILLIS = 2_000L
        private const val MAX_RECONNECT_DELAY_MILLIS = 60_000L
        private var nextNotificationId = EVENT_NOTIFICATION_ID_START

        fun start(context: Context) {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, GatewayEventService::class.java),
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, GatewayEventService::class.java),
            )
        }
    }
}
