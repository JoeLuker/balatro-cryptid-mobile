package systems.balatro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import systems.balatro.bridge.Telemetry
import systems.balatro.game.Boss

/**
 * Blind chip art from the reused BlindChips.png atlas (2x: 68×68px cells, 21 animation frames
 * across, each blind at its own row). This is the same atlas Balatro's blind_chips ATLAS uses.
 *
 * Atlas cell layout (from P_BLINDS in game.lua, pos={x,y} in 1x-units → row = y):
 *   row 0  = Small Blind   (bl_small)
 *   row 1  = Big Blind     (bl_big)
 *   row 2  = The Ox        (not in our Boss enum)
 *   row 4  = The Club
 *   row 6  = The Window
 *   row 9  = The Wall
 *   row 13 = The Goad
 *   row 14 = The Water
 *   row 20 = The Needle
 *   row 21 = The Head
 *   row 24 = The Flint
 *
 * We extract FRAME 0 (x=0 → pixel col 0) of each blind — the animated sprite's idle frame.
 * The 2x texture means each cell is 68×68px.
 */
object BlindArt {
    private const val CELL = 68     // 2x texture: 34px * 2 = 68px per cell

    /** Atlas row for each Boss enum value (from P_BLINDS pos.y in game.lua). */
    private val BOSS_ROW: Map<Boss, Int> = mapOf(
        Boss.THE_CLUB    to 4,
        Boss.THE_WINDOW  to 6,
        Boss.THE_WALL    to 9,
        Boss.THE_GOAD    to 13,
        Boss.THE_WATER   to 14,
        Boss.THE_NEEDLE  to 20,
        Boss.THE_HEAD    to 21,
        Boss.THE_FLINT   to 24,
    )

    /**
     * Load the atlas once and crop the frames needed.
     * Returns a map: null -> Small/Big sprites (index 0/1), Boss -> its sprite.
     */
    fun cache(ctx: Context, bosses: Set<Boss?>): Map<Boss?, ImageBitmap> {
        val atlas: Bitmap = try {
            ctx.assets.open("textures/BlindChips.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            Telemetry.event("ASSET", "err" to e.toString(), "file" to "BlindChips.png")
            return emptyMap()
        }
        val out = HashMap<Boss?, ImageBitmap>()
        for (boss in bosses) {
            val row = when (boss) {
                null -> 0    // small blind row; callers request null for Small AND Big separately
                else -> BOSS_ROW[boss] ?: continue
            }
            out[boss] = Bitmap.createBitmap(atlas, 0, row * CELL, CELL, CELL).asImageBitmap()
        }
        return out
    }

    /**
     * Cache all sprites needed for the current run: Small (null, row 0), Big (row 1, a separate
     * crop), and the current boss. Returns (smallBmp, bigBmp, bossBmp?).
     */
    fun cacheRun(ctx: Context, boss: Boss?): Triple<ImageBitmap?, ImageBitmap?, ImageBitmap?> {
        val atlas: Bitmap = try {
            ctx.assets.open("textures/BlindChips.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            Telemetry.event("ASSET", "err" to e.toString(), "file" to "BlindChips.png")
            return Triple(null, null, null)
        }
        fun row(r: Int): ImageBitmap =
            Bitmap.createBitmap(atlas, 0, r * CELL, CELL, CELL).asImageBitmap()

        val small = row(0)
        val big   = row(1)
        val bossBmp = boss?.let { BOSS_ROW[it]?.let { r -> row(r) } }
        return Triple(small, big, bossBmp)
    }
}
