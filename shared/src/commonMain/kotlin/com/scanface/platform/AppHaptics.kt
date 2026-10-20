package com.scanface.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

enum class AppHaptic { Selection, Light, Success, Warning, Error }

fun interface AppHaptics {
    fun perform(type: AppHaptic)
}

/** No-op by default so previews and non-interactive hosts need no platform services. */
val LocalAppHaptics = staticCompositionLocalOf<AppHaptics> { AppHaptics {} }

@Composable
expect fun rememberAppHaptics(): AppHaptics
