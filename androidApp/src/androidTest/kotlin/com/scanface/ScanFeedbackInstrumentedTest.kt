package com.scanface

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.scanface.core.AnalysisResult
import com.scanface.core.AnalysisStatus
import com.scanface.core.AppStore
import com.scanface.core.CapturedPhoto
import com.scanface.core.FaceAnalyzer
import com.scanface.core.KeyValueStore
import com.scanface.platform.PlatformServices
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.json.JSONObject

/** Holds only inference in the test APK; capture, App navigation, and preview are real. */
@RunWith(AndroidJUnit4::class)
class ScanFeedbackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val analyzer = HeldAnalyzer()
    private lateinit var originalAnalyzer: FaceAnalyzer
    private lateinit var originalServices: PlatformServices
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var originalIdleTimeout = 10_000L

    @Before
    fun launchWithIsolatedStorageAndHeldInference() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
        originalIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 100
        originalAnalyzer = GlobalContext.get().get()
        originalServices = GlobalContext.get().get()
        val theme = runCatching {
            JSONObject(originalServices.storage.get(AppStore.STORAGE_KEY).orEmpty()).optString("theme", "SYSTEM")
        }.getOrDefault("SYSTEM").takeIf { it in listOf("SYSTEM", "LIGHT", "DARK") } ?: "SYSTEM"
        val services = object : PlatformServices by originalServices { override val storage = MemoryStore(theme) }
        loadKoinModules(module {
            single<FaceAnalyzer> { analyzer }
            single<PlatformServices> { services }
        })
        scenario = ActivityScenario.launch(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        visible(By.text("Start with a face scan"))
    }

    @After
    fun removeOnlyTestOwnedCapturesAndRestoreBindings() {
        if (::scenario.isInitialized) scenario.close()
        analyzer.requests.forEach { File(it.photo.path).delete() }
        if (::originalServices.isInitialized) loadKoinModules(module {
            single<FaceAnalyzer> { originalAnalyzer }
            single<PlatformServices> { originalServices }
        })
        Configurator.getInstance().waitForIdleTimeout = originalIdleTimeout
    }

    @Test
    fun scanStaysInLivePreviewUntilCompletionAndCancelNeverOpensAResult() {
        val first = captureAndAwaitAnalysis()
        val preview = requirePreview()
        screenshot("android-live-preview-scanning")
        assertLockedPreview(preview)
        assertScanFitsPreview(preview)
        visible(By.desc("Close camera")).click()
        visible(By.text("Discard this capture?"))
        assertTrue("The modal pauses scan feedback", device.wait(Until.gone(By.desc(OVERLAY)), 3_000))
        visible(By.text("Discard")).click()
        visible(By.text("Start with a face scan"))
        await("Cancelling must reach the analyzer") { first.cancelled }
        assertFalse(File(first.photo.path).exists())
        first.result.complete(AnalysisResult(AnalysisStatus.COMPLETE, 35.0, "instrumentation-only"))
        assertFalse("A cancelled analysis cannot open results", device.hasObject(By.text("Estimated facial age")))

        val second = captureAndAwaitAnalysis()
        val secondPreview = requirePreview()
        assertLockedPreview(secondPreview)
        // Let the live sweep move before inspecting it again. No production delay exists.
        SystemClock.sleep(700)
        assertSame("The active camera remains mounted during inference", secondPreview, requirePreview())
        var enabledDuringExit = false
        val observer = ViewTreeObserver.OnPreDrawListener {
            if (secondPreview.isAttachedToWindow && secondPreview.isEnabled) enabledDuringExit = true
            true
        }
        scenario.onActivity { secondPreview.viewTreeObserver.addOnPreDrawListener(observer) }
        try {
            second.result.complete(AnalysisResult(AnalysisStatus.COMPLETE, 35.0, "instrumentation-only"))
            assertTrue("Results begin appearing without waiting for a complete sweep cycle",
                device.wait(Until.hasObject(By.text("Estimated facial age")), 1_000))
            assertTrue("The outgoing scan fades away with the camera", device.wait(Until.gone(By.desc(OVERLAY)), 1_000))
            await("The camera is released after the short exit transition", timeout = 1_000) {
                var released = false
                scenario.onActivity { released = findPreview(it.window.decorView) == null }
                released
            }
            assertFalse("The outgoing preview never unlocks during the fade", enabledDuringExit)
        } finally {
            scenario.onActivity {
                if (secondPreview.viewTreeObserver.isAlive) secondPreview.viewTreeObserver.removeOnPreDrawListener(observer)
            }
        }
        screenshot("android-live-preview-complete")
    }

    private fun captureAndAwaitAnalysis(): Request {
        visible(By.text("Scan")).click()
        await("Camera is ready") { device.findObject(By.desc("Capture photo"))?.isEnabled == true }
        visible(By.desc("Capture photo")).click()
        val request = requireNotNull(analyzer.started.poll(10, TimeUnit.SECONDS)) { "Capture did not reach inference" }
        visible(By.desc(OVERLAY))
        visible(By.text("Analyzing your scan…"))
        assertFalse("Capture stays in the viewfinder during analysis", device.hasObject(By.text("Scan details")))
        return request
    }

    private fun assertLockedPreview(preview: PreviewView) {
        scenario.onActivity {
            assertTrue("The live preview remains attached", preview.isAttachedToWindow)
            assertEquals(PreviewView.StreamState.STREAMING, preview.previewStreamState.value)
            assertFalse("Pinch and focus are disabled during inference", preview.isEnabled)
        }
        assertFalse(visible(By.desc("Capture photo")).isEnabled)
        assertFalse(visible(By.desc("Switch to rear camera")).isEnabled)
        assertFalse(visible(By.descContains("Change flash mode.")).isEnabled)
        // Material3 keeps the label node enabled beneath its disabled button.
        assertFalse(generateSequence(visible(By.text("Choose an existing photo"))) { it.parent }.all { it.isEnabled })
    }

    private fun requirePreview(): PreviewView {
        var preview: PreviewView? = null
        scenario.onActivity { preview = findPreview(it.window.decorView) }
        return requireNotNull(preview) { "Live camera preview was removed during analysis" }
    }

    private fun assertScanFitsPreview(preview: PreviewView) {
        val bounds = Rect()
        scenario.onActivity { assertTrue(preview.getGlobalVisibleRect(bounds)) }
        assertEquals("The scan spans the actual visible camera image, without arbitrary insets",
            bounds, visible(By.desc(OVERLAY)).visibleBounds)
    }

    private fun findPreview(view: View): PreviewView? {
        if (view is PreviewView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findPreview(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun screenshot(name: String) {
        val image = File(context.getExternalFilesDir(null), "qa/$name.png").apply { parentFile!!.mkdirs() }
        assertTrue(device.takeScreenshot(image))
        device.executeShellCommand("mkdir -p /data/local/tmp/scanface-scan-feedback")
        device.executeShellCommand("cp ${image.absolutePath} /data/local/tmp/scanface-scan-feedback/$name.png")
    }

    private fun visible(selector: BySelector): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), 10_000)) { "Missing UI element: $selector" }

    private fun await(label: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(label, condition())
    }

    private class MemoryStore(theme: String) : KeyValueStore {
        private val values = mutableMapOf(AppStore.STORAGE_KEY to "{\"theme\":\"$theme\"}")
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    private class Request(val photo: CapturedPhoto) {
        val result = CompletableDeferred<AnalysisResult>()
        @Volatile var cancelled = false
    }

    private class HeldAnalyzer : FaceAnalyzer {
        val requests = CopyOnWriteArrayList<Request>()
        val started = LinkedBlockingQueue<Request>()
        override suspend fun analyze(photo: CapturedPhoto): AnalysisResult {
            val request = Request(photo).also { requests.add(it); started.add(it) }
            return try { request.result.await() }
            catch (failure: CancellationException) { request.cancelled = true; throw failure }
        }
    }

    private companion object { const val OVERLAY = "Scan in progress" }
}
