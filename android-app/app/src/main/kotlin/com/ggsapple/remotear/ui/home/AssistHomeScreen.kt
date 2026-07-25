package com.ggsapple.remotear.ui.home

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ggsapple.remotear.R
import com.ggsapple.remotear.data.local.AppMode
import com.ggsapple.remotear.data.model.Profile
import com.ggsapple.remotear.ui.theme.AccentOrange
import com.ggsapple.remotear.ui.theme.OnSurfaceVariant
import com.ggsapple.remotear.ui.theme.SurfaceVariant
import com.ggsapple.remotear.util.PublicIdFormatter

@Composable
fun AssistHomeScreen(
    profile: Profile,
    uiState: HomeUiState,
    onAppModeChange: (AppMode) -> Unit,
    onExpertIdChange: (String) -> Unit,
    onPasteExpertId: (String) -> Unit,
    onShareId: () -> Unit,
    onJoinSession: () -> Unit,
    onCreateTutorial: () -> Unit,
    onSignOut: () -> Unit,
    onClearCache: () -> Unit,
    onDismissCacheMessage: () -> Unit,
    onOpenDebug: () -> Unit,
    onDismissDebug: () -> Unit,
    onDebugApiUrlChange: (String) -> Unit,
    onDebugLivekitUrlChange: (String) -> Unit,
    onSaveDebugUrls: () -> Unit,
    onResetDebugUrls: () -> Unit,
    connectionStatusContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val isExpert = uiState.appMode == AppMode.EXPERT
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.cacheClearMessage) {
        val message = uiState.cacheClearMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissCacheMessage()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentOrange),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("AR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "AR Assist",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isExpert,
                        onCheckedChange = { checked ->
                            onAppModeChange(if (checked) AppMode.EXPERT else AppMode.CUSTOMER)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentOrange,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = SurfaceVariant,
                        ),
                    )
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Clear cache") },
                            onClick = { menuExpanded = false; onClearCache() },
                        )
                        DropdownMenuItem(
                            text = { Text("Debug backend URL") },
                            onClick = { menuExpanded = false; onOpenDebug() },
                        )
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            onClick = { menuExpanded = false; onSignOut() },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Image(
                painter = painterResource(R.drawable.app_preview),
                contentDescription = "AR Assist preview",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isExpert) {
                    Text(
                        text = "Enter customer ID to provide quick remote support",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = uiState.expertIdInput,
                        onValueChange = onExpertIdChange,
                        placeholder = { Text("Enter the ID", color = OnSurfaceVariant) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let(onPasteExpertId)
                            }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Paste", tint = OnSurfaceVariant)
                            }
                        },
                    )
                    OrangePillButton(
                        text = "Join the session",
                        icon = Icons.AutoMirrored.Outlined.Login,
                        enabled = PublicIdFormatter.isValid(uiState.expertIdInput) && !uiState.isLoading,
                        loading = uiState.isLoading,
                        onClick = onJoinSession,
                    )
                } else {
                    Text(
                        text = "Share your ID to receive quick remote support",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(SurfaceVariant)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Your ID", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                            Text(
                                text = uiState.formattedPublicId,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(uiState.formattedPublicId))
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy ID", tint = Color.White)
                        }
                    }
                    OrangePillButton(
                        text = "Share your ID",
                        icon = Icons.Outlined.Share,
                        enabled = !uiState.isEnsuringSession,
                        loading = uiState.isEnsuringSession,
                        onClick = {
                            onShareId()
                            val shareText = "Join my AR Assist session with my ID: ${uiState.formattedPublicId}"
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    },
                                    "Share your ID",
                                ),
                            )
                        },
                    )
                }

                OutlinedButton(
                    onClick = onCreateTutorial,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Icon(Icons.Outlined.Videocam, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create video tutorial", color = Color.White)
                }

                if (uiState.errorMessage != null) {
                    Text(
                        text = uiState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            connectionStatusContent()
            Spacer(modifier = Modifier.height(8.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
        ) { data ->
            Snackbar(snackbarData = data)
        }

        if (uiState.showDebugSheet) {
            DebugBackendSheet(
                apiUrl = uiState.debugApiUrl,
                livekitUrl = uiState.debugLivekitUrl,
                onApiUrlChange = onDebugApiUrlChange,
                onLivekitUrlChange = onDebugLivekitUrlChange,
                onSave = onSaveDebugUrls,
                onReset = onResetDebugUrls,
                onDismiss = onDismissDebug,
            )
        }
    }
}

@Composable
private fun OrangePillButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) AccentOrange else AccentOrange.copy(alpha = 0.4f)),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun DeviceStatusBar(
    connectionReady: Boolean,
    incomingSession: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val batteryLevel = rememberBatteryLevel()
    val wifiBars = rememberWifiBars()

    val statusColor = when {
        incomingSession -> com.ggsapple.remotear.ui.theme.AccentOrange
        connectionReady -> com.ggsapple.remotear.ui.theme.SecondaryGreen
        else -> Color(0xFFF39C12)
    }
    val statusText = when {
        incomingSession -> "Incoming session connection"
        connectionReady -> "Ready to connect (connection is secure)"
        else -> "Checking connection…"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WifiStrengthIcon(bars = wifiBars)
            Text(
                text = batteryLevel,
                color = com.ggsapple.remotear.ui.theme.SecondaryGreen,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun WifiStrengthIcon(bars: Int) {
    val icon = if (bars > 0) Icons.Outlined.Wifi else Icons.Outlined.WifiOff
    val tint = if (bars > 0) com.ggsapple.remotear.ui.theme.SecondaryGreen else OnSurfaceVariant
    Icon(
        imageVector = icon,
        contentDescription = if (bars > 0) "Wi-Fi connected" else "No Wi-Fi",
        tint = tint,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun DebugBackendSheet(
    apiUrl: String,
    livekitUrl: String,
    onApiUrlChange: (String) -> Unit,
    onLivekitUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceVariant)
                .padding(20.dp)
                .fillMaxWidth(),
        ) {
            Text("Debug backend URLs", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = apiUrl,
                onValueChange = onApiUrlChange,
                label = { Text("API URL (https://…)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = livekitUrl,
                onValueChange = onLivekitUrlChange,
                label = { Text("LiveKit URL (wss://…)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("Save", color = AccentOrange)
                }
                TextButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                    Text("Reset", color = OnSurfaceVariant)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel", color = OnSurfaceVariant)
                }
            }
        }
    }
}
