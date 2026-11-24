package com.agenthud.threads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.agenthud.threads.data.Conversation
import com.agenthud.threads.data.LinkState

@Composable
fun ConversationListScreen(state: AppState, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val link by state.api.link.collectAsState()
    val route by state.api.route.collectAsState()
    val offset by state.api.clockOffsetMs.collectAsState()
    val all = state.conversations.value
    val filter = state.filter.value
    val shown = remember(all, filter) { if (filter.isBlank()) all else all.filter { it.provider == filter } }
    val now = System.currentTimeMillis() + offset

    PollWhileVisible(Unit, {
        val active = all.any { it.isRunning || it.isWaiting }
        if (route == com.agenthud.threads.data.BridgeApi.Route.RELAY) {
            if (active) 6_000L else 12_000L
        } else {
            if (active) 4_000L else 8_000L
        }
    }) { state.refreshList() }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            // Start at the top: the title sits just under the clock instead of mid-screen.
            autoCentering = null,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 30.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Threads", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(Modifier.width(6.dp))
                    Dot(
                        when (link) {
                            LinkState.CONNECTED -> SignalGreen
                            LinkState.SEARCHING -> SignalAmber
                            LinkState.OFFLINE -> SignalRed
                        },
                        6.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    // Settings one tap from the top, not past every conversation at the bottom.
                    RoundAction(Surface2, 28.dp, label = "Settings", onClick = onSettings) { GearIcon(InkDim, 14.dp) }
                }
            }
            item {
                val look = providerLook(filter)
                CompactChip(
                    onClick = {
                        val i = PROVIDER_FILTERS.indexOf(filter).coerceAtLeast(0)
                        state.setFilter(PROVIDER_FILTERS[(i + 1) % PROVIDER_FILTERS.size])
                    },
                    label = { Text(if (filter.isBlank()) "All apps" else look.short, fontSize = 12.sp, maxLines = 1) },
                    icon = if (filter.isBlank()) null else ({ ProviderLogo(filter, 14.dp) }),
                    colors = ChipDefaults.chipColors(backgroundColor = Surface2, contentColor = Ink)
                )
            }

            when {
                !state.listLoaded.value -> item {
                    StatusNote(
                        if (state.listError.value.isNotBlank()) state.listError.value
                        else if (link == LinkState.SEARCHING) {
                            if (state.prefs.hostPinned) "Connecting to ${state.prefs.host}…" else "Looking for your Mac…"
                        } else "Loading…"
                    )
                }
                shown.isEmpty() -> item {
                    StatusNote(if (filter.isBlank()) "No conversations this week" else "Nothing from ${providerLook(filter).short} this week")
                }
                else -> {
                    if (state.listError.value.isNotBlank()) item { StatusNote(state.listError.value, SignalRed) }
                    items(shown, key = { it.id }) { c -> ConversationRow(c, now) { onOpen(c.id) } }
                }
            }

            item {
                CompactChip(
                    onClick = onSettings,
                    label = { Text("Settings", fontSize = 12.sp) },
                    icon = { GearIcon(InkDim, 14.dp) },
                    colors = ChipDefaults.chipColors(backgroundColor = Surface1, contentColor = InkDim),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, nowMs: Long, onClick: () -> Unit) {
    val look = providerLook(c.provider)
    val shape = RoundedCornerShape(16.dp)
    val badgeLabel = c.project.ifBlank { if (look.logo != null) look.short else c.providerLabel }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface1)
            .then(if (c.isWaiting) Modifier.border(1.5.dp, SignalAmber, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(c.provider, 14.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                badgeLabel,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (c.project.isNotBlank()) ProjectPillInk else InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (c.project.isNotBlank()) ProjectPillBg else Surface2)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(5.dp))
            when {
                c.isWaiting -> Text("Needs you", fontSize = 10.sp, color = SignalAmber, fontWeight = FontWeight.SemiBold)
                c.isRunning -> PulseDot(look.accent, 7.dp)
                c.isError -> Dot(SignalRed, 7.dp)
                else -> Text(shortAgo(c.updatedAtMs, nowMs), fontSize = 10.sp, color = InkFaint)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            c.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val preview = when {
            c.isWaiting && !c.prompt.isNullOrBlank() -> plainText(c.prompt)
            c.preview.isNotBlank() -> (if (c.previewRole == "user") "You: " else "") + plainText(c.preview)
            else -> ""
        }
        if (preview.isNotBlank()) {
            Text(
                preview,
                fontSize = 12.sp,
                color = if (c.isWaiting) SignalAmber.copy(alpha = 0.85f) else InkDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun StatusNote(text: String, color: androidx.compose.ui.graphics.Color = InkDim) {
    Text(
        text,
        fontSize = 13.sp,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
    )
}
