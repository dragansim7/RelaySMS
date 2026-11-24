package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.CustomSourceEntity
import com.example.data.local.TempNumberEntity
import com.example.data.remote.ProbeOutcome
import com.example.data.remote.SourceProbeAttempt
import com.example.data.remote.TempSyncDiagnosticReport
import com.example.domain.PhoneFormatter
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalRelayExtras

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LiveSyncInspectorSheet(
    e164: String,
    currentSourceType: String,
    currentCustomSourceId: Long?,
    customSources: List<CustomSourceEntity>,
    report: TempSyncDiagnosticReport?,
    isProbing: Boolean,
    onRerunProbe: () -> Unit,
    onSwitchSource: (sourceType: String, customSourceId: Long?) -> Unit,
    onDismiss: () -> Unit,
    allowSourceSwitching: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current
    val context = LocalContext.current
    var copiedReport by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Troubleshoot,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.inspector_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = PhoneFormatter.displayE164(e164),
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                text = stringResource(R.string.inspector_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )

            if (isProbing) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.inspector_running),
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.primary,
                        )
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = colorScheme.primary,
                            trackColor = colorScheme.surfaceVariant,
                        )
                    }
                }
            }

            if (report != null) {
                val (verdictColor, verdictIcon) = when (report.overallVerdict) {
                    ProbeOutcome.SUCCESS_WITH_MESSAGES -> extras.liveSignal to Icons.Rounded.CheckCircle
                    ProbeOutcome.EMPTY_INBOX -> colorScheme.primary to Icons.Rounded.CheckCircle
                    ProbeOutcome.BOT_BLOCKED -> extras.inactiveSignal to Icons.Rounded.Shield
                    else -> extras.inactiveSignal to Icons.Rounded.ErrorOutline
                }

                // Overall Verdict Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inspector_verdict_card"),
                    shape = MaterialTheme.shapes.medium,
                    color = colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.25.dp, verdictColor.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = verdictIcon,
                                    contentDescription = null,
                                    tint = verdictColor,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = report.overallVerdict.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = verdictColor,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                text = "${report.totalLatencyMs}ms · ${report.attempts.size} probe${if (report.attempts.size == 1) "" else "s"}",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }

                        Text(
                            text = report.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface,
                        )

                        Text(
                            text = "Configured: ${report.configuredSource} · Active: ${report.resolvedSource ?: "None"}",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Per-Source Probe Timeline
                Text(
                    text = "SOURCE PROBE TIMELINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )

                if (report.attempts.isEmpty()) {
                    Text(
                        text = "No outbound HTTP probes were recorded yet. Tap Re-run Probe to test live connectivity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        report.attempts.forEachIndexed { idx, attempt ->
                            ProbeAttemptCard(
                                index = idx + 1,
                                attempt = attempt,
                            )
                        }
                    }
                }
            }

            if (allowSourceSwitching) {
                HorizontalDivider(color = colorScheme.outlineVariant)

                // Quick Switch Source
                Text(
                    text = "QUICK SWITCH SOURCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val builtIns = listOf(
                        TempNumberEntity.SOURCE_AUTO_DETECT to "Auto-Detect",
                        TempNumberEntity.SOURCE_ANOSIM to "Anosim.net",
                        TempNumberEntity.SOURCE_QUACKR to "Quackr.io",
                        TempNumberEntity.SOURCE_SMS24_ME to "SMS24.me",
                        TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR to "Receive-SMSS.live",
                        TempNumberEntity.SOURCE_TEMPSMSS to "TempSMSS.com",
                        TempNumberEntity.SOURCE_RECEIVE_SMS_FREE to "Receive-SMS-Free",
                        TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE to "Receive-SMS-Online",
                        TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API to "GetVirtualPhone",
                    )
                    builtIns.forEach { (key, label) ->
                        val selected = currentSourceType == key && currentCustomSourceId == null
                        FilterChip(
                            selected = selected,
                            enabled = !isProbing,
                            onClick = { onSwitchSource(key, null) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colorScheme.primary,
                                selectedLabelColor = colorScheme.onPrimary,
                            ),
                            modifier = Modifier.testTag("inspector_source_$key"),
                        )
                    }
                    customSources.forEach { custom ->
                        val selected = currentSourceType == TempNumberEntity.SOURCE_CUSTOM && currentCustomSourceId == custom.id
                        FilterChip(
                            selected = selected,
                            enabled = !isProbing,
                            onClick = { onSwitchSource(TempNumberEntity.SOURCE_CUSTOM, custom.id) },
                            label = { Text(custom.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colorScheme.primary,
                                selectedLabelColor = colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        copiedReport = false
                        onRerunProbe()
                    },
                    enabled = !isProbing,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("inspector_rerun_button"),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.inspector_action_rerun))
                }

                if (report != null) {
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Relay Diagnostic Report", report.formatShareableText()))
                            copiedReport = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("inspector_copy_button"),
                    ) {
                        Icon(
                            imageVector = if (copiedReport) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = if (copiedReport) extras.liveSignal else colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (copiedReport) stringResource(R.string.action_copied) else stringResource(R.string.inspector_action_copy))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeAttemptCard(
    index: Int,
    attempt: SourceProbeAttempt,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current
    var showSnippet by remember(attempt.url, attempt.httpCode) { mutableStateOf(false) }

    val outcomeColor = when (attempt.outcome) {
        ProbeOutcome.SUCCESS_WITH_MESSAGES -> extras.liveSignal
        ProbeOutcome.EMPTY_INBOX -> colorScheme.primary
        else -> extras.inactiveSignal
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("probe_attempt_$index"),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$index. ${attempt.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = outcomeColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, outcomeColor.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = attempt.outcome.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = outcomeColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }

            val sizeLabel = if (attempt.payloadBytes >= 1024) {
                "${"%.1f".format(attempt.payloadBytes / 1024.0)} KB"
            } else {
                "${attempt.payloadBytes} B"
            }
            val metricsParts = buildList {
                add("HTTP ${attempt.httpCode ?: "ERR"}")
                add("${attempt.latencyMs}ms")
                if (attempt.payloadBytes > 0) add(sizeLabel)
                if (!attempt.parserStrategy.isNullOrBlank()) add(attempt.parserStrategy)
                if (attempt.fromCache) add("cached")
            }
            Text(
                text = metricsParts.joinToString(" · "),
                fontFamily = JetBrainsMonoFamily,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )

            Text(
                text = attempt.url,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (attempt.botChallengeDetected) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = colorScheme.errorContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "Anti-bot / Cloudflare challenge detected",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (attempt.parsedCount > 0 || attempt.insertedCount > 0 || attempt.dedupSkippedCount > 0 || attempt.droppedByRetentionCount > 0) {
                val retentionSuffix = if (attempt.droppedByRetentionCount > 0) {
                    " · Older skipped: ${attempt.droppedByRetentionCount}"
                } else {
                    ""
                }
                Text(
                    text = "Parsed: ${attempt.parsedCount} · Saved new: +${attempt.insertedCount} · Duplicates skipped: ${attempt.dedupSkippedCount}$retentionSuffix",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.liveSignal,
                )
            }

            if (attempt.detailMessage.isNotBlank()) {
                Text(
                    text = attempt.detailMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface,
                )
            }

            if (!attempt.responseSnippet.isNullOrBlank()) {
                TextButton(
                    onClick = { showSnippet = !showSnippet },
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(
                        imageVector = if (showSnippet) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (showSnippet) "Hide response preview" else "Show response preview",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                AnimatedVisibility(visible = showSnippet) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = attempt.responseSnippet,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}
