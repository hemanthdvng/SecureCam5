package com.securecam.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.securecam.ui.screens.MainScreen
import com.securecam.ui.screens.CameraScreen
import com.securecam.ui.screens.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") { MainScreen(navController) }
                        composable("camera") { CameraScreen(navController) }
                        composable("settings") { SettingsScreen(navController) }
                        // Placeholder for Viewer Mode (to avoid crashing before WebRTC is fully coded)
                        composable("viewer") { 
                            androidx.compose.material3.Text("Viewer Mode Coming in Stage 5!") 
                        }
                    }
                }
            }
        }
    }
}