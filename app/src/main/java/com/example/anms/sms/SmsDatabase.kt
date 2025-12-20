package com.example.anms.sms

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

class SmsDatabase(private val context: Context) {
    private val tag = "ANMS_SmsDb"
    
    data class SmsMessage(
        val id: String,
        val phone: String,
        val body: String,
        val timestamp: Long,
        val type: Int // 1=received, 2=sent
    )
    
    fun getConversation(phoneNumber: String, limit: Int = 100): List<SmsMessage> {
        Log.d(tag, "Loading SMS for: $phoneNumber")
        return try {
            val messages = mutableListOf<SmsMessage>()
            
            val smsUri = Uri.parse("content://sms")
            val projection = arrayOf("_id", "address", "body", "date", "type")
            
            val cursor: Cursor? = context.contentResolver.query(
                smsUri,
                projection,
                null,
                null,
                "date DESC"
            )
            
            if (cursor == null) {
                Log.e(tag, "Cursor is NULL - permission denied")
                return emptyList()
            }
            
            val cleanTarget = cleanPhone(phoneNumber)
            Log.d(tag, "Searching for: $phoneNumber (cleaned: $cleanTarget)")
            
            cursor.use {
                val idCol = it.getColumnIndex("_id")
                val addressCol = it.getColumnIndex("address")
                val bodyCol = it.getColumnIndex("body")
                val dateCol = it.getColumnIndex("date")
                val typeCol = it.getColumnIndex("type")
                
                while (it.moveToNext() && messages.size < limit) {
                    try {
                        val id = it.getString(idCol)
                        val address = it.getString(addressCol) ?: "Unknown"
                        val body = it.getString(bodyCol) ?: ""
                        val timestamp = it.getLong(dateCol)
                        val type = it.getInt(typeCol)
                        
                        val cleanAddress = cleanPhone(address)
                        
                        // Simple match: ends with last 10 digits
                        if (cleanTarget.endsWith(cleanAddress) || cleanAddress.endsWith(cleanTarget) || cleanAddress == cleanTarget) {
                            messages.add(SmsMessage(
                                id = id,
                                phone = address,
                                body = body,
                                timestamp = timestamp,
                                type = type
                            ))
                            Log.d(tag, "Match: $address")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Row error: ${e.message}")
                    }
                }
            }
            
            Log.d(tag, "Found ${messages.size} messages")
            messages.sortBy { it.timestamp }
            messages
        } catch (e: Exception) {
            Log.e(tag, "Exception: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun cleanPhone(phone: String): String {
        return phone.replace("[^0-9+]".toRegex(), "")
            .replace("+", "")
            .takeLast(15) // Keep last 15 digits max
    }
}