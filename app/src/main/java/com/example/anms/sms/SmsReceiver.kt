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
        Log.d(tag, "SMS received")
        
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                val phoneNumber = message.originatingAddress ?: ""
                val messageText = message.messageBody ?: ""
                
                Log.d(tag, "SMS from $phoneNumber: $messageText")
                
                // Broadcast to connected WebSocket clients
                thread {
                    wsServer?.broadcastIncomingSMS(phoneNumber, messageText)
                }
            }
        }
    }
    
    companion object {
        var wsServer: WebSocketServer? = null
    }
}