package com.example.ancs.network

import android.content.Context
import android.util.Log
import com.example.ancs.calling.CallManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import kotlin.concurrent.thread

class HttpServer(val context: Context, private val port: Int = 8080, private val wsServer: WebSocketServer? = null) {
    private val tag = "ANCS_Http"
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
                path == "/" || path == "/client" -> sendHtmlResponse(getClientHtml())
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

    private fun sendHtmlResponse(html: String): String {
        val contentLength = html.toByteArray().size
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: $contentLength\r\nConnection: close\r\n\r\n$html"
    }

    private fun sendNotFound(): String {
        val body = "Not Found"
        return "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
    }

    private fun getClientHtml(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ANCS - Aquos Network Calling System</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, monospace;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
            padding: 10px;
        }

        .phone {
            width: 100%;
            max-width: 360px;
            aspect-ratio: 9/16;
            background: #0f3460;
            border-radius: 40px;
            border: 8px solid #000;
            padding: 12px;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.8),
                        inset 0 1px 0 rgba(255, 255, 255, 0.1);
            display: flex;
            flex-direction: column;
            position: relative;
            overflow: hidden;
        }

        .notch {
            position: absolute;
            top: 0;
            left: 50%;
            transform: translateX(-50%);
            width: 150px;
            height: 25px;
            background: #000;
            border-radius: 0 0 30px 30px;
            z-index: 10;
        }

        .screen {
            flex: 1;
            background: linear-gradient(180deg, #0f3460 0%, #1a1a2e 100%);
            border-radius: 30px;
            display: flex;
            flex-direction: column;
            overflow: hidden;
            margin-top: 15px;
            position: relative;
        }

        .status-bar {
            padding: 8px 16px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            font-size: 12px;
            color: #00ff88;
            text-shadow: 0 0 10px rgba(0, 255, 136, 0.5);
            border-bottom: 1px solid rgba(0, 255, 136, 0.2);
        }

        .status-dot {
            width: 6px;
            height: 6px;
            border-radius: 50%;
            background: #ff3333;
            display: inline-block;
            margin-right: 4px;
            animation: pulse 2s infinite;
        }

        .status-dot.connected {
            background: #00ff88;
            animation: pulse 1s infinite;
        }

        @keyframes pulse {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.3; }
        }

        .display {
            padding: 20px 16px;
            text-align: center;
            min-height: 120px;
            display: flex;
            flex-direction: column;
            justify-content: center;
            border-bottom: 1px solid rgba(0, 255, 136, 0.1);
        }

        .phone-number {
            font-size: 32px;
            font-weight: 300;
            letter-spacing: 4px;
            color: #00ff88;
            font-family: monospace;
            margin-bottom: 8px;
            min-height: 40px;
            text-shadow: 0 0 10px rgba(0, 255, 136, 0.3);
        }

        .call-status {
            font-size: 14px;
            color: #88ff00;
            letter-spacing: 2px;
            text-transform: uppercase;
            font-weight: 600;
        }

        .call-duration {
            font-size: 18px;
            color: #00ffff;
            margin-top: 8px;
            font-family: monospace;
            font-weight: bold;
        }

        .keypad-area {
            flex: 1;
            padding: 16px 12px;
            display: flex;
            flex-direction: column;
            justify-content: center;
            gap: 8px;
        }

        .keypad {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            gap: 8px;
            margin-bottom: 12px;
        }

        .key {
            aspect-ratio: 1;
            background: rgba(0, 255, 136, 0.1);
            border: 1px solid rgba(0, 255, 136, 0.3);
            color: #00ff88;
            font-size: 20px;
            font-weight: 600;
            cursor: pointer;
            border-radius: 12px;
            transition: all 0.2s ease;
            display: flex;
            align-items: center;
            justify-content: center;
            text-shadow: 0 0 10px rgba(0, 255, 136, 0.3);
            font-family: monospace;
        }

        .key:hover {
            background: rgba(0, 255, 136, 0.2);
            box-shadow: 0 0 10px rgba(0, 255, 136, 0.4), inset 0 0 5px rgba(0, 255, 136, 0.1);
            transform: scale(1.05);
        }

        .key:active {
            background: rgba(0, 255, 136, 0.3);
            transform: scale(0.95);
        }

        .controls {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 8px;
            margin-top: auto;
        }

        .btn {
            padding: 12px;
            border: none;
            border-radius: 12px;
            font-size: 13px;
            font-weight: 600;
            cursor: pointer;
            text-transform: uppercase;
            letter-spacing: 1px;
            transition: all 0.3s ease;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 6px;
        }

        .btn-call {
            background: rgba(0, 255, 136, 0.2);
            color: #00ff88;
            border: 1px solid rgba(0, 255, 136, 0.4);
            grid-column: 1 / 3;
        }

        .btn-call:hover:not(:disabled) {
            background: rgba(0, 255, 136, 0.3);
            box-shadow: 0 0 15px rgba(0, 255, 136, 0.5);
        }

        .btn-call:active:not(:disabled) {
            transform: scale(0.98);
        }

        .btn-end {
            background: rgba(255, 51, 51, 0.2);
            color: #ff3333;
            border: 1px solid rgba(255, 51, 51, 0.4);
            grid-column: 1 / 3;
        }

        .btn-end:hover {
            background: rgba(255, 51, 51, 0.3);
            box-shadow: 0 0 15px rgba(255, 51, 51, 0.5);
        }

        .btn-end:active {
            transform: scale(0.98);
        }

        .btn-action {
            background: rgba(100, 200, 255, 0.15);
            color: #64c8ff;
            border: 1px solid rgba(100, 200, 255, 0.3);
            font-size: 12px;
        }

        .btn-action:hover {
            background: rgba(100, 200, 255, 0.25);
            box-shadow: 0 0 10px rgba(100, 200, 255, 0.4);
        }

        .btn:disabled {
            opacity: 0.3;
            cursor: not-allowed;
        }

        .history {
            padding: 12px 16px;
            border-top: 1px solid rgba(0, 255, 136, 0.1);
            max-height: 100px;
            overflow-y: auto;
            font-size: 11px;
            color: #888;
        }

        .history-item {
            display: flex;
            justify-content: space-between;
            padding: 4px 0;
            border-bottom: 1px solid rgba(100, 100, 100, 0.2);
            margin-bottom: 2px;
        }

        .history-phone {
            color: #00ff88;
            font-family: monospace;
            flex: 1;
        }

        .history-time {
            color: #64c8ff;
            font-family: monospace;
            text-align: right;
            margin-left: 8px;
        }

        .notification {
            position: fixed;
            bottom: 20px;
            right: 20px;
            padding: 12px 16px;
            border-radius: 8px;
            font-size: 12px;
            max-width: 200px;
            word-wrap: break-word;
            animation: slideIn 0.3s ease;
            z-index: 1000;
        }

        @keyframes slideIn {
            from {
                transform: translateX(300px);
                opacity: 0;
            }
            to {
                transform: translateX(0);
                opacity: 1;
            }
        }

        .notification.error {
            background: rgba(255, 51, 51, 0.2);
            border: 1px solid rgba(255, 51, 51, 0.4);
            color: #ff3333;
        }

        .notification.success {
            background: rgba(0, 255, 136, 0.2);
            border: 1px solid rgba(0, 255, 136, 0.4);
            color: #00ff88;
        }
    </style>
