@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.scanface.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitViewController
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.window.ComposeUIViewController
import com.scanface.App
import com.scanface.core.AppStore
import com.scanface.core.CapturedPhoto
import com.scanface.core.KeyValueStore
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.*
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
private val navigationRegion = mutableStateOf<NavigationRegion?>(null)

/** Coordinates are UIKit points in the full Compose controller, before safe-area padding. */
fun updateNavigationRegion(region: NavigationRegion?) {
    navigationRegion.value = region?.takeIf {
        it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite() &&
            it.x >= 0f && it.y >= 0f && it.width > 0f && it.height > 0f
    }
}

@Composable
actual fun PlatformNavigationRegion(): NavigationRegion? = navigationRegion.value

fun MainViewController(cameraFactory: CameraControllerFactory, analyzer: NativeFaceAnalyzer, onReady: () -> Unit): UIViewController {
    com.scanface.platform.cameraFactory = cameraFactory
    updateNavigationRegion(null)
    val faceAnalyzer = IosFaceAnalyzer(analyzer)
    return ComposeUIViewController {
        App(createPlatformServices(), faceAnalyzer)
        val ready = rememberUpdatedState(onReady)
        LaunchedEffect(Unit) {
            // App restores its theme and route synchronously. Let its first frame
            // reach the renderer before removing the native launch handoff.
            withFrameNanos { }
            withFrameNanos { }
            ready.value()
        }
    }
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
        update = { controller ->
            controller.overrideUserInterfaceStyle = if (darkTheme) {
                UIUserInterfaceStyle.UIUserInterfaceStyleDark
            } else {
                UIUserInterfaceStyle.UIUserInterfaceStyleLight
            }
        },
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ),
    )
}

@Composable
actual fun PhotoImage(path: String?, modifier: Modifier, contentDescription: String) {
    val resolvedPath = path?.takeIf { it.isNotBlank() }?.let {
        runCatching { photoReferences().resolve(it) }.getOrNull()
    }
    if (resolvedPath == null) {
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
                imageView.image = UIImage.imageWithContentsOfFile(resolvedPath)
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

private fun photoReferences(): IosPhotoReferenceCodec {
    val support = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory, NSUserDomainMask, true,
    ).firstOrNull() as? String ?: error("Application storage is unavailable.")
    return IosPhotoReferenceCodec("$support/ScanFace/Captures")
}

private object IosPlatformServices : PlatformServices {
    override val storage: KeyValueStore = AtomicIosKeyValueStore()

    override fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000).toLong()

    override fun deletePhoto(path: String) {
        // Resolve only validated capture references into the current private folder.
        val resolved = photoReferences().resolve(path)
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(resolved)) {
            check(manager.removeItemAtPath(resolved, error = null)) { "Couldn't remove the saved photo." }
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

/** Snapshot replacement must finish before AppStore deletes any superseded image. */
private class AtomicIosKeyValueStore : KeyValueStore {
    private val manager = NSFileManager.defaultManager
    private val legacyDefaults = NSUserDefaults.standardUserDefaults
    private val folder: String
        get() {
            val support = NSSearchPathForDirectoriesInDomains(
                NSApplicationSupportDirectory, NSUserDomainMask, true,
            ).firstOrNull() as? String ?: error("Application storage is unavailable.")
            return "$support/ScanFace/State"
        }

    private fun path(key: String): String {
        require(key.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid storage key." }
        return "$folder/$key.json"
    }

    override fun get(key: String): String? {
        val path = path(key)
        if (!manager.fileExistsAtPath(path)) {
            val legacy = legacyDefaults.stringForKey(key) ?: return null
            put(key, legacy)
            legacyDefaults.removeObjectForKey(key)
            return decode(key, legacy)
        }
        val data = checkNotNull(NSData.dataWithContentsOfFile(path)) { "Couldn't read saved scans." }
        if (data.length == 0UL) return ""
        return decode(key, checkNotNull(data.bytes).readBytes(data.length.toInt()).decodeToString())
    }

    override fun put(key: String, value: String) {
        // Transform the whole snapshot before writing. Runtime ownership checks must
        // see one canonical path for saved, pending, and queued references alike.
        val storedValue = if (key == AppStore.STORAGE_KEY) photoReferences().encode(value) else value
        val path = path(key)
        check(manager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)) {
            "Couldn't create private storage."
        }
        check(NSURL.fileURLWithPath(folder).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)) {
            "Couldn't protect private storage from backup."
        }
        val bytes = storedValue.encodeToByteArray()
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned {
            NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
        }
        check(data.writeToFile(path, options = NSDataWritingAtomic or NSDataWritingFileProtectionComplete, error = null)) {
            "Couldn't save scans. Check available storage and try again."
        }
    }

    private fun decode(key: String, value: String): String =
        if (key == AppStore.STORAGE_KEY) photoReferences().decode(value) else value

    override fun remove(key: String) {
        val path = path(key)
        if (manager.fileExistsAtPath(path)) {
            check(manager.removeItemAtPath(path, error = null)) { "Couldn't remove saved scans." }
        }
        legacyDefaults.removeObjectForKey(key)
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
