package com.securecam.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.securecam.core.webrtc.FirebaseSignalingClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(navController: NavController) {
    val context = LocalContext.current
    var signalClient by remember { mutableStateOf<FirebaseSignalingClient?>(null) }
    var streamStatus by remember { mutableStateOf("Waiting for Firebase Config...") }
    var latestTelemetry by remember { mutableStateOf("No AI Insights yet.") }

    DisposableEffect(Unit) {
        signalClient = FirebaseSignalingClient(context).apply {
            onOfferReceived = { streamStatus = "Offer Received from Camera!" }
        }
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote WebRTC Viewer") },
                navigationIcon = { TextButton(onClick = { navController.popBackStack() }) { Text("Back") } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
            
            Column(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0x99000000))) {
                    Text(text = "Status: $streamStatus", color = Color.Green, modifier = Modifier.padding(8.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0x99D32F2F))) {
                    Text(text = "AI: $latestTelemetry", color = Color.White, modifier = Modifier.padding(16.dp))
                }
            }

            Button(
                onClick = { signalClient?.sendSignal("JOIN", "test_sdp_request") },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
            ) {
                Text("Join Stream")
            }
        }
    }
}