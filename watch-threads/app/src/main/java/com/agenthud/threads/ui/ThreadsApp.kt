package com.agenthud.threads.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

/**
 * list -> conversation -> (reply | full message). Swipe right goes back a level, as everywhere
 * on Wear OS; the crown scrolls whatever is on screen.
 */
@Composable
fun ThreadsApp(state: AppState) {
    val nav = rememberSwipeDismissableNavController()
    // Typed text waiting for review, by conversation (keeps it out of the route).
    val drafts = remember { mutableStateMapOf<String, String>() }
    fun enc(s: String) = Uri.encode(s)

    val requestedThread = state.openThreadRequest.value
    LaunchedEffect(requestedThread) {
        if (!requestedThread.isNullOrBlank()) {
            state.openThreadRequest.value = null
            nav.navigate("conv/${enc(requestedThread)}") {
                popUpTo("list") { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    MaterialTheme {
        SwipeDismissableNavHost(navController = nav, startDestination = "list") {
            composable("list") {
                ConversationListScreen(
                    state = state,
                    onOpen = { nav.navigate("conv/${enc(it)}") },
                    onSettings = { nav.navigate("settings") }
                )
            }
            composable("conv/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                ConversationScreen(
                    state = state,
                    id = id,
                    onSpeak = { nav.navigate("reply/${enc(id)}/voice") },
                    onQuick = { nav.navigate("reply/${enc(id)}/quick") },
                    onReview = { text ->
                        drafts[id] = text
                        nav.navigate("reply/${enc(id)}/review")
                    },
                    onOpenMessage = { msgId -> if (msgId.isNotBlank()) nav.navigate("msg/${enc(id)}/${enc(msgId)}") },
                    onOpenImage = { token -> nav.navigate("img/$token") }
                )
            }
            composable("reply/{id}/{mode}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val mode = entry.arguments?.getString("mode").orEmpty()
                // Read once for this screen; cleared after it shows, never during composition.
                val draft = remember(entry) { drafts[id].orEmpty() }
                LaunchedEffect(entry) { if (draft.isNotEmpty()) drafts.remove(id) }
                ReplyScreen(
                    state = state,
                    id = id,
                    mode = mode,
                    draft = draft,
                    onDone = { nav.popBackStack() }
                )
            }
            composable("msg/{id}/{msgId}") { entry ->
                MessageScreen(
                    state = state,
                    id = entry.arguments?.getString("id").orEmpty(),
                    messageId = entry.arguments?.getString("msgId").orEmpty(),
                    onOpenImage = { token -> nav.navigate("img/$token") }
                )
            }
            composable("img/{token}") { entry -> ImageScreen(state.api, entry.arguments?.getString("token").orEmpty()) }
            composable("settings") { SettingsScreen(state) }
        }
    }
}
