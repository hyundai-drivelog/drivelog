package com.drivelog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.drivelog.app.feature.car.CarApp
import com.drivelog.app.feature.car.CarViewModel
import com.drivelog.app.ui.theme.DrivelogTheme

class MainActivity : ComponentActivity() {
    private val model: CarViewModel by viewModels()

    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.startRecording()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // CCP Back policy: the top screen hides the app instead of closing it, so a drive keeps recording.
        onBackPressedDispatcher.addCallback(this) { if (!model.goBack()) moveTaskToBack(true) }
        setContent {
            DrivelogTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    CarApp(model, onStartPressed = {
                        val missing = model.missingLocationPermissions()
                        if (missing.isEmpty()) model.startRecording() else locationPermissions.launch(missing.toTypedArray())
                    })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as DrivelogApp).store.foreground = true
        model.onForeground()
    }

    override fun onStop() {
        (application as DrivelogApp).store.foreground = false
        model.onBackground()
        super.onStop()
    }
}
