# ANMS Quick Start Guide

## 🚀 Getting Started in 5 Minutes

### Prerequisites
- Android Studio (Flamingo or newer)
- Java 11+
- Android SDK API 21+
- Your Z Fold 6 or Android device with SIM card
- Aquos A207SH or any WiFi-enabled device
- Same WiFi network for both devices

## Host Device Setup (Z Fold 6)

### Step 1: Prepare Android Studio Project

```bash
# Clone the repository
git clone https://github.com/CKCHDX/ANMS.git
cd ANMS

# Open in Android Studio
open -a "Android Studio" .
```

### Step 2: Update your existing MainActivity

Replace your empty `MainActivity.kt` with the one from the repository:
- Copy `app/src/main/java/com/example/anms/MainActivity.kt`
- Overwrite your empty activity

### Step 3: Add All Source Files

Create these directories and files:
```
app/src/main/java/com/example/anms/
├── MainActivity.kt                    ✓
├── Message.kt                         ← Add
├── MessageAdapter.kt                  ← Add
├── sms/
│   ├── SmsManager.kt                  ← Add
│   └── SmsReceiver.kt                 ← Add
└── network/
    └── WebSocketServer.kt             ← Add
```

### Step 4: Update Layout Files

Replace your existing layouts:
```
app/src/main/res/layout/
├── activity_main.xml                 ← Update
└── item_message.xml                  ← Add
```

### Step 5: Update Manifest

Replace `app/src/main/AndroidManifest.xml` with the new version that includes:
- SMS permissions
- Network permissions
- SMS receiver configuration

### Step 6: Update build.gradle.kts

Replace your `app/build.gradle.kts` to include:
```kotlin
// Key dependencies
implementation("org.java-websocket:Java-WebSocket:1.5.4")
implementation("androidx.recyclerview:recyclerview:1.3.1")
```

### Step 7: Build and Run

```bash
# In Android Studio terminal
./gradlew assembleDebug

# Or click Run in Android Studio
# Connect your device and press Shift+F10
```

### Step 8: Grant Permissions

1. Launch the app
2. Grant all requested permissions:
   - Send SMS
   - Receive SMS
   - Read SMS
   - Read Contacts
   - Network Access

### Step 9: Find Your Host IP

```bash
# On your host device (Z Fold 6), find local IP
# Settings → Wi-Fi → (Your Network) → IP Address
# Example: 192.168.1.100
```

## Client Device Setup (Aquos A207SH)

### Step 1: Connect to WiFi

1. Go to WiFi settings
2. Connect to the same network as your Z Fold 6
3. Note the network name (SSID)

### Step 2: Load the Web Client

**Option A: Using Server File Serving**

The Android app serves the client at:
```
http://<HOST_IP>:8765/client
```

But first, you'll need to set up file serving. For now, use Option B.

**Option B: Local File (Recommended for Initial Setup)**

1. Save `client/index.html` to the A207SH:
   - Transfer via Bluetooth
   - Email to yourself
   - USB cable transfer
   - Micro SD card

2. Open the HTML file in your mobile browser:
   - File → Open
   - Or use file manager
   - Tap `index.html`

### Step 3: Enter Host Address

When prompted:
```
Enter host IP (e.g., 192.168.1.100:8765):
192.168.1.100:8765
```

⚠️ **Important**: Include the port number `:8765`

### Step 4: Verify Connection

You should see:
- ✅ Green status dot
- "Connected" message
- Success notification

## Testing the Connection

### From Host (Z Fold 6)

1. Open the ANMS app
2. See "WebSocket Server Running (Port 8765)"
3. Enter a phone number (can be any number for testing)
4. Type a test message
5. Click "Send SMS"
6. Message appears in the list

### From Client (Aquos A207SH)

1. Enter a phone number in the input field
2. Type a message
3. Click "Send"
4. Message appears in the chat
5. On host device, message shows in app
6. Host device sends the actual SMS

## Troubleshooting

### "Connection Error" on Client

**Problem**: Can't connect to host

**Solutions**:
1. Check both devices on same WiFi network
2. Verify host IP address is correct
3. Make sure port `:8765` is included
4. Ensure host app is running (green status indicator)
5. Check firewall settings (port 8765 should be allowed)

### "Permissions Required" on Host

**Problem**: App won't start, asks for permissions

**Solutions**:
1. Grant all permissions when prompted
2. Go to Settings → Apps → ANMS → Permissions
3. Enable all permissions manually
4. Restart the app

### Messages Not Appearing

**Problem**: Messages sent but not received

**Solutions**:
1. Check connection status (should be green)
2. Verify phone number format (international format recommended)
3. Check device has actual SIM card active
4. Try sending from host first
5. Check host app message list

### WebSocket Port Already in Use

**Problem**: "Address already in use" error

**Solutions**:
1. Restart the app
2. Restart your device
3. Kill background ANMS processes
4. Try using a different port (edit WebSocketServer.kt)

## File Serving Setup (Advanced)

To serve the client from the host:

### Option 1: Simple HTTP Server in Kotlin

```kotlin
// In WebSocketServer.kt
// Add HTTP file serving alongside WebSocket
```

### Option 2: Use NDK with lightweight HTTP server

This is beyond the scope of v0.1.0 but planned for v0.2.0.

## Security Notes

⚠️ **WARNING**: This version is NOT encrypted

- Use only on trusted home networks
- Don't use on public WiFi
- Messages are sent in plain text
- Implement HTTPS/WSS in production

## Next Steps

After confirming everything works:

1. **Test SMS Receiving**:
   - Have someone text your host device
   - Check if message appears in ANMS app
   - Verify it shows on client device

2. **Multiple Messages**:
   - Send various message lengths
   - Send to different phone numbers
   - Test with special characters

3. **Connection Stability**:
   - Leave connected for extended periods
   - Move devices around within WiFi range
   - Test reconnection after network interruption

## Performance Tips

- Keep message history <1000 items for better performance
- Use device's native browser (Opera Mini is lighter)
- Close other apps to free RAM
- Stay within WiFi range (typically 30-50 meters)

## Useful ADB Commands

```bash
# Install the app
adb install app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat | grep ANMS

# Forward port for local testing
adb forward tcp:8765 tcp:8765

# Check if app is running
adb shell ps | grep anms

# Clear app data
adb shell pm clear com.example.anms
```

## Network Diagnostics

```bash
# Find all devices on network
arp -a

# Test connectivity to host
ping 192.168.1.100

# Check if port 8765 is listening
netstat -an | grep 8765

# Test WebSocket connection
websocat ws://192.168.1.100:8765
```

## Getting Help

- 📖 Full documentation: [README.md](README.md)
- 🐛 Report issues: [GitHub Issues](https://github.com/CKCHDX/ANMS/issues)
- 💬 Ask questions: [GitHub Discussions](https://github.com/CKCHDX/ANMS/discussions)
- 🌐 Visit: [oscyra.solutions](https://oscyra.solutions/)

## Success Checklist

- [ ] Android Studio project builds successfully
- [ ] App installs on Z Fold 6
- [ ] All permissions granted
- [ ] WebSocket server shows as running
- [ ] A207SH can access the web client
- [ ] Can connect from A207SH to host
- [ ] Can send test message from host
- [ ] Can send test message from client
- [ ] Messages appear in both locations

---

🎉 **You're all set!** Enjoy using ANMS on your retro phones!
