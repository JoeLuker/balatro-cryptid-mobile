package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Retrigger harness — the third untested-by-oracle surface, and the one the audits flagged most
 * (mstack/chad retrigger caps, boredom's missing card-retrigger). Retriggers are routed through
 * `dispatchManifest` on two distinct passes, and the oracle only ever sees their effect folded into
 * a final score, so an off-by-one or a wrong target hides easily.
 *
 * This drives the REAL dispatchManifest on both retrigger routes and asserts the emitted repetition
 * count directly:
 *   - JOKER retrigger (`ctx.jokerRetriggerCheck`): the `retrigger` hook votes reps for a board joker —
 *     position-sensitive (chad=leftmost, spectrogram=rightmost), capped (loopy ≤40), self-excluded.
 *   - CARD retrigger (`ctx.repetition`): an `individual` hook contributes reps for a scored card.
 */
class RetriggerHookTest {

    private fun card(suit: Suit, id: Int) = PlayingCard(suit, id)
    private fun seeded(key: String): FJoker = FJoker(key).also { fj -> JOKER_MANIFEST[key]?.initialState?.let { fj.restore(it) } }

    /** Fire one joker-retrigger vote through dispatchManifest (self casts on [target], given [board] order). */
    private fun jokerVote(self: FJoker, target: FJoker, board: List<FJoker>): Fx? =
        dispatchManifest(JOKER_MANIFEST[self.key]!!, self, Sctx().apply {
            jokerRetriggerCheck = true; retriggeredJoker = target; this.board = board
        })

    /** Fire one card-retrigger collection pass through dispatchManifest for a scored played card. */
    private fun cardReps(fj: FJoker, c: PlayingCard, cfg: Sctx.() -> Unit = {}): Fx? =
        dispatchManifest(JOKER_MANIFEST[fj.key]!!, fj, Sctx().apply { repetition = true; cardarea = "play"; otherCard = c; cfg() })
    private fun cardReps(key: String, c: PlayingCard, cfg: Sctx.() -> Unit = {}): Fx? = cardReps(FJoker(key), c, cfg)

    // ── joker-retrigger hooks (ctx.jokerRetriggerCheck) ──────────────────────────────────────────
    @Test fun chad_retriggersLeftmostJoker_seeded2_selfExcluded() {
        val chad = seeded("j_cry_chad")                       // initialState n=2
        assertEquals(2, chad.n)
        val a = FJoker("j_a"); val b = FJoker("j_b")
        assertEquals(2, jokerVote(chad, a, listOf(a, b))!!.repetitions)   // a is leftmost → 2 reps
        assertNull("only the leftmost joker is retriggered", jokerVote(chad, b, listOf(a, b)))
        assertNull("chad never retriggers itself", jokerVote(chad, chad, listOf(chad, a)))
    }

    @Test fun spectrogram_retriggersRightmostJoker() {
        val spec = FJoker("j_cry_spectrogram", n = 2)         // n = Echo cards scored this hand
        val a = FJoker("j_a"); val b = FJoker("j_b")
        assertEquals(2, jokerVote(spec, b, listOf(a, b))!!.repetitions)   // b is rightmost → 2
        assertNull("only the rightmost joker is retriggered", jokerVote(spec, a, listOf(a, b)))
        val cold = FJoker("j_cry_spectrogram", n = 0)
        assertNull("no Echo cards → no retrigger", jokerVote(cold, b, listOf(a, b)))
    }

    @Test fun loopy_retriggersAnyOtherJoker_cappedAt40() {
        val a = FJoker("j_a")
        assertEquals(3, jokerVote(FJoker("j_cry_loopy", n = 3), a, listOf(a))!!.repetitions)
        assertEquals("capped at max_retriggers=40", 40, jokerVote(FJoker("j_cry_loopy", n = 50), a, listOf(a))!!.repetitions)
        val loopy = FJoker("j_cry_loopy", n = 5)
        assertNull("loopy never retriggers itself", jokerVote(loopy, loopy, listOf(loopy)))
    }

    @Test fun boredom_retriggersOncePerWonRoll_selfExcluded() {
        val a = FJoker("j_a")
        assertEquals(1, jokerVote(FJoker("j_cry_boredom", n = 1), a, listOf(a))!!.repetitions)  // roll won (n=1)
        assertNull("roll lost (n=0) → no retrigger", jokerVote(FJoker("j_cry_boredom", n = 0), a, listOf(a)))
        val bored = FJoker("j_cry_boredom", n = 1)
        assertNull("self-excluded", jokerVote(bored, bored, listOf(bored)))
    }

    @Test fun flipSide_retriggersOnlyDoubleSidedJokers() {
        val flip = FJoker("j_cry_flip_side")
        assertEquals(1, jokerVote(flip, FJoker("j_x", edition = "cry_double_sided"), listOf())!!.repetitions)
        assertNull("plain joker → no retrigger", jokerVote(flip, FJoker("j_x"), listOf()))
    }

    // ── card-retrigger hooks (ctx.repetition → individual hook contributes reps) ──────────────────
    @Test fun cardRetriggerReactors() {
        assertEquals(1, cardReps("j_hack", card(Suit.S, 3))!!.repetitions)            // 2–5
        assertNull(cardReps("j_hack", card(Suit.S, 9)))
        assertEquals(1, cardReps("j_sock_and_buskin", card(Suit.S, 13))!!.repetitions) // face
        assertNull(cardReps("j_sock_and_buskin", card(Suit.S, 5)))
        assertEquals(3, cardReps("j_cry_mask", card(Suit.S, 12))!!.repetitions)        // face → 3
        assertEquals(2, cardReps("j_cry_weegaming", card(Suit.S, 2))!!.repetitions)    // rank 2 → 2
        assertEquals(3, cardReps("j_cry_nosound", card(Suit.S, 7))!!.repetitions)      // rank 7 → 3
        assertEquals(2, cardReps("j_cry_exposed", card(Suit.S, 5))!!.repetitions)      // non-face → 2
        assertNull("exposed skips faces", cardReps("j_cry_exposed", card(Suit.S, 13)))
    }

    @Test fun mstack_retriggersEveryScoredCard_cappedAt40() {
        assertEquals(3, cardReps(FJoker("j_cry_mstack", n = 3), card(Suit.S, 9))!!.repetitions)
        assertEquals("retrigger count capped at 40", 40, cardReps(FJoker("j_cry_mstack", n = 99), card(Suit.S, 9))!!.repetitions)
    }

    @Test fun hangingChad_retriggersOnlyFirstScoringCard() {
        val first = card(Suit.S, 14); val other = card(Suit.S, 3)
        assertEquals(2, cardReps("j_hanging_chad", first) { scoringHand = listOf(first, other) }!!.repetitions)
        assertNull("only the first scoring card", cardReps("j_hanging_chad", other) { scoringHand = listOf(first, other) })
    }

    @Test fun dusk_retriggersOnlyOnLastHand() {
        assertEquals(1, cardReps("j_dusk", card(Suit.S, 9)) { handsLeft = 0 }!!.repetitions)
        assertNull("not the last hand → no retrigger", cardReps("j_dusk", card(Suit.S, 9)) { handsLeft = 2 })
    }

    @Test fun iterum_retriggersEveryCard() {
        assertEquals(1, cardReps("j_cry_iterum", card(Suit.S, 9))!!.repetitions)  // always +1 retrigger (also X2 mult)
    }
}
