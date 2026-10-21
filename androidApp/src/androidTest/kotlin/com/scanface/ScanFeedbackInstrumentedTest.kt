package com.scanface

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.scanface.platform.AppHaptic
import com.scanface.platform.AppHaptics
import com.scanface.platform.LocalAppHaptics
import com.scanface.platform.PlatformThemeEffect
import com.scanface.platform.rememberAppHaptics
import com.scanface.ui.ResultScreen
import com.scanface.ui.ScanFaceTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The delayed analyzer and portrait exist only in the instrumentation APK. This
 * hosts the real result UI, store, lifecycle, and platform feedback without a
 * production delay, sample result, alternate launch route, or model dependency.
 */
@RunWith(AndroidJUnit4::class)
class ScanFeedbackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val pulses = CopyOnWriteArrayList<Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val feedbackEnabled = mutableStateOf(true)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var store: AppStore
    private lateinit var analyzer: HeldAnalyzer
    private lateinit var photo: CapturedPhoto
    private var initialPosture: Int? = null
    private var initialAnimatorScale: String? = null
    private var originalIdleTimeout = 10_000L

    @Before
    fun prepareIsolatedHarness() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        initialPosture = Regex("Committed state: DeviceState\\{identifier=(\\d+)")
            .find(device.executeShellCommand("cmd device_state state"))?.groupValues?.get(1)?.toInt()
        initialAnimatorScale = device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 1")
        // An intentionally animating surface is never accessibility-idle.
        originalIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 100
        val bytes = instrumentation.context.assets.open("author-face.png").use { it.readBytes() }
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
        val fixture = File(context.filesDir, "captures/scan-feedback-${UUID.randomUUID()}.png").apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
        photo = CapturedPhoto(fixture.absolutePath, dimensions.outWidth, dimensions.outHeight,
            "Instrumentation portrait", false, System.currentTimeMillis())
    }

    @After
    fun restoreDeviceAndRemoveOnlyOwnedFixture() {
        if (::scenario.isInitialized) scenario.close()
        scope.cancel()
        if (::photo.isInitialized) File(photo.path).delete()
        initialAnimatorScale?.let {
            device.executeShellCommand(if (it == "null") "settings delete global animator_duration_scale"
                else "settings put global animator_duration_scale $it")
        }
        initialPosture?.let { device.executeShellCommand("cmd device_state state $it") }
        Configurator.getInstance().waitForIdleTimeout = originalIdleTimeout
    }

    @Test
    fun lightPhoneScanSettlesImmediatelyOnSuccessWithoutObscuringThePortrait() {
        setPosture("CLOSED")
        attachHarness(dark = false)
        assertFalse(device.hasObject(By.desc(OVERLAY)))
        startAnalysis()
        val photoBounds = portraitBounds()
        val during = screenshot("android-phone-light-scanning")
        assertOverlayFitsPhoto(photoBounds)
        assertFalse("An unfinished result cannot be saved", device.hasObject(By.text("Save scan")))
        assertTrue("Inference can be cancelled", visible(By.text("Cancel scan")).isEnabled)
        await("rhythmic scan feedback") { pulses.size >= 3 }
        val intervals = pulses.zipWithNext { left, right -> right - left }
        assertTrue("Feedback must remain gentle rather than buzz each frame", intervals.all { it >= 500 })

        analyzer.result.complete(AnalysisResult(AnalysisStatus.COMPLETE, 35.0, "instrumentation-only"))
        visible(By.text("Estimated facial age"))
        assertOverlayAndFeedbackStop()
        assertEquals("The original photo does not jump when inference completes", photoBounds, portraitBounds())
        val after = screenshot("android-phone-light-complete")
        assertPortraitRemainsVisible(during, after, photoBounds)
        assertTrue(visible(By.text("Save scan")).isEnabled)
    }

    @Test
    fun darkTabletScanStopsOnFailureAndKeepsTheOriginalPhotoGeometry() {
        setPosture("OPENED")
        attachHarness(dark = true)
        startAnalysis()
        val bounds = portraitBounds()
        assertOverlayFitsPhoto(bounds)
        screenshot("android-tablet-dark-scanning")
        analyzer.result.complete(AnalysisResult(AnalysisStatus.FAILED, message = "Test analysis failed."))
        visible(By.text("No estimate this time"))
        assertOverlayAndFeedbackStop()
        assertEquals(bounds, portraitBounds())
        screenshot("android-tablet-dark-failed")
    }

    @Test
    fun feedbackPausesForDialogsAndBackgroundAndCancellationCannotReplayIt() {
        setPosture("CLOSED")
        attachHarness(dark = true)
        startAnalysis()
        screenshot("android-phone-dark-scanning")
        await("first scan pulse") { pulses.isNotEmpty() }
        onMain { feedbackEnabled.value = false }
        assertOverlayAndFeedbackStop()
        assertTrue("A dialog pauses presentation without cancelling inference", store.state.value.isAnalyzing)
        onMain { feedbackEnabled.value = true }
        visible(By.desc(OVERLAY))
        val beforeBackground = pulses.size
        device.pressHome()
        await("activity stops") {
            scenario.state == androidx.lifecycle.Lifecycle.State.CREATED
        }
        assertPulseCountStaysStill()
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)))
        visible(By.desc(OVERLAY))
        await("scan resumes only while analysis remains active") { pulses.size > beforeBackground }

        visible(By.text("Cancel scan")).click()
        visible(By.text("No scan started"))
        assertOverlayAndFeedbackStop()
        await("analyzer receives cancellation") { analyzer.cancelled }
        assertFalse(File(photo.path).exists())
        analyzer.result.complete(AnalysisResult(AnalysisStatus.COMPLETE, 35.0, "instrumentation-only"))
        assertOverlayAndFeedbackStop()
        assertEquals(null, store.state.value.pending)
    }

    @Test
    fun reducedMotionUpdatesLiveAndKeepsAStaticAccessibleScanIndicator() {
        setPosture("OPENED")
        attachHarness(dark = false)
        startAnalysis()
        await("animated scan begins") { pulses.isNotEmpty() }
        device.executeShellCommand("settings put global animator_duration_scale 0")
        // Give the settings observer and the current frame time to settle.
        SystemClock.sleep(250)
        assertPulseCountStaysStill()
        visible(By.desc(OVERLAY))
        val first = screenshot("android-tablet-light-reduced-motion")
        SystemClock.sleep(800)
        val second = screenshot("android-tablet-light-reduced-motion-settled")
        assertSamePhotoPixels(first, second, portraitBounds())
        assertTrue(store.state.value.isAnalyzing)
        analyzer.result.complete(AnalysisResult(AnalysisStatus.COMPLETE, 35.0, "instrumentation-only"))
        visible(By.text("Estimated facial age"))
        assertOverlayAndFeedbackStop()
    }

    private fun attachHarness(dark: Boolean) {
        analyzer = HeldAnalyzer()
        onMain {
            store = AppStore(MemoryStore(), analyzer, { File(it).delete() }, scope)
        }
        scenario = ActivityScenario.launch(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        scenario.onActivity { activity ->
            activity.setContent {
                val nativeHaptics = rememberAppHaptics()
                val recordingHaptics = remember(nativeHaptics) {
                    AppHaptics { type ->
                        if (type == AppHaptic.ScanTick) pulses.add(SystemClock.uptimeMillis())
                        nativeHaptics.perform(type)
                    }
                }
                CompositionLocalProvider(LocalAppHaptics provides recordingHaptics) {
                    val state by store.state.collectAsState()
                    ScanFaceTheme(dark) {
                        PlatformThemeEffect(dark)
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                val pending = state.pending
                                if (pending == null) Text("No scan started")
                                else ResultScreen(pending, true, state.isAnalyzing,
                                    {}, { store.discardPending() }, {}, {}, feedbackEnabled.value)
                            }
                        }
                    }
                }
            }
        }
        visible(By.text("No scan started"))
    }

    private fun startAnalysis() {
        onMain { store.capture(photo) }
        visible(By.text("Analyzing your scan…"))
        visible(By.desc(OVERLAY))
    }

    private fun assertOverlayFitsPhoto(photo: Rect) {
        assertTrue("The scan remains inside the visible viewport",
            Rect(0, 0, device.displayWidth, device.displayHeight).contains(photo))
        assertTrue("The photo keeps its original aspect ratio",
            abs(photo.width().toDouble() / photo.height() - this.photo.width.toDouble() / this.photo.height) < .02)
        val status = visible(By.text("Analyzing your scan…")).visibleBounds
        if (context.resources.configuration.screenWidthDp >= 840) {
            assertTrue("Tablet analysis sits alongside the photo", photo.right < status.left)
            assertTrue("Tablet status is centered beside the photo", abs(photo.centerY() - status.centerY()) < 8)
        } else {
            assertTrue("Phone analysis follows the photo", photo.bottom < status.top)
        }
    }

    private fun assertOverlayAndFeedbackStop() {
        assertTrue("The overlay must leave as soon as analysis/presentation ends", device.wait(Until.gone(By.desc(OVERLAY)), 3_000))
        assertPulseCountStaysStill()
    }

    private fun assertPulseCountStaysStill() {
        SystemClock.sleep(150)
        val count = pulses.size
        SystemClock.sleep(850)
        assertEquals("No queued scan pulse may outlive its active foreground presentation", count, pulses.size)
    }

    private fun portraitBounds(): Rect {
        // Compose exposes the topmost full-photo progress semantics while the
        // scan is active. After completion, the original image node reappears.
        return (device.findObject(By.desc(OVERLAY)) ?:
            visible(By.desc("Captured face").clazz("android.widget.ImageView"))).visibleBounds
    }

    private fun screenshot(name: String): File {
        val image = File(context.getExternalFilesDir(null), "qa/$name.png").apply { parentFile!!.mkdirs() }
        assertTrue(device.takeScreenshot(image))
        device.executeShellCommand("mkdir -p /data/local/tmp/scanface-scan-feedback")
        device.executeShellCommand("cp ${image.absolutePath} /data/local/tmp/scanface-scan-feedback/$name.png")
        return image
    }

    private fun assertPortraitRemainsVisible(during: File, settled: File, bounds: Rect) {
        comparePhotoPixels(during, settled, bounds) { meanDifference, stronglyChanged ->
            assertTrue("Scan decoration must leave the facial image readable: mean delta=$meanDifference", meanDifference < 24)
            assertTrue("Most facial pixels must remain unobscured: changed=$stronglyChanged", stronglyChanged < .15)
        }
    }

    private fun assertSamePhotoPixels(first: File, second: File, bounds: Rect) {
        comparePhotoPixels(first, second, bounds) { meanDifference, _ ->
            assertEquals("Reduced motion must hold the scan geometry still", 0.0, meanDifference, .05)
        }
    }

    private fun comparePhotoPixels(first: File, second: File, bounds: Rect, check: (Double, Double) -> Unit) {
        val left = requireNotNull(BitmapFactory.decodeFile(first.absolutePath))
        val right = requireNotNull(BitmapFactory.decodeFile(second.absolutePath))
        try {
            var difference = 0L
            var changed = 0
            var count = 0
            // Inspect the face region rather than brackets around the photo edge.
            val insetX = bounds.width() / 5
            val insetY = bounds.height() / 5
            for (y in bounds.top + insetY until bounds.bottom - insetY step 3) {
                for (x in bounds.left + insetX until bounds.right - insetX step 3) {
                    val a = left.getPixel(x, y)
                    val b = right.getPixel(x, y)
                    val delta = (abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))) / 3
                    difference += delta
                    if (delta > 80) changed++
                    count++
                }
            }
            check(difference.toDouble() / count, changed.toDouble() / count)
        } finally { left.recycle(); right.recycle() }
    }

    private fun setPosture(name: String) {
        val states = device.executeShellCommand("cmd device_state print-states")
        val identifier = Regex("identifier=(\\d+), name='$name'").find(states)?.groupValues?.get(1) ?: return
        device.executeShellCommand("cmd device_state state $identifier")
        SystemClock.sleep(700)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    private fun visible(selector: BySelector): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), 10_000)) { "Missing UI element: $selector" }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(label, condition())
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private class MemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    private class HeldAnalyzer : FaceAnalyzer {
        val result = CompletableDeferred<AnalysisResult>()
        @Volatile var cancelled = false
        override suspend fun analyze(photo: CapturedPhoto): AnalysisResult = try { result.await() }
        catch (failure: CancellationException) { cancelled = true; throw failure }
    }

    private companion object { const val OVERLAY = "Scan in progress" }
}
