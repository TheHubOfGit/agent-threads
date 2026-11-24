package com.agenthud.threads.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.agenthud.threads.BuildConfig
import com.agenthud.threads.data.LinkState

/** One message in full, paragraph by paragraph so the crown scrolls it smoothly. */
@Composable
fun MessageScreen(state: AppState, id: String, messageId: String, onOpenImage: (String) -> Unit = {}) {
    val m = state.pages[id]?.messages?.firstOrNull { it.id == messageId }
    val paragraphs = remember(m?.text) {
        plainText(m?.text.orEmpty()).split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    }
    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            anchorType = ScalingLazyListAnchorType.ItemStart,
            autoCentering = null,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    if (m?.role == "user") "You" else state.pages[id]?.conversation?.let { c ->
                        providerLook(c.provider).let { look -> if (look.logo != null) look.short else c.providerLabel }
                    }.orEmpty(),
                    fontSize = 11.sp, color = InkFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
            if (m == null) item { StatusNote("That message is no longer loaded") }
            if (m != null && m.images.isNotEmpty()) item { ImageGrid(state.api, m.images, m.role == "user", onOpenImage) }
            items(paragraphs) { p -> Text(p, fontSize = 14.sp, lineHeight = 19.sp, color = Ink, modifier = Modifier.fillMaxWidth()) }
            if (m?.truncated == true) item { StatusNote("Shortened for the watch. The rest is on the Mac.", InkFaint) }
        }
    }
}

@Composable
fun SettingsScreen(state: AppState) {
    val link by state.api.link.collectAsState()
    val route by state.api.route.collectAsState()
    var relay by remember { mutableStateOf(state.prefs.relayUrl) }
    val lastError by state.api.lastError.collectAsState()
    var host by remember { mutableStateOf(state.prefs.host) }
    var pinned by remember { mutableStateOf(state.prefs.hostPinned) }
    var tokenSet by remember { mutableStateOf(state.prefs.token.isNotBlank()) }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val enterHost = rememberTextInput(
        label = "Mac address (host or host:port)",
        initial = if (pinned) "$host:${state.prefs.port}" else "",
        onUnavailable = { note = "No keyboard on this watch" }
    ) { raw ->
        val parsed = com.agenthud.threads.data.BridgeApi.parseAddress(raw, BuildConfig.DEFAULT_PORT)
        if (parsed == null) {
            note = "That address doesn't look right. Use host or host:port, like 192.168.1.20:8765"
            return@rememberTextInput
        }
        val (h, p) = parsed
        run {
            state.api.setManualHost(h, p)
            host = h; pinned = true; note = "Checking $h:$p…"
            // Try it right away so the status line says whether that address works.
            scope.launch {
                state.refreshList()
                note = if (state.listError.value.isBlank()) "Connected to $h:$p" else state.listError.value
            }
        }
    }
    val enterRelay = rememberTextInput(
        label = "Relay address (https://…)",
        initial = relay,
        onUnavailable = { note = "No keyboard on this watch" }
    ) { raw ->
        val clean = raw.trim().trimEnd('/')
        val ok = clean.isEmpty() || (clean.startsWith("https://") && clean.length > 10 && !clean.contains(' '))
        if (!ok) {
            note = "Use the full https:// address of your relay"
        } else {
            state.prefs.relayUrl = clean
            relay = clean
            note = if (clean.isEmpty()) "Relay off: home network only" else "Relay saved"
        }
    }
    val enterToken = rememberTextInput(label = "Bridge token", onUnavailable = { note = "No keyboard on this watch" }) {
        state.prefs.token = it
        tokenSet = it.isNotBlank()
        note = "Token saved"
    }

    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 30.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { Text("Settings", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink) }
            item {
                val status = when (link) {
                    LinkState.CONNECTED ->
                        if (route == com.agenthud.threads.data.BridgeApi.Route.RELAY) "Connected through the relay"
                        else "Connected on your home network"
                    LinkState.SEARCHING -> if (pinned) "Connecting to $host…" else "Looking for your Mac"
                    LinkState.OFFLINE -> lastError.ifBlank { "Mac not reachable" }
                }
                StatusNote(status, if (link == LinkState.OFFLINE) SignalRed else InkDim)
            }
            item {
                Chip(
                    onClick = enterHost,
                    label = { Text("Mac address", fontSize = 13.sp) },
                    secondaryLabel = { Text(if (pinned) "$host:${state.prefs.port}" else "Automatic (Bonjour)", fontSize = 11.sp, maxLines = 1) },
                    colors = ChipDefaults.chipColors(backgroundColor = Surface2, contentColor = Ink, secondaryContentColor = InkDim),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (pinned) item {
                Chip(
                    onClick = {
                        state.api.setManualHost("", BuildConfig.DEFAULT_PORT)
                        host = ""; pinned = false; note = "Finding your Mac automatically…"
                    },
                    label = { Text("Find Mac automatically", fontSize = 13.sp) },
                    colors = ChipDefaults.chipColors(backgroundColor = Surface2, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Chip(
                    onClick = enterRelay,
                    label = { Text("Relay (away from home)", fontSize = 13.sp) },
                    secondaryLabel = {
                        Text(
                            relay.removePrefix("https://").removePrefix("http://").ifBlank { "Not set: home network only" },
                            fontSize = 11.sp, maxLines = 1
                        )
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Surface2, contentColor = Ink, secondaryContentColor = InkDim),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Chip(
                    onClick = enterToken,
                    label = { Text("Bridge token", fontSize = 13.sp) },
                    secondaryLabel = { Text(if (tokenSet) "Set" else "Missing", fontSize = 11.sp) },
                    colors = ChipDefaults.chipColors(backgroundColor = Surface2, contentColor = Ink, secondaryContentColor = if (tokenSet) InkDim else SignalRed),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (note.isNotBlank()) item { StatusNote(note) }
            item {
                Text(
                    "Agent Threads ${BuildConfig.VERSION_NAME}\nOn your home network it talks to the Mac directly; anywhere else, through the relay. The relay address is picked up from the Mac while you're home.",
                    fontSize = 10.sp, color = InkFaint, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}
