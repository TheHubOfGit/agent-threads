package com.agenthud.threads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.agenthud.threads.data.ChatMessage
import com.agenthud.threads.data.Conversation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Messages longer than this show a preview here and the full text one tap away. */
private const val INLINE_CHARS = 700

@Composable
fun ConversationScreen(
    state: AppState,
    id: String,
    onSpeak: () -> Unit,
    onQuick: () -> Unit,
    onReview: (String) -> Unit,
    onOpenMessage: (String) -> Unit,
    onOpenImage: (String) -> Unit = {}
) {
    val page = state.pages[id]
    val conv: Conversation? = page?.conversation ?: state.conversations.value.firstOrNull { it.id == id }
    val pending = state.pending[id]
    val route by state.api.route.collectAsState()
    val offset by state.api.clockOffsetMs.collectAsState()
    val now = System.currentTimeMillis() + offset
    var loadError by remember(id) { mutableStateOf("") }
    var actionNote by remember(id) { mutableStateOf("") }
    // The answer picked here, kept on screen (frozen) until a different question opens, so it's
    // clear what was chosen and a second tap can't land while the Mac catches up.
    val pick = state.picks[id]
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val listState = rememberScalingLazyListState()
    // Items land with their top at mid-screen; a question is lifted most of the way up instead.
    val questionLift = with(androidx.compose.ui.platform.LocalDensity.current) {
        (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.34f).roundToPx()
    }

    val startTyping = rememberTextInput(
        label = "Reply",
        onUnavailable = { actionNote = "No keyboard on this watch. Use Speak or Quick." }
    ) { text -> onReview(text) }

    PollWhileVisible(id, {
        val busy = conv?.isRunning == true || pending != null
        if (route == com.agenthud.threads.data.BridgeApi.Route.RELAY) {
            if (busy) 3_500L else 8_000L
        } else {
            if (busy) 2_000L else 5_000L
        }
    }) {
        state.refreshConversation(id)
            .onSuccess { loadError = "" }
            .onFailure { loadError = it.message ?: "Can't reach the Mac" }
    }

    val messages = page?.messages.orEmpty()
    val hasMore = page?.hasMore == true
    // Item layout: 0 header, [1 "earlier"], messages..., [pending], status/actions.
    val firstMsgIndex = 1 + if (hasMore) 1 else 0
    val lastContentIndex = firstMsgIndex + messages.size - 1 + if (pending != null) 1 else 0

    // Open at the newest message; follow new ones only if already reading the end.
    var landed by remember(id) { mutableStateOf(false) }
    var lastCount by remember(id) { mutableStateOf(0) }
    // A new question clears notes about the last one (e.g. an old error) and comes into view.
    val currentQuestion = if (conv?.canAct == true) "${conv.prompt}|${conv.options.joinToString("|")}" else ""
    LaunchedEffect(currentQuestion) {
        if (currentQuestion.isNotEmpty() && currentQuestion != pick?.key) {
            actionNote = ""
            if (pick?.phase != PickPhase.Sending) state.picks.remove(id)
        }
    }
    LaunchedEffect(page != null, messages.size, pending, currentQuestion) {
        if (page == null) return@LaunchedEffect
        val target = lastContentIndex.coerceAtLeast(0)
        // Where an open question's card sits: right after the last message (and any working line).
        val questionIndex = target + 1
        if (!landed) {
            // Open on the question if one is waiting; otherwise on the newest message.
            if (currentQuestion.isNotEmpty() && currentQuestion != pick?.key) {
                // Question near the top, choices visible under it.
                listState.scrollToItem(questionIndex, questionLift)
            } else {
                listState.scrollToItem(target)
            }
            landed = true
        } else if (messages.size != lastCount || pending != null || currentQuestion.isNotEmpty()) {
            val atEnd = listState.centerItemIndex >= firstMsgIndex + lastCount - 2
            if (atEnd) {
                if (currentQuestion.isNotEmpty()) listState.animateScrollToItem(questionIndex, questionLift)
                else listState.animateScrollToItem(target)
            }
        }
        lastCount = messages.size
    }

    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            anchorType = ScalingLazyListAnchorType.ItemStart,
            autoCentering = null,
            contentPadding = PaddingValues(start = 7.dp, end = 7.dp, top = 26.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { Header(conv) }

            if (hasMore) item {
                CompactChip(
                    onClick = { scope.launch { state.loadEarlier(id) } },
                    label = { Text("Earlier messages", fontSize = 12.sp) },
                    colors = ChipDefaults.chipColors(backgroundColor = Surface2, contentColor = InkDim)
                )
            }

            if (page == null) {
                item { StatusNote(loadError.ifBlank { "Loading…" }, if (loadError.isBlank()) InkDim else SignalRed) }
            } else if (messages.isEmpty() && conv?.canAct != true && pick == null) {
                item { StatusNote("No messages yet") }
            }

            messages.forEachIndexed { i, m ->
                val prev = messages.getOrNull(i - 1)
                val showTime = m.atMs > 0 && (prev == null || m.atMs - prev.atMs > 15 * 60_000L)
                // Stable keys (ids are unique per page) keep the scroll steady when earlier messages load.
                item(key = m.id) {
                    MessageItem(
                        m = m,
                        api = state.api,
                        accent = providerLook(conv?.provider.orEmpty()).accent,
                        timeLabel = if (showTime) clockLabel(m.atMs, now) else null,
                        onOpen = { onOpenMessage(m.id) },
                        onOpenImage = onOpenImage
                    )
                }
            }

            if (pending != null) item(key = "pending") {
                Bubble(text = pending.text, mine = true, note = "Sent from watch", accent = ActionBlue)
            }

            if (conv != null) {
                val questionKey = "${conv.prompt}|${conv.options.joinToString("|")}"
                statusAndActions(
                    conv = conv,
                    pick = pick,
                    pendingSent = pending != null,
                    loadError = loadError.takeIf { page != null }.orEmpty(),
                    actionNote = actionNote,
                    onOption = { option ->
                        actionNote = ""
                        state.pickAnswer(conv, option, questionKey) { ok ->
                            view.performHapticFeedback(
                                if (ok) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.REJECT
                            )
                        }
                    },
                    onSpeak = onSpeak,
                    onType = startTyping,
                    onQuick = onQuick
                )
            }
        }
    }
}

