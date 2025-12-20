package com.example.anms.sms

import android.content.Context
import android.util.Log
import java.util.*
import kotlin.concurrent.thread

class SmsCache(private val context: Context) {
    private val tag = "ANMS_SmsCache"
    private val cache = mutableMapOf<String, MutableList<SmsDatabase.SmsMessage>>()
    private val smsDb = SmsDatabase(context)
    private var isLoaded = false
    private val loadLock = Any()
    
    fun loadInitialSms() {
        thread {
            synchronized(loadLock) {
                if (isLoaded) return@thread
                
                Log.d(tag, "Loading initial SMS (7 days)...")
                val startTime = System.currentTimeMillis()
                
                try {
                    val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
                    val allMessages = smsDb.getLastNDays(7)
                    
                    // Group by phone number
                    allMessages.forEach { msg ->
                        if (!cache.containsKey(msg.phone)) {
                            cache[msg.phone] = mutableListOf()
                        }
                        cache[msg.phone]?.add(msg)
                    }
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(tag, "Loaded ${allMessages.size} SMS from ${cache.size} contacts in ${elapsed}ms")
                    cache.forEach { (phone, msgs) ->
                        Log.d(tag, "  $phone: ${msgs.size} messages")
                    }
                    
                    isLoaded = true
                } catch (e: Exception) {
                    Log.e(tag, "Error loading SMS: ${e.message}", e)
                }
            }
        }
    }
    
    fun getConversation(phone: String): List<SmsDatabase.SmsMessage> {
        // Wait for initial load
        synchronized(loadLock) {
            if (!isLoaded) {
                Log.w(tag, "Conversation requested before cache loaded, waiting...")
                Thread.sleep(100) // Wait a bit for loading to complete
            }
        }
        
        return cache[phone]?.sortedBy { it.timestamp } ?: emptyList()
    }
    
    fun getAllConversations(): Map<String, List<SmsDatabase.SmsMessage>> {
        synchronized(loadLock) {
            if (!isLoaded) {
                Log.w(tag, "All conversations requested before cache loaded")
                return emptyMap()
            }
        }
        
        return cache.mapValues { it.value.sortedBy { msg -> msg.timestamp } }
    }
    
    fun addMessage(msg: SmsDatabase.SmsMessage) {
        synchronized(loadLock) {
            if (!cache.containsKey(msg.phone)) {
                cache[msg.phone] = mutableListOf()
            }
            cache[msg.phone]?.add(msg)
            Log.d(tag, "Added SMS from ${msg.phone}: ${msg.body.take(30)}...")
        }
    }
    
    fun isReady(): Boolean {
        synchronized(loadLock) {
            return isLoaded
        }
    }
    
    fun getStats(): Map<String, Any> {
        synchronized(loadLock) {
            return mapOf(
                "loaded" to isLoaded,
                "total_conversations" to cache.size,
                "total_messages" to cache.values.sumOf { it.size },
                "conversations" to cache.mapValues { it.value.size }
            )
        }
    }
}
