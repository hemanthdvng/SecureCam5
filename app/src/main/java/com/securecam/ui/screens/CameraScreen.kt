package com.securecam.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.google.gson.Gson
import com.securecam.core.ai.HybridAIPipeline
import com.securecam.core.webrtc.*
import dagger.hilt.android.lifecycle.HiltViewModel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    val aiPipeline: HybridAIPipeline
) : ViewModel()

@Composable
fun CameraScreen(navController: NavController, viewModel: CameraViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    
    val localRenderer = remember { SurfaceViewRenderer(context) }
    var streamStatus by remember { mutableStateOf("Initializing Hardware...") }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    DisposableEffect(Unit) {
        viewModel.aiPipeline.start()
        
        val rtcManager = WebRTCManager(context).apply { initRenderer(localRenderer) }
        val signalClient = FirebaseSignalingClient(context, "CAMERA")
        
        signalClient.clearSignals() // Erase history for new session

        signalClient.onConnected = { streamStatus = "Firebase Connected. Waiting for Viewer..." }
        
        val observer = object : SimplePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val json = Gson().toJson(IceCandidateData(it.sdpMid, it.sdpMLineIndex, it.sdp))
                    signalClient.sendSignal("ICE", json)
                }
            }
        }
        
        val peerConnection = rtcManager.createPeerConnection(observer)
        val localTrack = rtcManager.createLocalVideoTrack(context, localRenderer)
        localTrack?.let { peerConnection?.addTrack(it, listOf("stream_1")) }

        signalClient.onJoinReceived = {
            streamStatus = "Viewer JOIN detected! Gathering ICE & Sending Offer..."
            peerConnection?.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection.setLocalDescription(SimpleSdpObserver(), it)
                        val json = Gson().toJson(SdpData(it.type.canonicalForm(), it.description))
                        signalClient.sendSignal("OFFER", json)
                    }
                }
            }, MediaConstraints())
        }

        signalClient.onAnswerReceived = { sdpStr ->
            val data = Gson().fromJson(sdpStr, SdpData::class.java)
            val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(data.type), data.sdp)
            peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
            streamStatus = "LIVE STREAM ACTIVE"
        }

        signalClient.onIceCandidateReceived = { iceStr ->
            val data = Gson().fromJson(iceStr, IceCandidateData::class.java)
            peerConnection?.addIceCandidate(IceCandidate(data.sdpMid, data.sdpMLineIndex, data.sdp))
        }

        onDispose {
            try { localRenderer.release() } catch(e: Exception){}
            peerConnection?.dispose()
            rtcManager.dispose()
            viewModel.aiPipeline.stop()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        
        // Native WebRTC Video Surface Overlay
        AndroidView(factory = { localRenderer }, modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.align(Alignment.TopCenter).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0x99000000))) {
                Text(streamStatus, color = Color.Green, modifier = Modifier.padding(16.dp))
            }
        }

        Button(
            onClick = { navController.popBackStack() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).height(56.dp).fillMaxWidth(0.6f)
        ) {
            Text("END STREAM", style = MaterialTheme.typography.titleMedium)
        }
    }
}