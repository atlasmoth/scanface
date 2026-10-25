package com.scanface

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
fun App(services: PlatformServices, analyzer: FaceAnalyzer) {
    val haptics = rememberAppHaptics()
    CompositionLocalProvider(LocalAppHaptics provides haptics) {
        AppContent(services, analyzer)
    }
}

@Composable
private fun AppContent(services: PlatformServices, analyzer: FaceAnalyzer) {
    val haptics = LocalAppHaptics.current
    val scanEnvironment = rememberScanEnvironment()
    val scope = rememberCoroutineScope()
    val store = remember(services, analyzer) {
        AppStore(services.storage, analyzer, services::deletePhoto, scope, services.incomingImages)
    }
    val state by store.state.collectAsState()
    val incoming by services.incomingImages.state.collectAsState()
    val restoredPending = remember(store) { state.pending }
    var routeName by rememberSaveable { mutableStateOf(if (state.pending != null) Route.RESULT.name else Route.OVERVIEW.name) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var returnRoute by rememberSaveable { mutableStateOf(Route.OVERVIEW.name) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearAll by rememberSaveable { mutableStateOf(false) }
    var discardTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var captureSession by rememberSaveable { mutableIntStateOf(0) }
    var importPreview by remember(store) { mutableStateOf<CapturedPhoto?>(null) }
    var incomingDialog by rememberSaveable { mutableStateOf(false) }
    var restoringRoute by remember(store) { mutableStateOf(true) }
    val route = if (restoringRoute && restoredPending != null && routeName == Route.CAPTURE.name) {
        Route.RESULT
    } else Route.valueOf(routeName)
    val snackbar = remember { SnackbarHostState() }
    val systemDark = isSystemInDarkTheme()
    val dark = when (state.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    LaunchedEffect(store) {
        if (restoredPending != null && routeName == Route.CAPTURE.name) {
            selectedId = null
            discardTarget = null
            routeName = Route.RESULT.name
        }
        restoringRoute = false
    }
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
            importPreview = null
            returnRoute = if (route == Route.HISTORY) Route.HISTORY.name else Route.OVERVIEW.name
            captureSession++
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
    LaunchedEffect(incoming.error) {
        val error = incoming.error ?: return@LaunchedEffect
        val retry = incoming.canRetry
        val response = snackbar.showSnackbar(error, actionLabel = if (retry) "Retry" else null,
            duration = if (retry) SnackbarDuration.Indefinite else SnackbarDuration.Long)
        if (response == SnackbarResult.ActionPerformed) services.incomingImages.retry()
        else if (services.incomingImages.state.value.error == error) services.incomingImages.clearError()
    }
    LaunchedEffect(incoming.photos.size) {
        if (incoming.photos.isNotEmpty() && (route == Route.CAPTURE || state.pending != null)) {
            snackbar.showSnackbar("Photo queued. Finish your current scan to open it.")
        }
    }
    LaunchedEffect(incoming, state.pending?.id, state.records, state.isAnalyzing, state.error,
        routeName, scanEnvironment.isForeground, deleteId, clearAll, discardTarget, incomingDialog) {
        val request = incoming.photos.firstOrNull() ?: return@LaunchedEffect
        val owned = state.pending?.takeIf { it.id == request.id } ?: state.records.firstOrNull { it.id == request.id }
        if (owned != null) {
            if (owned.photoPath == request.photo.path) services.incomingImages.acknowledge(request.id)
            else store.dismissIncoming(request.id, services.incomingImages)
            return@LaunchedEffect
        }
        if (!scanEnvironment.isForeground || state.pending != null || state.isAnalyzing || state.error != null ||
            incoming.error != null || route !in listOf(Route.OVERVIEW, Route.HISTORY) ||
            deleteId != null || clearAll || discardTarget != null || incomingDialog) return@LaunchedEffect
        val analysis = store.captureIncoming(request) ?: return@LaunchedEffect
        if (store.state.value.pending?.id != request.id) return@LaunchedEffect
        returnRoute = routeName
        selectedId = null
        importPreview = request.photo
        captureSession++
        routeName = Route.CAPTURE.name
        services.incomingImages.acknowledge(request.id)
        scope.launch {
            analysis.join()
            val completed = store.state.value
            if (completed.pending?.id == request.id && !completed.isAnalyzing && routeName == Route.CAPTURE.name) {
                routeName = Route.RESULT.name
                if (completed.error == null && discardTarget == null) {
                    haptics.perform(if (completed.pending.status == AnalysisStatus.COMPLETE) AppHaptic.Success else AppHaptic.Warning)
                }
            }
        }
    }
    val waitingImages = incoming.photos.filterNot { request ->
        state.pending?.id == request.id || state.records.any { it.id == request.id }
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
                    val scanComplete = state.pending != null && !state.isAnalyzing
                    AnimatedContent(
                        // A rapid retake during the exit animation needs a fresh native
                        // camera, not the outgoing controller whose alpha is already zero.
                        targetState = if (route == Route.CAPTURE) captureSession else null,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (initialState != null && targetState == null && scanComplete && discardTarget == null &&
                                scanEnvironment.isForeground && !scanEnvironment.reduceMotion) {
                                fadeIn(tween(220)) togetherWith if (cameraFadeHandledNatively && importPreview == null) {
                                    ExitTransition.KeepUntilTransitionsFinished
                                } else fadeOut(tween(180))
                            } else EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "Camera to result",
                    ) { cameraSession ->
                    if (cameraSession != null) {
                        Box(Modifier.fillMaxSize()) {
                        val imported = importPreview
                        if (imported != null) ImportedScanPreview(imported, state.isAnalyzing, scanComplete,
                            discardTarget == null, onClose = { if (route == Route.CAPTURE) back() })
                        else CameraScreen(dark, state.isAnalyzing, scanComplete, discardTarget == null, onCaptured = {
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
                        }, onClose = { if (route == Route.CAPTURE) back() })
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
                                        if (waitingImages.isNotEmpty()) TextButton({ incomingDialog = true }) {
                                            Text("${waitingImages.size} waiting")
                                        }
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
                        if (discardTarget == Route.CAPTURE.name) captureSession++
                        if (discardTarget == Route.CAPTURE.name) importPreview = null
                        routeName = discardTarget!!
                        discardTarget = null
                    }
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton({
                    haptics.perform(AppHaptic.Light)
                    discardTarget = null
                }) { Text(if (state.isAnalyzing) "Keep scanning" else "Keep editing") } })
        }
        if (incomingDialog) {
            AlertDialog(onDismissRequest = { incomingDialog = false },
                title = { Text("Incoming photos") },
                text = { Text("${waitingImages.size} photos will open one at a time after you finish your current scan.") },
                confirmButton = { TextButton({ incomingDialog = false }) { Text("Keep waiting") } },
                dismissButton = { TextButton({
                    for (request in services.incomingImages.state.value.photos) {
                        val current = store.state.value
                        if (current.pending?.id != request.id && current.records.none { it.id == request.id }) {
                            if (!store.dismissIncoming(request.id, services.incomingImages)) break
                        }
                    }
                    incomingDialog = false
                }) { Text("Remove queued photos", color = MaterialTheme.colorScheme.error) } },
            )
        }
    }
}

@Composable
private fun ImportedScanPreview(photo: CapturedPhoto, analyzing: Boolean, complete: Boolean,
    feedbackEnabled: Boolean, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = photo.width.toFloat() / photo.height
        val width = minOf(maxWidth, maxHeight * aspect)
        Box(Modifier.width(width).height(width / aspect)) {
            PhotoImage(photo.path, Modifier.fillMaxSize(), "Imported photo")
            CameraScanOverlay(analyzing, complete, feedbackEnabled, Modifier.matchParentSize())
        }
        FilledTonalIconButton(onClose, Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Glyph(AppIcon.Close, description = "Close scan")
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
