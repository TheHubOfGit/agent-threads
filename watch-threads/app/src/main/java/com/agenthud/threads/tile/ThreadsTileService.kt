package com.agenthud.threads.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.em
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Border
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.agenthud.threads.MainActivity
import com.agenthud.threads.R
import com.agenthud.threads.data.BridgeApi
import com.agenthud.threads.data.Conversation
import com.agenthud.threads.data.Prefs
import com.agenthud.threads.ui.shortAgo
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Minimal Wear OS Tile (Option 2: Bezel-Hugging Pills) showing the latest 3 conversations.
 * Tap any pill to jump straight into that conversation, or tap THREADS to open the full list.
 */
class ThreadsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                val prefs = Prefs(applicationContext)
                val api = BridgeApi(applicationContext, prefs)
                val convs = withTimeoutOrNull(6_000L) {
                    api.listConversations(3).getOrNull()
                }.orEmpty()
                val nowMs = System.currentTimeMillis() + api.clockOffsetMs.value
                val screenW = requestParams.deviceConfiguration.screenWidthDp.takeIf { it > 0 }?.toFloat() ?: 272f
                val tile = TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    .setFreshnessIntervalMillis(120_000L)
                    .setTileTimeline(
                        Timeline.Builder()
                            .addTimelineEntry(
                                TimelineEntry.Builder()
                                    .setLayout(Layout.Builder().setRoot(buildRoot(convs, nowMs, screenW)).build())
                                    .build()
                            )
                            .build()
                    )
                    .build()
                completer.set(tile)
            }
            "ThreadsTileService.onTileRequest"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            val res = ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(ICON_CLAUDE, drawableRes(R.drawable.logo_claude))
                .addIdToImageMapping(ICON_CODEX, drawableRes(R.drawable.logo_codex_color))
                .addIdToImageMapping(ICON_ANTIGRAVITY, drawableRes(R.drawable.logo_antigravity))
                .addIdToImageMapping(ICON_T3, drawableRes(R.drawable.logo_t3code))
                .build()
            completer.set(res)
            "ThreadsTileService.onTileResourcesRequest"
        }

    private fun drawableRes(id: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(id).build()
        )
        .build()

    private fun buildRoot(convs: List<Conversation>, nowMs: Long, screenW: Float): LayoutElementBuilders.LayoutElement {
        val col = Column.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // Quiet "THREADS" header (tapping opens the full list)
        col.addContent(
            Text.Builder()
                .setText("THREADS")
                .setFontStyle(
                    FontStyle.Builder()
                        .setSize(sp(11f))
                        .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                        .setColor(argb(0xFF6B7280.toInt()))
                        .setLetterSpacing(em(0.08f))
                        .build()
                )
                .setModifiers(
                    Modifiers.Builder()
                        .setClickable(openAppClickable(null))
                        .setPadding(Padding.Builder().setBottom(dp(12f)).build())
                        .build()
                )
                .build()
        )

        if (convs.isEmpty()) {
            col.addContent(
                Box.Builder()
                    .setWidth(dp(screenW * 0.80f))
                    .setHeight(wrap())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setModifiers(
                        Modifiers.Builder()
                            .setClickable(openAppClickable(null))
                            .setBackground(
                                Background.Builder()
                                    .setColor(argb(0xFF14171C.toInt()))
                                    .setCorner(Corner.Builder().setRadius(dp(99f)).build())
                                    .build()
                            )
                            .setPadding(Padding.Builder().setStart(dp(14f)).setEnd(dp(14f)).setTop(dp(13f)).setBottom(dp(13f)).build())
                            .build()
                    )
                    .addContent(
                        Text.Builder()
                            .setText("Open Agent Threads")
                            .setFontStyle(FontStyle.Builder().setSize(sp(14f)).setColor(argb(0xFFB8BBBF.toInt())).build())
                            .build()
                    )
                    .build()
            )
        } else {
            val top3 = convs.take(3)
            val wideW = screenW * 0.92f
            val taperedW = screenW * 0.80f
            top3.forEachIndexed { idx, c ->
                if (idx > 0) col.addContent(Spacer.Builder().setHeight(dp(8f)).build())
                // Top and bottom pills taper slightly to hug the round watch bezel.
                val pillWidth = if (top3.size == 3 && idx == 1) wideW else taperedW
                col.addContent(buildPill(c, nowMs, pillWidth))
            }
        }

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(Background.Builder().setColor(argb(0xFF000000.toInt())).build())
                    .build()
            )
            .addContent(col.build())
            .build()
    }

    private fun buildPill(c: Conversation, nowMs: Long, widthDp: Float): LayoutElementBuilders.LayoutElement {
        val row = Row.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

        val iconId = when (c.provider.lowercase()) {
            "claude" -> ICON_CLAUDE
            "codex" -> ICON_CODEX
            "antigravity" -> ICON_ANTIGRAVITY
            "t3code" -> ICON_T3
            else -> null
        }
        if (iconId != null) {
            row.addContent(
                Image.Builder()
                    .setResourceId(iconId)
                    .setWidth(dp(18f))
                    .setHeight(dp(18f))
                    .build()
            )
            row.addContent(Spacer.Builder().setWidth(dp(8f)).build())
        }

        // Title fills remaining width inside the pill
        row.addContent(
            Box.Builder()
                .setWidth(expand())
                .setHeight(wrap())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .addContent(
                    Text.Builder()
                        .setText(c.title)
                        .setMaxLines(1)
                        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                        .setFontStyle(
                            FontStyle.Builder()
                                .setSize(sp(15f))
                                .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                                .setColor(argb(0xFFE8EAED.toInt()))
                                .build()
                        )
                        .build()
                )
                .build()
        )

        row.addContent(Spacer.Builder().setWidth(dp(8f)).build())

        // Right-aligned status dot or short timestamp
        when {
            c.isWaiting -> row.addContent(statusDot(0xFFF2B441.toInt()))
            c.isRunning -> row.addContent(statusDot(providerAccentArgb(c.provider)))
            c.isError -> row.addContent(statusDot(0xFFF28B82.toInt()))
            else -> row.addContent(
                Text.Builder()
                    .setText(shortAgo(c.updatedAtMs, nowMs))
                    .setMaxLines(1)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(0xFF6E747C.toInt()))
                            .build()
                    )
                    .build()
            )
        }

        val mods = Modifiers.Builder()
            .setClickable(openAppClickable(c.id))
            .setBackground(
                Background.Builder()
                    .setColor(argb(0xFF14171C.toInt()))
                    .setCorner(Corner.Builder().setRadius(dp(99f)).build())
                    .build()
            )
            .setPadding(
                Padding.Builder()
                    .setStart(dp(14f))
                    .setEnd(dp(14f))
                    .setTop(dp(13f))
                    .setBottom(dp(13f))
                    .build()
            )
        if (c.isWaiting) {
            mods.setBorder(
                Border.Builder()
                    .setWidth(dp(1.5f))
                    .setColor(argb(0xB3F2B441.toInt()))
                    .build()
            )
        }

        return Box.Builder()
            .setWidth(dp(widthDp))
            .setHeight(wrap())
            .setModifiers(mods.build())
            .addContent(row.build())
            .build()
    }

    private fun statusDot(colorArgb: Int) = Box.Builder()
        .setWidth(dp(8f))
        .setHeight(dp(8f))
        .setModifiers(
            Modifiers.Builder()
                .setBackground(
                    Background.Builder()
                        .setColor(argb(colorArgb))
                        .setCorner(Corner.Builder().setRadius(dp(4f)).build())
                        .build()
                )
                .build()
        )
        .build()

    private fun providerAccentArgb(provider: String): Int = when (provider.lowercase()) {
        "claude" -> 0xFFD97757.toInt()
        "codex" -> 0xFF7A84FF.toInt()
        "antigravity" -> 0xFF3D8BFF.toInt()
        "t3code" -> 0xFFD5D8DD.toInt()
        else -> 0xFF8AB4F8.toInt()
    }

    private fun openAppClickable(threadId: String?): Clickable {
        val act = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(MainActivity::class.java.name)
        if (!threadId.isNullOrBlank()) {
            act.addKeyToExtraMapping(
                "open_thread_id",
                ActionBuilders.AndroidStringExtra.Builder().setValue(threadId).build()
            )
        }
        return Clickable.Builder()
            .setId(threadId ?: "open_list")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(act.build()).build())
            .build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val ICON_CLAUDE = "icon_claude"
        const val ICON_CODEX = "icon_codex"
        const val ICON_ANTIGRAVITY = "icon_antigravity"
        const val ICON_T3 = "icon_t3"
    }
}
