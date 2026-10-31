@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.geonav.family.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.geonav.family.Family
import org.geonav.family.domain.FamilyDiagnostics
import org.geonav.family.ui.components.EmptyState
import org.geonav.family.ui.components.ScreenGaps
import org.geonav.family.ui.theme.FamilySpacing

/**
 * NERDY diagnostics for the sharing subsystem (MILES Studio-style, ported intent).
 *
 * Every value is read from real state: the relay client's counters, the database, the
 * crypto engine's key inventory, plus whatever rows the host app contributes through
 * [Family.hooks]. Nothing here is a mock or a hardcoded string — if a row says "0 fixes
 * sent", zero fixes were sent.
 *
 * The host app only offers this screen in debug builds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyDiagnosticsScreen(
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var diagnostics by remember { mutableStateOf<FamilyDiagnostics?>(null) }

    LaunchedEffect(Unit) {
        val repo = Family.repository ?: return@LaunchedEffect
        diagnostics = repo.buildDiagnostics(Family.hooks?.diagnosticRows() ?: emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sharing diagnostics") },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { copyAll(context, diagnostics) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy diagnostics")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
    ) { padding ->
        val diag = diagnostics
        if (diag == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(FamilySpacing.large))
                EmptyState(
                    icon = Icons.Default.Info,
                    title = "Collecting…",
                    body = "Reading relay, database and key state.",
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(FamilySpacing.medium),
            verticalArrangement = ScreenGaps,
        ) {
            if (diag.hostRows.isNotEmpty()) {
                item { DiagnosticsCard(title = "Host positioning stack", rows = diag.hostRows.map { it.label to it.value }) }
            }
            item { DiagnosticsCard(title = "Sharing engine", rows = diag.rows.map { it.label to it.value }) }
            item {
                Text("Event log", style = MaterialTheme.typography.titleMedium)
            }
            if (diag.logs.isEmpty()) {
                item {
                    Text(
                        text = "No events yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(diag.logs) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(title: String, rows: List<Pair<String, String>>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(FamilySpacing.medium)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(FamilySpacing.small))
            rows.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(170.dp),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun copyAll(context: Context, diagnostics: FamilyDiagnostics?) {
    val diag = diagnostics ?: return
    val text = buildString {
        diag.hostRows.forEach { append(it.label).append(": ").append(it.value).append('\n') }
        append('\n')
        diag.rows.forEach { append(it.label).append(": ").append(it.value).append('\n') }
        append('\n')
        diag.logs.forEach { append(it).append('\n') }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("sharing-diagnostics", text))
    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
}
