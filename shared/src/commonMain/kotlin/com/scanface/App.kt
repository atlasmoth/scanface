package com.scanface

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scanface.core.*
import com.scanface.platform.*
import com.scanface.ui.*
import kotlinx.coroutines.launch

private enum class Route { OVERVIEW, HISTORY, CAPTURE, RESULT, SETTINGS }

@Composable
fun App(services: PlatformServices, analyzer: FaceAnalyzer = remember { PendingModelAnalyzer() }) {
    val haptics = rememberAppHaptics()
    CompositionLocalProvider(LocalAppHaptics provides haptics) {
        AppContent(services, analyzer)
    }
}

@Composable
private fun AppContent(services: PlatformServices, analyzer: FaceAnalyzer) {
    val haptics = LocalAppHaptics.current
    val scope = rememberCoroutineScope()
    val store = remember(services, analyzer) { AppStore(services.storage, analyzer, services::deletePhoto, scope) }
    val state by store.state.collectAsState()
    var routeName by rememberSaveable { mutableStateOf(if (state.pending != null) Route.RESULT.name else Route.OVERVIEW.name) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var returnRoute by rememberSaveable { mutableStateOf(Route.OVERVIEW.name) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearAll by rememberSaveable { mutableStateOf(false) }
    var discardTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val route = Route.valueOf(routeName)
    val snackbar = remember { SnackbarHostState() }
    val systemDark = isSystemInDarkTheme()
    val dark = when (state.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    fun notify(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun navigate(target: Route) {
        if (target == route) return
        if (state.pending != null && route == Route.RESULT && target != Route.RESULT) {
            haptics.perform(AppHaptic.Warning)
            discardTarget = target.name
        } else {
            haptics.perform(AppHaptic.Selection)
            routeName = target.name
        }
    }
    fun scan() {
        if (state.pending != null) routeName = Route.RESULT.name
        else {
            returnRoute = if (route == Route.HISTORY) Route.HISTORY.name else Route.OVERVIEW.name
            routeName = Route.CAPTURE.name
        }
    }
    fun settings() {
        if (route == Route.OVERVIEW || route == Route.HISTORY) returnRoute = routeName
        navigate(Route.SETTINGS)
    }
    fun scanFromNavigation() {
        haptics.perform(AppHaptic.Selection)
        scan()
    }
    fun back() {
        when (route) {
            Route.CAPTURE -> {
                if (store.state.value.pending != null) discardTarget = returnRoute
                else routeName = returnRoute
            }
            Route.RESULT -> navigate(Route.valueOf(returnRoute))
            Route.SETTINGS -> {
                haptics.perform(AppHaptic.Selection)
                routeName = returnRoute
            }
            Route.HISTORY -> navigate(Route.OVERVIEW)
            Route.OVERVIEW -> Unit
        }
    }
    var previousError by remember(store) { mutableStateOf(state.error) }
    LaunchedEffect(state.error) {
        val error = state.error
        if (error != null) {
            // A restored storage error is shown without buzzing on app launch.
            if (error != previousError) haptics.perform(AppHaptic.Error)
            previousError = error
            snackbar.showSnackbar(error)
            store.clearError()
        } else previousError = null
    }
    ScanFaceTheme(dark) {
        PlatformThemeEffect(dark)
        PlatformBackHandler(route != Route.OVERVIEW, ::back)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                val navigationRegion = PlatformNavigationRegion()
                val systemSideNavigation = navigationRegion != null
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val viewportHeight = maxHeight
                    val rail = !systemSideNavigation && maxWidth >= 600.dp
                    val wide = maxWidth >= 840.dp
                    val historyWide = maxWidth >= 840.dp
                    var previouslyWide by remember { mutableStateOf(historyWide) }
                    LaunchedEffect(historyWide) {
                        if (previouslyWide != historyWide && state.pending == null) {
                            if (historyWide && route == Route.RESULT && returnRoute == Route.HISTORY.name) {
                                routeName = Route.HISTORY.name
                            } else if (!historyWide && route == Route.HISTORY && state.records.isNotEmpty()) {
                                selectedId = state.records.firstOrNull { it.id == selectedId }?.id ?: state.records.first().id
                                returnRoute = Route.HISTORY.name
                                routeName = Route.RESULT.name
                            }
                        }
                        previouslyWide = historyWide
                    }
                    if (route == Route.CAPTURE) {
                        Box(Modifier.fillMaxSize()) {
                        CameraScreen(dark, state.isAnalyzing, discardTarget == null, onCaptured = {
                            selectedId = null
                            val analysis = store.capture(it)
                            val captureId = store.state.value.pending?.takeIf { pending -> pending.photoPath == it.path }?.id
                            if (captureId == null) routeName = returnRoute
                            if (captureId != null) scope.launch {
                                analysis.join()
                                val completed = store.state.value
                                if (completed.pending?.id == captureId && !completed.isAnalyzing && routeName == Route.CAPTURE.name) {
                                    routeName = Route.RESULT.name
                                    if (completed.error == null && discardTarget == null) {
                                        haptics.perform(if (completed.pending.status == AnalysisStatus.COMPLETE)
                                            AppHaptic.Success else AppHaptic.Warning)
                                    }
                                }
                            }
                        }, onClose = ::back)
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                        }
                    } else {
                        Row(Modifier.fillMaxSize()) {
                            if (rail) {
                                // A minimum content height keeps Settings at the bottom
                                // on tablets, while short landscape windows can scroll.
                                Column(Modifier.fillMaxHeight().width(96.dp)
                                    .verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
                                    .padding(vertical = 4.dp).selectableGroup().semantics { isTraversalGroup = true },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Glyph(AppIcon.Face, Modifier.padding(vertical = 24.dp).size(32.dp), MaterialTheme.colorScheme.primary, "ScanFace")
                                        Spacer(Modifier.height(16.dp))
                                        NavigationRailItem(route == Route.OVERVIEW, { navigate(Route.OVERVIEW) },
                                            icon = { Glyph(AppIcon.Overview) }, label = { Text("Home") })
                                        NavigationRailItem(route == Route.CAPTURE, ::scanFromNavigation, icon = { Glyph(AppIcon.Scan) }, label = { Text("Scan") })
                                        NavigationRailItem(route == Route.HISTORY, { navigate(Route.HISTORY) },
                                            icon = { Glyph(AppIcon.History) }, label = { Text("History") })
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        NavigationRailItem(route == Route.SETTINGS, ::settings,
                                            icon = { Glyph(AppIcon.Settings) }, label = { Text("Settings") })
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            Scaffold(
                                modifier = Modifier.weight(1f),
                                containerColor = MaterialTheme.colorScheme.background,
                                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                snackbarHost = { SnackbarHost(snackbar) },
                                topBar = {
                                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (route == Route.RESULT || route == Route.SETTINGS) IconButton(::back) { Glyph(AppIcon.Back, description = "Back") }
                                        else if (!rail) Glyph(AppIcon.Face, Modifier.padding(8.dp).size(28.dp), MaterialTheme.colorScheme.primary)
                                        Text(when (route) {
                                            Route.HISTORY -> "Scan history"
                                            Route.SETTINGS -> "Settings"
                                            Route.RESULT -> "Scan details"
                                            else -> "ScanFace"
                                        }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                        if (route != Route.SETTINGS && !rail && !systemSideNavigation) IconButton(::settings) { Glyph(AppIcon.Settings, description = "Settings") }
                                    }
                                },
                                bottomBar = {
                                    if (!rail && !systemSideNavigation && route in listOf(Route.OVERVIEW, Route.HISTORY)) {
                                        NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
                                            NavigationBarItem(route == Route.OVERVIEW, { navigate(Route.OVERVIEW) }, icon = { Glyph(AppIcon.Overview) }, label = { Text("Home") })
                                            NavigationBarItem(false, ::scanFromNavigation, icon = { Glyph(AppIcon.Scan) }, label = { Text("Scan") })
                                            NavigationBarItem(route == Route.HISTORY, { navigate(Route.HISTORY) }, icon = { Glyph(AppIcon.History) }, label = { Text("History") })
                                        }
                                    }
                                },
                            ) { padding ->
                                Box(Modifier.fillMaxSize().padding(padding)) {
                                    when (route) {
                                        Route.OVERVIEW -> OverviewScreen(state, wide, services.nowMillis(), ::scan,
                                            { routeName = Route.HISTORY.name },
                                            { selectedId = it.id; returnRoute = Route.OVERVIEW.name; routeName = Route.RESULT.name })
                                        Route.HISTORY -> HistoryScreen(state.records, selectedId, historyWide, services.nowMillis(),
                                            { selectedId = it.id; if (!historyWide) { returnRoute = Route.HISTORY.name; routeName = Route.RESULT.name } },
                                            ::scan, { services.shareText(store.exportCsv(it?.id)) }, { deleteId = it.id })
                                        Route.SETTINGS -> SettingsScreen(state, { store.setTheme(it) },
                                            { store.setEnteredAge(it).also { success ->
                                                if (success) { haptics.perform(AppHaptic.Success); notify("Age saved") }
                                            } },
                                            { clearAll = true },
                                            { if (store.retryCleanup()) { haptics.perform(AppHaptic.Success); notify("Photo cleanup complete") } })
                                        Route.RESULT -> {
                                            val record = state.pending ?: state.records.firstOrNull { it.id == selectedId }
                                            if (record != null) ResultScreen(record, state.pending != null, state.isAnalyzing,
                                                {
                                                    store.savePending()?.let {
                                                        haptics.perform(AppHaptic.Success)
                                                        selectedId = it.id; routeName = Route.HISTORY.name; notify("Scan saved")
                                                    }
                                                },
                                                { discardTarget = Route.CAPTURE.name },
                                                { services.shareText(store.exportCsv(record.id)) }, { deleteId = record.id })
                                            else Column(Modifier.padding(24.dp)) {
                                                EmptyState("This scan is no longer here", "Start a new capture or return to your history.", "New face scan", ::scan)
                                            }
                                        }
                                        Route.CAPTURE -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
                if (navigationRegion != null && route != Route.CAPTURE) {
                    SystemSideNavigation(
                        region = navigationRegion,
                        route = if (route == Route.RESULT) Route.valueOf(returnRoute) else route,
                        onOverview = { navigate(Route.OVERVIEW) },
                        onScan = ::scanFromNavigation,
                        onHistory = { navigate(Route.HISTORY) },
                        onSettings = ::settings,
                    )
                }
            }
        }
        if (deleteId != null || clearAll) {
            AlertDialog(onDismissRequest = { deleteId = null; clearAll = false },
                title = { Text(if (clearAll) "Delete all saved scans?" else "Delete this scan?") },
                text = { Text("This removes the saved details and photo from this device. This action cannot be undone.") },
                confirmButton = {
                    TextButton({
                        val success = if (clearAll) store.clearHistory() else store.deleteRecord(deleteId!!)
                        if (success) {
                            deleteId = null
                            clearAll = false
                            if (route == Route.RESULT) routeName = Route.HISTORY.name
                            if (store.state.value.cleanupPendingCount == 0 && store.state.value.error == null) {
                                haptics.perform(AppHaptic.Success)
                                notify("Deleted from history")
                            }
                        }
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton({
                    haptics.perform(AppHaptic.Light)
                    deleteId = null
                    clearAll = false
                }) { Text("Keep scans") } })
        }
        if (discardTarget != null) {
            AlertDialog(onDismissRequest = { discardTarget = null },
                title = { Text("Discard this capture?") }, text = {
                    Text(if (state.isAnalyzing) "This stops the scan and removes this photo."
                        else "Save this scan to keep it in your history, or discard the photo to continue.")
                },
                confirmButton = { TextButton({
                    if (store.discardPending()) {
                        if (store.state.value.cleanupPendingCount == 0 && store.state.value.error == null) {
                            haptics.perform(AppHaptic.Success)
                        }
                        routeName = discardTarget!!
                        discardTarget = null
                    }
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton({
                    haptics.perform(AppHaptic.Light)
                    discardTarget = null
                }) { Text(if (state.isAnalyzing) "Keep scanning" else "Keep editing") } })
        }
    }
}

@Composable
private fun SystemSideNavigation(
    region: NavigationRegion,
    route: Route,
    onOverview: () -> Unit,
    onScan: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    // Keep content within its safe area, but place navigation in the separate
    // system bar region. Absolute offsets preserve the hardware side in RTL.
    Column(
        modifier = Modifier.absoluteOffset(region.x.dp, region.y.dp)
            .width(region.width.dp).height(region.height.dp)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        NavigationRailItem(route == Route.OVERVIEW, onOverview,
            icon = { Glyph(AppIcon.Overview, description = "Home") }, modifier = Modifier.height(64.dp))
        NavigationRailItem(false, onScan,
            icon = { Glyph(AppIcon.Scan, description = "Scan") }, modifier = Modifier.height(64.dp))
        NavigationRailItem(route == Route.HISTORY, onHistory,
            icon = { Glyph(AppIcon.History, description = "History") }, modifier = Modifier.height(64.dp))
        NavigationRailItem(route == Route.SETTINGS, onSettings,
            icon = { Glyph(AppIcon.Settings, description = "Settings") }, modifier = Modifier.height(64.dp))
    }
}
