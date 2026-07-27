package com.ggsapple.remotear.ui.call

import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import coil.compose.AsyncImage
import com.ggsapple.remotear.data.model.ModelItem
import com.ggsapple.remotear.ui.session.SidebarTool
import com.ggsapple.remotear.ui.theme.ErrorRed
import com.ggsapple.remotear.ui.theme.GlassActiveTool
import com.ggsapple.remotear.ui.theme.GlassPanel
import com.ggsapple.remotear.ui.theme.GlassPanelBorder
import com.ggsapple.remotear.ui.theme.OnBackground
import com.ggsapple.remotear.ui.theme.OnSurfaceVariant
import com.ggsapple.remotear.ui.theme.SecondaryGreen
import com.ggsapple.remotear.data.livekit.CallConnectionStatus

@Composable
fun Modifier.glassSurface(
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    fillAlpha: Float = 0.72f,
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(GlassPanel.copy(alpha = fillAlpha))
    .border(1.dp, GlassPanelBorder.copy(alpha = 0.35f), RoundedCornerShape(cornerRadius))

@Composable
fun AssistSessionTopBar(
    onSessionPillClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .glassSurface(cornerRadius = 24.dp)
                .clickable(onClick = onSessionPillClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Assist AR Session",
                style = MaterialTheme.typography.labelLarge,
                color = OnBackground,
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = OnBackground,
                modifier = Modifier.size(18.dp),
            )
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(GlassPanel.copy(alpha = 0.55f))
                .border(1.dp, GlassPanelBorder.copy(alpha = 0.35f), CircleShape)
                .clickable(onClick = onProfileClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = "Profile",
                tint = OnBackground.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun SessionMenuPanel(
    sessionLabel: String,
    elapsedSeconds: Int,
    connectionStatus: CallConnectionStatus,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .glassSurface(cornerRadius = 20.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Session recording",
                style = MaterialTheme.typography.bodyMedium,
                color = OnBackground,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = formatElapsed(elapsedSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(ErrorRed, CircleShape),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SessionMenuRow(
            label = "in-session chat",
            trailing = {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = Color(0xFF4DA3FF),
                    modifier = Modifier.size(22.dp),
                )
            },
        )

        Spacer(modifier = Modifier.height(10.dp))

        SessionMenuRow(
            label = "File sharing",
            trailing = {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = OnBackground,
                    modifier = Modifier.size(22.dp),
                )
            },
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = GlassPanelBorder.copy(alpha = 0.45f),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = sessionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = OnBackground,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (connectionStatus == CallConnectionStatus.CONNECTED) {
                                SecondaryGreen
                            } else {
                                Color(0xFFF39C12)
                            },
                            CircleShape,
                        ),
                )
                Text(
                    text = formatElapsed(elapsedSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnBackground,
                )
            }
        }
    }
}

@Composable
private fun SessionMenuRow(
    label: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = OnBackground,
        )
        trailing()
    }
}

