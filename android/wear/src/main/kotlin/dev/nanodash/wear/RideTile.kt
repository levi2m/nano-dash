package dev.nanodash.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * One swipe from the watch face to the remote: a single round "Ride" button that opens the app.
 * shortcut: the tile shows no ride state, because it cannot hold the phone link; the app connects within about two
 * seconds of opening. Upgrade only if riders ask to see the state without opening the app.
 */
class RideTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name).build(),
                ).build(),
            ).build()

        // The same ember accent and black label as the watch screen's Start button (WatchScreen.kt), 8.0:1 contrast.
        val button = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(96f)).setHeight(dp(96f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(open)
                    .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(0xFFFF7A1A.toInt())).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(48f)).build()).build())
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("Open Nano Dash to start or stop a ride").build())
                    .build(),
            )
            .addContent(text("Ride", 20f, 0xFF000000.toInt(), bold = true))
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text("Nano Dash", 14f, 0xFFA1A1A6.toInt(), bold = false))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
                    .addContent(button)
                    .build(),
            ).build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile) }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { it.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build()) }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(size)).setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build(),
        ).build()

    private companion object { const val RESOURCES = "1" }
}
