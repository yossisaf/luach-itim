package com.luachitim

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.luachitim.ui.LuachApp
import com.luachitim.util.initAndroidPlatform

class LuachApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initAndroidPlatform(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()          // ← handles status bar overlap correctly
        initAndroidPlatform(this)   // backup init
        setContent { LuachApp() }
    }
}
