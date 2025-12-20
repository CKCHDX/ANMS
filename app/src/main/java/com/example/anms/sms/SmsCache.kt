package com.example.anms.sms

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

class SmsCache(private val context: Context) {
    private val tag = "ANMS_SmsCache"
    private val cache = mutableMapOf<String, MutableList<SmsDatabase.SmsMessage>>()
    private val smsDb = SmsDatabase(context)
    
    fun loadConversation(phone: String) {
        thread {
            try {
                Log.d(tag, "Loading SMS for: $phone")
                val messages = smsDb.getConversation(phone, 500)
                
                synchronized(cache) {
                    cache[phone] = messages.toMutableList()
                    Log.d(tag, "Cached ${messages.size} messages for $phone")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error loading conversation: ${e.message}", e)
            }
        }
    }
    
    fun getConversation(phone: String): List<SmsDatabase.SmsMessage> {
        synchronized(cache) {
            return cache[phone]?.sortedBy { it.timestamp } ?: emptyList()
        }
    }
    
    fun addMessage(msg: SmsDatabase.SmsMessage) {
        synchronized(cache) {
            if (!cache.containsKey(msg.phone)) {
                cache[msg.phone] = mutableListOf()
            }
            cache[msg.phone]?.add(msg)
            Log.d(tag, "Added SMS from ${msg.phone}")
        }
    }
    
    fun getStats(): Map<String, Any> {
        synchronized(cache) {
            return mapOf(
                "total_conversations" to cache.size,
                "total_messages" to cache.values.sumOf { it.size },
                "conversations" to cache.mapValues { it.value.size }
            )
        }
    }
}
