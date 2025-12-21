# ANCS Implementation - Changes from ANMS Base

## Overview
ANCS (Aquos Network Calling System) transforms the ANMS messaging foundation into a full-featured calling system with real-time audio streaming, call management, and dial pad interface.

## New Kotlin Modules Created

### 1. **Data Classes** (`app/src/main/java/com/example/ancs/data/`)

#### `Call.kt`
- Data class representing a call with metadata
- Call types: INCOMING, OUTGOING, MISSED
- Call statuses: IDLE, DIALING, RINGING, ACTIVE, ENDED, FAILED
- Fields: id, phoneNumber, duration, timestamp, type, status

### 2. **Audio Management** (`app/src/main/java/com/example/ancs/audio/`)

#### `AudioManager.kt` - NEW
Complete audio I/O system for capturing and playing voice:

**Capture Side:**
- Uses `AudioRecord` at 16kHz, 16-bit PCM, Mono
- Buffer size: 4096 bytes
- Real-time callback `onAudioFrame()` for each chunk
- Thread-safe operation with error handling

**Playback Side:**
- Uses `AudioTrack` for speaker output
- Voice communication audio attribute
- Handles incoming audio frames from WebSocket
- Supports starting/stopping without disconnecting

**Key Methods:**
- `startCapture(onAudioFrame)` - Begin mic recording
- `stopCapture()` - Stop recording
- `startPlayback(onPlaybackReady)` - Initialize speaker
- `playAudio(audioFrame)` - Play received audio
- `stopPlayback()` - Stop playback
- `release()` - Clean up all resources

### 3. **Call Management** (`app/src/main/java/com/example/ancs/calling/`)

#### `CallManager.kt` - NEW
Stateful call lifecycle management:

**Call Lifecycle:**
1. `placeCall(phoneNumber)` - Initiate outgoing call
2. `acceptCall()` - Accept incoming call
3. `endCall()` - Terminate active call
4. `rejectCall()` - Decline incoming call
5. `handleIncomingCall(phoneNumber)` - Receive incoming

**State Tracking:**
- Current call state (`getCurrentCall()`)
- Call history (`getCallHistory()`)
- Active/Ongoing state checks
- Duration timer with 1s precision

**Callbacks:**
- `onCallStateChanged()` - State transitions
- `onCallDuration()` - Real-time duration updates
- `onCallEnded()` - Call completion
- `onError()` - Error reporting

**Features:**
- UUID-based call IDs
- Start time tracking
- Duration calculation
- Call history with up to N recent calls
- Callback system for UI updates

### 4. **Network Layer** - MODIFIED

#### `WebSocketServer.kt` - REWRITTEN
Completely rewritten for call and audio support:

**Previous (ANMS):**
- Text-only message frames
- SMS-specific message format: `PHONE|MESSAGE`
- Single broadcast method

**New (ANCS):**

**Text Frames (Call Events):**
```java
broadcastCallEvent(eventType, data)
// Sends JSON-formatted call events
// Types: "placeCall", "callStarted", "callConnected", "callEnded", "incomingCall"
```

**Binary Frames (Audio):**
```java
broadcastAudioFrame(audioData)
// Sends PCM16 audio frames as WebSocket binary frames
// Opcode 0x82 (binary frame)
// Variable-length payload handling
```

**Frame Encoding:**
- Text: UTF-8 encoded JSON with opcode 0x81
- Binary: Raw PCM16 audio with opcode 0x82
- Handles payload length: < 126, 126-65536, > 65536 bytes
- Proper WebSocket framing with FIN bit

## Client Changes

### `client/index.html` - REWRITTEN

**Previous (ANMS):**
- Chat-style interface with message history
- Input: phone number + message text
- Output: scrollable message list
- Focus on SMS reception/transmission

**New (ANCS):**

