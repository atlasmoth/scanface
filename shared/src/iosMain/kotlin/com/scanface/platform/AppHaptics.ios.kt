@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.scanface.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

@Composable
actual fun rememberAppHaptics(): AppHaptics = remember {
    object : AppHaptics {
        private val selection = UISelectionFeedbackGenerator()
        private val impact = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
        private val notification = UINotificationFeedbackGenerator()

        override fun perform(type: AppHaptic) {
            // UIKit chooses the device's supported feedback and handles disabled
            // system haptics. App callbacks and effects run on the main thread.
            when (type) {
                AppHaptic.Selection -> {
                    selection.selectionChanged()
                    selection.prepare()
                }
                AppHaptic.Light -> {
                    impact.impactOccurred()
                    impact.prepare()
                }
                else -> {
                    notification.notificationOccurred(when (type) {
                        AppHaptic.Success -> UINotificationFeedbackType.UINotificationFeedbackTypeSuccess
                        AppHaptic.Warning -> UINotificationFeedbackType.UINotificationFeedbackTypeWarning
                        else -> UINotificationFeedbackType.UINotificationFeedbackTypeError
                    })
                    notification.prepare()
                }
            }
        }
    }
}
