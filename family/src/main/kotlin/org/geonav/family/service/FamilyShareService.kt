package org.geonav.family.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.geonav.family.Family

/**
 * Foreground service that keeps the sharing session alive (ported from Luogo).
 *
 * Location acquisition itself lives in the host app, driven via [Family.hooks]; this
 * service only owns the foreground notification and the process priority that keeps the
 * session running while the screen is off.
 *
 * Contract with the repository:
 *  - [Family.repository.setSharingEnabled](true) sends [ACTION_START_SHARING] here.
 *  - [Family.repository.setSharingEnabled](false) (and the STOP SHARING button, the tile,
 *    and swipe-away) sends [ACTION_STOP_SHARING], which stops the service for good.
 */
class FamilyShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SHARING -> {
                Family.repository?.onShareServiceStopped()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val repo = Family.repository
        if (repo == null || !repo.userProfile.value.sharingEnabled) {
            // Nothing to share (or the repository is not up): never run in the foreground.
            stopSelf()
            return START_NOT_STICKY
        }
        showNotification()
        repo.onShareServiceStarted()
        // The service only persists the session; the host app owns the location callback.
        Family.hooks?.startLocationUpdates()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away while sharing: treat it as "stop sharing now".
        // Emergency stopping must work from every surface the user can reach.
        Family.repository?.setSharingEnabled(false)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Family.repository?.onShareServiceStopped()
        super.onDestroy()
    }

    private fun showNotification() {
        val repo = Family.repository ?: return
        val groups = repo.groupCountSync()
        val stoppedAt = repo.sharingStoppedAtMs
        val notification = repo.notificationHelper.buildSharingNotification(groups, stoppedAt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FamilyNotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(FamilyNotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START_SHARING = "org.geonav.family.START_SHARING"
        const val ACTION_STOP_SHARING = "org.geonav.family.STOP_SHARING"

        fun start(context: Context) {
            val intent = Intent(context, FamilyShareService::class.java)
                .setAction(ACTION_START_SHARING)
            runCatching {
                ContextCompatStartForeground.start(context, intent)
            }.onFailure {
                // Could not start the service (e.g. background restrictions). The sharing
                // state in the repository is still correct; only the notification is lost.
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FamilyShareService::class.java)
                .setAction(ACTION_STOP_SHARING)
            // startService (not startForegroundService): the service is already foreground
            // and only needs the stop action, and starting an FGS from the background is
            // restricted on Android 12+.
            runCatching { context.startService(intent) }
                .onFailure { runCatching { context.stopService(intent) } }
        }
    }
}

/** Small indirection so the start call is testable and exception-safe. */
private object ContextCompatStartForeground {
    fun start(context: Context, intent: Intent) {
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
}
