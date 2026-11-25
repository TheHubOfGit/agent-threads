package com.agenthud.threads.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.agenthud.threads.data.BridgeApi
import com.agenthud.threads.data.Conversation
import com.agenthud.threads.data.ConversationPage
import com.agenthud.threads.data.PendingReply
import com.agenthud.threads.data.Prefs
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Everything the screens share: the conversation list, each opened conversation's messages, and
 * replies sent from the watch that the conversation hasn't recorded yet. Screens poll through it
 * only while they're on screen.
 */
class AppState(val api: BridgeApi, val prefs: Prefs, private val buzz: () -> Unit = {}) {
    /** Questions/approvals already seen, so each new one buzzes once. Null until the first load. */
    private var seenWaiting: Set<String>? = null

    private fun waitKey(c: Conversation) = "${c.id}|${c.prompt}|${c.options.joinToString("|")}"

    /** Buzzes when a conversation starts waiting on you (only while the app is open). */
    private fun noteWaiting(list: List<Conversation>, partial: Boolean) {
        val now = list.filter { it.isWaiting }.map(::waitKey).toSet()
        val before = seenWaiting
        if (before != null && (now - before).isNotEmpty()) buzz()
        seenWaiting = if (partial) (before.orEmpty() + now) else now
    }

    val conversations = mutableStateOf<List<Conversation>>(emptyList())
    val listLoaded = mutableStateOf(false)
    val listError = mutableStateOf("")
    val filter = mutableStateOf(prefs.providerFilter)
    /** Set when launched from the Wear OS Tile to jump straight into a specific conversation. */
    val openThreadRequest = mutableStateOf<String?>(null)

    /**
     * Sends run here, not in a screen's scope: swiping away mid-send must not drop the result (the
     * request still lands on the Mac), or the screen would look unsent and invite a second send.
     */
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    /** Answers picked on the watch, by conversation, so they survive leaving and reopening it. */
    val picks = mutableStateMapOf<String, PickState>()

    val pages = mutableStateMapOf<String, ConversationPage>()
    val pageLimits = mutableStateMapOf<String, Int>()
    val pending = mutableStateMapOf<String, PendingReply>()

    suspend fun refreshList() {
        api.listConversations(60)
            .onSuccess {
                noteWaiting(it, partial = false)
                conversations.value = it
                listLoaded.value = true
                listError.value = ""
            }
            .onFailure { listError.value = it.message ?: "Can't reach the Mac" }
    }

    fun setFilter(provider: String) {
        filter.value = provider
        prefs.providerFilter = provider
    }

    fun limitFor(id: String): Int = pageLimits[id] ?: PAGE

    suspend fun refreshConversation(id: String): Result<ConversationPage> =
        api.conversation(id, limitFor(id)).onSuccess { page ->
            noteWaiting(listOf(page.conversation), partial = true)
            pages[id] = page
            reconcilePending(id, page)
            // Keep the list row in step with what the conversation now says.
            conversations.value = conversations.value.map { if (it.id == id) page.conversation.copy(preview = it.preview) else it }
        }

    suspend fun loadEarlier(id: String) {
        pageLimits[id] = (limitFor(id) + PAGE).coerceAtMost(300)
        refreshConversation(id)
    }

    /** Sends a reply to this exact conversation; on success it shows as pending until recorded. */
    suspend fun sendReply(id: String, text: String, clientId: String): Result<String> {
        val clean = text.trim()
        val sentAt = System.currentTimeMillis()
        return scope.async {
            api.reply(id, clean, clientId).onSuccess { pending[id] = PendingReply(clean, sentAt) }
        }.await()
    }

    /**
     * Answers [conv]'s open question with [option] and tracks it in [picks]: Sending, then Done (or
     * Failed, shown briefly with the reason, then cleared so the choices are live again). Runs in the
     * app scope, so leaving the conversation mid-send still records the outcome.
     */
    fun pickAnswer(conv: Conversation, option: String, key: String, feedback: (Boolean) -> Unit) {
        val current = picks[conv.id]
        if (current != null && current.phase != PickPhase.Failed) return // one answer per question
        val picked = PickState(key, conv.prompt, conv.status, conv.options, option, PickPhase.Sending)
        picks[conv.id] = picked
        scope.launch {
            answer(conv, option)
                .onSuccess {
                    picks[conv.id] = picked.copy(phase = PickPhase.Done)
                    feedback(true)
                    refreshConversation(conv.id)
                }
                .onFailure {
                    picks[conv.id] = picked.copy(phase = PickPhase.Failed, error = it.message ?: "Couldn't send")
                    feedback(false)
                    kotlinx.coroutines.delay(1800) // the shake, then the reason stays a moment
                    if (picks[conv.id]?.phase == PickPhase.Failed) picks.remove(conv.id)
                }
        }
    }

    /** Answers an open question or approval, the way the desk HUD does. */
    suspend fun answer(conv: Conversation, option: String): Result<String> {
        val action = when {
            conv.kind == "choice" -> "reply"
            option.equals("Proceed", true) || option.equals("Approve", true) || option.equals("Allow", true) -> "proceed"
            option.equals("Deny", true) -> "deny"
            else -> "reply"
        }
        return scope.async { api.action(conv.id, action, if (action == "reply") option else null) }.await()
    }

    private fun reconcilePending(id: String, page: ConversationPage) {
        val p = pending[id] ?: return
        val want = normalize(p.text)
        // Only a message recorded after the send counts: an earlier identical "Continue" doesn't.
        val sentMac = p.sentAtMs + api.clockOffsetMs.value - 15_000L
        val landed = page.messages.takeLast(8).any {
            it.role == "user" && (it.atMs == 0L || it.atMs >= sentMac) &&
                normalize(it.text).let { got -> got == want || got.startsWith(want.take(40)) }
        }
        if (landed || System.currentTimeMillis() - p.sentAtMs > 5 * 60_000L) pending.remove(id)
    }

    private fun normalize(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    companion object {
        const val PAGE = 30
    }
}
