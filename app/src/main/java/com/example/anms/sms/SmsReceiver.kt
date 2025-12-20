package com.example.anms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.annotation.RequiresPermission
import com.example.anms.Message

class SmsReceiver(
    private val onMessageReceived: (Message) -> Unit
) : BroadcastReceiver() {
    @RequiresPermission(android.Manifest.permission.RECEIVE_SMS)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val smsMessages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)
            } else {
                @Suppress("DEPRECATION")
                val pdus = intent.getParcelableArrayExtra("pdus") as? Array<*>
                pdus?.mapNotNull { pdu ->
                    try {
                        @Suppress("DEPRECATION")
                        android.telephony.SmsMessage.createFromPdu(pdu as ByteArray)
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()
            }

            for (sms in smsMessages) {
                val phoneNumber = sms.originatingAddress ?: "Unknown"
                val messageBody = sms.messageBody
                val timestamp = sms.timestampMillis

                onMessageReceived(
                    Message(
                        phoneNumber = phoneNumber,
                        content = messageBody,
                        timestamp = timestamp,
                        isOutgoing = false
                    )
                )
            }
        }
    }
}