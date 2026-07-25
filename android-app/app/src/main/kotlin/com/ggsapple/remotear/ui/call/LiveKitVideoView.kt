package com.ggsapple.remotear.ui.call

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon

@Composable
fun LiveKitVideoView(
    room: Room,
    videoTrack: VideoTrack?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    paused: Boolean = false,
) {
    var renderer by remember { mutableStateOf<TextureViewRenderer?>(null) }
    var attached by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TextureViewRenderer(context).apply {
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    isClickable = false
                    isFocusable = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    room.initVideoRenderer(this)
                    setMirror(mirror)
                    renderer = this
                    Log.i(TAG, "TextureViewRenderer created scaling=SCALE_ASPECT_FIT")
                }
            },
            update = { view ->
                view.setMirror(mirror)
                view.isClickable = false
                view.isFocusable = false
            },
        )

        if (paused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Pause,
                    contentDescription = "Paused",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
                Text(
                    text = "Paused",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    DisposableEffect(videoTrack, renderer, paused) {
        val view = renderer
        if (view != null && videoTrack != null && !paused && !attached) {
            videoTrack.addRenderer(view)
            attached = true
            Log.i(TAG, "VideoTrack attached to TextureViewRenderer")
        } else if (paused && attached && view != null && videoTrack != null) {
            videoTrack.removeRenderer(view)
            attached = false
            Log.i(TAG, "VideoTrack detached — paused (last frame frozen)")
        }
        onDispose {
            if (view != null && videoTrack != null && attached) {
                videoTrack.removeRenderer(view)
                attached = false
            }
        }
    }
}

private const val TAG = "LiveKitVideoView"
