@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.geonav.family.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.geonav.family.Family
import org.geonav.family.domain.GroupCategory
import org.geonav.family.domain.PeerGroup
import org.geonav.family.domain.PeerLocationState
import org.geonav.family.domain.formatAge
import org.geonav.family.qr.QrCodeEncoder
import org.geonav.family.service.FamilyShareService
import org.geonav.family.ui.components.CategoryIconTile
import org.geonav.family.ui.components.EmptyState
import org.geonav.family.ui.components.FreshnessChip
import org.geonav.family.ui.components.FreshnessTone
import org.geonav.family.ui.components.InitialsAvatar
import org.geonav.family.ui.components.NoticeCard
import org.geonav.family.ui.components.NoticeTone
import org.geonav.family.ui.components.ScreenGaps
import org.geonav.family.ui.theme.FamilySpacing
import java.io.File

/**
 * The "People" screen: Luogo's family location-sharing UI, ported to the family module.
 *
 * Luogo's sharing unit is the *circle* (a group with a shared E2EE key). A circle with one
 * other person is a contact, so per-contact opt-in is the per-circle share toggle: you only
 * send your location to circles you have switched on, and the master switch gates everything.
 *
 * This screen owns: circles (add/remove/toggle/invite), sightings of other people, the
 * master sharing switch and the emergency stop. The host app owns the map layer and the
 * location pipeline; it feeds fixes in via [Family.hooks] and renders [Family.repository.peers].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = Family.repository ?: return

    val profile by repo.userProfile.collectAsState()
    val groups by repo.groups.collectAsState()
    val peers by repo.peers.collectAsState()
    val networkConfig by repo.networkConfig.collectAsState()
    val connected by repo.relayClient.connected.collectAsState()

    var showAddSheet by remember { mutableStateOf(false) }
    var inviteFor by remember { mutableStateOf<PeerGroup?>(null) }
    var showPasteInvite by remember { mutableStateOf(false) }
    var showNewCircle by remember { mutableStateOf(false) }

    // Refresh group/peer lists once when the screen opens.
    LaunchedEffect(Unit) { repo.syncRelayConnection() }

    // Permission gate: without location there is nothing to share.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        val needed = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val missing = needed.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
    val hasLocationPermission = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("People", maxLines = 1)
                        Text(
                            text = "Location sharing, exactly how you want it",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                text = { Text("Add person") },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SharingBanner(
                sharingEnabled = profile.sharingEnabled,
                connected = connected,
                relayUrl = networkConfig.relayUrl,
                lastBroadcastMs = repo.lastBroadcastMs,
                peerCount = peers.size,
                onToggle = { enabled -> repo.setSharingEnabled(enabled) },
                modifier = Modifier.padding(FamilySpacing.medium),
            )

            if (!hasLocationPermission) {
                NoticeCard(
                    text = "Location permission is needed to share your position. Grant it in system settings.",
                    tone = NoticeTone.WARNING,
                    icon = Icons.Default.Warning,
                    modifier = Modifier.padding(FamilySpacing.medium),
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(FamilySpacing.medium),
                verticalArrangement = ScreenGaps,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Sharing circles",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Per-circle opt-in",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (groups.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.Group,
                            title = "No circles yet",
                            body = "Add a person by exchanging invite keys, or create a circle for family and switch sharing on only when you want to.",
                        )
                    }
                } else {
                    items(groups, key = { it.id }) { group ->
                        CircleCard(
                            group = group,
                            onToggleShare = { share ->
                                scope.launch { repo.toggleGroupSharing(group.id, share) }
                            },
                            onInvite = { inviteFor = group },
                            onDelete = { scope.launch { repo.leaveOrDeleteGroup(group.id) } },
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(FamilySpacing.small))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "People you can see",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (peers.isNotEmpty()) {
                            FreshnessChip(
                                label = "${peers.size} seen",
                                tone = FreshnessTone.RECENT,
                            )
                        }
                    }
                }
                if (peers.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Default.Place,
                            title = "Nobody here yet",
                            body = "When someone in a circle you belong to shares their position, they appear here and on the map.",
                        )
                    }
                } else {
                    items(peers, key = { it.userId }) { peer ->
                        PeerCard(peer = peer)
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    // ---- Add person / circle sheet -----------------------------------------------------
    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(FamilySpacing.medium),
            ) {
                Text("Add", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(FamilySpacing.small))
                SheetAction(
                    icon = Icons.Default.Share,
                    title = "Share my invite key",
                    subtitle = "Create a one-to-one circle and send its invite to the person.",
                ) {
                    showAddSheet = false
                    scope.launch {
                        val circle = repo.createGroup("Contact", GroupCategory.CUSTOM)
                        inviteFor = circle
                    }
                }
                SheetAction(
                    icon = Icons.Default.Keyboard,
                    title = "Paste an invite key",
                    subtitle = "Received a key? Paste it to join their circle.",
                ) {
                    showAddSheet = false
                    showPasteInvite = true
                }
                SheetAction(
                    icon = Icons.Default.Group,
                    title = "New group circle",
                    subtitle = "A named circle (Family, Team, …) for several people.",
                ) {
                    showAddSheet = false
                    showNewCircle = true
                }
                Spacer(Modifier.height(FamilySpacing.large))
            }
        }
    }

    inviteFor?.let { group ->
        InviteSheet(
            group = group,
            onClose = { inviteFor = null },
            context = context,
        )
    }

    if (showPasteInvite) {
        PasteInviteDialog(
            onDismiss = { showPasteInvite = false },
            onJoin = { raw ->
                showPasteInvite = false
                scope.launch {
                    val joined = repo.joinGroupFromInvitePayload(raw)
                    if (joined == null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Invalid invite key", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )
    }

    if (showNewCircle) {
        NewCircleDialog(
            onDismiss = { showNewCircle = false },
            onCreate = { name, category ->
                showNewCircle = false
                scope.launch { repo.createGroup(name, category) }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Master banner
// ---------------------------------------------------------------------------------------------

@Composable
private fun SharingBanner(
    sharingEnabled: Boolean,
    connected: Boolean,
    relayUrl: String,
    lastBroadcastMs: Long?,
    peerCount: Int,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (sharingEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(modifier = Modifier.padding(FamilySpacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (sharingEnabled) Icons.Default.MyLocation else Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = if (sharingEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(FamilySpacing.small))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (sharingEnabled) "Sharing is ON" else "Sharing is OFF",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when {
                            sharingEnabled && connected -> "Relay connected · $relayUrl"
                            sharingEnabled -> "Relay connecting…"
                            else -> "Nothing leaves this device until you turn sharing on."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = sharingEnabled, onCheckedChange = onToggle)
            }
            if (sharingEnabled) {
                Spacer(Modifier.height(FamilySpacing.small))
                Text(
                    text = buildString {
                        append("Last send: ")
                        append(lastBroadcastMs?.let { formatAge(System.currentTimeMillis() - it) } ?: "never")
                        append(" · ").append(peerCount).append(" people visible")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(FamilySpacing.small))
                // Emergency stop: one tap, immediately stops the service and the socket.
                OutlinedButton(
                    onClick = { onToggle(false) },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("STOP SHARING NOW", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Circles
// ---------------------------------------------------------------------------------------------

@Composable
private fun CircleCard(
    group: PeerGroup,
    onToggleShare: (Boolean) -> Unit,
    onInvite: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(FamilySpacing.medium)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryIconTile(
                    icon = Icons.Default.Group,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    size = 40.dp,
                )
                Spacer(Modifier.width(FamilySpacing.small))
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = buildString {
                            append(group.category.label)
                            append(" · ")
                            append(group.memberCount).append(" member(s)")
                            if (!group.shareMyLocation) append(" · you are not sending")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = group.shareMyLocation, onCheckedChange = onToggleShare)
            }
            Spacer(Modifier.height(FamilySpacing.small))
            Row {
                FilledTonalButton(onClick = onInvite) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Invite")
                }
                Spacer(Modifier.width(FamilySpacing.small))
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Leave")
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Leave ${group.name}?") },
            text = { Text("You stop sending to and receiving from this circle. The key is deleted from this device.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); confirmDelete = false }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Sightings
// ---------------------------------------------------------------------------------------------

@Composable
private fun PeerCard(peer: PeerLocationState) {
    val now = System.currentTimeMillis()
    val stale = peer.isStale(now)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(FamilySpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InitialsAvatar(name = peer.displayName, colorArgb = peer.colorArgb)
            Spacer(Modifier.width(FamilySpacing.small))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${formatAge(peer.ageMs(now))} · ±${peer.accuracyMeters.toInt()} m · ${peer.activityState.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "%.5f, %.5f".format(peer.latitude, peer.longitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FreshnessChip(
                label = if (stale) "STALE" else "LIVE",
                tone = if (stale) FreshnessTone.STALE else FreshnessTone.LIVE,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets and dialogs
// ---------------------------------------------------------------------------------------------

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(FamilySpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(FamilySpacing.small))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InviteSheet(group: PeerGroup, onClose: () -> Unit, context: Context) {
    var payload by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(group.id) {
        payload = Family.repository?.generateGroupInvitePayload(group.id)
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FamilySpacing.medium),
        ) {
            Text("Invite to ${group.name}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(FamilySpacing.small))
            Text(
                text = "This key carries the circle's encryption key, sealed so only the person you send it to can open it. Share it with someone you trust.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FamilySpacing.small))
            val key = payload
            if (key == null) {
                Text("Preparing invite…", style = MaterialTheme.typography.bodyMedium)
            } else {
                OutlinedTextField(
                    value = key,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Invite key") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(FamilySpacing.small))
                Row {
                    Button(onClick = { copyToClipboard(context, key) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                    Spacer(Modifier.width(FamilySpacing.small))
                    OutlinedButton(onClick = { shareQr(context, key) }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share QR")
                    }
                }
            }
            Spacer(Modifier.height(FamilySpacing.large))
        }
    }
}

@Composable
private fun PasteInviteDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste invite key") },
        text = {
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it },
                label = { Text("Invite key") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        },
        confirmButton = {
            TextButton(onClick = { onJoin(raw.trim()) }, enabled = raw.isNotBlank()) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewCircleDialog(onDismiss: () -> Unit, onCreate: (String, GroupCategory) -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(GroupCategory.CUSTOM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group circle") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(FamilySpacing.small))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = ScreenGaps,
                ) {
                    GroupCategory.entries.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim().ifEmpty { "Circle" }, category) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("invite", text))
    Toast.makeText(context, "Invite key copied", Toast.LENGTH_SHORT).show()
}

private fun shareQr(context: Context, payload: String) {
    GlobalScope.launch {
        val file = runCatching {
            val bitmap = QrCodeEncoder.encode(payload, 512)
            val dir = File(context.cacheDir, "invites").apply { mkdirs() }
            val f = File(dir, "invite.png")
            f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            f
        }.getOrNull()
        if (file == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Could not create QR code", Toast.LENGTH_SHORT).show()
            }
            return@launch
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.family.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(send, "Share invite"))
        }
    }
}

internal fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
