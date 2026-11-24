package com.agenthud.threads.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.roundToInt

enum class PickPhase { Sending, Done, Failed }


/**
 * The choices for an open question. [picked] is the one tapped (null until then); [phase] says
 * whether it is still sending, landed, or failed. The other choices fold away and the picked one
 * becomes your reply bubble, marked Sending… then Sent; a failed send shakes it back into a choice.
 */
@Composable
fun ChoicePicker(
    options: List<String>,
    picked: String?,
    phase: PickPhase?,
    onPick: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { opt ->
            val isPicked = opt == picked
            val others = picked != null && !isPicked
            val failed = phase == PickPhase.Failed
            AnimatedVisibility(
                visible = !others || failed,
                enter = fadeIn(tween(220)) + expandVertically(tween(260)),
                exit = fadeOut(tween(180, delayMillis = 140)) + shrinkVertically(tween(300, delayMillis = 140, easing = FastOutSlowInEasing))
            ) {
                BubbleChoice(opt, isPicked, phase, enabled = picked == null || failed, onPick)
            }
        }
    }
}

/** Shakes sideways once when a send fails, so the choice visibly bounces back. */
@Composable
private fun shakeOffset(phase: PickPhase?, isPicked: Boolean): Int {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(phase, isPicked) {
        if (isPicked && phase == PickPhase.Failed) {
            shake.animateTo(0f, keyframes {
                durationMillis = 420
                -10f at 60; 9f at 130; -6f at 200; 4f at 270; -2f at 340; 0f at 420
            })
        }
    }
    return shake.value.roundToInt()
}

/** A real button to TalkBack and UI tests (the raw tap handler that finds the tap point isn't). */
private fun Modifier.choiceSemantics(label: String, picked: Boolean, enabled: Boolean, onPick: (String) -> Unit) =
    semantics {
        contentDescription = label
        role = Role.Button
        selected = picked
        if (enabled) onClick(label = "Choose") { onPick(label); true } else disabled()
    }

@Composable
private fun BubbleChoice(opt: String, isPicked: Boolean, phase: PickPhase?, enabled: Boolean, onPick: (String) -> Unit) {
    val shake = shakeOffset(phase, isPicked)
    val active = isPicked && phase != PickPhase.Failed
    val spring = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    // Slides to the right edge and narrows to the size of a message bubble.
    val bias by animateFloatAsState(if (active) 1f else 0f, spring, label = "bias")
    val width by animateFloatAsState(if (active) 0.74f else 1f, spring, label = "width")
    val tail by animateFloatAsState(if (active) 5f else 24f, tween(320), label = "tail")
    val bg by animateColorAsState(
        when {
            active -> UserBubble
            opt.equals("Deny", true) -> Surface2
            else -> SignalAmber.copy(alpha = 0.18f)
        },
        tween(320), label = "bg"
    )
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = BiasAlignment(bias, 0f)) {
            Box(
                Modifier
                    .padding(end = (6f * bias).dp)
                    .fillMaxWidth(width)
                    .offset { IntOffset(shake, 0) }
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = tail.dp))
                    .background(bg)
                    .pointerInput(enabled, opt) {
                        if (!enabled) return@pointerInput
                        detectTapGestures { onPick(opt) }
                    }
                    .choiceSemantics(opt, isPicked, enabled, onPick)
                    .defaultMinSize(minHeight = 46.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column {
                    Text(opt, fontSize = 14.sp, color = if (opt.equals("Deny", true) && !active) InkDim else Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    // Status inside the bubble, like a sent message; outside, the round edge clipped it.
                    AnimatedVisibility(
                        visible = active,
                        enter = fadeIn(tween(200, delayMillis = 220)) + expandVertically(tween(220, delayMillis = 200))
                    ) {
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (phase == PickPhase.Done) {
                                CheckIcon(SignalGreen, 11.dp)
                                Spacer(Modifier.width(4.dp))
                                Text("Sent", fontSize = 11.sp, color = SignalGreen)
                            } else {
                                Text("Sending…", fontSize = 11.sp, color = InkFaint)
                            }
                        }
                    }
                }
            }
        }
    }
}
