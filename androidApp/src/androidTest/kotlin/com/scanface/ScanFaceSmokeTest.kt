package com.scanface

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.scanface.ui.dateLabel
import java.io.File
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Runs on a device/emulator with front and rear cameras, including the Pixel Fold AVD. */
@RunWith(AndroidJUnit4::class)
class ScanFaceSmokeTest {
    private lateinit var context: Context
    private lateinit var device: UiDevice

    @Before
    fun launchCleanApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.pressHome()
        context.getSharedPreferences("scanface", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "captures").listFiles()?.forEach { it.delete() }
        launch()
        visible(By.text("Start with a face scan"))
    }

    @After
    fun restoreRotation() {
        device.unfreezeRotation()
    }

    @Test
    fun sampleHistoryAndDarkAppearancePersistAcrossActivityRecreation() {
        visible(By.text("Explore sample results")).click()
        visible(By.text("Sample data"))
        visible(By.text("History")).click()
        visible(By.text("3 saved scans"))
        openSettings()
        visible(By.text("Dark")).click()
        assertTrue(snapshot().contains("\"theme\":\"DARK\""))
        captureEvidence("android-dark-settings")

        launch()
        visible(By.text("History")).click()
        visible(By.text("3 saved scans"))
        assertTrue(snapshot().contains("\"theme\":\"DARK\""))
        captureEvidence("android-dark-history")

        openSettings()
        visible(By.text("Light")).click()
        assertTrue(snapshot().contains("\"theme\":\"LIGHT\""))
    }

    @Test
    fun cameraCanFlipUseScreenFlashCaptureAndPersistPrivateUprightPhoto() {
        device.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
        visible(By.text("Scan")).click()
        visible(By.text("Front camera"), 20_000)
        val zoomSelector = By.desc("1.5x zoom")
        device.findObject(zoomSelector)?.let {
            assertTrue("Zoom choice is enabled", it.isEnabled)
            it.click()
            var zoomSettled = false
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < deadline) {
                val chip = device.findObject(zoomSelector)
                // Compose exposes the custom label as a child of the selectable chip.
                val selected = generateSequence(chip) { it.parent }.take(3)
                    .any { it.isChecked || it.isSelected }
                zoomSettled = selected ||
                    device.hasObject(By.textStartsWith("Zoom did not respond. The camera has been reset")) ||
                    device.hasObject(By.text("This zoom is unavailable."))
                if (zoomSettled) break
                SystemClock.sleep(100)
            }
            assertTrue("Zoom completes or safely resets before switching lenses", zoomSettled)
            assertTrue(visible(By.desc("Switch to rear camera")).isEnabled)
            visible(By.text("Capture photo"))
        }
        visible(By.desc("Switch to rear camera")).click()
        visible(By.text("Main rear camera"), 15_000)
        visible(By.desc("Switch to front camera")).click()
        visible(By.text("Front camera"), 15_000)
        visible(By.desc("Flash off. Change flash mode.")).click()
        visible(By.text("Screen flash"))
        captureEvidence("android-camera")
        visible(By.text("Capture photo")).click()
        visible(By.text("Ready for the next step"), 30_000)
        assertFalse("A real image must not produce a fabricated age", snapshot().contains("\"status\":\"COMPLETE\""))
        assertTrue(snapshot().contains("AWAITING_MODEL"))

        val photos = File(context.filesDir, "captures").listFiles().orEmpty()
        assertEquals(1, photos.size)
        assertTrue(photos.single().name.endsWith(".jpg"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photos.single().absolutePath, bounds)
        assertTrue("Capture must contain decodable pixels", bounds.outWidth > 0 && bounds.outHeight > 0)
        visible(By.desc("Captured face").clazz("android.widget.ImageView"))
        captureEvidence("android-captured-result")

        scrollToText("Save scan").click()
        visible(By.text(Pattern.compile("1 saved scans?")))
        launch()
        visible(By.text("History")).click()
        visible(By.text(Pattern.compile("1 saved scans?")))
        assertTrue("Saved photo survives recreation", photos.single().exists())
    }

    @Test
    fun historySelectionSurvivesFoldingAndUnfolding() {
        val states = device.executeShellCommand("cmd device_state print-states")
        val closed = postureIdentifier(states, "CLOSED")
        val opened = postureIdentifier(states, "OPENED")
        assumeTrue("Requires a foldable device with named CLOSED and OPENED states", closed != null && opened != null)
        val closedState = requireNotNull(closed)
        val openedState = requireNotNull(opened)
        val initial = Regex("Committed state: DeviceState\\{identifier=(\\d+)")
            .find(device.executeShellCommand("cmd device_state state"))?.groupValues?.get(1)?.toInt()
        try {
            setPosture(closedState, expanded = false)
            scrollToText("Explore sample results").click()
            visible(By.text("Sample data"))
            visible(By.text("History")).click()
            visible(By.text("3 saved scans"))
            val records = JSONObject(snapshot()).getJSONArray("records")
            val selectedDate = dateLabel(records.getJSONObject(1).getLong("createdAtMillis"), true)
            val oldestDate = dateLabel(records.getJSONObject(2).getLong("createdAtMillis"), true)
            scrollToText(selectedDate).click()
            visible(By.text("Scan details"))
            visible(By.text(selectedDate))
            visible(By.text("37"))

            setPosture(openedState, expanded = true)
            visible(By.text("Scan history"))
            visible(By.text("3 saved scans"))
            visible(By.text("Estimated facial age"))
            assertVisibleCount(By.text(selectedDate), 2)
            device.wait(Until.gone(By.text("Sample results added")), 5_000)
            captureEvidence("android-unfolded-selected-history")

            // Change the selection in the wide list before folding, proving that
            // compact navigation follows selection rather than always the latest scan.
            visible(By.text(oldestDate)).click()
            assertVisibleCount(By.text(oldestDate), 2)
            setPosture(closedState, expanded = false)
            visible(By.text("Scan details"))
            visible(By.text(oldestDate))
            assertFalse(device.hasObject(By.text("3 saved scans")))
            captureEvidence("android-folded-selected-history")
            visible(By.desc("Back")).click()
            visible(By.text("3 saved scans"))
        } finally {
            initial?.let { setPosture(it) }
        }
    }

    private fun launch() {
        context.startActivity(
            requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        visible(By.text("ScanFace"))
    }

    private fun snapshot(): String =
        context.getSharedPreferences("scanface", Context.MODE_PRIVATE).getString("scanface.snapshot.v1", "")!!

    private fun openSettings() {
        (device.findObject(By.desc("Settings")) ?: visible(By.text("Settings"))).click()
    }

    private fun postureIdentifier(states: String, name: String): Int? =
        Regex("identifier=(\\d+), name='$name'").find(states)?.groupValues?.get(1)?.toInt()

    private fun setPosture(identifier: Int, expanded: Boolean? = null) {
        device.executeShellCommand("cmd device_state state $identifier")
        device.waitForIdle()
        if (expanded != null) {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while ((context.resources.configuration.screenWidthDp >= 840) != expanded &&
                SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
            }
            assertEquals("Window width reflects the requested posture",
                expanded, context.resources.configuration.screenWidthDp >= 840)
        }
        // Pixel Fold can lock the outer display after the configuration callback.
        // Retry dismissal after the display transition, without restarting the Activity.
        repeat(5) {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            if (device.wait(Until.hasObject(By.pkg(context.packageName)), 2_000)) return
        }
        assertTrue("The app resumes after the posture transition", device.hasObject(By.pkg(context.packageName)))
    }

    private fun visible(selector: BySelector, timeout: Long = 10_000): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing UI element: $selector" }

    private fun assertVisibleCount(selector: BySelector, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (device.findObjects(selector).size != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
        }
        assertEquals("Expected matching list and detail content for $selector",
            expected, device.findObjects(selector).size)
    }

    private fun scrollToText(text: String): UiObject2 {
        repeat(6) {
            device.findObject(By.text(text))?.let { return it }
            device.swipe(device.displayWidth * 3 / 4, device.displayHeight * 4 / 5,
                device.displayWidth * 3 / 4, device.displayHeight / 3, 30)
        }
        return visible(By.text(text))
    }

    private fun captureEvidence(name: String) {
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        val image = File(directory, "$name.png")
        assertTrue(device.takeScreenshot(image))
        // Connected-test cleanup may uninstall the target APK; retain review artifacts
        // in the emulator's shell-owned temporary directory.
        device.executeShellCommand("mkdir -p /data/local/tmp/scanface-qa")
        device.executeShellCommand("cp ${image.absolutePath} /data/local/tmp/scanface-qa/$name.png")
    }
}
