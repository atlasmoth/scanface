package com.scanface.platform

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    return remember(view) {
        AppHaptics { type ->
            val feedback = when (type) {
                AppHaptic.Selection -> if (Build.VERSION.SDK_INT >= 34) {
                    HapticFeedbackConstants.SEGMENT_TICK
                } else HapticFeedbackConstants.CLOCK_TICK
                AppHaptic.Light -> HapticFeedbackConstants.CONTEXT_CLICK
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
            if (view.isAttachedToWindow) view.performHapticFeedback(feedback)
        }
    }
}
