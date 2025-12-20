package com.example.anms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.annotation.RequiresPermission
import com.example.anms.Message

class SmsReceiver(
    private val onMessageReceived: (Message) -> Unit
) : BroadcastReceiver() {
    @RequiresPermission(android.Manifest.permission.RECEIVE_SMS)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            try {
                val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)
                } else {
                    // For older Android versions, parse PDU manually
                    @Suppress("DEPRECATION")
                    val pdus = intent.getParcelableArrayExtra("pdus")
                    if (pdus != null) {
                        val pduList = mutableListOf<SmsMessage>()
                        for (pdu in pdus) {
                            try {
                                @Suppress("DEPRECATION")
                                val msg = SmsMessage.createFromPdu(pdu as ByteArray)
                                pduList.add(msg)
                            } catch (e: Exception) {
                                // Skip invalid PDUs
                            }
                        }
                        pduList.toTypedArray()
                    } else {
                        emptyArray()
                    }
                }

                // Process each SMS message
                for (sms in messages) {
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
            } catch (e: Exception) {
                // Log error but don't crash
                e.printStackTrace()
            }
        }
    }
}