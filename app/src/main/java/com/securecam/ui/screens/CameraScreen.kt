package com.securecam.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.RingtoneManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val alertHistory = remember { mutableStateListOf<String>() }
    var dataChannel by remember { mutableStateOf<DataChannel?>(null) }
    
    // Command Handlers
    val ringtone = remember { RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) }
    var forceScanTrigger by remember { mutableStateOf(0) }

    val prefs = context.getSharedPreferences("securecam_prefs", Context.MODE_PRIVATE)
    val scanIntervalMs = (prefs.getFloat("scan_interval_sec", 5f) * 1000).toLong()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    LaunchedEffect(forceScanTrigger) {
        while(isActive) {
            delay(if (forceScanTrigger > 0) 500 else scanIntervalMs)
            localRenderer.addFrameListener({ bitmap ->
                val bmpCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                viewModel.aiPipeline.processFrame(bmpCopy)
            }, 0.5f)
            if (forceScanTrigger > 0) forceScanTrigger = 0 // Reset override
        }
    }

    LaunchedEffect(Unit) {
        viewModel.eventRepository.securityEvents.collect { event ->
            val text = event.description
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            
            alertHistory.add(0, "[$timeStr] $text")
            if (alertHistory.size > 50) alertHistory.removeLast()
            
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
        signalClient.onConnected = { CoroutineScope(Dispatchers.Main).launch { streamStatus = "Firebase Connected. Waiting for Viewer..." } }
        
        val observer = object : SimplePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val json = Gson().toJson(IceCandidateData(it.sdpMid, it.sdpMLineIndex, it.sdp))
                    signalClient.sendSignal("ICE", json)
                }
            }
            
            // New: Listen for Remote Viewer Commands
            override fun onDataChannel(dc: DataChannel?) {
                dc?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        buffer?.data?.let { byteBuffer ->
                            val bytes = ByteArray(byteBuffer.remaining())
                            byteBuffer.get(bytes)
                            val command = String(bytes, Charsets.UTF_8)
                            
                            CoroutineScope(Dispatchers.Main).launch {
                                when (command) {
                                    "CMD_SIREN" -> {
                                        if (ringtone.isPlaying) ringtone.stop() else ringtone.play()
                                        alertHistory.add(0, "[SYSTEM] Siren toggled remotely.")
                                    }
                                    "CMD_SWITCH_CAM" -> {
                                        rtcManager.switchCamera()
                                        alertHistory.add(0, "[SYSTEM] Camera lens switched remotely.")
                                    }
                                    "CMD_FORCE_SCAN" -> {
                                        forceScanTrigger++
                                        alertHistory.add(0, "[SYSTEM] Remote Force Scan initiated.")
                                    }
                                }
                            }
                        }
                    }
                })
            }
        }
        
        val peerConnection = rtcManager.createPeerConnection(observer)
        
        // Audio support so we can hear the Viewer
        val localAudio = rtcManager.createLocalAudioTrack()
        localAudio?.let { peerConnection?.addTrack(it, listOf("audio_1")) }

        dataChannel = peerConnection?.createDataChannel("telemetry", DataChannel.Init())
        val localTrack = rtcManager.createLocalVideoTrack(context, localRenderer)
        localTrack?.let { peerConnection?.addTrack(it, listOf("stream_1")) }

        signalClient.onJoinReceived = {
            CoroutineScope(Dispatchers.Main).launch { streamStatus = "Viewer JOIN detected! Gathering ICE..." }
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
            CoroutineScope(Dispatchers.Main).launch { streamStatus = "LIVE STREAM ACTIVE" }
        }

        signalClient.onIceCandidateReceived = { iceStr ->
            val data = Gson().fromJson(iceStr, IceCandidateData::class.java)
            peerConnection?.addIceCandidate(IceCandidate(data.sdpMid, data.sdpMLineIndex, data.sdp))
        }

        onDispose {
            try { localRenderer.release() } catch(e: Exception){}
            if (ringtone.isPlaying) ringtone.stop()
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
            
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(alertHistory) { alert ->
                    val isSafe = alert.contains("CLEAR") || alert.contains("[STATUS_SAFE]")
                    val isSystem = alert.contains("[SYSTEM]")
                    val bgColor = if (isSystem) Color(0x991976D2) else if (isSafe) Color(0x99424242) else Color(0xCCD32F2F)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bgColor),
                        modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                    ) {
                        Text(text = alert, color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(72.dp))
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