**UI/UX:**
- Retro phone aesthetic with notch and rounded corners
- Cyberpunk-inspired color scheme (dark green/cyan)
- Full numeric dial pad (0-9, *, #)
- Large phone number display (32px monospace font)
- Call duration timer (HH:MM:SS format)
- Status indicator (connected/offline)
- Call history (last 5 calls)
- Four buttons: CALL, END CALL, CLEAR, BACKSPACE

**JavaScript Logic:**

**State Management:**
- `isCallActive` - Track active call
- `currentNumber` - Buffered dial input
- `callStartTime` - Call start timestamp
- `callHistory` - Array of previous calls
- `durationInterval` - Timer handle

**Event Handling:**
- Keypad input: dial number assembly
- CALL button: initiate call via WebSocket
- END CALL button: terminate call
- CLEAR button: clear dialed number
- BACKSPACE button: remove last digit

**WebSocket Messages:**

Outgoing (to host):
```json
{
  "type": "placeCall",
  "phoneNumber": "+46701234567"
}

{
  "type": "endCall",
  "phoneNumber": "+46701234567"
}
```

Incoming (from host):
```json
{
  "type": "callStarted",
  "data": { "phoneNumber": "..." },
  "timestamp": 1234567890
}

{
  "type": "callConnected",
  "data": { "phoneNumber": "..." },
  "timestamp": 1234567890
}

{
  "type": "callEnded",
  "data": { "phoneNumber": "...", "duration": 45 },
  "timestamp": 1234567890
}
```

**Audio Handling:**
- Binary WebSocket frames for PCM16 audio
- `handleAudioFrame()` - Receive audio from host
- Ready for WebAudio API integration

## Architecture Differences

| Aspect | ANMS | ANCS |
|--------|------|------|
| **Primary Use** | SMS messaging | Phone calling |
| **Data Format** | Text only | Text + Binary audio |
| **Audio** | None | Bidirectional PCM16 |
| **UI** | Chat-style | Phone dial pad |
| **Call State** | N/A | Full lifecycle tracking |
| **Duration** | Message history | Real-time call timer |
| **WebSocket Frames** | Text only | Text (events) + Binary (audio) |
| **Permission Focus** | SMS, network | CALL_PHONE, RECORD_AUDIO, MODIFY_AUDIO |

## Integration Points with Existing ANMS Code

### Reused:
- WebSocket handshake protocol (unchanged)
- HTTP server infrastructure
- Main Activity structure and permission flow
- Build system and dependencies

### Replaced:
- `Message.kt` → `Call.kt`
- `MessageAdapter.kt` → (call UI in MainActivity)
- `SMSListenerService.kt` → (call state callbacks)
- `WebSocketServer` broadcast methods
- `client/index.html` entirely

## Next Implementation Steps

### Phase 1: Host-Side Call Handling
- [ ] Integrate `CallManager` with `MainActivity`
- [ ] Hook into `TelecomManager` for call state
- [ ] Display incoming call notifications
- [ ] Add call UI to Android app
- [ ] Test with real calls

### Phase 2: Audio Streaming
- [ ] Start `AudioManager` capture on call connect
- [ ] Route audio through WebSocket
- [ ] Handle audio latency and buffering
- [ ] Test audio quality over WiFi
- [ ] Implement jitter buffer

### Phase 3: Client Audio Playback
- [ ] Implement WebAudio API playback
- [ ] Handle binary frame decoding
- [ ] Test on feature phone browsers
- [ ] Optimize for low-power devices
- [ ] Add volume controls

### Phase 4: Testing & Polish
- [ ] End-to-end call testing
- [ ] Audio quality testing
- [ ] Performance profiling
- [ ] Error handling and recovery
- [ ] Documentation

## File Structure

```
app/src/main/java/com/example/ancs/
├── data/
│   └── Call.kt                    # NEW
├── audio/
│   └── AudioManager.kt            # NEW
├── calling/
│   └── CallManager.kt             # NEW
├── network/
│   ├── WebSocketServer.kt         # REWRITTEN
│   └── HttpServer.kt              # UNCHANGED
├── MainActivity.kt                # TO BE UPDATED
└── [other existing files]

client/
└── index.html                     # REWRITTEN
```

## Key Technical Decisions

1. **Audio Format**: 16kHz PCM16 Mono
   - Standard for VoIP
   - Lightweight for retro phones
   - Good quality/bandwidth tradeoff

2. **WebSocket for Audio**
   - Single connection for both control and audio
   - Simpler than separate streams
   - Real-time capable over LAN WiFi
   - Future: Add Opus compression for optimization

3. **Separate Call Management Layer**
   - Allows swapping underlying call provider
   - Clean separation from audio/network
   - Enables mock calls for testing

4. **Callback Pattern**
   - Loose coupling between modules
   - Easy for UI binding
   - Supports multiple listeners
   - Standard Android pattern

## Testing Recommendations

1. **Unit Tests**
   - `CallManager` state transitions
   - `AudioManager` buffer handling
   - WebSocket frame encoding

2. **Integration Tests**
   - Host-client connection
   - Call placement and termination
   - Audio streaming
   - Reconnection scenarios

3. **Real Device Tests**
   - Sharp 207SH with WiFi
   - Various Android versions (10, 11, 12, 13)
   - Different WiFi conditions (home, hotspot)
   - Edge cases (poor signal, disconnection)

## Performance Targets

- **Latency**: < 300ms (WiFi LAN)
- **CPU**: < 30% (audio operations)
- **Memory**: < 150MB per call
- **Bitrate**: ~256 kbps (uncompressed)
- **Call setup time**: < 2 seconds

---

**Branch**: `ANCS`
**Status**: v0.1.0 (Core implementation complete, integration pending)
**Next**: Integrate with MainActivity and test real calls
