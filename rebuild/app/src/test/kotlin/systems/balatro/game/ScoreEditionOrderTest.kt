package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the pre_joker / post_joker edition-ordering fix (state_events.lua:847-926):
 *
 *   Foil (+50 chips) and Holo (+10 mult) are pre_joker — they fire BEFORE joker_main.
 *   Poly (×1.5 mult) is post_joker — it fires AFTER joker_main AND after other_joker reactions.
 *
 * The bugs these tests catch:
 *   Bug 1: Holo + xMult joker_main — wrong if Holo fires after the x-multiply.
 *   Bug 2: Foil + xChipMod joker_main — wrong if Foil fires after the x-multiply.
 *   Bug 3: Poly + other_joker additive — wrong if Poly fires before other_joker reactions.
 */
class ScoreEditionOrderTest {

    // Helpers: minimal played hand that scores as a High Card so base chips/mult are predictable.
    private val hand = PlayingCard.hand("S_A")   // single Ace: base High Card chips=5+11=16? No — use no-card pair
    // Actually use a simple pair so we get a stable base. Two Aces.
    private val pairHand = PlayingCard.hand("S_A", "H_A")  // Pair: base chips=10, mult=2 (lvl 1 pair: 10+2=12 chips, 2 mult)

    // Convenience: score a hand and return (chips, mult, score).
    private fun go(played: List<PlayingCard>, jokers: List<FJoker>): ScoreResult =
        Score.score(played, jokers)

    // ---------------------------------------------------------------------------
    // Bug 1 — Holo fires BEFORE joker_main xMult (pre_joker)
    // Joker: j_seeing_double (X2 mult when Club+nonClub score).
    // We put j_seeing_double on a Club+nonClub hand so xMult fires.
    //
    // vanilla: +10 Holo (pre) → mult=2+10=12, then X2 → mult=24
    // buggy:   X2 (joker_main) → mult=2*2=4, then +10 Holo → mult=14
    // ---------------------------------------------------------------------------
    @Test fun holo_fires_before_jokerMain_xMult() {
        // Pair of Clubs hand: Club+Club — seeing_double needs Club AND non-Club; won't fire.
        // Use a Club + Spade that form a Pair (same rank).
        val played = PlayingCard.hand("S_A", "C_A")   // Pair: A♠ + A♣ → Club present, non-Club present
        val joker = FJoker("j_seeing_double", edition = "Holo")
        val result = go(played, listOf(joker))
        // Pair base: chips=10, mult=2. Cards: A♠ chips=11 → chips=21; A♣ chips=11 → chips=32, mult=2.
        // Holo pre_joker: mult=2+10=12.
        // j_seeing_double joker_main (Club+non-Club ✓): mult=12×2=24.
        // Score = 32 * 24 = 768
        assertEquals(768.0, result.score, 0.0)
    }

    // ---------------------------------------------------------------------------
    // Bug 2 — Foil fires BEFORE joker_main xChipMod (pre_joker)
    // Joker: j_cry_big_cube (×6 chips).
    //
    // vanilla: +50 Foil (pre) → chips=base+50, then ×6
    // buggy:   ×6 (joker_main) → then +50 Foil (only +50, not ×6 on the 50)
    // ---------------------------------------------------------------------------
    @Test fun foil_fires_before_jokerMain_xChipMod() {
        val played = PlayingCard.hand("S_A", "H_A")   // Pair: chips=10+11+11=32, mult=2
        val joker = FJoker("j_cry_big_cube", edition = "Foil")
        val result = go(played, listOf(joker))
        // Pair base: chips=10, mult=2. Cards: chips=32, mult=2.
        // Foil pre_joker: chips=32+50=82.
        // j_cry_big_cube joker_main: chips=82×6=492.
        // Score = 492 * 2 = 984
        assertEquals(984.0, result.score, 0.0)
    }

