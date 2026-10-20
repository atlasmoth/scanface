package com.scanface.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.scanface.core.CapturedPhoto
import com.scanface.core.KeyValueStore

/** JVM host for domain tests; production apps use their native platform adapters. */
actual fun createPlatformServices(): PlatformServices = object : PlatformServices {
    override val storage = object : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }
    override fun nowMillis() = System.currentTimeMillis()
    override fun deletePhoto(path: String) = Unit
    override fun shareText(text: String) = Unit
    override fun openAppSettings() = Unit
}

@Composable
actual fun CameraScreen(darkTheme: Boolean, onCaptured: (CapturedPhoto) -> Unit, onClose: () -> Unit) {
    Text("Camera capture is available in the Android and iOS apps.")
}

@Composable
actual fun PhotoImage(path: String?, modifier: Modifier, contentDescription: String) {
    Box(modifier)
}

@Composable actual fun PlatformThemeEffect(darkTheme: Boolean) = Unit
@Composable actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
