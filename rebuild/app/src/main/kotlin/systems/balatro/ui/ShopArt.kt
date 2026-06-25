package systems.balatro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import systems.balatro.bridge.Telemetry
import systems.balatro.game.Planet

/**
 * Sprite art for the non-joker shop cards: planets/tarots/spectrals (all in Tarots.png — vanilla
 * aliases the Planet & Spectral atlases to Tarot, game.lua:1118-1119), vouchers (Vouchers.png), and
 * booster packs (boosters.png). Every atlas uses the same 142x190 cell (px=71,py=95 at 2x scaling,
 * game.lua:1018-1020); the (col,row) for each center is taken straight from its game.lua `pos`.
 * Same decode-once / crop-off-main-thread discipline as [JokerArt].
 */
object ShopArt {
    private const val CW = 142
    private const val CH = 190

    // Planet centers (game.lua c_* set="Planet"), keyed by the rebuild's Planet enum.
    private val PLANET_POS: Map<Planet, Pair<Int, Int>> = mapOf(
        Planet.MERCURY to (0 to 3), Planet.VENUS to (1 to 3), Planet.EARTH to (2 to 3),
        Planet.MARS to (3 to 3), Planet.JUPITER to (4 to 3), Planet.SATURN to (5 to 3),
        Planet.URANUS to (6 to 3), Planet.NEPTUNE to (7 to 3), Planet.PLUTO to (8 to 3),
        Planet.PLANET_X to (9 to 2), Planet.CERES to (8 to 2), Planet.ERIS to (3 to 2),
    )
    // Tarot centers (game.lua c_* set="Tarot"), keyed by display name (the rebuild's TAROTS names).
    private val TAROT_POS: Map<String, Pair<Int, Int>> = mapOf(
        "The Empress" to (3 to 0), "The Hierophant" to (5 to 0), "The Lovers" to (6 to 0),
        "The Chariot" to (7 to 0), "Justice" to (8 to 0), "The Devil" to (5 to 1),
        "The Tower" to (6 to 1), "The Star" to (7 to 1), "The Moon" to (8 to 1),
        "The Sun" to (9 to 1), "The World" to (1 to 2), "Strength" to (1 to 1),
        "The Hanged Man" to (2 to 1), "The High Priestess" to (2 to 0), "The Emperor" to (4 to 0),
        "The Hermit" to (9 to 0), "Temperance" to (4 to 1),
    )
    // Spectral centers (game.lua c_* set="Spectral"), keyed by the rebuild's Spectral enum.
    private val SPECTRAL_POS: Map<Spectral, Pair<Int, Int>> = mapOf(
        Spectral.BLACK_HOLE to (9 to 3), Spectral.IMMOLATE to (9 to 4), Spectral.ECTOPLASM to (8 to 4),
        Spectral.HEX to (2 to 5), Spectral.TALISMAN to (3 to 4), Spectral.DEJA_VU to (1 to 5),
        Spectral.WRAITH to (5 to 4),
        Spectral.SIGIL to (6 to 4), Spectral.OUIJA to (7 to 4), Spectral.FAMILIAR to (0 to 4),
        Spectral.GRIM to (1 to 4), Spectral.INCANTATION to (2 to 4),
        Spectral.TRANCE to (3 to 5), Spectral.MEDIUM to (4 to 5), Spectral.AURA to (4 to 4),
    )
    // Voucher centers (game.lua v_* set="Voucher"), keyed by the rebuild's VOUCHERS keys.
    private val VOUCHER_POS: Map<String, Pair<Int, Int>> = mapOf(
        "v_overstock_norm" to (0 to 0), "v_clearance_sale" to (3 to 0), "v_reroll_surplus" to (0 to 2),
        "v_grabber" to (5 to 0), "v_wasteful" to (6 to 0), "v_seed_money" to (1 to 2),
    )
    // Booster centers (game.lua p_* set="Booster", first art variant), keyed by the rebuild's BOOSTERS keys.
    private val BOOSTER_POS: Map<String, Pair<Int, Int>> = mapOf(
        "p_arcana_normal" to (0 to 0), "p_arcana_jumbo" to (0 to 2), "p_arcana_mega" to (2 to 2),
        "p_celestial_normal" to (0 to 1), "p_celestial_jumbo" to (0 to 3), "p_celestial_mega" to (2 to 3),
        "p_buffoon_normal" to (0 to 8), "p_buffoon_jumbo" to (2 to 8), "p_buffoon_mega" to (3 to 8),
        "p_standard_normal" to (0 to 6), "p_standard_jumbo" to (0 to 7), "p_standard_mega" to (2 to 7),
        "p_spectral_normal" to (0 to 4), "p_spectral_jumbo" to (2 to 4), "p_spectral_mega" to (3 to 4),
    )

