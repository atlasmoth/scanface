@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.scanface.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification

@Composable
actual fun rememberScanEnvironment(): ScanEnvironment {
    fun current() = ScanEnvironment(
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive,
        UIAccessibilityIsReduceMotionEnabled(),
    )
    var environment by remember { mutableStateOf(current()) }
    DisposableEffect(Unit) {
        var disposed = false
        val center = NSNotificationCenter.defaultCenter
        fun observe(name: String?, foreground: Boolean? = null) = center.addObserverForName(
            requireNotNull(name), `object` = null, queue = NSOperationQueue.mainQueue,
        ) {
            // A motion notification must not undo WillResignActive before
            // UIApplication finishes updating its own applicationState.
            if (!disposed) environment = ScanEnvironment(
                foreground ?: environment.isForeground, UIAccessibilityIsReduceMotionEnabled(),
            )
        }
        val observers = listOf(
            observe(UIApplicationDidBecomeActiveNotification, foreground = true),
            observe(UIApplicationWillResignActiveNotification, foreground = false),
            observe(UIApplicationDidEnterBackgroundNotification, foreground = false),
            observe(UIAccessibilityReduceMotionStatusDidChangeNotification),
        )
        environment = current()
        onDispose {
            disposed = true
            observers.forEach(center::removeObserver)
        }
    }
    return environment
}
