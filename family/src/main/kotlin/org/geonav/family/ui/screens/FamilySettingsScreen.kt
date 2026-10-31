@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.geonav.family.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.geonav.family.Family
import org.geonav.family.domain.NetworkConfig
import org.geonav.family.ui.components.DetailRow
import org.geonav.family.ui.components.EmptyState
import org.geonav.family.ui.components.NoticeCard
import org.geonav.family.ui.components.NoticeTone
import org.geonav.family.ui.components.ScreenGaps
import org.geonav.family.ui.components.SectionHeader
import org.geonav.family.ui.theme.FamilySpacing

/**
 * Sharing settings: identity, precision, relay.
 *
 * Ported from Luogo's SETTINGS tab and trimmed to what the sharing subsystem owns. The
 * master switch and emergency stop live on the People screen's banner; this screen is for
 * "who am I" and "how precisely, over which relay".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilySettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Family.repository ?: return

    val profile by repo.userProfile.collectAsState()
    val networkConfig by repo.networkConfig.collectAsState()
    val connected by repo.relayClient.connected.collectAsState()
    val lastError by repo.relayClient.lastError.collectAsState()

    var health by remember { mutableStateOf<org.geonav.family.network.RelayClient.HealthCheckResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    var relayUrlDraft by remember { mutableStateOf(networkConfig.relayUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Location sharing") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(FamilySpacing.medium),
            verticalArrangement = ScreenGaps,
        ) {
            // ---- Identity -------------------------------------------------------
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(FamilySpacing.medium)) {
                        SectionHeader("How you appear to others")
                        OutlinedTextField(
                            value = profile.displayName,
                            onValueChange = { name ->
                                repo.updateUserProfile(name, profile.colorArgb, profile.sharingEnabled)
                            },
                            label = { Text("Display name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(FamilySpacing.small))
                        Row(horizontalArrangement = Arrangement.spacedBy(FamilySpacing.small)) {
                            PRESET_COLORS.forEach { color ->
                                val selected = profile.colorArgb == color
                                Box(
                                    modifier = Modifier
                                        .size(if (selected) 36.dp else 30.dp)
                                        .background(Color(color.toInt()), CircleShape)
                                        .clickable {
                                            repo.updateUserProfile(
                                                profile.displayName,
                                                color,
                                                profile.sharingEnabled,
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- Precision ------------------------------------------------------
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(FamilySpacing.medium)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(FamilySpacing.small))
                            Text("How precisely people see you", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(FamilySpacing.small))
                        Text(
                            text = "Your position is rounded to a grid of this size before it is encrypted. Recipients see the rounded point and an accuracy that admits it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(FamilySpacing.small))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(FamilySpacing.small),
                        ) {
                            VISIBILITY_OPTIONS.forEach { (label, meters) ->
                                FilterChip(
                                    selected = profile.visibilityRadiusMeters == meters,
                                    onClick = { repo.setVisibilityRadius(meters) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }

            // ---- Relay ----------------------------------------------------------
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(FamilySpacing.medium)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.NetworkCheck,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(FamilySpacing.small))
                            Text("Relay", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(FamilySpacing.small))
                        Text(
                            text = "The relay forwards ciphertext between you and your circles. It never holds keys and cannot read your location.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(FamilySpacing.small))
                        OutlinedTextField(
                            value = relayUrlDraft,
                            onValueChange = { relayUrlDraft = it },
                            label = { Text("Relay URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(FamilySpacing.small))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    repo.updateNetworkConfig(networkConfig.copy(relayUrl = relayUrlDraft.trim()))
                                    Toast.makeText(context, "Relay saved", Toast.LENGTH_SHORT).show()
                                },
                                enabled = relayUrlDraft.trim() != networkConfig.relayUrl,
                            ) { Text("Save relay") }
                            Spacer(Modifier.width(FamilySpacing.small))
                            TextButton(
                                onClick = {
                                    checking = true
                                    health = null
                                    scope.launch {
                                        health = repo.relayClient.checkServerHealth(relayUrlDraft.trim())
                                        checking = false
                                    }
                                },
                            ) { Text("Test") }
                        }
                        when {
                            checking -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            health != null -> Text(
                                text = health!!.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (health!!.reachable) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        Spacer(Modifier.height(FamilySpacing.small))
                        DetailRow("Socket", if (connected) "connected" else "disconnected")
                        lastError?.let { DetailRow("Last error", it) }
                        DetailRow("Route", repo.relayClient.currentProxySummary())

                        Spacer(Modifier.height(FamilySpacing.small))
                        SettingsSwitchRow(
                            label = "Route through Tor (Orbot)",
                            checked = networkConfig.useTorOrbot,
                        ) { enabled ->
                            repo.updateNetworkConfig(networkConfig.copy(useTorOrbot = enabled))
                        }
                        SettingsSwitchRow(
                            label = "Custom SOCKS5 proxy",
                            checked = networkConfig.socks5Enabled,
                        ) { enabled ->
                            repo.updateNetworkConfig(networkConfig.copy(socks5Enabled = enabled))
                        }
                    }
                }
            }

            // ---- Honest note ----------------------------------------------------
            item {
                NoticeCard(
                    text = "Sharing is always opt-in: the master switch on the People screen starts or stops everything, and each circle can be switched off on its own. With sharing off and no circles, this module makes no network connections.",
                    tone = NoticeTone.INFO,
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** ARGB presets; the same palette Luogo offered, so shared contacts keep their colours. */
private val PRESET_COLORS = listOf(
    0xFF006C4CL,
    0xFF0277BDL,
    0xFFB33B1BL,
    0xFF6A1B9AL,
    0xFFAD6A00L,
    0xFF37474FL,
)

private val VISIBILITY_OPTIONS = listOf(
    "Precise" to 0,
    "~100 m" to 100,
    "~500 m" to 500,
    "~1 km" to 1000,
)
