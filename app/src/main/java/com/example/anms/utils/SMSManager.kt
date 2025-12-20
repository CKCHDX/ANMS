package com.example.anms.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class SMSManager(private val context: Context) {
    private val tag = "ANMS_SMS"
    private val smsManager = SmsManager.getDefault()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun sendSMS(phoneNumber: String, message: String): Boolean {
        return try {
            Log.d(tag, "Sending SMS to $phoneNumber: $message")
            val sentIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent("SMS_SENT"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            smsManager.sendTextMessage(phoneNumber, null, message, sentIntent, null)
            Log.d(tag, "SMS sent successfully to $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error sending SMS to $phoneNumber", e)
            false
        }
    }

    fun getSMSHistory(phoneNumber: String): List<SMSMessage> {
        val smsList = mutableListOf<SMSMessage>()
        val uri = Uri.parse("content://sms")
        
        return try {
            val selection = "address = ?"
            val selectionArgs = arrayOf(phoneNumber)
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("_id", "address", "body", "date", "type"),
                selection,
                selectionArgs,
                "date DESC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex("_id")
                val addressIndex = it.getColumnIndex("address")
                val bodyIndex = it.getColumnIndex("body")
                val dateIndex = it.getColumnIndex("date")
                val typeIndex = it.getColumnIndex("type")

                while (it.moveToNext()) {
                    try {
                        val id = if (idIndex >= 0) it.getString(idIndex) else ""
                        val address = if (addressIndex >= 0) it.getString(addressIndex) else ""
                        val body = if (bodyIndex >= 0) it.getString(bodyIndex) else ""
                        val date = if (dateIndex >= 0) it.getLong(dateIndex) else 0L
                        val type = if (typeIndex >= 0) it.getInt(typeIndex) else 0

                        val dateStr = if (date > 0) dateFormat.format(Date(date)) else "Unknown"
                        val direction = if (type == 1) "received" else "sent"

                        smsList.add(
                            SMSMessage(
                                id = id,
                                phoneNumber = address,
                                message = body,
                                timestamp = dateStr,
                                direction = direction
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "Error parsing SMS row", e)
                    }
                }
            }
            Log.d(tag, "Retrieved ${smsList.size} SMS messages from $phoneNumber")
            smsList
        } catch (e: Exception) {
            Log.e(tag, "Error querying SMS database", e)
            smsList // Return empty list on error
        }
    }

    fun formatSMSHistoryForWeb(phoneNumber: String): String {
        val messages = getSMSHistory(phoneNumber)
        val json = StringBuilder()
        json.append("{\"phone\":\"$phoneNumber\",\"messages\":[")
        
        messages.forEachIndexed { index, sms ->
            val escapedBody = sms.message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            
            json.append(
                """{\"id\":\"${sms.id}\",\"body\":\"$escapedBody\",\"timestamp\":\"${sms.timestamp}\",\"direction\":\"${sms.direction}\"}"""
            )
            if (index < messages.size - 1) json.append(",")
        }
        json.append("]}")        
        return json.toString()
    }

    data class SMSMessage(
        val id: String,
        val phoneNumber: String,
        val message: String,
        val timestamp: String,
        val direction: String // "sent" or "received"
    )
}