@Composable
fun AnnotationSidebar(
    activeTool: SidebarTool,
    drawingEnabled: Boolean,
    onToolSelected: (SidebarTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tools = listOf(
        SidebarTool.POINTER to Icons.Outlined.NearMe,
        SidebarTool.ARROW to Icons.Outlined.ArrowDownward,
        SidebarTool.DRAW to Icons.Outlined.Edit,
        SidebarTool.CIRCLE to Icons.Outlined.RadioButtonUnchecked,
        SidebarTool.UNDO to Icons.AutoMirrored.Outlined.Undo,
        SidebarTool.DELETE to Icons.Outlined.DeleteOutline,
    )

    Column(
        modifier = modifier
            .glassSurface(cornerRadius = 18.dp)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tools.forEach { (tool, icon) ->
            val enabled = when (tool) {
                SidebarTool.POINTER, SidebarTool.DRAW, SidebarTool.ARROW, SidebarTool.CIRCLE -> drawingEnabled
                // Undo/clear must stay tappable even while AR is still scanning.
                SidebarTool.UNDO, SidebarTool.DELETE -> true
                else -> true
            }
            val selected = when (tool) {
                SidebarTool.UNDO, SidebarTool.DELETE -> false
                else -> activeTool == tool
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            selected -> GlassActiveTool
                            else -> Color.Transparent
                        },
                    )
                    .clickable(enabled = enabled) {
                        Log.i(TAG, "AnnotationSidebar tap tool=$tool enabled=$enabled")
                        onToolSelected(tool)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = tool.name,
                    tint = if (enabled) OnBackground else OnSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
fun CallControlBottomSheet(
    isMuted: Boolean,
    isEnding: Boolean,
    isTechnician: Boolean,
    expanded: Boolean,
    modelsLoading: Boolean,
    assetSearchQuery: String,
    models: List<ModelItem>,
    recentModelIds: List<String>,
    onToggleExpanded: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePause: () -> Unit,
    onEndCall: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onModelSelected: (ModelItem) -> Unit,
    modifier: Modifier = Modifier,
    speakerOn: Boolean = true,
    paused: Boolean = false,
) {
    val filteredModels = remember(models, assetSearchQuery) {
        if (assetSearchQuery.isBlank()) models
        else models.filter {
            it.name.contains(assetSearchQuery, ignoreCase = true) ||
                (it.description?.contains(assetSearchQuery, ignoreCase = true) == true)
        }
    }
    val recentModels = remember(models, recentModelIds) {
        recentModelIds.mapNotNull { id -> models.find { it.id == id } }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 28.dp, fillAlpha = 0.78f)
            .animateContentSize()
            .padding(top = 8.dp, bottom = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(OnSurfaceVariant.copy(alpha = 0.55f))
                .clickable(onClick = onToggleExpanded),
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CallControlButton(
                label = "speaker",
                icon = Icons.Outlined.VolumeUp,
                active = speakerOn,
                onClick = onToggleSpeaker,
            )
            CallControlButton(
                label = if (isMuted) "unmute" else "mute",
                icon = if (isMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                onClick = onToggleMute,
            )
            CallControlButton(
                label = if (paused) "resume" else "pause",
                icon = Icons.Outlined.Pause,
                active = paused,
                onClick = onTogglePause,
            )
            CallControlButton(
                label = "end",
                icon = Icons.Default.Close,
                tint = Color.White,
                background = ErrorRed,
                loading = isEnding,
                onClick = onEndCall,
            )
        }

        if (isTechnician && expanded) {
            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = assetSearchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnBackground),
                    cursorBrush = SolidColor(OnBackground),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (assetSearchQuery.isEmpty()) {
                            Text(
                                text = "Search through assets",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
                if (assetSearchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = OnSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onSearchQueryChange("") },
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (modelsLoading) {
                Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            } else {
                if (recentModels.isNotEmpty()) {
                    Text(
                        "Recent models",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    ) {
                        items(recentModels, key = { "recent-${it.id}" }) { model ->
                            ModelThumbnail(model = model, onClick = { onModelSelected(model) })
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    "All models",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filteredModels, key = { it.id }) { model ->
                        ModelThumbnail(model = model, onClick = { onModelSelected(model) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControlButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    background: Color = Color.White.copy(alpha = if (active) 0.22f else 0.12f),
    tint: Color = OnBackground,
    loading: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = !loading) {
                    Log.i(TAG, "CallControlButton tap label=$label loading=$loading")
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = tint,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnBackground.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ModelThumbnail(
    model: ModelItem,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF3A3A3C))
            .border(1.dp, GlassPanelBorder.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Column {
            if (!model.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = model.thumbnailUrl,
                    contentDescription = model.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF6E6E73)),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = model.name,
                style = MaterialTheme.typography.labelSmall,
                color = OnBackground.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ModelDetailSheet(
    model: ModelItem?,
    onBack: () -> Unit,
    onPlace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (model == null) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 28.dp, fillAlpha = 0.82f)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(OnSurfaceVariant.copy(alpha = 0.55f)),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = OnBackground,
                    modifier = Modifier.size(18.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBackground,
                    textDecoration = TextDecoration.Underline,
                )
                Text(
                    text = model.description ?: "Component model",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onPlace)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Place",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnBackground,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFD1D1D6).copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!model.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = model.thumbnailUrl,
                    contentDescription = model.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(width = 120.dp, height = 90.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF8E8E93)),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "3D preview",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF3A3A3C),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private const val TAG = "AssistCallUi"
