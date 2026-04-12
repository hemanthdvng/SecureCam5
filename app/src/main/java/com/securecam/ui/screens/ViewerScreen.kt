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
import com.securecam.core.webrtc.WebRTCManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(navController: NavController) {
    val context = LocalContext.current
    var rtcManager by remember { mutableStateOf<WebRTCManager?>(null) }

    DisposableEffect(Unit) {
        rtcManager = WebRTCManager(context)
        onDispose {
            rtcManager?.dispose()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote WebRTC Viewer") },
                navigationIcon = { TextButton(onClick = { navController.popBackStack() }) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF121212)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (rtcManager != null) {
                // FIX: Swapped "Build" for "CheckCircle" which is included in the Core Icons library
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.CheckCircle, 
                    contentDescription = "WebRTC Ready",
                    tint = Color.Green,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("WebRTC Engine Initialized!", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "To stream video between two phones, we will need to implement a Signaling Server (like Firebase or Socket.IO) next to exchange SDP tokens.", 
                    style = MaterialTheme.typography.bodySmall, 
                    color = Color.LightGray,
                    modifier = Modifier.padding(32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}