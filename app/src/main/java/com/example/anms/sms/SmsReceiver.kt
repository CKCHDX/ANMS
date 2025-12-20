package com.example.anms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.anms.network.WebSocketServer
import kotlin.concurrent.thread

class SmsReceiver : BroadcastReceiver() {
    private val tag = "ANMS_SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(tag, "SMS BroadcastReceiver triggered")
        
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                val phoneNumber = message.originatingAddress ?: "Unknown"
                val messageText = message.messageBody ?: ""
                
                Log.d(tag, "SMS from $phoneNumber: $messageText")
                
                // Broadcast to connected WebSocket clients
                thread {
                    try {
                        globalWebSocketServer?.broadcastIncomingSMS(phoneNumber, messageText)
                        Log.d(tag, "Broadcasted to WebSocket")
                    } catch (e: Exception) {
                        Log.e(tag, "Error broadcasting", e)
                    }
                }
            }
        }
    }
    
    companion object {
        var globalWebSocketServer: WebSocketServer? = null
    }
}