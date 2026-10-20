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
import com.scanface.ml.LiteRtAgeModel
import java.io.File
import java.util.regex.Pattern
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun emptyHistoryAndDarkAppearancePersistAcrossActivityRecreation() {
        visible(By.text("History")).click()
        visible(By.text("No scans yet"))
        openSettings()
        visible(By.text("Dark")).click()
        assertTrue(snapshot().contains("\"theme\":\"DARK\""))
        captureEvidence("android-dark-settings")

        launch()
        visible(By.text("History")).click()
        visible(By.text("No scans yet"))
        assertEquals(0, JSONObject(snapshot()).getJSONArray("records").length())
        assertTrue(snapshot().contains("\"theme\":\"DARK\""))
        captureEvidence("android-dark-empty-history")
        device.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
        visible(By.text("Scan")).click()
        visible(By.descContains("Front camera."), 20_000)
        assertCameraControlsOverlayPreview()
        captureEvidence("android-dark-camera")
        visible(By.desc("Close camera")).click()

        openSettings()
        visible(By.text("Light")).click()
        assertTrue(snapshot().contains("\"theme\":\"LIGHT\""))
    }

    @Test
    fun cameraCanFlipUseScreenFlashCaptureAndPersistPrivateUprightPhoto() {
        device.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
        visible(By.text("Scan")).click()
        visible(By.descContains("Front camera."), 20_000)
        waitForCameraReady()
        visible(By.desc("Switch to rear camera")).click()
        visible(By.descContains("Main rear camera."), 15_000)
        waitForCameraReady()
        visible(By.desc("Switch to front camera")).click()
        visible(By.descContains("Front camera."), 15_000)
        waitForCameraReady()
        visible(By.desc("Flash off. Change flash mode.")).click()
        visible(By.desc("Screen flash. Change flash mode."))
        assertCameraControlsOverlayPreview()
        captureEvidence("android-camera")
        val capturedZoom = cameraZoom()
        visible(By.desc("Capture photo")).click()
        val deadline = SystemClock.uptimeMillis() + 60_000
        fun pendingResult() = snapshot().takeIf(String::isNotBlank)?.let(::JSONObject)?.optJSONObject("pending")
        var pending = pendingResult()
        while (pending?.optString("status") !in listOf("COMPLETE", "FAILED") && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            pending = pendingResult()
        }
        val analyzed = requireNotNull(pending)
        assertTrue("Capture metadata uses the acknowledged zoom",
            analyzed.getString("lens").endsWith("· ${zoomLabel(capturedZoom)}"))
        assertTrue("Bundled inference settles without waiting for a model download",
            analyzed.getString("status") in listOf("COMPLETE", "FAILED"))
        if (analyzed.getString("status") == "COMPLETE") {
            assertTrue(analyzed.getDouble("age").isFinite() && analyzed.getDouble("age") in 0.0..130.0)
            assertEquals(LiteRtAgeModel.MODEL_ID, analyzed.getString("modelId"))
        } else {
            assertTrue("Rejected images must not have an age", analyzed.isNull("age"))
        }

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
    fun pinchZoomCapsAtFourAndResetsWhenSwitchingLenses() {
        device.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
        visible(By.text("Scan")).click()
        waitForCameraReady()
        val preview = visible(By.descStartsWith("Camera preview."))
        val density = context.resources.displayMetrics.density
        // Start both fingers inside the unobscured image, clear of rounded
        // corners and overlaid close/capture controls.
        preview.setGestureMargins((48 * density).toInt(), (100 * density).toInt(),
            (48 * density).toInt(), (140 * density).toInt())
        val maximum = cameraMaximumZoom()
        assertTrue("Product zoom cap is at most 4x", maximum in 1f..4f)
        assertEquals(1f, cameraZoom(), .05f)
        if (maximum > 1f) {
            // Several updates arrive while the prior CameraX request is pending.
            // The gesture should settle on its final target, including at the cap.
            preview.pinchOpen(.9f, 1_500)
            waitForCameraReady()
            assertTrue("Pinch changes the acknowledged camera zoom", cameraZoom() > 1f)
            preview.pinchOpen(.9f, 1_500)
            waitForCameraReady()
            assertEquals("Repeated pinch is capped by hardware and 4x", maximum, cameraZoom(), .1f)
            preview.pinchClose(.9f, 1_500)
            waitForCameraReady()
            assertTrue("Reverse pinch reduces zoom", cameraZoom() < maximum)
            repeat(2) { preview.pinchClose(.9f, 1_500); waitForCameraReady() }
            assertEquals("Pinch cannot zoom below 1x", 1f, cameraZoom(), .05f)
            preview.pinchOpen(.75f, 1_500)
            waitForCameraReady()
            assertTrue(cameraZoom() > 1f)
            preview.pinchClose(.6f, 1_500)
            device.pressHome()
            device.waitForIdle()
            // A launcher-style intent resumes the existing task. A bare
            // component launch creates another MainActivity and starts home.
            context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)))
            waitForCameraReady()
        }
        visible(By.desc("Switch to rear camera")).click()
        visible(By.descContains("Main rear camera."), 15_000)
        waitForCameraReady()
        assertEquals("New lens starts at the confirmed baseline", 1f, cameraZoom(), .05f)
        visible(By.desc("Switch to front camera")).click()
        visible(By.descContains("Front camera."), 15_000)
        waitForCameraReady()
        assertEquals(1f, cameraZoom(), .05f)
        assertCameraControlsOverlayPreview()
    }

    @Test
    fun emptyHistoryAndNavigationSurviveFoldingAndUnfolding() {
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
            visible(By.text("History")).click()
            visible(By.text("Scan history"))
            visible(By.text("No scans yet"))

            setPosture(openedState, expanded = true)
            visible(By.text("Scan history"))
            visible(By.text("No scans yet"))
            captureEvidence("android-unfolded-empty-history")

            // Exercise both destinations while expanded, then ensure compact
            // navigation retains the chosen history destination after folding.
            visible(By.text("Overview")).click()
            visible(By.text("Start with a face scan"))
            captureEvidence("android-unfolded-home")
            device.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA")
            visible(By.text("Scan")).click()
            visible(By.descContains("Front camera."), 20_000)
            assertCameraControlsOverlayPreview()
            captureEvidence("android-unfolded-camera")
            visible(By.desc("Close camera")).click()
            visible(By.text("History")).click()
            visible(By.text("No scans yet"))
            setPosture(closedState, expanded = false)
            visible(By.text("Scan history"))
            visible(By.text("No scans yet"))
            captureEvidence("android-folded-empty-history")
            visible(By.text("Overview")).click()
            visible(By.text("Start with a face scan"))
        } finally {
            initial?.let { setPosture(it) }
        }
    }

    private fun assertCameraControlsOverlayPreview() {
        waitForCameraReady()
        val preview = visible(By.descStartsWith("Camera preview.")).visibleBounds
        val shutter = visible(By.desc("Capture photo")).visibleBounds
        val flip = visible(By.desc("Switch to rear camera")).visibleBounds
        val flash = visible(By.descContains("Change flash mode.")).visibleBounds
        val library = visible(By.text("Choose an existing photo")).visibleBounds
        val tolerance = (context.resources.displayMetrics.density * 2).toInt()
        assertTrue("Shutter stays inside the camera preview", preview.contains(shutter))
        assertTrue("Lens switching stays inside the camera preview", preview.contains(flip))
        assertTrue("Flash stays inside the camera preview", preview.contains(flash))
        assertTrue("Flip is left of the shutter", flip.centerX() < shutter.centerX())
        assertTrue("Flash is right of the shutter", flash.centerX() > shutter.centerX())
        assertTrue("Controls share the bottom baseline", abs(flip.centerY() - shutter.centerY()) <= tolerance &&
            abs(flash.centerY() - shutter.centerY()) <= tolerance)
        assertTrue("Preview begins near the top safe area", preview.top < context.resources.displayMetrics.density * 96)
        assertTrue("Preview uses available height above its footer",
            device.displayHeight - preview.bottom < context.resources.displayMetrics.density * 110)
        assertTrue("Camera has no separate header", !device.hasObject(By.text("New scan")))
        assertTrue("Zoom presets are removed", !device.hasObject(By.desc("1.5x zoom")))
        assertTrue("Shutter is centered horizontally", abs(preview.centerX() - shutter.centerX()) <= tolerance)
        assertTrue("Shutter is anchored to the lower preview", shutter.centerY() > preview.centerY())
        assertTrue("Photo library is below the camera preview", library.top >= preview.bottom)
        assertTrue("Photo library is centered horizontally", abs(preview.centerX() - library.centerX()) <= tolerance)
    }

    private fun waitForCameraReady() {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(By.desc("Capture photo"))?.isEnabled == true) return
            SystemClock.sleep(100)
        }
        error("Camera did not settle: ${device.findObject(By.descStartsWith("Camera preview."))?.contentDescription}")
    }

    private fun cameraZoom(): Float = Regex("Zoom ([0-9.]+)x")
        .find(visible(By.descStartsWith("Camera preview.")).contentDescription)!!.groupValues[1].toFloat()

    private fun cameraMaximumZoom(): Float = Regex("Maximum ([0-9.]+)x")
        .find(visible(By.descStartsWith("Camera preview.")).contentDescription)!!.groupValues[1].toFloat()

    private fun zoomLabel(value: Float): String = if (value == value.toInt().toFloat()) "${value.toInt()}x" else "${value}x"

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

    private fun scrollToText(text: String): UiObject2 {
        repeat(6) {
            device.findObject(By.text(text))?.let { return it }
            device.swipe(device.displayWidth * 3 / 4, device.displayHeight * 4 / 5,
                device.displayWidth * 3 / 4, device.displayHeight / 3, 30)
        }
        return visible(By.text(text))
    }

    private fun captureEvidence(name: String) {
        device.waitForIdle()
        // Preference writes can precede the new theme's first rendered frame.
        SystemClock.sleep(250)
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        val image = File(directory, "$name.png")
        assertTrue(device.takeScreenshot(image))
        // Connected-test cleanup may uninstall the target APK; retain review artifacts
        // in the emulator's shell-owned temporary directory.
        device.executeShellCommand("mkdir -p /data/local/tmp/scanface-qa")
        device.executeShellCommand("cp ${image.absolutePath} /data/local/tmp/scanface-qa/$name.png")
    }
}
