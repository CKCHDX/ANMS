package com.example.anms.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.anms.utils.SMSManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import kotlin.concurrent.thread

class WebSocketServer(
    private val context: Context,
    private val port: Int = 8765,
    private val onMessageReceived: (String) -> Unit = {}
) {
    private val tag = "ANMS_WS"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = mutableListOf<WebSocketClient>()
    private val smsManager = SMSManager(context)

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
                            handleWebSocketClient(clientSocket)
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

    private fun handleWebSocketClient(clientSocket: java.net.Socket) {
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
                Log.e(tag, "No WebSocket key found")
                clientSocket.close()
                return
            }
            
            // Send WebSocket handshake response
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
            Log.d(tag, "Client connected. Total clients: ${clients.size}")
            
            // Read WebSocket frames
            while (isRunning && !clientSocket.isClosed) {
                val message = readWebSocketFrame(reader)
                if (message != null) {
                    Log.d(tag, "Message: $message")
                    handleCommand(message, output)
                    onMessageReceived(message)
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling WebSocket client", e)
        } finally {
            try {
                clientSocket.close()
                synchronized(clients) {
                    clients.removeIf { it.socket.isClosed }
                }
                Log.d(tag, "Client disconnected. Remaining clients: ${clients.size}")
            } catch (e: Exception) {
                Log.e(tag, "Error closing client", e)
            }
        }
    }
    
    private fun handleCommand(message: String, output: OutputStream) {
        try {
            val parts = message.split("|", limit = 3)
            when {
                message.startsWith("GET_SMS_HISTORY:") -> {
                    val phoneNumber = message.substring(16).trim()
                    Log.d(tag, "Retrieving SMS history for $phoneNumber")
                    val history = smsManager.formatSMSHistoryForWeb(phoneNumber)
                    sendWebSocketFrame(output, "SMS_HISTORY|$history")
                }
                parts.size >= 3 && parts[0] == "SEND_SMS" -> {
                    val phoneNumber = parts[1]
                    val smsText = parts[2]
                    Log.d(tag, "Sending SMS to $phoneNumber: $smsText")
                    val success = smsManager.sendSMS(phoneNumber, smsText)
                    val response = if (success) "SMS_SENT|$phoneNumber|OK" else "SMS_ERROR|$phoneNumber|Failed"
                    sendWebSocketFrame(output, response)
                    // Also broadcast to all clients
                    broadcastMessage("SMS|$phoneNumber|$smsText|sent")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling command", e)
        }
    }
    
    private fun readWebSocketFrame(reader: BufferedReader): String? {
        return try {
            val firstByte = reader.read()
            if (firstByte == -1) return null
            
            val secondByte = reader.read()
            if (secondByte == -1) return null
            
            val opcode = firstByte and 0x0F
            val isMasked = (secondByte and 0x80) != 0
            var payloadLength = secondByte and 0x7F
            
            if (opcode == 0x08) return null // Close frame
            if (opcode != 0x01) return null // Only text frames
            
            if (payloadLength == 126) {
                val b1 = reader.read()
                val b2 = reader.read()
                payloadLength = ((b1 and 0xFF) shl 8) or (b2 and 0xFF)
            } else if (payloadLength == 127) {
                val bytes = ByteArray(8)
                for (i in 0..7) {
                    bytes[i] = reader.read().toByte()
                }
                payloadLength = 0
                for (b in bytes) {
                    payloadLength = (payloadLength shl 8) or (b.toInt() and 0xFF)
                }
            }
            
            val maskKey: ByteArray? = if (isMasked) {
                val mk = ByteArray(4)
                for (i in 0..3) {
                    mk[i] = reader.read().toByte()
                }
                mk
            } else null
            
            val payload = ByteArray(payloadLength)
            for (i in 0 until payloadLength) {
                payload[i] = reader.read().toByte()
            }
            
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }
            
            String(payload, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(tag, "Error reading WebSocket frame", e)
            null
        }
    }
    
    private fun sendWebSocketFrame(output: OutputStream, message: String) {
        try {
            val data = message.toByteArray(Charsets.UTF_8)
            val frame = ByteArray(data.size + 10)
            var index = 0
            
            // FIN + opcode (text)
            frame[index++] = 0x81.toByte()
            
            // Length
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
                    for (i in 0..7) {
                        frame[index++] = ((data.size shr (56 - i * 8)) and 0xFF).toByte()
                    }
                }
            }
            
            // Payload
            System.arraycopy(data, 0, frame, index, data.size)
            output.write(frame, 0, index + data.size)
            output.flush()
        } catch (e: Exception) {
            Log.e(tag, "Error sending WebSocket frame", e)
        }
    }
    
    private fun broadcastMessage(message: String) {
        synchronized(clients) {
            clients.forEach { client ->
                try {
                    sendWebSocketFrame(client.output, message)
                } catch (e: Exception) {
                    Log.e(tag, "Error broadcasting message", e)
                }
            }
        }
    }
    
    private fun generateWebSocketAcceptKey(clientKey: String): String {
        val guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hash = sha1.digest((clientKey + guid).toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP)
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
                Log.e(tag, "Error stopping WebSocket server", e)
            }
        }
    }
    
    fun getClientCount(): Int {
        synchronized(clients) {
            return clients.filter { !it.socket.isClosed }.size
        }
    }
    
    private data class WebSocketClient(
        val socket: java.net.Socket,
        val output: OutputStream
    )
}