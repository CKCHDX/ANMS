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
                path.startsWith("/api/chat/") -> {
                    val phone = URLDecoder.decode(path.substring(10), "UTF-8")
                    Log.d(tag, "=== CHAT REQUEST ===")
                    Log.d(tag, "Raw phone: $phone")
                    handleGetChat(phone)
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

    private fun handleGetChat(phone: String): String {
        return try {
            Log.d(tag, "Loading chat for: $phone")
            val messages = smsDb.getConversation(phone, 500)
            Log.d(tag, "Got ${messages.size} messages")

            val json = messages.joinToString(",") { msg ->
                val dir = if (msg.type == 1) "in" else "out"
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(msg.timestamp)
                val cleanBody = msg.body.replace("\\", " ").replace("\"", "\\\"")
                """{"body":"$cleanBody","dir":"$dir","time":"$time"}"""
            }

            val body = "[$json]"
            val contentLength = body.toByteArray().size
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $contentLength\r\nConnection: close\r\n\r\n$body"
        } catch (e: Exception) {
            Log.e(tag, "Error loading chat: ${e.message}", e)
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
        
        .main {
            display: flex;
            height: 100vh;
            gap: 0;
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
        }
        
        .messages-area {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 8px;
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
        
        @media (max-width: 768px) {
            .main {
                flex-direction: column;
            }
            
            .sidebar {
                width: 100%;
                max-height: 35%;
                border-right: none;
                border-bottom: 1px solid #333;
            }
            
            .chat-container {
                min-height: 65%;
            }
            
            .message-bubble {
                max-width: 80%;
            }
        }
    </style>
</head>
<body>
<div class="main">
    <div class="sidebar">
        <div class="sidebar-header">📱 Chats</div>
        <div class="phone-input-group">
            <input type="tel" id="phone" placeholder="+1234567890" maxlength="20">
            <button onclick="addContact()">Add</button>
        </div>
        <div class="contacts-list" id="contacts"></div>
    </div>
    
    <div class="chat-container">
        <div class="chat-header" id="chatHeader">Select a contact</div>
        <div class="messages-area" id="messages"><div class="empty-state">👈 Select a contact to start chatting</div></div>
        <div class="input-section">
            <textarea id="msg" placeholder="Type a message..." disabled></textarea>
            <button onclick="send()" id="sendBtn" disabled>Send</button>
        </div>
        <div class="status-bar" id="status">✓ Ready</div>
    </div>
</div>

<script>
let ws, active, chats = {}, contacts = [], pollInterval;

function init() {
    contacts = JSON.parse(localStorage.getItem('anms_contacts') || '[]');
    chats = JSON.parse(localStorage.getItem('anms_chats') || '{}');
    renderContacts();
    connectWS();
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
                loadChat(phone).then(() => {
                    if (active === phone) renderMsgs();
                });
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
    renderContacts();
    document.getElementById('msg').disabled = false;
    document.getElementById('sendBtn').disabled = false;
    document.getElementById('chatHeader').textContent = '📱 ' + p;
    clearInterval(pollInterval);
    loadChat(p).then(() => {
        renderMsgs();
        startPolling();
    });
}

function loadChat(phone) {
    console.log('Loading chat for:', phone);
    updateStatus('⏳ Loading...');
    return fetch('http://' + window.location.hostname + ':8080/api/chat/' + encodeURIComponent(phone))
        .then(r => {
            console.log('Response status:', r.status);
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(data => {
            console.log('Loaded', data.length, 'messages');
            chats[phone] = data;
            localStorage.setItem('anms_chats', JSON.stringify(chats));
            updateStatus('✓ Ready');
            return data;
        })
        .catch(e => {
            console.error('Load error:', e);
            updateStatus('✗ Error loading');
        });
}

function startPolling() {
    console.log('Started polling for:', active);
    pollInterval = setInterval(() => {
        if (active) {
            loadChat(active).then(() => {
                const msgCount = (chats[active] || []).length;
                console.log('Poll update: ' + msgCount + ' messages');
                renderMsgs();
            }).catch(e => console.error('Poll error:', e));
        }
    }, 2000);
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
            setTimeout(() => loadChat(active).then(() => renderMsgs()), 1000);
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
        return '<div class="contact-item ' + cls + '" onclick="selectContact(\'' + p.replace(/'/g, "\\\\'") + '\')\'><div class="contact-number">' + escapeHtml(p) + '</div><div class="contact-count">' + cnt + ' messages</div></div>';
    }).join('');
    document.getElementById('contacts').innerHTML = html || '<div style="color: #666; text-align: center; padding: 20px; font-size: 13px;">No contacts yet</div>';
}

function renderMsgs() {
    const msgs = chats[active] || [];
    if (!msgs.length) {
        document.getElementById('messages').innerHTML = '<div class="empty-state">No messages yet</div>';
        return;
    }
    
    const html = msgs.map(m => {
        const dirClass = m.dir === 'in' ? 'in' : 'out';
        const sender = m.dir === 'in' ? 'target' : 'you';
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
