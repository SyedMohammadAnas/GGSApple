package com.ggsapple.remotear.ui.call

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ggsapple.remotear.ui.theme.ErrorRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSettingsSheet(
    isMuted: Boolean,
    isEnding: Boolean,
    onDismiss: () -> Unit,
    onToggleMute: () -> Unit,
    onEndCall: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            TextButton(
                onClick = onToggleMute,
                enabled = !isEnding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isMuted) "Unmute" else "Mute")
            }
            TextButton(
                onClick = onEndCall,
                enabled = !isEnding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isEnding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("End Call", color = ErrorRed)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
