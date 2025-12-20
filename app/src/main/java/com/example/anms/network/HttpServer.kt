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
    <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
    <title>ANMS Chat</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { 
            font-family: Courier, monospace; 
            background: #1a1a1a; 
            color: #00ff00; 
            font-size: 13px;
            line-height: 1.4;
        }
        .container { max-width: 240px; margin: 0 auto; padding: 6px; height: 100vh; display: flex; flex-direction: column; }
        .header { background: #003300; padding: 8px; margin-bottom: 6px; border: 2px solid #00ff00; text-align: center; font-weight: bold; }
        .controls { display: flex; gap: 4px; margin-bottom: 6px; }
        .controls input { flex: 1; padding: 6px; background: #0a0a0a; border: 1px solid #00ff00; color: #00ff00; font-family: monospace; font-size: 12px; }
        .controls button { padding: 6px 12px; background: #003300; color: #00ff00; border: 1px solid #00ff00; cursor: pointer; font-family: monospace; font-weight: bold; }
        .controls button:active { background: #00ff00; color: #000; }
        .contacts { 
            background: #0a0a0a; 
            border: 1px solid #00ff00; 
            padding: 6px;
            max-height: 60px; 
            overflow-y: auto;
            margin-bottom: 6px;
            display: flex;
            flex-direction: column;
            gap: 4px;
        }
        .contact { 
            padding: 4px; 
            cursor: pointer; 
            border: 1px solid #003300;
            background: #1a1a1a;
            font-size: 12px;
        }
        .contact:hover { background: #003300; }
        .contact.active { background: #00ff00; color: #000; font-weight: bold; }
        .messages { 
            flex: 1;
            background: #0a0a0a; 
            border: 1px solid #00ff00; 
            padding: 6px;
            overflow-y: auto;
            margin-bottom: 6px;
            display: flex;
            flex-direction: column;
            gap: 2px;
            font-size: 12px;
        }
        .msg { padding: 4px; word-wrap: break-word; }
        .msg.in { color: #00ff00; }
        .msg.out { color: #ffff00; }
        .msg.time { color: #888; font-size: 10px; }
        .input-area { display: flex; gap: 4px; margin-bottom: 6px; }
        .input-area textarea { flex: 1; padding: 6px; background: #0a0a0a; border: 1px solid #00ff00; color: #00ff00; font-family: monospace; font-size: 12px; resize: none; height: 50px; }
        .input-area button { padding: 6px 10px; background: #003300; color: #00ff00; border: 1px solid #00ff00; cursor: pointer; font-family: monospace; font-weight: bold; }
        .input-area button:active { background: #00ff00; color: #000; }
        .status { background: #003300; padding: 4px; text-align: center; font-size: 11px; border-top: 1px solid #00ff00; }
        .loading { color: #ff6600; }
    </style>
</head>
<body>
<div class="container">
    <div class="header">ANMS SMS CHAT</div>
    
    <div class="controls">
        <input type="tel" id="phone" placeholder="+1234567890" maxlength="20">
        <button onclick="addContact()">Add</button>
    </div>
    
    <div class="contacts" id="contacts"></div>
    
    <div class="messages" id="messages"></div>
    
    <div class="input-area">
        <textarea id="msg" placeholder="Message..." disabled></textarea>
        <button onclick="send()" id="sendBtn" disabled>Send</button>
    </div>
    
    <div class="status" id="status">Ready</div>
</div>

<script>
let ws, active, chats = {}, contacts = [];

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
        ws.onopen = () => updateStatus('Connected');
        ws.onmessage = e => {
            if (e.data.startsWith('INCOMING_SMS|')) {
                const [_, phone, ...msgParts] = e.data.split('|');
                const text = msgParts.join('|');
                loadChat(phone).then(() => {
                    if (active === phone) renderMsgs();
                });
            }
        };
        ws.onclose = () => { updateStatus('Offline'); setTimeout(connectWS, 3000); };
        ws.onerror = () => updateStatus('Error');
    } catch (e) {
        updateStatus('WS Error');
    }
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
    loadChat(p).then(() => renderMsgs());
}

function loadChat(phone) {
    updateStatus('Loading...');
    return fetch('http://' + window.location.hostname + ':8080/api/chat/' + encodeURIComponent(phone))
        .then(r => r.json())
        .then(data => {
            chats[phone] = data;
            localStorage.setItem('anms_chats', JSON.stringify(chats));
            updateStatus('Ready');
            return data;
        })
        .catch(e => {
            updateStatus('Error loading');
            console.error('Load error:', e);
        });
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
            loadChat(active).then(() => renderMsgs());
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
        return '<div class="contact ' + (active === p ? 'active' : '') + '" onclick="selectContact(\'' + p + '\')\'>' + p + ' (' + cnt + ')</div>';
    }).join('');
    document.getElementById('contacts').innerHTML = html || '<div style="color: #666;">No contacts</div>';
}

function renderMsgs() {
    const msgs = chats[active] || [];
    const html = msgs.map(m => 
        '<div><div class="msg ' + m.dir + '">' + m.body + '</div><div class="msg time">' + m.time + '</div></div>'
    ).join('');
    const c = document.getElementById('messages');
    c.innerHTML = html || '<div style="color: #666; text-align: center; margin-top: 20px;">No messages</div>';
    c.scrollTop = c.scrollHeight;
}

function updateStatus(s) {
    document.getElementById('status').textContent = s;
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