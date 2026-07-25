package com.ggsapple.remotear.ui.annotation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ggsapple.remotear.annotation.ANNOTATION_COLORS
import com.ggsapple.remotear.annotation.AnnotationTool
import com.ggsapple.remotear.annotation.parseComposeColor
import com.ggsapple.remotear.ui.theme.Background
import com.ggsapple.remotear.ui.theme.Outline
import com.ggsapple.remotear.ui.theme.PrimaryCyan
import com.ggsapple.remotear.ui.theme.SurfaceVariant

private data class ToolOption(
    val tool: AnnotationTool,
    val label: String,
)

private val TOOLS = listOf(
    ToolOption(AnnotationTool.FREEHAND, "Draw"),
    ToolOption(AnnotationTool.CIRCLE, "Circle"),
    ToolOption(AnnotationTool.ARROW, "Arrow"),
)

@Composable
fun DrawingToolsBar(
    activeTool: AnnotationTool,
    activeColor: String,
    onToolSelected: (AnnotationTool) -> Unit,
    onColorSelected: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Background.copy(alpha = 0.88f))
            .border(1.dp, Outline, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TOOLS.forEach { option ->
                val selected = activeTool == option.tool
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) PrimaryCyan else SurfaceVariant)
                        .clickable { onToolSelected(option.tool) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Background else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ANNOTATION_COLORS.forEach { colorHex ->
                val selected = activeColor.equals(colorHex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(parseComposeColor(colorHex))
                        .border(
                            width = 2.dp,
                            color = if (selected) Color.White else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable { onColorSelected(colorHex) },
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceVariant)
                .clickable(onClick = onClear)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Clear",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
