package com.agenthud.threads.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agenthud.threads.R

// Same palette as the desk HUD, so both apps read as one product.
val Ink = Color(0xFFE8EAED)
val InkDim = Color(0xFFB8BBBF)
val InkFaint = Color(0xFF80858C)
val Surface1 = Color(0xFF16181C)
val Surface2 = Color(0xFF202329)
val UserBubble = Color(0xFF22446B)
val AgentStrip = Color(0xFF13161B)
val ProjectPillBg = Color(0xFF202834)
val ProjectPillInk = Color(0xFFD5E4FA)
val SignalAmber = Color(0xFFF2B441)
val SignalRed = Color(0xFFF28B82)
val SignalGreen = Color(0xFF81C995)
val ActionBlue = Color(0xFF8AB4F8)

data class ProviderLook(val accent: Color, @DrawableRes val logo: Int?, val short: String)

fun providerLook(provider: String): ProviderLook = when (provider.lowercase()) {
    "claude" -> ProviderLook(Color(0xFFD97757), R.drawable.logo_claude, "Claude")
    "codex" -> ProviderLook(Color(0xFF7A84FF), R.drawable.logo_codex_color, "Codex")
    "antigravity" -> ProviderLook(Color(0xFF3D8BFF), R.drawable.logo_antigravity, "Antigravity")
    "t3code" -> ProviderLook(Color(0xFFD5D8DD), R.drawable.logo_t3code, "T3 Code")
    else -> ProviderLook(ActionBlue, null, provider)
}

/** Filter chips cycle through these ("" = all). */
val PROVIDER_FILTERS = listOf("", "t3code", "claude", "codex", "antigravity")

@Composable
fun ProviderLogo(provider: String, size: Dp = 14.dp, modifier: Modifier = Modifier) {
    val res = providerLook(provider).logo ?: return
    Image(painter = painterResource(res), contentDescription = null, modifier = modifier.size(size))
}

/** "now", "4m", "2h", "Tue", "Nov 3". */
fun shortAgo(atMs: Long, nowMs: Long): String {
    if (atMs <= 0L) return ""
    val d = (nowMs - atMs).coerceAtLeast(0L)
    val min = d / 60_000L
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        min < 24 * 60 -> "${min / 60}h"
        min < 6 * 24 * 60 -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(atMs))
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(atMs))
    }
}

/** Clock time for a gap marker between messages: "3:41 PM", or "Tue 3:41 PM" on another day. */
fun clockLabel(atMs: Long, nowMs: Long): String {
    val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).let {
        it.format(java.util.Date(atMs)) == it.format(java.util.Date(nowMs))
    }
    val pattern = if (sameDay) "h:mm a" else "EEE h:mm a"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(atMs))
}
