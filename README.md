# ANMS - Aquos Network Messaging Service

## Overview

**ANMS** (Aquos Network Messaging Service) is a **remote SMS gateway and messaging platform** that allows the Aquos A207SH (a WiFi-only keitai phone) to send and receive real SMS messages remotely by leveraging the Samsung Z Fold 6's SIM card and cellular connectivity.

The system enables **two-way SMS conversations** between the A207SH and any phone number through a web-based chat interface, with all messages routed through the Z Fold 6 as the host device.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   A207SH (Keitai)                       │
│              Android 10 + WiFi Only                      │
│         (No SIM, No Cellular, Browser Only)             │
│                                                         │
│    Web Browser → http://192.168.1.50:5000              │
└─────────────────────────────────────────────────────────┘
                           │
                    Local WiFi Network
                           │
┌─────────────────────────────────────────────────────────┐
│              Z Fold 6 (Host Device)                     │
│           Android 16 (OneUI 8) + SIM Card              │
│                                                         │
│    Flask Server (Port 5000)                             │
│    ├─ SMS Send (via SMS Service)                        │
│    ├─ SMS Receive (Monitor Inbox)                       │
│    └─ Chat Interface                                    │
│                                                         │
│    Termux (Shell Access to SMS APIs)                    │
└─────────────────────────────────────────────────────────┘
                           │
                      Z Fold 6 SIM Card
                           │
        ┌───────────────────┴───────────────────┐
        │                                       │
    ┌─────────┐                            ┌─────────┐
    │ Phone # │  (Real SMS Network)        │ Phone # │
    │  +46... │  ←──────────────────→     │  +46... │
    └─────────┘                            └─────────┘
   (Target Contact)                    (Target Contact)
```

## Key Features

### 1. Remote Chat Interface
- Web-based chat UI accessible from A207SH browser
- Clean conversation view with message history
- Real-time message updates
- Character counter (0/160)
- Connection status indicator

### 2. Two-Way Messaging
**Send SMS:**
- A207SH user enters phone number and message
- Message is sent through Z Fold 6's SIM card
- Real SMS delivered to any phone number (globally)
- Confirmation of successful send

**Receive SMS:**
- When target number sends SMS to Z Fold 6
- Message appears in real-time in A207SH chat interface
- Automatically displayed in conversation thread
- User can reply immediately

### 3. Multi-Contact Support
- Switch between different contact conversations
- Each conversation thread maintained separately
- Contact list with last message preview
- Conversation history per contact

### 4. Network Features
- **Local WiFi Only** - A207SH connects via WiFi to Z Fold 6 (no cellular needed)
- **Automatic IP Discovery** - Easy connection setup
- **Fallback Mechanisms** - Graceful error handling
- **Session Management** - Persistent conversations

## System Components

### Client Side (A207SH)
- **Device:** Aquos A207SH (Android 10 + Termux optional)
- **Interface:** Web browser (built-in)
- **Connectivity:** WiFi only
- **Requirements:** None (just HTTP access to Z Fold 6)

### Server Side (Z Fold 6)
- **Device:** Samsung Galaxy Z Fold 6 (Android 16 + OneUI 8)
- **Runtime:** Termux (Linux environment)
- **Framework:** Python 3 + Flask
- **SMS Access:** Android SMS APIs via `am` intents and shell commands
- **Requirements:** Active SIM card with SMS capability

## Installation

### Z Fold 6 (Host Setup)

1. **Install Termux** (from F-Droid)
   ```bash
   pkg update
   pkg install python
   pip install flask
   ```

2. **Create ANMS Server**
   ```bash
   nano ~/anms_server.py
   # (Paste ANMS server code)
   ```

3. **Find Local IP**
   ```bash
   ip addr show wlan0 | grep "inet " | awk '{print $2}' | cut -d'/' -f1
   # Example: 192.168.1.50
   ```

4. **Start ANMS Server**
   ```bash
   python ~/anms_server.py
   ```

### A207SH (Client Access)

1. **Open Browser** (any browser on A207SH)

2. **Navigate to Host**
   ```
   http://192.168.1.50:5000
   ```

3. **Start Chatting**
   - Enter contact phone number
   - Type message
   - Send → goes through Z Fold 6 SIM
   - Receive messages from any number in real-time

## Usage Workflow

### Sending a Message

1. A207SH user opens browser to `http://192.168.1.50:5000`
2. Enters phone number (e.g., `0730895105` or `+46730895105`)
3. Types SMS message (max 160 characters)
4. Clicks **"Send Message"** button
5. Message transmitted through Z Fold 6 SIM card
6. Real SMS delivered to recipient phone number
7. Status confirmation displayed on A207SH

### Receiving Messages

1. Contact sends SMS to Z Fold 6 phone number
2. Z Fold 6 receives SMS in system SMS database
3. ANMS server monitors for new incoming SMS
4. Message automatically appears in A207SH chat interface
5. User sees notification and message content
6. User can reply by typing response

### Conversation Management

