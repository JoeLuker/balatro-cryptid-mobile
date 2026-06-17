package systems.balatro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import systems.balatro.bridge.Telemetry
import systems.balatro.game.HandType
import systems.balatro.game.PlayingCard
import systems.balatro.game.Suit

/**
 * Card art from the REUSED 8BitDeck atlas (1846x760, 13 ranks x 4 suits, cell 142x190).
 * Mapping is authoritative from Balatro's P_CARDS: col = rank-2, row = suit (H,C,D,S).
 * Cells are decoded+cropped ONCE off the main thread, cached and reused.
 */
object CardArt {
    private fun cell(c: PlayingCard): Pair<Int, Int> {
        val col = c.rank - 2                                  // 2->0 ... T->8, J->9, Q->10, K->11, A->12
        val row = when (c.suit) { Suit.H -> 0; Suit.C -> 1; Suit.D -> 2; Suit.S -> 3 }
        return col to row
    }

    fun cache(ctx: Context, cards: List<PlayingCard>): Map<PlayingCard, ImageBitmap> {
        val atlas = try {
            ctx.assets.open("textures/8BitDeck.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString()); return emptyMap() }
        val out = HashMap<PlayingCard, ImageBitmap>()
        for (c in cards.toSet()) {
            val (col, row) = cell(c)
            out[c] = Bitmap.createBitmap(atlas, col * 142, row * 190, 142, 190).asImageBitmap()
        }
        return out
    }

    /**
     * The plain white card stock (Balatro's c_base center, Enhancers.png cell {x=1,y=0}). 8BitDeck
     * is a TRANSPARENT rank/suit overlay — without a base under it cards render as floating pips on
     * the felt. Every card draws this base first, then its 8BitDeck overlay on top. Cached once.
     */
    fun base(ctx: Context): ImageBitmap? {
        val atlas = try {
            ctx.assets.open("textures/Enhancers.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString()); return null }
        return Bitmap.createBitmap(atlas, 1 * 142, 0 * 190, 142, 190).asImageBitmap()
    }

    /** The Red Deck card back (Balatro's b_red, centers=Enhancers.png cell {x=0,y=0}) — the deck stack. */
    fun back(ctx: Context): ImageBitmap? {
        val atlas = try {
            ctx.assets.open("textures/Enhancers.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString()); return null }
        return Bitmap.createBitmap(atlas, 0 * 142, 0 * 190, 142, 190).asImageBitmap()
    }
}

/** "FOUR_OF_A_KIND" -> "Four Of A Kind". */
fun handName(h: HandType): String =
    h.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
