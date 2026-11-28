package com.example.data.repository

import com.example.data.local.CustomSourceEntity
import com.example.data.local.MessageEntity
import com.example.data.local.MessageSearchMatchRow
import com.example.data.local.NumberEntity
import com.example.data.local.NumberSummaryRow
import com.example.data.local.RelayDao
import com.example.data.local.TempNumberDetailRow
import com.example.data.local.TempNumberEntity
import com.example.data.preferences.AppThemeMode
import com.example.data.preferences.RelayPreferences
import com.example.data.preferences.TempSyncSettings
import com.example.data.remote.AnosimProvider
import com.example.data.remote.ParsedRemoteSms
import com.example.data.remote.ProbeOutcome
import com.example.data.remote.ProviderDiagnostic
import com.example.data.remote.ProviderHealthStatus
import com.example.data.remote.ReceiveSmssProvider
import com.example.data.remote.Sms24NoiseFilter
import com.example.data.remote.SourceProbeAttempt
import com.example.data.remote.SyncHealthState
import com.example.data.remote.TempFetchResult
import com.example.data.remote.TempParsedMessage
import com.example.data.remote.TempSourceEngine
import com.example.data.remote.TempSyncDiagnosticReport
import com.example.domain.OtpCodeExtractor
import com.example.domain.PhoneFormatter
import com.example.domain.RelativeTimeFormatter
import com.example.domain.WebhookPayloadParser
import com.example.worker.TempSyncScheduler
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

data class NumberCardModel(
    val id: String,
    val e164: String,
    val countryCode: String,
    val countryName: String,
    val label: String,
    val carrier: String,
    val providerType: String,
    val dayCount: Int,
    val lastAtEpochMs: Long?,
    val preview: String?,
    val senderName: String?,
    val unseenCount: Int,
    val isTemp: Boolean = false,
    val tempMode: String? = null,
    val tempSourceType: String? = null,
    val tempResolvedSource: String? = null,
    val tempCustomSourceId: Long? = null,
    val tempStatus: String? = null,
    val tempLastError: String? = null,
    val tempLastSyncEpochMs: Long? = null,
    val tempNotes: String? = null,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isProviderLive: Boolean = false,
    val isProviderDegraded: Boolean = false,
    val providerError: String? = null,
    val searchMatchCount: Int = 0,
    val searchMatchBody: String? = null,
    val searchMatchSender: String? = null,
    val searchMatchEpochMs: Long? = null,
) {
    val isHostedBuiltIn: Boolean
        get() = !isTemp && (providerType == NumberEntity.PROVIDER_ANOSIM || providerType == NumberEntity.PROVIDER_RECEIVESMSS)

    val canInspectLiveSync: Boolean
        get() = isAutoTemp || isHostedBuiltIn

    fun isActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        val recent24h = RelativeTimeFormatter.isLineActive(lastAtEpochMs, nowMs)
        if (isTemp && tempMode == TempNumberEntity.MODE_AUTO) {
            return tempStatus == TempNumberEntity.STATUS_ONLINE && recent24h
        }
        if (isHostedBuiltIn) {
            return recent24h && !isProviderDegraded
        }
        return recent24h
    }

    val isManualTemp: Boolean
        get() = isTemp && tempMode == TempNumberEntity.MODE_MANUAL

    val isAutoTemp: Boolean
        get() = isTemp && tempMode == TempNumberEntity.MODE_AUTO
}

data class SmsMessageModel(
    val id: Long,
    val numberId: String,
    val fromNumber: String,
    val senderName: String,
    val body: String,
    val source: String,
    val receivedAtEpochMs: Long,
    val extractedOtp: String?,
    val serviceName: String? = null,
    val isManual: Boolean = false,
    val kind: String = "sms",
    val audioUrl: String? = null,
    val localAudioPath: String? = null,
)

data class InboxModel(
    val number: NumberCardModel,
    val messages: List<SmsMessageModel>,
)

