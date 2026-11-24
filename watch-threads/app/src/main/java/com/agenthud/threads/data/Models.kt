package com.agenthud.threads.data

import org.json.JSONArray
import org.json.JSONObject

/** One conversation in any provider (T3 Code, Codex, Claude Code, Antigravity). */
data class Conversation(
    val id: String,
    val provider: String,
    val providerLabel: String,
    val source: String,
    val title: String,
    val project: String = "",
    val preview: String,
    val previewRole: String,
    val updatedAtMs: Long,
    /** "running" | "waiting_approval" | "waiting_input" | "error" | "idle" */
    val status: String,
    val step: String,
    val prompt: String?,
    val options: List<String>,
    /** "choice" (multiple-choice question), "input", or "" */
    val kind: String,
    val multiSelect: Boolean,
    /** A question or approval is open and can be answered from the watch. */
    val canAct: Boolean,
    /** Typed or spoken replies can reach exactly this conversation. */
    val canReply: Boolean,
    /** Why replies can't reach it, or how they will (Antigravity types into the app). */
    val replyNote: String,
    /** The last reply sent from the watch failed on the Mac. */
    val replyError: String
) {
    val isWaiting: Boolean get() = status.startsWith("waiting")
    val isRunning: Boolean get() = status == "running"
    val isError: Boolean get() = status == "error"

    companion object {
        fun from(o: JSONObject) = Conversation(
            id = o.optString("id"),
            provider = o.optString("provider"),
            providerLabel = o.optString("providerLabel", "Agent"),
            source = o.optString("source", "desktop"),
            title = o.optString("title", "Conversation"),
            project = o.optString("project", ""),
            preview = o.optString("preview", ""),
            previewRole = o.optString("previewRole", ""),
            updatedAtMs = o.optLong("updatedAtMs", 0L),
            status = o.optString("status", "idle"),
            step = o.optString("step", ""),
            prompt = if (o.isNull("prompt")) null else o.optString("prompt").ifBlank { null },
            options = o.optJSONArray("options").strings(),
            kind = o.optString("kind", ""),
            multiSelect = o.optBoolean("multiSelect", false),
            canAct = o.optBoolean("canAct", false),
            canReply = o.optBoolean("canReply", false),
            replyNote = o.optString("replyNote", ""),
            replyError = o.optString("replyError", "")
        )
    }
}

/** role: "user" | "assistant" | "activity" (a folded run of tool steps). */
data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val atMs: Long,
    val truncated: Boolean,
    /** Image tokens; fetch previews with BridgeApi.image(). */
    val images: List<String> = emptyList()
) {
    companion object {
        fun from(o: JSONObject) = ChatMessage(
            id = o.optString("id"),
            role = o.optString("role", "assistant"),
            text = o.optString("text", ""),
            atMs = o.optLong("atMs", 0L),
            truncated = o.optBoolean("truncated", false),
            images = o.optJSONArray("images").strings()
        )
    }
}

data class ConversationPage(
    val conversation: Conversation,
    val messages: List<ChatMessage>,
    val hasMore: Boolean
)

/** A reply sent from the watch, shown until the conversation's own record contains it. */
data class PendingReply(val text: String, val sentAtMs: Long)

enum class LinkState { SEARCHING, CONNECTED, OFFLINE }

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add)
    }
}
