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
    onCaptured: (CapturedPhoto) -> Unit,
    onClose: () -> Unit,
)

@Composable
expect fun PhotoImage(path: String?, modifier: Modifier, contentDescription: String)

@Composable
expect fun PlatformThemeEffect(darkTheme: Boolean)

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
