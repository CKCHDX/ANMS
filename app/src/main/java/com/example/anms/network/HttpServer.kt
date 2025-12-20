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
    <title>ANMS - SMS Chat</title>
    <style>
        :root {
            --primary: #3b82f6;
            --primary-dark: #1e40af;
            --secondary: #10b981;
            --bg: #f8fafc;
            --surface: #ffffff;
            --border: #e2e8f0;
            --text: #1e293b;
            --text-light: #64748b;
        }
        
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body { height: 100%; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            background: var(--bg);
            color: var(--text);
            display: flex;
            flex-direction: column;
        }
        
        .container {
            display: flex;
            height: 100vh;
            overflow: hidden;
        }
        
        .sidebar {
            width: 100%;
            max-width: 280px;
            background: var(--surface);
            border-right: 1px solid var(--border);
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }
        
        .sidebar-header {
            padding: 16px;
            border-bottom: 1px solid var(--border);
            background: linear-gradient(135deg, var(--primary) 0%, var(--primary-dark) 100%);
            color: white;
            font-weight: 600;
            font-size: 16px;
        }
        
        .add-contact {
            padding: 12px;
            display: flex;
            gap: 8px;
            border-bottom: 1px solid var(--border);
        }
        
        .add-contact input {
            flex: 1;
            padding: 8px 12px;
            border: 1px solid var(--border);
            border-radius: 6px;
            font-size: 14px;
            font-family: inherit;
        }
        
        .add-contact input:focus {
            outline: none;
            border-color: var(--primary);
            box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
        }
        
        .add-contact button {
            padding: 8px 16px;
            background: var(--primary);
            color: white;
            border: none;
            border-radius: 6px;
            font-weight: 600;
            cursor: pointer;
            transition: background 0.2s;
        }
        
        .add-contact button:hover {
            background: var(--primary-dark);
        }
        
        .add-contact button:active {
            transform: scale(0.98);
        }
        
        .contacts {
            flex: 1;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
        }
        
        .contact {
            padding: 12px 16px;
            cursor: pointer;
            border-bottom: 1px solid var(--border);
            transition: background 0.2s;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }
        
        .contact:hover {
            background: #f1f5f9;
        }
        
        .contact.active {
            background: var(--primary);
            color: white;
        }
        
        .contact-info {
            flex: 1;
            min-width: 0;
        }
        
        .contact-phone {
            font-weight: 500;
            font-size: 14px;
        }
        
        .contact-badge {
            font-size: 12px;
            opacity: 0.7;
        }
        
        .chat-area {
            flex: 1;
            display: flex;
            flex-direction: column;
            overflow: hidden;
            background: var(--bg);
        }
        
        .chat-header {
            padding: 16px;
            background: var(--surface);
            border-bottom: 1px solid var(--border);
            font-weight: 600;
            font-size: 16px;
        }
        
        .messages {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 12px;
        }
        
        .message {
            display: flex;
            gap: 8px;
            animation: slideIn 0.3s ease-out;
        }
        
        @keyframes slideIn {
            from { opacity: 0; transform: translateY(10px); }
            to { opacity: 1; transform: translateY(0); }
        }
        
        .message.in {
            justify-content: flex-start;
        }
        
        .message.out {
            justify-content: flex-end;
        }
        
        .message-bubble {
            max-width: 70%;
            padding: 10px 14px;
            border-radius: 12px;
            word-wrap: break-word;
            font-size: 14px;
            line-height: 1.4;
        }
        
        .message.in .message-bubble {
            background: var(--surface);
            border: 1px solid var(--border);
            color: var(--text);
        }
        
        .message.out .message-bubble {
            background: var(--primary);
            color: white;
        }
        
        .message-time {
            font-size: 12px;
            color: var(--text-light);
            padding: 0 4px;
            align-self: flex-end;
        }
        
        .empty-state {
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100%;
            color: var(--text-light);
            text-align: center;
            padding: 20px;
        }
        
        .input-area {
            padding: 12px;
            background: var(--surface);
            border-top: 1px solid var(--border);
            display: flex;
            gap: 8px;
        }
        
        .input-area textarea {
            flex: 1;
            padding: 10px 12px;
            border: 1px solid var(--border);
            border-radius: 6px;
            font-family: inherit;
            font-size: 14px;
            resize: none;
            max-height: 100px;
        }
        
        .input-area textarea:focus {
            outline: none;
            border-color: var(--primary);
            box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
        }
        
        .input-area button {
            padding: 10px 20px;
            background: var(--primary);
            color: white;
            border: none;
            border-radius: 6px;
            font-weight: 600;
            cursor: pointer;
            transition: background 0.2s;
            white-space: nowrap;
        }
        
        .input-area button:hover {
            background: var(--primary-dark);
        }
        
        .input-area button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .status {
            padding: 8px 16px;
            font-size: 12px;
            color: var(--text-light);
            text-align: center;
            background: #f1f5f9;
        }
        
        @media (max-width: 600px) {
            .container {
                flex-direction: column;
            }
            
            .sidebar {
                max-width: 100%;
                width: 100%;
                max-height: 40%;
                border-right: none;
                border-bottom: 1px solid var(--border);
            }
            
            .chat-area {
                flex: 1;
            }
            
            .message-bubble {
                max-width: 85%;
            }
        }
        
        @media (max-width: 480px) {
            .sidebar {
                max-height: 35%;
            }
            
            .sidebar-header,
            .add-contact,
            .chat-header {
                padding: 12px;
            }
            
            .messages {
                padding: 12px;
            }
        }
    </style>
