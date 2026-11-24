package com.example.ui.screens.numbers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.remote.SyncHealthState
import com.example.data.repository.NumberCardModel
import com.example.data.repository.RelayRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CountryOption(
    val code: String,
    val name: String,
)

data class NumbersUiState(
    val allNumbers: List<NumberCardModel> = emptyList(),
    val filteredNumbers: List<NumberCardModel> = emptyList(),
    val featuredNumber: NumberCardModel? = null,
    val remainingNumbers: List<NumberCardModel> = emptyList(),
    val countries: List<CountryOption> = emptyList(),
    val dayTotal: Int = 0,
    val searchQuery: String = "",
    val selectedCountry: String = "ALL",
    val isManaging: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isRemoving: Boolean = false,
    val errorMessage: String? = null,
    val syncHealth: SyncHealthState = SyncHealthState(),
    val nowTickMs: Long = System.currentTimeMillis(),
)

private data class NumbersFilterState(
    val searchQuery: String = "",
    val selectedCountry: String = "ALL",
    val isManaging: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isRemoving: Boolean = false,
    val errorMessage: String? = null,
)

class NumbersViewModel(
    private val repository: RelayRepository,
) : ViewModel() {

    private val filterState = MutableStateFlow(NumbersFilterState())
    private val clockTick = MutableStateFlow(System.currentTimeMillis())

    init {
        viewModelScope.launch {
            while (true) {
                delay(15_000L)
                clockTick.value = System.currentTimeMillis()
            }
        }
    }

    val uiState: StateFlow<NumbersUiState> = combine(
        repository.observeNumberCards(),
        filterState,
        repository.syncHealth,
        clockTick,
    ) { rows, filter, health, nowMs ->
        val countryMap = linkedMapOf<String, String>()
        rows.forEach { countryMap[it.countryCode] = it.countryName }
        val countries = countryMap.entries.map { CountryOption(it.key, it.value) }

        val effectiveCountry = if (filter.selectedCountry == "ALL" || countryMap.containsKey(filter.selectedCountry)) {
            filter.selectedCountry
        } else {
            "ALL"
        }

        val needle = filter.searchQuery.trim().lowercase()
        val filtered = rows.filter { row ->
            if (effectiveCountry != "ALL" && row.countryCode != effectiveCountry) return@filter false
            if (needle.isEmpty()) return@filter true
            val hay = "${row.e164} ${row.label} ${row.carrier} ${row.countryName} ${row.senderName.orEmpty()} ${row.preview.orEmpty()}".lowercase()
            hay.contains(needle)
        }

        val featured = filtered.firstOrNull { it.isPinned }
            ?: filtered.firstOrNull { it.countryCode == "AU" && it.lastAtEpochMs != null }
            ?: filtered.firstOrNull()
        val rest = if (featured == null) emptyList() else filtered.filter { it.id != featured.id }
        val dayTotal = rows.sumOf { it.dayCount }

        NumbersUiState(
            allNumbers = rows,
            filteredNumbers = filtered,
            featuredNumber = featured,
            remainingNumbers = rest,
            countries = countries,
            dayTotal = dayTotal,
            searchQuery = filter.searchQuery,
            selectedCountry = effectiveCountry,
            isManaging = filter.isManaging && rows.isNotEmpty(),
            selectedIds = filter.selectedIds.intersect(rows.map { it.id }.toSet()),
            isRemoving = filter.isRemoving,
            errorMessage = filter.errorMessage,
            syncHealth = health,
            nowTickMs = nowMs,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = NumbersUiState(),
    )

    fun onSearchQueryChanged(query: String) {
        filterState.update { it.copy(searchQuery = query) }
    }

    fun onCountrySelected(code: String) {
        filterState.update { it.copy(selectedCountry = code) }
    }

    fun onToggleManageMode() {
        filterState.update {
            it.copy(
                isManaging = !it.isManaging,
                selectedIds = emptySet(),
                errorMessage = null,
            )
        }
    }

    fun onToggleSelection(id: String) {
        filterState.update { state ->
            val next = if (id in state.selectedIds) state.selectedIds - id else state.selectedIds + id
            state.copy(selectedIds = next)
        }
    }

    fun onRemoveSelected() {
        val toRemove = filterState.value.selectedIds
        if (toRemove.isEmpty() || filterState.value.isRemoving) return
        viewModelScope.launch {
            filterState.update { it.copy(isRemoving = true, errorMessage = null) }
            try {
                repository.removeNumbers(toRemove)
                filterState.update {
                    it.copy(
                        selectedIds = emptySet(),
                        isRemoving = false,
                        isManaging = false,
                    )
                }
            } catch (err: Throwable) {
                filterState.update {
                    it.copy(
                        isRemoving = false,
                        errorMessage = err.message ?: "Could not remove selected lines",
                    )
                }
            }
        }
    }

    fun onRefreshManual() {
        viewModelScope.launch {
            repository.syncAll(force = true, isManual = true)
            clockTick.value = System.currentTimeMillis()
        }
    }

    fun onRestoreDefaultLines() {
        viewModelScope.launch {
            repository.restoreDefaultLiveLines()
            clockTick.value = System.currentTimeMillis()
        }
    }

    companion object {
        fun factory(repository: RelayRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return NumbersViewModel(repository) as T
                }
            }
    }
}
