package org.geonav.family.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.geonav.family.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notifications for the family location-sharing service (adapted from Luogo).
 * Channel IDs kept identical to Luogo's for continuity.
 */
class FamilyNotificationHelper(private val context: Context) {

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.family_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.family_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Builds the foreground notification for the share service.
     *
     * Ongoing while sharing is live; becomes a dismissible summary once the user has
     * stopped sharing (so an emergency stop is not blocked by an ongoing notification).
     */
    fun buildSharingNotification(groups: Int, stoppedAtMs: Long?): android.app.Notification {
        createNotificationChannels()
        val stopText = stoppedAtMs?.let { formatStopText(it) }
            ?: context.getString(R.string.family_notification_no_stop)
        val contentText = context.getString(R.string.family_notification_text, groups, stopText)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.family_notification_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(stoppedAtMs == null)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchIntent())
            .build()
    }

    fun showSharingNotification(groups: Int, stoppedAtMs: Long?) {
        if (!hasPermission()) return
        notificationManager()?.notify(
            NOTIFICATION_ID,
            buildSharingNotification(groups, stoppedAtMs),
        )
    }

    fun cancelSharingNotification() {
        notificationManager()?.cancel(NOTIFICATION_ID)
    }

    private fun notificationManager(): NotificationManager? =
        runCatching { context.getSystemService(NotificationManager::class.java) }.getOrNull()

    private fun launchIntent(): PendingIntent? =
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return null
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()

    private fun formatStopText(epochMs: Long): String {
        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
        return context.getString(R.string.family_notification_stop, format.format(Date(epochMs)))
    }

    companion object {
        const val CHANNEL_ID = "live_location_sharing"
        const val NOTIFICATION_ID = 73021
    }
}
