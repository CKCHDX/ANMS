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
            val messages = smsDb.getConversation(phone, 100)
            
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
            Log.e(tag, "Error loading chat: ${e.message}")
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
    <title>SMS Chat</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body { height: 100%; width: 100%; }
        body { 
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: #fff;
            color: #000;
            display: flex;
            overflow: hidden;
        }
        
        .sidebar {
            width: 280px;
            background: #f8f9fa;
            border-right: 1px solid #ddd;
            display: flex;
            flex-direction: column;
            height: 100%;
            overflow: hidden;
        }
        
        .sidebar-header {
            padding: 16px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            font-weight: bold;
            font-size: 16px;
            border-bottom: 1px solid #ddd;
        }
        
        .input-box {
            padding: 12px;
            border-bottom: 1px solid #ddd;
            display: flex;
            gap: 8px;
            background: white;
        }
        
        .input-box input {
            flex: 1;
            padding: 8px 12px;
            border: 1px solid #ddd;
            border-radius: 6px;
            font-size: 13px;
        }
        
        .input-box input:focus {
            outline: none;
            border-color: #667eea;
            box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.1);
        }
        
        .input-box button {
            padding: 8px 16px;
            background: #667eea;
            color: white;
            border: none;
            border-radius: 6px;
            font-weight: 600;
            cursor: pointer;
            font-size: 13px;
        }
        
        .input-box button:hover { background: #5568d3; }
        .input-box button:active { transform: scale(0.95); }
        
        .contacts-list {
            flex: 1;
            overflow-y: auto;
            display: flex;
            flex-direction: column;
            padding: 4px;
        }
        
        .contact-item {
            padding: 12px;
            margin: 2px 0;
            cursor: pointer;
            border-radius: 6px;
            transition: all 0.2s;
            font-size: 14px;
            background: transparent;
        }
        
        .contact-item:hover {
            background: #e8e8e8;
        }
        
        .contact-item.active {
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            font-weight: 600;
        }
        
        .contact-number { font-weight: 500; }
        .contact-badge { font-size: 12px; opacity: 0.7; margin-top: 2px; }
        
        .chat-section {
            flex: 1;
            display: flex;
            flex-direction: column;
            background: white;
            overflow: hidden;
        }
        
        .chat-header {
            padding: 16px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            font-weight: 600;
            border-bottom: 1px solid #ddd;
        }
        
        .messages-container {
            flex: 1;
            overflow-y: auto;
            padding: 16px;
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        
        .empty-message {
            display: flex;
            align-items: center;
            justify-content: center;
            height: 100%;
            color: #999;
            font-size: 15px;
            text-align: center;
        }
        
        .message {
            display: flex;
            margin-bottom: 4px;
            animation: slideIn 0.2s ease-out;
        }
        
        @keyframes slideIn {
            from { opacity: 0; transform: translateY(8px); }
            to { opacity: 1; transform: translateY(0); }
        }
        
        .msg-in { justify-content: flex-start; }
        .msg-out { justify-content: flex-end; }
        
        .bubble {
            max-width: 70%;
            padding: 10px 14px;
            border-radius: 12px;
            word-wrap: break-word;
            font-size: 14px;
            line-height: 1.4;
        }
        
        .msg-in .bubble {
            background: #e9ecef;
            color: #000;
        }
        
        .msg-out .bubble {
            background: #667eea;
            color: white;
        }
        
        .msg-time {
            font-size: 11px;
            color: #999;
            margin-top: 3px;
            padding: 0 4px;
        }
        
        .msg-in .msg-time { text-align: left; }
        .msg-out .msg-time { text-align: right; }
        
        .input-section {
            padding: 12px;
            background: white;
            border-top: 1px solid #ddd;
            display: flex;
            gap: 8px;
        }
        
        .input-section textarea {
            flex: 1;
            padding: 10px;
            border: 1px solid #ddd;
            border-radius: 6px;
            font-family: inherit;
            font-size: 14px;
            resize: none;
            max-height: 80px;
        }
        
        .input-section textarea:focus {
            outline: none;
            border-color: #667eea;
            box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.1);
        }
        
        .input-section button {
            padding: 10px 16px;
            background: #667eea;
            color: white;
            border: none;
            border-radius: 6px;
            font-weight: 600;
            cursor: pointer;
            font-size: 14px;
            white-space: nowrap;
        }
        
        .input-section button:hover { background: #5568d3; }
        .input-section button:active { transform: scale(0.95); }
        .input-section button:disabled { opacity: 0.5; cursor: not-allowed; }
        
        .status-bar {
            padding: 8px 16px;
            background: #f8f9fa;
            border-top: 1px solid #ddd;
            font-size: 12px;
            color: #666;
            font-weight: 500;
        }
        
        @media (max-width: 768px) {
            body { flex-direction: column; }
            .sidebar { width: 100%; height: 35%; border-right: none; border-bottom: 1px solid #ddd; }
            .chat-section { height: 65%; }
        }
        
        @media (max-width: 480px) {
            .sidebar { height: 30%; }
            .chat-section { height: 70%; }
            .bubble { max-width: 85%; }
            .sidebar-header, .chat-header { padding: 12px; font-size: 14px; }
            .messages-container { padding: 12px; }
        }
    </style>
</head>
<body>
    <div class="sidebar">
        <div class="sidebar-header">💬 Contacts</div>
        <div class="input-box">
            <input type="tel" id="phone" placeholder="+1234567890" maxlength="20">
            <button onclick="addContact()">Add</button>
        </div>
        <div class="contacts-list" id="contacts"></div>
    </div>
    
    <div class="chat-section">
        <div class="chat-header" id="chatHeader">Select a contact →</div>
        <div class="messages-container" id="messages"><div class="empty-message">👈 Select a contact to start</div></div>
        <div class="input-section">
            <textarea id="msg" placeholder="Type a message..." disabled></textarea>
            <button onclick="send()" id="sendBtn" disabled>Send</button>
        </div>
        <div class="status-bar" id="status">✓ Ready</div>
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
    if (!p) { alert('Enter phone number'); return; }
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
    updateStatus('Loading...', '#FF9800');
    loadChat(p).then(() => {
        renderMsgs();
        updateStatus('✓ Ready', '#4CAF50');
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
            updateStatus('✗ Error', '#f44336');
            console.error(e);
        });
}

function startPolling() {
    if (!active) return;
    pollInterval = setInterval(() => {
        if (active) loadChat(active).then(() => renderMsgs()).catch(() => {});
    }, 2000);
}

function send() {
    if (!active) return;
    const txt = document.getElementById('msg').value.trim();
    if (!txt) return;
    
    document.getElementById('sendBtn').disabled = true;
    document.getElementById('msg').value = '';
    updateStatus('⏳ Sending...', '#FF9800');
    
    fetch('http://' + window.location.hostname + ':8080/send', {
        method: 'POST',
        body: 'phone=' + encodeURIComponent(active) + '&message=' + encodeURIComponent(txt)
    })
    .then(r => r.json())
    .then(data => {
        document.getElementById('sendBtn').disabled = false;
        if (data.success) {
            updateStatus('✓ Sent', '#4CAF50');
            setTimeout(() => loadChat(active).then(() => renderMsgs()), 500);
        } else {
            updateStatus('✗ Failed', '#f44336');
        }
    })
    .catch(e => {
        document.getElementById('sendBtn').disabled = false;
        updateStatus('✗ Error', '#f44336');
        console.error(e);
    });
}

function renderContacts() {
    const html = contacts.length ? contacts.map(p => {
        const cnt = (chats[p] || []).length;
        const cls = active === p ? 'active' : '';
        return '<div class="contact-item ' + cls + '" onclick="selectContact(\'' + p.replace(/'/g, "\\") + '\')"><div class="contact-number">' + htmlEscape(p) + '</div><div class="contact-badge">' + cnt + ' messages</div></div>';
    }).join('') : '<div style="padding:20px;text-align:center;color:#999">No contacts yet</div>';
    document.getElementById('contacts').innerHTML = html;
}

function renderMsgs() {
    const msgs = chats[active] || [];
    if (!msgs.length) {
        document.getElementById('messages').innerHTML = '<div class="empty-message">📭 No messages</div>';
        return;
    }
    const html = msgs.map(m => {
        const dirClass = m.dir === 'in' ? 'msg-in' : 'msg-out';
        return '<div class="message ' + dirClass + '"><div><div class="bubble">' + htmlEscape(m.body) + '</div><div class="msg-time">' + htmlEscape(m.time) + '</div></div></div>';
    }).join('');
    const area = document.getElementById('messages');
    area.innerHTML = html;
    area.scrollTop = area.scrollHeight;
}

function updateStatus(txt, color) {
    const el = document.getElementById('status');
    el.textContent = txt;
    el.style.color = color || '#666';
}

function htmlEscape(text) {
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