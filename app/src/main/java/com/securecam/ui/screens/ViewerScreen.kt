package com.securecam.ui.screens

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
import androidx.navigation.NavController
import com.google.gson.Gson
import com.securecam.core.webrtc.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(navController: NavController) {
    val context = LocalContext.current
    val remoteRenderer = remember { SurfaceViewRenderer(context) }
    var signalClient by remember { mutableStateOf<FirebaseSignalingClient?>(null) }
    var streamStatus by remember { mutableStateOf("Initializing Viewport...") }
    val alertHistory = remember { mutableStateListOf<String>() }

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
                        val isSafe = alert.contains("CLEAR")
                        val bgColor = if (isSafe) Color(0x99424242) else Color(0xCCD32F2F)
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
                onClick = { 
                    streamStatus = "Sending JOIN signal..."
                    signalClient?.sendSignal("JOIN") 
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
            ) {
                Text("Join Stream")
            }
        }
    }
}