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
            Log.d(tag, "Got ${allMessages.size} total messages for phone $phone")

            // Pagination from END of conversation (most recent messages)
            // offset=0 means get the LAST 'limit' messages (most recent)
            // offset=8 means skip last 8, get next 8 older, etc.
            val startIndex = maxOf(0, allMessages.size - limit - offset)
            val endIndex = allMessages.size - offset
            
            val paginatedMessages = if (startIndex < endIndex && endIndex > 0) {
                allMessages.subList(startIndex, endIndex)
            } else {
                emptyList()
            }

            Log.d(tag, "Returning ${paginatedMessages.size} paginated messages")

            val json = paginatedMessages.joinToString(",") { msg ->
                val dir = if (msg.type == 1) "in" else "out"
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(msg.timestamp)
                val cleanBody = msg.body.replace("\\", " ").replace("\"", "\\\"")
                """{"body":"$cleanBody","dir":"$dir","time":"$time"}"""
            }

            val hasMore = (allMessages.size - offset - limit) > 0
            val body = """{"messages":[$json],"total":${allMessages.size},"offset":$offset,"limit":$limit,"hasMore":$hasMore}"""
            val contentLength = body.toByteArray().size
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\nConnection: close\r\n\r\n$body"
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
        val json = """{"success":$success,"message":"$message"}"""
        val contentLength = json.toByteArray().size
        return "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\nConnection: close\r\n\r\n$json"
    }

    private fun sendHtmlResponse(html: String): String {
        val contentLength = html.toByteArray().size
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: $contentLength\r\nConnection: close\r\n\r\n$html"
    }

    private fun sendNotFound(): String {
        val body = "Not Found"
        return "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
    }

    private fun getHtml(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no, viewport-fit=cover">
    <title>ANMS - SMS Client</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
            -webkit-user-select: none;
            user-select: none;
            -webkit-touch-callout: none;
            -webkit-tap-highlight-color: transparent;
        }
        
        html, body {
            width: 100%;
            height: 100%;
            background: #0f0f0f;
            color: #e0e0e0;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 15px;
            position: fixed;
            top: 0;
            left: 0;
            overflow: hidden;
        }
        
        body {
            display: flex;
            flex-direction: column;
        }
        
        .main {
            display: flex;
            width: 100vw;
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
            flex-shrink: 0;
        }
        
        .phone-input-group {
            display: flex;
            gap: 8px;
            flex-shrink: 0;
        }
        
        .phone-input-group input,
        .phone-input-group button {
            padding: 10px 12px;
            background: #252525;
            border: 1px solid #404040;
            border-radius: 6px;
            color: #e0e0e0;
            font-size: 14px;
            -webkit-appearance: none;
            appearance: none;
        }
        
        .phone-input-group input {
            flex: 1;
        }
        
        .phone-input-group input:focus {
            outline: none;
            border-color: #4a9eff;
            box-shadow: 0 0 0 2px rgba(74, 158, 255, 0.1);
        }
        
        .phone-input-group button {
            background: #4a9eff;
            color: #fff;
            font-weight: 600;
            cursor: pointer;
            font-size: 13px;
            transition: all 0.2s;
            padding: 10px 16px;
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
            -webkit-overflow-scrolling: touch;
        }
        
        .contact-item {
            padding: 12px;
            background: #252525;
            border: 1px solid #333;
            border-radius: 8px;
            cursor: pointer;
            transition: all 0.2s;
            font-size: 14px;
            -webkit-appearance: none;
            appearance: none;
        }
        
        .contact-item:active {
            background: #303030;
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
            flex-shrink: 0;
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
            -webkit-appearance: none;
            appearance: none;
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
            -webkit-overflow-scrolling: touch;
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
            -webkit-appearance: none;
            appearance: none;
        }
        
        .load-older-btn:active {
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
            padding: 12px 16px;
            border-radius: 12px;
            word-wrap: break-word;
            overflow-wrap: break-word;
            font-size: 16px;
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
            font-size: 13px;
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
            font-size: 15px;
        }
        
        .input-section {
            padding: 16px;
            background: #1a1a1a;
            border-top: 1px solid #333;
            display: flex;
            gap: 12px;
            flex-shrink: 0;
        }
        
        .input-section textarea {
            flex: 1;
            padding: 10px 12px;
            background: #252525;
            border: 1px solid #404040;
            border-radius: 6px;
            color: #e0e0e0;
            font-family: inherit;
            font-size: 15px;
            resize: none;
            max-height: 80px;
            min-height: 40px;
            -webkit-appearance: none;
            appearance: none;
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
            -webkit-appearance: none;
            appearance: none;
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
            font-size: 13px;
            color: #999;
            font-weight: 500;
            flex-shrink: 0;
        }
        
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
        
        /* ==================== DESKTOP (1024px+) ==================== */
        @media (max-width: 1024px) {
            .sidebar { width: 240px; padding: 12px; }
            .chat-header { padding: 12px; font-size: 14px; }
            .messages-area { padding: 12px; }
            .message-bubble { max-width: 75%; font-size: 15px; }
        }
        
        /* ==================== TABLET (768px - 1024px) ==================== */
        @media (max-width: 768px) {
            .main { flex-direction: column; }
            .sidebar { width: 100%; max-height: 40%; border-right: none; border-bottom: 1px solid #333; padding: 12px; }
            .chat-container { min-height: 60%; flex: 1; }
            .message-bubble { max-width: 85%; font-size: 15px; }
            .input-section { padding: 12px; gap: 8px; }
            .input-section textarea { font-size: 14px; min-height: 36px; }
            .input-section button { padding: 8px 16px; font-size: 12px; }
            .phone-input-group { gap: 6px; }
            .phone-input-group input, .phone-input-group button { padding: 8px 10px; font-size: 13px; }
        }
        
        /* ==================== KEITAI / MOBILE (<480px) ==================== */
        @media (max-width: 480px) {
            html, body { font-size: 14px; }
            .main { flex-direction: column; width: 100vw; height: 100vh; }
            
            .sidebar {
                width: 100%;
                border: none;
                padding: 6px;
                gap: 4px;
                flex: 0 0 auto;
                max-height: none;
            }
            
            .sidebar.chat-active { display: none; }
            
            .sidebar-header { font-size: 13px; padding: 2px 0; }
            
            /* FULL WIDTH INPUT - CRITICAL FOR KEITAI */
            .phone-input-group {
                flex-direction: column;
                width: 100%;
                gap: 4px;
            }
            
            .phone-input-group input {
                width: 100%;
                min-height: 44px;
                padding: 10px 8px;
                font-size: 13px;
            }
            
            .phone-input-group button {
                width: 100%;
                min-height: 44px;
                padding: 10px 8px;
                font-size: 12px;
            }
            
            .contacts-list {
                flex: 1;
                min-height: 100px;
                gap: 4px;
                overflow-y: auto;
            }
            
            .contact-item {
                padding: 10px;
                font-size: 12px;
                min-height: 44px;
                flex-shrink: 0;
            }
            
            .contact-number { margin-bottom: 2px; font-weight: 600; font-size: 13px; }
            .contact-count { font-size: 11px; }
            
            .chat-container {
                display: none;
                width: 100%;
                height: 100vh;
                flex: none;
            }
            
            .chat-container.chat-active { display: flex; }
            
            .chat-header { padding: 8px; font-size: 13px; gap: 6px; }
            
            .chat-header-back {
                display: block !important;
                padding: 6px 8px !important;
                font-size: 11px;
                min-height: 44px;
                display: flex;
                align-items: center;
            }
            
            .chat-header-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
            
            .messages-area { padding: 8px; gap: 5px; font-size: 13px; flex: 1; }
            
            .message-group { max-width: 90%; }
            .message-bubble { max-width: 100%; padding: 8px 10px; font-size: 14px; border-radius: 5px; line-height: 1.4; }
            .message-time { font-size: 11px; }
            
            .input-section { padding: 6px; gap: 4px; flex-direction: column; }
            .input-section textarea { padding: 8px; font-size: 13px; min-height: 32px; width: 100%; }
            .input-section button { padding: 8px 12px; font-size: 12px; width: 100%; min-height: 44px; }
            
            .status-bar { padding: 4px 6px; font-size: 11px; }
            .empty-state { font-size: 13px; }
        }
    </style>
</head>
<body>
<div class="main">
    <div class="sidebar" id="sidebar">
        <div class="sidebar-header">📱 Chats</div>
        <div class="phone-input-group">
            <input type="tel" id="phone" placeholder="Phone #" maxlength="20" autocomplete="tel">
            <button type="button" onclick="addContact()">Add</button>
        </div>
        <div class="contacts-list" id="contacts"></div>
    </div>
    
    <div class="chat-container" id="chatContainer">
        <div class="chat-header">
            <button class="chat-header-back" id="backBtn" type="button" onclick="goBack()">← Back</button>
            <div class="chat-header-title" id="chatHeader">Select a contact</div>
        </div>
        <div class="messages-area" id="messages"><div class="empty-state">👈 Select a contact to start chatting</div></div>
        <div class="input-section">
            <textarea id="msg" placeholder="Type message..." disabled autocomplete="off"></textarea>
            <button type="button" onclick="send()" id="sendBtn" disabled>Send</button>
        </div>
        <div class="status-bar" id="status">✓ Ready</div>
    </div>
</div>

<script>
let ws, active, chats = {}, contacts = [], pollInterval, screenWidth = window.innerWidth;
let deviceMode = 'desktop';
let messageState = {};
const MESSAGES_PER_LOAD = 8;

window.addEventListener('DOMContentLoaded', () => {
    console.log('=== PAGE LOADED ===' );
    console.log('innerWidth:', window.innerWidth);
    console.log('outerWidth:', window.outerWidth);
    console.log('screen.width:', screen.width);
    console.log('devicePixelRatio:', window.devicePixelRatio);
    init();
});

function detectScreenSize() {
    const w = window.innerWidth;
    console.log('[detectScreenSize] width:', w);
    
    if (w < 480) {
        deviceMode = 'keitai';
    } else if (w < 768) {
        deviceMode = 'phone';
    } else if (w < 1024) {
        deviceMode = 'tablet';
    } else {
        deviceMode = 'desktop';
    }
    
    console.log('[detectScreenSize] MODE:', deviceMode, 'WIDTH:', w);
    applyDeviceLayout();
}

function applyDeviceLayout() {
    const sidebar = document.getElementById('sidebar');
    const chatContainer = document.getElementById('chatContainer');
    console.log('[applyLayout] deviceMode=' + deviceMode + ', active=' + active);
    
    if (deviceMode === 'keitai') {
        if (active) {
            sidebar.classList.add('chat-active');
            chatContainer.classList.add('chat-active');
            console.log('[applyLayout] showing chat');
        } else {
            sidebar.classList.remove('chat-active');
            chatContainer.classList.remove('chat-active');
            console.log('[applyLayout] showing sidebar');
        }
    } else {
        sidebar.classList.remove('chat-active');
        chatContainer.classList.remove('chat-active');
    }
}

function init() {
    detectScreenSize();
    window.addEventListener('resize', () => detectScreenSize());
    
    contacts = JSON.parse(localStorage.getItem('anms_contacts') || '[]');
    chats = JSON.parse(localStorage.getItem('anms_chats') || '{}');
    renderContacts();
    
    connectWS();
}

function goBack() {
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
            if (e.data.startsWith('INCOMING_SMS|')) {
                const [_, phone, ...msgParts] = e.data.split('|');
                const text = msgParts.join('|');
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
    active = p;
    document.getElementById('msg').disabled = false;
    document.getElementById('sendBtn').disabled = false;
    document.getElementById('chatHeader').textContent = '📱 ' + p;
    clearInterval(pollInterval);
    
    if (!messageState[p]) {
        messageState[p] = { total: 0, offset: 0, loading: false, lastTotal: 0 };
    }
    
    applyDeviceLayout();
    renderContacts();
    
    loadChat(p).then(() => {
        renderMsgs();
        startPolling();
    });
}

function loadChat(phone) {
    console.log('Loading messages for:', phone);
    updateStatus('⏳ Loading...');
    
    return fetch('http://' + window.location.hostname + ':8080/api/messages/' + encodeURIComponent(phone) + '?offset=0&limit=' + MESSAGES_PER_LOAD)
        .then(r => {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(data => {
            chats[phone] = data.messages;
            messageState[phone] = {
                total: data.total,
                offset: MESSAGES_PER_LOAD,
                loading: false,
                lastTotal: data.total
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
    fetch('http://' + window.location.hostname + ':8080/api/messages/' + encodeURIComponent(phone) + '?offset=0&limit=' + MESSAGES_PER_LOAD)
        .then(r => r.json())
        .then(data => {
            const state = messageState[phone];
            if (!state) return;
            
            if (data.total > state.lastTotal) {
                chats[phone] = data.messages;
                state.lastTotal = data.total;
                state.total = data.total;
                
                localStorage.setItem('anms_chats', JSON.stringify(chats));
                
                if (phone === active) {
                    renderMsgs();
                    const area = document.getElementById('messages');
                    area.scrollTop = area.scrollHeight;
                }
                
                renderContacts();
            }
        })
        .catch(e => console.error('Poll error:', e));
}

function loadOlderMessages() {
    if (!active) return;
    const state = messageState[active];
    if (!state || state.loading || state.offset >= state.total) return;
    
    state.loading = true;
    const btn = document.getElementById('loadOlderBtn');
    if (btn) btn.disabled = true;
    
    fetch('http://' + window.location.hostname + ':8080/api/messages/' + encodeURIComponent(active) + '?offset=' + state.offset + '&limit=' + MESSAGES_PER_LOAD)
        .then(r => r.json())
        .then(data => {
            chats[active] = data.messages.concat(chats[active]);
            state.offset += MESSAGES_PER_LOAD;
            state.loading = false;
            localStorage.setItem('anms_chats', JSON.stringify(chats));
            renderMsgs();
        })
        .catch(e => {
            console.error('Error loading older messages:', e);
            state.loading = false;
            if (btn) btn.disabled = false;
        });
}

function startPolling() {
    pollInterval = setInterval(() => {
        if (active) { pollChat(active); }
    }, 1000);
}

function send() {
    if (!active) { alert('Select a contact'); return; }
    const text = document.getElementById('msg').value.trim();
    if (!text) return;
    
    document.getElementById('sendBtn').disabled = true;
    document.getElementById('msg').value = '';
    updateStatus('⏳ Sending...');
    
    fetch('http://' + window.location.hostname + ':8080/send', {
        method: 'POST',
        body: 'phone=' + encodeURIComponent(active) + '&message=' + encodeURIComponent(text)
    })
    .then(r => r.json())
    .then(data => {
        document.getElementById('sendBtn').disabled = false;
        if (data.success) {
            updateStatus('✓ Sent');
            setTimeout(() => pollChat(active), 500);
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
    const html = contacts.map((p, idx) => {
        const cnt = (chats[p] || []).length;
        const cls = active === p ? 'active' : '';
        return '<div class="contact-item ' + cls + '" onclick="selectContactByIdx(' + idx + ')"><div class="contact-number">' + escapeHtml(p) + '</div><div class="contact-count">' + cnt + ' msgs</div></div>';
    }).join('');
    document.getElementById('contacts').innerHTML = html || '';
}

window.selectContactByIdx = function(idx) {
    if (contacts[idx]) { selectContact(contacts[idx]); }
}

function renderMsgs() {
    const msgs = chats[active] || [];
    if (!msgs.length) {
        document.getElementById('messages').innerHTML = '<div class="empty-state">No messages yet</div>';
        return;
    }
    
    const state = messageState[active] || { total: msgs.length, offset: 0 };
    let html = '';
    
    if (state.offset < state.total) {
        html += '<button id="loadOlderBtn" class="load-older-btn" type="button" onclick="loadOlderMessages()">↑ Load older messages</button>';
    }
    
    html += msgs.map(m => {
        const dirClass = m.dir === 'in' ? 'in' : 'out';
        return '<div class="message-group ' + dirClass + '"><div><div class="message-bubble">' + escapeHtml(m.body) + '</div><div class="message-time">' + m.time + '</div></div></div>';
    }).join('');
    
    const area = document.getElementById('messages');
    area.innerHTML = html;
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
