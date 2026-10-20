package com.scanface.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberAppHaptics(): AppHaptics = remember { AppHaptics {} }
