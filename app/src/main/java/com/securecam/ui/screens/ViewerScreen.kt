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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.google.gson.Gson
import com.securecam.core.webrtc.*
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(navController: NavController) {
    val context = LocalContext.current
    val remoteRenderer = remember { SurfaceViewRenderer(context) }
    var signalClient by remember { mutableStateOf<FirebaseSignalingClient?>(null) }
    var streamStatus by remember { mutableStateOf("Initializing Viewport...") }
    var latestTelemetry by remember { mutableStateOf("No AI Insights yet.") }

    DisposableEffect(Unit) {
        val rtcManager = WebRTCManager(context).apply { initRenderer(remoteRenderer) }
        signalClient = FirebaseSignalingClient(context, "VIEWER")

        signalClient?.onConnected = { streamStatus = "Firebase Connected. Ready to Join!" }
        
        val observer = object : SimplePeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val json = Gson().toJson(IceCandidateData(it.sdpMid, it.sdpMLineIndex, it.sdp))
                    signalClient?.sendSignal("ICE", json)
                }
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                // Attach incoming network video frames to the Android screen
                val track = receiver?.track() as? VideoTrack
                track?.addSink(remoteRenderer)
            }
        }
        
        val peerConnection = rtcManager.createPeerConnection(observer)

        signalClient?.onOfferReceived = { sdpStr -> 
            streamStatus = "Offer Received! Routing ICE & Sending Answer..." 
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
            streamStatus = "LIVE STREAM ACTIVE"
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
            
            // Native WebRTC Video Surface Overlay
            AndroidView(factory = { remoteRenderer }, modifier = Modifier.fillMaxSize())
            
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
                onClick = { 
                    streamStatus = "Sending JOIN signal..."
                    signalClient?.sendSignal("JOIN") 
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
            ) {
                Text("Join Stream")
            }
        }
    }
}