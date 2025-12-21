package com.example.anms.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager as AndroidSmsManager
import android.util.Log

class SmsManager(private val context: Context) {
    private val tag = "ANMS_SmsManager"
    private val smsManager = AndroidSmsManager.getDefault()

    fun sendSms(phoneNumber: String, message: String): Boolean {
        return try {
            smsManager.sendTextMessage(
                phoneNumber,
                null,
                message,
                null,
                null
            )
            Log.d(tag, "SMS sent to $phoneNumber: $message")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to send SMS: ${e.message}")
            false
        }
    }
}