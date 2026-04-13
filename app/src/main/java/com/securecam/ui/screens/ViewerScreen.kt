package com.securecam.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.navigation.NavController
import com.google.gson.Gson
import com.securecam.core.webrtc.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(navController: NavController) {
    val context = LocalContext.current
    var hasMicPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMicPermission = it }
    LaunchedEffect(Unit) { if (!hasMicPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO) }

    val remoteRenderer = remember { SurfaceViewRenderer(context) }
    var signalClient by remember { mutableStateOf<FirebaseSignalingClient?>(null) }
    var streamStatus by remember { mutableStateOf("Initializing Viewport...") }
    val alertHistory = remember { mutableStateListOf<String>() }
    
    // Command Center State
    var dataChannel by remember { mutableStateOf<DataChannel?>(null) }
    var localAudioTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var isMicActive by remember { mutableStateOf(false) }

    fun sendCommand(cmd: String) {
        dataChannel?.let { dc ->
            if (dc.state() == DataChannel.State.OPEN) {
                val buffer = ByteBuffer.wrap(cmd.toByteArray(Charsets.UTF_8))
                dc.send(DataChannel.Buffer(buffer, false))
            }
        }
    }

    DisposableEffect(Unit) {
        val rtcManager = WebRTCManager(context).apply { initRenderer(remoteRenderer) }
        signalClient = FirebaseSignalingClient(context, "VIEWER")

        signalClient?.onConnected = { CoroutineScope(Dispatchers.Main).launch { streamStatus = "Firebase Connected. Ready to Join!" } }
        
        val observer = object : SimplePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val json = Gson().toJson(IceCandidateData(it.sdpMid, it.sdpMLineIndex, it.sdp))
                    signalClient?.sendSignal("ICE", json)
                }
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() as? VideoTrack
                track?.addSink(remoteRenderer)
            }
            override fun onDataChannel(dc: DataChannel?) {
                dataChannel = dc // Save reference so UI can send commands back
                dc?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer?) {
                        buffer?.data?.let { byteBuffer ->
                            val bytes = ByteArray(byteBuffer.remaining())
                            byteBuffer.get(bytes)
                            val text = String(bytes, Charsets.UTF_8)
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            
                            CoroutineScope(Dispatchers.Main).launch {
                                alertHistory.add(0, "[$timeStr] $text")
                                if (alertHistory.size > 50) alertHistory.removeLast()
                            }
                        }
                    }
                })
            }
        }
        
        val peerConnection = rtcManager.createPeerConnection(observer)
        
        // Initialize Audio (Muted by default to prevent echoes)
        localAudioTrack = rtcManager.createLocalAudioTrack()
        localAudioTrack?.setEnabled(false)
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("audio_1")) }

        signalClient?.onOfferReceived = { sdpStr -> 
            CoroutineScope(Dispatchers.Main).launch { streamStatus = "Offer Received! Routing ICE..." }
            val data = Gson().fromJson(sdpStr, SdpData::class.java)
            val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(data.type), data.sdp)
            peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
            
            peerConnection?.createAnswer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        peerConnection.setLocalDescription(SimpleSdpObserver(), it)
                        val json = Gson().toJson(SdpData(it.type.canonicalForm(), it.description))
                        signalClient?.sendSignal("ANSWER", json)
                    }
                }
            }, MediaConstraints())
            CoroutineScope(Dispatchers.Main).launch { streamStatus = "LIVE STREAM ACTIVE" }
        }

        signalClient?.onIceCandidateReceived = { iceStr ->
            val data = Gson().fromJson(iceStr, IceCandidateData::class.java)
            peerConnection?.addIceCandidate(IceCandidate(data.sdpMid, data.sdpMLineIndex, data.sdp))
        }

        onDispose {
            try { remoteRenderer.release() } catch(e: Exception){}
            peerConnection?.dispose()
            rtcManager.dispose()
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
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
            
            AndroidView(factory = { remoteRenderer }, modifier = Modifier.fillMaxSize())
            
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0x99000000))) {
                    Text(text = "Status: $streamStatus", color = Color.Green, modifier = Modifier.padding(8.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(alertHistory) { alert ->
                        val isSafe = alert.contains("CLEAR") || alert.contains("[STATUS_SAFE]")
                        val bgColor = if (isSafe) Color(0x99424242) else Color(0xCCD32F2F)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = bgColor),
                            modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                        ) {
                            Text(text = alert, color = Color.White, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(160.dp)) // Leave room for controls
            }

            // --- COMMAND CENTER UI ---
            Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).fillMaxWidth()) {
                if (streamStatus != "LIVE STREAM ACTIVE") {
                    Button(
                        onClick = { signalClient?.sendSignal("JOIN") },
                        modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth()
                    ) {
                        Text("JOIN STREAM")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(onClick = { sendCommand("CMD_SIREN") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) {
                            Text("🚨 Siren")
                        }
                        Button(onClick = { sendCommand("CMD_SWITCH_CAM") }) {
                            Text("🔄 Flip Cam")
                        }
                        Button(onClick = { sendCommand("CMD_FORCE_SCAN") }) {
                            Text("🔍 Scan")
                        }
                    }
                    Button(
                        onClick = { 
                            isMicActive = !isMicActive
                            localAudioTrack?.setEnabled(isMicActive)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isMicActive) Color(0xFF388E3C) else Color(0xFF616161)),
                        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp)
                    ) {
                        Text(if (isMicActive) "🎤 MIC ACTIVE (Tap to Mute)" else "🔇 PUSH TO TALK")
                    }
                }
            }
        }
    }
}