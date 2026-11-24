package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.preferences.AppThemeMode
import com.example.ui.navigation.RelayApp
import com.example.ui.theme.RelayTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as RelayApplication).repository

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val themeMode by repository.themeModeFlow.collectAsStateWithLifecycle(
                initialValue = AppThemeMode.CIRCUIT_AMBER
            )
            val systemDark = isSystemInDarkTheme()
            val useDarkBars = themeMode.isDark ?: systemDark
            val scope = rememberCoroutineScope()

            LaunchedEffect(useDarkBars) {
                val style = if (useDarkBars) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = style,
                    navigationBarStyle = style,
                )
            }

            RelayTheme(themeMode = themeMode) {
                RelayApp(
                    repository = repository,
                    windowSizeClass = windowSizeClass,
                    selectedTheme = themeMode,
                    onSelectTheme = { mode ->
                        scope.launch { repository.setThemeMode(mode) }
                    },
                )
            }
        }
    }
}
