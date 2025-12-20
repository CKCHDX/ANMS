package com.example.anms.network

import android.content.Context
import android.util.Log
import com.example.anms.Message
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class WebSocketServer(
    context: Context,
    port: Int,
    private val onMessageReceived: (Message) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    private val connections = mutableSetOf<WebSocket>()
    private val tag = "WebSocketServer"

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        connections.add(conn)
        Log.d(tag, "Client connected: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        connections.remove(conn)
        Log.d(tag, "Client disconnected: ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val parts = message.split("|", limit = 2)
            if (parts.size == 2) {
                val phoneNumber = parts[0]
                val messageBody = parts[1]

                onMessageReceived(
                    Message(
                        phoneNumber = phoneNumber,
                        content = messageBody,
                        timestamp = System.currentTimeMillis(),
                        isOutgoing = true
                    )
                )

                conn.send("OK")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing message: $message", e)
            conn.send("ERROR")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(tag, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.d(tag, "WebSocket server started on port ${this.port}")
    }

    fun broadcastMessage(message: Message) {
        val payload = "${message.phoneNumber}|${message.content}|${message.timestamp}|${message.isOutgoing}"
        connections.forEach { conn ->
            try {
                conn.send(payload)
            } catch (e: Exception) {
                Log.e(tag, "Error broadcasting to client", e)
            }
        }
    }

    fun stop(callback: (Boolean) -> Unit = {}) {
        thread {
            try {
                super.stop()
                callback(false)
                Log.d(tag, "WebSocket server stopped")
            } catch (e: Exception) {
                Log.e(tag, "Error stopping server", e)
                callback(true)
            }
        }
    }
}