# ANCS Implementation Guide - Integration with MainActivity

## Current Status

✅ **Complete:**
- `Call.kt` - Data models and call types
- `AudioManager.kt` - Mic capture and speaker playback
- `CallManager.kt` - Call state management
- `WebSocketServer.kt` - Call events and binary audio frames
- `client/index.html` - Dial pad UI and call logic

⏳ **Next:**
- Integrate `CallManager` into MainActivity
- Hook audio capture/playback into call lifecycle
- Handle incoming calls from TelecomManager
- Test end-to-end calling

## Step 1: Update MainActivity.kt

### Add Imports

```kotlin
import com.example.ancs.audio.AudioManager
import com.example.ancs.calling.CallManager
import com.example.ancs.data.Call
import com.example.ancs.data.CallStatus
import com.example.ancs.data.CallType
import org.json.JSONObject
```

### Add Member Variables

```kotlin
class MainActivity : AppCompatActivity() {
    private val tag = "ANCS_Main"
    private val permissionRequestCode = 42
    
    private var httpServer: HttpServer? = null
    private var wsServer: WebSocketServer? = null
    private var isServerRunning = false
    private var startTime: Long = 0
    
    // NEW: Call and Audio Management
    private lateinit var callManager: CallManager
    private lateinit var audioManager: AudioManager
    
    private lateinit var statusText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var permStatusText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    
    // NEW: Call UI Elements
    private lateinit var callStatusText: TextView
    private lateinit var callDurationText: TextView
    private lateinit var activeCallPanel: View
    
    // ... rest of existing code
}
```

### Add Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.READ_CALL_LOG" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### Update requestPermissions()

```kotlin
private fun requestPermissions() {
    val permissions = mutableListOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE
    )
    
    // Keep existing SMS permissions for compatibility
    permissions.addAll(listOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS
    ))
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        permissions.add(Manifest.permission.FOREGROUND_SERVICE)
    }
    
    val missingPerms = permissions.filter { perm ->
        ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
    }
    
    Log.d(tag, "Checking permissions: ${permissions.size} total, ${missingPerms.size} missing")
    
    if (missingPerms.isNotEmpty()) {
        Log.d(tag, "Requesting ${missingPerms.size} permissions...")
        ActivityCompat.requestPermissions(this, missingPerms.toTypedArray(), permissionRequestCode)
    } else {
        Log.d(tag, "All permissions already granted")
        updatePermissionStatus()
        initializeCallSystem()
    }
}
```

### Initialize Call System in onCreate()

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    
    try {
        initViews()
        setupButtons()
        requestPermissions()
        startUptimeTimer()
        startSMSService()
    } catch (e: Exception) {
        Log.e(tag, "Error in onCreate", e)
    }
}

