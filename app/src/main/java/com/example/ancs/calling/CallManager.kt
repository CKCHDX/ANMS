package com.example.ancs.calling

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.example.ancs.data.Call
import com.example.ancs.data.CallStatus
import com.example.ancs.data.CallType
import java.util.UUID

class CallManager(private val context: Context) {
    private val tag = "ANCS_CallManager"
    private var currentCall: Call? = null
    private var callStartTime: Long = 0L
    private var callHistory = mutableListOf<Call>()
    
    private val callbacks = mutableListOf<CallCallback>()
    
    interface CallCallback {
        fun onCallStateChanged(call: Call)
        fun onCallDuration(seconds: Long)
        fun onCallEnded(call: Call)
        fun onError(message: String)
    }
    
    fun addCallback(callback: CallCallback) {
        callbacks.add(callback)
    }
    
    fun removeCallback(callback: CallCallback) {
        callbacks.remove(callback)
    }
    
    /**
     * Place outgoing call
     */
    fun placeCall(phoneNumber: String): Boolean {
        return try {
            if (isCallActive()) {
                Log.w(tag, "Call already active")
                return false
            }
            
            // Validate phone number
            if (phoneNumber.isEmpty()) {
                notifyError("Invalid phone number")
                return false
            }
            
            // Create call record
            currentCall = Call(
                id = UUID.randomUUID().toString(),
                phoneNumber = phoneNumber,
                type = CallType.OUTGOING,
                status = CallStatus.DIALING
            )
            
            callStartTime = System.currentTimeMillis()
            notifyCallStateChanged(currentCall!!)
            
            // Start the call
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            
            context.startActivity(intent)
            Log.d(tag, "Call placed to $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error placing call", e)
            notifyError("Failed to place call: ${e.message}")
            false
        }
    }
    
    /**
     * Handle incoming call
     */
    fun handleIncomingCall(phoneNumber: String) {
        if (isCallActive()) {
            Log.w(tag, "Call already active")
            return
        }
        
        currentCall = Call(
            id = UUID.randomUUID().toString(),
            phoneNumber = phoneNumber,
            type = CallType.INCOMING,
            status = CallStatus.RINGING
        )
        
        callStartTime = System.currentTimeMillis()
        notifyCallStateChanged(currentCall!!)
        Log.d(tag, "Incoming call from $phoneNumber")
    }
    
    /**
     * Accept current incoming call
     */
    fun acceptCall(): Boolean {
        if (currentCall == null || currentCall?.type != CallType.INCOMING) {
            notifyError("No incoming call to accept")
            return false
        }
        
        currentCall = currentCall?.copy(status = CallStatus.ACTIVE)
        callStartTime = System.currentTimeMillis()
        notifyCallStateChanged(currentCall!!)
        Log.d(tag, "Call accepted from ${currentCall?.phoneNumber}")
        startDurationTimer()
        return true
    }
    
    /**
     * End current call
     */
    fun endCall(): Boolean {
        if (currentCall == null) {
            notifyError("No active call")
            return false
        }
        
        val duration = (System.currentTimeMillis() - callStartTime) / 1000
        currentCall = currentCall?.copy(
            status = CallStatus.ENDED,
            duration = duration
        )
        
        // Save to history
        currentCall?.let {
            callHistory.add(it)
            notifyCallEnded(it)
        }
        
        Log.d(tag, "Call ended. Duration: ${duration}s")
        currentCall = null
        return true
    }
    
    /**
     * Reject incoming call
     */
    fun rejectCall(): Boolean {
        if (currentCall == null || currentCall?.type != CallType.INCOMING) {
            return false
        }
        
        currentCall = currentCall?.copy(
            status = CallStatus.ENDED,
            type = CallType.MISSED
        )
        
        currentCall?.let {
            callHistory.add(it)
            notifyCallEnded(it)
        }
        
        Log.d(tag, "Call rejected from ${currentCall?.phoneNumber}")
        currentCall = null
        return true
    }
    
    /**
     * Get current call state
     */
    fun getCurrentCall(): Call? = currentCall
    
    fun isCallActive(): Boolean = currentCall != null && currentCall?.status == CallStatus.ACTIVE
    
    fun isCallOngoing(): Boolean = currentCall != null && 
        (currentCall?.status == CallStatus.ACTIVE || 
         currentCall?.status == CallStatus.DIALING || 
         currentCall?.status == CallStatus.RINGING)
    
    /**
     * Get call history
     */
    fun getCallHistory(): List<Call> = callHistory.toList()
    
    fun clearCallHistory() {
        callHistory.clear()
        Log.d(tag, "Call history cleared")
    }
    
    // Callback helpers
    private fun notifyCallStateChanged(call: Call) {
        callbacks.forEach { it.onCallStateChanged(call) }
    }
    
    private fun notifyCallEnded(call: Call) {
        callbacks.forEach { it.onCallEnded(call) }
    }
    
    private fun notifyError(message: String) {
        callbacks.forEach { it.onError(message) }
    }
    
    private fun notifyDuration(seconds: Long) {
        callbacks.forEach { it.onCallDuration(seconds) }
    }
    
    private fun startDurationTimer() {
        kotlin.concurrent.thread {
            var elapsedSeconds = 0L
            while (isCallActive()) {
                try {
                    Thread.sleep(1000)
                    elapsedSeconds = (System.currentTimeMillis() - callStartTime) / 1000
                    notifyDuration(elapsedSeconds)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }
}
