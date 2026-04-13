package com.securecam.core.network

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class LocalSignalingServer(private val port: Int, private val expectedToken: String) {
    var onMessageReceived: ((String) -> Unit)? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var out: PrintWriter? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // FIX 2: Enable reuseAddress so rapid exits/enters don't crash with BindException
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                while (isRunning) {
                    clientSocket = serverSocket?.accept()
                    out = PrintWriter(clientSocket!!.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(clientSocket!!.inputStream))
                    
                    val token = reader.readLine()
                    if (token != expectedToken) {
                        clientSocket?.close()
                        continue
                    }
                    
                    while (isRunning && !clientSocket!!.isClosed) {
                        val msg = reader.readLine() ?: break
                        withContext(Dispatchers.Main) { onMessageReceived?.invoke(msg) }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun send(msg: String) { CoroutineScope(Dispatchers.IO).launch { out?.println(msg) } }

    fun stop() {
        isRunning = false
        try { clientSocket?.close(); serverSocket?.close() } catch (e: Exception) {}
    }
}

class LocalSignalingClient(private val ip: String, private val port: Int, private val token: String) {
    var onMessageReceived: ((String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    private var socket: Socket? = null
    private var out: PrintWriter? = null
    private var isRunning = false

    fun connect() {
        isRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = Socket(ip, port)
                out = PrintWriter(socket!!.getOutputStream(), true)
                out?.println(token) 
                
                withContext(Dispatchers.Main) { onConnected?.invoke() }
                
                val reader = BufferedReader(InputStreamReader(socket!!.inputStream))
                while (isRunning && !socket!!.isClosed) {
                    val msg = reader.readLine() ?: break
                    withContext(Dispatchers.Main) { onMessageReceived?.invoke(msg) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "Connection failed") }
            }
        }
    }

    fun send(msg: String) { CoroutineScope(Dispatchers.IO).launch { out?.println(msg) } }

    fun disconnect() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
    }
}