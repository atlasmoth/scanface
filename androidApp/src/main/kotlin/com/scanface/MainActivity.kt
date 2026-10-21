package com.scanface

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.core.view.WindowCompat
import com.scanface.core.FaceAnalyzer
import com.scanface.platform.PlatformServices
import org.koin.android.ext.android.inject
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val services: PlatformServices by inject()
    private val analyzer: FaceAnalyzer by inject()
    private var splashExit: Animator? = null
    private var releaseSplash: (() -> Unit)? = null
    private var backgrounded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val startupDark = savedAppearanceIsDark(systemDark)
        setTheme(if (startupDark) R.style.AppStartingTheme_Dark else R.style.AppStartingTheme_Light)
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { startupDark }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        splash.setOnExitAnimationListener { provider ->
            finishSplash(provider, startupDark, systemDark, animate = savedInstanceState == null)
        }
        // Native first-draw readiness dismisses the splash. Model loading and
        // decorative animation never hold the first usable screen behind a timer.
        setContent { App(services = services, analyzer = analyzer) }
    }

    private fun savedAppearanceIsDark(systemDark: Boolean): Boolean = runCatching {
        val saved = getSharedPreferences("scanface", MODE_PRIVATE).getString("scanface.snapshot.v1", null)
        when (saved?.let(::JSONObject)?.optString("theme")) {
            "DARK" -> true
            "LIGHT" -> false
            else -> systemDark
        }
    }.getOrDefault(systemDark)

    private fun finishSplash(provider: SplashScreenViewProvider, dark: Boolean, systemDark: Boolean, animate: Boolean) {
        var removed = false
        val remove = {
            if (!removed) {
                removed = true
                provider.remove()
                restoreStartupBarContrast(dark)
                releaseSplash = null
                splashExit = null
            }
        }
        releaseSplash = remove
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || backgrounded || isFinishing || isDestroyed) {
            remove()
            return
        }
        if (dark != systemDark) {
            // The system window is drawn before preferences can be read. On
            // handoff, recolor that same native surface to the restored choice;
            // the icon animation ends here rather than replaying another splash.
            provider.view.setBackgroundColor(if (dark) Color.rgb(16, 26, 22) else Color.rgb(246, 247, 242))
            val icon = provider.iconView
            val iconLocation = IntArray(2).also(icon::getLocationInWindow)
            val splashLocation = IntArray(2).also(provider.view::getLocationInWindow)
            AppCompatResources.getDrawable(this, R.drawable.splash_mark_still)?.mutate()?.let { mark ->
                mark.setTint(if (dark) Color.rgb(174, 215, 186) else Color.rgb(36, 92, 69))
                // Android12+ reports the masked icon view (192dp), while its
                // no-background drawable is rendered in a 288dp outer container.
                val size = resources.getDimensionPixelSize(R.dimen.splash_mark_container)
                val left = iconLocation[0] - splashLocation[0] + (icon.width - size) / 2
                val top = iconLocation[1] - splashLocation[1] + (icon.height - size) / 2
                mark.setBounds(left, top, left + size, top + size)
                icon.alpha = 0f
                provider.view.overlay.add(mark)
            }
            restoreStartupBarContrast(dark)
        }
        splashExit = ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f).apply {
            duration = 120
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = remove()
                override fun onAnimationCancel(animation: Animator) = remove()
            })
            start()
        }
    }

    private fun restoreStartupBarContrast(dark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onStart() {
        backgrounded = false
        super.onStart()
    }

    override fun onStop() {
        backgrounded = true
        splashExit?.cancel()
        releaseSplash?.invoke()
        super.onStop()
    }

    override fun onDestroy() {
        splashExit?.cancel()
        releaseSplash?.invoke()
        super.onDestroy()
    }
}
