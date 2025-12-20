package com.example.anms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.anms.databinding.ActivityMainBinding
import com.example.anms.network.HttpServer
import com.example.anms.network.WebSocketServer
import com.example.anms.sms.SmsManager

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var webSocketServer: WebSocketServer
    private var httpServer: HttpServer? = null
    private lateinit var smsManager: SmsManager
    private lateinit var messageAdapter: MessageAdapter

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeApp()
        } else {
            Toast.makeText(
                this,
                getString(R.string.permissions_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPermissions()
        setupUI()
    }

    private fun setupPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.INTERNET
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            initializeApp()
        }
    }

    private fun setupUI() {
        messageAdapter = MessageAdapter()
        binding.messagesRecyclerView.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
        }

        binding.sendButton.setOnClickListener {
            val phoneNumber = binding.phoneNumberInput.text.toString().trim()
            val message = binding.messageInput.text.toString().trim()

            if (phoneNumber.isNotEmpty() && message.isNotEmpty()) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        smsManager.sendSms(phoneNumber, message)
                        messageAdapter.addMessage(
                            Message(
                                phoneNumber = phoneNumber,
                                content = message,
                                timestamp = System.currentTimeMillis(),
                                isOutgoing = true
                            )
                        )
                        binding.messageInput.text.clear()
                        binding.messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                        Toast.makeText(this, getString(R.string.message_sent), Toast.LENGTH_SHORT).show()
                    } catch (e: SecurityException) {
                        Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, getString(R.string.permission_required_sms), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
            }
        }

        binding.serverStatusTextView.text = getString(R.string.initializing)
    }

    private fun initializeApp() {
        smsManager = SmsManager(this) { incomingMessage ->
            messageAdapter.addMessage(incomingMessage)
            binding.messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
            webSocketServer.broadcastMessage(incomingMessage)
        }

        webSocketServer = WebSocketServer(this, 8765) { clientMessage ->
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    smsManager.sendSms(clientMessage.phoneNumber, clientMessage.content)
                    messageAdapter.addMessage(
                        Message(
                            phoneNumber = clientMessage.phoneNumber,
                            content = clientMessage.content,
                            timestamp = System.currentTimeMillis(),
                            isOutgoing = true
                        )
                    )
                    binding.messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }

        webSocketServer.start()
        
        // Start HTTP server to serve web client
        try {
            httpServer = HttpServer(this, 8080)
            binding.serverStatusTextView.text = getString(R.string.server_running) + "\nHTTP: 8080 | WS: 8765"
        } catch (e: Exception) {
            binding.serverStatusTextView.text = "HTTP Server Error: ${e.message}"
            Toast.makeText(this, "HTTP Server failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webSocketServer.isInitialized) {
            webSocketServer.stopServer()
        }
        if (httpServer != null) {
            httpServer?.stopServer()
        }
    }
}