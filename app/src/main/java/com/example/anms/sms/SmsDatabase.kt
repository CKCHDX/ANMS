package com.example.anms.sms

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

class SmsDatabase(private val context: Context) {
    private val tag = "ANMS_SmsDb"
    
    data class Message(
        val id: String,
        val phone: String,
        val body: String,
        val timestamp: Long,
        val type: Int // 1=received, 2=sent
    )
    
    fun getConversation(phoneNumber: String, limit: Int = 100): List<Message> {
        return try {
            val messages = mutableListOf<Message>()
            
            // Content URIs for SMS
            val smsUri = Uri.parse("content://sms/")
            
            // Query both inbox and sent folders
            val inboxUri = Uri.parse("content://sms/inbox")
            val sentUri = Uri.parse("content://sms/sent")
            
            // Get inbox messages
            messages.addAll(querySms(inboxUri, phoneNumber, 1, limit / 2))
            
            // Get sent messages
            messages.addAll(querySms(sentUri, phoneNumber, 2, limit / 2))
            
            // Sort by timestamp
            messages.sortBy { it.timestamp }
            
            Log.d(tag, "Loaded ${messages.size} messages for $phoneNumber")
            messages
        } catch (e: Exception) {
            Log.e(tag, "Error reading SMS: ${e.message}")
            emptyList()
        }
    }
    
    private fun querySms(uri: Uri, phoneNumber: String, type: Int, limit: Int): List<Message> {
        val messages = mutableListOf<Message>()
        
        try {
            val projection = arrayOf("_id", "address", "body", "date", "type")
            
            // Match phone number (handle different formats)
            val cleanPhone = phoneNumber.replace("[^0-9+]".toRegex(), "")
            val selection = "address LIKE ?"
            val selectionArgs = arrayOf("%$cleanPhone%")
            
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "date DESC LIMIT $limit"
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
                        
                        messages.add(Message(
                            id = id,
                            phone = address,
                            body = body,
                            timestamp = timestamp,
                            type = type
                        ))
                    } catch (e: Exception) {
                        Log.e(tag, "Error parsing SMS row: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error querying SMS: ${e.message}")
        }
        
        return messages
    }
    
    fun getAllConversations(limit: Int = 50): Map<String, List<Message>> {
        return try {
            val conversations = mutableMapOf<String, MutableList<Message>>()
            
            val smsUri = Uri.parse("content://sms/")
            val projection = arrayOf("_id", "address", "body", "date", "type")
            
            val cursor: Cursor? = context.contentResolver.query(
                smsUri,
                projection,
                null,
                null,
                "date DESC LIMIT ${limit * 10}"
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
                        conversations[address]?.add(Message(id, address, body, timestamp, type))
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
            Log.e(tag, "Error reading conversations: ${e.message}")
            emptyMap()
        }
    }
}