package com.agenthud.threads.ui

import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.input.RemoteInputIntentHelper

private const val KEY = "text"

/**
 * Opens the watch's own text input (keyboard or handwriting) and hands back what was entered.
 * Returns a function that starts it; [onUnavailable] runs if the watch has no input method.
 */
@Composable
fun rememberTextInput(
    label: String,
    initial: String = "",
    onUnavailable: () -> Unit = {},
    onText: (String) -> Unit
): () -> Unit {
    val callback by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(KEY)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) callback(text)
    }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val input = RemoteInput.Builder(KEY).setLabel(label).build()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        if (initial.isNotBlank()) {
            // Pre-fills the field on keyboards that honor it (Gboard for Wear does).
            RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(KEY, initial) })
        }
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}
