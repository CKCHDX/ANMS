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

class HttpServer(val context: Context, private val port: Int = 8080) {
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
                path == "/" -> sendHtmlResponse(getHtml(clientSocket.inetAddress.hostAddress))
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

    private fun getHtml(serverIp: String): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ANMS Chat</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { 
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.app {
  width: 100%;
  max-width: 500px;
  height: 100vh;
  max-height: 800px;
  background: white;
  border-radius: 20px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,0.3);
  overflow: hidden;
}
.header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 20px;
  text-align: center;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
}
.header h1 { font-size: 24px; margin-bottom: 4px; }
.header p { font-size: 14px; opacity: 0.9; }
.contacts-section {
  padding: 16px;
  border-bottom: 1px solid #eee;
}
.input-group {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.input-group input {
  flex: 1;
  padding: 12px 16px;
  border: 2px solid #e0e0e0;
  border-radius: 10px;
  font-size: 14px;
  transition: border-color 0.3s;
}
.input-group input:focus {
  outline: none;
  border-color: #667eea;
}
.input-group button {
  padding: 12px 20px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.3s;
}
.input-group button:hover {
  background: #5568d3;
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
}
.contacts-list {
  max-height: 120px;
  overflow-y: auto;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.contact-tag {
  background: #f0f0f0;
  padding: 8px 12px;
  border-radius: 20px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.3s;
  border: 2px solid transparent;
}
.contact-tag:hover {
  background: #e0e0e0;
}
.contact-tag.active {
  background: #667eea;
  color: white;
  border-color: #667eea;
}
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.phone-display {
  background: #f5f5f5;
  padding: 16px;
  text-align: center;
  font-weight: 600;
  color: #333;
  border-bottom: 1px solid #eee;
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
.msg {
  display: flex;
  gap: 8px;
  animation: slideIn 0.3s ease;
}
@keyframes slideIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
.msg.out {
  justify-content: flex-end;
}
.msg-bubble {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14px;
  word-wrap: break-word;
  line-height: 1.4;
}
.msg.in .msg-bubble {
  background: #e3f2fd;
  color: #1565c0;
}
.msg.out .msg-bubble {
  background: #667eea;
  color: white;
}
.msg-time {
  font-size: 11px;
  color: #999;
  margin-top: 4px;
  align-self: flex-end;
}
.msg.out .msg-time {
  color: #999;
}
.input-area {
  padding: 16px;
  border-top: 1px solid #eee;
  display: flex;
  gap: 8px;
  background: #fafafa;
}
.input-area textarea {
  flex: 1;
  padding: 12px 16px;
  border: 2px solid #e0e0e0;
  border-radius: 10px;
  font-family: inherit;
  font-size: 14px;
  resize: none;
  height: 40px;
  transition: border-color 0.3s;
}
.input-area textarea:focus {
  outline: none;
  border-color: #667eea;
}
.input-area button {
  padding: 12px 20px;
  background: #667eea;
  color: white;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.3s;
  min-width: 60px;
}
.input-area button:hover {
  background: #5568d3;
  transform: translateY(-2px);
}
.input-area button:disabled {
  background: #ccc;
  cursor: not-allowed;
  transform: none;
}
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #999;
  font-size: 14px;
}
</style>
</head>
<body>
<div class="app">
  <div class="header">
    <h1>ANMS Chat</h1>
    <p>Remote SMS Control</p>
  </div>
  
  <div class="contacts-section">
    <div class="input-group">
      <input type="tel" id="newPhone" placeholder="Enter phone number" maxlength="20">
      <button onclick="addContact()">Add</button>
    </div>
    <div class="contacts-list" id="contactsList"></div>
  </div>
  
  <div class="chat-area">
    <div class="phone-display" id="phoneDisplay">Select a contact to chat</div>
    <div class="messages" id="messages"></div>
    <div class="input-area">
      <textarea id="messageInput" placeholder="Type message..." disabled></textarea>
      <button id="sendBtn" onclick="sendMessage()" disabled>Send</button>
    </div>
  </div>
</div>

<script>
const STATE = {
  phone: null,
  chats: {},
  contacts: [],
  serverIp: '$serverIp'
};

const DOM = {
  newPhone: document.getElementById('newPhone'),
  contactsList: document.getElementById('contactsList'),
  phoneDisplay: document.getElementById('phoneDisplay'),
  messages: document.getElementById('messages'),
  messageInput: document.getElementById('messageInput'),
  sendBtn: document.getElementById('sendBtn')
};

function addContact() {
  const phone = DOM.newPhone.value.trim();
  if (!phone || STATE.contacts.includes(phone)) return;
  
  STATE.contacts.push(phone);
  STATE.chats[phone] = [];
  DOM.newPhone.value = '';
  renderContacts();
  selectContact(phone);
}

function selectContact(phone) {
  STATE.phone = phone;
  DOM.phoneDisplay.textContent = 'Chat: ' + phone;
  DOM.messageInput.disabled = false;
  DOM.sendBtn.disabled = false;
  renderMessages();
  DOM.messageInput.focus();
}

function addMessage(phone, text, direction) {
  const time = new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
  if (!STATE.chats[phone]) STATE.chats[phone] = [];
  STATE.chats[phone].push({text, direction, time});
  if (STATE.phone === phone) renderMessages();
  renderContacts();
}

async function sendMessage() {
  if (!STATE.phone) return;
  const text = DOM.messageInput.value.trim();
  if (!text) return;
  
  DOM.sendBtn.disabled = true;
  const oldText = DOM.messageInput.value;
  DOM.messageInput.value = '';
  addMessage(STATE.phone, text, 'out');
  
  try {
    const response = await fetch('http://' + STATE.serverIp + ':8080/send', {
      method: 'POST',
      body: 'phone=' + encodeURIComponent(STATE.phone) + '&message=' + encodeURIComponent(text)
    });
    const data = await response.json();
    console.log('Send response:', data);
    if (!data.success) {
      addMessage(STATE.phone, '❌ Failed: ' + data.message, 'error');
    }
  } catch (e) {
    console.error('Send error:', e);
    addMessage(STATE.phone, '❌ Network error', 'error');
  } finally {
    DOM.sendBtn.disabled = false;
    DOM.messageInput.focus();
  }
}

function renderContacts() {
  DOM.contactsList.innerHTML = STATE.contacts.map(p => {
    const lastMsg = STATE.chats[p] && STATE.chats[p].length > 0 
      ? STATE.chats[p][STATE.chats[p].length - 1].text 
      : 'No messages';
    return `<div class="contact-tag ${'${STATE.phone === p ? "active" : ""}'}" onclick="selectContact('${'\'' + p + '\''}')" title="${'${lastMsg}'}">${'${p}'}</div>`;
  }).join('');
}

function renderMessages() {
  if (!STATE.phone) {
    DOM.messages.innerHTML = '<div class="empty-state">Select a contact to start chatting</div>';
    return;
  }
  
  const msgs = STATE.chats[STATE.phone] || [];
  if (msgs.length === 0) {
    DOM.messages.innerHTML = '<div class="empty-state">No messages yet. Type a message below to send SMS!</div>';
    return;
  }
  
  DOM.messages.innerHTML = msgs.map(m => `
    <div class="msg ${'${m.direction}'}">
      <div>
        <div class="msg-bubble">${'${escapeHtml(m.text)}'}</div>
        <div class="msg-time">${'${m.time}'}</div>
      </div>
    </div>
  `).join('');
  
  DOM.messages.scrollTop = DOM.messages.scrollHeight;
}

function escapeHtml(text) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

DOM.messageInput.onkeypress = (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sendMessage();
  }
};

DOM.newPhone.onkeypress = (e) => {
  if (e.key === 'Enter') addContact();
};

renderContacts();
renderMessages();
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