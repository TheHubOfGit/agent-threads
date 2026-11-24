package com.agenthud.threads.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.agenthud.threads.audio.VoiceRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Step {
    data object NeedMic : Step
    data class Recording(val level: Float, val seconds: Int) : Step
    data object Transcribing : Step
    data class Review(val text: String) : Step
    data object Quick : Step
    data class Sending(val text: String) : Step
    data class Sent(val message: String) : Step
    data class Failed(val message: String, val text: String?) : Step
}

/** Longest single voice reply; past this the recording stops on its own. */
private const val MAX_SECONDS = 90

/**
 * Composing a reply to one conversation. [mode]: "voice" starts recording at once, "quick" shows
 * canned replies, "review" shows [draft] (from the keyboard) ready to send.
 */
@Composable
fun ReplyScreen(state: AppState, id: String, mode: String, draft: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder() }
    val title = state.pages[id]?.conversation?.title ?: state.conversations.value.firstOrNull { it.id == id }?.title.orEmpty()

    var step by remember {
        mutableStateOf<Step>(
            when (mode) {
                "quick" -> Step.Quick
                "review" -> if (draft.isNotBlank()) Step.Review(draft) else Step.Quick
                else -> Step.NeedMic
            }
        )
    }
    var recordJob by remember { mutableStateOf<Job?>(null) }
    val clientIds = remember { mutableMapOf<String, String>() }
    val level = remember { mutableFloatStateOf(0f) }
    val seconds = remember { mutableIntStateOf(0) }

    fun send(text: String) {
        // Ignore a second tap while one is on its way or done.
        if (step is Step.Sending || step is Step.Sent) return
        // Same text, same id: a retry after a timeout can't post it twice.
        val clientId = clientIds.getOrPut(text.trim()) { java.util.UUID.randomUUID().toString() }
        step = Step.Sending(text)
        scope.launch {
            state.sendReply(id, text, clientId)
                .onSuccess {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    step = Step.Sent(it)
                    delay(900)
                    onDone()
                }
                .onFailure { step = Step.Failed(it.message ?: "Couldn't send", text) }
        }
    }

    fun startRecording() {
        seconds.intValue = 0
        level.floatValue = 0f
        step = Step.Recording(0f, 0)
        recordJob = scope.launch {
            val ticker = launch {
                while (true) {
                    delay(1000)
                    seconds.intValue += 1
                    if (seconds.intValue >= MAX_SECONDS) recorder.stop()
                }
            }
            val wav = try {
                recorder.recordUntilStopped { level.floatValue = it }
            } catch (t: Throwable) {
                ticker.cancel()
                step = Step.Failed("The microphone isn't available", null)
                return@launch
            }
            ticker.cancel()
            if (wav.size < 44 + 16000) { // under half a second of audio
                step = Step.Failed("Didn't catch that", null)
                return@launch
            }
            step = Step.Transcribing
            state.api.transcribe(wav)
                .onSuccess { step = Step.Review(it) }
                .onFailure { step = Step.Failed(it.message ?: "Couldn't transcribe", null) }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else step = Step.Failed("Allow the microphone to reply by voice (Settings > Apps > Agent Threads > Permissions)", null)
    }

    /** Every way into recording goes through here, so a refused microphone is asked for again. */
    fun speak() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val retype = rememberTextInput(
        label = "Reply",
        initial = (step as? Step.Review)?.text.orEmpty(),
        onUnavailable = { step = Step.Failed("No keyboard on this watch", (step as? Step.Review)?.text) }
    ) { step = Step.Review(it) }

    LaunchedEffect(Unit) {
        if (step is Step.NeedMic) speak()
    }
    DisposableEffect(Unit) { onDispose { recorder.stop(); recordJob?.cancel() } }

    when (val s = step) {
        is Step.NeedMic -> Centered { CircularProgressIndicator(indicatorColor = ActionBlue) }
        is Step.Recording -> RecordingView(level.floatValue, seconds.intValue, title) { recorder.stop() }
        is Step.Transcribing -> Centered {
            CircularProgressIndicator(indicatorColor = ActionBlue)
            Text("Transcribing on your Mac", fontSize = 12.sp, color = InkDim, modifier = Modifier.padding(top = 10.dp))
        }
        is Step.Sending -> Centered {
            CircularProgressIndicator(indicatorColor = ActionBlue)
            Text("Sending", fontSize = 12.sp, color = InkDim, modifier = Modifier.padding(top = 10.dp))
        }
        is Step.Sent -> Centered {
            Box(Modifier.size(48.dp).clip(CircleShape).background(SignalGreen), contentAlignment = Alignment.Center) {
                CheckIcon(Color(0xFF0E2214), 24.dp)
            }
            Text(s.message, fontSize = 13.sp, color = Ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, start = 24.dp, end = 24.dp))
        }
        is Step.Review -> ChoiceList(header = "Send to ${title.ifBlank { "this conversation" }}?", body = s.text) {
            primary("Send") { send(s.text) }
            secondary("Say it again") { speak() }
            secondary("Edit with keyboard") { retype() }
            secondary("Cancel") { onDone() }
        }
        is Step.Quick -> QuickList(title) { send(it) }
        is Step.Failed -> ChoiceList(header = s.message, body = s.text, headerColor = SignalRed) {
            if (s.text != null) primary("Try again") { send(s.text) } else primary("Speak again") { speak() }
            secondary("Cancel") { onDone() }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) { content() }
}

