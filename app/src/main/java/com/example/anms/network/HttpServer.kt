package com.example.anms.network

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.example.anms.sms.SmsDatabase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

class HttpServer(val context: Context, private val port: Int = 8080, private val wsServer: WebSocketServer? = null) {
    private val tag = "ANMS_Http"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val smsManager = SmsManager.getDefault()
    private val smsDb = SmsDatabase(context)

    fun start() {
        thread {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                Log.d(tag, "HTTP Server started on port $port")

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
                    Log.e(tag, "Error in HTTP server", e)
                }
            }
        }
    }

    private fun handleClient(clientSocket: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.inputStream))
            val writer = PrintWriter(clientSocket.outputStream, true)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            Log.d(tag, "$method $path")

            // Read headers
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:")) {
                    contentLength = line.substring(16).trim().toIntOrNull() ?: 0
                }
            }

            val response = when {
                path == "/" -> sendHtmlResponse(getHtml())
                path.startsWith("/api/messages/") -> {
                    // Pagination endpoint: /api/messages/{phone}?offset=0&limit=50
                    val pathParts = path.substring(14).split("?")
                    val phone = URLDecoder.decode(pathParts[0], "UTF-8")
                    val queryString = pathParts.getOrNull(1) ?: ""
                    val offset = queryString.split("&").find { it.startsWith("offset=") }?.substring(7)?.toIntOrNull() ?: 0
                    val limit = queryString.split("&").find { it.startsWith("limit=") }?.substring(6)?.toIntOrNull() ?: 50
                    handleGetMessages(phone, offset, limit)
                }
                path.startsWith("/send") && method == "POST" -> {
                    val body = if (contentLength > 0) {
                        val chars = CharArray(contentLength)
                        reader.read(chars)
                        String(chars)
                    } else ""
                    handleSendMessage(body)
                }
                else -> sendNotFound()
            }

            writer.print(response)
            writer.flush()
            clientSocket.close()
        } catch (e: Exception) {
            Log.e(tag, "Error handling client", e)
            try {
                clientSocket.close()
            } catch (e: Exception) {}
        }
    }

    private fun handleGetMessages(phone: String, offset: Int, limit: Int): String {
        return try {
            Log.d(tag, "Loading messages for: $phone (offset=$offset, limit=$limit)")
            val allMessages = smsDb.getConversation(phone, 500)
            Log.d(tag, "Got ${allMessages.size} total messages")

            // Pagination from END of conversation (most recent messages)
            // offset=0 means get the LAST 'limit' messages
            // offset=8 means skip last 8, get next 8, etc.
            val startIndex = maxOf(0, allMessages.size - limit - offset)
            val endIndex = allMessages.size - offset
            
            val paginatedMessages = if (startIndex < endIndex && endIndex > 0) {
                allMessages.subList(startIndex, endIndex)
            } else {
                emptyList()
            }

            val json = paginatedMessages.joinToString(",") { msg ->
                val dir = if (msg.type == 1) "in" else "out"
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(msg.timestamp)
                val cleanBody = msg.body.replace("\\", " ").replace("\"", "\\\"")
                
                "{\"body\":\"$cleanBody\",\"dir\":\"$dir\",\"time\":\"$time\"}"
            }

            val hasMore = (allMessages.size - offset - limit) > 0
            val body = "{\"messages\":[$json],\"total\":${allMessages.size},\"offset\":$offset,\"limit\":$limit,\"hasMore\":$hasMore}"
            val contentLength = body.toByteArray().size
            
            // NO-CACHE headers to ensure fresh data every time
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\nCache-Control: no-store, no-cache, must-revalidate, max-age=0\r\nPragma: no-cache\r\nExpires: 0\r\nConnection: close\r\n\r\n$body"
        } catch (e: Exception) {
            Log.e(tag, "Error loading messages: ${e.message}", e)
            jsonResponse(false, "Error: ${e.message}")
        }
    }

    private fun handleSendMessage(body: String): String {
        return try {
            val params = body.split("&").associate {
                val kv = it.split("=")
                Pair(kv[0], URLDecoder.decode(kv.getOrNull(1) ?: "", "UTF-8"))
            }
            val phone = params["phone"] ?: return jsonResponse(false, "Missing phone")
            val message = params["message"] ?: return jsonResponse(false, "Missing message")

            Log.d(tag, "Sending SMS to $phone: $message")

            try {
                smsManager.sendTextMessage(phone, null, message, null, null)
                Log.d(tag, "SMS sent successfully")
                jsonResponse(true, "SMS sent")
            } catch (e: Exception) {
                Log.e(tag, "SMS error: ${e.message}")
                jsonResponse(false, e.message ?: "SMS failed")
            }
        } catch (e: Exception) {
            Log.e(tag, "Parse error: ${e.message}")
            jsonResponse(false, e.message ?: "Parse failed")
        }
    }

    private fun jsonResponse(success: Boolean, message: String): String {
        val json = "{\"success\":$success,\"message\":\"$message\"}"
        val contentLength = json.toByteArray().size
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\nCache-Control: no-store, no-cache, must-revalidate, max-age=0\r\nPragma: no-cache\r\nExpires: 0\r\nConnection: close\r\n\r\n$json"
    }

    private fun sendHtmlResponse(html: String): String {
        val contentLength = html.toByteArray().size
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: $contentLength\r\nCache-Control: no-store, no-cache, must-revalidate, max-age=0\r\nPragma: no-cache\r\nExpires: 0\r\nConnection: close\r\n\r\n$html"
    }

    private fun sendNotFound(): String {
        val body = "Not Found"
        return "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
    }

    private fun getHtml(): String {
        return """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no, maximum-scale=1">
    <title>ANMS - SMS Client</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        html, body {
            width: 100%;
            height: 100%;
            background: #0f0f0f;
            color: #e0e0e0;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 14px;
        }
        
        body {
            display: flex;
            flex-direction: column;
        }
        
        .main {
            display: flex;
            height: 100vh;
            gap: 0;
            flex: 1;
        }
        
        .sidebar {
            width: 280px;
            background: #1a1a1a;
            border-right: 1px solid #333;
            display: flex;
            flex-direction: column;
            padding: 16px;
            gap: 12px;
        }
        
        .sidebar-header {
            font-weight: 600;
            font-size: 16px;
            color: #fff;
        }
        
        .phone-input-group {
            display: flex;
            gap: 8px;
        }
        
        .phone-input-group input {
            flex: 1;
            padding: 10px 12px;
            background: #252525;
            border: 1px solid #404040;
            border-radius: 6px;
            color: #e0e0e0;
            font-size: 13px;
        }
        
        .phone-input-group input:focus {
            outline: none;
            border-color: #4a9eff;
            box-shadow: 0 0 0 2px rgba(74, 158, 255, 0.1);
        }
        
        .phone-input-group button {
            padding: 10px 16px;
            background: #4a9eff;
            border: none;
            border-radius: 6px;
            color: #fff;
            font-weight: 600;
            cursor: pointer;
            font-size: 13px;
            transition: all 0.2s;
        }
        
        .phone-input-group button:hover {
            background: #2e7dd9;
        }
        
        .phone-input-group button:active {
            transform: scale(0.95);
        }
        
        .contacts-list {
            flex: 1;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            gap: 6px;
        }
        
        .contact-item {
            padding: 12px;
            background: #252525;
            border: 1px solid #333;
            border-radius: 8px;
            cursor: pointer;
            transition: all 0.2s;
            font-size: 13px;
        }
        
        .contact-item:hover {
            background: #303030;
            border-color: #404040;
        }
        
        .contact-item.active {
            background: #4a9eff;
            border-color: #4a9eff;
            color: #fff;
            font-weight: 600;
        }
        
        .contact-number {
            font-weight: 500;
            margin-bottom: 4px;
        }
        
        .contact-count {
            font-size: 12px;
            opacity: 0.7;
        }
        
        .chat-container {
            flex: 1;
            display: flex;
            flex-direction: column;
            background: #0f0f0f;
        }
        
        .chat-header {
            padding: 16px;
            background: #1a1a1a;
            border-bottom: 1px solid #333;
            font-weight: 600;
            font-size: 15px;
            color: #fff;
            display: flex;
            align-items: center;
            justify-content: space-between;
        }
        
        .chat-header-back {
            display: none;
            background: #4a9eff;
            border: none;
            color: #fff;
            padding: 8px 12px;
            border-radius: 4px;
            cursor: pointer;
            font-size: 12px;
            font-weight: 600;
            transition: all 0.2s;
        }
        
        .chat-header-back:active {
            transform: scale(0.95);
        }
        
        .messages-area {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        
        .load-older-btn {
            padding: 8px 12px;
            background: #252525;
            border: 1px solid #333;
            border-radius: 6px;
            color: #e0e0e0;
            font-size: 12px;
            cursor: pointer;
            text-align: center;
            transition: all 0.2s;
            margin: 8px 0;
        }
        
        .load-older-btn:hover {
            background: #303030;
        }
        
        .load-older-btn:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .message-group {
            display: flex;
            margin-bottom: 4px;
            animation: fadeIn 0.3s ease-out;
        }
        
        @keyframes fadeIn {
            from {
                opacity: 0;
                transform: translateY(8px);
            }
            to {
                opacity: 1;
                transform: translateY(0);
            }
        }
        
        .message-group.in {
            justify-content: flex-start;
        }
        
        .message-group.out {
            justify-content: flex-end;
        }
        
        .message-bubble {
            max-width: 65%;
            padding: 10px 14px;
            border-radius: 12px;
            word-wrap: break-word;
            font-size: 14px;
            line-height: 1.4;
        }
        
        .message-group.in .message-bubble {
            background: #2a4a5a;
            color: #ffd700;
            border-bottom-left-radius: 4px;
        }
        
        .message-group.out .message-bubble {
            background: #1a4a2a;
            color: #4ade80;
            border-bottom-right-radius: 4px;
        }
        
        .message-time {
            font-size: 12px;
            margin-top: 4px;
            opacity: 0.6;
        }
        
        .message-group.in .message-time {
            color: #ffd700;
            text-align: left;
        }
        
        .message-group.out .message-time {
            color: #4ade80;
            text-align: right;
        }
        
        .empty-state {
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100%;
            color: #666;
            text-align: center;
            font-size: 14px;
        }
        
        .input-section {
            padding: 16px;
            background: #1a1a1a;
            border-top: 1px solid #333;
            display: flex;
            gap: 12px;
        }
        
        .input-section textarea {
            flex: 1;
            padding: 10px 12px;
            background: #252525;
            border: 1px solid #404040;
            border-radius: 6px;
            color: #e0e0e0;
            font-family: inherit;
            font-size: 14px;
            resize: none;
            max-height: 80px;
            min-height: 40px;
        }
        
        .input-section textarea:focus {
            outline: none;
            border-color: #4a9eff;
            box-shadow: 0 0 0 2px rgba(74, 158, 255, 0.1);
        }
        
        .input-section button {
            padding: 10px 20px;
            background: #4a9eff;
            border: none;
            border-radius: 6px;
            color: #fff;
            font-weight: 600;
            cursor: pointer;
            font-size: 13px;
            white-space: nowrap;
            transition: all 0.2s;
            align-self: flex-end;
        }
        
        .input-section button:hover {
            background: #2e7dd9;
        }
        
        .input-section button:active {
            transform: scale(0.95);
        }
        
        .input-section button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .status-bar {
            padding: 8px 16px;
            background: #1a1a1a;
            border-top: 1px solid #333;
            font-size: 12px;
            color: #999;
            font-weight: 500;
        }
        
        /* Scrollbar styling */
        ::-webkit-scrollbar {
            width: 6px;
        }
        
        ::-webkit-scrollbar-track {
            background: #1a1a1a;
        }
        
        ::-webkit-scrollbar-thumb {
            background: #404040;
            border-radius: 3px;
        }
        
        ::-webkit-scrollbar-thumb:hover {
            background: #505050;
        }
        
        /* ==================== DESKTOP (1024px+) ==================== */
        @media (max-width: 1024px) {
            .sidebar {
                width: 240px;
                padding: 12px;
            }
            
            .chat-header {
                padding: 12px;
                font-size: 14px;
            }
            
            .messages-area {
                padding: 12px;
            }
            
            .message-bubble {
                max-width: 75%;
                font-size: 13px;
            }
        }
        
        /* ==================== TABLET (768px - 1024px) ==================== */
        @media (max-width: 768px) {
            .main {
                flex-direction: column;
            }
            
            .sidebar {
                width: 100%;
                max-height: 40%;
                border-right: none;
                border-bottom: 1px solid #333;
                padding: 12px;
            }
            
            .chat-container {
                min-height: 60%;
                flex: 1;
            }
            
            .message-bubble {
                max-width: 85%;
                font-size: 13px;
            }
            
            .input-section {
                padding: 12px;
                gap: 8px;
            }
            
            .input-section textarea {
                font-size: 13px;
                min-height: 36px;
            }
            
            .input-section button {
                padding: 8px 16px;
                font-size: 12px;
            }
            
            .phone-input-group {
                gap: 6px;
            }
            
            .phone-input-group input,
            .phone-input-group button {
                padding: 8px 10px;
                font-size: 12px;
            }
        }
        
        /* ==================== PHONE (480px - 768px) ==================== */
        @media (max-width: 480px) {
            .main {
                flex-direction: column;
                height: 100vh;
            }
            
            /* Contact selection screen (visible by default on keitai) */
            .sidebar {
                width: 100%;
                border: none;
                padding: 8px;
                gap: 8px;
            }
            
            /* Hide sidebar when chat is active on small screens */
            .sidebar.chat-active {
                display: none;
            }
            
            .sidebar-header {
                font-size: 14px;
                font-weight: 600;
                padding: 4px 0;
            }
            
            .phone-input-group {
                display: grid;
                grid-template-columns: 1fr 1fr;
                gap: 6px;
                width: 100%;
            }
            
            .phone-input-group input {
                padding: 10px 8px;
                font-size: 12px;
                border-radius: 4px;
                grid-column: span 1;
            }
            
            .phone-input-group button {
                padding: 10px 8px;
                font-size: 12px;
                border-radius: 4px;
                grid-column: span 1;
            }
            
            .contacts-list {
                flex: none;
                max-height: 100%;
                gap: 4px;
                padding-bottom: 0;
                overflow-y: auto;
            }
            
            .contact-item {
                padding: 12px;
                font-size: 13px;
                border-radius: 6px;
                border: 1px solid #333;
                cursor: pointer;
                transition: all 0.2s;
            }
            
            .contact-item:active {
                background: #303030;
            }
            
            .contact-number {
                margin-bottom: 4px;
                font-weight: 600;
                font-size: 14px;
            }
            
            .contact-count {
                font-size: 11px;
                opacity: 0.7;
            }
            
            /* Chat container hidden by default, shown when contact selected */
            .chat-container {
                display: none;
                width: 100%;
                height: 100vh;
                flex: none;
                border: none;
            }
            
            .chat-container.chat-active {
                display: flex;
            }
            
            .chat-header {
                padding: 8px;
                font-size: 13px;
                font-weight: 600;
                gap: 8px;
            }
            
            .chat-header-back {
                display: block !important;
                padding: 6px 10px !important;
                font-size: 12px;
                flex-shrink: 0;
                background: #4a9eff;
                border: none;
                color: #fff;
                border-radius: 4px;
                cursor: pointer;
                font-weight: 600;
                transition: all 0.2s;
            }
            
            .chat-header-back:active {
                transform: scale(0.95);
            }
            
            .chat-header-title {
                flex: 1;
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
                font-size: 13px;
            }
            
            /* Compact vertical message layout for keitai */
            .messages-area {
                padding: 4px 6px;
                gap: 3px;
                font-size: 11px;
                flex-direction: column;
            }
            
            .message-group {
                display: flex;
                flex-direction: column;
                margin-bottom: 0px;
                animation: fadeIn 0.3s ease-out;
                width: fit-content;
                max-width: 85%;
            }
            
            .message-group.in {
                align-items: flex-start;
                margin-right: auto;
            }

            .message-group.out {
                align-items: flex-end;
                margin-left: auto;
            }
            
            .message-bubble {
                max-width: 100%;
                padding: 5px 7px;
                font-size: 10px;
                border-radius: 6px;
                word-break: break-word;
                line-height: 1.2;
            }
            
            .message-group.in .message-time {
                text-align: left;
            }

            .message-group.out .message-time {
                text-align: right;
            }

            .message-time {
                font-size: 8px;
                margin-top: 0px;
                display: block;
                opacity: 0.8;
            }
            
            .input-section {
                padding: 6px;
                gap: 4px;
            }

            .input-section textarea {
                padding: 6px;
                font-size: 11px;
                min-height: 28px;
                max-height: 56px;
            }

            .input-section button {
                padding: 6px 10px;
                font-size: 10px;
            }
            
            .status-bar {
                padding: 4px 6px;
                font-size: 10px;
            }
            
            .empty-state {
                font-size: 12px;
            }
        }
        
        /* ==================== ULTRA-SMALL (<320px) ==================== */
        @media (max-width: 320px) {
            .chat-header {
                padding: 8px;
            }
            
            .chat-header-back {
                padding: 6px 8px !important;
                font-size: 10px;
            }
            
            .messages-area {
                padding: 6px;
            }
            
            .message-bubble {
                padding: 6px 8px;
                font-size: 11px;
            }
            
            .input-section {
                padding: 6px;
            }
            
            .input-section textarea {
                min-height: 28px;
                font-size: 11px;
                padding: 6px;
            }
            
            .input-section button {
                padding: 6px 10px;
                font-size: 10px;
            }
            
            .contact-item {
                padding: 10px !important;
            }
            
            .contact-number {
                font-size: 12px !important;
            }
        }
    </style>
</head>
<body>
<div class="main">
    <!-- CONTACT SELECTION SCREEN (visible on keitai by default) -->
    <div class="sidebar" id="sidebar">
        <div class="sidebar-header">📱 Chats</div>
        <div class="phone-input-group">
            <input type="tel" id="phone" placeholder="Phone number" maxlength="20">
            <button onclick="addContact()">Add</button>
        </div>
        <div class="contacts-list" id="contacts"></div>
    </div>
    
    <!-- CHAT SCREEN (hidden on keitai until contact selected) -->
    <div class="chat-container" id="chatContainer">
        <div class="chat-header">
            <button class="chat-header-back" id="backBtn" onclick="goBack()">← Back</button>
            <div class="chat-header-title" id="chatHeader">Select a contact</div>
        </div>
        <div class="messages-area" id="messages"><div class="empty-state">👈 Select a contact to start chatting</div></div>
        <div class="input-section">
            <textarea id="msg" placeholder="Type message..." disabled></textarea>
            <button onclick="send()" id="sendBtn" disabled>Send</button>
        </div>
        <div class="status-bar" id="status">✓ Ready</div>
    </div>
</div>

<script>
let ws, active, chats = {}, contacts = [], pollInterval, screenWidth = window.innerWidth;
let deviceMode = 'desktop'; // 'desktop', 'tablet', 'phone', 'keitai'
let messageState = {}; // Track pagination state per contact: { phone: { total, offset, loading, lastSeenCount } }
const MESSAGES_PER_LOAD = 8;

function detectScreenSize() {
    screenWidth = window.innerWidth;
    
    if (screenWidth < 480) {
        deviceMode = 'keitai';
    } else if (screenWidth < 768) {
        deviceMode = 'phone';
    } else if (screenWidth < 1024) {
        deviceMode = 'tablet';
    } else {
        deviceMode = 'desktop';
    }
    
    console.log('Screen:', screenWidth + 'px, Mode:', deviceMode);
    applyDeviceLayout();
}

function applyDeviceLayout() {
    const sidebar = document.getElementById('sidebar');
    const chatContainer = document.getElementById('chatContainer');
    
    if (deviceMode === 'keitai') {
        // Keitai: Show contacts by default, chat replaces when selected
        if (active) {
            sidebar.classList.add('chat-active');
            chatContainer.classList.add('chat-active');
        } else {
            sidebar.classList.remove('chat-active');
            chatContainer.classList.remove('chat-active');
        }
    } else {
        // Desktop/Tablet/Phone: Show both side by side (or stacked)
        sidebar.classList.remove('chat-active');
        chatContainer.classList.remove('chat-active');
    }
}

function init() {
    detectScreenSize();
    window.addEventListener('resize', () => {
        detectScreenSize();
    });
    
    contacts = JSON.parse(localStorage.getItem('anms_contacts') || '[]');
    chats = JSON.parse(localStorage.getItem('anms_chats') || '{}');
    renderContacts();
    
    connectWS();
}

function goBack() {
    // Keitai: Return to contact selection
    active = null;
    document.getElementById('msg').disabled = true;
    document.getElementById('sendBtn').disabled = true;
    document.getElementById('messages').innerHTML = '<div class="empty-state">👈 Select a contact to start chatting</div>';
    clearInterval(pollInterval);
    
    applyDeviceLayout();
    renderContacts();
}

function connectWS() {
    const host = window.location.hostname;
    try {
        ws = new WebSocket('ws://' + host + ':8765');
        ws.onopen = () => updateStatus('✓ Connected');
        ws.onmessage = e => {
            console.log('WS Message:', e.data);
            if (e.data.startsWith('INCOMING_SMS|')) {
                const [_, phone, ...msgParts] = e.data.split('|');
                const text = msgParts.join('|');
                console.log('New SMS from:', phone, text);
                pollChat(phone);
            }
        };
        ws.onclose = () => { updateStatus('⚠ Reconnecting...'); setTimeout(connectWS, 3000); };
        ws.onerror = (e) => { console.error('WS Error:', e); updateStatus('✗ Error'); };
    } catch (e) {
        console.error('WS Connect Error:', e);
        updateStatus('✗ WS Error');
    }
}

function addContact() {
    const p = document.getElementById('phone').value.trim();
    if (!p) { alert('Enter phone number'); return; }
    if (contacts.includes(p)) { alert('Already in contacts'); return; }
    contacts.push(p);
    localStorage.setItem('anms_contacts', JSON.stringify(contacts));
    document.getElementById('phone').value = '';
    renderContacts();
    selectContact(p);
}

function selectContact(p) {
    console.log('Selecting contact:', p);
    active = p;
    document.getElementById('msg').disabled = false;
    document.getElementById('sendBtn').disabled = false;
    document.getElementById('chatHeader').textContent = '📱 ' + p;
    clearInterval(pollInterval);
    
    // Initialize pagination state for this contact
    if (!messageState[p]) {
        messageState[p] = { total: 0, offset: 0, loading: false, lastSeenCount: 0 };
    }
    
    applyDeviceLayout();
    renderContacts();
    
    loadChat(p).then(() => {
        renderMsgs();
        startPolling();
    });
}

function loadChat(phone) {
    console.log('Loading latest messages for:', phone);
    updateStatus('⏳ Loading...');
    
    // Always fetch the LATEST messages (offset=0)
    // Add timestamp to bust cache
    const ts = Date.now();
    return fetch('http://' + window.location.hostname + ':8080/api/messages/' + encodeURIComponent(phone) + '?offset=0&limit=' + MESSAGES_PER_LOAD + '&_t=' + ts, {
        method: 'GET',
        headers: {
            'Cache-Control': 'no-cache',
            'Pragma': 'no-cache'
        }
    })
        .then(r => {
            console.log('Response status:', r.status);
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(data => {
            console.log('Loaded', data.messages.length, 'messages (total:', data.total + ')');
            // Store the new messages
            chats[phone] = data.messages;
            
            // Initialize state
            messageState[phone] = {
                total: data.total,
                offset: MESSAGES_PER_LOAD,
                loading: false,
                lastSeenCount: data.messages.length  // Track how many we've displayed
            };
            
            localStorage.setItem('anms_chats', JSON.stringify(chats));
            updateStatus('✓ Ready');
            return data;
        })
        .catch(e => {
            console.error('Load error:', e);
            updateStatus('✗ Error loading');
        });
}

function pollChat(phone) {
    // Poll for new messages
    console.log('Polling for:', phone);
    
    // Add timestamp to bust cache
    const ts = Date.now();
    fetch('http://' + window.location.hostname + ':8080/api/messages/' + encodeURIComponent(phone) + '?offset=0&limit=' + MESSAGES_PER_LOAD + '&_t=' + ts, {
        method: 'GET',
        headers: {
            'Cache-Control': 'no-cache',
            'Pragma': 'no-cache'
        }
    })
        .then(r => {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(data => {
            console.log('Poll: Got', data.messages.length, 'messages (total:', data.total + ')');
            
            const state = messageState[phone];
            if (!state) return;
            
            // Check if there are NEW messages
            const oldMessages = chats[phone] || [];
            const newMessages = data.messages;
            
            console.log('Old count:', oldMessages.length, 'New count:', newMessages.length);
            
            // Only update if total count changed
            if (newMessages.length > oldMessages.length) {
                console.log('New messages detected!');
                chats[phone] = newMessages;
                state.total = data.total;
                localStorage.setItem('anms_chats', JSON.stringify(chats));
                
                // If this is the active chat, append new messages
                if (phone === active) {
                    appendNewMessages();
                }
            }
        })
        .catch(e => console.error('Poll error:', e));
}

function appendNewMessages() {
    // Only append NEW messages, don't rebuild entire DOM
    if (!active) return;
    
    const state = messageState[active];
    const msgs = chats[active] || [];
    const newCount = msgs.length;
    const prevCount = state.lastSeenCount || 0;
    
    console.log('appendNewMessages: prevCount=' + prevCount + ', newCount=' + newCount);
    
    if (newCount <= prevCount) return; // No new messages
    
    // Add only new messages
    const area = document.getElementById('messages');
    const newMessages = msgs.slice(prevCount);
    
    console.log('Appending', newMessages.length, 'new messages');
    
    const html = newMessages.map(m => {
        const dirClass = m.dir === 'in' ? 'in' : 'out';
        return '<div class="message-group ' + dirClass + '"><div><div class="message-bubble">' + escapeHtml(m.body) + '</div><div class="message-time">' + m.time + '</div></div></div>';
    }).join('');
    
    // Append to DOM instead of replacing
    const tempDiv = document.createElement('div');
    tempDiv.innerHTML = html;
    while (tempDiv.firstChild) {
        area.appendChild(tempDiv.firstChild);
    }
    
    // Update count
    state.lastSeenCount = newCount;
    
    // Auto-scroll only if user is at bottom
    if (isScrolledToBottom()) {
        area.scrollTop = area.scrollHeight;
    }
}

function loadOlderMessages() {
    if (!active) return;
    const state = messageState[active];
    if (!state || state.loading || state.offset >= state.total) return;
    
    state.loading = true;
    const btn = document.getElementById('loadOlderBtn');
    if (btn) btn.disabled = true;
    
    console.log('Loading older messages: offset=' + state.offset);
    
    // Add timestamp to bust cache
    const ts = Date.now();
    fetch('http://' + window.location.hostname + ':8080/api/messages/' + encodeURIComponent(active) + '?offset=' + state.offset + '&limit=' + MESSAGES_PER_LOAD + '&_t=' + ts, {
        method: 'GET',
        headers: {
            'Cache-Control': 'no-cache',
            'Pragma': 'no-cache'
        }
    })
        .then(r => r.json())
        .then(data => {
            console.log('Loaded', data.messages.length, 'older messages');
            // Prepend older messages to the beginning
            chats[active] = data.messages.concat(chats[active]);
            state.offset += MESSAGES_PER_LOAD;
            state.loading = false;
            state.lastSeenCount = chats[active].length;
            localStorage.setItem('anms_chats', JSON.stringify(chats));
            renderMsgs();
        })
        .catch(e => {
            console.error('Error loading older messages:', e);
            state.loading = false;
            if (btn) btn.disabled = false;
        });
}

function isScrolledToBottom() {
    const area = document.getElementById('messages');
    // Check if user is within 50px of the bottom
    return area.scrollHeight - area.scrollTop - area.clientHeight < 50;
}

function startPolling() {
    console.log('Started polling for:', active);
    pollInterval = setInterval(() => {
        if (active) {
            pollChat(active);
        }
    }, 1000);
}

function send() {
    if (!active) { alert('Select a contact'); return; }
    const text = document.getElementById('msg').value.trim();
    if (!text) return;
    
    console.log('Sending to:', active);
    document.getElementById('sendBtn').disabled = true;
    document.getElementById('msg').value = '';
    updateStatus('⏳ Sending...');
    
    fetch('http://' + window.location.hostname + ':8080/send', {
        method: 'POST',
        body: 'phone=' + encodeURIComponent(active) + '&message=' + encodeURIComponent(text)
    })
    .then(r => r.json())
    .then(data => {
        console.log('Send response:', data);
        document.getElementById('sendBtn').disabled = false;
        if (data.success) {
            updateStatus('✓ Sent');
            setTimeout(() => pollChat(active), 1000);
        } else {
            updateStatus('✗ Send failed: ' + data.message);
        }
    })
    .catch(e => {
        console.error('Send error:', e);
        updateStatus('✗ Error');
        document.getElementById('sendBtn').disabled = false;
    });
}

function renderContacts() {
    const html = contacts.map(p => {
        const cnt = (chats[p] || []).length;
        const cls = active === p ? 'active' : '';
        return '<div class="contact-item ' + cls + '" onclick="selectContact(\'' + p.replace(/'/g, "\\\\'") + '\'"><div class="contact-number">' + escapeHtml(p) + '</div><div class="contact-count">' + cnt + ' messages</div></div>';
    }).join('');
    document.getElementById('contacts').innerHTML = html || '<div style="color: #666; text-align: center; padding: 20px; font-size: 12px;">No contacts yet</div>';
}

function renderMsgs() {
    // Full rebuild (only on initial load or when loading older messages)
    const msgs = chats[active] || [];
    if (!msgs.length) {
        document.getElementById('messages').innerHTML = '<div class="empty-state">No messages yet</div>';
        return;
    }
    
    const state = messageState[active] || { total: msgs.length, offset: 0, lastSeenCount: 0 };
    let html = '';
    
    // Show "Load older" button if there are more messages
    if (state.offset < state.total) {
        html += '<button id="loadOlderBtn" class="load-older-btn" onclick="loadOlderMessages()">↑ Load older messages</button>';
    }
    
    html += msgs.map(m => {
        const dirClass = m.dir === 'in' ? 'in' : 'out';
        return '<div class="message-group ' + dirClass + '"><div><div class="message-bubble">' + escapeHtml(m.body) + '</div><div class="message-time">' + m.time + '</div></div></div>';
    }).join('');
    
    const area = document.getElementById('messages');
    area.innerHTML = html;
    state.lastSeenCount = msgs.length;
    area.scrollTop = area.scrollHeight;
}

function updateStatus(s) {
    document.getElementById('status').textContent = s;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

document.getElementById('msg').addEventListener('keypress', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});

document.getElementById('phone').addEventListener('keypress', e => {
    if (e.key === 'Enter') addContact();
});

init();
</script>
</body>
</html>"""
    }

    fun stopServer() {
        thread {
            try {
                isRunning = false
                serverSocket?.close()
                Log.d(tag, "HTTP server stopped")
            } catch (e: Exception) {
                Log.e(tag, "Error stopping server", e)
            }
        }
    }
}
