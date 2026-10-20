@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.scanface.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.window.ComposeUIViewController
import com.scanface.App
import com.scanface.core.CapturedPhoto
import com.scanface.core.KeyValueStore
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.*

/** Swift owns AVFoundation; the shared UI owns the surrounding application flow. */
interface CameraControllerFactory {
    fun makeCameraController(
        darkTheme: Boolean,
        onCaptured: (CapturedPhoto) -> Unit,
        onClose: () -> Unit,
    ): UIViewController
}

private var cameraFactory: CameraControllerFactory? = null
private var currentDarkTheme = false

fun MainViewController(cameraFactory: CameraControllerFactory): UIViewController {
    com.scanface.platform.cameraFactory = cameraFactory
    return ComposeUIViewController { App(createPlatformServices()) }
}

@Composable
actual fun CameraScreen(
    darkTheme: Boolean,
    onCaptured: (CapturedPhoto) -> Unit,
    onClose: () -> Unit,
) {
    val factory = requireNotNull(cameraFactory) { "Install the native camera factory before starting ScanFace." }
    UIKitViewController(
        factory = { factory.makeCameraController(darkTheme, onCaptured, onClose) },
        modifier = Modifier.fillMaxSize(),
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ),
    )
}

@Composable
actual fun PhotoImage(path: String?, modifier: Modifier, contentDescription: String) {
    if (path.isNullOrBlank()) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("◯", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        UIKitView(
            modifier = modifier,
            factory = {
                UIImageView().apply {
                    contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                    clipsToBounds = true
                    accessibilityLabel = contentDescription
                    isAccessibilityElement = contentDescription.isNotBlank()
                }
            },
            update = { imageView ->
                imageView.image = UIImage.imageWithContentsOfFile(path)
                imageView.accessibilityLabel = contentDescription
            },
            properties = UIKitInteropProperties(
                interactionMode = null,
                isNativeAccessibilityEnabled = true,
            ),
        )
    }
}

@Composable
actual fun PlatformThemeEffect(darkTheme: Boolean) {
    SideEffect {
        currentDarkTheme = darkTheme
        // Changing the app's appearance must not overwrite system traits: System mode
        // needs to continue observing real OS changes.
        NSNotificationCenter.defaultCenter.postNotificationName(
            aName = "ScanFaceThemeDidChange",
            `object` = null,
            userInfo = mapOf("dark" to darkTheme),
        )
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

actual fun createPlatformServices(): PlatformServices = IosPlatformServices

private object IosPlatformServices : PlatformServices {
    override val storage: KeyValueStore = object : KeyValueStore {
        private val defaults = NSUserDefaults.standardUserDefaults
        override fun get(key: String): String? = defaults.stringForKey(key)
        override fun put(key: String, value: String) = defaults.setObject(value, forKey = key)
        override fun remove(key: String) = defaults.removeObjectForKey(key)
    }

    override fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000).toLong()

    override fun deletePhoto(path: String) {
        val normalized = NSURL.fileURLWithPath(path).URLByStandardizingPath?.path ?: return
        val support = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory, NSUserDomainMask, true,
        ).firstOrNull() as? String ?: return
        val captureRoot = "$support/ScanFace/Captures/"
        // Only app-owned captures can be removed. Imported originals stay in Photos.
        if (normalized.startsWith(captureRoot)) {
            val manager = NSFileManager.defaultManager
            if (manager.fileExistsAtPath(normalized)) {
                check(manager.removeItemAtPath(normalized, error = null)) { "Couldn't remove the saved photo." }
            }
        }
    }

    override fun shareText(text: String) {
        val presenter = activePresenter() ?: return
        val activity = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        activity.overrideUserInterfaceStyle = if (currentDarkTheme) {
            UIUserInterfaceStyle.UIUserInterfaceStyleDark
        } else {
            UIUserInterfaceStyle.UIUserInterfaceStyleLight
        }
        activity.popoverPresentationController?.let { popover ->
            popover.sourceView = presenter.view
            popover.sourceRect = presenter.view.bounds.useContents {
                CGRectMake(size.width / 2, size.height / 2, 1.0, 1.0)
            }
        }
        presenter.presentViewController(activity, animated = true, completion = null)
    }

    override fun openAppSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
            UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any?>(), completionHandler = null)
        }
    }
}

private fun activePresenter(): UIViewController? {
    val windows = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>() }
    var controller = windows.firstOrNull { it.isKeyWindow() }?.rootViewController ?: return null
    while (controller.presentedViewController != null) controller = controller.presentedViewController!!
    return controller
}
