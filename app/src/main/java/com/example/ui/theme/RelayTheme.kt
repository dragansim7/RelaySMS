package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.preferences.AppThemeMode

@Immutable
data class RelayExtras(
    val liveSignal: Color,
    val inactiveSignal: Color,
    val circuitTrace: Color,
    val circuitAccentTrace: Color,
    val circuitViaGlow: Color,
    val showCircuitOverlay: Boolean,
    val isDarkTheme: Boolean,
)

val LocalRelayExtras = staticCompositionLocalOf {
    RelayExtras(
        liveSignal = Color(0xFF4ADE80),
        inactiveSignal = Color(0xFFE57373),
        circuitTrace = Color(0xFF232730),
        circuitAccentTrace = Color(0x44F5A623),
        circuitViaGlow = Color(0x66F5A623),
        showCircuitOverlay = true,
        isDarkTheme = true,
    )
}

private val CircuitAmberScheme = darkColorScheme(
    primary = Color(0xFFF5A623),
    onPrimary = Color(0xFF0B0C0E),
    primaryContainer = Color(0xFF2B200E),
    onPrimaryContainer = Color(0xFFFFD58A),
    secondary = Color(0xFFD98E2B),
    onSecondary = Color(0xFF0B0C0E),
    secondaryContainer = Color(0xFF1D1F26),
    onSecondaryContainer = Color(0xFFE5E7EB),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF052E16),
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF111317),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF191C22),
    onSurfaceVariant = Color(0xFF98A0AE),
    surfaceContainerLowest = Color(0xFF08090B),
    surfaceContainerLow = Color(0xFF101216),
    surfaceContainer = Color(0xFF15181E),
    surfaceContainerHigh = Color(0xFF1B1F27),
    surfaceContainerHighest = Color(0xFF232833),
    outline = Color(0xFF2D3340),
    outlineVariant = Color(0xFF20252F),
    error = Color(0xFFE57373),
    onError = Color(0xFF260909),
    errorContainer = Color(0xFF331618),
    onErrorContainer = Color(0xFFFFB4AB),
)

private val ObsidianMonoScheme = darkColorScheme(
    primary = Color(0xFFD7DDE6),
    onPrimary = Color(0xFF0A0A0B),
    primaryContainer = Color(0xFF23252A),
    onPrimaryContainer = Color(0xFFF4F4F5),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF0A0A0B),
    secondaryContainer = Color(0xFF1A1A1E),
    onSecondaryContainer = Color(0xFFE4E4E7),
    tertiary = Color(0xFF7DCEA0),
    onTertiary = Color(0xFF072112),
    background = Color(0xFF0A0A0B),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF121214),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF1A1A1E),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainerLowest = Color(0xFF070708),
    surfaceContainerLow = Color(0xFF101012),
    surfaceContainer = Color(0xFF141417),
    surfaceContainerHigh = Color(0xFF1B1B20),
    surfaceContainerHighest = Color(0xFF24242A),
    outline = Color(0xFF323238),
    outlineVariant = Color(0xFF242429),
    error = Color(0xFFE08B8B),
    onError = Color(0xFF260B0B),
    errorContainer = Color(0xFF331A1A),
    onErrorContainer = Color(0xFFFFC4C4),
)

private val StealthEmeraldScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF062017),
    primaryContainer = Color(0xFF0E2B21),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF6EE7B7),
    onSecondary = Color(0xFF062017),
    secondaryContainer = Color(0xFF15201C),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = Color(0xFFF5A623),
    onTertiary = Color(0xFF1F1402),
    background = Color(0xFF090D0B),
    onBackground = Color(0xFFF0F5F2),
    surface = Color(0xFF101613),
    onSurface = Color(0xFFF0F5F2),
    surfaceVariant = Color(0xFF17201C),
    onSurfaceVariant = Color(0xFF8FA39A),
    surfaceContainerLowest = Color(0xFF060908),
    surfaceContainerLow = Color(0xFF0E1411),
    surfaceContainer = Color(0xFF131B17),
    surfaceContainerHigh = Color(0xFF1A2420),
    surfaceContainerHighest = Color(0xFF22302A),
    outline = Color(0xFF283932),
    outlineVariant = Color(0xFF1C2923),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0909),
    errorContainer = Color(0xFF361618),
    onErrorContainer = Color(0xFFFECACA),
)

