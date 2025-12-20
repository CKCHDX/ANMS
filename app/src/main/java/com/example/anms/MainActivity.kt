package com.example.anms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.anms.network.HttpServer
import com.example.anms.network.WebSocketServer
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val tag = "ANMS_Main"
    private val permissionRequestCode = 42
    
    private var httpServer: HttpServer? = null
    private var wsServer: WebSocketServer? = null
    private var isServerRunning = false
    private var startTime: Long = 0
    private var messageCount = 0
    
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var messageCountText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var restartBtn: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        requestPermissions()
        initViews()
        setupButtons()
        startUptimeTimer()
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        
        val missingPerms = permissions.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPerms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPerms.toTypedArray(), permissionRequestCode)
        }
    }
    
    private fun initViews() {
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        messageCountText = findViewById(R.id.messageCountText)
        uptimeText = findViewById(R.id.uptimeText)
        
        startBtn = findViewById(R.id.startServerButton)
        stopBtn = findViewById(R.id.stopServerButton)
        restartBtn = findViewById(R.id.restartServerButton)
        
        updateStatusUI()
    }
    
    private fun setupButtons() {
        startBtn.setOnClickListener { startServer() }
        stopBtn.setOnClickListener { stopServer() }
        restartBtn.setOnClickListener { restartServer() }
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
                Log.d(tag, "WebSocket Server started on port 8765")
                Thread.sleep(500)
                
                // Start HTTP server (for sending SMS)
                httpServer = HttpServer(this@MainActivity, 8080, wsServer)
                httpServer?.start()
                Log.d(tag, "HTTP Server started on port 8080")
                
                isServerRunning = true
                startTime = System.currentTimeMillis()
                
                runOnUiThread {
                    updateStatusUI()
                    statusText.text = "Online - Open http://YOUR_IP:8080"
                    statusDot.setBackgroundColor(android.graphics.Color.GREEN)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error starting server", e)
                runOnUiThread {
                    statusText.text = "Error: ${e.message}"
                    statusDot.setBackgroundColor(android.graphics.Color.RED)
                }
            }
        }
    }
    
    private fun stopServer() {
        thread {
            try {
                httpServer?.stopServer()
                wsServer?.stopServer()
                isServerRunning = false
                messageCount = 0
                
                runOnUiThread {
                    updateStatusUI()
                    statusText.text = "Offline"
                    statusDot.setBackgroundColor(android.graphics.Color.RED)
                    uptimeText.text = "0s"
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
        messageCountText.text = messageCount.toString()
    }
    
    private fun startUptimeTimer() {
        thread {
            while (true) {
                try {
                    Thread.sleep(1000)
                    if (isServerRunning) {
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        runOnUiThread {
                            uptimeText.text = formatUptime(elapsed)
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
        stopServer()
    }
}