private fun initializeCallSystem() {
    try {
        // Initialize managers
        callManager = CallManager(this)
        audioManager = AudioManager()
        
        // Add callback for call state changes
        callManager.addCallback(object : CallManager.CallCallback {
            override fun onCallStateChanged(call: Call) {
                updateCallUI(call)
            }
            
            override fun onCallDuration(seconds: Long) {
                updateDurationUI(seconds)
            }
            
            override fun onCallEnded(call: Call) {
                onCallEnded(call)
            }
            
            override fun onError(message: String) {
                showCallError(message)
            }
        })
        
        Log.d(tag, "Call system initialized")
    } catch (e: Exception) {
        Log.e(tag, "Error initializing call system", e)
    }
}
```

## Step 2: Handle Call Events from WebSocket

Inside `startServer()` function, after servers start:

```kotlin
private fun startServer() {
    if (isServerRunning) {
        Log.d(tag, "Server already running")
        return
    }
    
    thread {
        try {
            // Start WebSocket server first
            wsServer = WebSocketServer(8765)
            wsServer?.start()
            Log.d(tag, "WebSocket Server started on port 8765")
            Thread.sleep(500)
            
            // NEW: Set up WebSocket command handler
            setupWebSocketHandlers()
            
            // Then HTTP server
            httpServer = HttpServer(this@MainActivity, 8080, wsServer)
            httpServer?.start()
            Log.d(tag, "HTTP Server started on port 8080")
            
            isServerRunning = true
            startTime = System.currentTimeMillis()
            
            runOnUiThread {
                try {
                    updateStatusUI()
                    statusText.text = "Online - http://YOUR_IP:8080"
                } catch (e: Exception) {
                    Log.e(tag, "Error updating UI", e)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error starting server", e)
            runOnUiThread {
                try {
                    statusText.text = "Error: ${e.message}"
                } catch (ex: Exception) {
                    Log.e(tag, "Error updating UI", ex)
                }
            }
        }
    }
}

private fun setupWebSocketHandlers() {
    // Listen for incoming WebSocket messages
    // This would require modifying WebSocketServer to accept command handlers
    // For now, implement in WebSocketServer.onMessage()
}
```

### Modify WebSocketServer.handleClient() to Parse Commands

In `WebSocketServer.kt`, update `handleClient()` to parse incoming text frames:

```kotlin
private fun handleClient(clientSocket: java.net.Socket) {
    try {
        // ... existing WebSocket upgrade code ...
        
        val client = WebSocketClient(clientSocket, output)
        clients.add(client)
        Log.d(tag, "Client connected. Total: ${clients.size}")
        
        // NEW: Listen for incoming frames
        val inputStream = clientSocket.inputStream
        val buffer = ByteArray(4096)
        
        while (isRunning && !clientSocket.isClosed) {
            try {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    parseWebSocketFrame(buffer, bytesRead)
                }
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                break
            }
        }
        
        clientSocket.close()
    } catch (e: Exception) {
        Log.e(tag, "Error handling client", e)
    } finally {
        // ... cleanup code ...
    }
}

private fun parseWebSocketFrame(buffer: ByteArray, length: Int) {
    try {
        // WebSocket frame parsing
        // Extract FIN, opcode, payload
        val fin = (buffer[0].toInt() and 0x80) != 0
        val opcode = buffer[0].toInt() and 0x0F
        
        // Handle based on opcode
        when (opcode) {
            0x1 -> {
                // Text frame - parse as JSON command
                val payloadStart = getPayloadStart(buffer)
                val payload = buffer.copyOfRange(payloadStart, length)
                val text = String(payload, Charsets.UTF_8)
                handleCommand(JSONObject(text))
            }
            0x2 -> {
                // Binary frame - audio data
                val payloadStart = getPayloadStart(buffer)
                val audioData = buffer.copyOfRange(payloadStart, length)
                audioManager.playAudio(audioData)
            }
        }
    } catch (e: Exception) {
        Log.e(tag, "Error parsing WebSocket frame", e)
    }
}

private fun handleCommand(command: JSONObject) {
    try {
        val type = command.getString("type")
        when (type) {
            "placeCall" -> {
                val phoneNumber = command.getString("phoneNumber")
                callManager.placeCall(phoneNumber)
            }
            "endCall" -> {
                callManager.endCall()
            }
        }
    } catch (e: Exception) {
        Log.e(tag, "Error handling command", e)
    }
}
```

## Step 3: UI Updates for Call Status

```kotlin
private fun updateCallUI(call: Call) {
    runOnUiThread {
        try {
            if (call.status == CallStatus.IDLE) {
                activeCallPanel?.visibility = View.GONE
            } else {
                activeCallPanel?.visibility = View.VISIBLE
                callStatusText.text = call.status.toString()
                callStatusText.text = "${call.type} - ${call.phoneNumber}"
            }
        } catch (e: Exception) {
            Log.e(tag, "Error updating call UI", e)
        }
    }
}

private fun updateDurationUI(seconds: Long) {
    runOnUiThread {
        try {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            callDurationText.text = String.format("%02d:%02d:%02d", hours, minutes, secs)
        } catch (e: Exception) {
            Log.e(tag, "Error updating duration", e)
        }
    }
}

private fun onCallEnded(call: Call) {
    runOnUiThread {
        try {
            // Broadcast call end to all connected clients
            val event = JSONObject().apply {
                put("type", "callEnded")
                put("phoneNumber", call.phoneNumber)
                put("duration", call.duration)
            }
            wsServer?.broadcastCallEvent("callEnded", event)
            
            activeCallPanel?.visibility = View.GONE
            Log.d(tag, "Call ended: ${call.phoneNumber} (${call.duration}s)")
        } catch (e: Exception) {
            Log.e(tag, "Error handling call end", e)
        }
    }
}

private fun showCallError(message: String) {
    runOnUiThread {
        try {
            Log.e(tag, "Call error: $message")
            // Show toast or error UI
        } catch (e: Exception) {
            Log.e(tag, "Error showing call error", e)
        }
    }
}
```

## Step 4: Audio Integration on Call Start

Add to `CallManager.kt` - modify `acceptCall()` method:

```kotlin
fun acceptCall(audioManager: AudioManager): Boolean {
    if (currentCall == null || currentCall?.type != CallType.INCOMING) {
        notifyError("No incoming call to accept")
        return false
    }
    
    currentCall = currentCall?.copy(status = CallStatus.ACTIVE)
    callStartTime = System.currentTimeMillis()
    notifyCallStateChanged(currentCall!!)
    
    // Start audio capture
    audioManager.startCapture { audioFrame ->
        // Send audio to WebSocket
        // Need callback to send: wsServer.broadcastAudioFrame(audioFrame)
    }
    audioManager.startPlayback()
    
    Log.d(tag, "Call accepted from ${currentCall?.phoneNumber}")
    startDurationTimer()
    return true
}
```

## Step 5: Testing Checklist

### Unit Tests
```kotlin
// Test CallManager state transitions
@Test
fun testPlaceCall() {
    val manager = CallManager(context)
    manager.placeCall("+46701234567")
    
    assert(manager.isCallOngoing())
    assert(manager.getCurrentCall()?.type == CallType.OUTGOING)
}

// Test AudioManager
@Test
fun testAudioCapture() {
    val audioManager = AudioManager()
    var framesReceived = 0
    
    audioManager.startCapture { frame ->
        framesReceived++
    }
    
    Thread.sleep(1000)
    audioManager.stopCapture()
    
    assert(framesReceived > 0)
}
```

### Integration Tests
1. Start ANCS host app
2. Open client HTML in browser
3. Dial a phone number
4. Verify:
   - WebSocket connection established
   - Call initiated on host device
   - Call status updates on client
   - Duration timer increments
   - Call history recorded

### Real Device Tests
1. Test on primary Android device
2. Test with real SIM call
3. Test from Sharp 207SH client
4. Verify audio transmission
5. Test WiFi hotspot stability

## Permissions Checklist

- [ ] `CALL_PHONE` - Make calls
- [ ] `READ_CALL_LOG` - Access call history  
- [ ] `RECORD_AUDIO` - Mic access
- [ ] `MODIFY_AUDIO_SETTINGS` - Audio routing
- [ ] `INTERNET` - WebSocket
- [ ] `ACCESS_NETWORK_STATE` - Network info

## Next Steps After Integration

1. **Test Real Calls** (Week 1)
   - Place call from client
   - Verify audio routing
   - Test call termination
   - Check call history

2. **Incoming Calls** (Week 2)
   - Handle incoming call detection
   - Forward to client
   - Auto-answer capability
   - Call rejection

3. **Audio Optimization** (Week 3)
   - Latency testing
   - Echo cancellation
   - Jitter buffer
   - Codec compression (Opus)

4. **Error Handling** (Week 4)
   - Reconnection logic
   - Call failure recovery
   - Network issue handling
   - Graceful degradation

## Debugging Tips

**Enable Verbose Logging:**
```kotlin
Log.d(tag, "CallManager: $message")
Log.d(tag, "AudioManager: $message")
Log.d(tag, "WebSocket: $message")
```

**Monitor Audio Levels:**
```kotlin
// In AudioManager.startCapture()
val maxAmplitude = audioRecord?.maxAmplitude
Log.d(tag, "Audio level: $maxAmplitude")
```

**Verify WebSocket Frames:**
```kotlin
// Log frame hex dump
Log.d(tag, "Frame: ${buffer.contentToString()}")
```

**Test Call State Machine:**
```kotlin
Log.d(tag, "Call state: ${currentCall?.status}")
Log.d(tag, "Call type: ${currentCall?.type}")
Log.d(tag, "Duration: ${currentCall?.duration}s")
```

---

**Status**: Ready for MainActivity integration
**Estimated Time**: 4-6 hours of focused development
**Difficulty**: Medium (audio handling is trickiest)
