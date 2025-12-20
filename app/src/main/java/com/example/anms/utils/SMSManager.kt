package com.example.anms.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
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
            Log.d(tag, "SMS sent successfully")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error sending SMS", e)
            false
        }
    }

    fun getSMSHistory(phoneNumber: String): List<SMSMessage> {
        val smsList = mutableListOf<SMSMessage>()
        val uri = Uri.parse("content://sms")
        val cursor: Cursor? = try {
            val selection = "address = ?"
            val selectionArgs = arrayOf(phoneNumber)
            context.contentResolver.query(
                uri,
                arrayOf("_id", "address", "body", "date", "type"),
                selection,
                selectionArgs,
                "date DESC"
            )
        } catch (e: Exception) {
            Log.e(tag, "Error querying SMS", e)
            null
        }

        cursor?.use {
            val idIndex = it.getColumnIndex("_id")
            val addressIndex = it.getColumnIndex("address")
            val bodyIndex = it.getColumnIndex("body")
            val dateIndex = it.getColumnIndex("date")
            val typeIndex = it.getColumnIndex("type")

            while (it.moveToNext()) {
                try {
                    val id = it.getString(idIndex)
                    val address = it.getString(addressIndex)
                    val body = it.getString(bodyIndex)
                    val date = it.getLong(dateIndex)
                    val type = it.getInt(typeIndex)

                    val dateStr = dateFormat.format(Date(date))
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
                    Log.e(tag, "Error parsing SMS", e)
                }
            }
        }

        Log.d(tag, "Retrieved ${smsList.size} SMS messages from $phoneNumber")
        return smsList
    }

    fun formatSMSHistoryForWeb(phoneNumber: String): String {
        val messages = getSMSHistory(phoneNumber)
        val json = StringBuilder()
        json.append("{\"phone\":\"$phoneNumber\",\"messages\":[")
        messages.forEachIndexed { index, sms ->
            json.append(
                """{\"id\":\"${sms.id}\",\"body\":\"${sms.message.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"timestamp\":\"${sms.timestamp}\",\"direction\":\"${sms.direction}\"}"""
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