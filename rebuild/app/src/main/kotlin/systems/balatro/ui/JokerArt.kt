package systems.balatro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import systems.balatro.bridge.Telemetry

/**
 * Joker art from the REUSED atlases (base Jokers.png + Cryptid atlasone/two/three/exotic).
 * Every atlas uses the same 142x190 cell; the (atlas, col, row) for each ported joker is
 * taken straight from the original definitions. Needed atlases are each decoded ONCE and
 * the cells cropped off the main thread, same cache discipline as the card/board art.
 */
object JokerArt {
    // key -> (atlas asset file, col, row), from each joker's original `atlas` + `pos`.
    private val MAP: Map<String, Triple<String, Int, Int>> = mapOf(
        "j_joker" to Triple("Jokers.png", 0, 0),
        "j_greedy_joker" to Triple("Jokers.png", 6, 1),
        "j_lusty_joker" to Triple("Jokers.png", 7, 1),
        "j_wrathful_joker" to Triple("Jokers.png", 8, 1),
        "j_gluttenous_joker" to Triple("Jokers.png", 9, 1),
        "j_even_steven" to Triple("Jokers.png", 8, 3),
        "j_odd_todd" to Triple("Jokers.png", 9, 3),
        "j_scholar" to Triple("Jokers.png", 0, 4),
        "j_cry_cube" to Triple("atlasone.png", 5, 4),
        "j_cry_triplet_rhythm" to Triple("atlastwo.png", 0, 4),
        "j_cry_lightupthenight" to Triple("atlasone.png", 1, 1),
        "j_cry_weegaming" to Triple("atlastwo.png", 3, 4),
        "j_cry_brokenhome" to Triple("atlasthree.png", 1, 7),
        "j_cry_krustytheclown" to Triple("atlasone.png", 3, 4),
        "j_cry_waluigi" to Triple("atlastwo.png", 0, 3),
        "j_cry_oldblueprint" to Triple("atlasthree.png", 2, 1),
        "j_cry_maximized" to Triple("atlastwo.png", 5, 2),
        "j_cry_primus" to Triple("atlasexotic.png", 0, 4),
    )

    /** Crop the cells for `keys`, decoding each distinct atlas only once. */
    fun cache(ctx: Context, keys: List<String>): Map<String, ImageBitmap> {
        val out = HashMap<String, ImageBitmap>()
        val byAtlas = keys.distinct().mapNotNull { k -> MAP[k]?.let { k to it } }.groupBy { it.second.first }
        for ((file, entries) in byAtlas) {
            val atlas = try {
                ctx.assets.open("textures/$file").use { BitmapFactory.decodeStream(it) }
            } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString(), "file" to file); continue }
            for ((k, t) in entries) {
                val (_, col, row) = t
                out[k] = Bitmap.createBitmap(atlas, col * 142, row * 190, 142, 190).asImageBitmap()
            }
        }
        return out
    }
}
