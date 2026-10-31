package org.geonav.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.geonav.family.domain.PeerLocationState
import org.geonav.family.domain.formatAge
import org.geonav.family.ui.components.DetailRow
import org.geonav.family.ui.components.FreshnessChip
import org.geonav.family.ui.components.FreshnessTone
import org.geonav.family.ui.components.InitialsAvatar
import org.geonav.family.ui.theme.FamilySpacing

/**
 * Bottom sheet for a person seen on the map (a contact who shares their position).
 *
 * Shows who they are, how fresh their fix is, and the one action that matters on a map:
 * "Navigate here", which hands their coordinate to GeoNav's routing pipeline exactly the
 * same way selecting a search result does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonSheet(
    peer: PeerLocationState,
    onNavigateHere: (PeerLocationState) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val stale = peer.isStale(now)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FamilySpacing.medium),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar(
                    name = peer.displayName,
                    colorArgb = peer.colorArgb,
                    ringColor = if (stale) null else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(FamilySpacing.small))
                Column(modifier = Modifier.weight(1f)) {
                    Text(peer.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "${formatAge(peer.ageMs(now))} · ±${peer.accuracyMeters.toInt()} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FreshnessChip(
                    label = if (stale) "STALE" else "LIVE",
                    tone = if (stale) FreshnessTone.STALE else FreshnessTone.LIVE,
                )
            }

            Spacer(Modifier.height(FamilySpacing.medium))

            DetailRow("Position", "%.5f, %.5f".format(peer.latitude, peer.longitude))
            DetailRow("Activity", peer.activityState.label)
            peer.batteryPercent?.let { DetailRow("Battery", "$it%") }
            DetailRow("Source", peer.sourceSummary)
            DetailRow("Updated", formatAge(peer.ageMs(now)))

            Spacer(Modifier.height(FamilySpacing.medium))

            // The action this sheet exists for.
            Button(
                onClick = { onNavigateHere(peer) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Navigate here")
            }

            if (stale) {
                Spacer(Modifier.height(FamilySpacing.small))
                Text(
                    text = "This position is a few minutes old — they may have moved since.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(FamilySpacing.large))
        }
    }
}
