package com.example.ui.screens.connect

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.PhoneFormatter
import com.example.ui.components.circuitBoardAccent
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalRelayExtras

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: ConnectViewModel,
    onOpenInbox: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current
    var countryMenuExpanded by remember { mutableStateOf(false) }

    val selectedCountryName = PhoneFormatter.countryNameFor(state.countryCode) ?: "Australia"

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
            // Header
            item(key = "connect_header") {
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
                            text = stringResource(R.string.connect_eyebrow),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.connect_headline),
                            style = MaterialTheme.typography.headlineMedium,
                            color = colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.connect_subcopy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Add a Line Form
            item(key = "add_line_form") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.35f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.connect_add_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "${state.numberCount}/${state.poolCap} slots",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelMedium,
                                color = colorScheme.primary,
                            )
                        }

                        LinearProgressIndicator(
                            progress = { (state.numberCount.toFloat() / state.poolCap.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = colorScheme.primary,
                            trackColor = colorScheme.surfaceVariant,
                        )

                        val fieldColors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = colorScheme.surfaceContainerLow,
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outlineVariant,
                        )

                        OutlinedTextField(
                            value = state.e164,
                            onValueChange = viewModel::onE164Changed,
                            label = { Text(stringResource(R.string.connect_field_number)) },
                            placeholder = { Text("+61468001999") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_e164_input"),
                        )

                        OutlinedTextField(
                            value = state.label,
                            onValueChange = viewModel::onLabelChanged,
                            label = { Text(stringResource(R.string.connect_field_label)) },
                            placeholder = { Text("North line") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_label_input"),
                        )

                        ExposedDropdownMenuBox(
                            expanded = countryMenuExpanded,
                            onExpandedChange = { countryMenuExpanded = !countryMenuExpanded },
                        ) {
                            OutlinedTextField(
                                value = "$selectedCountryName (${state.countryCode})",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.connect_field_country)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = countryMenuExpanded)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors,
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                                    .testTag("connect_country_dropdown"),
                            )
                            ExposedDropdownMenu(
                                expanded = countryMenuExpanded,
                                onDismissRequest = { countryMenuExpanded = false },
                            ) {
                                PhoneFormatter.SUPPORTED_COUNTRIES.forEach { country ->
                                    DropdownMenuItem(
                                        text = {
                                            Text("${country.name} (${country.dialPrefix})")
                                        },
                                        onClick = {
                                            viewModel.onCountryChanged(country.code)
                                            countryMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = state.carrier,
                            onValueChange = viewModel::onCarrierChanged,
                            label = { Text(stringResource(R.string.connect_field_carrier)) },
                            placeholder = { Text("Telstra, Optus, Twilio") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("connect_carrier_input"),
                        )

                        if (!state.formError.isNullOrBlank()) {
                            Text(
                                text = state.formError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.error,
                            )
                        }

                        Button(
                            onClick = { viewModel.onSubmitLine(onAdded = onOpenInbox) },
                            enabled = !state.isSubmitting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("connect_add_button"),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.isSubmitting) "Adding..." else stringResource(R.string.connect_add_button))
                        }
                    }
                }
            }

            // 3-Step Onboarding Strip
            item(key = "steps_strip") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepCard(
                        step = "01",
                        title = stringResource(R.string.connect_step1_title),
                        body = stringResource(R.string.connect_step1_body),
                    )
                    StepCard(
                        step = "02",
                        title = stringResource(R.string.connect_step2_title),
                        body = stringResource(R.string.connect_step2_body),
                    )
                    StepCard(
                        step = "03",
                        title = stringResource(R.string.connect_step3_title),
                        body = stringResource(R.string.connect_step3_body),
                    )
                }
            }

            // Interactive Webhook Payload Tester
            item(key = "webhook_tester") {
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
                            text = stringResource(R.string.webhook_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.webhook_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WebhookTemplate.entries.forEach { template ->
                                val selected = state.webhookTemplate == template
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.onSelectWebhookTemplate(template) },
                                    label = { Text(template.label) },
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
                                    modifier = Modifier.testTag("webhook_template_${template.name.lowercase()}"),
                                )
                            }
                        }

                        OutlinedTextField(
                            value = state.webhookPayload,
                            onValueChange = viewModel::onWebhookPayloadChanged,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                            minLines = 3,
                            maxLines = 6,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = colorScheme.surfaceContainerLowest,
                                unfocusedContainerColor = colorScheme.surfaceContainerLowest,
                                focusedBorderColor = colorScheme.primary,
                                unfocusedBorderColor = colorScheme.outlineVariant,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("webhook_payload_input"),
                        )

                        if (!state.webhookError.isNullOrBlank()) {
                            Text(
                                text = state.webhookError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.error,
                            )
                        }

                        if (!state.webhookSuccessSummary.isNullOrBlank()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                                color = extras.liveSignal.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, extras.liveSignal.copy(alpha = 0.5f)),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = extras.liveSignal,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = state.webhookSuccessSummary!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colorScheme.onSurface,
                                        )
                                    }
                                    if (state.webhookSuccessNumberId != null) {
                                        TextButton(
                                            onClick = { onOpenInbox(state.webhookSuccessNumberId!!) },
                                            modifier = Modifier.testTag("webhook_open_inbox_button"),
                                        ) {
                                            Text("Open", color = colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = viewModel::onDispatchWebhookPayload,
                            enabled = !state.isTestingWebhook && state.webhookPayload.isNotBlank(),
                            border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("webhook_dispatch_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (state.isTestingWebhook) "Parsing..." else stringResource(R.string.webhook_dispatch_button),
                                color = colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            item(key = "connect_footer_spacer") {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StepCard(
    step: String,
    title: String,
    body: String,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colorScheme.primaryContainer,
                border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.4f)),
            ) {
                Text(
                    text = step,
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
