package com.agenthud.threads.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import com.agenthud.threads.data.BridgeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded previews in memory, by token and size. Thumbnails are small JPEGs made on the Mac
 * (5–25 KB), so a few dozen fit easily; the full-screen size is cached separately.
 */
object ImageMemory {
    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(token: String, width: Int): ImageBitmap? = cache.get("$token@$width")

    suspend fun load(api: BridgeApi, token: String, width: Int): ImageBitmap? {
        get(token, width)?.let { return it }
        val bytes = api.image(token, width).getOrNull() ?: return null
        val bmp = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } ?: return null
        cache.put("$token@$width", bmp)
        return bmp
    }
}

/**
 * One image from a conversation, loaded from the Mac on first show. While loading it holds a
 * neutral box (4:3, or square when [square]); a preview that can't be made says so in place.
 */
@Composable
fun RemoteImage(
    api: BridgeApi,
    token: String,
    widthPx: Int,
    modifier: Modifier = Modifier,
    square: Boolean = false,
    description: String = "Image",
    onClick: (() -> Unit)? = null
) {
    var bmp by remember(token, widthPx) { mutableStateOf(ImageMemory.get(token, widthPx)) }
    var failed by remember(token, widthPx) { mutableStateOf(false) }
    LaunchedEffect(token, widthPx) {
        if (bmp == null) {
            val loaded = ImageMemory.load(api, token, widthPx)
            if (loaded == null) failed = true else bmp = loaded
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val base = modifier
        .clip(shape)
        .background(Surface2)
        .semantics { contentDescription = description }
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    val b = bmp
    when {
        b != null -> Image(
            bitmap = b,
            contentDescription = null,
            contentScale = if (square) ContentScale.Crop else ContentScale.Fit,
            modifier = base.aspectRatio(if (square) 1f else (b.width.toFloat() / b.height).coerceIn(0.5f, 2.2f))
        )
        failed -> Box(base.aspectRatio(if (square) 1f else 4f / 3f), contentAlignment = Alignment.Center) {
            Text("Image unavailable", fontSize = 10.sp, color = InkFaint, textAlign = TextAlign.Center)
        }
        else -> Box(base.aspectRatio(if (square) 1f else 4f / 3f))
    }
}

/** Images in a message bubble: one at full bubble width, or a 2-column grid (with "+N" past four). */
@Composable
fun ImageGrid(api: BridgeApi, tokens: List<String>, mine: Boolean, onOpen: (String) -> Unit) {
    if (tokens.isEmpty()) return
    val desc = if (mine) "Your image" else "Image from the agent"
    if (tokens.size == 1) {
        RemoteImage(api, tokens[0], 320, Modifier.fillMaxWidth(), description = desc) { onOpen(tokens[0]) }
        return
    }
    val shown = tokens.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEachIndexed { col, t ->
                    val index = row * 2 + col
                    Box(Modifier.weight(1f)) {
                        RemoteImage(api, t, 160, Modifier.fillMaxWidth(), square = true, description = desc) { onOpen(t) }
                        if (index == 3 && tokens.size > 4) {
                            Box(
                                Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) { Text("+${tokens.size - 4}", fontSize = 15.sp, color = Ink) }
                        }
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** Small square thumbnails on a tool-step line (screenshots the agent looked at). */
@Composable
fun ThumbStrip(api: BridgeApi, tokens: List<String>, size: Dp = 44.dp, onOpen: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tokens.take(4).forEach { t ->
            RemoteImage(api, t, 96, Modifier.size(size), square = true, description = "Image the agent viewed") { onOpen(t) }
        }
    }
}

/**
 * One image, full screen. Tap to zoom in where you tapped (tap again to fit); turn the crown to zoom
 * smoothly; drag to look around while zoomed. At normal size, swipe right goes back as usual.
 */
@Composable
fun ImageScreen(api: BridgeApi, token: String) {
    var bmp by remember(token) { mutableStateOf(ImageMemory.get(token, 640)) }
    var failed by remember(token) { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(token) {
        if (bmp == null) {
            val loaded = ImageMemory.load(api, token, 640)
            if (loaded == null) failed = true else bmp = loaded
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            // The crown zooms (rotary events reach only a focused node).
            .onRotaryScrollEvent { ev ->
                scale = (scale * (1f + ev.verticalScrollPixels / 600f)).coerceIn(1f, 4f)
                if (scale == 1f) offset = Offset.Zero
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { tap ->
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else {
                        scale = 2.2f
                        offset = Offset((size.width / 2f - tap.x) * 1.2f, (size.height / 2f - tap.y) * 1.2f)
                    }
                })
            }
            // Drag/pinch only while zoomed: at 1x a horizontal drag must stay free for swipe-to-go-back.
            .then(
                if (scale > 1f) Modifier.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        when {
            b != null -> Image(
                bitmap = b,
                contentDescription = "Image. Tap to zoom",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
            )
            failed -> Text("Couldn't load this image", fontSize = 13.sp, color = InkDim)
            else -> CircularProgressIndicator(indicatorColor = ActionBlue)
        }
    }
}
