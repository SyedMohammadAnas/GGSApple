package com.ggsapple.remotear.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val email: String,
    @SerialName("display_name")
    val displayName: String? = null,
    val role: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("public_id")
    val publicId: String? = null,
) {
    val userRole: UserRole
        get() = when (role.lowercase()) {
            "technician" -> UserRole.TECHNICIAN
            else -> UserRole.CUSTOMER
        }
}

enum class UserRole {
    CUSTOMER,
    TECHNICIAN,
}
