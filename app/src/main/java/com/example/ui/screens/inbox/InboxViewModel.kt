package com.example.ui.screens.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CustomSourceEntity
import com.example.data.remote.SyncHealthState
import com.example.data.remote.TempSyncDiagnosticReport
import com.example.data.repository.InboxModel
import com.example.data.repository.RelayRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TestSmsPreset(
    val senderName: String,
    val fromNumber: String,
    val body: String,
)

val TEST_SMS_PRESETS: List<TestSmsPreset> = listOf(
    TestSmsPreset(
        senderName = "Telegram",
        fromNumber = "+42777",
        body = "Telegram code: 184920",
    ),
    TestSmsPreset(
        senderName = "Google",
        fromNumber = "Google",
        body = "G-184223 is your Google verification code.",
    ),
    TestSmsPreset(
        senderName = "Discord",
        fromNumber = "+447781",
        body = "Your Discord verification code is 091284",
    ),
)

data class InboxUiState(
    val isLoading: Boolean = true,
    val inbox: InboxModel? = null,
    val selectedPresetIndex: Int = 0,
    val testBody: String = TEST_SMS_PRESETS.first().body,
    val isSendingTest: Boolean = false,
    val testError: String? = null,
    val copiedKey: String? = null,
    val syncHealth: SyncHealthState = SyncHealthState(),
    val diagnosticReport: TempSyncDiagnosticReport? = null,
    val customSources: List<CustomSourceEntity> = emptyList(),
    val showInspector: Boolean = false,
    val isProbingInspector: Boolean = false,
    val nowTickMs: Long = System.currentTimeMillis(),
)

private data class InboxLocalState(
    val selectedPresetIndex: Int = 0,
    val testBody: String = TEST_SMS_PRESETS.first().body,
    val isSendingTest: Boolean = false,
    val testError: String? = null,
    val copiedKey: String? = null,
    val showInspector: Boolean = false,
    val isProbingInspector: Boolean = false,
)

class InboxViewModel(
    private val numberId: String,
    private val repository: RelayRepository,
) : ViewModel() {

    private val localState = MutableStateFlow(InboxLocalState())
    private val clockTick = MutableStateFlow(System.currentTimeMillis())

    init {
        viewModelScope.launch {
            repository.markLineSeen(numberId)
            repository.syncForLine(numberId, force = false)
        }
        viewModelScope.launch {
            while (true) {
                delay(10_000L)
                repository.syncForLine(numberId, force = false)
                repository.markLineSeen(numberId)
                clockTick.value = System.currentTimeMillis()
            }
        }
    }

    val uiState: StateFlow<InboxUiState> = combine(
        repository.observeInbox(numberId),
        localState,
        repository.syncHealth,
        combine(repository.diagnosticReports, repository.observeCustomSources(), clockTick) { reports, sources, nowMs ->
            Triple(reports[numberId], sources, nowMs)
        },
    ) { inbox, local, health, (report, customSources, nowMs) ->
        InboxUiState(
            isLoading = false,
            inbox = inbox,
            selectedPresetIndex = local.selectedPresetIndex,
            testBody = local.testBody,
            isSendingTest = local.isSendingTest,
            testError = local.testError,
            copiedKey = local.copiedKey,
            syncHealth = health,
            diagnosticReport = report,
            customSources = customSources,
            showInspector = local.showInspector,
            isProbingInspector = local.isProbingInspector,
            nowTickMs = nowMs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = InboxUiState(),
    )

    fun onSelectPreset(index: Int) {
        val preset = TEST_SMS_PRESETS.getOrNull(index) ?: return
        localState.update {
            it.copy(
                selectedPresetIndex = index,
                testBody = preset.body,
                testError = null,
            )
        }
    }

    fun onTestBodyChanged(value: String) {
        localState.update {
            it.copy(
                testBody = value.take(320),
                testError = null,
            )
        }
    }

    fun onSendTestSms() {
        val state = localState.value
        if (state.isSendingTest || state.testBody.isBlank()) return
        val preset = TEST_SMS_PRESETS.getOrNull(state.selectedPresetIndex) ?: TEST_SMS_PRESETS.first()

        viewModelScope.launch {
            localState.update { it.copy(isSendingTest = true, testError = null) }
            val result = repository.sendTestSms(
                numberId = numberId,
                fromNumber = preset.fromNumber,
                senderName = preset.senderName,
                body = state.testBody,
            )
            result.onSuccess {
                repository.markLineSeen(numberId)
                clockTick.value = System.currentTimeMillis()
                localState.update { it.copy(isSendingTest = false, testError = null) }
            }.onFailure { err ->
                localState.update {
                    it.copy(
                        isSendingTest = false,
                        testError = err.message ?: "Could not deliver test SMS",
                    )
                }
            }
        }
    }

    fun onMarkCopied(key: String) {
        localState.update { it.copy(copiedKey = key) }
        viewModelScope.launch {
            delay(1_600L)
            localState.update { state ->
                if (state.copiedKey == key) state.copy(copiedKey = null) else state
            }
        }
    }

    fun onRefreshManual() {
        viewModelScope.launch {
            repository.syncForLine(numberId, force = true, isManual = true)
            repository.markLineSeen(numberId)
            clockTick.value = System.currentTimeMillis()
        }
    }

    fun onOpenInspector() {
        viewModelScope.launch {
            localState.update { it.copy(showInspector = true, isProbingInspector = true) }
            repository.inspectLine(numberId)
            repository.markLineSeen(numberId)
            clockTick.value = System.currentTimeMillis()
            localState.update { it.copy(isProbingInspector = false) }
        }
    }

    fun onSwitchSourceFromInspector(sourceType: String, customSourceId: Long?) {
        viewModelScope.launch {
            localState.update { it.copy(isProbingInspector = true) }
            repository.switchTempSource(numberId, sourceType, customSourceId)
            repository.markLineSeen(numberId)
            clockTick.value = System.currentTimeMillis()
            localState.update { it.copy(isProbingInspector = false) }
        }
    }

    fun onDismissInspector() {
        localState.update { it.copy(showInspector = false, isProbingInspector = false) }
    }

    fun onTogglePin() {
        viewModelScope.launch {
            repository.togglePinTempNumber(numberId)
        }
    }

    fun onToggleMute() {
        viewModelScope.launch {
            repository.toggleMuteTempNumber(numberId)
        }
    }

    fun onAddManualMessage(sender: String, body: String, timestampMs: Long) {
        viewModelScope.launch {
            repository.addManualMessage(numberId, sender, body, timestampMs)
            repository.markLineSeen(numberId)
            clockTick.value = System.currentTimeMillis()
        }
    }

    fun onUpdateManualMessage(messageId: Long, sender: String, body: String, timestampMs: Long) {
        viewModelScope.launch {
            repository.updateManualMessage(messageId, sender, body, timestampMs)
            clockTick.value = System.currentTimeMillis()
        }
    }

    fun onDeleteManualMessage(messageId: Long) {
        viewModelScope.launch {
            repository.deleteManualMessage(messageId)
            clockTick.value = System.currentTimeMillis()
        }
    }

    companion object {
        fun factory(numberId: String, repository: RelayRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return InboxViewModel(numberId, repository) as T
                }
            }
    }
}
