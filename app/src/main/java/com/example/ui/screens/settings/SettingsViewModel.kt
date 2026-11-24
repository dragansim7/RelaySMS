package com.example.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.CustomSourceEntity
import com.example.data.local.TempNumberDetailRow
import com.example.data.local.TempNumberEntity
import com.example.data.preferences.AppThemeMode
import com.example.data.preferences.TempSyncSettings
import com.example.data.remote.TempSyncDiagnosticReport
import com.example.data.repository.RelayRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val tempNumbers: List<TempNumberDetailRow> = emptyList(),
    val customSources: List<CustomSourceEntity> = emptyList(),
    val syncSettings: TempSyncSettings = TempSyncSettings(),
    val themeMode: AppThemeMode = AppThemeMode.CIRCUIT_AMBER,
    val isBusy: Boolean = false,
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val diagnosticReports: Map<String, TempSyncDiagnosticReport> = emptyMap(),
    val inspectingNumberId: String? = null,
    val isProbingInspector: Boolean = false,
    val nowTickMs: Long = System.currentTimeMillis(),
)

private data class SettingsActionState(
    val isBusy: Boolean = false,
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val inspectingNumberId: String? = null,
    val isProbingInspector: Boolean = false,
)

class SettingsViewModel(
    private val repository: RelayRepository,
) : ViewModel() {

    private val actionState = MutableStateFlow(SettingsActionState())
    private val clockTick = MutableStateFlow(System.currentTimeMillis())

    init {
        viewModelScope.launch {
            while (true) {
                delay(15_000L)
                clockTick.value = System.currentTimeMillis()
            }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.observeTempNumbers(),
        repository.observeCustomSources(),
        repository.tempSyncSettingsFlow,
        repository.themeModeFlow,
        combine(actionState, repository.diagnosticReports, clockTick) { a, reports, t -> Triple(a, reports, t) },
    ) { temps, sources, syncPrefs, theme, (action, reports, nowMs) ->
        SettingsUiState(
            tempNumbers = temps,
            customSources = sources,
            syncSettings = syncPrefs,
            themeMode = theme,
            isBusy = action.isBusy,
            statusBanner = action.statusBanner,
            errorBanner = action.errorBanner,
            diagnosticReports = reports,
            inspectingNumberId = action.inspectingNumberId,
            isProbingInspector = action.isProbingInspector,
            nowTickMs = nowMs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(),
    )

    fun dismissBanners() {
        actionState.update { it.copy(statusBanner = null, errorBanner = null) }
    }

    fun acknowledgePublicWarning() {
        viewModelScope.launch {
            repository.acknowledgePublicWarning()
        }
    }

    fun addTempNumber(
        rawNumber: String,
        label: String,
        notes: String,
        mode: String,
        sourceType: String,
        customSourceId: Long?,
        initialManualSender: String,
        initialManualBody: String,
        initialManualTimestampMs: Long,
        onComplete: (String) -> Unit,
    ) {
        viewModelScope.launch {
            actionState.update { it.copy(isBusy = true, statusBanner = null, errorBanner = null) }
            val result = repository.addTempNumber(
                rawNumber = rawNumber,
                labelInput = label,
                notesInput = notes,
                mode = mode,
                sourceType = sourceType,
                customSourceId = customSourceId,
                initialManualSender = initialManualSender,
                initialManualBody = initialManualBody,
                initialManualTimestampMs = initialManualTimestampMs,
            )
            result.onSuccess { entity ->
                clockTick.value = System.currentTimeMillis()
                val msg = if (entity.mode == TempNumberEntity.MODE_AUTO) {
                    if (entity.status == TempNumberEntity.STATUS_ONLINE) {
                        "Added ${entity.e164} & synced inbox"
                    } else {
                        "Added ${entity.e164} (${entity.lastError ?: "queued for next sync"})"
                    }
                } else {
                    "Added manual thread ${entity.e164}"
                }
                actionState.update { it.copy(isBusy = false, statusBanner = msg) }
                onComplete(entity.numberId)
            }.onFailure { err ->
                actionState.update {
                    it.copy(
                        isBusy = false,
                        errorBanner = err.message ?: "Could not add temporary number",
                    )
                }
            }
        }
    }

    fun updateTempNumber(
        numberId: String,
        label: String,
        notes: String,
        mode: String,
        sourceType: String,
        customSourceId: Long?,
    ) {
        viewModelScope.launch {
            actionState.update { it.copy(isBusy = true, statusBanner = null, errorBanner = null) }
            val result = repository.updateTempNumber(
                numberId = numberId,
                labelInput = label,
                notesInput = notes,
                mode = mode,
                sourceType = sourceType,
                customSourceId = customSourceId,
            )
            result.onSuccess {
                clockTick.value = System.currentTimeMillis()
                actionState.update { it.copy(isBusy = false, statusBanner = "Temporary number updated") }
            }.onFailure { err ->
                actionState.update {
                    it.copy(isBusy = false, errorBanner = err.message ?: "Update failed")
                }
            }
        }
    }

    fun syncSingleTempNumber(numberId: String) {
        viewModelScope.launch {
            actionState.update { it.copy(isBusy = true, statusBanner = null, errorBanner = null) }
            val result = repository.syncTempNumber(numberId, force = true)
            clockTick.value = System.currentTimeMillis()
            result.onSuccess { added ->
                actionState.update {
                    it.copy(
                        isBusy = false,
                        statusBanner = if (added > 0) "Synced $added new message${if (added == 1) "" else "s"}" else "Inbox is up to date",
                    )
                }
            }.onFailure { err ->
                actionState.update {
                    it.copy(isBusy = false, errorBanner = err.message ?: "Sync failed")
                }
            }
        }
    }

    fun inspectTempNumber(numberId: String) {
        viewModelScope.launch {
            actionState.update {
                it.copy(
                    inspectingNumberId = numberId,
                    isProbingInspector = true,
                )
            }
            repository.inspectTempNumber(numberId)
            clockTick.value = System.currentTimeMillis()
            actionState.update { it.copy(isProbingInspector = false) }
        }
    }

    fun switchTempSourceFromInspector(numberId: String, sourceType: String, customSourceId: Long?) {
        viewModelScope.launch {
            actionState.update { it.copy(isProbingInspector = true) }
            repository.switchTempSource(numberId, sourceType, customSourceId)
            clockTick.value = System.currentTimeMillis()
            actionState.update { it.copy(isProbingInspector = false) }
        }
    }

    fun dismissInspector() {
        actionState.update { it.copy(inspectingNumberId = null, isProbingInspector = false) }
    }

    fun syncAllTempNumbers() {
        viewModelScope.launch {
            actionState.update { it.copy(isBusy = true, statusBanner = null, errorBanner = null) }
            val added = repository.syncAllAutoTempNumbers(force = true)
            clockTick.value = System.currentTimeMillis()
            actionState.update {
                it.copy(
                    isBusy = false,
                    statusBanner = if (added > 0) "Synced $added new message${if (added == 1) "" else "s"} across temp lines" else "All temp numbers checked",
                )
            }
        }
    }

    fun togglePin(numberId: String) {
        viewModelScope.launch {
            repository.togglePinTempNumber(numberId)
        }
    }

    fun toggleMute(numberId: String) {
        viewModelScope.launch {
            repository.toggleMuteTempNumber(numberId)
        }
    }

    fun deleteTempNumber(numberId: String) {
        viewModelScope.launch {
            repository.deleteTempNumber(numberId)
            actionState.update { it.copy(statusBanner = "Deleted temporary number and history") }
        }
    }

    fun addManualMessage(numberId: String, sender: String, body: String, timestampMs: Long) {
        viewModelScope.launch {
            val result = repository.addManualMessage(numberId, sender, body, timestampMs)
            result.onSuccess {
                actionState.update { it.copy(statusBanner = "Manual message added", errorBanner = null) }
            }.onFailure { err ->
                actionState.update { it.copy(errorBanner = err.message ?: "Could not add message") }
            }
        }
    }

    fun updateSyncIntervals(foregroundSec: Int, backgroundMin: Int) {
        viewModelScope.launch {
            repository.updateTempSyncSchedule(foregroundSec, backgroundMin)
            val bgLabel = if (backgroundMin <= 0) "Off" else "${backgroundMin}m"
            actionState.update {
                it.copy(statusBanner = "Sync schedule set: ${foregroundSec}s active · $bgLabel background")
            }
        }
    }

    fun saveCustomSource(source: CustomSourceEntity, onSaved: () -> Unit) {
        viewModelScope.launch {
            val result = repository.saveCustomSource(source)
            result.onSuccess {
                actionState.update { it.copy(statusBanner = "Saved source \"${source.name}\"", errorBanner = null) }
                onSaved()
            }.onFailure { err ->
                actionState.update { it.copy(errorBanner = err.message ?: "Invalid custom source") }
            }
        }
    }

    fun deleteCustomSource(sourceId: Long) {
        viewModelScope.launch {
            repository.deleteCustomSource(sourceId)
            actionState.update { it.copy(statusBanner = "Removed custom source") }
        }
    }

    companion object {
        fun factory(repository: RelayRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(repository) as T
                }
            }
    }
}
