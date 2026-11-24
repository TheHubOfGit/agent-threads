package com.agenthud.threads.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Runs [block] now and then every [intervalMs] (re-read each round) while the app is on screen.
 * Stops when the watch sleeps or another app comes up, so nothing polls from a pocket.
 */
@Composable
fun PollWhileVisible(key: Any?, intervalMs: () -> Long, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val currentBlock by rememberUpdatedState(block)
    val currentInterval by rememberUpdatedState(intervalMs)
    LaunchedEffect(key, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                currentBlock()
                delay(currentInterval())
            }
        }
    }
}

/**
 * A dot for "working": one soft pulse every two seconds, still in between. A continuously breathing
 * dot redrew the screen 60 times a second for as long as an agent worked (minutes, on a battery).
 */
@Composable
fun PulseDot(color: Color, size: Dp = 7.dp) {
    val a = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            a.animateTo(0.35f, tween(380))
            a.animateTo(1f, tween(420))
            delay(1_200)
        }
    }
    Box(Modifier.size(size).alpha(a.value).clip(CircleShape).background(color))
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Round icon button used for Speak / Type / Quick replies. */
@Composable
fun RoundAction(
    bg: Color,
    size: Dp = 46.dp,
    enabled: Boolean = true,
    /** Spoken by TalkBack (and used by UI tests); the icon alone says nothing to them. */
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) bg else Surface2)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { content() }
}

val QUICK_REPLIES = listOf(
    "Continue",
    "Yes, go ahead",
    "No, stop there",
    "Looks good",
    "Summarize where things stand",
    "Explain that more simply",
    "Commit and push it"
)
