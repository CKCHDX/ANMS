package com.example.anms.network

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import kotlin.concurrent.thread

class WebSocketServer(
    context: Context,
    private val port: Int = 8765,
    private val onMessageReceived: (String) -> Unit = {}
) {
    private val tag = "ANMS_WS"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = mutableListOf<PrintWriter>()

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
            val writer = PrintWriter(clientSocket.outputStream, true)
            
            synchronized(clients) {
                clients.add(writer)
            }
            Log.d(tag, "Client connected. Total clients: ${clients.size}")

            // Read and broadcast messages
            var line: String?
            while (reader.readLine().also { line = it } != null && isRunning) {
                if (line != null) {
                    Log.d(tag, "Message: $line")
                    onMessageReceived(line!!)
                    // Broadcast to other clients
                    broadcastMessage(line!!)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling WebSocket client", e)
        } finally {
            try {
                clientSocket.close()
                synchronized(clients) {
                    clients.removeIf { it == null }
                }
                Log.d(tag, "Client disconnected. Remaining clients: ${clients.size}")
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun broadcastMessage(message: String) {
        synchronized(clients) {
            clients.forEach { client ->
                try {
                    client.println(message)
                    client.flush()
                } catch (e: Exception) {
                    Log.e(tag, "Error broadcasting message", e)
                }
            }
        }
    }

    fun stopServer() {
        thread {
            try {
                isRunning = false
                synchronized(clients) {
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
            return clients.size
        }
    }
}