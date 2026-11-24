package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.preferences.AppThemeMode
import com.example.data.remote.ProviderDiagnostic
import com.example.data.remote.ProviderHealthStatus
import com.example.data.remote.SyncHealthState
import com.example.domain.RelativeTimeFormatter
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalRelayExtras
import com.example.ui.theme.themePreviewSwatches

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeStudioSheet(
    selectedTheme: AppThemeMode,
    onSelectTheme: (AppThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colorScheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorScheme.surface,
        contentColor = colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.theme_studio_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.theme_studio_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            AppThemeMode.entries.forEach { mode ->
                val active = mode == selectedTheme
                val (bg, surface, accent) = themePreviewSwatches(mode)
                Surface(
                    onClick = { onSelectTheme(mode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("theme_option_${mode.id}"),
                    shape = MaterialTheme.shapes.medium,
                    color = if (active) colorScheme.primaryContainer.copy(alpha = 0.55f) else colorScheme.surfaceContainer,
                    border = BorderStroke(
                        width = if (active) 1.5.dp else 1.dp,
                        color = if (active) colorScheme.primary else colorScheme.outlineVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Sleek 3-swatch chip preview
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .border(1.dp, colorScheme.outline, RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(surface)
                                    .border(0.5.dp, accent.copy(alpha = 0.4f), CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(accent)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurface,
                                )
                                if (mode == AppThemeMode.CIRCUIT_AMBER) {
                                    Text(
                                        text = "DEFAULT",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                text = mode.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Selected theme",
                                tint = colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHealthSheet(
    syncHealth: SyncHealthState,
    onSyncNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colorScheme.surface,
        contentColor = colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.sync_health_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "Auto-polls live public providers every 10s",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
                SyncHealthPill(status = syncHealth.overallStatus, onClick = {})
            }

            HorizontalDivider(color = colorScheme.outlineVariant)

            syncHealth.providers.values.forEach { provider ->
                ProviderDiagnosticRow(provider = provider)
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = onSyncNow,
                enabled = !syncHealth.isManualRefreshing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("sync_now_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (syncHealth.isManualRefreshing) "Syncing feeds..." else stringResource(R.string.action_sync_now),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (syncHealth.lastSuccessfulSyncMs != null) {
                Text(
                    text = "Last successful pull: ${RelativeTimeFormatter.formatShortClock(syncHealth.lastSuccessfulSyncMs!!)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.liveSignal.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun ProviderDiagnosticRow(provider: ProviderDiagnostic) {
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current

    val (statusLabel, statusColor) = when (provider.status) {
        ProviderHealthStatus.LIVE -> "ONLINE" to extras.liveSignal
        ProviderHealthStatus.SYNCING -> "PULLING" to colorScheme.primary
        ProviderHealthStatus.DEGRADED -> "DEGRADED" to extras.inactiveSignal
        ProviderHealthStatus.IDLE -> "IDLE" to colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val latencyText = provider.lastLatencyMs?.let { "${it}ms" } ?: "—"
                val lastSuccess = provider.lastSuccessEpochMs?.let {
                    RelativeTimeFormatter.formatRelative(it)
                } ?: "Not synced yet"
                Text(
                    text = "Latency $latencyText · +${provider.lastNewCount} new",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = lastSuccess,
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            if (!provider.lastError.isNullOrBlank()) {
                Text(
                    text = "Error: ${provider.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.inactiveSignal,
                )
            }
        }
    }
}