@Composable
private fun Header(conv: Conversation?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        if (conv != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(conv.provider, 12.dp)
                Spacer(Modifier.width(4.dp))
                Text(conv.providerLabel, fontSize = 11.sp, color = InkFaint, maxLines = 1)
            }
        }
        Text(
            conv?.title ?: "Conversation",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
}

@Composable
private fun MessageItem(
    m: ChatMessage,
    api: com.agenthud.threads.data.BridgeApi,
    accent: Color,
    timeLabel: String?,
    onOpen: () -> Unit,
    onOpenImage: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (timeLabel != null) {
            Text(timeLabel, fontSize = 10.sp, color = InkFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp))
        }
        when (m.role) {
            "activity" -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                ) {
                    Dot(InkFaint, 4.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(m.text, fontSize = 11.sp, color = InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (m.images.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    ThumbStrip(api, m.images, onOpen = onOpenImage)
                }
            }
            else -> {
                val text = plainText(m.text)
                val long = text.length > INLINE_CHARS || m.truncated
                Bubble(
                    text = if (text.length > INLINE_CHARS) safeTake(text, INLINE_CHARS).trimEnd() + "…" else text,
                    mine = m.role == "user",
                    accent = accent,
                    note = if (long) "Read all" else null,
                    onClick = if (long) onOpen else null,
                    images = m.images,
                    api = api,
                    onOpenImage = onOpenImage
                )
            }
        }
    }
}

