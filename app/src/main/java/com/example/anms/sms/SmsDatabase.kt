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
    
    fun getConversation(phoneNumber: String, limit: Int = 500): List<SmsMessage> {
        Log.d(tag, "=== Getting conversation for: $phoneNumber ===")
        return try {
            val messages = mutableListOf<SmsMessage>()
            
            val smsUri = Uri.parse("content://sms")
            val projection = arrayOf("_id", "address", "body", "date", "type")
            
            Log.d(tag, "Querying URI: $smsUri")
            Log.d(tag, "Requesting projection: ${projection.joinToString(", ")}")
            
            val cursor: Cursor? = context.contentResolver.query(
                smsUri,
                projection,
                null,
                null,
                "date DESC LIMIT $limit"
            )
            
            if (cursor == null) {
                Log.e(tag, "Cursor is NULL - permission denied or provider unavailable")
                return emptyList()
            }
            
            Log.d(tag, "Cursor returned: ${cursor.count} total SMS in database")
            
            cursor.use {
                val idCol = it.getColumnIndex("_id")
                val addressCol = it.getColumnIndex("address")
                val bodyCol = it.getColumnIndex("body")
                val dateCol = it.getColumnIndex("date")
                val typeCol = it.getColumnIndex("type")
                
                Log.d(tag, "Column indices - id:$idCol addr:$addressCol body:$bodyCol date:$dateCol type:$typeCol")
                
                if (idCol < 0 || addressCol < 0 || bodyCol < 0 || dateCol < 0 || typeCol < 0) {
                    Log.e(tag, "Invalid column index - columns not found")
                    return emptyList()
                }
                
                val cleanPhoneTarget = phoneNumber.replace("[^0-9+]".toRegex(), "")
                val lastDigits = cleanPhoneTarget.replace("+", "").takeLast(11)
                
                Log.d(tag, "Looking for phone: $phoneNumber")
                Log.d(tag, "  Clean: $cleanPhoneTarget")
                Log.d(tag, "  Last 11 digits: $lastDigits")
                
                var matchCount = 0
                var totalCount = 0
                while (it.moveToNext()) {
                    try {
                        totalCount++
                        val id = it.getString(idCol)
                        val address = it.getString(addressCol) ?: "Unknown"
                        val body = it.getString(bodyCol) ?: ""
                        val timestamp = it.getLong(dateCol)
                        val type = it.getInt(typeCol)
                        
                        if (addressMatches(address, cleanPhoneTarget, lastDigits)) {
                            messages.add(SmsMessage(
                                id = id,
                                phone = address,
                                body = body,
                                timestamp = timestamp,
                                type = type
                            ))
                            matchCount++
                            if (matchCount <= 3) {
                                Log.d(tag, "Match #$matchCount: $address | ${body.take(50)}...")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error parsing row $totalCount: ${e.message}")
                    }
                }
                
                Log.d(tag, "Scanned $totalCount rows, found $matchCount matches")
            }
            
            messages.sortBy { it.timestamp }
            Log.d(tag, "Returning ${messages.size} messages sorted by timestamp")
            messages
        } catch (e: Exception) {
            Log.e(tag, "Exception in getConversation: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun addressMatches(address: String, cleanPhone: String, lastDigits: String): Boolean {
        val cleanAddress = address.replace("[^0-9+]".toRegex(), "")
        val addressLastDigits = cleanAddress.replace("+", "").takeLast(11)
        
        val match = cleanAddress == cleanPhone || 
                   cleanAddress.endsWith(lastDigits) ||
                   cleanPhone.endsWith(addressLastDigits) ||
                   address.contains(cleanPhone) ||
                   address == cleanPhone
        
        return match
    }
    
    fun getAllConversations(limit: Int = 50): Map<String, List<SmsMessage>> {
        return try {
            val conversations = mutableMapOf<String, MutableList<SmsMessage>>()
            
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
                Log.e(tag, "Cursor is NULL in getAllConversations")
                return emptyMap()
            }
            
            cursor.use {
                val idCol = it.getColumnIndex("_id")
                val addressCol = it.getColumnIndex("address")
                val bodyCol = it.getColumnIndex("body")
                val dateCol = it.getColumnIndex("date")
                val typeCol = it.getColumnIndex("type")
                
                while (it.moveToNext()) {
                    try {
                        val id = it.getString(idCol)
                        val address = it.getString(addressCol) ?: "Unknown"
                        val body = it.getString(bodyCol) ?: ""
                        val timestamp = it.getLong(dateCol)
                        val type = it.getInt(typeCol)
                        
                        if (!conversations.containsKey(address)) {
                            conversations[address] = mutableListOf()
                        }
                        conversations[address]?.add(SmsMessage(id, address, body, timestamp, type))
                    } catch (e: Exception) {
                        Log.e(tag, "Error parsing SMS: ${e.message}")
                    }
                }
            }
            
            conversations.forEach { (_, msgs) -> msgs.sortBy { it.timestamp } }
            Log.d(tag, "Loaded ${conversations.size} conversations")
            conversations
        } catch (e: Exception) {
            Log.e(tag, "Error reading conversations: ${e.message}", e)
            emptyMap()
        }
    }
}