private val ArcticSlateScheme = lightColorScheme(
    primary = Color(0xFFC27803),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF5C3500),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF15803D),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEAEFF5),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE8EEF5),
    surfaceContainerHighest = Color(0xFFDEE6F0),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

val RelayShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

fun themePreviewSwatches(mode: AppThemeMode): Triple<Color, Color, Color> = when (mode) {
    AppThemeMode.CIRCUIT_AMBER -> Triple(Color(0xFF0B0C0E), Color(0xFF181B21), Color(0xFFF5A623))
    AppThemeMode.OBSIDIAN_MONO -> Triple(Color(0xFF0A0A0B), Color(0xFF1A1A1E), Color(0xFFD7DDE6))
    AppThemeMode.STEALTH_EMERALD -> Triple(Color(0xFF090D0B), Color(0xFF17201C), Color(0xFF34D399))
    AppThemeMode.ARCTIC_SLATE -> Triple(Color(0xFFF4F6F9), Color(0xFFFFFFFF), Color(0xFFC27803))
    AppThemeMode.SYSTEM_DYNAMIC -> Triple(Color(0xFF1C1F26), Color(0xFF38BDF8), Color(0xFFF5A623))
}

@Composable
fun RelayTheme(
    themeMode: AppThemeMode = AppThemeMode.CIRCUIT_AMBER,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current

    val isDark: Boolean
    val colorScheme: ColorScheme
    val extras: RelayExtras

    when (themeMode) {
        AppThemeMode.CIRCUIT_AMBER -> {
            isDark = true
            colorScheme = CircuitAmberScheme
            extras = RelayExtras(
                liveSignal = Color(0xFF4ADE80),
                inactiveSignal = Color(0xFFE57373),
                circuitTrace = Color(0xFF232834),
                circuitAccentTrace = Color(0x52F5A623),
                circuitViaGlow = Color(0x88F5A623),
                showCircuitOverlay = true,
                isDarkTheme = true,
            )
        }
        AppThemeMode.OBSIDIAN_MONO -> {
            isDark = true
            colorScheme = ObsidianMonoScheme
            extras = RelayExtras(
                liveSignal = Color(0xFF7DCEA0),
                inactiveSignal = Color(0xFFE08B8B),
                circuitTrace = Color(0xFF26262C),
                circuitAccentTrace = Color(0x33D7DDE6),
                circuitViaGlow = Color(0x55D7DDE6),
                showCircuitOverlay = false,
                isDarkTheme = true,
            )
        }
        AppThemeMode.STEALTH_EMERALD -> {
            isDark = true
            colorScheme = StealthEmeraldScheme
            extras = RelayExtras(
                liveSignal = Color(0xFF34D399),
                inactiveSignal = Color(0xFFF87171),
                circuitTrace = Color(0xFF1F3029),
                circuitAccentTrace = Color(0x4A34D399),
                circuitViaGlow = Color(0x8034D399),
                showCircuitOverlay = true,
                isDarkTheme = true,
            )
        }
        AppThemeMode.ARCTIC_SLATE -> {
            isDark = false
            colorScheme = ArcticSlateScheme
            extras = RelayExtras(
                liveSignal = Color(0xFF16A34A),
                inactiveSignal = Color(0xFFDC2626),
                circuitTrace = Color(0xFFD7DFEA),
                circuitAccentTrace = Color(0x3AC27803),
                circuitViaGlow = Color(0x55C27803),
                showCircuitOverlay = false,
                isDarkTheme = false,
            )
        }
        AppThemeMode.SYSTEM_DYNAMIC -> {
            isDark = systemDark
            colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (isDark) CircuitAmberScheme else ArcticSlateScheme
            }
            extras = RelayExtras(
                liveSignal = if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A),
                inactiveSignal = if (isDark) Color(0xFFE57373) else Color(0xFFDC2626),
                circuitTrace = colorScheme.outlineVariant,
                circuitAccentTrace = colorScheme.primary.copy(alpha = 0.25f),
                circuitViaGlow = colorScheme.primary.copy(alpha = 0.45f),
                showCircuitOverlay = isDark,
                isDarkTheme = isDark,
            )
        }
    }

    CompositionLocalProvider(LocalRelayExtras provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RelayTypography,
            shapes = RelayShapes,
            content = content,
        )
    }
}
