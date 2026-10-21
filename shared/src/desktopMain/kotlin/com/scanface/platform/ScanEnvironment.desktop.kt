package com.scanface.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
actual fun rememberScanEnvironment(): ScanEnvironment {
    // Desktop is a shared-code test host. Follow Compose's window and animation
    // environment; native desktop OS accessibility is not exposed by this target.
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return ScanEnvironment(LocalWindowInfo.current.isWindowFocused, !scale.isFinite() || scale <= 0f)
}
