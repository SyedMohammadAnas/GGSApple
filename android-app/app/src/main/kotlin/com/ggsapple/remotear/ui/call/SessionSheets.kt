package com.ggsapple.remotear.ui.call

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ggsapple.remotear.data.realtime.ChatMessage
import com.ggsapple.remotear.data.realtime.SharedFileNotice
import com.ggsapple.remotear.ui.theme.ErrorRed
import com.ggsapple.remotear.ui.theme.GlassPanelBorder
import com.ggsapple.remotear.ui.theme.OnBackground
import com.ggsapple.remotear.ui.theme.OnSurfaceVariant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionOptionsSheet(
    isRecording: Boolean,
    recordingSeconds: Int,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SessionOptionRow(
                label = if (isRecording) "Stop recording" else "Session recording",
                trailing = {
                    if (isRecording) {
                        Text(
                            text = com.ggsapple.remotear.ui.call.formatElapsed(recordingSeconds),
                            style = MaterialTheme.typography.labelMedium,
                            color = ErrorRed,
                        )
                    } else {
                        Icon(Icons.Outlined.FiberManualRecord, null, tint = ErrorRed)
                    }
                },
                onClick = {
                    if (isRecording) onStopRecording() else onStartRecording()
                },
            )
            SessionOptionRow(
                label = "in-session chat",
                trailing = {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Color(0xFF4DA3FF))
                },
                onClick = onOpenChat,
            )
            SessionOptionRow(
                label = "File sharing",
                trailing = {
                    Icon(Icons.Outlined.Description, null, tint = OnBackground)
                },
                onClick = onOpenFiles,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SessionOptionRow(
    label: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = OnBackground)
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(
    messages: List<ChatMessage>,
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text("In-session chat", style = MaterialTheme.typography.titleMedium, color = OnBackground)
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { "${it.timestamp}-${it.senderId}-${it.text.hashCode()}" }) { msg ->
                    ChatBubble(message = msg)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    singleLine = true,
                )
                TextButton(onClick = onSend) {
                    Text("Send")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (message.isLocal) Color(0xFF003F4F) else Color.White.copy(alpha = 0.08f),
            )
            .padding(10.dp),
    ) {
        Text(
            text = message.senderName,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant,
        )
        Text(text = message.text, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSharingSheet(
    files: List<SharedFileNotice>,
    isUploading: Boolean,
    onPickFile: () -> Unit,
    onOpenFile: (SharedFileNotice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("File sharing", style = MaterialTheme.typography.titleMedium, color = OnBackground)
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = onPickFile,
                enabled = !isUploading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isUploading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Pick PDF or image to share")
                }
            }
            HorizontalDivider(color = GlassPanelBorder.copy(alpha = 0.4f))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { "${it.timestamp}-${it.fileUrl}" }) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenFile(file) }
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                file.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${file.senderName} · ${formatFileTime(file.timestamp)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = OnSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun formatFileTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
