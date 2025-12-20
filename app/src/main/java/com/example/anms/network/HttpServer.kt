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
    private var lastChecked = mutableMapOf<String, Long>()

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
            
            val json = messages.joinToString(",") { msg ->
                val dir = if (msg.type == 1) "in" else "out"
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(msg.timestamp)
                val cleanBody = msg.body.replace("\\", " ").replace("\"", "\\\"")
                """{"body":"$cleanBody","dir":"$dir","time":"$time","ts":${msg.timestamp}}"""
            }
            
            lastChecked[phone] = System.currentTimeMillis()
            
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
                Log.e(tag, "SMS error: ${e.message}", e)
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
    <title>SMS Chat</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body { height: 100%; width: 100%; }
        body { 
            font-family: system-ui, -apple-system, sans-serif;
            background: #fff;
            color: #000;
            display: grid;
            grid-template-columns: 1fr 2fr;
            gap: 0;
            overflow: hidden;
        }
        
        .sidebar {
            background: #f5f5f5;
            border-right: 2px solid #ddd;
            display: flex;
            flex-direction: column;
            min-height: 100vh;
        }
        
        .sidebar-title {
            padding: 16px;
            font-size: 18px;
            font-weight: bold;
            background: #2c3e50;
            color: white;
            border-bottom: 2px solid #1a252f;
        }
        
        .add-contact-box {
            padding: 12px;
            display: flex;
            gap: 8px;
            border-bottom: 1px solid #ddd;
            background: white;
        }
        
        .add-contact-box input {
            flex: 1;
            padding: 10px;
            border: 2px solid #ccc;
            border-radius: 4px;
            font-size: 14px;
            font-family: monospace;
        }
        
        .add-contact-box input:focus {
            outline: none;
            border-color: #2196F3;
            box-shadow: 0 0 4px #2196F3;
        }
        
        .add-contact-box button {
            padding: 10px 16px;
            background: #2196F3;
            color: white;
            border: none;
            border-radius: 4px;
            font-weight: bold;
            cursor: pointer;
            font-size: 14px;
        }
        
        .add-contact-box button:hover {
            background: #0b7dda;
        }
        
        .add-contact-box button:active {
            background: #0056b3;
        }
        
        .contacts-list {
            flex: 1;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
        }
        
        .contact-item {
            padding: 12px 16px;
            border-bottom: 1px solid #ddd;
            cursor: pointer;
            transition: background 0.15s;
            background: white;
        }
        
        .contact-item:hover {
            background: #e8e8e8;
        }
        
        .contact-item.active {
            background: #2196F3;
            color: white;
            font-weight: bold;
            border-left: 4px solid #1976d2;
        }
        
        .contact-phone {
            font-size: 16px;
            font-weight: 500;
        }
        
        .contact-count {
            font-size: 12px;
            opacity: 0.7;
            margin-top: 4px;
        }
        
        .chat-container {
            display: flex;
            flex-direction: column;
            min-height: 100vh;
            background: white;
        }
        
        .chat-header {
            padding: 16px;
            background: #2c3e50;
            color: white;
            font-size: 16px;
            font-weight: bold;
            border-bottom: 2px solid #1a252f;
        }
        
        .messages-area {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 8px;
            background: #fafafa;
        }
        
        .message {
            display: flex;
            margin-bottom: 8px;
            animation: fadeIn 0.3s ease-in;
        }
        
        @keyframes fadeIn {
            from { opacity: 0; }
            to { opacity: 1; }
        }
        
        .msg-in {
            justify-content: flex-start;
        }
        
        .msg-out {
            justify-content: flex-end;
        }
        
        .bubble {
            max-width: 70%;
            padding: 10px 14px;
            border-radius: 8px;
            word-wrap: break-word;
            font-size: 15px;
            line-height: 1.4;
        }
        
        .msg-in .bubble {
            background: white;
            border: 1px solid #ccc;
            color: #000;
        }
        
        .msg-out .bubble {
            background: #2196F3;
            color: white;
        }
        
        .msg-time {
            font-size: 11px;
            color: #999;
            margin-top: 4px;
            padding: 0 4px;
        }
        
        .msg-in .msg-time {
            text-align: left;
        }
        
        .msg-out .msg-time {
            text-align: right;
        }
        
        .empty-chat {
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100%;
            color: #999;
            font-size: 16px;
            text-align: center;
            padding: 20px;
        }
        
        .input-section {
            padding: 12px;
            background: white;
            border-top: 2px solid #ddd;
            display: flex;
            gap: 8px;
        }
        
        .input-section textarea {
            flex: 1;
            padding: 10px;
            border: 2px solid #ccc;
            border-radius: 4px;
            font-family: system-ui, sans-serif;
            font-size: 14px;
            resize: none;
            max-height: 80px;
        }
        
        .input-section textarea:focus {
            outline: none;
            border-color: #2196F3;
            box-shadow: 0 0 4px #2196F3;
        }
        
        .input-section button {
            padding: 10px 20px;
            background: #2196F3;
            color: white;
            border: none;
            border-radius: 4px;
            font-weight: bold;
            cursor: pointer;
            font-size: 14px;
            white-space: nowrap;
        }
        
        .input-section button:hover {
            background: #0b7dda;
        }
        
        .input-section button:active {
            background: #0056b3;
        }
        
        .input-section button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .status-bar {
            padding: 8px 16px;
            background: #f0f0f0;
            border-top: 1px solid #ddd;
            font-size: 12px;
            color: #666;
            font-weight: 500;
        }
        
        .status-bar.active {
            color: #4CAF50;
        }
        
        .status-bar.loading {
            color: #FF9800;
        }
        
        .status-bar.error {
            color: #f44336;
        }
        
        @media (max-width: 900px) {
            body { grid-template-columns: 1fr; }
            .sidebar { display: none; }
        }
        
        @media (max-width: 600px) {
            body { grid-template-columns: 1fr; }
            .sidebar { max-height: 35vh; border-right: none; border-bottom: 2px solid #ddd; }
            .chat-container { min-height: 65vh; }
            .bubble { max-width: 85%; }
        }
        
        @media (max-width: 400px) {
            .sidebar { max-height: 30vh; }
            .chat-container { min-height: 70vh; }
            .add-contact-box { flex-wrap: wrap; }
            .add-contact-box button { width: 100%; }
        }
    </style>
</head>
<body>
    <div class="sidebar">
        <div class="sidebar-title">💬 Contacts</div>
        <div class="add-contact-box">
            <input type="tel" id="phone" placeholder="+1234567890" maxlength="20">
            <button onclick="addContact()">Add</button>
        </div>
        <div class="contacts-list" id="contacts"></div>
    </div>
    
    <div class="chat-container">
        <div class="chat-header" id="chatHeader">Select a contact →</div>
        <div class="messages-area" id="messages"><div class="empty-chat">👈 Select a contact to start chatting</div></div>
        <div class="input-section">
            <textarea id="msg" placeholder="Type message..." disabled></textarea>
            <button onclick="send()" id="sendBtn" disabled>Send</button>
        </div>
        <div class="status-bar active" id="status">✓ Ready</div>
    </div>
</body>

<script>
let active, chats = {}, contacts = [], pollInterval;

function init() {
    const saved = localStorage.getItem('anms_contacts');
    const savedChats = localStorage.getItem('anms_chats');
    contacts = saved ? JSON.parse(saved) : [];
    chats = savedChats ? JSON.parse(savedChats) : {};
    renderContacts();
}

function addContact() {
    const inp = document.getElementById('phone');
    const p = inp.value.trim();
    if (!p) { alert('Enter a phone number'); return; }
    if (contacts.includes(p)) { alert('Already added'); return; }
    contacts.push(p);
    localStorage.setItem('anms_contacts', JSON.stringify(contacts));
    inp.value = '';
    renderContacts();
    selectContact(p);
}

function selectContact(p) {
    active = p;
    renderContacts();
    document.getElementById('msg').disabled = false;
    document.getElementById('sendBtn').disabled = false;
    document.getElementById('chatHeader').textContent = '📱 ' + p;
    clearInterval(pollInterval);
    updateStatus('Loading...', 'loading');
    loadChat(p).then(() => {
        renderMsgs();
        updateStatus('✓ Ready', 'active');
        startPolling();
    });
}

function loadChat(phone) {
    return fetch('http://' + window.location.hostname + ':8080/api/chat/' + encodeURIComponent(phone))
        .then(r => r.json())
        .then(data => {
            chats[phone] = data;
            localStorage.setItem('anms_chats', JSON.stringify(chats));
            return data;
        })
        .catch(e => {
            updateStatus('✗ Load error', 'error');
            console.error(e);
        });
}

function startPolling() {
    if (!active) return;
    pollInterval = setInterval(() => {
        if (active) {
            loadChat(active).then(() => {
                renderMsgs();
            }).catch(() => {});
        }
    }, 2000);
}

function send() {
    if (!active) return;
    const txt = document.getElementById('msg').value.trim();
    if (!txt) return;
    
    document.getElementById('sendBtn').disabled = true;
    document.getElementById('msg').value = '';
    updateStatus('⏳ Sending...', 'loading');
    
    fetch('http://' + window.location.hostname + ':8080/send', {
        method: 'POST',
        body: 'phone=' + encodeURIComponent(active) + '&message=' + encodeURIComponent(txt)
    })
    .then(r => r.json())
    .then(data => {
        document.getElementById('sendBtn').disabled = false;
        if (data.success) {
            updateStatus('✓ Sent', 'active');
            setTimeout(() => loadChat(active).then(() => renderMsgs()), 500);
        } else {
            updateStatus('✗ Failed', 'error');
        }
    })
    .catch(e => {
        document.getElementById('sendBtn').disabled = false;
        updateStatus('✗ Error', 'error');
        console.error(e);
    });
}

function renderContacts() {
    const html = contacts.length > 0 ? contacts.map(p => {
        const cnt = (chats[p] || []).length;
        const cls = active === p ? 'active' : '';
        return '<div class="contact-item ' + cls + '" onclick="selectContact(\'' + p.replace(/'/g, "\\") + '\')" style="cursor:pointer"><div class="contact-phone">' + escapeHtml(p) + '</div><div class="contact-count">' + cnt + ' msgs</div></div>';
    }).join('') : '<div style="padding:20px;color:#999;text-align:center">No contacts</div>';
    document.getElementById('contacts').innerHTML = html;
}

function renderMsgs() {
    const msgs = chats[active] || [];
    if (msgs.length === 0) {
        document.getElementById('messages').innerHTML = '<div class="empty-chat">📭 No messages</div>';
        return;
    }
    const html = msgs.map(m => {
        const dirClass = m.dir === 'in' ? 'msg-in' : 'msg-out';
        return '<div class="message ' + dirClass + '"><div><div class="bubble">' + escapeHtml(m.body) + '</div><div class="msg-time">' + escapeHtml(m.time) + '</div></div></div>';
    }).join('');
    const area = document.getElementById('messages');
    area.innerHTML = html;
    area.scrollTop = area.scrollHeight;
}

function updateStatus(txt, cls) {
    const el = document.getElementById('status');
    el.textContent = txt;
    el.className = 'status-bar ' + (cls || '');
}

function escapeHtml(text) {
    const d = document.createElement('div');
    d.textContent = text;
    return d.innerHTML;
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