package com.agenthud.threads.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
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
 * Material 3 Expressive Wear OS Tile showing the latest 3 conversations in chunky,
 * provider-tinted tonal containers with an edge-hugging bottom "More" button.
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

    private data class M3Colors(val container: Int, val iconBg: Int, val title: Int, val sub: Int, val dot: Int)

    private fun colorsFor(c: Conversation): M3Colors = when {
        c.isWaiting -> M3Colors(
            container = 0xFF4A320A.toInt(),
            iconBg = 0xFF6B4810.toInt(),
            title = 0xFFFFF0C2.toInt(),
            sub = 0xFFF2B441.toInt(),
            dot = 0xFFF2B441.toInt()
        )
        c.provider.equals("claude", true) -> M3Colors(
            container = 0xFF3E2218.toInt(),
            iconBg = 0xFF5A3324.toInt(),
            title = 0xFFFFE5DB.toInt(),
            sub = 0xFFE3A994.toInt(),
            dot = 0xFFD97757.toInt()
        )
        c.provider.equals("codex", true) -> M3Colors(
            container = 0xFF232752.toInt(),
            iconBg = 0xFF353B75.toInt(),
            title = 0xFFE3E6FF.toInt(),
            sub = 0xFFAAB2F5.toInt(),
            dot = 0xFF7A84FF.toInt()
        )
        c.provider.equals("antigravity", true) -> M3Colors(
            container = 0xFF0E2D5C.toInt(),
            iconBg = 0xFF1A4585.toInt(),
            title = 0xFFD9EBFF.toInt(),
            sub = 0xFF8AB4F8.toInt(),
            dot = 0xFF3D8BFF.toInt()
        )
        else -> M3Colors(
            container = 0xFF242933.toInt(),
            iconBg = 0xFF363D4A.toInt(),
            title = 0xFFF0F2F5.toInt(),
            sub = 0xFFAAB0BA.toInt(),
            dot = 0xFFD5D8DD.toInt()
        )
    }

    private fun buildRoot(convs: List<Conversation>, nowMs: Long, screenW: Float): LayoutElementBuilders.LayoutElement {
        val col = Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        // Material 3 Expressive top title
        col.addContent(
            Text.Builder()
                .setText("Threads")
                .setFontStyle(
                    FontStyle.Builder()
                        .setSize(sp(16f))
                        .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                        .setColor(argb(0xFFFFFFFF.toInt()))
                        .build()
                )
                .setModifiers(
                    Modifiers.Builder()
                        .setClickable(openAppClickable(null))
                        .setPadding(Padding.Builder().setTop(dp(14f)).setBottom(dp(8f)).build())
                        .build()
                )
                .build()
        )

        // Center stack of up to 3 chunky M3 tonal cards
        val stack = Column.Builder()
            .setWidth(expand())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        if (convs.isEmpty()) {
            stack.addContent(
                Box.Builder()
                    .setWidth(dp(screenW * 0.84f))
                    .setHeight(dp(56f))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .setModifiers(
                        Modifiers.Builder()
                            .setClickable(openAppClickable(null))
                            .setBackground(
                                Background.Builder()
                                    .setColor(argb(0xFF242933.toInt()))
                                    .setCorner(Corner.Builder().setRadius(dp(24f)).build())
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        Text.Builder()
                            .setText("Open Agent Threads")
                            .setFontStyle(FontStyle.Builder().setSize(sp(14f)).setColor(argb(0xFFE8EAED.toInt())).build())
                            .build()
                    )
                    .build()
            )
        } else {
            val top3 = convs.take(3)
            val wideW = screenW * 0.90f
            val taperedW = screenW * 0.82f
            top3.forEachIndexed { idx, c ->
                if (idx > 0) stack.addContent(Spacer.Builder().setHeight(dp(6f)).build())
                val cardW = if (top3.size == 3 && idx == 1) wideW else taperedW
                stack.addContent(buildCard(c, nowMs, cardW))
            }
        }

        col.addContent(
            Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(stack.build())
                .build()
        )

        // Edge-hugging bottom M3 "More" pill
        col.addContent(
            Box.Builder()
                .setWidth(dp(screenW * 0.46f))
                .setHeight(dp(34f))
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(
                    Modifiers.Builder()
                        .setClickable(openAppClickable(null))
                        .setBackground(
                            Background.Builder()
                                .setColor(argb(0xFF343840.toInt()))
                                .setCorner(Corner.Builder().setRadius(dp(99f)).build())
                                .build()
                        )
                        .build()
                )
                .addContent(
                    Text.Builder()
                        .setText("More")
                        .setFontStyle(
                            FontStyle.Builder()
                                .setSize(sp(13f))
                                .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                                .setColor(argb(0xFFE8EAED.toInt()))
                                .build()
                        )
                        .build()
                )
                .build()
        )
        col.addContent(Spacer.Builder().setHeight(dp(6f)).build())

        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(Background.Builder().setColor(argb(0xFF000000.toInt())).build())
                    .build()
            )
            .addContent(col.build())
            .build()
    }

    private fun buildCard(c: Conversation, nowMs: Long, widthDp: Float): LayoutElementBuilders.LayoutElement {
        val colors = colorsFor(c)
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
                Box.Builder()
                    .setWidth(dp(26f))
                    .setHeight(dp(26f))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .setModifiers(
                        Modifiers.Builder()
                            .setBackground(
                                Background.Builder()
                                    .setColor(argb(colors.iconBg))
                                    .setCorner(Corner.Builder().setRadius(dp(13f)).build())
                                    .build()
                            )
                            .build()
                    )
                    .addContent(
                        Image.Builder()
                            .setResourceId(iconId)
                            .setWidth(dp(15f))
                            .setHeight(dp(15f))
                            .build()
                    )
                    .build()
            )
            row.addContent(Spacer.Builder().setWidth(dp(8f)).build())
        }

        // Title + quiet project/status subtitle
        val textCol = Column.Builder()
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
                            .setSize(sp(13.5f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(argb(colors.title))
                            .build()
                    )
                    .build()
            )
        val subText = when {
            c.isWaiting -> if (c.project.isNotBlank()) "${c.project} · Needs you" else "Needs you"
            c.isRunning -> if (c.project.isNotBlank()) "${c.project} · Working" else "Working"
            c.project.isNotBlank() -> "${c.project} · ${shortAgo(c.updatedAtMs, nowMs)}"
            else -> shortAgo(c.updatedAtMs, nowMs)
        }
        if (subText.isNotBlank()) {
            textCol.addContent(
                Text.Builder()
                    .setText(subText)
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(10.5f))
                            .setColor(argb(colors.sub))
                            .build()
                    )
                    .build()
            )
        }
        row.addContent(textCol.build())

        if (c.isWaiting || c.isRunning || c.isError) {
            row.addContent(Spacer.Builder().setWidth(dp(6f)).build())
            row.addContent(statusDot(if (c.isError) 0xFFF28B82.toInt() else colors.dot))
        }

        val mods = Modifiers.Builder()
            .setClickable(openAppClickable(c.id))
            .setBackground(
                Background.Builder()
                    .setColor(argb(colors.container))
                    .setCorner(Corner.Builder().setRadius(dp(22f)).build())
                    .build()
            )
            .setPadding(
                Padding.Builder()
                    .setStart(dp(9f))
                    .setEnd(dp(12f))
                    .setTop(dp(8f))
                    .setBottom(dp(8f))
                    .build()
            )
        if (c.isWaiting) {
            mods.setBorder(
                Border.Builder()
                    .setWidth(dp(1.5f))
                    .setColor(argb(0xCCF2B441.toInt()))
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