class RelayRepository(
    private val dao: RelayDao,
    private val preferences: RelayPreferences,
    private val anosimProvider: AnosimProvider,
    private val receiveSmssProvider: ReceiveSmssProvider,
    private val tempSourceEngine: TempSourceEngine,
    private val tempSyncScheduler: TempSyncScheduler,
    private val callAudioDownloader: com.example.data.remote.CallAudioDownloader? = null,
) {

    private val _syncHealth = MutableStateFlow(SyncHealthState())
    val syncHealth: StateFlow<SyncHealthState> = _syncHealth.asStateFlow()

    private val _diagnosticReports = MutableStateFlow<Map<String, TempSyncDiagnosticReport>>(emptyMap())
    val diagnosticReports: StateFlow<Map<String, TempSyncDiagnosticReport>> = _diagnosticReports.asStateFlow()

    val themeModeFlow: Flow<AppThemeMode> = preferences.themeModeFlow
    val tempSyncSettingsFlow: Flow<TempSyncSettings> = preferences.tempSyncSettingsFlow
    val activeNumberCountFlow: Flow<Int> = dao.observeActiveNumberCount()

    fun observeTempNumbers(): Flow<List<TempNumberDetailRow>> = dao.observeTempNumbers()
    fun observeCustomSources(): Flow<List<CustomSourceEntity>> = dao.observeCustomSources()

    suspend fun setThemeMode(mode: AppThemeMode) {
        preferences.setThemeMode(mode)
    }

    suspend fun acknowledgePublicWarning() {
        preferences.setPublicWarningAcknowledged()
    }

    suspend fun updateTempSyncSchedule(foregroundSec: Int, backgroundMin: Int) {
        preferences.setTempSyncIntervals(foregroundSec, backgroundMin)
        refreshWorkManagerSchedule()
    }

    suspend fun refreshWorkManagerSchedule() {
        val settings = preferences.tempSyncSettingsFlow.first()
        val hasAuto = dao.getAutoTempNumberCount() > 0
        tempSyncScheduler.syncSchedule(
            intervalMinutes = settings.backgroundIntervalMin,
            hasAutoTempNumbers = hasAuto,
        )
    }

    suspend fun ensureInitialized() {
        preferences.markInitialSeedDoneIfNeeded {
            dao.insertNumbersIgnore(NumberEntity.DEFAULT_LIVE_NUMBERS)
        }
        dao.purgePlaceholderMessages()
        pruneOldMessages()
        refreshWorkManagerSchedule()
    }

    suspend fun restoreDefaultLiveLines() {
        dao.insertNumbersIgnore(NumberEntity.DEFAULT_LIVE_NUMBERS)
        syncAll(force = true)
    }

    fun observeNumberCards(): Flow<List<NumberCardModel>> {
        val dayCutoff = System.currentTimeMillis() - RelativeTimeFormatter.DAY_WINDOW_MS
        return combine(
            dao.observeNumberSummaries(dayCutoff),
            dao.observeAllMessageTimestamps(),
            preferences.seenBaselinesFlow,
            syncHealth,
        ) { summaries, timestamps, baselines, health ->
            val byNumber = timestamps.groupBy { it.numberId }
            summaries.map { row ->
                val baseline = baselines.baselineFor(row.id)
                val unseen = if (baseline <= 0L || row.isMuted) {
                    0
                } else {
                    byNumber[row.id].orEmpty().count { it.receivedAtEpochMs > baseline }
                }
                row.toCardModel(unseenCount = unseen, health = health)
            }
        }
    }

    fun observeMessageSearchMatches(rawQuery: String): Flow<Map<String, MessageSearchMatchRow>> {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return flowOf(emptyMap())
        val escaped = trimmed
            .lowercase()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val pattern = "%$escaped%"
        return dao.observeMessageSearchMatches(pattern).map { rows ->
            rows.associateBy { it.numberId }
        }
    }

    fun observeInbox(numberId: String): Flow<InboxModel?> {
        val cleanId = PhoneFormatter.digitsOnly(numberId)
        val dayCutoff = System.currentTimeMillis() - RelativeTimeFormatter.DAY_WINDOW_MS
        return combine(
            dao.observeNumberSummary(cleanId, dayCutoff),
            dao.observeMessagesForNumber(cleanId),
            syncHealth,
        ) { summary, messages, health ->
            if (summary == null) return@combine null
            InboxModel(
                number = summary.toCardModel(unseenCount = 0, health = health),
                messages = messages.map { msg ->
                    SmsMessageModel(
                        id = msg.id,
                        numberId = msg.numberId,
                        fromNumber = msg.fromNumber,
                        senderName = msg.senderName,
                        body = msg.body,
                        source = msg.source,
                        receivedAtEpochMs = msg.receivedAtEpochMs,
                        extractedOtp = if (msg.kind == "call") null else {
                            msg.explicitCode?.ifBlank { null } ?: OtpCodeExtractor.extract(msg.body)
                        },
                        serviceName = msg.serviceName,
                        isManual = msg.isManual,
                        kind = msg.kind,
                        audioUrl = msg.audioUrl,
                        localAudioPath = msg.localAudioPath,
                    )
                }
            )
        }
    }

    suspend fun markLineSeen(numberId: String) {
        preferences.markLineSeen(PhoneFormatter.digitsOnly(numberId))
    }

    suspend fun recordAppBackgrounded() {
        preferences.recordAppBackgrounded()
    }

    suspend fun pruneOldMessages() {
        val now = System.currentTimeMillis()
        val cutoff = now - RelativeTimeFormatter.RETENTION_WINDOW_MS
        val tempCutoff = now - RelativeTimeFormatter.TEMP_RETENTION_WINDOW_MS
        dao.pruneMessagesOlderThan(cutoffEpochMs = cutoff, tempCutoffEpochMs = tempCutoff)
    }

    suspend fun syncAll(
        force: Boolean = false,
        isManual: Boolean = false,
        includeTempNumbers: Boolean = isManual || force,
    ) {
        if (isManual) {
            _syncHealth.update { it.copy(isManualRefreshing = true) }
        }
        try {
            pruneOldMessages()
            coroutineScope {
                val anosimJob = async { syncAnosimInternal(force = force) }
                val rsJob = async { syncReceiveSmssInternal(onlyNumberId = null, force = force) }
                val tempJob = if (includeTempNumbers) {
                    async { syncAllAutoTempNumbers(force = force) }
                } else {
                    null
                }
                anosimJob.await()
                rsJob.await()
                tempJob?.await()
            }
        } finally {
            if (isManual) {
                _syncHealth.update { it.copy(isManualRefreshing = false) }
            }
        }
    }

    suspend fun syncForLine(numberId: String, force: Boolean = false, isManual: Boolean = false) {
        val cleanId = PhoneFormatter.digitsOnly(numberId)
        if (isManual) {
            _syncHealth.update { it.copy(isManualRefreshing = true) }
        }
        try {
            pruneOldMessages()
            val temp = dao.getTempNumberById(cleanId)
            when {
                temp != null && temp.mode == TempNumberEntity.MODE_AUTO -> syncTempNumber(cleanId, force = force)
                cleanId == AnosimProvider.NUMBER_ID -> syncAnosimInternal(force = force)
                cleanId in ReceiveSmssProvider.SUPPORTED_IDS -> syncReceiveSmssInternal(onlyNumberId = cleanId, force = force)
                else -> Unit
            }
        } finally {
            if (isManual) {
                _syncHealth.update { it.copy(isManualRefreshing = false) }
            }
        }
    }

    suspend fun syncAllAutoTempNumbers(force: Boolean = false): Int {
        val autoTemps = dao.getAutoTempNumbers()
        if (autoTemps.isEmpty()) return 0
        pruneOldMessages()
        return coroutineScope {
            autoTemps.map { temp ->
                async { syncTempNumber(temp.numberId, force = force).getOrDefault(0) }
            }.awaitAll().sum()
        }
    }

    suspend fun syncTempNumber(numberId: String, force: Boolean = false): Result<Int> {
        val cleanId = PhoneFormatter.digitsOnly(numberId)
        val temp = dao.getTempNumberById(cleanId)
            ?: return Result.failure(IllegalStateException("Temporary number not found"))
        if (temp.mode != TempNumberEntity.MODE_AUTO) {
            return Result.success(0)
        }
        // Avoid stomping live status/UI when another poll for this number is already running
        if (!force && tempSourceEngine.isSyncInProgress(temp.numberId)) {
            return Result.success(0)
        }

        val startedAt = System.currentTimeMillis()
        dao.purgePlaceholderMessages()
        dao.updateTempSyncState(
            numberId = temp.numberId,
            status = TempNumberEntity.STATUS_SYNCING,
            lastError = null,
            lastSyncEpochMs = null,
            resolvedSource = null,
        )

        val customSource = temp.customSourceId?.let { dao.getCustomSourceById(it) }
        return when (val result = tempSourceEngine.fetchForTempNumber(temp, customSource, force)) {
            TempFetchResult.SkippedInProgress -> {
                // Another sync holds the lock; leave DB state & diagnostics untouched for the owner
                Result.success(0)
            }
            is TempFetchResult.Success -> {
                val persist = persistTempMessages(
                    numberId = temp.numberId,
                    messages = result.messages,
                    keepAllHistory = temp.isPinned,
                    retentionWindowMs = RelativeTimeFormatter.TEMP_RETENTION_WINDOW_MS,
                )
                val finishedAt = System.currentTimeMillis()
                dao.updateTempSyncState(
                    numberId = temp.numberId,
                    status = TempNumberEntity.STATUS_ONLINE,
                    lastError = null,
                    lastSyncEpochMs = finishedAt,
                    resolvedSource = result.resolvedSource,
                )
                val enrichedAttempts = enrichAttemptsWithPersistStats(
                    attempts = result.attempts,
                    winningSource = result.resolvedSource,
                    inserted = persist.inserted,
                    dedupSkipped = persist.dedupSkipped,
                    droppedByRetention = persist.droppedByRetention,
                )
                val verdict = if (result.messages.isNotEmpty()) ProbeOutcome.SUCCESS_WITH_MESSAGES else ProbeOutcome.EMPTY_INBOX
                val summary = if (result.messages.isNotEmpty()) {
                    buildString {
                        append("Synced ${result.messages.size} messages via ${sourceDisplayName(result.resolvedSource, customSource?.name)} ")
                        append("(+${persist.inserted} new, ${persist.dedupSkipped} duplicate skipped")
                        if (persist.droppedByRetention > 0) {
                            append(", ${persist.droppedByRetention} older than 30d skipped")
                        }
                        append(").")
                    }
                } else {
                    "Connected to ${sourceDisplayName(result.resolvedSource, customSource?.name)} — inbox currently has 0 messages."
                }
                recordDiagnosticReport(
                    TempSyncDiagnosticReport(
                        numberId = temp.numberId,
                        e164 = temp.e164,
                        configuredSource = sourceDisplayName(temp.sourceType, customSource?.name),
                        resolvedSource = sourceDisplayName(result.resolvedSource, customSource?.name),
                        startedAtEpochMs = startedAt,
                        completedAtEpochMs = finishedAt,
                        overallVerdict = verdict,
                        summary = summary,
                        attempts = enrichedAttempts,
                    )
                )
                Result.success(persist.inserted)
            }
            is TempFetchResult.Failure -> {
                val finishedAt = System.currentTimeMillis()
                // Preserve existing cached messages while surfacing clear status/error
                dao.updateTempSyncState(
                    numberId = temp.numberId,
                    status = result.status,
                    lastError = result.errorMessage,
                    lastSyncEpochMs = null,
                    resolvedSource = null,
                )
                val verdict = when (result.status) {
                    TempNumberEntity.STATUS_BOT_BLOCKED -> ProbeOutcome.BOT_BLOCKED
                    TempNumberEntity.STATUS_NOT_FOUND -> ProbeOutcome.NOT_FOUND
                    TempNumberEntity.STATUS_RATE_LIMITED -> ProbeOutcome.RATE_LIMITED
                    TempNumberEntity.STATUS_OFFLINE -> ProbeOutcome.OFFLINE
                    else -> result.attempts.firstOrNull { it.outcome == ProbeOutcome.PARSE_MISMATCH }?.outcome
                        ?: ProbeOutcome.ERROR
                }
                recordDiagnosticReport(
                    TempSyncDiagnosticReport(
                        numberId = temp.numberId,
                        e164 = temp.e164,
                        configuredSource = sourceDisplayName(temp.sourceType, customSource?.name),
                        resolvedSource = temp.resolvedSource?.let { sourceDisplayName(it, customSource?.name) },
                        startedAtEpochMs = startedAt,
                        completedAtEpochMs = finishedAt,
                        overallVerdict = verdict,
                        summary = result.errorMessage,
                        attempts = result.attempts,
                    )
                )
                Result.failure(IllegalStateException(result.errorMessage))
            }
        }
    }

    suspend fun inspectTempNumber(numberId: String): TempSyncDiagnosticReport? {
        val cleanId = PhoneFormatter.digitsOnly(numberId)
        syncTempNumber(cleanId, force = true)
        return _diagnosticReports.value[cleanId]
    }

    /**
     * Runs an uncached live sync diagnostic for ANY hosted line:
     * - Temporary numbers (Auto mode)
     * - Built-in Anosim.net line (+61485841378)
     * - Built-in Receive-SMSS.live lines
     */
    suspend fun inspectLine(numberId: String): TempSyncDiagnosticReport? {
        val cleanId = PhoneFormatter.digitsOnly(numberId)
        if (dao.getTempNumberById(cleanId) != null) {
            return inspectTempNumber(cleanId)
        }
        val line = dao.getActiveNumberById(cleanId) ?: return null
        return when {
            line.providerType == NumberEntity.PROVIDER_ANOSIM || cleanId == AnosimProvider.NUMBER_ID -> {
                syncAnosimInternal(force = true)
                _diagnosticReports.value[cleanId]
            }
            line.providerType == NumberEntity.PROVIDER_RECEIVESMSS || cleanId in ReceiveSmssProvider.SUPPORTED_IDS -> {
                val startedAt = System.currentTimeMillis()
                when (val result = tempSourceEngine.receiveSmssMirrorAdapter.fetch(line.e164, force = true)) {
                    is TempFetchResult.Success -> {
                        val persist = persistTempMessages(cleanId, result.messages, keepAllHistory = false)
                        val finishedAt = System.currentTimeMillis()
                        val enriched = enrichAttemptsWithPersistStats(
                            attempts = result.attempts,
                            winningSource = result.resolvedSource,
                            inserted = persist.inserted,
                            dedupSkipped = persist.dedupSkipped,
                            droppedByRetention = persist.droppedByRetention,
                        )
                        val verdict = if (result.messages.isNotEmpty()) ProbeOutcome.SUCCESS_WITH_MESSAGES else ProbeOutcome.EMPTY_INBOX
                        val report = TempSyncDiagnosticReport(
                            numberId = cleanId,
                            e164 = line.e164,
                            configuredSource = "Receive-SMSS.live",
                            resolvedSource = "Receive-SMSS.live",
                            startedAtEpochMs = startedAt,
                            completedAtEpochMs = finishedAt,
                            overallVerdict = verdict,
                            summary = if (result.messages.isNotEmpty()) {
                                "Synced ${result.messages.size} messages from Receive-SMSS.live (+${persist.inserted} new, ${persist.dedupSkipped} duplicates skipped)."
                            } else {
                                "Connected to Receive-SMSS.live — inbox currently has 0 messages."
                            },
                            attempts = enriched,
                        )
                        recordDiagnosticReport(report)
                        report
                    }
                    is TempFetchResult.Failure -> {
                        val finishedAt = System.currentTimeMillis()
                        val verdict = when (result.status) {
                            TempNumberEntity.STATUS_BOT_BLOCKED -> ProbeOutcome.BOT_BLOCKED
                            TempNumberEntity.STATUS_NOT_FOUND -> ProbeOutcome.NOT_FOUND
                            TempNumberEntity.STATUS_RATE_LIMITED -> ProbeOutcome.RATE_LIMITED
                            TempNumberEntity.STATUS_OFFLINE -> ProbeOutcome.OFFLINE
                            else -> result.attempts.firstOrNull { it.outcome == ProbeOutcome.PARSE_MISMATCH }?.outcome
                                ?: ProbeOutcome.ERROR
                        }
                        val report = TempSyncDiagnosticReport(
                            numberId = cleanId,
                            e164 = line.e164,
                            configuredSource = "Receive-SMSS.live",
                            resolvedSource = null,
                            startedAtEpochMs = startedAt,
                            completedAtEpochMs = finishedAt,
                            overallVerdict = verdict,
                            summary = result.errorMessage,
                            attempts = result.attempts,
                        )
                        recordDiagnosticReport(report)
                        report
                    }
                    TempFetchResult.SkippedInProgress -> _diagnosticReports.value[cleanId]
                }
            }
            else -> null
        }
    }

    suspend fun switchTempSource(
        numberId: String,
        sourceType: String,
        customSourceId: Long? = null,
    ): TempSyncDiagnosticReport? {
        val cleanId = PhoneFormatter.digitsOnly(numberId)
        val existing = dao.getTempNumberById(cleanId) ?: return null
        updateTempNumber(
            numberId = cleanId,
            labelInput = existing.label,
            notesInput = existing.notes,
            mode = TempNumberEntity.MODE_AUTO,
            sourceType = sourceType,
            customSourceId = customSourceId,
        )
        return _diagnosticReports.value[cleanId] ?: inspectTempNumber(cleanId)
    }

    private fun recordDiagnosticReport(report: TempSyncDiagnosticReport) {
        _diagnosticReports.update { current -> current + (report.numberId to report) }
    }

    private fun enrichAttemptsWithPersistStats(
        attempts: List<SourceProbeAttempt>,
        winningSource: String,
        inserted: Int,
        dedupSkipped: Int,
        droppedByRetention: Int = 0,
    ): List<SourceProbeAttempt> {
        val winIdx = attempts.indexOfLast { it.sourceKey == winningSource && it.parsedCount > 0 }
            .takeIf { it >= 0 }
            ?: attempts.indexOfLast { it.sourceKey == winningSource }
        if (winIdx < 0) return attempts
        return attempts.mapIndexed { idx, attempt ->
            if (idx == winIdx) {
                attempt.copy(
                    insertedCount = inserted,
                    dedupSkippedCount = dedupSkipped,
                    droppedByRetentionCount = droppedByRetention,
                )
            } else {
                attempt
            }
        }
    }

    suspend fun addTempNumber(
        rawNumber: String,
        labelInput: String,
        notesInput: String,
        mode: String,
        sourceType: String,
        customSourceId: Long?,
        initialManualSender: String = "",
        initialManualBody: String = "",
        initialManualTimestampMs: Long = System.currentTimeMillis(),
    ): Result<TempNumberEntity> {
        val e164 = PhoneFormatter.normalizeE164(rawNumber)
        if (!PhoneFormatter.isE164(e164)) {
            return Result.failure(IllegalArgumentException("Enter a valid international number (e.g. +61402380367)"))
        }
        val numberId = e164.drop(1)
        if (dao.getTotalNumberCount() >= POOL_CAP && dao.findNumberId(numberId) == null) {
            return Result.failure(IllegalStateException("The number pool is full ($POOL_CAP slots max)"))
        }
        if (dao.getTempNumberById(numberId) != null) {
            return Result.failure(IllegalStateException("This temporary number is already configured"))
        }

        val (countryCode, countryName) = PhoneFormatter.inferCountryFromE164(e164)
        val label = labelInput.trim().take(40).ifEmpty {
            if (mode == TempNumberEntity.MODE_MANUAL) "Manual ${e164.takeLast(4)}" else "Temp ${e164.takeLast(4)}"
        }
        val notes = notesInput.trim().take(240)
        val effectiveSourceType = if (mode == TempNumberEntity.MODE_MANUAL) {
            TempNumberEntity.SOURCE_MANUAL
        } else {
            sourceType
        }
        val carrierLabel = sourceDisplayName(effectiveSourceType, customSourceId?.let { dao.getCustomSourceById(it)?.name })

        if (dao.findNumberId(numberId) == null) {
            dao.insertNumber(
                NumberEntity(
                    id = numberId,
                    e164 = e164,
                    countryCode = countryCode,
                    countryName = countryName,
                    label = label,
                    carrier = carrierLabel,
                    providerType = "temp_$mode".lowercase(),
                    active = true,
                )
            )
        } else {
            dao.updateNumberMetadata(
                id = numberId,
                label = label,
                carrier = carrierLabel,
                providerType = "temp_$mode".lowercase(),
            )
        }

        val tempEntity = TempNumberEntity(
            numberId = numberId,
            e164 = e164,
            mode = mode,
            sourceType = effectiveSourceType,
            resolvedSource = if (mode == TempNumberEntity.MODE_MANUAL) TempNumberEntity.SOURCE_MANUAL else null,
            customSourceId = if (effectiveSourceType == TempNumberEntity.SOURCE_CUSTOM) customSourceId else null,
            label = label,
            notes = notes,
            status = if (mode == TempNumberEntity.MODE_MANUAL) TempNumberEntity.STATUS_MANUAL else TempNumberEntity.STATUS_SYNCING,
        )
        dao.upsertTempNumber(tempEntity)

        if (mode == TempNumberEntity.MODE_MANUAL && initialManualBody.isNotBlank()) {
            addManualMessage(
                numberId = numberId,
                sender = initialManualSender.ifBlank { "Manual" },
                body = initialManualBody,
                receivedAtEpochMs = initialManualTimestampMs,
            )
        } else if (mode == TempNumberEntity.MODE_AUTO) {
            // Immediately pull inbox & schedule WorkManager background sync
            syncTempNumber(numberId, force = true)
            refreshWorkManagerSchedule()
        }

        return Result.success(dao.getTempNumberById(numberId) ?: tempEntity)
    }

    suspend fun updateTempNumber(
        numberId: String,
        labelInput: String,
        notesInput: String,
        mode: String,
        sourceType: String,
        customSourceId: Long?,
    ): Result<Unit> {
        val cleanId = PhoneFormatter.digitsOnly(numberId)
        val existing = dao.getTempNumberById(cleanId)
            ?: return Result.failure(IllegalStateException("Temporary number not found"))

        val label = labelInput.trim().take(40).ifEmpty { existing.label }
        val notes = notesInput.trim().take(240)
        val effectiveSource = if (mode == TempNumberEntity.MODE_MANUAL) {
            TempNumberEntity.SOURCE_MANUAL
        } else {
            sourceType
        }
        val effectiveCustomId = if (effectiveSource == TempNumberEntity.SOURCE_CUSTOM) customSourceId else null
        val nextStatus = when {
            mode == TempNumberEntity.MODE_MANUAL -> TempNumberEntity.STATUS_MANUAL
            existing.mode == TempNumberEntity.MODE_MANUAL -> TempNumberEntity.STATUS_SYNCING
            else -> existing.status
        }
        val carrierLabel = sourceDisplayName(effectiveSource, effectiveCustomId?.let { dao.getCustomSourceById(it)?.name })

        dao.updateTempMetadata(
            numberId = cleanId,
            label = label,
            notes = notes,
            mode = mode,
            sourceType = effectiveSource,
            customSourceId = effectiveCustomId,
            status = nextStatus,
            lastError = if (mode == TempNumberEntity.MODE_MANUAL) null else existing.lastError,
        )
        dao.updateNumberMetadata(
            id = cleanId,
            label = label,
            carrier = carrierLabel,
            providerType = "temp_$mode".lowercase(),
        )

        if (mode == TempNumberEntity.MODE_AUTO && (existing.mode != TempNumberEntity.MODE_AUTO || existing.sourceType != effectiveSource || existing.customSourceId != effectiveCustomId)) {
            syncTempNumber(cleanId, force = true)
        }
        refreshWorkManagerSchedule()
        return Result.success(Unit)
    }

    suspend fun togglePinTempNumber(numberId: String) {
        val existing = dao.getTempNumberById(PhoneFormatter.digitsOnly(numberId)) ?: return
        dao.setTempPinned(existing.numberId, !existing.isPinned)
    }

    suspend fun toggleMuteTempNumber(numberId: String) {
        val existing = dao.getTempNumberById(PhoneFormatter.digitsOnly(numberId)) ?: return
        dao.setTempMuted(existing.numberId, !existing.isMuted)
    }

    suspend fun deleteTempNumber(numberId: String) {
        val cleanId = PhoneFormatter.digitsOnly(numberId)
        dao.deleteNumbersByIds(listOf(cleanId))
        preferences.removeSeenEntries(listOf(cleanId))
        refreshWorkManagerSchedule()
    }

    suspend fun addManualMessage(
        numberId: String,
        sender: String,
        body: String,
        receivedAtEpochMs: Long = System.currentTimeMillis(),
    ): Result<Long> {
        val cleanId = PhoneFormatter.digitsOnly(numberId)
        if (dao.getActiveNumberById(cleanId) == null) {
            return Result.failure(IllegalStateException("Number not found in pool"))
        }
        val cleanBody = PhoneFormatter.sanitizeBody(body, 640)
        if (cleanBody.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message text cannot be empty"))
        }
        val cleanSender = sender.trim().take(40).ifEmpty { "Manual" }
        val hash = PhoneFormatter.computeDedupHash(cleanId, cleanSender, cleanBody, receivedAtEpochMs)
        val insertedId = dao.insertMessage(
            MessageEntity(
                numberId = cleanId,
                fromNumber = cleanSender.take(32),
                senderName = cleanSender,
                body = cleanBody,
                source = "manual",
                receivedAtEpochMs = receivedAtEpochMs,
                dedupHash = hash,
                isManual = true,
            )
        )
        return Result.success(insertedId)
    }

    suspend fun updateManualMessage(
        messageId: Long,
        sender: String,
        body: String,
        receivedAtEpochMs: Long,
    ): Result<Unit> {
        val existing = dao.getMessageById(messageId)
            ?: return Result.failure(IllegalStateException("Message not found"))
        val cleanBody = PhoneFormatter.sanitizeBody(body, 640)
        if (cleanBody.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message text cannot be empty"))
        }
        val cleanSender = sender.trim().take(40).ifEmpty { "Manual" }
        val hash = PhoneFormatter.computeDedupHash(existing.numberId, cleanSender, cleanBody, receivedAtEpochMs)
        dao.updateMessage(
            existing.copy(
                fromNumber = cleanSender.take(32),
                senderName = cleanSender,
                body = cleanBody,
                receivedAtEpochMs = receivedAtEpochMs,
                dedupHash = hash,
            )
        )
        return Result.success(Unit)
    }

    suspend fun deleteManualMessage(messageId: Long) {
        dao.deleteMessageById(messageId)
    }

    suspend fun saveCustomSource(source: CustomSourceEntity): Result<Long> {
        val cleanName = source.name.trim().take(40)
        if (cleanName.length < 2) {
            return Result.failure(IllegalArgumentException("Give this source a name (2+ chars)"))
        }
        val cleanUrl = source.urlTemplate.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("URL template must start with https:// or http://"))
        }
        if (!cleanUrl.contains("{E164}") && !cleanUrl.contains("{NUMBER}") && !cleanUrl.contains("{number}")) {
            return Result.failure(IllegalArgumentException("URL template must include {E164} or {NUMBER} placeholder"))
        }
        if (source.parserType == CustomSourceEntity.PARSER_HTML_REGEX) {
            if (source.htmlRegexPattern.isBlank()) {
                return Result.failure(IllegalArgumentException("Enter an HTML regex pattern with capture groups"))
            }
            val regexCheck = runCatching { Regex(source.htmlRegexPattern) }
            if (regexCheck.isFailure) {
                return Result.failure(IllegalArgumentException("Invalid regex: ${regexCheck.exceptionOrNull()?.message}"))
            }
        }
        val id = dao.upsertCustomSource(
            source.copy(
                name = cleanName,
                urlTemplate = cleanUrl,
                cacheSeconds = source.cacheSeconds.coerceIn(5, 600),
            )
        )
        return Result.success(id)
    }

    suspend fun deleteCustomSource(sourceId: Long) {
        dao.detachCustomSourceFromTempNumbers(sourceId)
        dao.deleteCustomSource(sourceId)
    }

    private data class TempPersistStats(
        val inserted: Int,
        val dedupSkipped: Int,
        val droppedByRetention: Int = 0,
    )

    private suspend fun persistTempMessages(
        numberId: String,
        messages: List<TempParsedMessage>,
        keepAllHistory: Boolean,
        retentionWindowMs: Long = RelativeTimeFormatter.RETENTION_WINDOW_MS,
    ): TempPersistStats {
        var added = 0
        var dupSkipped = 0
        var droppedByRetention = 0
        val retentionCutoff = System.currentTimeMillis() - retentionWindowMs
        for (msg in messages) {
            if (msg.messageKind != "call" && Sms24NoiseFilter.isNonSmsPlaceholder(msg.body)) continue
            if (!keepAllHistory && msg.receivedAtEpochMs < retentionCutoff) {
                droppedByRetention += 1
                continue
            }
            val hash = msg.overrideDedupHash
                ?: PhoneFormatter.computeDedupHash(numberId, msg.senderName, msg.body, msg.receivedAtEpochMs)
            if (dao.countByDedupHash(numberId, hash) > 0) {
                dupSkipped += 1
                continue
            }
            // Only apply fuzzy window/content dedup when the provider did not supply an exact canonical dedupe hash
            if (msg.overrideDedupHash == null) {
                val windowDup = dao.countDuplicateMessage(
                    numberId = numberId,
                    senderName = msg.senderName,
                    body = msg.body,
                    fromEpochMs = msg.receivedAtEpochMs - DEDUP_WINDOW_MS,
                    toEpochMs = msg.receivedAtEpochMs + DEDUP_WINDOW_MS,
                )
                if (windowDup > 0) {
                    dupSkipped += 1
                    continue
                }
                if (!msg.hasExplicitTimestamp) {
                    val contentDup = dao.countDuplicateByContent(
                        numberId = numberId,
                        senderName = msg.senderName,
                        body = msg.body,
                        sinceEpochMs = retentionCutoff,
                    )
                    if (contentDup > 0) {
                        dupSkipped += 1
                        continue
                    }
                }
            }

            val localAudioPath = if (msg.messageKind == "call" && !msg.audioUrl.isNullOrBlank()) {
                callAudioDownloader?.downloadCallAudio(msg.audioUrl, hash)
            } else {
                null
            }

            dao.insertMessage(
                MessageEntity(
                    numberId = numberId,
                    fromNumber = msg.fromNumber,
                    senderName = msg.senderName,
                    body = msg.body,
                    source = msg.sourceTag,
                    receivedAtEpochMs = msg.receivedAtEpochMs,
                    dedupHash = hash,
                    explicitCode = msg.explicitCode,
                    serviceName = msg.serviceName,
                    isManual = false,
                    kind = msg.messageKind,
                    audioUrl = msg.audioUrl,
                    localAudioPath = localAudioPath,
                )
            )
            added += 1
        }
        return TempPersistStats(
            inserted = added,
            dedupSkipped = dupSkipped,
            droppedByRetention = droppedByRetention,
        )
    }

    private suspend fun syncAnosimInternal(force: Boolean) {
        val active = dao.getActiveNumberById(AnosimProvider.NUMBER_ID)
        if (active == null) {
            updateProvider(SyncHealthState.KEY_ANOSIM) {
                it.copy(status = ProviderHealthStatus.IDLE, lastError = null)
            }
            return
        }
        val started = System.currentTimeMillis()
        updateProvider(SyncHealthState.KEY_ANOSIM) {
            it.copy(status = ProviderHealthStatus.SYNCING, lastAttemptEpochMs = started)
        }
        when (val result = anosimProvider.fetchForNumber(active.e164, force = force)) {
            is TempFetchResult.Success -> {
                val persist = persistTempMessages(active.id, result.messages, keepAllHistory = false)
                val now = System.currentTimeMillis()
                val elapsed = (now - started).coerceAtLeast(1L)
                updateProvider(SyncHealthState.KEY_ANOSIM) {
                    it.copy(
                        status = ProviderHealthStatus.LIVE,
                        lastSuccessEpochMs = now,
                        lastLatencyMs = elapsed,
                        lastNewCount = persist.inserted,
                        lastError = null,
                    )
                }
                val enriched = enrichAttemptsWithPersistStats(
                    attempts = result.attempts,
                    winningSource = TempNumberEntity.SOURCE_ANOSIM,
                    inserted = persist.inserted,
                    dedupSkipped = persist.dedupSkipped,
                    droppedByRetention = persist.droppedByRetention,
                )
                val verdict = if (result.messages.isNotEmpty()) ProbeOutcome.SUCCESS_WITH_MESSAGES else ProbeOutcome.EMPTY_INBOX
                recordDiagnosticReport(
                    TempSyncDiagnosticReport(
                        numberId = active.id,
                        e164 = active.e164,
                        configuredSource = "Anosim.net",
                        resolvedSource = "Anosim.net",
                        startedAtEpochMs = started,
                        completedAtEpochMs = now,
                        overallVerdict = verdict,
                        summary = if (result.messages.isNotEmpty()) {
                            "Synced ${result.messages.size} messages from Anosim.net (+${persist.inserted} new, ${persist.dedupSkipped} duplicates skipped)."
                        } else {
                            "Connected to Anosim.net — inbox currently has 0 messages."
                        },
                        attempts = enriched,
                    )
                )
            }
            is TempFetchResult.Failure -> {
                val now = System.currentTimeMillis()
                val elapsed = (now - started).coerceAtLeast(1L)
                updateProvider(SyncHealthState.KEY_ANOSIM) {
                    it.copy(
                        status = ProviderHealthStatus.DEGRADED,
                        lastLatencyMs = elapsed,
                        lastError = result.errorMessage,
                    )
                }
                val verdict = when (result.status) {
                    TempNumberEntity.STATUS_BOT_BLOCKED -> ProbeOutcome.BOT_BLOCKED
                    TempNumberEntity.STATUS_NOT_FOUND -> ProbeOutcome.NOT_FOUND
                    TempNumberEntity.STATUS_RATE_LIMITED -> ProbeOutcome.RATE_LIMITED
                    TempNumberEntity.STATUS_OFFLINE -> ProbeOutcome.OFFLINE
                    else -> result.attempts.firstOrNull { it.outcome == ProbeOutcome.PARSE_MISMATCH }?.outcome
                        ?: ProbeOutcome.ERROR
                }
                recordDiagnosticReport(
                    TempSyncDiagnosticReport(
                        numberId = active.id,
                        e164 = active.e164,
                        configuredSource = "Anosim.net",
                        resolvedSource = null,
                        startedAtEpochMs = started,
                        completedAtEpochMs = now,
                        overallVerdict = verdict,
                        summary = result.errorMessage,
                        attempts = result.attempts,
                    )
                )
            }
            TempFetchResult.SkippedInProgress -> Unit
        }
    }

    private suspend fun syncReceiveSmssInternal(onlyNumberId: String?, force: Boolean) {
        val activeIds = ReceiveSmssProvider.SUPPORTED_IDS.filter { dao.getActiveNumberById(it) != null }.toSet()
        if (activeIds.isEmpty()) {
            updateProvider(SyncHealthState.KEY_RECEIVESMSS) {
                it.copy(status = ProviderHealthStatus.IDLE, lastError = null)
            }
            return
        }
        val started = System.currentTimeMillis()
        updateProvider(SyncHealthState.KEY_RECEIVESMSS) {
            it.copy(status = ProviderHealthStatus.SYNCING, lastAttemptEpochMs = started)
        }
        val result = receiveSmssProvider.fetchMessages(
            activeIds = activeIds,
            onlyNumberId = onlyNumberId,
            force = force,
        )
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        result.onSuccess { fetched ->
            val added = persistRemoteMessages(fetched)
            val now = System.currentTimeMillis()
            updateProvider(SyncHealthState.KEY_RECEIVESMSS) {
                it.copy(
                    status = ProviderHealthStatus.LIVE,
                    lastSuccessEpochMs = now,
                    lastLatencyMs = elapsed,
                    lastNewCount = added,
                    lastError = null,
                )
            }
        }.onFailure { err ->
            updateProvider(SyncHealthState.KEY_RECEIVESMSS) {
                it.copy(
                    status = ProviderHealthStatus.DEGRADED,
                    lastLatencyMs = elapsed,
                    lastError = err.message ?: "Network error",
                )
            }
        }
    }

    private suspend fun persistRemoteMessages(messages: List<ParsedRemoteSms>): Int {
        var added = 0
        val retentionCutoff = System.currentTimeMillis() - RelativeTimeFormatter.RETENTION_WINDOW_MS
        for (msg in messages) {
            if (msg.receivedAtEpochMs < retentionCutoff) continue
            val hash = PhoneFormatter.computeDedupHash(msg.numberId, msg.senderName, msg.body, msg.receivedAtEpochMs)
            if (dao.countByDedupHash(msg.numberId, hash) > 0) continue
            val dedupCount = dao.countDuplicateMessage(
                numberId = msg.numberId,
                senderName = msg.senderName,
                body = msg.body,
                fromEpochMs = msg.receivedAtEpochMs - DEDUP_WINDOW_MS,
                toEpochMs = msg.receivedAtEpochMs + DEDUP_WINDOW_MS,
            )
            if (dedupCount > 0) continue
            dao.insertMessage(
                MessageEntity(
                    numberId = msg.numberId,
                    fromNumber = msg.fromNumber,
                    senderName = msg.senderName,
                    body = msg.body,
                    source = msg.source,
                    receivedAtEpochMs = msg.receivedAtEpochMs,
                    dedupHash = hash,
                )
            )
            added += 1
        }
        return added
    }

    suspend fun sendTestSms(
        numberId: String,
        fromNumber: String,
        senderName: String,
        body: String,
    ): Result<SmsMessageModel> {
        val cleanId = PhoneFormatter.digitsOnly(numberId).take(16)
        val active = dao.getActiveNumberById(cleanId)
            ?: return Result.failure(IllegalStateException("That line is not in the pool"))

        val cleanBody = PhoneFormatter.sanitizeBody(body, maxLen = 320)
        if (cleanBody.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message is empty"))
        }

        val now = System.currentTimeMillis()
        val recentSim = dao.countRecentBySource(
            numberId = active.id,
            source = "sim",
            sinceEpochMs = now - SIM_COOLDOWN_MS,
        )
        if (recentSim > 0) {
            return Result.failure(IllegalStateException("Wait a few seconds before sending another test"))
        }

        val cleanFrom = fromNumber.trim().take(32).ifEmpty { "Unknown" }
        val cleanSender = senderName.trim().take(40)
        val hash = PhoneFormatter.computeDedupHash(active.id, cleanSender, cleanBody, now)
        val entity = MessageEntity(
            numberId = active.id,
            fromNumber = cleanFrom,
            senderName = cleanSender,
            body = cleanBody,
            source = "sim",
            receivedAtEpochMs = now,
            dedupHash = hash,
        )
        val insertedId = dao.insertMessage(entity)
        return Result.success(
            SmsMessageModel(
                id = insertedId,
                numberId = active.id,
                fromNumber = cleanFrom,
                senderName = cleanSender,
                body = cleanBody,
                source = "sim",
                receivedAtEpochMs = now,
                extractedOtp = OtpCodeExtractor.extract(cleanBody),
            )
        )
    }

    suspend fun acceptInboundWebhookPayload(rawPayload: String): Result<SmsMessageModel> {
        val parsed = WebhookPayloadParser.parse(rawPayload).getOrElse {
            return Result.failure(it)
        }
        val normalizedTo = PhoneFormatter.normalizeE164(parsed.to)
        if (!PhoneFormatter.isE164(normalizedTo)) {
            return Result.failure(IllegalArgumentException("Unknown destination number"))
        }
        val numberId = normalizedTo.drop(1)
        val active = dao.getActiveNumberById(numberId)
            ?: return Result.failure(IllegalStateException("Destination $normalizedTo is not in the pool"))

        val cleanBody = PhoneFormatter.sanitizeBody(parsed.body, maxLen = 640)
        if (cleanBody.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message body is empty"))
        }

        val now = System.currentTimeMillis()
        val burstCount = dao.countRecentAll(
            numberId = active.id,
            sinceEpochMs = now - BURST_WINDOW_MS,
        )
        if (burstCount >= MAX_BURST_PER_MIN) {
            return Result.failure(IllegalStateException("This line is receiving too fast (20/min cap)"))
        }

        val fromNormalized = PhoneFormatter.normalizeE164(parsed.from)
            .ifEmpty { parsed.from.trim().take(32) }
            .ifEmpty { "Unknown" }
        val guessedSender = PhoneFormatter.guessSender(parsed.from, cleanBody)
        val hash = PhoneFormatter.computeDedupHash(active.id, guessedSender.ifEmpty { fromNormalized }, cleanBody, now)
        val entity = MessageEntity(
            numberId = active.id,
            fromNumber = fromNormalized,
            senderName = guessedSender,
            body = cleanBody,
            source = "webhook",
            receivedAtEpochMs = now,
            dedupHash = hash,
        )
        val insertedId = dao.insertMessage(entity)
        return Result.success(
            SmsMessageModel(
                id = insertedId,
                numberId = active.id,
                fromNumber = fromNormalized,
                senderName = guessedSender,
                body = cleanBody,
                source = "webhook",
                receivedAtEpochMs = now,
                extractedOtp = OtpCodeExtractor.extract(cleanBody),
            )
        )
    }

    suspend fun registerNumber(
        e164Input: String,
        labelInput: String,
        countryCodeInput: String,
        carrierInput: String,
    ): Result<NumberEntity> {
        val e164 = PhoneFormatter.normalizeE164(e164Input)
        if (!PhoneFormatter.isE164(e164)) {
            return Result.failure(IllegalArgumentException("Use a full international number, like +61468001999"))
        }
        val code = countryCodeInput.uppercase()
        val countryName = PhoneFormatter.countryNameFor(code)
            ?: return Result.failure(IllegalArgumentException("Pick a supported country"))
        val label = labelInput.trim().take(40)
        if (label.length < 2) {
            return Result.failure(IllegalArgumentException("Give the line a short name (2+ characters)"))
        }
        val carrier = carrierInput.trim().take(40).ifEmpty { "Virtual" }
        val id = e164.drop(1)

        if (dao.getTotalNumberCount() >= POOL_CAP) {
            return Result.failure(IllegalStateException("The pool is full ($POOL_CAP slots max)"))
        }
        if (dao.findNumberId(id) != null) {
            return Result.failure(IllegalStateException("That number is already in the pool"))
        }

        val providerType = when {
            id == AnosimProvider.NUMBER_ID -> NumberEntity.PROVIDER_ANOSIM
            id in ReceiveSmssProvider.SUPPORTED_IDS -> NumberEntity.PROVIDER_RECEIVESMSS
            else -> NumberEntity.PROVIDER_CUSTOM
        }

        val entity = NumberEntity(
            id = id,
            e164 = e164,
            countryCode = code,
            countryName = countryName,
            label = label,
            carrier = carrier,
            providerType = providerType,
            active = true,
        )
        dao.insertNumber(entity)
        return Result.success(entity)
    }

    suspend fun removeNumbers(ids: Set<String>): Int {
        val clean = ids.map { PhoneFormatter.digitsOnly(it).take(16) }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return 0
        val deleted = dao.deleteNumbersByIds(clean)
        preferences.removeSeenEntries(clean)
        refreshWorkManagerSchedule()
        return deleted
    }

    private fun updateProvider(
        key: String,
        transform: (ProviderDiagnostic) -> ProviderDiagnostic,
    ) {
        _syncHealth.update { state ->
            val current = state.providers[key] ?: return@update state
            state.copy(providers = state.providers + (key to transform(current)))
        }
    }

    private fun NumberSummaryRow.toCardModel(
        unseenCount: Int,
        health: SyncHealthState,
    ): NumberCardModel {
        val providerDiag = when (providerType) {
            NumberEntity.PROVIDER_ANOSIM -> health.providers[SyncHealthState.KEY_ANOSIM]
            NumberEntity.PROVIDER_RECEIVESMSS -> health.providers[SyncHealthState.KEY_RECEIVESMSS]
            else -> null
        }
        return NumberCardModel(
            id = id,
            e164 = e164,
            countryCode = countryCode,
            countryName = countryName,
            label = label,
            carrier = carrier,
            providerType = providerType,
            dayCount = dayCount,
            lastAtEpochMs = lastAtEpochMs,
            preview = preview,
            senderName = senderName,
            unseenCount = unseenCount,
            isTemp = isTemp,
            tempMode = tempMode,
            tempSourceType = tempSourceType,
            tempResolvedSource = tempResolvedSource,
            tempCustomSourceId = tempCustomSourceId,
            tempStatus = tempStatus,
            tempLastError = tempLastError,
            tempLastSyncEpochMs = tempLastSyncEpochMs,
            tempNotes = tempNotes,
            isPinned = isPinned,
            isMuted = isMuted,
            isProviderLive = providerDiag?.status == ProviderHealthStatus.LIVE,
            isProviderDegraded = providerDiag?.status == ProviderHealthStatus.DEGRADED,
            providerError = providerDiag?.lastError,
        )
    }

    companion object {
        const val POOL_CAP = 40
        private const val DEDUP_WINDOW_MS = 2L * 60L * 1000L
        private const val SIM_COOLDOWN_MS = 3_000L
        private const val BURST_WINDOW_MS = 60_000L
        private const val MAX_BURST_PER_MIN = 20

        fun sourceDisplayName(sourceType: String?, customName: String? = null): String = when (sourceType) {
            TempNumberEntity.SOURCE_GET_VIRTUAL_PHONE_API -> "GetVirtualPhone API"
            TempNumberEntity.SOURCE_ANOSIM -> "Anosim.net"
            TempNumberEntity.SOURCE_SMS24_ME -> "SMS24.me"
            TempNumberEntity.SOURCE_RECEIVE_SMSS_MIRROR -> "Receive-SMSS.live"
            TempNumberEntity.SOURCE_QUACKR -> "Quackr.io"
            TempNumberEntity.SOURCE_TEMPSMSS -> "TempSMSS.com"
            TempNumberEntity.SOURCE_RECEIVE_SMS_FREE -> "Receive-SMS-Free.cc"
            TempNumberEntity.SOURCE_RECEIVE_SMS_ONLINE -> "Receive-SMS-Online.info"
            TempNumberEntity.SOURCE_PUBLIC_MIRROR -> "Public SMS Mirror"
            TempNumberEntity.SOURCE_AUTO_DETECT -> "Auto-Detect"
            TempNumberEntity.SOURCE_CUSTOM -> customName ?: "Custom Source"
            TempNumberEntity.SOURCE_MANUAL -> "Manual"
            else -> sourceType?.removePrefix("CUSTOM:") ?: "Public API"
        }
    }
}