- **Contact List:** Shows all active conversations
- **Chat Thread:** Full message history with timestamps
- **Delete Conversation:** Clear chat history with specific contact
- **Multiple Contacts:** Switch between different contacts seamlessly

## Technical Details

### SMS Transmission Flow

```
A207SH Browser
    ↓ HTTP POST
    │  phone: "+46730895105"
    │  message: "Hello"
    ↓
Flask Server (Z Fold 6:5000)
    ↓
Validate Message (max 160 chars)
    ↓
Execute 'am' Intent Command
    ├─ action: android.intent.action.SENDTO
    ├─ data: sms:+46730895105
    ├─ extra: sms_body="Hello"
    ↓
Android SMS Service
    ↓
Z Fold 6 SIM Card
    ↓
Real SMS Network
    ↓
Target Phone Number (+46730895105)
```

### SMS Reception Flow

```
Target Phone Number
    ↓
Real SMS Network
    ↓
Z Fold 6 SIM Card
    ↓
Android SMS Database
    ↓
ANMS Server (Polling)
    ├─ Check for new messages
    ├─ Parse sender & content
    ├─ Store in conversation thread
    ↓
Flask API
    ↓
A207SH Browser (Real-time Update)
    ↓
User sees incoming message
```

### Phone Number Normalization

All phone numbers automatically converted to Swedish format:
- `0730895105` → `+46730895105`
- `730895105` → `+46730895105`
- `+46730895105` → `+46730895105` (unchanged)

## API Endpoints

### Send Message
```
POST /api/send-message
Content-Type: application/json

{
  "contact": "+46730895105",
  "message": "Hello there!"
}

Response: {
  "status": "sent",
  "contact": "+46730895105",
  "timestamp": "2025-12-19 19:45:00"
}
```

### Get Conversation
```
GET /api/conversation?contact=%2B46730895105

Response: {
  "contact": "+46730895105",
  "messages": [
    {
      "timestamp": "2025-12-19 19:40:15",
      "sender": "A207SH",
      "body": "Hello"
    },
    {
      "timestamp": "2025-12-19 19:41:30",
      "sender": "+46730895105",
      "body": "Hi! How are you?"
    }
  ]
}
```

### Get Contact List
```
GET /api/contacts

Response: {
  "contacts": [
    {
      "phone": "+46730895105",
      "last_message": "Hi! How are you?",
      "last_timestamp": "2025-12-19 19:41:30",
      "unread": 1
    },
    {
      "phone": "+46701234567",
      "last_message": "See you tomorrow",
      "last_timestamp": "2025-12-19 18:30:00",
      "unread": 0
    }
  ]
}
```

## Features (Current vs. Planned)

### ✅ Current Implementation
- Send SMS from A207SH to any phone number
- Automatic phone number formatting
- Web-based interface
- Status messages
- Character counter

### 🔄 In Development
- Real-time SMS reception display
- Conversation history storage
- Multi-contact support
- Message notifications
- Conversation export

### 📋 Planned Features
- Message encryption
- Contact favorites/pinning
- Message search
- Scheduled SMS send
- MMS support (future)
- Cloud backup (future)

## Requirements

### Minimum Requirements
- **Z Fold 6:** Android 16 (OneUI 8), active SIM card, WiFi connectivity
- **A207SH:** Android 10+, WiFi connectivity, any web browser
- **Network:** Both devices on same local WiFi network

### Recommended Setup
- Z Fold 6 on 5GHz WiFi for reliability
- A207SH within WiFi range (typical home/office network)
- Z Fold 6 screen on while receiving SMS (for real-time polling)

## Limitations & Notes

1. **One-Way Direct SMS Only:** Can only send/receive SMS, not calls
2. **WiFi Required:** A207SH requires WiFi to access ANMS
3. **Z Fold 6 Polling:** Incoming SMS detection uses polling (not push)
4. **Character Limit:** Standard SMS 160 character limit applies
5. **No MMS:** Picture/media messages not supported (SMS text only)
6. **Single User:** Designed for one A207SH client per Z Fold 6 instance

## Security Considerations

- **Local Network Only:** ANMS operates on local WiFi only (no internet exposure by default)
- **No Authentication:** Current version has no login (assumes trusted network)
- **HTTP by Default:** Use HTTPS in production with self-signed certificates
- **SIM Access:** Z Fold 6 has full control over SMS functionality

## Project Goal

ANMS enables the **Aquos A207SH** (a feature phone with WiFi but no SIM) to function as a **remote SMS terminal** connected to a **host device (Z Fold 6)** with a SIM card. It provides a seamless chat experience where the A207SH user can send and receive real SMS messages as if the A207SH had its own SIM card.

This is particularly useful for:
- **Keitai Phone Users:** Extending the A207SH's messaging capabilities
- **Retro Device Enthusiasts:** Using vintage phones with modern connectivity
- **Multi-Device Messaging:** Consolidating SMS from multiple contacts through one SIM
- **IoT Applications:** Remote SMS gateway for monitoring systems

## Development Status

**Alpha 1.0:** Basic SMS sending working
**Next:** SMS receiving + real-time updates
**Future:** Full chat application with history
