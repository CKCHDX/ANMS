package com.example.anms.network

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlin.concurrent.thread

class HttpServer(context: Context, port: Int = 8080) : NanoHTTPD(port) {
    private val tag = "ANMS_Http"

    init {
        start()
        Log.d(tag, "HTTP Server started on port $port")
    }

    override fun serve(session: IHTTPSession?): Response {
        return try {
            when (session?.uri) {
                "/", "/index.html" -> {
                    val html = getIndexHtml()
                    newFixedLengthResponse(html).apply {
                        addHeader("Content-Type", "text/html; charset=utf-8")
                    }
                }
                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html", "<html><body><h1>404 Not Found</h1></body></html>")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling request", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", "<html><body><h1>500 Server Error</h1></body></html>")
        }
    }

    private fun getIndexHtml(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ANMS Client</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: Arial, sans-serif; background: #667eea; min-height: 100vh; display: flex; justify-content: center; align-items: center; padding: 10px; }
        .container { width: 100%; max-width: 500px; background: white; border-radius: 8px; box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2); display: flex; flex-direction: column; height: 90vh; max-height: 800px; }
        .header { background: #667eea; color: white; padding: 15px; border-radius: 8px 8px 0 0; text-align: center; }
        .header h1 { font-size: 20px; margin-bottom: 5px; }
        .status { display: inline-flex; align-items: center; gap: 6px; padding: 6px 10px; background: rgba(255, 255, 255, 0.2); border-radius: 12px; font-size: 11px; }
        .dot { width: 6px; height: 6px; border-radius: 50%; background: #4caf50; }
        .dot.offline { background: #f44336; }
        .messages { flex: 1; overflow-y: auto; padding: 15px; background: #f5f5f5; }
        .msg { margin-bottom: 10px; padding: 10px; border-radius: 6px; word-break: break-word; }
        .msg.in { background: #e3f2fd; color: #1565c0; }
        .msg.out { background: #f3e5f5; color: #6a1b9a; margin-left: 15px; }
        .meta { font-size: 10px; opacity: 0.7; margin-top: 3px; }
        .input-area { padding: 12px; background: white; border-top: 1px solid #ddd; }
        input, textarea { width: 100%; padding: 8px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; font-family: Arial, sans-serif; margin-bottom: 8px; resize: none; }
        textarea { height: 70px; }
        input:focus, textarea:focus { outline: none; border-color: #667eea; box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.1); }
        .buttons { display: flex; gap: 8px; }
        button { flex: 1; padding: 10px; border: none; border-radius: 4px; font-size: 13px; font-weight: bold; cursor: pointer; transition: all 0.2s; }
        .btn-send { background: #667eea; color: white; }
        .btn-send:hover:not(:disabled) { background: #5568d3; }
        .btn-send:disabled { opacity: 0.5; cursor: not-allowed; }
        .btn-clear { background: #f44336; color: white; }
        .btn-clear:hover { background: #d32f2f; }
        .notif { padding: 8px; border-radius: 4px; margin-bottom: 8px; font-size: 12px; }
        .notif.success { background: #f1f8e9; color: #2e7d32; }
        .notif.error { background: #ffebee; color: #c62828; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>ANMS</h1>
            <div class="status">
                <div class="dot" id="dot"></div>
                <span id="status">Connecting...</span>
            </div>
        </div>
        <div class="messages" id="msgs"></div>
        <div class="input-area">
            <div id="notif"></div>
            <input type="text" id="phone" placeholder="+1234567890" maxlength="20">
            <textarea id="msg" placeholder="Type message..."></textarea>
            <div class="buttons">
                <button class="btn-send" id="send" disabled>Send</button>
                <button class="btn-clear" id="clear">Clear</button>
            </div>
        </div>
    </div>

    <script>
        const DOT = document.getElementById('dot');
        const STATUS = document.getElementById('status');
        const MSGS = document.getElementById('msgs');
        const PHONE = document.getElementById('phone');
        const MSG = document.getElementById('msg');
        const SEND = document.getElementById('send');
        const CLEAR = document.getElementById('clear');
        const NOTIF = document.getElementById('notif');

        let ws = null;
        let connected = false;

        function getHost() {
            let host = localStorage.getItem('anms_host') || location.hostname + ':8765';
            return host;
        }

        function connect() {
            let host = getHost();
            try {
                ws = new WebSocket('ws://' + host);

                ws.onopen = () => {
                    connected = true;
                    DOT.classList.remove('offline');
                    STATUS.textContent = 'Connected';
                    SEND.disabled = false;
                    show('Connected', 'success');
                };

                ws.onmessage = (e) => {
                    let data = e.data;
                    let parts = data.split('|');
                    if (parts.length >= 2) {
                        let phone = parts[0];
                        let content = parts[1];
                        let time = new Date().toLocaleTimeString();
                        let out = parts[3] === 'true';
                        addMsg(phone, content, time, out);
                    }
                };

                ws.onerror = () => {
                    DOT.classList.add('offline');
                    STATUS.textContent = 'Error';
                    show('Connection error', 'error');
                };

                ws.onclose = () => {
                    connected = false;
                    DOT.classList.add('offline');
                    STATUS.textContent = 'Disconnected';
                    SEND.disabled = true;
                    show('Disconnected', 'error');
                    setTimeout(connect, 3000);
                };
            } catch (e) {
                console.error(e);
                setTimeout(connect, 3000);
            }
        }

        function addMsg(phone, content, time, out) {
            let div = document.createElement('div');
            div.className = 'msg ' + (out ? 'out' : 'in');
            div.innerHTML = content + '<div class="meta">' + phone + ' \u2022 ' + time + '</div>';
            MSGS.appendChild(div);
            MSGS.scrollTop = MSGS.scrollHeight;
        }

        function send() {
            let phone = PHONE.value.trim();
            let msg = MSG.value.trim();
            if (!phone) { show('Enter phone number', 'error'); return; }
            if (!msg) { show('Enter message', 'error'); return; }
            if (!connected) { show('Not connected', 'error'); return; }

            try {
                ws.send(phone + '|' + msg);
                addMsg(phone, msg, new Date().toLocaleTimeString(), true);
                MSG.value = '';
                MSG.focus();
            } catch (e) {
                show('Error sending', 'error');
            }
        }

        function show(text, type) {
            NOTIF.innerHTML = '<div class="notif ' + type + '">' + text + '</div>';
            if (type === 'success') setTimeout(() => { NOTIF.innerHTML = ''; }, 2000);
        }

        SEND.addEventListener('click', send);
        MSG.addEventListener('keypress', (e) => { if (e.key === 'Enter' && e.ctrlKey) send(); });
        CLEAR.addEventListener('click', () => { MSGS.innerHTML = ''; });
        PHONE.addEventListener('keypress', (e) => { if (e.key === 'Enter') MSG.focus(); });

        connect();
    </script>
</body>
</html>
"""
    }

    fun stopServer() {
        thread {
            try {
                stop()
                Log.d(tag, "HTTP server stopped")
            } catch (e: Exception) {
                Log.e(tag, "Error stopping server", e)
            }
        }
    }
}