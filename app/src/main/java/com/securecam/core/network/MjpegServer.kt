package com.securecam.core.network

import android.graphics.Bitmap
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket

class MjpegServer {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var latestJpeg: ByteArray? = null

    fun updateFrame(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
        latestJpeg = stream.toByteArray()
    }

    fun start(port: Int = 8080, requiredToken: String) {
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
                    val client = serverSocket?.accept()
                    launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client?.inputStream))
                            val requestLine = reader.readLine() ?: ""
                            
                            if (!requestLine.contains("token=$requiredToken")) {
                                val out = client?.getOutputStream()
                                out?.write("HTTP/1.0 403 Forbidden\r\n\r\nAccess Denied: Invalid Master Token".toByteArray())
                                out?.flush()
                                client?.close()
                                return@launch
                            }

                            val out = client?.getOutputStream()
                            out?.write(("HTTP/1.0 200 OK\r\n" +
                                    "Server: SecureCam5\r\n" +
                                    "Connection: close\r\n" +
                                    "Max-Age: 0\r\n" +
                                    "Expires: 0\r\n" +
                                    "Cache-Control: no-cache, private\r\n" + 
                                    "Pragma: no-cache\r\n" + 
                                    "Content-Type: multipart/x-mixed-replace; boundary=--BoundaryString\r\n\r\n").toByteArray())
                            
                            while (isRunning && client?.isClosed == false) {
                                latestJpeg?.let { jpeg ->
                                    out?.write(("--BoundaryString\r\n" +
                                            "Content-type: image/jpeg\r\n" +
                                            "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray())
                                    out?.write(jpeg)
                                    out?.write("\r\n\r\n".toByteArray())
                                    out?.flush()
                                }
                                delay(200) 
                            }
                        } catch(e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch(e: Exception) {}
    }
}