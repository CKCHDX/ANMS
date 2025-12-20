package com.example.anms

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.anms.network.HttpServer
import com.example.anms.network.WebSocketServer
import com.example.anms.sms.SmsCache
import com.example.anms.sms.SmsReceiver
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val tag = "ANMS_Main"
    private val permissionRequestCode = 42
    
    private var httpServer: HttpServer? = null
    private var wsServer: WebSocketServer? = null
    private var smsCache: SmsCache? = null
    private var isServerRunning = false
    private var startTime: Long = 0
    private var messageCount = 0
    
    private lateinit var statusText: TextView
    private lateinit var messageCountText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var permStatusText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var restartBtn: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        try {
            initViews()
            setupButtons()
            requestPermissions()
            smsCache = SmsCache(this)
            startUptimeTimer()
            startSMSService()
        } catch (e: Exception) {
            Log.e(tag, "Error in onCreate", e)
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
            permissions.add(Manifest.permission.FOREGROUND_SERVICE)
        }
        
        val missingPerms = permissions.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        
        Log.d(tag, "Checking permissions: ${permissions.size} total, ${missingPerms.size} missing")
        missingPerms.forEach { Log.d(tag, "  Missing: $it") }
        
        if (missingPerms.isNotEmpty()) {
            Log.d(tag, "Requesting ${missingPerms.size} permissions...")
            ActivityCompat.requestPermissions(this, missingPerms.toTypedArray(), permissionRequestCode)
        } else {
            Log.d(tag, "All permissions already granted")
            updatePermissionStatus()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            Log.d(tag, "Permission result received")
            permissions.forEachIndexed { i, perm ->
                val granted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                Log.d(tag, "  $perm: $granted")
            }
            updatePermissionStatus()
        }
    }
    
    private fun updatePermissionStatus() {
        val readSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val sendSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val receiveSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        
        val status = when {
            readSms && sendSms && receiveSms -> "✓ SMS permissions granted"
            readSms && sendSms -> "⚠ Missing RECEIVE_SMS"
            readSms && receiveSms -> "⚠ Missing SEND_SMS"
            readSms -> "⚠ Missing SEND/RECEIVE_SMS"
            else -> "✗ READ_SMS permission DENIED - Cannot read messages!"
        }
        
        Log.d(tag, status)
        permStatusText.text = status
    }
    
    private fun startSMSService() {
        try {
            val serviceIntent = Intent(this, SMSListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(tag, "SMS Listener Service started")
        } catch (e: Exception) {
            Log.e(tag, "Error starting SMS service", e)
        }
    }
    
    private fun initViews() {
        try {
            statusText = findViewById(R.id.statusText)
            messageCountText = findViewById(R.id.messageCountText)
            uptimeText = findViewById(R.id.uptimeText)
            permStatusText = findViewById(R.id.permStatusText)
            
            startBtn = findViewById(R.id.startServerButton)
            stopBtn = findViewById(R.id.stopServerButton)
            restartBtn = findViewById(R.id.restartServerButton)
            
            updateStatusUI()
        } catch (e: Exception) {
            Log.e(tag, "Error initializing views", e)
        }
    }
    
    private fun setupButtons() {
        try {
            startBtn.setOnClickListener { startServer() }
            stopBtn.setOnClickListener { stopServer() }
            restartBtn.setOnClickListener { restartServer() }
        } catch (e: Exception) {
            Log.e(tag, "Error setting up buttons", e)
        }
    }
    
    private fun startServer() {
        if (isServerRunning) {
            Log.d(tag, "Server already running")
            return
        }
        
        thread {
            try {
                // Start WebSocket server (for incoming SMS)
                wsServer = WebSocketServer(8765)
                wsServer?.start()
                SmsReceiver.globalWebSocketServer = wsServer
                Log.d(tag, "WebSocket Server started on port 8765")
                Thread.sleep(500)
                
                // Start HTTP server (for sending SMS)
                httpServer = HttpServer(this@MainActivity, 8080, wsServer, smsCache)
                httpServer?.start()
                Log.d(tag, "HTTP Server started on port 8080")
                
                isServerRunning = true
                startTime = System.currentTimeMillis()
                
                runOnUiThread {
                    try {
                        updateStatusUI()
                        statusText.text = "Online - Open http://YOUR_IP:8080"
                    } catch (e: Exception) {
                        Log.e(tag, "Error updating UI", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error starting server", e)
                runOnUiThread {
                    try {
                        statusText.text = "Error: ${e.message}"
                    } catch (ex: Exception) {
                        Log.e(tag, "Error updating error UI", ex)
                    }
                }
            }
        }
    }
    
    private fun stopServer() {
        thread {
            try {
                httpServer?.stopServer()
                wsServer?.stopServer()
                SmsReceiver.globalWebSocketServer = null
                isServerRunning = false
                messageCount = 0
                
                runOnUiThread {
                    try {
                        updateStatusUI()
                        statusText.text = "Offline"
                        uptimeText.text = "0s"
                    } catch (e: Exception) {
                        Log.e(tag, "Error updating UI", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error stopping server", e)
            }
        }
    }
    
    private fun restartServer() {
        stopServer()
        thread {
            Thread.sleep(500)
            startServer()
        }
    }
    
    private fun updateStatusUI() {
        try {
            messageCountText.text = messageCount.toString()
        } catch (e: Exception) {
            Log.e(tag, "Error updating status UI", e)
        }
    }
    
    private fun startUptimeTimer() {
        thread {
            while (true) {
                try {
                    Thread.sleep(1000)
                    if (isServerRunning) {
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        runOnUiThread {
                            try {
                                uptimeText.text = formatUptime(elapsed)
                            } catch (e: Exception) {
                                Log.e(tag, "Error updating uptime", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Uptime timer error", e)
                }
            }
        }
    }
    
    private fun formatUptime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, secs)
            minutes > 0 -> String.format("%02d:%02d", minutes, secs)
            else -> "${secs}s"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            stopServer()
        } catch (e: Exception) {
            Log.e(tag, "Error in onDestroy", e)
        }
    }
}