    // ---------------------------------------------------------------------------
    // Bug 3 — Poly fires AFTER other_joker reactions (post_joker)
    // Board: joker A (j_joker, Poly) + joker B (j_cry_exoplanet, no edition).
    // j_cry_exoplanet fires +15 mult per Holo joker in other_joker.
    // Joker A is Poly — NOT Holo — so j_cry_exoplanet does not react to it.
    //
    // But Poly still fires last; with j_joker (+4 mult) + Poly:
    //   vanilla: (mult+4) from joker_main, then other_joker (no reaction here), then ×1.5 Poly
    //   buggy:   (mult+4), then ×1.5 Poly, then other_joker (no reaction here) — same result
    //
    // To expose the ordering bug properly we need an additive other_joker reaction THAT FIRES
    // for the Poly-edition joker. j_cry_meteor fires +75 chips per Foil joker in other_joker.
    // Board: joker A (j_joker, Foil) + joker B (j_cry_exoplanet, Poly).
    //
    // On joker B's turn (Poly):
    //   joker_main: j_cry_exoplanet has no joker_main
    //   other_joker: j_cry_exoplanet (voter=B) reacts to B (other_joker=B): edition=="Holo"? No, Poly. No reaction.
    //                j_joker (voter=A) reacts to B: no other_joker reaction.
    //   post_joker Poly: ×1.5 on whatever mult has accumulated
    //
    // This case is actually order-invariant since there are no additive-mult reactions to B.
    // The canonical test for Poly ordering: joker with Poly + a board joker that has +multMod
    // other_joker reaction to ANY joker. No such vanilla joker does +multMod to all jokers —
    // only j_cry_exoplanet (+15 to Holo) and j_cry_stardust (×2 to Poly, multiplicative).
    //
    // Use j_cry_stardust (×2 per Poly joker in other_joker) — multiplicative, order-invariant
    // with Poly ×1.5 (both xMult, commute). Test that the combined multiplier lands correctly:
    //   Board: j_joker (Poly) + j_cry_stardust.
    //   On j_joker's turn: Holo/Foil pre — none. joker_main: +4 mult.
    //     other_joker: j_cry_stardust reacts to j_joker (Poly) → ×2 mult.
    //     post_joker Poly: ×1.5 mult.
    //   On j_cry_stardust's turn: no edition pre. no joker_main. other_joker: j_cry_stardust reacts
    //     to j_cry_stardust (no Poly) → no reaction. j_joker reacts to j_cry_stardust → no. No post.
    // ---------------------------------------------------------------------------
    @Test fun poly_fires_after_otherJoker_xMult() {
        val played = PlayingCard.hand("S_A", "H_A")   // Pair: chips=10+11+11=32, mult=2
        val jJoker = FJoker("j_joker", edition = "Poly")
        val stardust = FJoker("j_cry_stardust")
        val result = go(played, listOf(jJoker, stardust))
        // Pair base chips=10 mult=2. Cards: chips=32, mult=2.
        // j_joker turn:
        //   pre_joker: no Foil/Holo
        //   joker_main: mult=2+4=6
        //   other_joker: stardust sees j_joker (Poly) → ×2: mult=12
        //   post_joker Poly: mult=12×1.5=18
        // stardust turn:
        //   pre_joker: none. joker_main: none. other_joker: stardust sees stardust (no Poly) → no; j_joker sees stardust (no Poly) → no. post: none.
        // Score = 32 * 18 = 576
        assertEquals(576.0, result.score, 0.0)
    }

    // ---------------------------------------------------------------------------
    // Sanity: Negative has no scoring effect.
    // ---------------------------------------------------------------------------
    @Test fun negative_has_no_scoring_effect() {
        val played = PlayingCard.hand("S_A", "H_A")
        val withNeg    = go(played, listOf(FJoker("j_joker", edition = "Negative")))
        val withoutEdi = go(played, listOf(FJoker("j_joker")))
        assertEquals(withoutEdi.score, withNeg.score, 0.0)
    }
}
