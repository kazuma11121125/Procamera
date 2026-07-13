package com.procamera.recorder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Host Activity. Real Camera2 preview / recording controls arrive in Phase 4 (§4.5);
 * this stub only proves the Compose + Activity wiring builds end-to-end for Phase 1.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProCameraRoot()
        }
    }
}

@Composable
private fun ProCameraRoot() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text("ProCamera — architecture scaffold (Phase 1)")
            }
        }
    }
}
