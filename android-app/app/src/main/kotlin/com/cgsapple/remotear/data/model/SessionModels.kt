package com.cgsapple.remotear.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateSessionResponse(
    @SerialName("sessionId")
    val sessionId: String,
    @SerialName("roomName")
    val roomName: String,
    @SerialName("joinCode")
    val joinCode: String,
    val status: String,
    val token: String,
)

@Serializable
data class JoinSessionResponse(
    @SerialName("sessionId")
    val sessionId: String,
    @SerialName("roomName")
    val roomName: String,
    @SerialName("joinCode")
    val joinCode: String,
    val status: String,
    val token: String,
)

@Serializable
data class EndSessionResponse(
    @SerialName("sessionId")
    val sessionId: String,
    val status: String,
)

@Serializable
data class SessionRow(
    val id: String,
    @SerialName("join_code")
    val joinCode: String,
    @SerialName("room_name")
    val roomName: String,
    val status: String,
    @SerialName("customer_id")
    val customerId: String,
    @SerialName("technician_id")
    val technicianId: String? = null,
)

@Serializable
data class SessionDetailResponse(
    val session: SessionRow,
)

@Serializable
data class ApiErrorResponse(
    val error: String? = null,
)

@Serializable
data class JoinByPublicIdRequest(
    val targetPublicId: String,
)

data class ActiveSession(
    val sessionId: String,
    val joinCode: String,
    val roomName: String,
    val livekitToken: String,
    val role: SessionParticipantRole,
)

enum class SessionParticipantRole {
    CUSTOMER,
    TECHNICIAN,
}

enum class SessionStatus {
    WAITING,
    ACTIVE,
    ENDED,
    ;

    companion object {
        fun fromRaw(value: String): SessionStatus? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

class SessionApiException(
    val errorMessage: String,
    val statusCode: Int? = null,
) : Exception(errorMessage)
