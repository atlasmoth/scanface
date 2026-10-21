package com.scanface

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
        val services = object : PlatformServices by originalServices { override val storage = MemoryStore() }
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
        second.result.complete(AnalysisResult(AnalysisStatus.COMPLETE, 35.0, "instrumentation-only"))
        visible(By.text("Estimated facial age"))
        assertTrue("Scan feedback ends before results appear", device.wait(Until.gone(By.desc(OVERLAY)), 3_000))
        scenario.onActivity { assertEquals("The camera is released after inference", null, findPreview(it.window.decorView)) }
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

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(label, condition())
    }

    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
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
