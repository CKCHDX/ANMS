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
    <title>ANMS - SMS</title>
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
        
        .app {
            display: flex;
            height: 100vh;
            overflow: hidden;
        }
        
        @media (min-width: 600px) {
            .app {
                gap: 0;
            }
            
            .sidebar {
                width: 280px;
                background: #1a1a1a;
                border-right: 1px solid #333;
                display: flex !important;
                flex-direction: column;
                padding: 16px;
                gap: 12px;
            }
            
            .chat-section {
                display: flex !important;
                flex: 1;
            }
            
            .contacts-screen {
                display: none !important;
            }
        }
        
        @media (max-width: 600px) {
            .app {
                flex-direction: column;
            }
            
            .sidebar {
                display: none;
            }
            
            .chat-section {
                display: none;
            }
            
            .contacts-screen {
                display: flex !important;
                flex-direction: column;
                width: 100%;
                height: 100%;
            }
            
            .chat-section.mobile-view {
                display: flex !important;
                width: 100%;
                height: 100%;
            }
            
            .contacts-screen.hidden {
                display: none !important;
            }
        }
        
        .sidebar {
            display: none;
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
        
        .contacts-screen {
            display: none;
            flex-direction: column;
            padding: 16px;
            gap: 12px;
            background: #0f0f0f;
        }
        
        .contacts-screen-header {
            font-weight: 600;
            font-size: 18px;
            color: #fff;
            text-align: center;
        }
        
        .chat-section {
            display: none;
            flex-direction: column;
            flex: 1;
            background: #0f0f0f;
            overflow: hidden;
        }
        
        .chat-header {
            padding: 12px 16px;
            background: #1a1a1a;
            border-bottom: 1px solid #333;
            font-weight: 600;
            font-size: 15px;
            color: #fff;
            display: flex;
            align-items: center;
            gap: 12px;
            justify-content: space-between;
        }
        
        .back-btn {
            display: none;
            padding: 6px 12px;
            background: #252525;
            border: 1px solid #404040;
            border-radius: 4px;
            color: #e0e0e0;
            cursor: pointer;
            font-size: 12px;
        }
        
        @media (max-width: 600px) {
            .back-btn {
                display: block;
            }
            
            .chat-header {
                padding: 10px 12px;
                font-size: 14px;
            }
        }
        
        .messages-area {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        
        @media (max-width: 600px) {
            .messages-area {
                padding: 12px;
                gap: 6px;
            }
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
        
        @media (max-width: 600px) {
            .message-bubble {
                max-width: 80%;
                padding: 8px 12px;
                font-size: 13px;
            }
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
            padding: 12px;
            background: #1a1a1a;
            border-top: 1px solid #333;
            display: flex;
            gap: 8px;
        }
        
        @media (max-width: 600px) {
            .input-section {
                padding: 10px;
                gap: 6px;
            }
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
        
        @media (max-width: 600px) {
            .input-section textarea {
                padding: 8px 10px;
                font-size: 13px;
                min-height: 36px;
            }
        }
        
        .input-section textarea:focus {
            outline: none;
            border-color: #4a9eff;
            box-shadow: 0 0 0 2px rgba(74, 158, 255, 0.1);
        }
        
        .input-section button {
            padding: 10px 16px;
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
        
        @media (max-width: 600px) {
            .input-section button {
                padding: 8px 12px;
                font-size: 12px;
            }
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
            padding: 8px 12px;
            background: #1a1a1a;
            border-top: 1px solid #333;
            font-size: 11px;
            color: #999;
            font-weight: 500;
            text-align: center;
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
        
        ::-webkit-scrollbar-thumb:hover {
            background: #505050;
        }
    </style>
</head>
<body>
<div class="app">
    <div class="sidebar">
        <div class="sidebar-header">Phone Chats</div>
        <div class="phone-input-group">
            <input type="tel" id="phone" placeholder="+1234567890" maxlength="20">
            <button onclick="addContact()">Add</button>
        </div>
        <div class="contacts-list" id="contacts"></div>
    </div>
    
    <div class="contacts-screen" id="contactsScreen">
        <div class="contacts-screen-header">SMS Chat</div>
        <div class="phone-input-group">
            <input type="tel" id="phoneMobile" placeholder="+1234567890" maxlength="20">
            <button onclick="addContact()">Add</button>
        </div>
        <div class="contacts-list" id="contactsMobile"></div>
    </div>
    
    <div class="chat-section" id="chatSection">
        <div class="chat-header">
            <button class="back-btn" onclick="goBack()">Back</button>
            <span id="chatHeader">Select contact</span>
        </div>
        <div class="messages-area" id="messages"><div class="empty-state">Select a contact</div></div>
        <div class="input-section">
            <textarea id="msg" placeholder="Type..." disabled></textarea>
            <button onclick="send()" id="sendBtn" disabled>Send</button>
        </div>
        <div class="status-bar" id="status">Ready</div>
    </div>
</div>

<script>
let ws, active, chats = {}, contacts = [], pollInterval;
let isMobile = window.innerWidth <= 600;

function init() {
    contacts = JSON.parse(localStorage.getItem('anms_contacts') || '[]');
    chats = JSON.parse(localStorage.getItem('anms_chats') || '{}');
    renderContacts();
    connectWS();
    window.addEventListener('resize', function() {
        isMobile = window.innerWidth <= 600;
    });
}

function connectWS() {
    var host = window.location.hostname;
    try {
        ws = new WebSocket('ws://' + host + ':8765');
        ws.onopen = function() { updateStatus('Connected'); };
        ws.onmessage = function(e) {
            if (e.data.indexOf('INCOMING_SMS|') === 0) {
                var parts = e.data.split('|');
                var phone = parts[1];
                var text = parts.slice(2).join('|');
                loadChat(phone).then(function() {
                    if (active === phone) renderMsgs();
                });
            }
        };
        ws.onclose = function() { updateStatus('Reconnecting...'); setTimeout(connectWS, 3000); };
        ws.onerror = function(e) { console.error('WS Error:', e); updateStatus('Error'); };
    } catch (e) {
        console.error('WS Connect Error:', e);
        updateStatus('WS Error');
    }
}

function addContact() {
    var input = isMobile ? document.getElementById('phoneMobile') : document.getElementById('phone');
    var p = input.value.trim();
    if (!p) { alert('Enter phone'); return; }
    if (contacts.indexOf(p) >= 0) { alert('Already added'); return; }
    contacts.push(p);
    localStorage.setItem('anms_contacts', JSON.stringify(contacts));
    input.value = '';
    renderContacts();
    if (isMobile) selectContactMobile(p);
}

function renderContacts() {
    var html = contacts.map(function(p) {
        var cnt = (chats[p] || []).length;
        var cls = active === p ? 'active' : '';
        return '<div class="contact-item ' + cls + '" onclick="selectContactMobile(' + JSON.stringify(p) + ')"><div class="contact-number">' + escapeHtml(p) + '</div><div class="contact-count">' + cnt + ' msgs</div></div>';
    }).join('');
    
    document.getElementById('contacts').innerHTML = html || '<div style="color: #666; text-align: center; padding: 20px; font-size: 13px;">No contacts</div>';
    document.getElementById('contactsMobile').innerHTML = html || '<div style="color: #666; text-align: center; padding: 20px; font-size: 13px;">No contacts</div>';
}

function selectContact(p) {
    active = p;
    renderContacts();
    document.getElementById('msg').disabled = false;
    document.getElementById('sendBtn').disabled = false;
    document.getElementById('chatHeader').textContent = 'Chat: ' + p;
    clearInterval(pollInterval);
    loadChat(p).then(function() {
        renderMsgs();
        startPolling();
    });
}

function selectContactMobile(p) {
    active = p;
    renderContacts();
    document.getElementById('contactsScreen').classList.add('hidden');
    document.getElementById('chatSection').classList.add('mobile-view');
    document.getElementById('msg').disabled = false;
    document.getElementById('sendBtn').disabled = false;
    document.getElementById('chatHeader').textContent = 'Chat: ' + p;
    clearInterval(pollInterval);
    loadChat(p).then(function() {
        renderMsgs();
        startPolling();
    });
}

function goBack() {
    active = null;
    clearInterval(pollInterval);
    document.getElementById('contactsScreen').classList.remove('hidden');
    document.getElementById('chatSection').classList.remove('mobile-view');
    document.getElementById('msg').disabled = true;
    document.getElementById('sendBtn').disabled = true;
    renderContacts();
}

function loadChat(phone) {
    updateStatus('Loading...');
    return fetch('http://' + window.location.hostname + ':8080/api/chat/' + encodeURIComponent(phone))
        .then(function(r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(function(data) {
            chats[phone] = data;
            localStorage.setItem('anms_chats', JSON.stringify(chats));
            updateStatus('Ready');
            return data;
        })
        .catch(function(e) {
            console.error('Load error:', e);
            updateStatus('Error');
        });
}

function startPolling() {
    pollInterval = setInterval(function() {
        if (active) {
            loadChat(active).then(function() { renderMsgs(); }).catch(function(e) { console.error('Poll error:', e); });
        }
    }, 2000);
}

function send() {
    if (!active) return;
    var text = document.getElementById('msg').value.trim();
    if (!text) return;
    
    document.getElementById('sendBtn').disabled = true;
    document.getElementById('msg').value = '';
    updateStatus('Sending...');
    
    fetch('http://' + window.location.hostname + ':8080/send', {
        method: 'POST',
        body: 'phone=' + encodeURIComponent(active) + '&message=' + encodeURIComponent(text)
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
        document.getElementById('sendBtn').disabled = false;
        if (data.success) {
            updateStatus('Sent');
            setTimeout(function() { loadChat(active).then(function() { renderMsgs(); }); }, 1000);
        } else {
            updateStatus('Failed');
        }
    })
    .catch(function(e) {
        console.error('Send error:', e);
        updateStatus('Error');
        document.getElementById('sendBtn').disabled = false;
    });
}

function renderMsgs() {
    var msgs = chats[active] || [];
    if (!msgs.length) {
        document.getElementById('messages').innerHTML = '<div class="empty-state">No messages yet</div>';
        return;
    }
    
    var html = msgs.map(function(m) {
        var dirClass = m.dir === 'in' ? 'in' : 'out';
        return '<div class="message-group ' + dirClass + '"><div><div class="message-bubble">' + escapeHtml(m.body) + '</div><div class="message-time">' + m.time + '</div></div></div>';
    }).join('');
    
    var area = document.getElementById('messages');
    area.innerHTML = html;
    area.scrollTop = area.scrollHeight;
}

function updateStatus(s) {
    document.getElementById('status').textContent = s;
}

function escapeHtml(text) {
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

document.getElementById('msg').addEventListener('keypress', function(e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});

document.getElementById('phone').addEventListener('keypress', function(e) {
    if (e.key === 'Enter') addContact();
});

var phoneMobile = document.getElementById('phoneMobile');
if (phoneMobile) {
    phoneMobile.addEventListener('keypress', function(e) {
        if (e.key === 'Enter') addContact();
    });
}

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