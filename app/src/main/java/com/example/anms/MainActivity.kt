package com.example.anms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.anms.network.HttpServer
import com.example.anms.network.WebSocketServer
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val tag = "ANMS_Main"
    private val permissionRequestCode = 42
    
    private var httpServer: HttpServer? = null
    private var wsServer: WebSocketServer? = null
    private var isServersRunning = false
    private var startTime: Long = 0
    private var messageCount = 0
    private var clientCount = 0
    
    // UI Elements
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var clientCountText: TextView
    private lateinit var messageCountText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var restartBtn: Button
    private lateinit var messagesTabBtn: Button
    private lateinit var logsTabBtn: Button
    private lateinit var smsTabBtn: Button
    private lateinit var messagesTab: LinearLayout
    private lateinit var logsTab: LinearLayout
    private lateinit var smsTab: LinearLayout
    
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
        // Status
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        clientCountText = findViewById(R.id.clientCountText)
        messageCountText = findViewById(R.id.messageCountText)
        uptimeText = findViewById(R.id.uptimeText)
        
        // Control Buttons
        startBtn = findViewById(R.id.startServerButton)
        stopBtn = findViewById(R.id.stopServerButton)
        restartBtn = findViewById(R.id.restartServerButton)
        
        // Tab Buttons
        messagesTabBtn = findViewById(R.id.messagesTabButton)
        logsTabBtn = findViewById(R.id.logsTabButton)
        smsTabBtn = findViewById(R.id.smsTabButton)
        
        // Tabs
        messagesTab = findViewById(R.id.messagesTab)
        logsTab = findViewById(R.id.logsTab)
        smsTab = findViewById(R.id.smsTab)
        
        updateStatusUI()
    }
    
    private fun setupButtons() {
        startBtn.setOnClickListener { startServers() }
        stopBtn.setOnClickListener { stopServers() }
        restartBtn.setOnClickListener { restartServers() }
        
        messagesTabBtn.setOnClickListener { switchTab(0) }
        logsTabBtn.setOnClickListener { switchTab(1) }
        smsTabBtn.setOnClickListener { switchTab(2) }
    }
    
    private fun startServers() {
        if (isServersRunning) {
            Log.d(tag, "Servers already running")
            return
        }
        
        thread {
            try {
                httpServer = HttpServer(this@MainActivity, 8080)
                httpServer?.start()
                Log.d(tag, "HTTP Server started")
                
                wsServer = WebSocketServer(this@MainActivity, 8765) { message ->
                    messageCount++
                    updateStatsUI()
                }
                wsServer?.start()
                Log.d(tag, "WebSocket Server started")
                
                isServersRunning = true
                startTime = System.currentTimeMillis()
                
                runOnUiThread {
                    updateStatusUI()
                    statusText.text = "Online"
                    statusDot.setBackgroundColor(android.graphics.Color.GREEN)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error starting servers", e)
                runOnUiThread {
                    statusText.text = "Error"
                    statusDot.setBackgroundColor(android.graphics.Color.RED)
                }
            }
        }
    }
    
    private fun stopServers() {
        thread {
            try {
                httpServer?.stopServer()
                wsServer?.stopServer()
                isServersRunning = false
                messageCount = 0
                clientCount = 0
                
                runOnUiThread {
                    updateStatusUI()
                    statusText.text = "Offline"
                    statusDot.setBackgroundColor(android.graphics.Color.RED)
                    uptimeText.text = "0s"
                }
            } catch (e: Exception) {
                Log.e(tag, "Error stopping servers", e)
            }
        }
    }
    
    private fun restartServers() {
        stopServers()
        thread {
            Thread.sleep(500)
            startServers()
        }
    }
    
    private fun switchTab(tabIndex: Int) {
        // Hide all tabs
        messagesTab.visibility = View.GONE
        logsTab.visibility = View.GONE
        smsTab.visibility = View.GONE
        
        // Reset button colors to gray
        messagesTabBtn.setBackgroundColor(android.graphics.Color.LTGRAY)
        logsTabBtn.setBackgroundColor(android.graphics.Color.LTGRAY)
        smsTabBtn.setBackgroundColor(android.graphics.Color.LTGRAY)
        
        // Show selected tab and highlight button
        when (tabIndex) {
            0 -> {
                messagesTab.visibility = View.VISIBLE
                messagesTabBtn.setBackgroundColor(android.graphics.Color.parseColor("#667eea"))
            }
            1 -> {
                logsTab.visibility = View.VISIBLE
                logsTabBtn.setBackgroundColor(android.graphics.Color.parseColor("#667eea"))
            }
            2 -> {
                smsTab.visibility = View.VISIBLE
                smsTabBtn.setBackgroundColor(android.graphics.Color.parseColor("#667eea"))
            }
        }
    }
    
    private fun updateStatusUI() {
        clientCountText.text = clientCount.toString()
        messageCountText.text = messageCount.toString()
    }
    
    private fun updateStatsUI() {
        clientCountText.text = clientCount.toString()
        messageCountText.text = messageCount.toString()
    }
    
    private fun startUptimeTimer() {
        thread {
            while (true) {
                Thread.sleep(1000)
                if (isServersRunning) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    runOnUiThread {
                        uptimeText.text = formatUptime(elapsed)
                    }
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
        stopServers()
    }
}