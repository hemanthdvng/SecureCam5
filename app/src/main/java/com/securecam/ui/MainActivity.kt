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
import com.securecam.ui.screens.ViewerScreen
import com.securecam.ui.screens.LogsScreen
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
                        composable("viewer") { ViewerScreen(navController) }
                        composable("logs") { LogsScreen(navController) }
                    }
                }
            }
        }
    }
}