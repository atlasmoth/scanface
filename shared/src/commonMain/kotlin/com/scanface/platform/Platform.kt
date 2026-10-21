package com.scanface.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.scanface.core.CapturedPhoto
import com.scanface.core.KeyValueStore

interface PlatformServices {
    val storage: KeyValueStore
    fun nowMillis(): Long
    fun deletePhoto(path: String)
    fun shareText(text: String)
    fun openAppSettings()
}

expect fun createPlatformServices(): PlatformServices

@Composable
expect fun CameraScreen(
    darkTheme: Boolean,
    analyzing: Boolean,
    feedbackEnabled: Boolean,
    onCaptured: (CapturedPhoto) -> Unit,
    onClose: () -> Unit,
)

@Composable
expect fun PhotoImage(path: String?, modifier: Modifier, contentDescription: String)

@Composable
expect fun PlatformThemeEffect(darkTheme: Boolean)

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** System-provided side-bar bounds in logical points, relative to the full app view. */
data class NavigationRegion(val x: Float, val y: Float, val width: Float, val height: Float)

@Composable
expect fun PlatformNavigationRegion(): NavigationRegion?
