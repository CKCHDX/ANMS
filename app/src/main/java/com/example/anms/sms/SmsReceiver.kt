package com.example.anms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.example.anms.network.WebSocketServer
import kotlin.concurrent.thread

class SmsReceiver : BroadcastReceiver() {
    private val tag = "ANMS_SmsReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(tag, "onReceive called with action: ${intent?.action}")
        
        try {
            if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
                Log.d(tag, "SMS_RECEIVED_ACTION triggered")
                
                val bundle = intent.extras
                if (bundle == null) {
                    Log.w(tag, "Bundle is null")
                    return
                }
                
                val pdus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+
                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    messages.map { it.originatingAddress to it.messageBody }
                } else {
                    // Fallback for older versions
                    val pdus = bundle.get("pdus") as? Array<*> ?: emptyArray<Any>()
                    pdus.mapNotNull { pdu ->
                        try {
                            val message = SmsMessage.createFromPdu(pdu as ByteArray)
                            message.originatingAddress to message.messageBody
                        } catch (e: Exception) {
                            Log.e(tag, "Error parsing PDU", e)
                            null
                        }
                    }
                }
                
                Log.d(tag, "Received ${pdus.size} SMS messages")
                
                for ((phone, text) in pdus) {
                    Log.d(tag, "SMS from $phone: $text")
                    
                    thread {
                        try {
                            val server = globalWebSocketServer
                            if (server != null) {
                                Log.d(tag, "Broadcasting to WebSocket server")
                                server.broadcastIncomingSMS(phone ?: "Unknown", text ?: "")
                            } else {
                                Log.w(tag, "WebSocket server is null")
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Error broadcasting", e)
                        }
                    }
                }
            } else {
                Log.d(tag, "Received non-SMS action: ${intent?.action}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in onReceive", e)
        }
    }
    
    companion object {
        var globalWebSocketServer: WebSocketServer? = null
    }
}