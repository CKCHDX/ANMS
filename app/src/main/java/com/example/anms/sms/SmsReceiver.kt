package com.example.anms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.Socket

class SmsReceiver : BroadcastReceiver() {
    private val tag = "ANMS_SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(tag, "SMS received")
        
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                val phoneNumber = message.originatingAddress ?: ""
                val messageText = message.messageBody ?: ""
                val timestamp = message.timestampMillis
                
                Log.d(tag, "SMS from $phoneNumber: $messageText")
                
                // Send to HTTP server
                CoroutineScope(Dispatchers.IO).launch {
                    sendToServer(phoneNumber, messageText, timestamp)
                }
            }
        }
    }

    private fun sendToServer(phone: String, text: String, time: Long) {
        try {
            Log.d(tag, "Sending SMS notification to server: $phone: $text")
            val socket = Socket("127.0.0.1", 8765)
            val output = socket.outputStream
            
            // Send as simple text message
            val message = "INCOMING_SMS|$phone|$text|$time"
            output.write(message.toByteArray())
            output.flush()
            socket.close()
            
            Log.d(tag, "Sent to server successfully")
        } catch (e: Exception) {
            Log.e(tag, "Error sending to server: ${e.message}")
        }
    }
}