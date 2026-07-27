package com.ggsapple.remotear.ui.navigation

object Routes {
    const val SPLASH = "splash"
    const val AUTH = "auth"
    const val HOME = "home"
    const val SESSION_ENDED = "session_ended"
    const val LOCAL_TUTORIAL = "local_tutorial"

    const val CUSTOMER_CALL = "customer_call/{sessionId}/{joinCode}"

    fun customerCall(sessionId: String, joinCode: String): String =
        "customer_call/$sessionId/${compactJoinCode(joinCode)}"

    private fun compactJoinCode(joinCode: String): String =
        joinCode.uppercase().filter { it.isLetterOrDigit() }
}
