package com.scanface.platform

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return remember(view, lifecycle) {
        AppHaptics { type ->
            // Check current native state as well as the UI's pulse-loop gate:
            // an analysis completion can arrive during a lifecycle transition.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !view.isAttachedToWindow || !view.isShown) return@AppHaptics
            // Dialog actions remain in the active app, although their separate
            // window takes focus from this host. Decorative pulses should pause.
            if (type == AppHaptic.ScanTick &&
                (!view.hasWindowFocus() || isScanMotionReduced(view.context))) return@AppHaptics
            val feedback = when (type) {
                AppHaptic.Selection -> if (Build.VERSION.SDK_INT >= 34) {
                    HapticFeedbackConstants.SEGMENT_TICK
                } else HapticFeedbackConstants.CLOCK_TICK
                AppHaptic.Light -> HapticFeedbackConstants.CONTEXT_CLICK
                AppHaptic.ScanTick -> if (Build.VERSION.SDK_INT >= 34) {
                    HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                } else HapticFeedbackConstants.CLOCK_TICK
                AppHaptic.Success -> if (Build.VERSION.SDK_INT >= 30) {
                    HapticFeedbackConstants.CONFIRM
                } else HapticFeedbackConstants.VIRTUAL_KEY
                AppHaptic.Warning -> HapticFeedbackConstants.LONG_PRESS
                AppHaptic.Error -> if (Build.VERSION.SDK_INT >= 30) {
                    HapticFeedbackConstants.REJECT
                } else HapticFeedbackConstants.LONG_PRESS
            }
            // View feedback respects both system and view haptic settings. No
            // vibrator permission or Activity retained beyond this composition.
            view.performHapticFeedback(feedback)
        }
    }
}
