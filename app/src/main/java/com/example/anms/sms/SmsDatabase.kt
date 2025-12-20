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
        Log.d(tag, "===== getConversation START =====")
        Log.d(tag, "Looking for phone: '$phoneNumber'")
        
        return try {
            val messages = mutableListOf<SmsMessage>()
            
            val smsUri = Uri.parse("content://sms")
            val projection = arrayOf("_id", "address", "body", "date", "type")
            
            Log.d(tag, "Querying SMS content provider...")
            val cursor: Cursor? = context.contentResolver.query(
                smsUri,
                projection,
                null,
                null,
                "date DESC"
            )
            
            if (cursor == null) {
                Log.e(tag, "CURSOR IS NULL - PERMISSION DENIED or DB EMPTY")
                return emptyList()
            }
            
            Log.d(tag, "Cursor returned, total rows: ${cursor.count}")
            
            val cleanTarget = cleanPhone(phoneNumber)
            Log.d(tag, "Cleaned target: '$cleanTarget'")
            
            cursor.use {
                val idCol = it.getColumnIndex("_id")
                val addressCol = it.getColumnIndex("address")
                val bodyCol = it.getColumnIndex("body")
                val dateCol = it.getColumnIndex("date")
                val typeCol = it.getColumnIndex("type")
                
                Log.d(tag, "Column indices: id=$idCol addr=$addressCol body=$bodyCol date=$dateCol type=$typeCol")
                
                var scanned = 0
                var matched = 0
                
                while (it.moveToNext() && messages.size < limit) {
                    try {
                        scanned++
                        val id = it.getString(idCol)
                        val address = it.getString(addressCol) ?: "Unknown"
                        val body = it.getString(bodyCol) ?: ""
                        val timestamp = it.getLong(dateCol)
                        val type = it.getInt(typeCol)
                        
                        val cleanAddress = cleanPhone(address)
                        
                        val isMatch = phoneMatches(cleanTarget, cleanAddress, address, phoneNumber)
                        
                        if (scanned <= 5) {
                            Log.d(tag, "Row $scanned: address='$address' cleaned='$cleanAddress' match=$isMatch")
                        }
                        
                        if (isMatch) {
                            matched++
                            messages.add(SmsMessage(
                                id = id,
                                phone = address,
                                body = body.take(50),
                                timestamp = timestamp,
                                type = type
                            ))
                            Log.d(tag, "MATCHED #$matched: '$address' - '${body.take(30)}...'")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Row parse error: ${e.message}")
                    }
                }
                
                Log.d(tag, "Scanned $scanned rows, matched $matched")
            }
            
            messages.sortBy { it.timestamp }
            Log.d(tag, "===== RETURNING ${messages.size} MESSAGES =====")
            messages
        } catch (e: Exception) {
            Log.e(tag, "EXCEPTION: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun cleanPhone(phone: String): String {
        return phone.replace("[^0-9+]".toRegex(), "")
            .replace("+", "")
            .trim()
    }
    
    private fun phoneMatches(cleanTarget: String, cleanAddress: String, originalAddress: String, originalPhone: String): Boolean {
        // Try multiple matching strategies
        return when {
            // Exact match
            cleanTarget == cleanAddress -> true
            // Last 10 digits match
            cleanTarget.takeLast(10) == cleanAddress.takeLast(10) && cleanTarget.length >= 10 && cleanAddress.length >= 10 -> true
            // Original strings match
            originalPhone == originalAddress -> true
            // Original contains cleaned
            originalAddress.contains(originalPhone) -> true
            // Last 9 digits
            cleanTarget.takeLast(9) == cleanAddress.takeLast(9) && cleanTarget.length >= 9 && cleanAddress.length >= 9 -> true
            else -> false
        }
    }
}