</head>
<body>
    <div class="phone">
        <div class="notch"></div>
        <div class="screen">
            <div class="status-bar">
                <span><span class="status-dot" id="statusDot"></span>ANCS</span>
                <span id="statusText">OFFLINE</span>
            </div>

            <div class="display">
                <div class="phone-number" id="displayNumber">0</div>
                <div class="call-status" id="callStatus">READY</div>
                <div class="call-duration" id="callDuration" style="display:none;">00:00:00</div>
            </div>

            <div class="keypad-area">
                <div class="keypad">
                    <button class="key" data-value="1">1</button>
                    <button class="key" data-value="2">2</button>
                    <button class="key" data-value="3">3</button>
                    
                    <button class="key" data-value="4">4</button>
                    <button class="key" data-value="5">5</button>
                    <button class="key" data-value="6">6</button>
                    
                    <button class="key" data-value="7">7</button>
                    <button class="key" data-value="8">8</button>
                    <button class="key" data-value="9">9</button>
                    
                    <button class="key" data-value="*">*</button>
                    <button class="key" data-value="0">0</button>
                    <button class="key" data-value="#">#</button>
                </div>

                <div class="controls">
                    <button class="btn btn-call" id="callBtn">CALL</button>
                    <button class="btn btn-end" id="endBtn" style="display:none;">END CALL</button>
                    <button class="btn btn-action" id="clearBtn">CLR</button>
                    <button class="btn btn-action" id="backBtn">←</button>
                </div>
            </div>

            <div class="history" id="history"></div>
        </div>
    </div>

    <script>
        // DOM Elements
        const statusDot = document.getElementById('statusDot');
        const statusText = document.getElementById('statusText');
        const displayNumber = document.getElementById('displayNumber');
        const callStatus = document.getElementById('callStatus');
        const callDuration = document.getElementById('callDuration');
        const callBtn = document.getElementById('callBtn');
        const endBtn = document.getElementById('endBtn');
        const clearBtn = document.getElementById('clearBtn');
        const backBtn = document.getElementById('backBtn');
        const history = document.getElementById('history');

        // State
        let ws = null;
        let isConnected = false;
        let currentNumber = '';
        let isCallActive = false;
        let callStartTime = 0;
        let callHistory = [];
        let durationInterval = null;

        // Connect to WebSocket
        function connectWebSocket() {
            const hostAddress = localStorage.getItem('ancs_host') || prompt(
                'Enter host IP (e.g., 192.168.1.100:8765):',
                'localhost:8765'
            );
            
            if (!hostAddress) return;
            localStorage.setItem('ancs_host', hostAddress);
            
            ws = new WebSocket(`ws://\${{hostAddress}}`);
            ws.binaryType = 'arraybuffer';
            
            ws.onopen = () => {
                isConnected = true;
                updateStatus('CONNECTED', true);
                showNotification('Connected to ANCS', 'success');
            };
            
            ws.onmessage = (event) => {
                if (event.data instanceof ArrayBuffer) {
                    // Binary audio frame
                    handleAudioFrame(event.data);
                } else {
                    // Text message (call event)
                    try {
                        const msg = JSON.parse(event.data);
                        handleCallEvent(msg);
                    } catch (e) {
                        console.error('Invalid message:', event.data);
                    }
                }
            };
            
            ws.onerror = () => {
                isConnected = false;
                updateStatus('ERROR', false);
                showNotification('Connection error', 'error');
            };
            
            ws.onclose = () => {
                isConnected = false;
                updateStatus('OFFLINE', false);
                showNotification('Disconnected', 'error');
                setTimeout(connectWebSocket, 5000);
            };
        }
        
        function handleCallEvent(msg) {
            const { type, data } = msg;
            
            switch (type) {
                case 'callStarted':
                    isCallActive = true;
                    callStartTime = Date.now();
                    callStatus.textContent = 'CALLING...';
                    callBtn.style.display = 'none';
                    endBtn.style.display = 'block';
                    startDurationTimer();
                    break;
                    
                case 'callConnected':
                    callStatus.textContent = 'CONNECTED';
                    break;
                    
                case 'callEnded':
                    endCall();
                    break;
                    
                case 'incomingCall':
                    handleIncomingCall(data.phoneNumber);
                    break;
            }
        }
        
        function handleAudioFrame(audioData) {
            // Handle audio playback
            console.log('Received audio frame:', audioData.byteLength, 'bytes');
        }
        
        function updateStatus(text, connected) {
            statusText.textContent = text;
            if (connected) {
                statusDot.classList.add('connected');
            } else {
                statusDot.classList.remove('connected');
            }
        }
        
        function showNotification(message, type) {
            const notif = document.createElement('div');
            notif.className = `notification \${{type}}`;
            notif.textContent = message;
            document.body.appendChild(notif);
            
            setTimeout(() => notif.remove(), 3000);
        }
        
        function addToHistory(number, duration) {
            const minutes = Math.floor(duration / 60);
            const seconds = duration % 60;
            const time = `\${{minutes}}m \${{seconds}}s`;
            
            callHistory.unshift({ number, time });
            updateHistory();
        }
        
        function updateHistory() {
            history.innerHTML = callHistory.slice(0, 5)
                .map(call => `
                    <div class="history-item">
                        <span class="history-phone">\${{call.number}}</span>
                        <span class="history-time">\${{call.time}}</span>
                    </div>
                `).join('');
        }
        
        function startDurationTimer() {
            callDuration.style.display = 'block';
            durationInterval = setInterval(() => {
                if (!isCallActive) {
                    clearInterval(durationInterval);
                    return;
                }
                
                const elapsed = Math.floor((Date.now() - callStartTime) / 1000);
                const hours = Math.floor(elapsed / 3600);
                const minutes = Math.floor((elapsed % 3600) / 60);
                const seconds = elapsed % 60;
                
                callDuration.textContent = 
                    `\${{String(hours).padStart(2, '0')}}:\${{String(minutes).padStart(2, '0')}}:\${{String(seconds).padStart(2, '0')}}`;
            }, 1000);
        }
        
        function placeCall() {
            if (!isConnected) {
                showNotification('Not connected', 'error');
                return;
            }
            
            if (currentNumber.length === 0) {
                showNotification('Enter phone number', 'error');
                return;
            }
            
            try {
                ws.send(JSON.stringify({
                    type: 'placeCall',
                    phoneNumber: currentNumber
                }));
                
                isCallActive = true;
                callStartTime = Date.now();
                callStatus.textContent = 'DIALING...';
                callBtn.style.display = 'none';
                endBtn.style.display = 'block';
                startDurationTimer();
            } catch (e) {
                showNotification('Error placing call', 'error');
            }
        }
        
        function endCall() {
            if (!isCallActive) return;
            
            isCallActive = false;
            clearInterval(durationInterval);
            
            const duration = Math.floor((Date.now() - callStartTime) / 1000);
            addToHistory(currentNumber, duration);
            
            ws.send(JSON.stringify({
                type: 'endCall',
                phoneNumber: currentNumber
            }));
            
            displayNumber.textContent = '0';
            currentNumber = '';
            callStatus.textContent = 'READY';
            callDuration.style.display = 'none';
            callDuration.textContent = '00:00:00';
            callBtn.style.display = 'block';
            endBtn.style.display = 'none';
        }
        
        function handleIncomingCall(phoneNumber) {
            callStatus.textContent = 'INCOMING CALL';
            displayNumber.textContent = phoneNumber;
        }
        
        // Event Listeners
        document.querySelectorAll('.key').forEach(btn => {
            btn.addEventListener('click', () => {
                if (isCallActive) return;
                currentNumber += btn.dataset.value;
                displayNumber.textContent = currentNumber || '0';
            });
        });
        
        callBtn.addEventListener('click', placeCall);
        endBtn.addEventListener('click', endCall);
        
        clearBtn.addEventListener('click', () => {
            currentNumber = '';
            displayNumber.textContent = '0';
        });
        
        backBtn.addEventListener('click', () => {
            currentNumber = currentNumber.slice(0, -1);
            displayNumber.textContent = currentNumber || '0';
        });
        
        // Initialize
        connectWebSocket();
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
