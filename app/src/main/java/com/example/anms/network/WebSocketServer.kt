package com.example.anms.network

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class WebSocketServer(private val port: Int = 8765) {
    private val tag = "ANMS_WebSocket"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = mutableListOf<WebSocketClient>()

    fun start() {
        thread {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                Log.d(tag, "WebSocket Server started on port $port")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept()
                    if (clientSocket != null) {
                        thread {
                            handleClient(clientSocket)
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
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.startsWith("Sec-WebSocket-Key:")) {
                    secWebSocketKey = line!!.substring(19).trim()
                }
            }
            
            if (secWebSocketKey == null) {
                Log.e(tag, "No WebSocket key")
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
            synchronized(clients) {
                clients.add(client)
            }
            Log.d(tag, "Client connected. Total: ${clients.size}")
            
            // Keep connection alive
            while (isRunning && !clientSocket.isClosed) {
                try {
                    Thread.sleep(100)
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling client", e)
        } finally {
            try {
                clientSocket.close()
                synchronized(clients) {
                    clients.removeIf { it.socket.isClosed }
                }
                Log.d(tag, "Client disconnected. Remaining: ${clients.size}")
            } catch (e: Exception) {}
        }
    }
    
    fun broadcastIncomingSMS(phone: String, message: String) {
        val wsMessage = "INCOMING_SMS|$phone|${message.replace("\\", "\\\\").replace("\"", "\\\"")}"
        Log.d(tag, "Broadcasting: $wsMessage")
        synchronized(clients) {
            clients.forEach { client ->
                try {
                    sendWebSocketFrame(client.output, wsMessage)
                } catch (e: Exception) {
                    Log.e(tag, "Error broadcasting", e)
                }
            }
        }
    }
    
    private fun sendWebSocketFrame(output: OutputStream, message: String) {
        try {
            val data = message.toByteArray(Charsets.UTF_8)
            val frame = ByteArray(data.size + 10)
            var index = 0
            
            frame[index++] = 0x81.toByte() // FIN + opcode
            
            when {
                data.size < 126 -> frame[index++] = data.size.toByte()
                data.size < 65536 -> {
                    frame[index++] = 126.toByte()
                    frame[index++] = ((data.size shr 8) and 0xFF).toByte()
                    frame[index++] = (data.size and 0xFF).toByte()
                }
            }
            
            System.arraycopy(data, 0, frame, index, data.size)
            output.write(frame, 0, index + data.size)
            output.flush()
        } catch (e: Exception) {
            Log.e(tag, "Error sending frame", e)
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
                synchronized(clients) {
                    clients.forEach { it.socket.close() }
                    clients.clear()
                }
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