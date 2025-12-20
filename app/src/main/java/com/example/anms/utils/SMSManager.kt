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
import kotlin.concurrent.thread

class SMSManager(private val context: Context) {
    private val tag = "ANMS_SMS"
    private val smsManager = SmsManager.getDefault()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.US)

    fun sendSMS(phoneNumber: String, message: String): Boolean {
        return try {
            Log.d(tag, "[SEND] Sending SMS to $phoneNumber: $message")
            val sentIntent = PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                Intent("SMS_SENT"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val deliveryIntent = PendingIntent.getBroadcast(
                context,
                (System.currentTimeMillis() + 1).toInt(),
                Intent("SMS_DELIVERED"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            smsManager.sendTextMessage(phoneNumber, null, message, sentIntent, deliveryIntent)
            Log.d(tag, "[SEND] SMS queued successfully")
            true
        } catch (e: Exception) {
            Log.e(tag, "[SEND] Error sending SMS: ${e.message}", e)
            false
        }
    }

    fun getSMSHistory(phoneNumber: String): List<SMSMessage> {
        Log.d(tag, "[QUERY] Requesting SMS history for: $phoneNumber")
        val smsList = mutableListOf<SMSMessage>()
        val uri = Uri.parse("content://sms")
        
        val result = mutableListOf<SMSMessage>()
        
        thread {
            try {
                Log.d(tag, "[QUERY] Starting query thread")
                val selection = "address = ?"
                val selectionArgs = arrayOf(phoneNumber)
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf("_id", "address", "body", "date", "type"),
                    selection,
                    selectionArgs,
                    "date DESC LIMIT 50"
                )

                Log.d(tag, "[QUERY] Got cursor: ${cursor != null}")
                
                cursor?.use {
                    Log.d(tag, "[QUERY] Cursor count: ${it.count}")
                    val idIndex = it.getColumnIndex("_id")
                    val addressIndex = it.getColumnIndex("address")
                    val bodyIndex = it.getColumnIndex("body")
                    val dateIndex = it.getColumnIndex("date")
                    val typeIndex = it.getColumnIndex("type")

                    while (it.moveToNext()) {
                        try {
                            val id = if (idIndex >= 0) it.getString(idIndex) else ""
                            val address = if (addressIndex >= 0) it.getString(addressIndex) else phoneNumber
                            val body = if (bodyIndex >= 0) it.getString(bodyIndex) else ""
                            val date = if (dateIndex >= 0) it.getLong(dateIndex) else 0L
                            val type = if (typeIndex >= 0) it.getInt(typeIndex) else 0

                            val dateStr = try {
                                if (date > 0) dateFormat.format(Date(date)) else "--:--"
                            } catch (e: Exception) {
                                "--:--"
                            }
                            val direction = if (type == 1) "received" else "sent"

                            result.add(
                                SMSMessage(
                                    id = id,
                                    phoneNumber = address,
                                    message = body,
                                    timestamp = dateStr,
                                    direction = direction
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(tag, "[QUERY] Error parsing SMS row: ${e.message}")
                        }
                    }
                    Log.d(tag, "[QUERY] Retrieved ${result.size} messages")
                }
            } catch (e: Exception) {
                Log.e(tag, "[QUERY] Error querying SMS database: ${e.message}", e)
            }
        }.join(5000) // Wait max 5 seconds
        
        smsList.addAll(result)
        Log.d(tag, "[QUERY] Final count: ${smsList.size}")
        return smsList
    }

    fun formatSMSHistoryForWeb(phoneNumber: String): String {
        Log.d(tag, "[FORMAT] Formatting SMS history for: $phoneNumber")
        val messages = getSMSHistory(phoneNumber)
        
        val messageJsons = messages.map { sms ->
            val escapedBody = sms.message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            
            """{"id":"${sms.id}","body":"$escapedBody","timestamp":"${sms.timestamp}","direction":"${sms.direction}"}"""
        }
        
        val json = """{"phone":"$phoneNumber","messages":[${messageJsons.joinToString(",")}]}"""
        Log.d(tag, "[FORMAT] Generated JSON: ${json.take(200)}...")
        return json
    }

    data class SMSMessage(
        val id: String,
        val phoneNumber: String,
        val message: String,
        val timestamp: String,
        val direction: String // "sent" or "received"
    )
}