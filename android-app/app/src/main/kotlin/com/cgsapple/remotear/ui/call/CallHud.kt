package com.cgsapple.remotear.ui.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cgsapple.remotear.data.livekit.CallConnectionStatus
import com.cgsapple.remotear.ui.theme.Background
import com.cgsapple.remotear.ui.theme.ErrorRed
import com.cgsapple.remotear.ui.theme.OnBackground
import com.cgsapple.remotear.ui.theme.SecondaryGreen
import com.cgsapple.remotear.ui.theme.TertiaryWarning

@Composable
fun CallHudStrip(
    elapsedSeconds: Int,
    connectionStatus: CallConnectionStatus,
    modifier: Modifier = Modifier,
    showRecordingDot: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Background.copy(alpha = 0.75f))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showRecordingDot) {
                    RecordingDot()
                }
                Text(
                    text = formatElapsed(elapsedSeconds),
                    style = MaterialTheme.typography.labelLarge,
                    color = OnBackground,
                )
            }
            ConnectionStatusPill(status = connectionStatus)
        }
    }
}

@Composable
fun RemoteNetworkUnstableBadge(
    participantLabel: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(TertiaryWarning.copy(alpha = 0.88f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$participantLabel network is unstable — wait for reconnect",
            style = MaterialTheme.typography.labelMedium,
            color = Background,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun ReconnectingBanner(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(TertiaryWarning.copy(alpha = 0.9f))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Reconnecting…",
            style = MaterialTheme.typography.labelMedium,
            color = Background,
        )
    }
}

@Composable
private fun RecordingDot() {
    val transition = rememberInfiniteTransition(label = "recording-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording-dot-alpha",
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(ErrorRed, CircleShape),
    )
}

@Composable
private fun ConnectionStatusPill(status: CallConnectionStatus) {
    val (label, color) = when (status) {
        CallConnectionStatus.CONNECTED -> "Connected" to SecondaryGreen
        CallConnectionStatus.CONNECTING -> "Connecting" to TertiaryWarning
        CallConnectionStatus.RECONNECTING -> "Reconnecting" to TertiaryWarning
        CallConnectionStatus.DISCONNECTED -> "Disconnected" to ErrorRed
        CallConnectionStatus.ERROR -> "Error" to ErrorRed
    }

    Text(
        text = label,
        modifier = Modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
