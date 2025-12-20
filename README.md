# ANMS - Aquos Network Messaging System

ANMS enables the **Aquos A207SH** (a feature phone with WiFi but no SIM) to function as a **remote SMS terminal** connected to a **host device (Z Fold 6)** with a SIM card. It provides a seamless chat experience where the A207SH user can send and receive real SMS messages as if the A207SH had its own SIM card.

## Features

### Host Application (Android)
- 📱 Native Android app with SMS management UI
- 🌐 WebSocket server for real-time client communication
- 📨 SMS sending and receiving with real-time updates
- 💬 Message history display with timestamps
- 🔌 Network connectivity monitoring
- ✅ Full permission handling for SMS and network access
- 🎨 Clean and intuitive material design UI

### Client Application (Web)
- 🌍 Browser-based interface for Aquos A207SH
- 🔐 Secure WebSocket connection to host
- 📱 Responsive design optimized for small screens
- 🔄 Persistent connection with auto-reconnect
- 📦 Message delivery confirmation
- ⚡ Simple and fast messaging interface

## Architecture

```
┌────────────────────────────────────────────┐
│   Aquos A207SH (WiFi enabled)              │
│   ┌──────────────────────────────────────┐ │
│   │   Web Browser Client                  │ │
│   │   (HTML/CSS/JavaScript)               │ │
│   └──────────────────────┬────────────────┘ │
│                          │                  │
│              WebSocket (WiFi)               │
│                          │                  │
└──────────────────────────┼───────────────────┘
                           │
                           │
┌──────────────────────────▼───────────────────┐
│   Z Fold 6 (With SIM card)                   │
│   ┌──────────────────────────────────────┐  │
│   │  Android Host App                     │  │
│   │  ┌──────────────────────────────────┐│  │
│   │  │ WebSocket Server (8765)          ││  │
│   │  └──────────────────────────────────┘│  │
│   │  ┌──────────────────────────────────┐│  │
│   │  │ SMS Manager                      ││  │
│   │  │ - Send/Receive SMS               ││  │
│   │  │ - Real-time updates              ││  │
│   │  └──────────────────────────────────┘│  │
│   │  ┌──────────────────────────────────┐│  │
│   │  │ Message History                  ││  │
│   │  └──────────────────────────────────┘│  │
│   └──────────────────────────────────────┘  │
│   │         ▼                                │
│   │    [SIM Card / GSM Network]             │
└───────────────────────────────────────────┘
```

## Project Structure

```
ANMS/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/anms/
│   │   │   │   ├── MainActivity.kt              # Main activity with UI
│   │   │   │   ├── Message.kt                   # Message data class
│   │   │   │   ├── MessageAdapter.kt            # RecyclerView adapter
│   │   │   │   ├── sms/
│   │   │   │   │   ├── SmsManager.kt            # SMS handling
│   │   │   │   │   └── SmsReceiver.kt           # Broadcast receiver
│   │   │   │   └── network/
│   │   │   │       └── WebSocketServer.kt       # WebSocket server
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_main.xml        # Main UI layout
│   │   │   │   │   └── item_message.xml         # Message item layout
│   │   │   │   └── values/
│   │   │   │       └── strings.xml              # String resources
│   │   │   └── AndroidManifest.xml              # App manifest
│   │   └── test/                                # Unit tests
│   └── build.gradle.kts                         # Build configuration
├── client/
│   └── index.html                               # Web client application
├── README.md                                    # This file
└── .gitignore
```

## Development Status

- ✅ **v0.1.0 (Alpha)**: Core SMS sending and WebSocket communication
- ⏳ **v0.2.0**: SMS receiving with real-time updates
- ⏳ **v0.3.0**: Full chat application with history persistence
- ⏳ **v0.4.0**: Multi-device support and advanced features

## Setup Instructions

### Host Device (Z Fold 6)

1. **Clone and setup in Android Studio**
   ```bash
   git clone https://github.com/CKCHDX/ANMS.git
   cd ANMS
   ```

2. **Build and install the APK**
   - Open the project in Android Studio
   - Connect your Z Fold 6 via USB or use an emulator
   - Click "Run" or use: `./gradlew installDebug`

3. **Grant required permissions**
   - SMS permissions
   - Network access
   - Contact reading

4. **Start the application**
   - The WebSocket server will start on port 8765
   - Note the device's local network IP address

### Client Device (Aquos A207SH)

1. **Connect to the same WiFi network** as the Z Fold 6

2. **Open a browser** (Opera Mini, UC Browser, or native browser)

3. **Navigate to**: `http://<HOST_IP>:8765/client` or load the HTML file locally

4. **Enter the host IP address** when prompted (e.g., `192.168.1.100:8765`)

5. **Start messaging!**

## Usage

### Sending SMS
1. Enter the recipient's phone number
2. Type your message
3. Click "Send SMS" or press Ctrl+Enter
4. The message is sent via the host device's SIM card
5. Delivery confirmation appears in the chat

### Receiving SMS
- Incoming SMS messages appear automatically in the chat
- The host device broadcasts messages to all connected clients
- Message timestamps and sender information is preserved

## Technical Details

### WebSocket Protocol

**Message Format (Client → Host)**:
```
<PHONE_NUMBER>|<MESSAGE_BODY>
```
Example: `+46701234567|Hello from A207SH!`

**Message Format (Host → Client)**:
```
<PHONE_NUMBER>|<MESSAGE_BODY>|<TIMESTAMP>|<IS_OUTGOING>
```
Example: `+46701234567|Message received!|1671234567890|false`

### Permissions Required

**Android Host**:
- `android.permission.SEND_SMS` - Send SMS messages
- `android.permission.RECEIVE_SMS` - Receive SMS messages
- `android.permission.READ_SMS` - Access SMS database
- `android.permission.READ_CONTACTS` - Display contact information
- `android.permission.INTERNET` - WebSocket communication
- `android.permission.ACCESS_NETWORK_STATE` - Network monitoring

## Dependencies

### Host (Android)
- AndroidX AppCompat 1.6.1
- Java-WebSocket 1.5.4
- RecyclerView 1.3.1
- Kotlin Coroutines

### Client (Web)
- HTML5 WebSocket API
- Modern CSS3 (Grid, Flexbox, Animations)
- Vanilla JavaScript (ES6+)

## Known Limitations

- 🔒 WebSocket server runs without SSL/TLS (use only on trusted networks)
- ⚠️ Message history not persisted (cleared on app restart)
- 🔐 No encryption for transmitted messages
- 📱 Single host device support (one WebSocket server per app instance)
- 📵 Limited to one active client connection per send operation

## Future Enhancements

- [ ] End-to-end message encryption
- [ ] Persistent message database
- [ ] Multi-client support
- [ ] Contact management and quick reply
- [ ] Message scheduling
- [ ] Group messaging
- [ ] Media sharing (photos, etc.)
- [ ] Voice message support
- [ ] User authentication
- [ ] SSL/TLS secure connections

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Author

**Alex Jonsson** - [@CKCHDX](https://github.com/CKCHDX)

## Support

For issues, questions, or suggestions:
- 📝 Open an issue on [GitHub Issues](https://github.com/CKCHDX/ANMS/issues)
- 💬 Check existing discussions in [GitHub Discussions](https://github.com/CKCHDX/ANMS/discussions)
- 🌐 Visit [oscyra.solutions](https://oscyra.solutions/)

## Changelog

### v0.1.0 (2025-12-20)
- Initial Android host application
- WebSocket server implementation
- SMS sending functionality
- Web client interface
- Real-time message display

---

**ANMS**: Bringing retro phones to the modern messaging era. 📱✨