@Composable
fun Bubble(
    text: String,
    mine: Boolean,
    accent: Color,
    note: String? = null,
    onClick: (() -> Unit)? = null,
    images: List<String> = emptyList(),
    api: com.agenthud.threads.data.BridgeApi? = null,
    onOpenImage: (String) -> Unit = {}
) {
    val imageOnly = images.isNotEmpty() && text.isEmpty()
    val shape = if (mine) {
        RoundedCornerShape(13.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 13.dp, bottomEnd = 13.dp)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (mine) UserBubble else AgentStrip)
            .then(
                if (!mine) Modifier.drawBehind {
                    drawRect(color = accent, topLeft = Offset.Zero, size = Size(3.dp.toPx(), size.height))
                } else Modifier
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = if (imageOnly) (if (mine) 6.dp else 9.dp) else (if (mine) 10.dp else 12.dp),
                end = if (imageOnly) 6.dp else 10.dp,
                top = if (imageOnly) 6.dp else 7.dp,
                bottom = if (imageOnly) 6.dp else 7.dp
            ),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (images.isNotEmpty() && api != null) {
                ImageGrid(api, images, mine, onOpenImage)
                if (text.isNotEmpty()) Spacer(Modifier.height(6.dp))
            }
            if (text.isNotEmpty()) Text(text, fontSize = 14.sp, lineHeight = 18.sp, color = Ink)
            if (note != null) {
                Text(note, fontSize = 11.sp, color = if (onClick != null) ActionBlue else InkFaint, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (mine && !imageOnly) {
            Text(
                "▸",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = ActionBlue,
                modifier = Modifier.padding(start = 6.dp, top = 1.dp)
            )
        }
    }
}

/** An answer tapped on the watch, with the question it answered (kept even after it closes). */
data class PickState(
    val key: String,
    val prompt: String?,
    val status: String,
    val options: List<String>,
    val picked: String,
    val phase: PickPhase,
    /** Why the answer failed (shown while phase is Failed). */
    val error: String? = null
)

private fun ScalingLazyListScope.statusAndActions(
    conv: Conversation,
    pick: PickState?,
    pendingSent: Boolean,
    loadError: String,
    actionNote: String,
    onOption: (String) -> Unit,
    onSpeak: () -> Unit,
    onType: () -> Unit,
    onQuick: () -> Unit
) {
    val look = providerLook(conv.provider)

    // The open question, or the one just answered here (frozen, showing what was picked).
    val shown = pick ?: if (conv.canAct) PickState("", conv.prompt, conv.status, conv.options, "", PickPhase.Sending) else null
    if (shown != null) {
        val answered = pick?.phase == PickPhase.Done
        item(key = "prompt") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, if (answered) InkFaint.copy(alpha = 0.5f) else SignalAmber, RoundedCornerShape(16.dp))
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                Text(
                    when {
                        answered -> "Answered"
                        shown.status == "waiting_approval" -> "Needs approval"
                        else -> "Question"
                    },
                    fontSize = 11.sp, color = if (answered) InkFaint else SignalAmber, fontWeight = FontWeight.SemiBold
                )
                Text(plainText(shown.prompt ?: "Waiting on you"), fontSize = 13.sp, lineHeight = 17.sp, color = if (answered) InkDim else Ink, modifier = Modifier.padding(top = 2.dp))
                if (conv.multiSelect && !answered) Text("Pick one here; answer the rest on the Mac", fontSize = 10.sp, color = InkFaint, modifier = Modifier.padding(top = 3.dp))
            }
        }
        item(key = "choices") {
            ChoicePicker(
                options = shown.options,
                picked = pick?.picked,
                phase = pick?.phase,
                onPick = onOption
            )
        }
    }
    // After the question: you answered, then the agent works (and nothing above jumps).
    if (conv.isRunning || (pendingSent && !conv.isWaiting)) item(key = "status") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        ) {
            PulseDot(look.accent)
            Spacer(Modifier.width(6.dp))
            Text(
                plainText(conv.step).ifBlank { "Working…" }.take(80),
                fontSize = 12.sp,
                color = InkDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (conv.isError) item(key = "err") { StatusNote(plainText(conv.step).ifBlank { "The last run failed" }, SignalRed) }
    if (conv.replyError.isNotBlank()) item(key = "replyErr") { StatusNote("Reply failed: ${conv.replyError}", SignalRed) }
    if (loadError.isNotBlank()) item(key = "loadErr") { StatusNote(loadError, SignalRed) }
    pick?.error?.let { err -> item(key = "pickErr") { StatusNote(err, SignalRed) } }
    if (actionNote.isNotBlank()) item(key = "actNote") { StatusNote(actionNote) }

    item(key = "reply") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (conv.canReply) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundAction(Surface2, 40.dp, label = "Quick reply", onClick = onQuick) { QuickReplyIcon(Ink, 17.dp) }
                    RoundAction(ActionBlue, 50.dp, label = "Speak a reply", onClick = onSpeak) { MicIcon(Color(0xFF0E1A2B), 22.dp) }
                    RoundAction(Surface2, 40.dp, label = "Type a reply", onClick = onType) { KeyboardIcon(Ink, 18.dp) }
                }
                if (conv.replyNote.isNotBlank()) {
                    Text(conv.replyNote, fontSize = 10.sp, color = InkFaint, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp, start = 16.dp, end = 16.dp))
                }
            } else {
                Text(
                    conv.replyNote.ifBlank { "Replies can't reach this conversation" },
                    fontSize = 11.sp,
                    color = InkFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}


/** First [n] chars without splitting an emoji (a surrogate pair) in half. */
private fun safeTake(text: String, n: Int): String {
    if (text.length <= n) return text
    val end = if (Character.isHighSurrogate(text[n - 1])) n - 1 else n
    return text.substring(0, end)
}
