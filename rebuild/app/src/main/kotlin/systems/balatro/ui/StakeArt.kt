package systems.balatro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import systems.balatro.bridge.Telemetry

/**
 * Stake chip art from the reused chips.png atlas (2x: 58×58px cells, 5 cols × 2 rows).
 * Used in hudBlind (0.5u stake sprite inside the chip-target panel) and hudDollarsChips
 * (same 0.5u sprite inside the round-score bar) — both via Spr(bmp, 0.5f, 0.5f).
 *
 * Stake positions (from P_STAKES in game.lua, pos={x,y} in atlas cell coords):
 *   White  stake 1  (0,0)  — always active at run start
 *   Red    stake 2  (1,0)
 *   Green  stake 3  (2,0)
 *   Black  stake 4  (4,0)
 *   Blue   stake 5  (3,0)
 *   Purple stake 6  (0,1)
 *   Orange stake 7  (1,1)
 *   Gold   stake 8  (2,1)
 *
 * The Kotlin rebuild tracks no stake level yet (always stake 1), so we only load White.
 * When stake progression is added, expose a stakeLevel parameter and pick the right cell.
 */
object StakeArt {
    private const val CELL = 58     // 2x texture: 29px * 2 = 58px per cell

    /** Load the White Chip (stake 1, pos={x=0,y=0}) — the always-active stake sprite. */
    fun whiteChip(ctx: Context): ImageBitmap? = try {
        val bmp = ctx.assets.open("textures/chips.png").use { BitmapFactory.decodeStream(it) }
        Bitmap.createBitmap(bmp, 0, 0, CELL, CELL).asImageBitmap()
    } catch (e: Throwable) {
        Telemetry.event("ASSET", "err" to e.toString(), "file" to "chips.png")
        null
    }
}