    /** The cropped cells, by lookup key. Empty maps if an atlas failed to decode.
     *  `internal` because it surfaces the `internal` Spectral enum (same module as the UI). */
    internal class Cells(
        val planets: Map<Planet, ImageBitmap>,
        val tarots: Map<String, ImageBitmap>,
        val spectrals: Map<Spectral, ImageBitmap>,
        val vouchers: Map<String, ImageBitmap>,
        val boosters: Map<String, ImageBitmap>,
    ) {
        companion object { val EMPTY = Cells(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()) }
    }

    private fun decode(ctx: Context, file: String): Bitmap? = try {
        ctx.assets.open("textures/$file").use { BitmapFactory.decodeStream(it) }
    } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString(), "file" to file); null }

    /** Crop one cell from any bundled atlas — for one-off UI glyphs (e.g. the game-over chip icon). */
    fun cell(ctx: Context, file: String, col: Int, row: Int, cw: Int, ch: Int): ImageBitmap? =
        decode(ctx, file)?.let { Bitmap.createBitmap(it, col * cw, row * ch, cw, ch).asImageBitmap() }

    // Deck-back sprites (game.lua b_* set="Back"), on Enhancers.png (142×190 cells), keyed by variant.
    private val DECK_BACK_POS: Map<DeckVariant, Pair<Int, Int>> = mapOf(
        DeckVariant.RED to (0 to 0), DeckVariant.BLUE to (0 to 2), DeckVariant.YELLOW to (1 to 2),
        DeckVariant.BLACK to (3 to 2), DeckVariant.PAINTED to (4 to 3), DeckVariant.ABANDONED to (3 to 3),
        DeckVariant.CHECKERED to (1 to 3), DeckVariant.ERRATIC to (2 to 3),
    )
    /** Crop every deck-back from Enhancers.png (one decode). Call off the main thread. */
    internal fun deckBacks(ctx: Context): Map<DeckVariant, ImageBitmap> = crop(decode(ctx, "Enhancers.png"), DECK_BACK_POS)

    private fun <K> crop(atlas: Bitmap?, pos: Map<K, Pair<Int, Int>>): Map<K, ImageBitmap> {
        atlas ?: return emptyMap()
        return pos.mapValues { (_, p) -> Bitmap.createBitmap(atlas, p.first * CW, p.second * CH, CW, CH).asImageBitmap() }
    }

    /** Decode each atlas once and crop every needed cell. Call off the main thread. */
    internal fun cache(ctx: Context): Cells {
        val tarots = decode(ctx, "Tarots.png")   // tarots + planets + spectrals share this atlas
        return Cells(
            planets = crop(tarots, PLANET_POS),
            tarots = crop(tarots, TAROT_POS),
            spectrals = crop(tarots, SPECTRAL_POS),
            vouchers = crop(decode(ctx, "Vouchers.png"), VOUCHER_POS),
            boosters = crop(decode(ctx, "boosters.png"), BOOSTER_POS),
        )
    }
}
