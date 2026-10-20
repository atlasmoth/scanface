package com.scanface

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scanface.core.FaceAnalyzer
import com.scanface.platform.PlatformServices
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val services: PlatformServices by inject()
    private val analyzer: FaceAnalyzer by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(services = services, analyzer = analyzer) }
    }
}
