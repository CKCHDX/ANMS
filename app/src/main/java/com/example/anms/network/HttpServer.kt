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
    <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no, maximum-scale=1, viewport-fit=cover">
    <title>ANMS - SMS Client</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
            -webkit-user-select: none;
            user-select: none;
        }
        
        html, body {
            width: 100%;
            height: 100%;
            background: #0f0f0f;
            color: #e0e0e0;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            font-size: 12px;
            line-height: 1.4;
            overflow: hidden;
        }
        
        body {
            display: flex;
            flex-direction: column;
        }
        
        /* ==================== SCREEN: CONTACT LIST ==================== */
        .screen-contacts {
            display: flex;
            flex-direction: column;
            width: 100%;
            height: 100vh;
            background: #0f0f0f;
        }
        
        .screen-contacts.hidden {
            display: none;
        }
        
        .header {
            background: #1a1a1a;
            border-bottom: 1px solid #333;
            padding: 10px 12px;
            font-weight: 600;
            font-size: 13px;
            color: #fff;
        }
        
        .input-group {
            display: flex;
            flex-direction: column;
            gap: 8px;
            padding: 10px 12px;
            background: #0f0f0f;
            border-bottom: 1px solid #333;
        }
        
        .input-group input {
            padding: 8px 10px;
            background: #252525;
            border: 1px solid #404040;
            border-radius: 4px;
            color: #e0e0e0;
            font-size: 12px;
            font-family: inherit;
        }
        
        .input-group input:focus {
            outline: none;
            border-color: #4a9eff;
            box-shadow: 0 0 0 2px rgba(74, 158, 255, 0.15);
        }
        
        .input-group input::placeholder {
            color: #666;
        }
        
        .button-group {
            display: flex;
            gap: 8px;
        }
        
        .button-group button {
            flex: 1;
            padding: 8px 12px;
            background: #4a9eff;
            border: none;
            border-radius: 4px;
            color: #fff;
            font-weight: 600;
            font-size: 11px;
            cursor: pointer;
            transition: all 0.15s;
        }
        
        .button-group button:active {
            transform: scale(0.95);
            background: #2e7dd9;
        }
        
        .button-group button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .contacts-list {
            flex: 1;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            gap: 6px;
            padding: 8px 10px;
        }
        
        .contact-item {
            padding: 10px 12px;
            background: #252525;
            border: 1px solid #333;
            border-radius: 6px;
            cursor: pointer;
            transition: all 0.15s;
            display: flex;
            flex-direction: column;
            gap: 3px;
        }
        
        .contact-item:active {
            background: #303030;
            border-color: #4a9eff;
        }
        
        .contact-name {
            font-weight: 600;
            font-size: 12px;
            color: #fff;
        }
        
        .contact-phone {
            font-size: 11px;
            color: #999;
        }
        
        .contact-count {
            font-size: 10px;
            color: #666;
        }
        
        .empty-message {
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100%;
            color: #666;
            text-align: center;
            font-size: 12px;
        }
        
        /* ==================== SCREEN: CHAT ==================== */
        .screen-chat {
            display: flex;
            flex-direction: column;
            width: 100%;
            height: 100vh;
            background: #0f0f0f;
        }
        
        .screen-chat.hidden {
            display: none;
        }
        
        .chat-header {
            background: #1a1a1a;
            border-bottom: 1px solid #333;
            padding: 8px 12px;
            font-weight: 600;
            font-size: 12px;
            color: #fff;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        
        .messages-area {
            flex: 1;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            gap: 4px;
            padding: 8px 10px;
            background: #0f0f0f;
        }
        
        .message-group {
            display: flex;
            margin-bottom: 2px;
            animation: slideIn 0.2s ease-out;
        }
        
        @keyframes slideIn {
            from {
                opacity: 0;
                transform: translateY(4px);
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
            max-width: 80%;
            padding: 6px 10px;
            border-radius: 8px;
            word-wrap: break-word;
            font-size: 11px;
            line-height: 1.3;
        }
        
        .message-group.in .message-bubble {
            background: #2a4a5a;
            color: #ffd700;
        }
        
        .message-group.out .message-bubble {
            background: #1a4a2a;
            color: #4ade80;
        }
        
        .message-time {
            font-size: 9px;
            margin-top: 1px;
            opacity: 0.6;
        }
        
        .message-group.in .message-time {
            color: #ffd700;
            text-align: left;
            padding-left: 2px;
        }
        
        .message-group.out .message-time {
            color: #4ade80;
            text-align: right;
            padding-right: 2px;
        }
        
        .empty-chat {
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100%;
            color: #666;
            font-size: 11px;
        }
        
        .input-section {
            padding: 8px 10px;
            background: #1a1a1a;
            border-top: 1px solid #333;
            display: flex;
            gap: 6px;
        }
        
        .input-section textarea {
            flex: 1;
            padding: 6px 8px;
            background: #252525;
            border: 1px solid #404040;
            border-radius: 4px;
            color: #e0e0e0;
            font-family: inherit;
            font-size: 11px;
            resize: none;
            max-height: 60px;
            min-height: 32px;
        }
        
        .input-section textarea:focus {
            outline: none;
            border-color: #4a9eff;
            box-shadow: 0 0 0 2px rgba(74, 158, 255, 0.15);
        }
        
        .input-section textarea::placeholder {
            color: #666;
        }
        
        .input-section button {
            padding: 6px 12px;
            background: #4a9eff;
            border: none;
            border-radius: 4px;
            color: #fff;
            font-weight: 600;
            font-size: 11px;
            white-space: nowrap;
            cursor: pointer;
            transition: all 0.15s;
            align-self: flex-end;
        }
        
        .input-section button:active {
            transform: scale(0.95);
            background: #2e7dd9;
        }
        
        .input-section button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .status-bar {
            padding: 4px 10px;
            background: #1a1a1a;
            border-top: 1px solid #333;
            font-size: 10px;
            color: #999;
            font-weight: 500;
        }
        
        /* Scrollbar */
        ::-webkit-scrollbar {
            width: 4px;
        }
        
        ::-webkit-scrollbar-track {
            background: #1a1a1a;
        }
        
        ::-webkit-scrollbar-thumb {
            background: #404040;
            border-radius: 2px;
        }
        
        ::-webkit-scrollbar-thumb:hover {
            background: #505050;
        }
    </style>
</head>
<body>

<!-- CONTACT LIST SCREEN -->
<div class="screen-contacts" id="screenContacts">
    <div class="header">📱 ANMS SMS</div>
    
    <div class="input-group">
        <input type="tel" id="phoneInput" placeholder="Phone number" maxlength="20">
        <input type="text" id="nameInput" placeholder="Contact name" maxlength="30">
        <div class="button-group">
            <button onclick="addContact()">Add Contact</button>
            <button onclick="clearInputs()">Clear</button>
        </div>
    </div>
    
    <div class="contacts-list" id="contactsList"></div>
</div>

<!-- CHAT SCREEN -->
<div class="screen-chat hidden" id="screenChat">
    <div class="chat-header" id="chatHeader">Chat</div>
    <div class="messages-area" id="messagesArea"><div class="empty-chat">No messages</div></div>
    <div class="input-section">
        <textarea id="msgInput" placeholder="Type message..." disabled></textarea>
        <button onclick="sendMessage()" id="sendBtn" disabled>Send</button>
    </div>
    <div class="status-bar" id="statusBar">Ready</div>
</div>

<script>
let ws, activePhone, activeContact, chats = {}, contacts = [], pollInterval;

// Detect back button (physical or browser)
window.addEventListener('keydown', e => {
    if (e.key === 'Backspace' || e.key === 'Escape' || e.key === 'ArrowLeft') {
        if (!document.getElementById('screenChat').classList.contains('hidden')) {
            goBack();
            e.preventDefault();
        }
    }
});

window.addEventListener('popstate', () => {
    if (!document.getElementById('screenChat').classList.contains('hidden')) {
        goBack();
    }
});

function init() {
    contacts = JSON.parse(localStorage.getItem('anms_contacts') || '[]');
    chats = JSON.parse(localStorage.getItem('anms_chats') || '{}');
    renderContacts();
    connectWS();
}

function clearInputs() {
    document.getElementById('phoneInput').value = '';
    document.getElementById('nameInput').value = '';
    document.getElementById('phoneInput').focus();
}

function addContact() {
    const phone = document.getElementById('phoneInput').value.trim();
    const name = document.getElementById('nameInput').value.trim();
    
    if (!phone) { alert('Enter phone number'); return; }
    if (!name) { alert('Enter contact name'); return; }
    
    if (contacts.some(c => c.phone === phone)) {
        alert('Phone number already exists');
        return;
    }
    
    const contact = { phone, name };
    contacts.push(contact);
    localStorage.setItem('anms_contacts', JSON.stringify(contacts));
    clearInputs();
    renderContacts();
}

function selectContact(phone, name) {
    activePhone = phone;
    activeContact = name;
    document.getElementById('screenContacts').classList.add('hidden');
    document.getElementById('screenChat').classList.remove('hidden');
    document.getElementById('chatHeader').textContent = '📱 ' + name;
    document.getElementById('msgInput').disabled = false;
    document.getElementById('sendBtn').disabled = false;
    
    clearInterval(pollInterval);
    loadChat(phone).then(() => {
        renderMessages();
        startPolling();
    });
}

function goBack() {
    activePhone = null;
    activeContact = null;
    document.getElementById('screenChat').classList.add('hidden');
    document.getElementById('screenContacts').classList.remove('hidden');
    document.getElementById('msgInput').disabled = true;
    document.getElementById('sendBtn').disabled = true;
    clearInterval(pollInterval);
}

function connectWS() {
    const host = window.location.hostname;
    try {
        ws = new WebSocket('ws://' + host + ':8765');
        ws.onopen = () => updateStatus('Connected');
        ws.onmessage = e => {
            if (e.data.startsWith('INCOMING_SMS|')) {
                const [_, phone, ...msgParts] = e.data.split('|');
                const text = msgParts.join('|');
                loadChat(phone).then(() => {
                    if (activePhone === phone) renderMessages();
                });
            }
        };
        ws.onclose = () => { updateStatus('Reconnecting...'); setTimeout(connectWS, 3000); };
        ws.onerror = () => updateStatus('Error');
    } catch (e) {
        updateStatus('WS Error');
    }
}

function loadChat(phone) {
    updateStatus('Loading...');
    return fetch('http://' + window.location.hostname + ':8080/api/chat/' + encodeURIComponent(phone))
        .then(r => {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(data => {
            chats[phone] = data;
            localStorage.setItem('anms_chats', JSON.stringify(chats));
            updateStatus('Ready');
        })
        .catch(e => updateStatus('Error'));
}

function startPolling() {
    pollInterval = setInterval(() => {
        if (activePhone) {
            loadChat(activePhone).then(() => renderMessages());
        }
    }, 2000);
}

function sendMessage() {
    if (!activePhone) return;
    const text = document.getElementById('msgInput').value.trim();
    if (!text) return;
    
    document.getElementById('sendBtn').disabled = true;
    document.getElementById('msgInput').value = '';
    updateStatus('Sending...');
    
    fetch('http://' + window.location.hostname + ':8080/send', {
        method: 'POST',
        body: 'phone=' + encodeURIComponent(activePhone) + '&message=' + encodeURIComponent(text)
    })
    .then(r => r.json())
    .then(data => {
        document.getElementById('sendBtn').disabled = false;
        if (data.success) {
            updateStatus('Sent');
            setTimeout(() => loadChat(activePhone).then(() => renderMessages()), 500);
        } else {
            updateStatus('Failed');
        }
    })
    .catch(e => {
        updateStatus('Error');
        document.getElementById('sendBtn').disabled = false;
    });
}

function renderContacts() {
    const html = contacts.length === 0 
        ? '<div class="empty-message">No contacts added yet</div>'
        : contacts.map(c => {
            const msgCount = (chats[c.phone] || []).length;
            return '<div class="contact-item" onclick="selectContact(\'' + c.phone.replace(/'/g, "\\\\'") + '\', \'' + c.name.replace(/'/g, "\\\\'") + '\')"><div class="contact-name">' + escapeHtml(c.name) + '</div><div class="contact-phone">' + escapeHtml(c.phone) + '</div><div class="contact-count">' + msgCount + ' messages</div></div>';
        }).join('');
    
    document.getElementById('contactsList').innerHTML = html;
}

function renderMessages() {
    const msgs = chats[activePhone] || [];
    if (!msgs.length) {
        document.getElementById('messagesArea').innerHTML = '<div class="empty-chat">No messages yet</div>';
        return;
    }
    
    const html = msgs.map(m => {
        const dirClass = m.dir === 'in' ? 'in' : 'out';
        return '<div class="message-group ' + dirClass + '"><div><div class="message-bubble">' + escapeHtml(m.body) + '</div><div class="message-time">' + m.time + '</div></div></div>';
    }).join('');
    
    const area = document.getElementById('messagesArea');
    area.innerHTML = html;
    area.scrollTop = area.scrollHeight;
}

function updateStatus(s) {
    document.getElementById('statusBar').textContent = s;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

document.getElementById('msgInput').addEventListener('keypress', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
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
