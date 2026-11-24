package com.example.ui.screens.numbers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.repository.NumberCardModel
import com.example.domain.PhoneFormatter
import com.example.domain.RelativeTimeFormatter
import com.example.ui.components.StatusSignalDot
import com.example.ui.components.circuitBoardAccent
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalRelayExtras

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumbersScreen(
    viewModel: NumbersViewModel,
    selectedInboxId: String?,
    onOpenInbox: (String) -> Unit,
    onNavigateConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current
    val pullState = rememberPullToRefreshState()
    var showConfirmDelete by remember { mutableStateOf(false) }

    if (showConfirmDelete && state.selectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            containerColor = colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = "Remove ${state.selectedIds.size} line${if (state.selectedIds.size == 1) "" else "s"}?",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = "Removing these lines also clears their cached local messages. You can restore the default live lines anytime.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDelete = false
                        viewModel.onRemoveSelected()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("confirm_remove_button"),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
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
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Hero PCB Telemetry Header
                item(key = "hero_banner") {
                    HeroTelemetryCard(
                        lineCount = state.allNumbers.size,
                        dayTotal = state.dayTotal,
                    )
                }

                // Search & Manage Bar
                item(key = "search_bar") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = viewModel::onSearchQueryChanged,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.search_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.onSearchQueryChanged("") },
                                        modifier = Modifier.testTag("clear_search_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = stringResource(R.string.cd_clear_search),
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = colorScheme.surfaceContainer,
                                unfocusedContainerColor = colorScheme.surfaceContainer,
                                focusedBorderColor = colorScheme.primary,
                                unfocusedBorderColor = colorScheme.outlineVariant,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("search_numbers_input"),
                        )

                        if (state.allNumbers.isNotEmpty()) {
                            OutlinedButton(
                                onClick = viewModel::onToggleManageMode,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(
                                    1.dp,
                                    if (state.isManaging) colorScheme.primary else colorScheme.outlineVariant,
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                                modifier = Modifier.testTag("toggle_manage_button"),
                            ) {
                                Text(
                                    text = if (state.isManaging) stringResource(R.string.action_done) else stringResource(R.string.action_select),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (state.isManaging) colorScheme.primary else colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                // Country Filter Chips
                if (state.countries.isNotEmpty()) {
                    item(key = "country_filters") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "country_ALL") {
                                FilterChip(
                                    selected = state.selectedCountry == "ALL",
                                    onClick = { viewModel.onCountrySelected("ALL") },
                                    label = { Text(stringResource(R.string.filter_all)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colorScheme.primary,
                                        selectedLabelColor = colorScheme.onPrimary,
                                        containerColor = colorScheme.surfaceContainer,
                                        labelColor = colorScheme.onSurfaceVariant,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = state.selectedCountry == "ALL",
                                        borderColor = colorScheme.outlineVariant,
                                        selectedBorderColor = colorScheme.primary,
                                    ),
                                    modifier = Modifier.testTag("filter_chip_ALL"),
                                )
                            }
                            items(state.countries, key = { "country_${it.code}" }) { country ->
                                val selected = state.selectedCountry == country.code
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.onCountrySelected(country.code) },
                                    label = { Text(country.name) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = colorScheme.primary,
                                        selectedLabelColor = colorScheme.onPrimary,
                                        containerColor = colorScheme.surfaceContainer,
                                        labelColor = colorScheme.onSurfaceVariant,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = colorScheme.outlineVariant,
                                        selectedBorderColor = colorScheme.primary,
                                    ),
                                    modifier = Modifier.testTag("filter_chip_${country.code}"),
                                )
                            }
                        }
                    }
                }

                // Batch Remove Contextual Bar
                item(key = "manage_bar") {
                    AnimatedVisibility(visible = state.isManaging && state.selectedIds.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = colorScheme.errorContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.45f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${state.selectedIds.size} selected",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colorScheme.onSurface,
                                )
                                Button(
                                    onClick = { showConfirmDelete = true },
                                    enabled = !state.isRemoving,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colorScheme.error,
                                        contentColor = colorScheme.onError,
                                    ),
                                    modifier = Modifier.testTag("remove_selected_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (state.isRemoving) "Removing..." else stringResource(R.string.action_remove_selected))
                                }
                            }
                        }
                    }
                }

                // Featured Line or Empty State
                val featured = state.featuredNumber
                if (featured != null) {
                    item(key = "featured_${featured.id}") {
                        NumberLineCard(
                            row = featured,
                            featured = true,
                            isManaging = state.isManaging,
                            isChecked = featured.id in state.selectedIds,
                            isHighlighted = featured.id == selectedInboxId,
                            nowMs = state.nowTickMs,
                            onToggleCheck = { viewModel.onToggleSelection(featured.id) },
                            onClick = {
                                if (state.isManaging) viewModel.onToggleSelection(featured.id)
                                else onOpenInbox(featured.id)
                            },
                        )
                    }
                } else {
                    item(key = "empty_state") {
                        EmptyPoolCard(
                            isPoolEmpty = state.allNumbers.isEmpty(),
                            onRestoreDefaults = viewModel::onRestoreDefaultLines,
                            onNavigateConnect = onNavigateConnect,
                        )
                    }
                }

                // Remaining Pool Lines
                items(state.remainingNumbers, key = { it.id }) { row ->
                    NumberLineCard(
                        row = row,
                        featured = false,
                        isManaging = state.isManaging,
                        isChecked = row.id in state.selectedIds,
                        isHighlighted = row.id == selectedInboxId,
                        nowMs = state.nowTickMs,
                        onToggleCheck = { viewModel.onToggleSelection(row.id) },
                        onClick = {
                            if (state.isManaging) viewModel.onToggleSelection(row.id)
                            else onOpenInbox(row.id)
                        },
                    )
                }

                // Error notice if any
                if (!state.errorMessage.isNullOrBlank()) {
                    item(key = "error_notice") {
                        Text(
                            text = state.errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.error,
                        )
                    }
                }

                // Public Noticeboard Disclaimer
                item(key = "public_warning") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 16.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = if (extras.isDarkTheme) colorScheme.primary.copy(alpha = 0.8f) else colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.notice_public_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroTelemetryCard(
    lineCount: Int,
    dayTotal: Int,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Subtle generated circuitry board artwork layered cleanly on the right
            if (extras.isDarkTheme) {
                Image(
                    painter = painterResource(id = R.drawable.img_circuit_hero_1795495141488),
                    contentDescription = stringResource(R.string.cd_circuit_banner),
                    contentScale = ContentScale.Crop,
                    alpha = if (extras.showCircuitOverlay) 0.34f else 0.14f,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                0.0f to colorScheme.surfaceContainer.copy(alpha = 0.96f),
                                0.58f to colorScheme.surfaceContainer.copy(alpha = 0.78f),
                                1.0f to colorScheme.surfaceContainer.copy(alpha = 0.35f),
                            )
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .circuitBoardAccent(enabled = true)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.hero_eyebrow),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.hero_headline),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.hero_subcopy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = colorScheme.outlineVariant)
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TelemetryStat(
                        label = stringResource(R.string.stat_lines),
                        value = lineCount.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryStat(
                        label = stringResource(R.string.stat_day_total),
                        value = dayTotal.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    TelemetryStat(
                        label = stringResource(R.string.stat_retention),
                        value = stringResource(R.string.stat_retention_value),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumberLineCard(
    row: NumberCardModel,
    featured: Boolean,
    isManaging: Boolean,
    isChecked: Boolean,
    isHighlighted: Boolean,
    nowMs: Long,
    onToggleCheck: () -> Unit,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extras = LocalRelayExtras.current
    val active = row.isActive(nowMs)

    val borderColor = when {
        isHighlighted -> colorScheme.primary
        featured || row.isPinned -> colorScheme.primary.copy(alpha = 0.48f)
        else -> colorScheme.outlineVariant
    }

    val containerColor = when {
        featured -> colorScheme.surfaceContainerHigh
        isHighlighted -> colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> colorScheme.surfaceContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("number_card_${row.id}"),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(if (featured || isHighlighted || row.isPinned) 1.25.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .circuitBoardAccent(enabled = featured && extras.showCircuitOverlay)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isManaging) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onToggleCheck() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colorScheme.primary,
                        checkmarkColor = colorScheme.onPrimary,
                    ),
                    modifier = Modifier.testTag("select_checkbox_${row.id}"),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusSignalDot(active = active || row.isManualTemp, dotSize = 7.dp)
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = row.countryCode,
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (row.isTemp) {
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = colorScheme.primaryContainer.copy(alpha = 0.42f),
                            border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier.testTag("temp_badge_${row.id}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Public,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = stringResource(
                                        if (row.isManualTemp) R.string.temp_badge_manual else R.string.temp_badge_public
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = if (active) stringResource(R.string.status_active) else stringResource(R.string.status_inactive),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) extras.liveSignal else extras.inactiveSignal,
                        )
                    }
                    if (row.isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = "Pinned",
                            tint = colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (row.isMuted) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsOff,
                            contentDescription = "Muted",
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (featured && !row.isPinned) {
                        Text(
                            text = "· FEATURED",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.primary,
                        )
                    }
                    if (row.unseenCount > 0 && !row.isMuted) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.primary,
                        ) {
                            Text(
                                text = "${row.unseenCount} new",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = PhoneFormatter.displayE164(row.e164),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (featured) 21.sp else 17.sp,
                    letterSpacing = (-0.2).sp,
                    color = colorScheme.onSurface,
                )

                Spacer(Modifier.height(3.dp))

                Text(
                    text = "${row.label} · ${row.carrier}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                val previewText = if (!row.preview.isNullOrBlank()) {
                    if (!row.senderName.isNullOrBlank()) "${row.senderName}: ${row.preview}"
                    else row.preview
                } else {
                    "No messages yet"
                }

                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.preview.isNullOrBlank()) colorScheme.onSurfaceVariant.copy(alpha = 0.65f) else colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (row.isTemp && !row.tempLastError.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = row.tempLastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = extras.inactiveSignal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.dayCount.toString(),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = "24h",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                if (row.lastAtEpochMs != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = RelativeTimeFormatter.formatRelative(row.lastAtEpochMs, nowMs),
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPoolCard(
    isPoolEmpty: Boolean,
    onRestoreDefaults: () -> Unit,
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.SimCard,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = if (isPoolEmpty) stringResource(R.string.empty_pool) else stringResource(R.string.empty_search),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            if (isPoolEmpty) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Button(
                        onClick = onRestoreDefaults,
                        modifier = Modifier.testTag("restore_defaults_button"),
                    ) {
                        Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_restore_defaults))
                    }
                    OutlinedButton(
                        onClick = onNavigateConnect,
                        modifier = Modifier.testTag("empty_connect_button"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add line")
                    }
                }
            }
        }
    }
}
