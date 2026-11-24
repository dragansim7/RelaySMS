package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.CustomSourceEntity
import com.example.data.local.TempNumberDetailRow
import com.example.data.local.TempNumberEntity
import com.example.data.repository.RelayRepository
import com.example.domain.PhoneFormatter
import com.example.domain.RelativeTimeFormatter
import com.example.ui.components.LiveSyncInspectorSheet
import com.example.ui.components.StatusSignalDot
import com.example.ui.components.circuitBoardAccent
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalRelayExtras

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenInbox: (String) -> Unit,
    onOpenThemeStudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current

    var showPublicWarning by remember { mutableStateOf(false) }
    var showAddOrEditDialog by remember { mutableStateOf(false) }
    var editingTemp by remember { mutableStateOf<TempNumberDetailRow?>(null) }
    var deletingTemp by remember { mutableStateOf<TempNumberDetailRow?>(null) }
    var manualMsgTargetId by remember { mutableStateOf<String?>(null) }

    var showCustomSourceDialog by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<CustomSourceEntity?>(null) }
    var deletingSource by remember { mutableStateOf<CustomSourceEntity?>(null) }

    val inspectingItem = state.inspectingNumberId?.let { id ->
        state.tempNumbers.firstOrNull { it.numberId == id }
    }
    if (inspectingItem != null) {
        LiveSyncInspectorSheet(
            e164 = inspectingItem.e164,
            currentSourceType = inspectingItem.sourceType,
            currentCustomSourceId = inspectingItem.customSourceId,
            customSources = state.customSources,
            report = state.diagnosticReports[inspectingItem.numberId],
            isProbing = state.isProbingInspector,
            onRerunProbe = { viewModel.inspectTempNumber(inspectingItem.numberId) },
            onSwitchSource = { srcType, customId ->
                viewModel.switchTempSourceFromInspector(inspectingItem.numberId, srcType, customId)
            },
            onDismiss = viewModel::dismissInspector,
        )
    }

    if (showPublicWarning) {
        PublicInboxWarningDialog(
            onAcknowledge = {
                viewModel.acknowledgePublicWarning()
                showPublicWarning = false
                editingTemp = null
                showAddOrEditDialog = true
            },
            onDismiss = { showPublicWarning = false },
        )
    }

    if (showAddOrEditDialog) {
        AddOrEditTempNumberDialog(
            editing = editingTemp,
            customSources = state.customSources,
            syncFgSeconds = state.syncSettings.foregroundIntervalSec,
            syncBgMinutes = state.syncSettings.backgroundIntervalMin,
            onConfirmSave = { draft ->
                val target = editingTemp
                showAddOrEditDialog = false
                editingTemp = null
                if (target == null) {
                    viewModel.addTempNumber(
                        rawNumber = draft.rawNumber,
                        label = draft.label,
                        notes = draft.notes,
                        mode = draft.mode,
                        sourceType = draft.sourceType,
                        customSourceId = draft.customSourceId,
                        initialManualSender = draft.initialManualSender,
                        initialManualBody = draft.initialManualBody,
                        initialManualTimestampMs = draft.initialManualTimestampMs,
                        onComplete = { /* stays in Settings with banner & quick Open tap */ },
                    )
                } else {
                    viewModel.updateTempNumber(
                        numberId = target.numberId,
                        label = draft.label,
                        notes = draft.notes,
                        mode = draft.mode,
                        sourceType = draft.sourceType,
                        customSourceId = draft.customSourceId,
                    )
                }
            },
            onDismiss = {
                showAddOrEditDialog = false
                editingTemp = null
            },
        )
    }

    if (deletingTemp != null) {
        val target = deletingTemp!!
        AlertDialog(
            onDismissRequest = { deletingTemp = null },
            containerColor = colorScheme.surfaceContainerHigh,
            title = { Text("Delete temporary number?") },
            text = {
                Text(
                    text = "This removes ${PhoneFormatter.displayE164(target.e164)} (${target.label}) and permanently deletes all ${target.messageCount} stored messages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deletingTemp = null
                        viewModel.deleteTempNumber(target.numberId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("confirm_delete_temp_button"),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTemp = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (manualMsgTargetId != null) {
        val targetId = manualMsgTargetId!!
        ManualMessageDialog(
            title = stringResource(R.string.manual_add_message),
            onSave = { sender, body, ts ->
                manualMsgTargetId = null
                viewModel.addManualMessage(targetId, sender, body, ts)
            },
            onDismiss = { manualMsgTargetId = null },
        )
    }

    if (showCustomSourceDialog) {
        CustomSourceDialog(
            editing = editingSource,
            onSave = { entity ->
                viewModel.saveCustomSource(entity) {
                    showCustomSourceDialog = false
                    editingSource = null
                }
            },
            onDismiss = {
                showCustomSourceDialog = false
                editingSource = null
            },
        )
    }

    if (deletingSource != null) {
        val src = deletingSource!!
        AlertDialog(
            onDismissRequest = { deletingSource = null },
            containerColor = colorScheme.surfaceContainerHigh,
            title = { Text("Remove custom source?") },
            text = {
                Text(
                    text = "Remove \"${src.name}\"? Existing cached messages will stay intact, but numbers using this source will need another source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deletingSource = null
                        viewModel.deleteCustomSource(src.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSource = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Settings Header
            item(key = "settings_header") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .circuitBoardAccent()
                            .padding(18.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_eyebrow),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.settings_headline),
                            style = MaterialTheme.typography.headlineMedium,
                            color = colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_subcopy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.isBusy) {
                item(key = "settings_busy") {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colorScheme.primary,
                        trackColor = colorScheme.surfaceVariant,
                    )
                }
            }

            // Feedback Banners
            if (!state.statusBanner.isNullOrBlank()) {
                item(key = "status_banner") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = extras.liveSignal.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, extras.liveSignal.copy(alpha = 0.45f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = extras.liveSignal, modifier = Modifier.size(18.dp))
                                Text(state.statusBanner!!, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = viewModel::dismissBanners) { Text("OK") }
                        }
                    }
                }
            }

            if (!state.errorBanner.isNullOrBlank()) {
                item(key = "error_banner") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = colorScheme.errorContainer.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.55f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.ErrorOutline, null, tint = colorScheme.error, modifier = Modifier.size(18.dp))
                                Text(state.errorBanner!!, style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurface)
                            }
                            TextButton(onClick = viewModel::dismissBanners) { Text("Dismiss") }
                        }
                    }
                }
            }

            // Section 1: Temporary Numbers Header + Add CTA
            item(key = "temp_section_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.temp_section_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "${state.tempNumbers.size} configured · synced to dashboard",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            if (!state.syncSettings.publicWarningAcknowledged) {
                                showPublicWarning = true
                            } else {
                                editingTemp = null
                                showAddOrEditDialog = true
                            }
                        },
                        modifier = Modifier.testTag("add_temp_number_button"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add")
                    }
                }
            }

            if (state.tempNumbers.isEmpty()) {
                item(key = "temp_empty") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SimCard,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = stringResource(R.string.temp_empty_state),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    if (!state.syncSettings.publicWarningAcknowledged) {
                                        showPublicWarning = true
                                    } else {
                                        editingTemp = null
                                        showAddOrEditDialog = true
                                    }
                                },
                                modifier = Modifier.testTag("empty_add_temp_button"),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.temp_add_button))
                            }
                        }
                    }
                }
            } else {
                items(state.tempNumbers, key = { "temp_${it.numberId}" }) { item ->
                    TempNumberSettingsCard(
                        item = item,
                        nowMs = state.nowTickMs,
                        onOpenInbox = { onOpenInbox(item.numberId) },
                        onSyncNow = { viewModel.syncSingleTempNumber(item.numberId) },
                        onInspect = { viewModel.inspectTempNumber(item.numberId) },
                        onAddManualMsg = { manualMsgTargetId = item.numberId },
                        onTogglePin = { viewModel.togglePin(item.numberId) },
                        onToggleMute = { viewModel.toggleMute(item.numberId) },
                        onEdit = {
                            editingTemp = item
                            showAddOrEditDialog = true
                        },
                        onDelete = { deletingTemp = item },
                    )
                }
            }

            // Section 2: Sync Schedule & Background WorkManager
            item(key = "sync_schedule_card") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.CloudSync, null, tint = colorScheme.primary, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(R.string.temp_sync_schedule_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Text(
                            text = stringResource(R.string.temp_sync_schedule_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )

                        Text(
                            text = "ACTIVE FOREGROUND POLLING",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 45, 60).forEach { sec ->
                                val selected = state.syncSettings.foregroundIntervalSec == sec
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        viewModel.updateSyncIntervals(sec, state.syncSettings.backgroundIntervalMin)
                                    },
                                    label = { Text("${sec}s") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colorScheme.primary,
                                        selectedLabelColor = colorScheme.onPrimary,
                                    ),
                                    modifier = Modifier.testTag("fg_interval_${sec}s"),
                                )
                            }
                        }

                        Text(
                            text = "BACKGROUND WORKMANAGER SYNC",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15 to "15m", 30 to "30m", 60 to "60m", 0 to "Off").forEach { (min, lbl) ->
                                val selected = state.syncSettings.backgroundIntervalMin == min
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        viewModel.updateSyncIntervals(state.syncSettings.foregroundIntervalSec, min)
                                    },
                                    label = { Text(lbl) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colorScheme.primary,
                                        selectedLabelColor = colorScheme.onPrimary,
                                    ),
                                    modifier = Modifier.testTag("bg_interval_${min}m"),
                                )
                            }
                        }

                        if (state.tempNumbers.any { it.mode == TempNumberEntity.MODE_AUTO }) {
                            OutlinedButton(
                                onClick = viewModel::syncAllTempNumbers,
                                enabled = !state.isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("sync_all_temp_button"),
                            ) {
                                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Sync All Auto Temp Numbers Now")
                            }
                        }
                    }
                }
            }

            // Section 3: Custom SMS Sources
            item(key = "custom_sources_card") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                Icon(Icons.Rounded.Hub, null, tint = colorScheme.primary, modifier = Modifier.size(20.dp))
                                Text(
                                    text = stringResource(R.string.temp_custom_sources_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            TextButton(
                                onClick = {
                                    editingSource = null
                                    showCustomSourceDialog = true
                                },
                                modifier = Modifier.testTag("add_custom_source_button"),
                            ) {
                                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add")
                            }
                        }

                        Text(
                            text = stringResource(R.string.temp_custom_sources_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )

                        // Built-in sources summary
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, colorScheme.outlineVariant),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "BUILT-IN AU (+61) & GLOBAL PLATFORMS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.primary,
                                )
                                Text(
                                    text = "• Anosim.net (Direct country page + cookie AJAX · AU +61)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurface,
                                )
                                Text(
                                    text = "• Quackr.io, TempSMSS.com, Receive-SMS-Free.cc & Receive-SMS-Online.info (AU +61 hosts)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurface,
                                )
                                Text(
                                    text = "• SMS24.me & Receive-SMSS.live (Hardened HTML + anti-bot diagnostics)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurface,
                                )
                                Text(
                                    text = "• GetVirtualPhoneNumber JSON API (Primary clean JSON · 15s cache)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurface,
                                )
                            }
                        }

                        state.customSources.forEach { src ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, colorScheme.outlineVariant),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${src.name} · ${src.parserType}",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            text = src.urlTemplate,
                                            fontFamily = JetBrainsMonoFamily,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editingSource = src
                                            showCustomSourceDialog = true
                                        }
                                    ) {
                                        Icon(Icons.Outlined.Edit, contentDescription = "Edit custom source")
                                    }
                                    IconButton(onClick = { deletingSource = src }) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = "Delete custom source",
                                            tint = colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 4: Appearance Shortcut
            item(key = "appearance_card") {
                Surface(
                    onClick = onOpenThemeStudio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_theme_card"),
                    shape = MaterialTheme.shapes.large,
                    color = colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Outlined.Palette, null, tint = colorScheme.primary)
                            Column {
                                Text(
                                    text = stringResource(R.string.theme_studio_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = "Current: ${state.themeMode.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = "Change",
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.primary,
                        )
                    }
                }
            }

            item(key = "settings_bottom_spacer") {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TempNumberSettingsCard(
    item: TempNumberDetailRow,
    nowMs: Long,
    onOpenInbox: () -> Unit,
    onSyncNow: () -> Unit,
    onInspect: () -> Unit,
    onAddManualMsg: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current
    val isManual = item.mode == TempNumberEntity.MODE_MANUAL

    val (statusLabel, statusColor, dotActive) = when (item.status) {
        TempNumberEntity.STATUS_ONLINE -> Triple("ONLINE", extras.liveSignal, true)
        TempNumberEntity.STATUS_SYNCING -> Triple("SYNCING", colorScheme.primary, true)
        TempNumberEntity.STATUS_RATE_LIMITED -> Triple("RATE LIMITED", colorScheme.primary, false)
        TempNumberEntity.STATUS_BOT_BLOCKED -> Triple("BOT BLOCKED", extras.inactiveSignal, false)
        TempNumberEntity.STATUS_NOT_FOUND -> Triple("NOT FOUND", extras.inactiveSignal, false)
        TempNumberEntity.STATUS_OFFLINE -> Triple("OFFLINE", extras.inactiveSignal, false)
        TempNumberEntity.STATUS_ERROR -> Triple("ERROR", extras.inactiveSignal, false)
        else -> Triple("MANUAL", colorScheme.onSurfaceVariant, false)
    }

    val sourceLabel = RelayRepository.sourceDisplayName(
        sourceType = item.resolvedSource ?: item.sourceType,
        customName = item.customSourceName,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpenInbox)
            .testTag("settings_temp_card_${item.numberId}"),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            width = if (item.isPinned) 1.25.dp else 1.dp,
            color = if (item.isPinned) colorScheme.primary.copy(alpha = 0.6f) else colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusSignalDot(active = dotActive, dotSize = 7.dp)
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                    )
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = if (isManual) "MANUAL" else "AUTO",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (item.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = "Pinned",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (item.isMuted) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsOff,
                            contentDescription = "Muted",
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    text = "${item.messageCount} msgs",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = PhoneFormatter.displayE164(item.e164),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = colorScheme.onSurface,
            )

            val syncMeta = if (isManual) {
                "${item.label} · Local manual thread (kept until deleted)"
            } else {
                val syncedText = item.lastSyncEpochMs?.let { "Synced ${RelativeTimeFormatter.formatRelative(it, nowMs)}" } ?: "Not synced yet"
                "${item.label} · $sourceLabel · $syncedText"
            }
            Text(
                text = syncMeta,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )

            if (item.notes.isNotBlank()) {
                Text(
                    text = item.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!item.lastError.isNullOrBlank()) {
                Text(
                    text = item.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.inactiveSignal,
                )
            }

            HorizontalDivider(color = colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier.testTag("pin_temp_${item.numberId}"),
                    ) {
                        Icon(
                            imageVector = if (item.isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (item.isPinned) "Unpin" else "Pin",
                            tint = if (item.isPinned) colorScheme.primary else colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier.testTag("mute_temp_${item.numberId}"),
                    ) {
                        Icon(
                            imageVector = if (item.isMuted) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsNone,
                            contentDescription = if (item.isMuted) "Unmute" else "Mute",
                            tint = if (item.isMuted) colorScheme.primary else colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isManual) {
                        IconButton(
                            onClick = onAddManualMsg,
                            modifier = Modifier.testTag("add_manual_msg_${item.numberId}"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddComment,
                                contentDescription = "Add manual message",
                                tint = colorScheme.primary,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onSyncNow,
                            modifier = Modifier.testTag("sync_temp_${item.numberId}"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Sync now",
                                tint = colorScheme.primary,
                            )
                        }
                        IconButton(
                            onClick = onInspect,
                            modifier = Modifier.testTag("inspect_temp_${item.numberId}"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Troubleshoot,
                                contentDescription = stringResource(R.string.cd_inspect_temp),
                                tint = colorScheme.primary,
                            )
                        }
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("edit_temp_${item.numberId}"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "Edit temporary number",
                            tint = colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_temp_${item.numberId}"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete temporary number",
                            tint = colorScheme.error,
                        )
                    }
                }

                TextButton(onClick = onOpenInbox) {
                    Text("Inbox →", color = colorScheme.primary)
                }
            }
        }
    }
}
