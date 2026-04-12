package com.securecam.core.webrtc

import android.content.Context
import org.webrtc.*

data class IceCandidateData(val sdpMid: String, val sdpMLineIndex: Int, val sdp: String)
data class SdpData(val type: String, val sdp: String)

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

open class SimplePeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(p0: IceCandidate?) {}
    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
    override fun onAddStream(p0: MediaStream?) {}
    override fun onRemoveStream(p0: MediaStream?) {}
    override fun onDataChannel(p0: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
}

class WebRTCManager(private val context: Context) {
    val rootEglBase: EglBase = EglBase.create()
    var peerConnectionFactory: PeerConnectionFactory? = null
    private var videoCapturer: VideoCapturer? = null

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun initRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(rootEglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false) // Use true for front-camera, false for back-camera
    }

    fun createLocalVideoTrack(context: Context, renderer: SurfaceViewRenderer): VideoTrack? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        // We prioritize the back camera for a security system
        val cameraName = deviceNames.firstOrNull { enumerator.isBackFacing(it) } ?: deviceNames.firstOrNull()
        
        videoCapturer = cameraName?.let { enumerator.createCapturer(it, null) }
        if (videoCapturer == null) return null
        
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
        val videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer!!.startCapture(640, 480, 30)

        val videoTrack = peerConnectionFactory?.createVideoTrack("video_track", videoSource)
        videoTrack?.addSink(renderer)
        return videoTrack
    }

    fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection? {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        
        return peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
    }

    fun dispose() {
        try { videoCapturer?.stopCapture() } catch(e: Exception){}
        videoCapturer?.dispose()
        peerConnectionFactory?.dispose()
        rootEglBase.release()
    }
}