package com.example.ancs.data

data class Call(
    val id: String = "",
    val phoneNumber: String = "",
    val duration: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val type: CallType = CallType.OUTGOING,
    val status: CallStatus = CallStatus.IDLE
)

enum class CallType {
    INCOMING,
    OUTGOING,
    MISSED
}

enum class CallStatus {
    IDLE,
    DIALING,
    RINGING,
    ACTIVE,
    ENDED,
    FAILED
}
