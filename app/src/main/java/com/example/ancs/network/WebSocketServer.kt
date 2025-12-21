package com.example.ancs.network

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import kotlin.concurrent.thread

class WebSocketServer(private val port: Int = 8765) {
    private val tag = "ANCS_WebSocket"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = CopyOnWriteArrayList<WebSocketClient>()

    fun start() {
        thread {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                Log.d(tag, "WebSocket Server started on port $port")

                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null) {
                            thread {
                                handleClient(clientSocket)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(tag, "Error accepting client", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Error in WebSocket server", e)
                }
            }
        }
    }

    private fun handleClient(clientSocket: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
            val output = clientSocket.outputStream
            
            // Read HTTP upgrade request
            var line: String?
            var secWebSocketKey: String? = null
            var lineCount = 0
            
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                lineCount++
                Log.d(tag, "Header: $line")
                if (line!!.startsWith("Sec-WebSocket-Key:")) {
                    secWebSocketKey = line!!.substring(19).trim()
                }
                if (lineCount > 100) break
            }
            
            if (secWebSocketKey == null) {
                Log.e(tag, "No WebSocket key found")
                clientSocket.close()
                return
            }
            
            // Send handshake
            val acceptKey = generateWebSocketAcceptKey(secWebSocketKey)
            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptKey\r\n" +
                    "\r\n"
            output.write(response.toByteArray())
            output.flush()
            Log.d(tag, "WebSocket handshake completed")
            
            val client = WebSocketClient(clientSocket, output)
            clients.add(client)
            Log.d(tag, "Client connected. Total: ${clients.size}")
            
            // Keep connection alive and handle incoming frames
            while (isRunning && !clientSocket.isClosed) {
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    break
                }
            }
            
            clientSocket.close()
        } catch (e: Exception) {
            Log.e(tag, "Error handling client", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) {}
            clients.removeIf { it.socket.isClosed }
            Log.d(tag, "Client disconnected. Remaining: ${clients.size}")
        }
    }
    
    /**
     * Broadcast call event to all connected clients
     */
    fun broadcastCallEvent(eventType: String, data: JSONObject) {
        val message = JSONObject().apply {
            put("type", eventType)
            put("data", data)
            put("timestamp", System.currentTimeMillis())
        }
        
        Log.d(tag, "Broadcasting call event: $eventType to ${clients.size} clients")
        
        clients.forEach { client ->
            try {
                sendWebSocketFrame(client.output, message.toString())
            } catch (e: Exception) {
                Log.e(tag, "Error broadcasting to client", e)
                try {
                    client.socket.close()
                } catch (ex: Exception) {}
            }
        }
    }
    
    /**
     * Broadcast audio frame to all connected clients
     * Audio sent as binary WebSocket frame
     */
    fun broadcastAudioFrame(audioData: ByteArray) {
        Log.v(tag, "Broadcasting audio frame: ${audioData.size} bytes to ${clients.size} clients")
        
        clients.forEach { client ->
            try {
                sendWebSocketBinaryFrame(client.output, audioData)
            } catch (e: Exception) {
                Log.e(tag, "Error broadcasting audio to client", e)
                try {
                    client.socket.close()
                } catch (ex: Exception) {}
            }
        }
    }
    
    /**
     * Send text WebSocket frame
     */
    private fun sendWebSocketFrame(output: OutputStream, message: String) {
        try {
            val data = message.toByteArray(Charsets.UTF_8)
            val frame = ByteArray(data.size + 14)
            var index = 0
            
            // FIN + opcode 1 (text frame)
            frame[index++] = 0x81.toByte()
            
            // Payload length with masking bit
            when {
                data.size < 126 -> {
                    frame[index++] = data.size.toByte()
                }
                data.size < 65536 -> {
                    frame[index++] = 126.toByte()
                    frame[index++] = ((data.size shr 8) and 0xFF).toByte()
                    frame[index++] = (data.size and 0xFF).toByte()
                }
                else -> {
                    frame[index++] = 127.toByte()
                    for (i in 7 downTo 0) {
                        frame[index++] = ((data.size shr (8 * i)) and 0xFF).toByte()
                    }
                }
            }
            
            // Copy payload
            System.arraycopy(data, 0, frame, index, data.size)
            
            output.write(frame, 0, index + data.size)
            output.flush()
            
            Log.v(tag, "Sent text frame: ${data.size} bytes")
        } catch (e: Exception) {
            Log.e(tag, "Error sending frame", e)
            throw e
        }
    }
    
    /**
     * Send binary WebSocket frame for audio data
     */
    private fun sendWebSocketBinaryFrame(output: OutputStream, data: ByteArray) {
        try {
            val frame = ByteArray(data.size + 14)
            var index = 0
            
            // FIN + opcode 2 (binary frame)
            frame[index++] = 0x82.toByte()
            
            // Payload length
            when {
                data.size < 126 -> {
                    frame[index++] = data.size.toByte()
                }
                data.size < 65536 -> {
                    frame[index++] = 126.toByte()
                    frame[index++] = ((data.size shr 8) and 0xFF).toByte()
                    frame[index++] = (data.size and 0xFF).toByte()
                }
                else -> {
                    frame[index++] = 127.toByte()
                    for (i in 7 downTo 0) {
                        frame[index++] = ((data.size shr (8 * i)) and 0xFF).toByte()
                    }
                }
            }
            
            // Copy payload
            System.arraycopy(data, 0, frame, index, data.size)
            
            output.write(frame, 0, index + data.size)
            output.flush()
            
            Log.v(tag, "Sent binary frame: ${data.size} bytes")
        } catch (e: Exception) {
            Log.e(tag, "Error sending binary frame", e)
            throw e
        }
    }
    
    private fun generateWebSocketAcceptKey(clientKey: String): String {
        val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest((clientKey + guid).toByteArray())
        return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
    }
    
    fun stopServer() {
        thread {
            try {
                isRunning = false
                clients.forEach { 
                    try {
                        it.socket.close()
                    } catch (e: Exception) {}
                }
                clients.clear()
                serverSocket?.close()
                Log.d(tag, "WebSocket server stopped")
            } catch (e: Exception) {
                Log.e(tag, "Error stopping server", e)
            }
        }
    }
    
    private data class WebSocketClient(
        val socket: java.net.Socket,
        val output: OutputStream
    )
}
