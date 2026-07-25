package com.ggsapple.remotear.ui.session

import android.content.Intent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ggsapple.remotear.ui.theme.AccentOrange
import com.ggsapple.remotear.ui.theme.Background
import com.ggsapple.remotear.ui.theme.PrimaryCyan
import com.ggsapple.remotear.ui.theme.SecondaryGreen
import com.ggsapple.remotear.util.PublicIdFormatter

@Composable
fun WaitingScreen(
    uiState: WaitingUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val displayId = PublicIdFormatter.formatDisplay(uiState.publicId).ifBlank {
        formatJoinCodeForDisplay(uiState.joinCode)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (uiState.expertJoining) "Expert is joining your session…" else "Waiting for expert",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = displayId,
                style = MaterialTheme.typography.headlineLarge,
                color = if (uiState.expertJoining) SecondaryGreen else MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            if (uiState.expertJoining) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Incoming session — connecting now",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryGreen,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(displayId))
                    },
                ) {
                    Text("Copy ID")
                }
                OutlinedButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Join my AR Assist session with my ID: $displayId")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share your ID"))
                    },
                ) {
                    Text("Share")
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            PulsingRing()
            Spacer(modifier = Modifier.height(32.dp))
            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            TextButton(
                onClick = onCancel,
                enabled = !uiState.isCancelling,
            ) {
                if (uiState.isCancelling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = "Cancel Session",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun PulsingRing() {
    val transition = rememberInfiniteTransition(label = "waiting-ring")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring-scale",
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(1f)
                .padding(4.dp),
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxSize(),
            ) {
                drawCircle(
                    color = PrimaryCyan.copy(alpha = 0.35f),
                    radius = size.minDimension / 2f,
                )
                drawCircle(
                    color = PrimaryCyan,
                    radius = size.minDimension / 2f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}

fun formatJoinCodeForDisplay(raw: String): String {
    val cleaned = raw.uppercase().filter { it.isLetterOrDigit() }
    if (cleaned.length != 6) return raw.uppercase()
    return "${cleaned.take(3)}-${cleaned.drop(3)}"
}

fun compactJoinCode(joinCode: String): String =
    joinCode.uppercase().filter { it.isLetterOrDigit() }
