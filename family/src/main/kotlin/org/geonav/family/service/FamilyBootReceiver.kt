package org.geonav.family.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.geonav.family.Family

/**
 * Resumes sharing after reboot if the user had it enabled (ported from Luogo).
 *
 * This only re-arms a session the user explicitly left on; a fresh install or a user who
 * stopped sharing stays stopped.
 */
class FamilyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }
        runCatching {
            val sharing = Family.repository?.userProfile?.value?.sharingEnabled == true
            if (sharing) {
                FamilyShareService.start(context)
            }
        }
    }
}
