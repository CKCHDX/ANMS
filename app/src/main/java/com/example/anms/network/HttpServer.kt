package com.example.anms.network

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import kotlin.concurrent.thread

class HttpServer(context: Context, private val port: Int = 8080) {
    private val tag = "ANMS_Http"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

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

            val response = when {
                path == "/" || path == "/index.html" -> {
                    sendHtmlResponse(getIndexHtml())
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
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun sendHtmlResponse(html: String): String {
        return "HTTP/1.1 200 OK\nContent-Type: text/html; charset=utf-8\nContent-Length: ${html.toByteArray().size}\nConnection: close\n\n$html"
    }

    private fun sendNotFound(): String {
        val body = "<html><body><h1>404 Not Found</h1></body></html>"
        return "HTTP/1.1 404 Not Found\nContent-Type: text/html\nContent-Length: ${body.toByteArray().size}\nConnection: close\n\n$body"
    }

    private fun getIndexHtml(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ANMS Remote Chat</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif; background: #667eea; height: 100vh; }
.container { width: 100%; height: 100vh; background: white; display: flex; flex-direction: column; }
.header { background: #667eea; color: white; padding: 12px; text-align: center; }
.header h1 { font-size: 18px; margin: 0; }
.status-bar { font-size: 11px; display: flex; align-items: center; justify-content: center; margin-top: 4px; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: #4caf50; margin-right: 6px; }
.status-dot.offline { background: #f44336; }
.content { display: flex; flex: 1; overflow: hidden; }
.contacts { width: 35%; background: #f5f5f5; border-right: 1px solid #ddd; overflow-y: auto; }
.contact { padding: 12px; border-bottom: 1px solid #eee; cursor: pointer; background: white; }
.contact:hover { background: #f9f9f9; }
.contact.active { background: #e3f2fd; border-left: 4px solid #667eea; }
.contact-name { font-weight: 500; font-size: 14px; }
.contact-preview { font-size: 12px; color: #999; margin-top: 2px; }
.chat-area { flex: 1; display: flex; flex-direction: column; }
.chat-header { background: #f9f9f9; padding: 12px; border-bottom: 1px solid #ddd; font-weight: 500; }
.messages { flex: 1; overflow-y: auto; padding: 12px; background: white; }
.msg { margin: 8px 0; padding: 10px 12px; border-radius: 8px; max-width: 85%; word-wrap: break-word; }
.msg.in { background: #e3f2fd; color: #1565c0; margin-right: auto; }
.msg.out { background: #f3e5f5; color: #6a1b9a; margin-left: auto; }
.msg-time { font-size: 10px; opacity: 0.7; margin-top: 2px; }
.input-area { padding: 12px; border-top: 1px solid #ddd; display: flex; gap: 8px; }
textarea { flex: 1; padding: 10px; border: 1px solid #ddd; border-radius: 4px; resize: none; height: 40px; font-family: inherit; }
button { padding: 10px 16px; background: #667eea; color: white; border: none; border-radius: 4px; cursor: pointer; }
button:hover { background: #5568d3; }
input.add-phone { width: 100%; padding: 10px; border: 1px solid #ddd; border-radius: 4px; margin-bottom: 8px; }
.loading { font-size: 11px; color: #999; padding: 8px 12px; }
</style>
</head>
<body>
<div class="container">
<div class="header">
<h1>ANMS Remote Chat</h1>
<div class="status-bar">
<div class="status-dot" id="dot"></div>
<span id="status">Connecting...</span>
</div>
</div>
<div class="content">
<div class="contacts">
<div style="padding: 12px;">
<input class="add-phone" id="newPhone" placeholder="+1234567890" maxlength="20">
</div>
<div id="contactsList"></div>
</div>
<div class="chat-area">
<div class="chat-header" id="chatTitle">Select a contact</div>
<div class="messages" id="msgs"></div>
<div class="input-area">
<textarea id="msg" placeholder="Message..." disabled></textarea>
<button id="send" disabled>Send</button>
</div>
</div>
</div>
</div>
<script>
const STATE = { ws: null, connected: false, active: null, chats: {}, loading: {} };
const DOM = { dot: document.getElementById('dot'), status: document.getElementById('status'), newPhone: document.getElementById('newPhone'), msg: document.getElementById('msg'), send: document.getElementById('send'), msgs: document.getElementById('msgs'), chatTitle: document.getElementById('chatTitle'), contactsList: document.getElementById('contactsList') };

function connect() {
const host = localStorage.getItem('anms_host') || location.hostname + ':8765';
STATE.ws = new WebSocket('ws://' + host);
STATE.ws.onopen = () => { STATE.connected = true; DOM.dot.classList.remove('offline'); DOM.status.textContent = 'Connected'; if(STATE.active) { DOM.msg.disabled = false; DOM.send.disabled = false; } };
STATE.ws.onmessage = (e) => { handleWSMessage(e.data); };
STATE.ws.onerror = () => { DOM.dot.classList.add('offline'); DOM.status.textContent = 'Error'; };
STATE.ws.onclose = () => { STATE.connected = false; DOM.dot.classList.add('offline'); DOM.status.textContent = 'Disconnected'; DOM.msg.disabled = true; DOM.send.disabled = true; setTimeout(connect, 3000); };
}

function handleWSMessage(data) {
if (data.startsWith('SMS_HISTORY|')) {
const json = JSON.parse(data.substring(12));
const phone = json.phone;
if(!STATE.chats[phone]) STATE.chats[phone] = [];
STATE.chats[phone] = json.messages.map(m => ({
out: m.direction === 'sent',
text: m.body,
time: m.timestamp.split(' ')[1]
}));
if(STATE.active === phone) render();
STATE.loading[phone] = false;
} else if (data.startsWith('SMS|')) {
const parts = data.split('|');
const phone = parts[1];
const text = parts[2];
const dir = parts[3];
addMsg(phone, text, dir === 'sent');
} else {
const p = data.split('|')[0];
const t = data.split('|')[1];
if(p && t) addMsg(p, t, false);
}
}

function addMsg(phone, text, out) { 
if(!STATE.chats[phone]) STATE.chats[phone] = []; 
const time = new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}); 
STATE.chats[phone].push({out,text,time}); 
if(STATE.active === phone) render(); 
}

function select(phone) { 
STATE.active = phone; 
DOM.msg.disabled = !STATE.connected; 
DOM.send.disabled = !STATE.connected; 
DOM.chatTitle.textContent = 'Chat: ' + phone; 
if(!STATE.chats[phone] || STATE.chats[phone].length === 0) {
if(!STATE.loading[phone]) {
STATE.loading[phone] = true;
STATE.ws.send('GET_SMS_HISTORY:' + phone);
}
}
render(); 
DOM.msg.focus(); 
}

function render() { 
const c = Object.keys(STATE.chats).sort(); 
const html = c.map(p => '<div class="contact ' + (STATE.active === p ? 'active' : '') + '" onclick="select(\'' + p + '\')" style="cursor:pointer;">' + '<div class="contact-name">' + p + '</div>' + '<div class="contact-preview">' + (STATE.chats[p] && STATE.chats[p].length > 0 ? STATE.chats[p][STATE.chats[p].length-1].text : 'No messages') + '</div>' + '</div>').join(''); 
DOM.contactsList.innerHTML = html; 
if(!STATE.active) DOM.msgs.innerHTML = '<p style="text-align:center;color:#ccc;margin-top:20px;">Select a contact</p>'; 
else { 
if(STATE.loading[STATE.active]) DOM.msgs.innerHTML = '<p class="loading">Loading messages...</p>';
else {
const m = STATE.chats[STATE.active] || []; 
DOM.msgs.innerHTML = m.map(x => '<div class="msg ' + (x.out ? 'out' : 'in') + '">' + x.text + '<div class="msg-time">' + x.time + '</div></div>').join(''); 
DOM.msgs.scrollTop = DOM.msgs.scrollHeight;
}
}
}

function send() { 
if(!STATE.active || !STATE.connected) return; 
const t = DOM.msg.value.trim(); 
if(!t) return; 
STATE.ws.send('SEND_SMS|' + STATE.active + '|' + t);
addMsg(STATE.active, t, true); 
DOM.msg.value = ''; 
DOM.msg.focus(); 
}

DOM.send.onclick = send;
DOM.msg.onkeypress = (e) => { if(e.key === 'Enter' && e.ctrlKey) send(); };
DOM.newPhone.onkeypress = (e) => { if(e.key === 'Enter') { const p = DOM.newPhone.value.trim(); if(p && !STATE.chats[p]) { STATE.chats[p] = []; DOM.newPhone.value = ''; select(p); } } };

render();
connect();
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