@Composable
private fun RecordingView(level: Float, seconds: Int, title: String, onStop: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().clickable(onClick = onStop).padding(horizontal = 28.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text("LISTENING", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = ActionBlue)
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size((64 + level * 34).dp).clip(CircleShape).background(ActionBlue.copy(alpha = 0.18f)))
            Box(Modifier.size(52.dp).clip(CircleShape).background(ActionBlue), contentAlignment = Alignment.Center) {
                MicIcon(Color(0xFF0E1A2B), 24.dp)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%d:%02d".format(seconds / 60, seconds % 60), fontSize = 13.sp, color = Ink)
            Text("Tap to stop", fontSize = 11.sp, color = InkFaint)
            if (title.isNotBlank()) Text(title, fontSize = 10.sp, color = InkFaint, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private class ChoiceScope {
    val entries = mutableListOf<Triple<String, Boolean, () -> Unit>>()
    fun primary(label: String, onClick: () -> Unit) { entries += Triple(label, true, onClick) }
    fun secondary(label: String, onClick: () -> Unit) { entries += Triple(label, false, onClick) }
}

@Composable
private fun ChoiceList(header: String, body: String?, headerColor: Color = InkDim, build: ChoiceScope.() -> Unit) {
    val entries = ChoiceScope().apply(build).entries
    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 30.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text(header, fontSize = 12.sp, color = headerColor, textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))
            }
            if (!body.isNullOrBlank()) item {
                Bubble(text = body, mine = true, accent = ActionBlue)
            }
            items(entries) { (label, isPrimary, onClick) ->
                Chip(
                    onClick = onClick,
                    label = { Text(label, fontSize = 13.sp) },
                    colors = if (isPrimary) ChipDefaults.chipColors(backgroundColor = ActionBlue, contentColor = Color(0xFF0E1A2B))
                    else ChipDefaults.chipColors(backgroundColor = Surface2, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun QuickList(title: String, onPick: (String) -> Unit) {
    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 30.dp, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Quick reply", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    if (title.isNotBlank()) Text(title, fontSize = 10.sp, color = InkFaint, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            items(QUICK_REPLIES) { text ->
                Chip(
                    onClick = { onPick(text) },
                    label = { Text(text, fontSize = 13.sp, maxLines = 2) },
                    colors = ChipDefaults.chipColors(backgroundColor = Surface2, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}
