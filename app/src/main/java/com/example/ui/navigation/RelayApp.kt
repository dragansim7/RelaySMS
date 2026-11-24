package com.example.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.data.preferences.AppThemeMode
import com.example.data.preferences.TempSyncSettings
import com.example.data.repository.RelayRepository
import com.example.ui.components.RelayBrandMark
import com.example.ui.components.SyncHealthPill
import com.example.ui.components.SyncHealthSheet
import com.example.ui.components.ThemeStudioSheet
import com.example.ui.screens.connect.ConnectScreen
import com.example.ui.screens.connect.ConnectViewModel
import com.example.ui.screens.inbox.InboxScreen
import com.example.ui.screens.inbox.InboxViewModel
import com.example.ui.screens.numbers.NumbersScreen
import com.example.ui.screens.numbers.NumbersViewModel
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object NumbersRoute

@Serializable
data class InboxRoute(val digits: String)

@Serializable
data object ConnectRoute

@Serializable
data object SettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayApp(
    repository: RelayRepository,
    windowSizeClass: WindowSizeClass,
    selectedTheme: AppThemeMode,
    onSelectTheme: (AppThemeMode) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val syncHealth by repository.syncHealth.collectAsStateWithLifecycle()
    val tempSyncSettings by repository.tempSyncSettingsFlow.collectAsStateWithLifecycle(
        initialValue = TempSyncSettings()
    )
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showThemeSheet by remember { mutableStateOf(false) }
    var showSyncSheet by remember { mutableStateOf(false) }
    var tabletSelectedInboxId by rememberSaveable { mutableStateOf<String?>(null) }

    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
    val useRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    // Built-in public feeds foreground poller (10s)
    LaunchedEffect(lifecycleOwner) {
        repository.ensureInitialized()
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repository.syncAll(force = false)
                delay(10_000L)
            }
        }
    }

    // Dedicated active foreground poller for Auto Temporary Numbers honoring user-configured cadence (default 30s)
    LaunchedEffect(lifecycleOwner, tempSyncSettings.foregroundIntervalSec) {
        val intervalMs = tempSyncSettings.foregroundIntervalSec.coerceIn(15, 120) * 1_000L
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(intervalMs)
                repository.syncAllAutoTempNumbers(force = false)
            }
        }
    }

    // Record app visit baseline when backgrounded
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                scope.launch { repository.recordAppBackgrounded() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isNumbersSection = destination?.hasRoute<NumbersRoute>() == true ||
        destination?.hasRoute<InboxRoute>() == true ||
        destination == null
    val isConnectSection = destination?.hasRoute<ConnectRoute>() == true
    val isSettingsSection = destination?.hasRoute<SettingsRoute>() == true

    val numbersViewModel: NumbersViewModel = viewModel(
        factory = NumbersViewModel.factory(repository)
    )
    val connectViewModel: ConnectViewModel = viewModel(
        factory = ConnectViewModel.factory(repository)
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(repository)
    )

    if (showThemeSheet) {
        ThemeStudioSheet(
            selectedTheme = selectedTheme,
            onSelectTheme = onSelectTheme,
            onDismiss = { showThemeSheet = false },
        )
    }

    if (showSyncSheet) {
        SyncHealthSheet(
            syncHealth = syncHealth,
            onSyncNow = {
                scope.launch { repository.syncAll(force = true, isManual = true) }
            },
            onDismiss = { showSyncSheet = false },
        )
    }

    val colorScheme = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { RelayBrandMark() },
                actions = {
                    SyncHealthPill(
                        status = syncHealth.overallStatus,
                        onClick = { showSyncSheet = true },
                    )
                    IconButton(
                        onClick = { showThemeSheet = true },
                        modifier = Modifier.testTag("theme_studio_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = stringResource(R.string.cd_open_themes),
                            tint = colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    titleContentColor = colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            if (!useRail) {
                NavigationBar(
                    containerColor = colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    val navItemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colorScheme.primary,
                        selectedTextColor = colorScheme.primary,
                        indicatorColor = colorScheme.primaryContainer,
                        unselectedIconColor = colorScheme.onSurfaceVariant,
                        unselectedTextColor = colorScheme.onSurfaceVariant,
                    )
                    NavigationBarItem(
                        selected = isNumbersSection,
                        onClick = {
                            navController.navigate(NumbersRoute) {
                                popUpTo(NumbersRoute) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (isNumbersSection) Icons.Filled.SimCard else Icons.Outlined.SimCard,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_numbers)) },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_bar_numbers"),
                    )
                    NavigationBarItem(
                        selected = isConnectSection,
                        onClick = {
                            navController.navigate(ConnectRoute) {
                                popUpTo(NumbersRoute) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (isConnectSection) Icons.Filled.AddLink else Icons.Outlined.AddLink,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_connect)) },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_bar_connect"),
                    )
                    NavigationBarItem(
                        selected = isSettingsSection,
                        onClick = {
                            navController.navigate(SettingsRoute) {
                                popUpTo(NumbersRoute) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (isSettingsSection) Icons.Filled.Settings else Icons.Outlined.Settings,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_settings)) },
                        colors = navItemColors,
                        modifier = Modifier.testTag("nav_bar_settings"),
                    )
                }
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (useRail) {
                val railColors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colorScheme.primary,
                    selectedTextColor = colorScheme.primary,
                    indicatorColor = colorScheme.primaryContainer,
                    unselectedIconColor = colorScheme.onSurfaceVariant,
                    unselectedTextColor = colorScheme.onSurfaceVariant,
                )
                NavigationRail(containerColor = colorScheme.surface) {
                    NavigationRailItem(
                        selected = isNumbersSection,
                        onClick = {
                            navController.navigate(NumbersRoute) {
                                popUpTo(NumbersRoute) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (isNumbersSection) Icons.Filled.SimCard else Icons.Outlined.SimCard,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_numbers)) },
                        colors = railColors,
                        modifier = Modifier.testTag("nav_rail_numbers"),
                    )
                    NavigationRailItem(
                        selected = isConnectSection,
                        onClick = {
                            navController.navigate(ConnectRoute) {
                                popUpTo(NumbersRoute) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (isConnectSection) Icons.Filled.AddLink else Icons.Outlined.AddLink,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_connect)) },
                        colors = railColors,
                        modifier = Modifier.testTag("nav_rail_connect"),
                    )
                    NavigationRailItem(
                        selected = isSettingsSection,
                        onClick = {
                            navController.navigate(SettingsRoute) {
                                popUpTo(NumbersRoute) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (isSettingsSection) Icons.Filled.Settings else Icons.Outlined.Settings,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_settings)) },
                        colors = railColors,
                        modifier = Modifier.testTag("nav_rail_settings"),
                    )
                }
                VerticalDivider(color = colorScheme.outlineVariant)
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                HorizontalDivider(color = colorScheme.outlineVariant)
                NavHost(
                    navController = navController,
                    startDestination = NumbersRoute,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable<NumbersRoute> {
                        if (isExpanded) {
                            val numbersState by numbersViewModel.uiState.collectAsStateWithLifecycle()
                            val activeId = tabletSelectedInboxId ?: numbersState.featuredNumber?.id

                            Row(modifier = Modifier.fillMaxSize()) {
                                NumbersScreen(
                                    viewModel = numbersViewModel,
                                    selectedInboxId = activeId,
                                    onOpenInbox = { digits -> tabletSelectedInboxId = digits },
                                    onNavigateConnect = {
                                        navController.navigate(ConnectRoute) { launchSingleTop = true }
                                    },
                                    modifier = Modifier.weight(0.95f),
                                )
                                VerticalDivider(color = colorScheme.outlineVariant)
                                if (activeId != null) {
                                    val detailVm: InboxViewModel = viewModel(
                                        key = "inbox_$activeId",
                                        factory = InboxViewModel.factory(activeId, repository),
                                    )
                                    InboxScreen(
                                        viewModel = detailVm,
                                        showBackButton = false,
                                        isWideLayout = false,
                                        onBack = {},
                                        onNavigateConnect = {
                                            navController.navigate(ConnectRoute) { launchSingleTop = true }
                                        },
                                        modifier = Modifier.weight(1.15f),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.weight(1.15f).fillMaxHeight(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Select a line to view incoming SMS",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        } else {
                            NumbersScreen(
                                viewModel = numbersViewModel,
                                selectedInboxId = null,
                                onOpenInbox = { digits ->
                                    navController.navigate(InboxRoute(digits))
                                },
                                onNavigateConnect = {
                                    navController.navigate(ConnectRoute) { launchSingleTop = true }
                                },
                            )
                        }
                    }

                    composable<InboxRoute> { entry ->
                        val route = entry.toRoute<InboxRoute>()
                        val inboxVm: InboxViewModel = viewModel(
                            key = "inbox_${route.digits}",
                            factory = InboxViewModel.factory(route.digits, repository),
                        )
                        InboxScreen(
                            viewModel = inboxVm,
                            showBackButton = true,
                            isWideLayout = isExpanded,
                            onBack = { navController.popBackStack() },
                            onNavigateConnect = {
                                navController.navigate(ConnectRoute) { launchSingleTop = true }
                            },
                        )
                    }

                    composable<ConnectRoute> {
                        ConnectScreen(
                            viewModel = connectViewModel,
                            onOpenInbox = { digits ->
                                if (isExpanded) {
                                    tabletSelectedInboxId = digits
                                    navController.navigate(NumbersRoute) {
                                        popUpTo(NumbersRoute) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.navigate(InboxRoute(digits))
                                }
                            },
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onOpenInbox = { digits ->
                                if (isExpanded) {
                                    tabletSelectedInboxId = digits
                                    navController.navigate(NumbersRoute) {
                                        popUpTo(NumbersRoute) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.navigate(InboxRoute(digits))
                                }
                            },
                            onOpenThemeStudio = { showThemeSheet = true },
                        )
                    }
                }
            }
        }
    }
}
