package com.scanface.platform

import androidx.compose.runtime.Composable

/** Current platform conditions for decorative scan motion and feedback. */
data class ScanEnvironment(val isForeground: Boolean, val reduceMotion: Boolean)

/** Observes platform changes for the lifetime of this composition. */
@Composable
expect fun rememberScanEnvironment(): ScanEnvironment
