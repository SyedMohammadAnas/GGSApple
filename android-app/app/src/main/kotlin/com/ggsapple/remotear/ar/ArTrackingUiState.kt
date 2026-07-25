package com.ggsapple.remotear.ar

enum class ArTrackingUiState {
    /** Waiting for Google Play Services for AR install / update. */
    INSTALLING,
    SCANNING,
    SURFACE_FOUND,
    TRACKING_LOST,
    STABLE,
}

fun ArTrackingUiState.barMessage(): String? = when (this) {
    ArTrackingUiState.INSTALLING -> "Installing AR services… return here after Play Store"
    ArTrackingUiState.SCANNING -> "Scanning… move camera slowly"
    ArTrackingUiState.SURFACE_FOUND -> "Surface found"
    ArTrackingUiState.TRACKING_LOST -> "Tracking lost — move camera slowly"
    ArTrackingUiState.STABLE -> null
}
