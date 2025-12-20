package com.example.anms.sms

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.example.anms.Message

class SmsManager(
    private val context: Context,
    private val onMessageReceived: (Message) -> Unit
) {
    private val smsReceiver = SmsReceiver(onMessageReceived)
    private val androidSmsManager: SmsManager = SmsManager.getDefault()

    init {
        registerReceiver()
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.registerReceiver(
                context,
                smsReceiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(smsReceiver, intentFilter)
        }
    }

    @Throws(SecurityException::class)
    fun sendSms(phoneNumber: String, message: String) {
        try {
            androidSmsManager.sendTextMessage(
                phoneNumber,
                null,
                message,
                null,
                null
            )
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(smsReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}