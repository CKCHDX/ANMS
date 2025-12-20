package com.example.anms.network

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
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
    private val messageHistory = mutableMapOf<String, MutableList<Map<String, String>>>()

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

    private fun handleSendMessage(body: String): String {
        return try {
            // Parse: phone=XXX&message=YYY
            val params = body.split("&").associate {
                val kv = it.split("=")
                Pair(kv[0], URLDecoder.decode(kv.getOrNull(1) ?: "", "UTF-8"))
            }
            val phone = params["phone"] ?: return jsonResponse(false, "Missing phone")
            val message = params["message"] ?: return jsonResponse(false, "Missing message")

            Log.d(tag, "Sending SMS to $phone: $message")
            
            try {
                smsManager.sendTextMessage(phone, null, message, null, null)
                
                // Store in history
                if (!messageHistory.containsKey(phone)) {
                    messageHistory[phone] = mutableListOf()
                }
                messageHistory[phone]?.add(mapOf(
                    "text" to message,
                    "direction" to "out",
                    "time" to java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
                ))
                
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
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <title>ANMS</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: monospace; background: #000; color: #0f0; font-size: 14px; }
        .container { max-width: 240px; margin: 0 auto; padding: 8px; }
        .header { background: #003; padding: 8px; margin-bottom: 8px; border: 1px solid #006; text-align: center; }
        .input-row { display: flex; gap: 4px; margin-bottom: 8px; }
        .input-row input { flex: 1; padding: 6px; background: #001; border: 1px solid #003; color: #0f0; font-size: 12px; }
        .btn { padding: 6px 12px; background: #006; color: #0f0; border: 1px solid #00f; cursor: pointer; font-size: 12px; font-weight: bold; }
        .btn:active { background: #00f; }
        .contacts { margin-bottom: 8px; padding: 6px; background: #001; border: 1px solid #003; max-height: 80px; overflow-y: auto; }
        .contact { padding: 4px; cursor: pointer; border-bottom: 1px solid #003; font-size: 12px; }
        .contact:hover { background: #002; }
        .contact.active { background: #006; color: #0ff; }
        .messages { height: 180px; background: #000; border: 1px solid #003; padding: 6px; overflow-y: auto; margin-bottom: 8px; display: flex; flex-direction: column; gap: 4px; }
        .msg { padding: 4px; font-size: 12px; word-wrap: break-word; max-width: 100%; line-height: 1.3; }
        .msg.in { background: #003; color: #0f0; }
        .msg.out { background: #006; color: #0ff; text-align: right; }
        .input-msg { display: flex; gap: 4px; }
        .input-msg textarea { flex: 1; padding: 4px; background: #001; border: 1px solid #003; color: #0f0; font-size: 12px; font-family: monospace; height: 40px; resize: none; }
        .status { font-size: 11px; color: #666; padding: 4px; text-align: center; border-top: 1px solid #003; }
    </style>
</head>
<body>
<div class="container">
    <div class="header">ANMS CHAT</div>
    
    <div class="input-row">
        <input type="tel" id="phone" placeholder="Phone #" maxlength="15">
        <button class="btn" onclick="addContact()">Add</button>
    </div>
    
    <div class="contacts" id="contacts"></div>
    
    <div class="messages" id="messages"></div>
    
    <div class="input-msg">
        <textarea id="msg" placeholder="Message" disabled></textarea>
        <button class="btn" onclick="send()" id="sendBtn" disabled>Send</button>
    </div>
    
    <div class="status" id="status">Connecting...</div>
</div>

<script>
let ws, activePhone, chats = {}, contacts = [];

function initWS() {
    const host = window.location.hostname;
    ws = new WebSocket('ws://' + host + ':8765');
    ws.onopen = () => updateStatus('Ready');
    ws.onmessage = e => {
        if (e.data.startsWith('INCOMING_SMS|')) {
            const [_, phone, ...msgParts] = e.data.split('|');
            const text = msgParts.join('|');
            addMsg(phone, text, 'in');
        }
    };
    ws.onclose = () => { updateStatus('Offline'); setTimeout(initWS, 3000); };
    ws.onerror = () => updateStatus('Error');
}

function addContact() {
    const p = document.getElementById('phone').value.trim();
    if (!p || contacts.includes(p)) return;
    contacts.push(p);
    chats[p] = [];
    document.getElementById('phone').value = '';
    renderContacts();
    selectContact(p);
}

function selectContact(p) {
    activePhone = p;
    renderContacts();
    document.getElementById('msg').disabled = false;
    document.getElementById('sendBtn').disabled = false;
    renderMsgs();
    document.getElementById('msg').focus();
}

function addMsg(phone, text, dir) {
    if (!chats[phone]) chats[phone] = [];
    chats[phone].push({ text, dir, time: new Date().toLocaleTimeString('en-US', {hour:'2-digit', minute:'2-digit'}) });
    if (activePhone === phone) renderMsgs();
    renderContacts();
}

function send() {
    if (!activePhone) return;
    const text = document.getElementById('msg').value.trim();
    if (!text) return;
    
    addMsg(activePhone, text, 'out');
    document.getElementById('msg').value = '';
    
    fetch('http://' + window.location.hostname + ':8080/send', {
        method: 'POST',
        body: 'phone=' + encodeURIComponent(activePhone) + '&message=' + encodeURIComponent(text)
    }).catch(e => addMsg(activePhone, 'Failed: ' + e, 'err'));
}

function renderContacts() {
    const html = contacts.map(p => {
        const last = chats[p].length > 0 ? chats[p][chats[p].length-1].text : '';
        return '<div class="contact ' + (activePhone === p ? 'active' : '') + '" onclick="selectContact(\'' + p + '\')" title="' + last + '">' + p + '</div>';
    }).join('');
    document.getElementById('contacts').innerHTML = html;
}

function renderMsgs() {
    const html = (chats[activePhone] || []).map(m => 
        '<div class="msg ' + m.dir + '">' + m.text + '</div>'
    ).join('');
    const c = document.getElementById('messages');
    c.innerHTML = html;
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

initWS();
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