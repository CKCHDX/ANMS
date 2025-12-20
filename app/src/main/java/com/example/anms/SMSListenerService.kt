package com.example.anms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.anms.sms.SmsReceiver

class SMSListenerService : Service() {
    private val tag = "ANMS_Service"
    private var smsReceiver: SmsReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "SMS Listener Service created")
        startForegroundNotification()
        registerSmsReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "SMS Listener Service started")
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "sms_listener_channel"
        val channelName = "SMS Listener"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ANMS Running")
            .setContentText("Listening for incoming SMS...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
    }

    private fun registerSmsReceiver() {
        smsReceiver = SmsReceiver()
        val intentFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        intentFilter.priority = 999
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(smsReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsReceiver, intentFilter)
        }
        
        Log.d(tag, "SMS Receiver registered")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (smsReceiver != null) {
            unregisterReceiver(smsReceiver)
        }
        Log.d(tag, "SMS Listener Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}