package org.geonav.family.service

import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.geonav.family.Family

/**
 * Quick Settings tile for starting/stopping location sharing (ported from Luogo).
 *
 * Mirrors the master switch on the People screen: one tap to start, one tap to stop, and
 * the label always says which state a tap will move to.
 */
class FamilyShareTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val sharing = Family.repository?.userProfile?.value?.sharingEnabled == true
        if (sharing) {
            // Emergency stop path: also shuts the socket via setSharingEnabled(false).
            Family.repository?.setSharingEnabled(false)
        } else {
            FamilyShareService.start(this)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val sharing = Family.repository?.userProfile?.value?.sharingEnabled == true
        tile.state = if (sharing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(
            if (sharing) {
                org.geonav.family.R.string.family_share_tile_active
            } else {
                org.geonav.family.R.string.family_share_tile_inactive
            },
        )
        tile.updateTile()
    }
}
