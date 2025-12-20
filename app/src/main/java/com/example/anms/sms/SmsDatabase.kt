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
        return try {
            val messages = mutableListOf<SmsMessage>()
            
            // Query both inbox and sent folders
            val inboxUri = Uri.parse("content://sms/inbox")
            val sentUri = Uri.parse("content://sms/sent")
            val draftUri = Uri.parse("content://sms/draft")
            
            // Get inbox messages
            messages.addAll(querySms(inboxUri, phoneNumber, 1))
            
            // Get sent messages
            messages.addAll(querySms(sentUri, phoneNumber, 2))
            
            // Get draft messages
            messages.addAll(querySms(draftUri, phoneNumber, 3))
            
            // Sort by timestamp ascending (oldest first)
            messages.sortBy { it.timestamp }
            
            // Take last N messages
            val result = if (messages.size > limit) messages.takeLast(limit) else messages
            
            Log.d(tag, "Loaded ${result.size} messages for $phoneNumber (total ${messages.size})")
            result
        } catch (e: Exception) {
            Log.e(tag, "Error reading SMS: ${e.message}", e)
            emptyList()
        }
    }
    
    private fun querySms(uri: Uri, phoneNumber: String, type: Int): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        
        try {
            val projection = arrayOf("_id", "address", "body", "date", "type")
            
            // Clean phone number - remove everything except digits and +
            val cleanPhone = phoneNumber.replace("[^0-9+]".toRegex(), "")
            val lastDigits = cleanPhone.replace("+", "").takeLast(10)
            
            Log.d(tag, "Querying $uri for phone: $phoneNumber (clean: $cleanPhone, last10: $lastDigits)")
            
            // Try multiple matching strategies
            val cursor: Cursor? = context.contentResolver.query(
                uri,
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
                
                while (it.moveToNext()) {
                    try {
                        val id = it.getString(idCol)
                        val address = it.getString(addressCol) ?: "Unknown"
                        val body = it.getString(bodyCol) ?: ""
                        val timestamp = it.getLong(dateCol)
                        
                        // Match phone number - check various formats
                        if (phoneNumberMatches(address, cleanPhone, lastDigits)) {
                            messages.add(SmsMessage(
                                id = id,
                                phone = address,
                                body = body,
                                timestamp = timestamp,
                                type = type
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error parsing SMS row: ${e.message}")
                    }
                }
            }
            
            Log.d(tag, "Found ${messages.size} messages in $uri")
        } catch (e: Exception) {
            Log.e(tag, "Error querying SMS: ${e.message}", e)
        }
        
        return messages
    }
    
    private fun phoneNumberMatches(address: String, cleanPhone: String, lastDigits: String): Boolean {
        val cleanAddress = address.replace("[^0-9+]".toRegex(), "")
        val addressLastDigits = cleanAddress.replace("+", "").takeLast(10)
        
        return cleanAddress == cleanPhone || 
               cleanAddress.endsWith(lastDigits) ||
               cleanPhone.endsWith(addressLastDigits) ||
               address.contains(cleanPhone) ||
               address == cleanPhone
    }
    
    fun getAllConversations(limit: Int = 50): Map<String, List<SmsMessage>> {
        return try {
            val conversations = mutableMapOf<String, MutableList<SmsMessage>>()
            
            val smsUri = Uri.parse("content://sms/")
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
                
                var count = 0
                while (it.moveToNext() && count < limit * 10) {
                    try {
                        val id = it.getString(idCol)
                        val address = it.getString(addressCol) ?: "Unknown"
                        val body = it.getString(bodyCol) ?: ""
                        val timestamp = it.getLong(dateCol)
                        val typeVal = it.getInt(typeCol)
                        
                        if (!conversations.containsKey(address)) {
                            conversations[address] = mutableListOf()
                        }
                        conversations[address]?.add(SmsMessage(id, address, body, timestamp, typeVal))
                        count++
                    } catch (e: Exception) {
                        Log.e(tag, "Error parsing SMS: ${e.message}")
                    }
                }
            }
            
            // Sort messages within each conversation and limit size
            conversations.forEach { (_, msgs) -> 
                msgs.sortBy { it.timestamp }
            }
            
            Log.d(tag, "Loaded ${conversations.size} conversations")
            conversations
        } catch (e: Exception) {
            Log.e(tag, "Error reading conversations: ${e.message}", e)
            emptyMap()
        }
    }
}