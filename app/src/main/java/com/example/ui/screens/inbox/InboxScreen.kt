package com.example.ui.screens.inbox

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.NumberEntity
import com.example.data.local.TempNumberEntity
import com.example.data.repository.NumberCardModel
import com.example.data.repository.RelayRepository
import com.example.data.repository.SmsMessageModel
import com.example.domain.OtpCodeExtractor
import com.example.domain.PhoneFormatter
import com.example.domain.RelativeTimeFormatter
import com.example.ui.components.LiveSyncInspectorSheet
import com.example.ui.components.OtpCodeChip
import com.example.ui.components.StatusSignalDot
import com.example.ui.components.circuitBoardAccent
import com.example.ui.screens.settings.ManualMessageDialog
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalRelayExtras

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    showBackButton: Boolean,
    isWideLayout: Boolean,
    onBack: () -> Unit,
    onNavigateConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colorScheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val pullState = rememberPullToRefreshState()

    var showAddManualDialog by remember { mutableStateOf(false) }
    var editingManualMessage by remember { mutableStateOf<SmsMessageModel?>(null) }

    fun copyToClipboard(text: String, key: String) {
        clipboard.setText(AnnotatedString(text))
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.onMarkCopied(key)
    }

    if (showAddManualDialog) {
        ManualMessageDialog(
            title = stringResource(R.string.manual_add_message),
            onSave = { sender, body, ts ->
                showAddManualDialog = false
                viewModel.onAddManualMessage(sender, body, ts)
            },
            onDismiss = { showAddManualDialog = false },
        )
    }

    if (editingManualMessage != null) {
        val target = editingManualMessage!!
        ManualMessageDialog(
            title = stringResource(R.string.manual_edit_message),
            initialSender = target.senderName,
            initialBody = target.body,
            initialTimestampMs = target.receivedAtEpochMs,
            onSave = { sender, body, ts ->
                editingManualMessage = null
                viewModel.onUpdateManualMessage(target.id, sender, body, ts)
            },
            onDismiss = { editingManualMessage = null },
        )
    }

    if (state.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colorScheme.primary)
        }
        return
    }

    val inbox = state.inbox
    if (inbox == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.inbox_not_found),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Button(onClick = onBack, modifier = Modifier.testTag("not_found_back_button")) {
                    Text(stringResource(R.string.inbox_back_numbers))
                }
            }
        }
        return
    }

    if (state.showInspector && inbox.number.canInspectLiveSync) {
        val inspectorSourceType = inbox.number.tempSourceType ?: when (inbox.number.providerType) {
            NumberEntity.PROVIDER_ANOSIM -> TempNumberEntity.SOURCE_ANOSIM
            else -> TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR
        }
        LiveSyncInspectorSheet(
            e164 = inbox.number.e164,
            currentSourceType = inspectorSourceType,
            currentCustomSourceId = inbox.number.tempCustomSourceId,
            customSources = state.customSources,
            report = state.diagnosticReport,
            isProbing = state.isProbingInspector,
            onRerunProbe = viewModel::onOpenInspector,
            onSwitchSource = viewModel::onSwitchSourceFromInspector,
            onDismiss = viewModel::onDismissInspector,
            allowSourceSwitching = inbox.number.isAutoTemp,
        )
    }

    PullToRefreshBox(
        isRefreshing = state.syncHealth.isManualRefreshing,
        onRefresh = viewModel::onRefreshManual,
        state = pullState,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = state.syncHealth.isManualRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = colorScheme.surfaceContainerHigh,
                color = colorScheme.primary,
            )
        },
    ) {
        if (isWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1.45f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "inbox_header") {
                        InboxHeaderCard(
                            number = inbox.number,
                            copiedKey = state.copiedKey,
                            nowMs = state.nowTickMs,
                            showBackButton = showBackButton,
                            onBack = onBack,
                            onCopyIntl = { copyToClipboard(inbox.number.e164, "intl") },
                            onCopyLocal = { local -> copyToClipboard(local.replace(" ", ""), "local") },
                            onTogglePin = viewModel::onTogglePin,
                            onToggleMute = viewModel::onToggleMute,
                            onAddManualMessage = { showAddManualDialog = true },
                            onInspectSync = viewModel::onOpenInspector,
                        )
                    }
                    val bannerError = when {
                        inbox.number.isTemp && !inbox.number.tempLastError.isNullOrBlank() -> inbox.number.tempLastError
                        inbox.number.isHostedBuiltIn && inbox.number.isProviderDegraded && !inbox.number.providerError.isNullOrBlank() -> inbox.number.providerError
                        else -> null
                    }
                    if (!bannerError.isNullOrBlank()) {
                        item(key = "temp_error_banner") {
                            TempSyncErrorBanner(
                                errorMessage = bannerError,
                                showInspect = inbox.number.canInspectLiveSync,
                                onInspect = viewModel::onOpenInspector,
                            )
                        }
                    }
                    item(key = "inbox_section_title") {
                        SectionHeading(
                            count = inbox.messages.size,
                            isManual = inbox.number.isManualTemp,
                        )
                    }
                    if (inbox.messages.isEmpty()) {
                        item(key = "inbox_empty") { EmptyInboxCard() }
                    } else {
                        items(inbox.messages, key = { it.id }) { msg ->
                            SmsMessageCard(
                                message = msg,
                                copiedKey = state.copiedKey,
                                nowMs = state.nowTickMs,
                                allowManualEdit = inbox.number.isManualTemp || msg.isManual,
                                onCopyOtp = { code ->
                                    copyToClipboard(OtpCodeExtractor.copyValue(code), "otp_${msg.id}")
                                },
                                onEdit = { editingManualMessage = msg },
                                onDelete = { viewModel.onDeleteManualMessage(msg.id) },
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item(key = "simulator_pane") {
                        TestSmsSimulatorCard(
                            selectedPresetIndex = state.selectedPresetIndex,
                            body = state.testBody,
                            isSending = state.isSendingTest,
                            errorMessage = state.testError,
                            onSelectPreset = viewModel::onSelectPreset,
                            onBodyChanged = viewModel::onTestBodyChanged,
                            onDeliver = viewModel::onSendTestSms,
                            onNavigateConnect = onNavigateConnect,
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "inbox_header") {
                        InboxHeaderCard(
                            number = inbox.number,
                            copiedKey = state.copiedKey,
                            nowMs = state.nowTickMs,
                            showBackButton = showBackButton,
                            onBack = onBack,
                            onCopyIntl = { copyToClipboard(inbox.number.e164, "intl") },
                            onCopyLocal = { local -> copyToClipboard(local.replace(" ", ""), "local") },
                            onTogglePin = viewModel::onTogglePin,
                            onToggleMute = viewModel::onToggleMute,
                            onAddManualMessage = { showAddManualDialog = true },
                            onInspectSync = viewModel::onOpenInspector,
                        )
                    }
                    val mobileBannerError = when {
                        inbox.number.isTemp && !inbox.number.tempLastError.isNullOrBlank() -> inbox.number.tempLastError
                        inbox.number.isHostedBuiltIn && inbox.number.isProviderDegraded && !inbox.number.providerError.isNullOrBlank() -> inbox.number.providerError
                        else -> null
                    }
                    if (!mobileBannerError.isNullOrBlank()) {
                        item(key = "temp_error_banner") {
                            TempSyncErrorBanner(
                                errorMessage = mobileBannerError,
                                showInspect = inbox.number.canInspectLiveSync,
                                onInspect = viewModel::onOpenInspector,
                            )
                        }
                    }
                    item(key = "inbox_section_title") {
                        SectionHeading(
                            count = inbox.messages.size,
                            isManual = inbox.number.isManualTemp,
                        )
                    }
                    if (inbox.messages.isEmpty()) {
                        item(key = "inbox_empty") { EmptyInboxCard() }
                    } else {
                        items(inbox.messages, key = { it.id }) { msg ->
                            SmsMessageCard(
                                message = msg,
                                copiedKey = state.copiedKey,
                                nowMs = state.nowTickMs,
                                allowManualEdit = inbox.number.isManualTemp || msg.isManual,
                                onCopyOtp = { code ->
                                    copyToClipboard(OtpCodeExtractor.copyValue(code), "otp_${msg.id}")
                                },
                                onEdit = { editingManualMessage = msg },
                                onDelete = { viewModel.onDeleteManualMessage(msg.id) },
                            )
                        }
                    }
                    item(key = "simulator_mobile") {
                        Spacer(Modifier.height(4.dp))
                        TestSmsSimulatorCard(
                            selectedPresetIndex = state.selectedPresetIndex,
                            body = state.testBody,
                            isSending = state.isSendingTest,
                            errorMessage = state.testError,
                            onSelectPreset = viewModel::onSelectPreset,
                            onBodyChanged = viewModel::onTestBodyChanged,
                            onDeliver = viewModel::onSendTestSms,
                            onNavigateConnect = onNavigateConnect,
                        )
                    }
                    item(key = "bottom_pad") {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TempSyncErrorBanner(
    errorMessage: String,
    showInspect: Boolean = false,
    onInspect: () -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.errorContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "$errorMessage Showing last known messages.",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (showInspect) {
                TextButton(
                    onClick = onInspect,
                    modifier = Modifier.testTag("inbox_error_inspect_button"),
                ) {
                    Text(stringResource(R.string.inspector_action_inspect))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxHeaderCard(
    number: NumberCardModel,
    copiedKey: String?,
    nowMs: Long,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onCopyIntl: () -> Unit,
    onCopyLocal: (String) -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onAddManualMessage: () -> Unit,
    onInspectSync: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current
    val active = number.isActive(nowMs)
    val localNum = PhoneFormatter.localFormat(number.e164)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .circuitBoardAccent()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBackButton) {
                    TextButton(
                        onClick = onBack,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("inbox_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.nav_numbers))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                if (number.isTemp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onTogglePin,
                            modifier = Modifier.testTag("inbox_pin_button"),
                        ) {
                            Icon(
                                imageVector = if (number.isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (number.isPinned) "Unpin number" else "Pin number",
                                tint = if (number.isPinned) colorScheme.primary else colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier.testTag("inbox_mute_button"),
                        ) {
                            Icon(
                                imageVector = if (number.isMuted) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsNone,
                                contentDescription = if (number.isMuted) "Unmute number" else "Mute number",
                                tint = if (number.isMuted) colorScheme.primary else colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (number.isTemp) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.45f)),
                    ) {
                        Text(
                            text = if (number.isManualTemp) stringResource(R.string.temp_badge_manual)
                            else stringResource(R.string.temp_badge_public),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = "${number.countryName} · ${number.label} · ${number.carrier}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusSignalDot(active = active, dotSize = 7.dp)
                    val statusText = when {
                        number.isManualTemp -> "Manual"
                        number.tempStatus == TempNumberEntity.STATUS_SYNCING -> "Syncing"
                        number.tempStatus == TempNumberEntity.STATUS_RATE_LIMITED -> "Rate limited"
                        number.tempStatus == TempNumberEntity.STATUS_BOT_BLOCKED -> "Bot blocked"
                        number.tempStatus == TempNumberEntity.STATUS_OFFLINE -> "Offline"
                        active -> stringResource(R.string.status_active)
                        else -> stringResource(R.string.status_inactive)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) extras.liveSignal else if (number.isManualTemp) colorScheme.onSurfaceVariant else extras.inactiveSignal,
                    )
                }
            }

            if (number.isAutoTemp && number.tempLastSyncEpochMs != null) {
                Spacer(Modifier.height(4.dp))
                val srcName = RelayRepository.sourceDisplayName(number.tempResolvedSource ?: number.tempSourceType)
                Text(
                    text = "Source: $srcName · Synced ${RelativeTimeFormatter.formatRelative(number.tempLastSyncEpochMs, nowMs)}",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            } else if (number.isManualTemp) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.manual_mode_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = PhoneFormatter.displayE164(number.e164),
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                letterSpacing = (-0.3).sp,
                color = colorScheme.onSurface,
            )

            Spacer(Modifier.height(14.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCopyIntl,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        if (copiedKey == "intl") extras.liveSignal else colorScheme.outline,
                    ),
                    modifier = Modifier.testTag("copy_intl_button"),
                ) {
                    Icon(
                        imageVector = if (copiedKey == "intl") Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = if (copiedKey == "intl") extras.liveSignal else colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (copiedKey == "intl") stringResource(R.string.action_copied) else stringResource(R.string.action_copy_number),
                        color = if (copiedKey == "intl") extras.liveSignal else colorScheme.onSurface,
                    )
                }

                if (localNum != null) {
                    OutlinedButton(
                        onClick = { onCopyLocal(localNum) },
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(
                            1.dp,
                            if (copiedKey == "local") extras.liveSignal else colorScheme.outline,
                        ),
                        modifier = Modifier.testTag("copy_local_button"),
                    ) {
                        Icon(
                            imageVector = if (copiedKey == "local") Icons.Rounded.Check else Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = if (copiedKey == "local") extras.liveSignal else colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (copiedKey == "local") stringResource(R.string.action_copied) else stringResource(R.string.action_copy_local),
                            color = if (copiedKey == "local") extras.liveSignal else colorScheme.onSurface,
                        )
                    }
                }

                if (number.canInspectLiveSync) {
                    OutlinedButton(
                        onClick = onInspectSync,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.55f)),
                        modifier = Modifier.testTag("inbox_inspect_sync_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Troubleshoot,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.inspector_action_inspect),
                            color = colorScheme.primary,
                        )
                    }
                }

                if (number.isManualTemp) {
                    Button(
                        onClick = onAddManualMessage,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.testTag("inbox_add_manual_msg_button"),
                    ) {
                        Icon(Icons.Rounded.AddComment, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.manual_add_message))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(count: Int, isManual: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.inbox_title).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (isManual) "$count manual message${if (count == 1) "" else "s"}"
            else "$count message${if (count == 1) "" else "s"} · live sync",
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SmsMessageCard(
    message: SmsMessageModel,
    copiedKey: String?,
    nowMs: Long,
    allowManualEdit: Boolean,
    onCopyOtp: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val sourceLabel = when {
        message.source == "sim" -> "test"
        message.source == "webhook" -> "webhook"
        message.source == "anosim" -> "live · anosim.net"
        message.source == "receivesmss" || message.source == "receive_smss_mirror" -> "live · receive-smss"
        message.source == "gvpn_api" -> "live · API"
        message.source == "sms24" -> "live · SMS24"
        message.source == "quackr" -> "live · quackr.io"
        message.source == "tempsmss" -> "live · tempsmss"
        message.source == "receive_sms_free" -> "live · receive-sms-free"
        message.source == "receive_sms_online" -> "live · receive-sms-online"
        message.source == "manual" -> "manual"
        message.source.startsWith("custom:") -> "live · custom"
        else -> message.source
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sms_message_card_${message.id}"),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (message.extractedOtp != null) colorScheme.primary.copy(alpha = 0.28f) else colorScheme.outlineVariant,
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
                    Text(
                        text = message.senderName.ifBlank { message.fromNumber },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                    )
                    if (!message.serviceName.isNullOrBlank() && message.serviceName != message.senderName) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = message.serviceName,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Text(
                    text = RelativeTimeFormatter.formatRelative(message.receivedAtEpochMs, nowMs),
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyLarge,
                color = colorScheme.onSurface,
            )

            if (message.extractedOtp != null) {
                OtpCodeChip(
                    code = message.extractedOtp,
                    copied = copiedKey == "otp_${message.id}",
                    onClick = { onCopyOtp(message.extractedOtp) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${message.fromNumber} · $sourceLabel",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                if (allowManualEdit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.testTag("edit_msg_${message.id}"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit message",
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag("delete_msg_${message.id}"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete message",
                                tint = colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        text = RelativeTimeFormatter.formatAbsoluteUtc(message.receivedAtEpochMs),
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyInboxCard() {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Sms,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = stringResource(R.string.empty_inbox),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestSmsSimulatorCard(
    selectedPresetIndex: Int,
    body: String,
    isSending: Boolean,
    errorMessage: String?,
    onSelectPreset: (Int) -> Unit,
    onBodyChanged: (String) -> Unit,
    onDeliver: () -> Unit,
    onNavigateConnect: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

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
            Text(
                text = stringResource(R.string.sim_title),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.sim_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TEST_SMS_PRESETS.forEachIndexed { index, preset ->
                    val selected = index == selectedPresetIndex
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPreset(index) },
                        label = { Text(preset.senderName) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primary,
                            selectedLabelColor = colorScheme.onPrimary,
                            containerColor = colorScheme.surfaceContainerHigh,
                            labelColor = colorScheme.onSurfaceVariant,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = colorScheme.outlineVariant,
                            selectedBorderColor = colorScheme.primary,
                        ),
                        modifier = Modifier.testTag("sim_preset_${preset.senderName.lowercase()}"),
                    )
                }
            }

            OutlinedTextField(
                value = body,
                onValueChange = onBodyChanged,
                label = { Text(stringResource(R.string.sim_message_label)) },
                supportingText = {
                    Text(
                        text = "${body.length}/320",
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = colorScheme.surfaceContainerLow,
                    focusedBorderColor = colorScheme.primary,
                    unfocusedBorderColor = colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sim_message_input"),
            )

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.error,
                )
            }

            Button(
                onClick = onDeliver,
                enabled = !isSending && body.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("sim_deliver_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isSending) "Sending..." else stringResource(R.string.sim_deliver_button))
            }

            TextButton(
                onClick = onNavigateConnect,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                modifier = Modifier.testTag("sim_connect_link"),
            ) {
                Text(
                    text = "Need a line you control? Connect a real number →",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.primary,
                )
            }
        }
    }
}
