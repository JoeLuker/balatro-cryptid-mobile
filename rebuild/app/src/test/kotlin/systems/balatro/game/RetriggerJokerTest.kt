package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Oracle tests for every retrigger joker added in the retrigger-parity fix:
 *   - j_selzer        (Seltzer) — +1 rep per scored played card, unconditional
 *   - j_hanging_chad  (Hanging Chad) — +2 reps for scoring_hand[0] only
 *   - j_sock_and_buskin — +1 rep per scored face card
 *   - j_hack          — +1 rep per scored 2/3/4/5
 *   - j_dusk          — +1 rep per scored played card when handsLeft==0
 *   - j_mime          — +1 held-card rep when the card produced any effect
 *
 * All expected scores are hand-calculated from card.lua semantics and verified
 * against the baseline (same hand without the joker, from the existing oracle).
 *
 * Baseline references (from Oracle.kt / vanilla-verified):
 *   Pair of aces (no jokers): chips=32, mult=2, score=64
 *   Pair of aces + 1 steel held (no Mime): chips=32, mult=3, score=96
 */
class RetriggerJokerTest {

    private fun en(key: String, e: Enhancement) = PlayingCard.parse(key).copy(enhancement = e)
    private fun seal(key: String, s: Seal) = PlayingCard.parse(key).copy(seal = s)

    // Pair of aces base: Pair chips=10, mult=2. A♠ chips=11, A♥ chips=11 → chips=32, mult=2. score=64.
    private val pairAces = PlayingCard.hand("S_A", "H_A")
    // Pair of jacks base: J♠ chips=10, J♥ chips=10 → chips=30, mult=2. score=60.
    private val pairJacks = PlayingCard.hand("S_J", "H_J")
    // Pair of 2s base: chips=10+2+2=14, mult=2. score=28.
    private val pair2s = PlayingCard.hand("S_2", "H_2")

    // -------------------------------------------------------------------------
    // j_selzer: +1 rep per scored card. Pair of aces: each ace fires twice.
    //   chips=10 + 11*2 + 11*2 = 54, mult=2, score=108.
    // -------------------------------------------------------------------------
    @Test fun selzer_retriggers_all_played_cards_once() {
        val result = Score.score(pairAces, listOf(FJoker("j_selzer")))
        assertEquals(108.0, result.score, 0.0)
    }

