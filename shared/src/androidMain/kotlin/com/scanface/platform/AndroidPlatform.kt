package com.scanface.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scanface.core.KeyValueStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private lateinit var applicationContext: Context

fun initAndroidPlatform(context: Context) {
    applicationContext = context.applicationContext
}

actual fun createPlatformServices(): PlatformServices = AndroidPlatformServices(applicationContext)

private class AndroidPlatformServices(private val context: Context) : PlatformServices {
    override val storage: KeyValueStore = object : KeyValueStore {
        private val preferences = context.getSharedPreferences("scanface", Context.MODE_PRIVATE)
        override fun get(key: String): String? = preferences.getString(key, null)
        override fun put(key: String, value: String) {
            check(preferences.edit().putString(key, value).commit()) { "Unable to save local data." }
        }
    }

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun deletePhoto(path: String) {
        // Only delete images owned by this app; an exported path is never a deletion target.
        val directory = File(context.filesDir, "captures").canonicalFile
        val file = File(path).canonicalFile
        if (file.parentFile == directory && file.exists()) {
            check(file.delete()) { "Unable to delete this photo." }
        }
    }

    override fun shareText(text: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ScanFace result")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(share, "Export ScanFace result")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
actual fun PhotoImage(path: String?, modifier: Modifier, contentDescription: String) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = null
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val photo = File(path).canonicalFile
                require(photo.parentFile == File(applicationContext.filesDir, "captures").canonicalFile) {
                    "Photo is outside private app storage"
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(photo.absolutePath, bounds)
                val options = BitmapFactory.Options().apply {
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
                    inSampleSize = sample
                }
                BitmapFactory.decodeFile(photo.absolutePath, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(loaded, contentDescription, modifier, contentScale = ContentScale.Crop)
    } else {
        val background = MaterialTheme.colorScheme.surfaceVariant
        val foreground = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
            drawRect(background)
            drawCircle(foreground, size.minDimension * 0.17f, Offset(size.width / 2, size.height * 0.36f))
            drawOval(
                foreground,
                Offset(size.width * 0.15f, size.height * 0.6f),
                Size(size.width * 0.7f, size.height * 0.6f),
            )
        }
    }
}

@Composable
actual fun PlatformThemeEffect(darkTheme: Boolean) {
    val window = LocalContext.current.findActivity()?.window ?: return
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val currentDarkTheme by rememberUpdatedState(darkTheme)
    fun updateSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !currentDarkTheme
            isAppearanceLightNavigationBars = !currentDarkTheme
        }
    }
    SideEffect { updateSystemBars() }
    DisposableEffect(window, view, lifecycleOwner, configuration) {
        val update = Runnable { updateSystemBars() }
        fun scheduleUpdate() {
            view.removeCallbacks(update)
            // Reapply after Android's focus/configuration dispatch, which can
            // restore system defaults even when the app theme did not change.
            view.post(update)
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scheduleUpdate()
        }
        val focusObserver = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) scheduleUpdate()
        }
        val layoutObserver = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) scheduleUpdate()
        }
        val treeObserver = view.viewTreeObserver
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        treeObserver.addOnWindowFocusChangeListener(focusObserver)
        view.addOnLayoutChangeListener(layoutObserver)
        scheduleUpdate()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            view.removeOnLayoutChangeListener(layoutObserver)
            view.removeCallbacks(update)
            val activeObserver = if (treeObserver.isAlive) treeObserver else view.viewTreeObserver
            if (activeObserver.isAlive) activeObserver.removeOnWindowFocusChangeListener(focusObserver)
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled, onBack)
}

@Composable
actual fun PlatformNavigationRegion(): NavigationRegion? = null

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
