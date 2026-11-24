package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.CustomSourceEntity
import com.example.data.local.TempNumberDetailRow
import com.example.data.local.TempNumberEntity
import com.example.data.repository.RelayRepository
import com.example.domain.PhoneFormatter
import com.example.domain.RelativeTimeFormatter
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalRelayExtras

@Composable
fun PublicInboxWarningDialog(
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surfaceContainerHigh,
        icon = {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        },
        title = {
            Text(
                text = stringResource(R.string.temp_public_warning_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.temp_public_warning_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onAcknowledge,
                modifier = Modifier.testTag("ack_public_warning_button"),
            ) {
                Text(stringResource(R.string.temp_public_warning_ack))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

data class TempNumberDraft(
    val rawNumber: String,
    val label: String,
    val notes: String,
    val mode: String,
    val sourceType: String,
    val customSourceId: Long?,
    val initialManualSender: String,
    val initialManualBody: String,
    val initialManualTimestampMs: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditTempNumberDialog(
    editing: TempNumberDetailRow?,
    customSources: List<CustomSourceEntity>,
    syncFgSeconds: Int,
    syncBgMinutes: Int,
    onConfirmSave: (TempNumberDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current

    var mode by remember { mutableStateOf(editing?.mode ?: TempNumberEntity.MODE_AUTO) }
    var rawNumber by remember { mutableStateOf(editing?.e164 ?: "") }
    var label by remember { mutableStateOf(editing?.label ?: "") }
    var notes by remember { mutableStateOf(editing?.notes ?: "") }
    var sourceType by remember {
        mutableStateOf(
            if (editing != null && editing.sourceType != TempNumberEntity.SOURCE_MANUAL) editing.sourceType
            else TempNumberEntity.SOURCE_AUTO_DETECT
        )
    }
    var selectedCustomId by remember { mutableStateOf(editing?.customSourceId) }
    var initialSender by remember { mutableStateOf("") }
    var initialBody by remember { mutableStateOf("") }
    var sourceExpanded by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var pendingConfirmDraft by remember { mutableStateOf<TempNumberDraft?>(null) }

    val normalizedPreview = remember(rawNumber) { PhoneFormatter.normalizeE164(rawNumber) }
    val isNumberValid = remember(normalizedPreview) { PhoneFormatter.isE164(normalizedPreview) }

    val selectedCustomName = customSources.firstOrNull { it.id == selectedCustomId }?.name
    val currentSourceLabel = when (sourceType) {
        TempNumberEntity.SOURCE_AUTO_DETECT -> "Auto-Detect (AU +61 & Global Chain)"
        TempNumberEntity.SOURCE_ANOSIM -> "Anosim.net (AU +61 & Global)"
        TempNumberEntity.SOURCE_QUACKR -> "Quackr.io (AU +61)"
        TempNumberEntity.SOURCE_SMS24_ME -> "SMS24.me HTML Scraper"
        TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR -> "Receive-SMSS.live (AU +61)"
        TempNumberEntity.SOURCE_TEMPSMSS -> "TempSMSS.com (AU +61)"
        TempNumberEntity.SOURCE_RECEIVE_SMS_FREE -> "Receive-SMS-Free.cc (AU +61)"
        TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE -> "Receive-SMS-Online.info (AU +61)"
        TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API -> "GetVirtualPhoneNumber JSON API"
        TempNumberEntity.SOURCE_PUBLIC_MIRROR -> "Public SMS Mirrors (Multi-Host)"
        TempNumberEntity.SOURCE_CUSTOM -> selectedCustomName?.let { "Custom: $it" } ?: "Pick a custom source"
        else -> "Auto-Detect (AU +61 & Global Chain)"
    }

    if (pendingConfirmDraft != null) {
        val draft = pendingConfirmDraft!!
        val confirmNormalized = PhoneFormatter.normalizeE164(draft.rawNumber)
        val bgText = if (syncBgMinutes <= 0) "Off" else "every ${syncBgMinutes}m"
        AlertDialog(
            onDismissRequest = { pendingConfirmDraft = null },
            containerColor = colorScheme.surfaceContainerHigh,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    tint = colorScheme.primary,
                )
            },
            title = {
                Text(
                    text = if (editing == null) "Confirm Temporary Number" else "Confirm Update",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = PhoneFormatter.displayE164(confirmNormalized),
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.primary,
                    )
                    Text(
                        text = if (draft.mode == TempNumberEntity.MODE_AUTO) {
                            "Mode: Auto Sync ($currentSourceLabel)\nWill immediately fetch inbox, sync every ${syncFgSeconds}s while active, and run WorkManager ($bgText) in background."
                        } else {
                            "Mode: Manual Entry Only\nWill never auto-sync unless you switch it to Auto mode later. Messages are retained until deleted."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingConfirmDraft = null
                        onConfirmSave(draft)
                    },
                    modifier = Modifier.testTag("confirm_add_temp_button"),
                ) {
                    Text(if (editing == null) "Add & Sync" else "Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmDraft = null }) {
                    Text("Back")
                }
            },
        )
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colorScheme.surfaceContainerLow,
        unfocusedContainerColor = colorScheme.surfaceContainerLow,
        focusedBorderColor = colorScheme.primary,
        unfocusedBorderColor = colorScheme.outlineVariant,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        title = {
            Text(
                text = if (editing == null) stringResource(R.string.temp_add_button) else "Edit Temporary Number",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Mode Toggle: Auto vs Manual
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = mode == TempNumberEntity.MODE_AUTO,
                        onClick = {
                            mode = TempNumberEntity.MODE_AUTO
                            validationError = null
                        },
                        label = { Text("Auto (Recommended)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primary,
                            selectedLabelColor = colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("temp_mode_auto_chip"),
                    )
                    FilterChip(
                        selected = mode == TempNumberEntity.MODE_MANUAL,
                        onClick = {
                            mode = TempNumberEntity.MODE_MANUAL
                            validationError = null
                        },
                        label = { Text("Manual") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primary,
                            selectedLabelColor = colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("temp_mode_manual_chip"),
                    )
                }

                // Phone Number Input with live E.164 normalization preview
                OutlinedTextField(
                    value = rawNumber,
                    onValueChange = {
                        rawNumber = it.take(26)
                        validationError = null
                    },
                    enabled = editing == null,
                    label = { Text("Phone number (E.164)") },
                    placeholder = { Text("+61 402 380 367") },
                    supportingText = {
                        if (rawNumber.isNotBlank()) {
                            Text(
                                text = if (isNumberValid) {
                                    "Normalized: ${PhoneFormatter.displayE164(normalizedPreview)}"
                                } else {
                                    "Include country code (e.g. +61402380367)"
                                },
                                fontFamily = JetBrainsMonoFamily,
                                color = if (isNumberValid) extras.liveSignal else colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("temp_number_input"),
                )

                // Source Picker (Auto Mode only)
                if (mode == TempNumberEntity.MODE_AUTO) {
                    ExposedDropdownMenuBox(
                        expanded = sourceExpanded,
                        onExpandedChange = { sourceExpanded = !sourceExpanded },
                    ) {
                        OutlinedTextField(
                            value = currentSourceLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Inbox source") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceExpanded)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors,
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                                .testTag("temp_source_dropdown"),
                        )
                        ExposedDropdownMenu(
                            expanded = sourceExpanded,
                            onDismissRequest = { sourceExpanded = false },
                        ) {
                            val builtInSources = listOf(
                                TempNumberEntity.SOURCE_AUTO_DETECT to "Auto-Detect (AU +61 & Global Chain)",
                                TempNumberEntity.SOURCE_ANOSIM to "Anosim.net (AU +61 & Global)",
                                TempNumberEntity.SOURCE_QUACKR to "Quackr.io (AU +61)",
                                TempNumberEntity.SOURCE_SMS24_ME to "SMS24.me HTML Scraper",
                                TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR to "Receive-SMSS.live (AU +61)",
                                TempNumberEntity.SOURCE_TEMPSMSS to "TempSMSS.com (AU +61)",
                                TempNumberEntity.SOURCE_RECEIVE_SMS_FREE to "Receive-SMS-Free.cc (AU +61)",
                                TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE to "Receive-SMS-Online.info (AU +61)",
                                TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API to "GetVirtualPhoneNumber JSON API",
                            )
                            builtInSources.forEach { (key, title) ->
                                DropdownMenuItem(
                                    text = { Text(title) },
                                    onClick = {
                                        sourceType = key
                                        selectedCustomId = null
                                        sourceExpanded = false
                                    },
                                )
                            }
                            customSources.forEach { custom ->
                                DropdownMenuItem(
                                    text = { Text("Custom: ${custom.name} (${custom.parserType})") },
                                    onClick = {
                                        sourceType = TempNumberEntity.SOURCE_CUSTOM
                                        selectedCustomId = custom.id
                                        sourceExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                // Display Label
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text("Display label (optional)") },
                    placeholder = { Text(if (mode == TempNumberEntity.MODE_AUTO) "Sydney QA line" else "Demo OTP thread") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("temp_label_input"),
                )

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(240) },
                    label = { Text("Notes (optional)") },
                    placeholder = { Text("Used for staging signups") },
                    maxLines = 2,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("temp_notes_input"),
                )

                // Optional First Message in Manual Add Mode
                if (editing == null && mode == TempNumberEntity.MODE_MANUAL) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Initial message (optional)",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.primary,
                            )
                            OutlinedTextField(
                                value = initialSender,
                                onValueChange = { initialSender = it.take(40) },
                                label = { Text("Sender") },
                                placeholder = { Text("Telegram, Google, +42777") },
                                singleLine = true,
                                colors = fieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("temp_manual_init_sender"),
                            )
                            OutlinedTextField(
                                value = initialBody,
                                onValueChange = { initialBody = it.take(640) },
                                label = { Text("Message text") },
                                placeholder = { Text("Your verification code is 492810") },
                                minLines = 2,
                                maxLines = 3,
                                colors = fieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("temp_manual_init_body"),
                            )
                        }
                    }
                }

                if (!validationError.isNullOrBlank()) {
                    Text(
                        text = validationError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isNumberValid) {
                        validationError = "Enter a valid E.164 phone number (e.g. +61402380367)"
                        return@Button
                    }
                    if (mode == TempNumberEntity.MODE_AUTO && sourceType == TempNumberEntity.SOURCE_CUSTOM && selectedCustomId == null) {
                        validationError = "Select a custom source or switch to Auto-Detect"
                        return@Button
                    }
                    pendingConfirmDraft = TempNumberDraft(
                        rawNumber = rawNumber,
                        label = label,
                        notes = notes,
                        mode = mode,
                        sourceType = sourceType,
                        customSourceId = selectedCustomId,
                        initialManualSender = initialSender,
                        initialManualBody = initialBody,
                        initialManualTimestampMs = System.currentTimeMillis(),
                    )
                },
                modifier = Modifier.testTag("temp_continue_button"),
            ) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun CustomSourceDialog(
    editing: CustomSourceEntity?,
    onSave: (CustomSourceEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var urlTemplate by remember {
        mutableStateOf(
            editing?.urlTemplate ?: "https://example.com/api/sms?number={E164}"
        )
    }
    var parserType by remember { mutableStateOf(editing?.parserType ?: CustomSourceEntity.PARSER_JSON) }
    var jsonArrayPath by remember { mutableStateOf(editing?.jsonArrayPath ?: "") }
    var jsonFromKey by remember { mutableStateOf(editing?.jsonFromKey ?: "from") }
    var jsonTextKey by remember { mutableStateOf(editing?.jsonTextKey ?: "text") }
    var jsonCodeKey by remember { mutableStateOf(editing?.jsonCodeKey ?: "code") }
    var jsonTimeKey by remember { mutableStateOf(editing?.jsonTimeKey ?: "received") }
    var jsonServiceKey by remember { mutableStateOf(editing?.jsonServiceKey ?: "service") }
    var htmlRegexPattern by remember {
        mutableStateOf(
            editing?.htmlRegexPattern ?: """<h6[^>]*>(.*?)</h6>.*?<p[^>]*>(.*?)</p>.*?<small[^>]*>(.*?)</small>"""
        )
    }
    var cacheSeconds by remember { mutableStateOf((editing?.cacheSeconds ?: 15).toString()) }
    var localError by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colorScheme.surfaceContainerLow,
        unfocusedContainerColor = colorScheme.surfaceContainerLow,
        focusedBorderColor = colorScheme.primary,
        unfocusedBorderColor = colorScheme.outlineVariant,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        title = {
            Text(
                text = if (editing == null) "Add Custom Source" else "Edit Custom Source",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40); localError = null },
                    label = { Text("Source name") },
                    placeholder = { Text("My SMS Gateway") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_source_name_input"),
                )

                OutlinedTextField(
                    value = urlTemplate,
                    onValueChange = { urlTemplate = it; localError = null },
                    label = { Text("URL template") },
                    supportingText = {
                        Text(
                            text = "Placeholders: {E164} (URL-encoded +61...) or {NUMBER} (digits only)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                    maxLines = 2,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_source_url_input"),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = parserType == CustomSourceEntity.PARSER_JSON,
                        onClick = { parserType = CustomSourceEntity.PARSER_JSON; localError = null },
                        label = { Text("JSON Mapping") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primary,
                            selectedLabelColor = colorScheme.onPrimary,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = parserType == CustomSourceEntity.PARSER_HTML_REGEX,
                        onClick = { parserType = CustomSourceEntity.PARSER_HTML_REGEX; localError = null },
                        label = { Text("HTML Regex") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primary,
                            selectedLabelColor = colorScheme.onPrimary,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (parserType == CustomSourceEntity.PARSER_JSON) {
                    OutlinedTextField(
                        value = jsonArrayPath,
                        onValueChange = { jsonArrayPath = it.take(60) },
                        label = { Text("JSON array path (blank if root array)") },
                        placeholder = { Text("e.g. data.messages") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = jsonFromKey,
                            onValueChange = { jsonFromKey = it.take(30) },
                            label = { Text("From key") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = jsonTextKey,
                            onValueChange = { jsonTextKey = it.take(30) },
                            label = { Text("Text key") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = jsonCodeKey,
                            onValueChange = { jsonCodeKey = it.take(30) },
                            label = { Text("Code key") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = jsonTimeKey,
                            onValueChange = { jsonTimeKey = it.take(30) },
                            label = { Text("Time key") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = jsonServiceKey,
                        onValueChange = { jsonServiceKey = it.take(30) },
                        label = { Text("Service key (optional)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = htmlRegexPattern,
                        onValueChange = { htmlRegexPattern = it; localError = null },
                        label = { Text("HTML Regex pattern") },
                        supportingText = {
                            Text(
                                text = "Capture groups: (1) sender, (2) body, (3) timestamp [opt], (4) code [opt]",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                        minLines = 2,
                        maxLines = 4,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = cacheSeconds,
                    onValueChange = { cacheSeconds = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("Cache / rate-limit cooldown (seconds)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!localError.isNullOrBlank()) {
                    Text(
                        text = localError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val entity = CustomSourceEntity(
                        id = editing?.id ?: 0L,
                        name = name.trim(),
                        urlTemplate = urlTemplate.trim(),
                        parserType = parserType,
                        jsonArrayPath = jsonArrayPath.trim(),
                        jsonFromKey = jsonFromKey.trim().ifEmpty { "from" },
                        jsonTextKey = jsonTextKey.trim().ifEmpty { "text" },
                        jsonCodeKey = jsonCodeKey.trim(),
                        jsonTimeKey = jsonTimeKey.trim(),
                        jsonServiceKey = jsonServiceKey.trim(),
                        htmlRegexPattern = htmlRegexPattern.trim(),
                        cacheSeconds = (cacheSeconds.toIntOrNull() ?: 15).coerceIn(5, 600),
                    )
                    if (entity.name.length < 2) {
                        localError = "Name must be at least 2 characters"
                        return@Button
                    }
                    if (!entity.urlTemplate.startsWith("http")) {
                        localError = "URL must start with https:// or http://"
                        return@Button
                    }
                    if (!entity.urlTemplate.contains("{E164}") && !entity.urlTemplate.contains("{NUMBER}") && !entity.urlTemplate.contains("{number}")) {
                        localError = "Include {E164} or {NUMBER} in the URL template"
                        return@Button
                    }
                    if (entity.parserType == CustomSourceEntity.PARSER_HTML_REGEX) {
                        if (entity.htmlRegexPattern.isBlank() || runCatching { Regex(entity.htmlRegexPattern) }.isFailure) {
                            localError = "Enter a valid regex pattern"
                            return@Button
                        }
                    }
                    onSave(entity)
                },
                modifier = Modifier.testTag("save_custom_source_button"),
            ) {
                Text("Save Source")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun ManualMessageDialog(
    title: String,
    initialSender: String = "",
    initialBody: String = "",
    initialTimestampMs: Long = System.currentTimeMillis(),
    onSave: (sender: String, body: String, timestampMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var sender by remember { mutableStateOf(initialSender) }
    var body by remember { mutableStateOf(initialBody) }
    var timestampMs by remember { mutableLongStateOf(initialTimestampMs) }
    var error by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colorScheme.surfaceContainerLow,
        unfocusedContainerColor = colorScheme.surfaceContainerLow,
        focusedBorderColor = colorScheme.primary,
        unfocusedBorderColor = colorScheme.outlineVariant,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colorScheme.surface,
        title = {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it.take(40); error = null },
                    label = { Text("Sender") },
                    placeholder = { Text("Google, Telegram, +447781") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_msg_sender_input"),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(640); error = null },
                    label = { Text("Message text") },
                    placeholder = { Text("Your verification code is 184920") },
                    minLines = 3,
                    maxLines = 5,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_msg_body_input"),
                )
                Text(
                    text = "Timestamp: ${RelativeTimeFormatter.formatAbsoluteUtc(timestampMs)}",
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val offsets = listOf("Now" to 0L, "-5m" to 5 * 60_000L, "-1h" to 3_600_000L, "-1d" to 86_400_000L)
                    offsets.forEach { (lbl, delta) ->
                        FilterChip(
                            selected = false,
                            onClick = { timestampMs = System.currentTimeMillis() - delta },
                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                if (!error.isNullOrBlank()) {
                    Text(text = error!!, style = MaterialTheme.typography.bodySmall, color = colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (body.isBlank()) {
                        error = "Message text is required"
                        return@Button
                    }
                    onSave(sender.trim().ifEmpty { "Manual" }, body.trim(), timestampMs)
                },
                modifier = Modifier.testTag("manual_msg_save_button"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
