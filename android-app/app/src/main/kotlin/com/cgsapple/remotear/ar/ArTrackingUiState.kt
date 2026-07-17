package com.cgsapple.remotear.ar

enum class ArTrackingUiState {
    SCANNING,
    SURFACE_FOUND,
    TRACKING_LOST,
    STABLE,
}

fun ArTrackingUiState.barMessage(): String? = when (this) {
    ArTrackingUiState.SCANNING -> "Scanning… move camera slowly"
    ArTrackingUiState.SURFACE_FOUND -> "Surface found"
    ArTrackingUiState.TRACKING_LOST -> "Tracking lost — move camera slowly"
    ArTrackingUiState.STABLE -> null
}
