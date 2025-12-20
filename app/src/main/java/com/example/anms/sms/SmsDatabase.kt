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
            
            // Try to query SMS content provider
            val smsUri = Uri.parse("content://sms")
            val projection = arrayOf("_id", "address", "body", "date", "type")
            
            // Query ALL SMS first
            val cursor: Cursor? = context.contentResolver.query(
                smsUri,
                projection,
                null,
                null,
                "date DESC"
            )
            
            Log.d(tag, "Total SMS in database: ${cursor?.count}")
            
            cursor?.use {
                val idCol = it.getColumnIndex("_id")
                val addressCol = it.getColumnIndex("address")
                val bodyCol = it.getColumnIndex("body")
                val dateCol = it.getColumnIndex("date")
                val typeCol = it.getColumnIndex("type")
                
                Log.d(tag, "Columns - id:$idCol addr:$addressCol body:$bodyCol date:$dateCol type:$typeCol")
                
                val cleanPhoneTarget = phoneNumber.replace("[^0-9+]".toRegex(), "")
                val lastDigits = cleanPhoneTarget.replace("+", "").takeLast(11)
                
                Log.d(tag, "Looking for phone: $phoneNumber (clean: $cleanPhoneTarget, last11: $lastDigits)")
                
                var matchCount = 0
                while (it.moveToNext()) {
                    try {
                        val id = it.getString(idCol)
                        val address = it.getString(addressCol) ?: "Unknown"
                        val body = it.getString(bodyCol) ?: ""
                        val timestamp = it.getLong(dateCol)
                        val type = it.getInt(typeCol)
                        
                        // Check if this SMS matches our target phone
                        if (addressMatches(address, cleanPhoneTarget, lastDigits)) {
                            messages.add(SmsMessage(
                                id = id,
                                phone = address,
                                body = body,
                                timestamp = timestamp,
                                type = type
                            ))
                            matchCount++
                            Log.d(tag, "Match #$matchCount: $address -> $body")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error parsing SMS row: ${e.message}", e)
                    }
                }
                
                Log.d(tag, "Found $matchCount matching messages")
            }
            
            // Sort by timestamp ascending (oldest first)
            messages.sortBy { it.timestamp }
            
            Log.d(tag, "Returning ${messages.size} messages")
            messages
        } catch (e: Exception) {
            Log.e(tag, "Error reading SMS: ${e.message}", e)
            e.printStackTrace()
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
        
        if (match) {
            Log.d(tag, "  MATCH: '$address' matches '$cleanPhone'")
        }
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
            
            cursor?.use {
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
            
            // Sort messages within each conversation
            conversations.forEach { (_, msgs) -> msgs.sortBy { it.timestamp } }
            
            Log.d(tag, "Loaded ${conversations.size} conversations")
            conversations
        } catch (e: Exception) {
            Log.e(tag, "Error reading conversations: ${e.message}", e)
            emptyMap()
        }
    }
}