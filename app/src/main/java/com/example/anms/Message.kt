package com.example.anms

data class Message(
    val phoneNumber: String,
    val content: String,
    val timestamp: Long,
    val isOutgoing: Boolean = false
)