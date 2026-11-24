package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.relayDataStore: DataStore<Preferences> by preferencesDataStore(name = "relay_prefs")

enum class AppThemeMode(
    val id: String,
    val title: String,
    val subtitle: String,
    val isDark: Boolean?,
) {
    CIRCUIT_AMBER(
        id = "circuit_amber",
        title = "Circuit Amber",
        subtitle = "Dark carbon PCB traces with precision amber signals",
        isDark = true,
    ),
    OBSIDIAN_MONO(
        id = "obsidian_mono",
        title = "Obsidian Mono",
        subtitle = "Minimalist obsidian dark with cool silver highlights",
        isDark = true,
    ),
    STEALTH_EMERALD(
        id = "stealth_emerald",
        title = "Stealth Emerald",
        subtitle = "Matte graphite circuitry with telecom emerald accents",
        isDark = true,
    ),
    ARCTIC_SLATE(
        id = "arctic_slate",
        title = "Arctic Slate",
        subtitle = "Crisp daylight engineering surface with deep amber",
        isDark = false,
    ),
    SYSTEM_DYNAMIC(
        id = "system_dynamic",
        title = "System Dynamic",
        subtitle = "Adapts to wallpaper Material You palette automatically",
        isDark = null,
    );

    companion object {
        fun fromId(id: String?): AppThemeMode =
            entries.firstOrNull { it.id == id } ?: CIRCUIT_AMBER
    }
}

data class TempSyncSettings(
    val foregroundIntervalSec: Int = 30,
    val backgroundIntervalMin: Int = 15, // 0 = Off
    val publicWarningAcknowledged: Boolean = false,
)

data class SeenBaselines(
    val lastAppVisitEpochMs: Long,
    val perLineSeenEpochMs: Map<String, Long>,
) {
    fun baselineFor(numberId: String): Long {
        val lineSeen = perLineSeenEpochMs[numberId] ?: 0L
        return maxOf(lastAppVisitEpochMs, lineSeen)
    }
}

class RelayPreferences(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val keyTheme = stringPreferencesKey("theme_mode")
    private val keyLastVisit = longPreferencesKey("last_visit_ms")
    private val keySeenMap = stringPreferencesKey("seen_map_json")
    private val keyInitialSeedDone = booleanPreferencesKey("initial_seed_done")
    private val keyTempFgIntervalSec = intPreferencesKey("temp_fg_interval_sec")
    private val keyTempBgIntervalMin = intPreferencesKey("temp_bg_interval_min")
    private val keyPublicWarningAck = booleanPreferencesKey("public_warning_ack")

    val themeModeFlow: Flow<AppThemeMode> = context.relayDataStore.data.map { prefs ->
        AppThemeMode.fromId(prefs[keyTheme])
    }

    val tempSyncSettingsFlow: Flow<TempSyncSettings> = context.relayDataStore.data.map { prefs ->
        TempSyncSettings(
            foregroundIntervalSec = (prefs[keyTempFgIntervalSec] ?: 30).coerceIn(15, 120),
            backgroundIntervalMin = prefs[keyTempBgIntervalMin] ?: 15,
            publicWarningAcknowledged = prefs[keyPublicWarningAck] ?: false,
        )
    }

    val seenBaselinesFlow: Flow<SeenBaselines> = context.relayDataStore.data.map { prefs ->
        val visit = prefs[keyLastVisit] ?: 0L
        val rawMap = prefs[keySeenMap].orEmpty()
        val parsed: Map<String, Long> = if (rawMap.isBlank()) {
            emptyMap()
        } else {
            runCatching { json.decodeFromString<Map<String, Long>>(rawMap) }.getOrDefault(emptyMap())
        }
        SeenBaselines(lastAppVisitEpochMs = visit, perLineSeenEpochMs = parsed)
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.relayDataStore.edit { prefs ->
            prefs[keyTheme] = mode.id
        }
    }

    suspend fun setTempSyncIntervals(foregroundSec: Int, backgroundMin: Int) {
        context.relayDataStore.edit { prefs ->
            prefs[keyTempFgIntervalSec] = foregroundSec.coerceIn(15, 120)
            prefs[keyTempBgIntervalMin] = if (backgroundMin <= 0) 0 else backgroundMin.coerceAtLeast(15)
        }
    }

    suspend fun setPublicWarningAcknowledged() {
        context.relayDataStore.edit { prefs ->
            prefs[keyPublicWarningAck] = true
        }
    }

    suspend fun markInitialSeedDoneIfNeeded(onNeedSeed: suspend () -> Unit) {
        var needed = false
        context.relayDataStore.edit { prefs ->
            if (prefs[keyInitialSeedDone] != true) {
                needed = true
                prefs[keyInitialSeedDone] = true
            }
            if ((prefs[keyLastVisit] ?: 0L) == 0L) {
                prefs[keyLastVisit] = System.currentTimeMillis()
            }
        }
        if (needed) {
            onNeedSeed()
        }
    }

    suspend fun markLineSeen(numberId: String, nowMs: Long = System.currentTimeMillis()) {
        context.relayDataStore.edit { prefs ->
            val current = decodeMap(prefs[keySeenMap]).toMutableMap()
            current[numberId] = nowMs
            prefs[keySeenMap] = json.encodeToString(current)
        }
    }

    suspend fun removeSeenEntries(numberIds: List<String>) {
        if (numberIds.isEmpty()) return
        context.relayDataStore.edit { prefs ->
            val current = decodeMap(prefs[keySeenMap]).toMutableMap()
            numberIds.forEach { current.remove(it) }
            prefs[keySeenMap] = json.encodeToString(current)
        }
    }

    suspend fun recordAppBackgrounded(nowMs: Long = System.currentTimeMillis()) {
        context.relayDataStore.edit { prefs ->
            prefs[keyLastVisit] = nowMs
        }
    }

    private fun decodeMap(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Long>>(raw) }.getOrDefault(emptyMap())
    }
}
