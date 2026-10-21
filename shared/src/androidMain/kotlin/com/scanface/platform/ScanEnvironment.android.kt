package com.scanface.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal fun isScanMotionReduced(context: Context): Boolean {
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val power = context.getSystemService(PowerManager::class.java)
    // Decorative motion also pauses in Battery Saver, without changing settings.
    return !scale.isFinite() || scale <= 0f || power?.isPowerSaveMode == true
}

@Composable
actual fun rememberScanEnvironment(): ScanEnvironment {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = view.context.applicationContext
    fun foreground() = view.isAttachedToWindow && view.hasWindowFocus() &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    var environment by remember(view, lifecycle) {
        mutableStateOf(ScanEnvironment(foreground(), isScanMotionReduced(context)))
    }
    DisposableEffect(view, lifecycle) {
        var disposed = false
        fun refresh() {
            if (!disposed) environment = ScanEnvironment(foreground(), isScanMotionReduced(context))
        }
        val focusObserver = ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
        var treeObserver = view.viewTreeObserver
        treeObserver.addOnWindowFocusChangeListener(focusObserver)
        val attachmentObserver = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (treeObserver.isAlive) treeObserver.removeOnWindowFocusChangeListener(focusObserver)
                treeObserver = view.viewTreeObserver
                treeObserver.addOnWindowFocusChangeListener(focusObserver)
                refresh()
            }
            override fun onViewDetachedFromWindow(view: View) {
                // Android can dispatch this before clearing the view's attach info.
                if (!disposed) environment = environment.copy(isForeground = false)
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> refresh() }
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refresh() }
        }
        val powerObserver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh() }
        }
        view.addOnAttachStateChangeListener(attachmentObserver)
        lifecycle.addObserver(lifecycleObserver)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settingsObserver,
        )
        ContextCompat.registerReceiver(context, powerObserver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
        onDispose {
            disposed = true
            view.removeOnAttachStateChangeListener(attachmentObserver)
            if (treeObserver.isAlive) treeObserver.removeOnWindowFocusChangeListener(focusObserver)
            lifecycle.removeObserver(lifecycleObserver)
            context.contentResolver.unregisterContentObserver(settingsObserver)
            context.unregisterReceiver(powerObserver)
        }
    }
    return environment
}
