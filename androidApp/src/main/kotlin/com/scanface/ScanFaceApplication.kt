package com.scanface

import android.app.Application
import com.scanface.core.FaceAnalyzer
import com.scanface.ml.AndroidFaceAnalyzer
import com.scanface.platform.PlatformServices
import com.scanface.platform.createPlatformServices
import com.scanface.platform.initAndroidPlatform
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.dsl.onClose

class ScanFaceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initAndroidPlatform(this)
        startKoin {
            androidContext(this@ScanFaceApplication)
            modules(scanFaceAndroidModule)
        }
    }
}

internal val scanFaceAndroidModule = module {
    single<PlatformServices> { createPlatformServices() }
    single { AndroidFaceAnalyzer(androidContext()) } onClose { it?.close() }
    single<FaceAnalyzer> { get<AndroidFaceAnalyzer>() }
}
