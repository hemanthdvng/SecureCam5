package com.securecam.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.securecam.data.repository.EventRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    val aiPipeline: HybridAIPipeline,
    val eventRepository: EventRepository
) : ViewModel()

@Composable
fun CameraScreen(navController: NavController, viewModel: CameraViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    
    val localRenderer = remember { SurfaceViewRenderer(context) }
    var streamStatus by remember { mutableStateOf("Initializing Hardware...") }
    
    // UI SYNC: Use a scrolling history list just like the Viewer app
    val alertHistory = remember { mutableStateListOf<String>() }
    
    var dataChannel by remember { mutableStateOf<DataChannel?>(null) }

    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
    val scanIntervalMs = (prefs.getFloat("scan_interval_sec", 5f) * 1000).toLong()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    // Frame Capture Loop
    LaunchedEffect(Unit) {
        while(isActive) {
            delay(scanIntervalMs)
            localRenderer.addFrameListener({ bitmap ->
                val bmpCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                viewModel.aiPipeline.processFrame(bmpCopy)
            }, 0.5f)
        }
    }

    // Telemetry Broadcast & Local Logging Loop
    LaunchedEffect(Unit) {
        viewModel.eventRepository.securityEvents.collect { event ->
            val text = event.description
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            
            // Add the new alert to the Camera's local screen
            alertHistory.add(0, "[$timeStr] $text")
            if (alertHistory.size > 50) alertHistory.removeLast()
            
            // Push text through WebRTC to Viewer
            dataChannel?.let { dc ->
                if (dc.state() == DataChannel.State.OPEN) {
                    val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                    dc.send(DataChannel.Buffer(buffer, false))
                }
            }
        }
    }

    DisposableEffect(Unit) {
        viewModel.aiPipeline.start()
        
        val rtcManager = WebRTCManager(context).apply { initRenderer(localRenderer) }
        val signalClient = FirebaseSignalingClient(context, "CAMERA")
        
        signalClient.clearSignals() 
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
        
        dataChannel = peerConnection?.createDataChannel("telemetry", DataChannel.Init())

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
            dataChannel?.dispose()
            peerConnection?.dispose()
            rtcManager.dispose()
            viewModel.aiPipeline.stop()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        
        AndroidView(factory = { localRenderer }, modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0x99000000))) {
                Text(text = "Status: $streamStatus", color = Color.Green, modifier = Modifier.padding(8.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            // Scrolling Alert History
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(alertHistory) { alert ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xCCD32F2F)),
                        modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                    ) {
                        Text(text = alert, color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(72.dp)) // Leave room for button
        }

        Button(
            onClick = { navController.popBackStack() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).height(56.dp).fillMaxWidth(0.6f)
        ) {
            Text("END STREAM", style = MaterialTheme.typography.titleMedium)
        }
    }
}