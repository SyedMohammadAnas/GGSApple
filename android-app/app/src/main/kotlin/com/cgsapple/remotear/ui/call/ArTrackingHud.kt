package com.cgsapple.remotear.ui.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cgsapple.remotear.ar.ArTrackingUiState
import com.cgsapple.remotear.ar.barMessage
import com.cgsapple.remotear.ui.theme.Background
import com.cgsapple.remotear.ui.theme.OnBackground
import com.cgsapple.remotear.ui.theme.PrimaryCyan
import com.cgsapple.remotear.ui.theme.SecondaryGreen
import com.cgsapple.remotear.ui.theme.TertiaryWarning

@Composable
fun ArFallbackBanner(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(TertiaryWarning.copy(alpha = 0.88f))
            .padding(vertical = 8.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "AR not available — video mode only",
            style = MaterialTheme.typography.labelMedium,
            color = Background,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ArTrackingStateBar(
    trackingState: ArTrackingUiState,
    modifier: Modifier = Modifier,
) {
    val message = trackingState.barMessage() ?: return

    val textColor = when (trackingState) {
        ArTrackingUiState.INSTALLING -> TertiaryWarning
        ArTrackingUiState.SCANNING -> PrimaryCyan
        ArTrackingUiState.SURFACE_FOUND -> SecondaryGreen
        ArTrackingUiState.TRACKING_LOST -> TertiaryWarning
        ArTrackingUiState.STABLE -> OnBackground
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Background.copy(alpha = 0.65f))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ArScanRingOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) {
        return
    }

    val transition = rememberInfiniteTransition(label = "scan-ring")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan-ring-scale",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = (size.minDimension * 0.12f) * scale
        drawCircle(
            color = PrimaryCyan.copy(alpha = 0.75f),
            radius = radius,
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = 4.dp.toPx()),
        )
    }
}
