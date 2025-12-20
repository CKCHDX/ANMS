# ANMS Implementation Notes

## Architecture Overview

### Components

#### 1. Android Host Application

**MainActivity**
- Entry point for the application
- Manages permission requests using modern Android APIs
- Initializes WebSocket server and SMS manager
- Provides UI for sending SMS and viewing message history
- Updates server status and message display in real-time

**WebSocketServer** (network/)
- Built on org.java-websocket library
- Listens on port 8765 (configurable)
- Maintains active client connections
- Broadcasts SMS messages to all connected clients
- Handles client connection/disconnection events
- Parses incoming messages from clients

**SmsManager** (sms/)
- Wrapper around Android's native SmsManager
- Manages SMS broadcast receiver lifecycle
- Sends SMS through SIM card
- Notifies listeners of received SMS
- Handles permission-guarded operations

**SmsReceiver** (sms/)
- BroadcastReceiver that listens for incoming SMS
- Extracts sender phone number, message body, and timestamp
- Works on Android API 19+ with compatibility
- Handles both single and multi-part SMS
- Calls callback for each received message

#### 2. Web Client Application

- Responsive HTML5 interface
- WebSocket connection management with auto-reconnect
- Message sending and receiving
- Connection status indicator
- Local storage for host configuration
- Vanilla JavaScript ES6+ implementation
- No external dependencies

## Protocol Specification

### Message Format

**Client to Server (SMS Send Request)**
```
<PHONE_NUMBER>|<MESSAGE_BODY>
Example: +46701234567|Hello from Aquos A207SH!
```

**Server to Client (Message Broadcast)**
```
<PHONE_NUMBER>|<MESSAGE_BODY>|<TIMESTAMP_MS>|<IS_OUTGOING>
Example: +46701234567|Thanks for the message!|1671234567890|false
```

## Technical Decisions

### WebSocket Protocol

Why WebSocket?
- Bidirectional real-time communication
- Lower latency than HTTP polling
- Efficient for mobile networks
- Native browser support
- Works on feature phones with basic WiFi

### Port 8765

- Non-privileged port (doesn't require root)
- Not commonly used by other services
- Easy to remember
- Can be changed in configuration

### Pipe Delimiter Format

Advantages:
- Lightweight parsing
- No JSON overhead
- Works on limited devices
- Fast to serialize/deserialize

Future: Plan to migrate to JSON for v0.2.0

## Performance Considerations

### Memory

- Base RAM: ~50-100 MB
- Per 100 messages: ~50 KB
- Per 1000 message history: ~5 MB

### Network

- Message overhead: ~60 bytes per message
- Bandwidth: <1 KB/sec typical usage

## Security Considerations

WARNING: NO ENCRYPTION - Use only on trusted networks

- Messages sent in plain text
- No authentication required
- No SSL/TLS (WebSocket not WSS)
- Any client on local network can send SMS

Future mitigations:
- Implement WSS (WebSocket Secure)
- Add client authentication
- Encrypt messages with AES
- Rate limiting on message sending

## Known Issues

### 1. Multi-part SMS

Issue: Long SMS split into multiple SMS
Current: Processed as separate messages
Future: Implement multi-part SMS reassembly

### 2. No Message Persistence

Issue: Messages cleared on app restart
Current: Works as designed for MVP
Future: Implement Room database

### 3. No Contact Management

Issue: Manual phone number entry
Current: Works for simple use case
Future: Integration with contacts app

## Code Quality

### Kotlin Features

- Data classes for type safety
- Coroutines for async operations
- Extension functions
- Lambda expressions
- Scope functions (apply, let, run)

### Android Best Practices

- View Binding for type-safe UI access
- Modern permission handling APIs
- Activity lifecycle awareness
- Resource cleanup in onDestroy()
- BroadcastReceiver properly exported
- Proper context scoping

### Web Standards

- ES6+ modern JavaScript
- HTML5 semantic markup
- CSS3 for styling
- Mobile-first design
- No external dependencies

## Future Roadmap

### v0.2.0 (SMS Receiving)
- Implement real-time SMS receiving
- SMS multi-part message handling
- Message persistence with Room
- Delivery status tracking

### v0.3.0 (Security)
- WSS (WebSocket Secure) support
- Client authentication
- Message encryption
- Rate limiting

### v0.4.0 (Features)
- Contact integration
- Message scheduling
- Group messaging
- Media sharing
- Voice messages

## Debugging

View logs:
```bash
adb logcat | grep "WebSocket\|SMS\|ANMS"
```

Test WebSocket directly:
```bash
websocat ws://192.168.1.100:8765
```

---

Author: Alex Jonsson (@CKCHDX)
Last Updated: December 20, 2025
Version: 0.1.0