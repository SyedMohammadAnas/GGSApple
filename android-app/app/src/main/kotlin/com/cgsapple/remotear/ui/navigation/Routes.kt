package com.cgsapple.remotear.ui.navigation

object Routes {
    const val SPLASH = "splash"
    const val AUTH = "auth"
    const val HOME = "home"
    const val CUSTOMER_HOME = "customer_home"
    const val TECHNICIAN_HOME = "technician_home"
    const val JOIN_SESSION = "join_session"
    const val SESSION_ENDED = "session_ended"
    const val LOCAL_TUTORIAL = "local_tutorial"

    const val WAITING = "waiting/{sessionId}/{joinCode}/{publicId}"
    const val CUSTOMER_CALL = "customer_call/{sessionId}/{joinCode}"
    const val TECHNICIAN_CALL = "technician_call/{sessionId}/{joinCode}"

    fun waiting(sessionId: String, joinCode: String, publicId: String = ""): String =
        "waiting/$sessionId/${compactJoinCode(joinCode)}/$publicId"

    fun customerCall(sessionId: String, joinCode: String): String =
        "customer_call/$sessionId/${compactJoinCode(joinCode)}"

    fun technicianCall(sessionId: String, joinCode: String): String =
        "technician_call/$sessionId/${compactJoinCode(joinCode)}"

    private fun compactJoinCode(joinCode: String): String =
        joinCode.uppercase().filter { it.isLetterOrDigit() }
}
