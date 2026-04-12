package com.securecam.core.webrtc

import android.content.Context
import org.webrtc.PeerConnectionFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase

class WebRTCManager(private val context: Context) {
    val rootEglBase: EglBase = EglBase.create()
    var peerConnectionFactory: PeerConnectionFactory? = null

    init {
        // Step 1: Initialize WebRTC globals
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        // Step 2: Create the factory with hardware acceleration
        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun dispose() {
        peerConnectionFactory?.dispose()
        rootEglBase.release()
    }
}