    // Seltzer doesn't fire when it isn't on the board (sanity).
    @Test fun selzer_absent_gives_baseline() {
        val result = Score.score(pairAces, emptyList())
        assertEquals(64.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // j_hanging_chad: +2 reps for scoring_hand[0] only (other cards unchanged).
    //   Pair of aces: A♠ is first → fires 3×, A♥ fires 1×.
    //   chips=10 + 11*3 + 11*1 = 54, mult=2, score=108.
    // -------------------------------------------------------------------------
    @Test fun hanging_chad_retriggers_first_scored_card_twice_extra() {
        val result = Score.score(pairAces, listOf(FJoker("j_hanging_chad")))
        assertEquals(108.0, result.score, 0.0)
    }

    // With Seltzer + Hanging Chad: A♠ fires 3+1=4×, A♥ fires 1+1=2×.
    //   chips=10 + 11*4 + 11*2 = 76, mult=2, score=152.
    @Test fun hanging_chad_and_selzer_stack_additively() {
        val result = Score.score(pairAces, listOf(FJoker("j_hanging_chad"), FJoker("j_selzer")))
        assertEquals(152.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // j_sock_and_buskin: +1 rep per scored face card.
    //   Pair of jacks (both face): each fires 2×.
    //   chips=10 + 10*2 + 10*2 = 50, mult=2, score=100.
    // -------------------------------------------------------------------------
    @Test fun sock_and_buskin_retriggers_face_cards() {
        val result = Score.score(pairJacks, listOf(FJoker("j_sock_and_buskin")))
        assertEquals(100.0, result.score, 0.0)
    }

    // Sock and Buskin + j_scary_face (+30 chips per face). Both individual effects retrigger.
    //   J♠ fires 2×: chips per fire = 10+30=40 → +80. J♥ fires 2×: +80.
    //   chips=10+80+80=170, mult=2, score=340.
    @Test fun sock_and_buskin_retriggers_individual_joker_effects_too() {
        val result = Score.score(pairJacks, listOf(FJoker("j_sock_and_buskin"), FJoker("j_scary_face")))
        assertEquals(340.0, result.score, 0.0)
    }

    // Sock and Buskin does NOT retrigger a non-face card (Pair of 2s).
    //   No retrigger: chips=14, mult=2, score=28.
    @Test fun sock_and_buskin_does_not_retrigger_non_face() {
        val result = Score.score(pair2s, listOf(FJoker("j_sock_and_buskin")))
        assertEquals(28.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // j_hack: +1 rep per scored 2, 3, 4, or 5.
    //   Pair of 2s: each 2 fires 2×. chips=10+2*2+2*2=18, mult=2, score=36.
    // -------------------------------------------------------------------------
    @Test fun hack_retriggers_2_through_5() {
        val result = Score.score(pair2s, listOf(FJoker("j_hack")))
        assertEquals(36.0, result.score, 0.0)
    }

    // Hack does NOT retrigger face cards (Pair of Jacks). score=60.
    @Test fun hack_does_not_retrigger_face_cards() {
        val result = Score.score(pairJacks, listOf(FJoker("j_hack")))
        assertEquals(60.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // j_dusk: +1 rep per scored card when handsLeft==0 (last hand).
    //   Pair of aces, handsLeft=0: same as Seltzer. chips=54, mult=2, score=108.
    //   Pair of aces, handsLeft=1: no retrigger. score=64.
    // -------------------------------------------------------------------------
    @Test fun dusk_retriggers_on_last_hand() {
        val result = Score.score(pairAces, listOf(FJoker("j_dusk")), handsLeft = 0)
        assertEquals(108.0, result.score, 0.0)
    }

    @Test fun dusk_does_not_retrigger_on_non_last_hand() {
        val result = Score.score(pairAces, listOf(FJoker("j_dusk")), handsLeft = 1)
        assertEquals(64.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // j_mime: +1 held-card rep when any held-card effect fired.
    //   Pair of aces, 1 Steel King held.
    //   Without Mime: chips=32, mult=2*1.5=3.0, score=96.
    //   With Mime: Steel fires twice → mult=2*1.5*1.5=4.5, score=floor(32*4.5)=144.
    // -------------------------------------------------------------------------
    @Test fun mime_retriggers_held_card_with_effect() {
        val steelKing = en("H_K", Enhancement.STEEL)
        val result = Score.score(pairAces, listOf(FJoker("j_mime")), held = listOf(steelKing))
        assertEquals(144.0, result.score, 0.0)
    }

    // Mime does NOT retrigger a held non-Steel card (no effect produced).
    //   Pair of aces, plain King held (no Steel). Mime votes 0 reps (no effect).
    //   chips=32, mult=2, score=64.
    @Test fun mime_does_not_retrigger_held_card_without_effect() {
        val plainKing = PlayingCard.parse("H_K")
        val result = Score.score(pairAces, listOf(FJoker("j_mime")), held = listOf(plainKing))
        assertEquals(64.0, result.score, 0.0)
    }

    // Mime + j_baron (×1.5 per held King): Baron fires on held King. Mime retriggers it.
    //   Pair of aces, plain King held.
    //   Without Mime + Baron: mult=2*1.5=3, score=96.
    //   With Mime: Baron fires → effect non-empty → Mime +1 rep → King fires 2×.
    //   mult=2*1.5*1.5=4.5, score=floor(32*4.5)=144.
    @Test fun mime_retriggers_held_card_that_produced_joker_effect() {
        val plainKing = PlayingCard.parse("H_K")
        val result = Score.score(pairAces, listOf(FJoker("j_mime"), FJoker("j_baron")), held = listOf(plainKing))
        assertEquals(144.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // Red Seal + retrigger joker: additive. Red Seal +1 rep, Hack +1 rep for a 2.
    //   Single 2♥ with red seal: fires 1+1(seal)+1(hack)=3 times.
    //   High Card base: chips=5, mult=1. 2♥ chips=2 → fires 3×.
    //   chips=5+2*3=11, mult=1, score=11.
    // -------------------------------------------------------------------------
    @Test fun red_seal_and_hack_add_independently() {
        val redSeal2 = PlayingCard.parse("H_2").copy(seal = Seal.RED)
        val result = Score.score(listOf(redSeal2), listOf(FJoker("j_hack")))
        // High Card base chips=5, mult=1. 2 (chips=2) fires 3×: chips=5+6=11, mult=1. score=11.
        assertEquals(11.0, result.score, 0.0)
    }
}
