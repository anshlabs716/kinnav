package org.geonav.family.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.geonav.family.ui.theme.FamilySpacing

/**
 * Shared building blocks for the family/sharing UI.
 *
 * These are a trimmed port of Luogo's CommonComponents (same visuals, same spacing scale)
 * so the sharing screens look and behave the way they did in Luogo.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** Simple compact header for use inside a bottom sheet or a dialog. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FamilySpacing.medium, vertical = FamilySpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionHeader(title: String, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FamilySpacing.medium, vertical = FamilySpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/** Shown when a list has nothing in it. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(FamilySpacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(FamilySpacing.medium))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(FamilySpacing.xSmall))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(FamilySpacing.medium))
            action()
        }
    }
}

/** Circular initials avatar tinted with the person's configured colour. */
@Composable
fun InitialsAvatar(
    name: String,
    colorArgb: Long,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    ringColor: Color? = null,
) {
    Box(
        modifier = modifier
            .size(if (ringColor != null) size + 8.dp else size)
            .then(
                if (ringColor != null) {
                    Modifier.border(3.dp, ringColor, CircleShape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .background(Color(colorArgb.toInt()), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Status chip whose colour reflects how fresh the underlying data is.
 * LIVE, recently seen, stale and unknown are visually distinct, so a glance is enough.
 */
@Composable
fun FreshnessChip(
    label: String,
    tone: FreshnessTone,
    modifier: Modifier = Modifier,
) {
    val container = when (tone) {
        FreshnessTone.LIVE -> MaterialTheme.colorScheme.primaryContainer
        FreshnessTone.RECENT -> MaterialTheme.colorScheme.tertiaryContainer
        FreshnessTone.STALE -> MaterialTheme.colorScheme.surfaceContainerHighest
        FreshnessTone.UNKNOWN -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (tone) {
        FreshnessTone.LIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        FreshnessTone.RECENT -> MaterialTheme.colorScheme.onTertiaryContainer
        FreshnessTone.STALE -> MaterialTheme.colorScheme.onSurfaceVariant
        FreshnessTone.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = container, modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

enum class FreshnessTone { LIVE, RECENT, STALE, UNKNOWN }

/** Rounded icon tile used to give list rows a visual identity. */
@Composable
fun CategoryIconTile(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    container: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(container, MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

/** Label/value row used throughout the detail sheets and settings. */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Inline notice, used for warnings and for "this is unavailable" explanations.
 * The container is blended toward the surface so it reads as a band, not a highlighter.
 */
@Composable
fun NoticeCard(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Info,
    tone: NoticeTone = NoticeTone.INFO,
) {
    val scheme = MaterialTheme.colorScheme
    val base = when (tone) {
        NoticeTone.INFO -> scheme.secondaryContainer
        NoticeTone.WARNING -> scheme.tertiaryContainer
        NoticeTone.ERROR -> scheme.errorContainer
    }
    val content = when (tone) {
        NoticeTone.INFO -> scheme.onSecondaryContainer
        NoticeTone.WARNING -> scheme.onTertiaryContainer
        NoticeTone.ERROR -> scheme.onErrorContainer
    }
    val container = lerp(base, scheme.surfaceContainer, 0.35f)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = content)
        }
    }
}

enum class NoticeTone { INFO, WARNING, ERROR }

val ScreenPadding = PaddingValues(
    horizontal = FamilySpacing.medium,
    vertical = FamilySpacing.small,
)

val ScreenGaps: Arrangement.HorizontalOrVertical = Arrangement.spacedBy(FamilySpacing.small)