</head>
<body>
<div class="container">
    <div class="sidebar">
        <div class="sidebar-header">💬 Messages</div>
        <div class="add-contact">
            <input type="tel" id="phone" placeholder="+1234567890" maxlength="20">
            <button onclick="addContact()">Add</button>
        </div>
        <div class="contacts" id="contacts"></div>
    </div>
    
    <div class="chat-area">
        <div class="chat-header" id="chatHeader">Select a contact</div>
        <div class="messages" id="messages"><div class="empty-state">👈 Select a contact to start</div></div>
        <div class="input-area">
            <textarea id="msg" placeholder="Type a message..." disabled></textarea>
            <button onclick="send()" id="sendBtn" disabled>Send</button>
        </div>
        <div class="status" id="status">Ready</div>
    </div>
</div>

<script>
let active, chats = {}, contacts = [], pollInterval;

function init() {
    contacts = JSON.parse(localStorage.getItem('anms_contacts') || '[]');
    chats = JSON.parse(localStorage.getItem('anms_chats') || '{}');
    renderContacts();
}

function addContact() {
    const p = document.getElementById('phone').value.trim();
    if (!p || contacts.includes(p)) return;
    contacts.push(p);
    localStorage.setItem('anms_contacts', JSON.stringify(contacts));
    document.getElementById('phone').value = '';
    renderContacts();
    selectContact(p);
}

function selectContact(p) {
    active = p;
    renderContacts();
    document.getElementById('msg').disabled = false;
    document.getElementById('sendBtn').disabled = false;
    document.getElementById('chatHeader').textContent = p;
    clearInterval(pollInterval);
    loadChat(p).then(() => {
        renderMsgs();
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
            updateStatus('Error loading');
            console.error('Load error:', e);
        });
}

function startPolling() {
    if (!active) return;
    pollInterval = setInterval(() => {
        if (active) loadChat(active).then(() => renderMsgs());
    }, 2000);
}

function send() {
    if (!active) return;
    const text = document.getElementById('msg').value.trim();
    if (!text) return;
    
    document.getElementById('sendBtn').disabled = true;
    document.getElementById('msg').value = '';
    updateStatus('Sending...');
    
    fetch('http://' + window.location.hostname + ':8080/send', {
        method: 'POST',
        body: 'phone=' + encodeURIComponent(active) + '&message=' + encodeURIComponent(text)
    })
    .then(r => r.json())
    .then(data => {
        if (data.success) {
            setTimeout(() => loadChat(active).then(() => renderMsgs()), 500);
            updateStatus('Ready');
        } else {
            updateStatus('Send failed');
        }
        document.getElementById('sendBtn').disabled = false;
    })
    .catch(e => {
        updateStatus('Error');
        document.getElementById('sendBtn').disabled = false;
    });
}

function renderContacts() {
    const html = contacts.map(p => {
        const cnt = (chats[p] || []).length;
        return '<div class="contact ' + (active === p ? 'active' : '') + '" onclick="selectContact(\'' + p.replace(/'/g, "\\'") + '\')"><div class="contact-info"><div class="contact-phone">' + p + '</div><div class="contact-badge">' + cnt + ' messages</div></div></div>';
    }).join('');
    document.getElementById('contacts').innerHTML = html || '<div style="padding: 20px; text-align: center; color: #999; font-size: 14px;">No contacts yet</div>';
}

function renderMsgs() {
    const msgs = chats[active] || [];
    const html = msgs.map(m => {
        return '<div class="message ' + m.dir + '"><div class="message-bubble">' + escapeHtml(m.body) + '</div><div class="message-time">' + m.time + '</div></div>';
    }).join('');
    const c = document.getElementById('messages');
    c.innerHTML = html || '<div class="empty-state">No messages yet</div>';
    c.scrollTop = c.scrollHeight;
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