package com.scanface

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
        device.findObject(By.desc("1.5x zoom"))?.let {
            it.click()
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
        captureEvidence("android-captured-result")

        scrollToText("Save scan").click()
        visible(By.text(Pattern.compile("1 saved scans?")))
        launch()
        visible(By.text("History")).click()
        visible(By.text(Pattern.compile("1 saved scans?")))
        assertTrue("Saved photo survives recreation", photos.single().exists())
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
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        val image = File(directory, "$name.png")
        assertTrue(device.takeScreenshot(image))
        // Connected-test cleanup may uninstall the target APK; retain review artifacts
        // in the emulator's shell-owned temporary directory.
        device.executeShellCommand("mkdir -p /data/local/tmp/scanface-qa")
        device.executeShellCommand("cp ${image.absolutePath} /data/local/tmp/scanface-qa/$name.png")
    }
}
