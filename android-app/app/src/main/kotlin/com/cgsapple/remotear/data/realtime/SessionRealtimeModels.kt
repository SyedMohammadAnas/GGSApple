package com.cgsapple.remotear.data.realtime

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessagePayload(
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
)

data class ChatMessage(
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isLocal: Boolean,
)

@Serializable
data class FileSharePayload(
    val senderId: String,
    val senderName: String,
    val fileUrl: String,
    val fileName: String,
    val fileSizeBytes: Long = 0L,
    val timestamp: Long,
)

data class SharedFileNotice(
    val senderId: String,
    val senderName: String,
    val fileUrl: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val timestamp: Long,
    val isLocal: Boolean,
)
