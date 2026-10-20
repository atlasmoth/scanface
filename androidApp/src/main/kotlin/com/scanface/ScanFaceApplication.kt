package com.scanface

import android.app.Application
import com.scanface.core.FaceAnalyzer
import com.scanface.core.ModelManifestService
import com.scanface.core.PendingModelAnalyzer
import com.scanface.platform.PlatformServices
import com.scanface.platform.createPlatformServices
import com.scanface.platform.initAndroidPlatform
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

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
    single<FaceAnalyzer> { PendingModelAnalyzer() }
    single { ModelManifestService(get()) }
    // Ready for model-manifest / model-download integration. Creating this client does
    // not send requests, and the photo pipeline never hands image bytes to networking.
    single {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 30_000
            }
            engine {
                config { retryOnConnectionFailure(true) }
            }
        }
    }
}
