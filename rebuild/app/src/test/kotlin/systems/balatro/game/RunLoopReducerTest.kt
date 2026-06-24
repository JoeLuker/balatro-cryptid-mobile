package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reducer-driving harness — closes the coverage gap the oracle structurally cannot reach.
 *
 * The oracle scores with PRE-SET joker state (it calls Score.score on a fixed FJoker), so it exercises
 * only the jokerMain READ path. The ACCRUAL path — a joker's `reduce` hook fired by a run-loop GameEvent —
 * is never driven, which is exactly how this session's latent bugs hid behind green oracle cases:
 *   - j_square accrued on a 5-card hand instead of 4 (#59) — its oracle case pre-set chips=12.
 *   - j_cry_eternalflame gated its sell-scaling on sellValue>=2 (#59) — its oracle case pre-set x=1.3.
 *   - the #44 perCard double-count — masked by a wrong asserted value.
 *
 * [Sim] mirrors RunScreen's per-event reducer dispatch verbatim
 *   (scoreBank / discard / sell / cashOut / deck-add: `restore(reduce(snapshot(), event))`)
 * and seeds each joker from its spec's initialState (as initialFJoker does), so a test reads like the
 * real run loop: apply a sequence of events, assert the accumulated FJokerState. Any reducer whose
 * trigger / constant / floor / reset regresses now fails here automatically — no manual audit needed.
 *
 * Validated by mutation: reverting j_square to `== 5` or eternalflame to `sellValue >= 2` makes the
 * corresponding test below fail.
 */
class RunLoopReducerTest {

    /** Mirrors RunScreen's run-loop reducer dispatch over a set of owned jokers, seeded like initialFJoker. */
    private class Sim(vararg keys: String) {
        val jokers: List<FJoker> = keys.map { key ->
            FJoker(key).also { fj -> JOKER_MANIFEST[key]?.initialState?.let { fj.restore(it) } }
        }
        /** The exact dispatch RunScreen uses at every run-loop event site. */
        private fun dispatch(e: GameEvent) = apply {
            for (j in jokers) JOKER_MANIFEST[j.key]?.reduce?.let { j.restore(it(j.snapshot(), e)) }
        }
        fun hand(t: HandType, played: Int = 5, plays: Map<HandType, Int> = emptyMap(), total: Int = 0) =
            dispatch(GameEvent.HandScored(t, played, plays, total))
        fun beforeHand(name: HandType) = dispatch(GameEvent.BeforeHand(Sctx().apply { scoringName = name }))
        fun discard(n: Int) = dispatch(GameEvent.Discarded(List(n) { PlayingCard(Suit.S, 2) }))
        fun sell(key: String, value: Int = 3) = dispatch(GameEvent.Sold(key, value))
        fun roundEnd(discardsUsed: Int = 0) = dispatch(GameEvent.RoundEnd(discardsUsed))
        fun cardAdded(n: Int = 1) = dispatch(GameEvent.CardAdded(n))
        fun blindSkipped() = dispatch(GameEvent.BlindSkipped)
        operator fun get(key: String): FJoker = jokers.first { it.key == key }
    }

    // ── per-hand chip / mult accumulators ────────────────────────────────────────────────────────
    @Test fun square_accruesOnExactly4Cards_not5() {
        val sim = Sim("j_square")
        sim.hand(HandType.FLUSH, played = 4)
        assertEquals("4-card hand accrues +4 chips", 4.0, sim["j_square"].chips, 0.0)
        sim.hand(HandType.FLUSH, played = 5)
        assertEquals("5-card hand must NOT accrue (the #59 bug)", 4.0, sim["j_square"].chips, 0.0)
        sim.hand(HandType.FLUSH, played = 4)
        assertEquals(8.0, sim["j_square"].chips, 0.0)
    }

    @Test fun greenJoker_accruesPerHand_decaysPerDiscard_floorsAtZero() {
        val sim = Sim("j_green_joker")
        repeat(3) { sim.hand(HandType.HIGH_CARD) }       // +1 each → 3
        assertEquals(3.0, sim["j_green_joker"].mult, 0.0)
        repeat(4) { sim.discard(1) }                     // -1 each → floors at 0, never negative
        assertEquals(0.0, sim["j_green_joker"].mult, 0.0)
    }

    @Test fun spareTrousers_onlyTwoPairAndFullHouse() {
        val sim = Sim("j_spare_trousers")
        sim.hand(HandType.TWO_PAIR);  assertEquals(2.0, sim["j_spare_trousers"].mult, 0.0)
        sim.hand(HandType.FULL_HOUSE); assertEquals(4.0, sim["j_spare_trousers"].mult, 0.0)
        sim.hand(HandType.PAIR);      assertEquals("Pair must not accrue", 4.0, sim["j_spare_trousers"].mult, 0.0)
    }

    @Test fun runner_onlyStraights() {
        val sim = Sim("j_runner")
        sim.hand(HandType.STRAIGHT);       assertEquals(15.0, sim["j_runner"].chips, 0.0)
        sim.hand(HandType.STRAIGHT_FLUSH); assertEquals(30.0, sim["j_runner"].chips, 0.0)
        sim.hand(HandType.FLUSH);          assertEquals(30.0, sim["j_runner"].chips, 0.0)
    }

    @Test fun bonk_accruesOnPairBeforePass() {
        val sim = Sim("j_cry_bonk")                      // seeded chips=6 from initialState
        sim.beforeHand(HandType.PAIR);  assertEquals(7.0, sim["j_cry_bonk"].chips, 0.0)
        sim.beforeHand(HandType.FLUSH); assertEquals("non-Pair must not accrue", 7.0, sim["j_cry_bonk"].chips, 0.0)
    }

    @Test fun duplicare_accruesPlayedCountPerHand() {
        val sim = Sim("j_cry_duplicare")                 // starts x=1.0
        sim.hand(HandType.PAIR, played = 2)
        assertEquals(3.0, sim["j_cry_duplicare"].x, 1e-9)   // 1 + 2
    }

    // ── Sold-event accumulators ───────────────────────────────────────────────────────────────────
    @Test fun eternalflame_scalesOnEverySale_regardlessOfValue() {
        val sim = Sim("j_cry_eternalflame")
        sim.sell("j_x", value = 1)                       // sellValue 1 must STILL fire (the #59 bug)
        sim.sell("j_y", value = 5)
        assertEquals(1.2, sim["j_cry_eternalflame"].x, 1e-9)
    }

    @Test fun campfire_scalesPerSale_resetsAtRoundEnd() {
        val sim = Sim("j_campfire")
        sim.sell("j_a"); sim.sell("j_b")
        assertEquals(1.5, sim["j_campfire"].x, 1e-9)
        sim.roundEnd()
        assertEquals("Campfire resets at end of round", 1.0, sim["j_campfire"].x, 1e-9)
    }

    @Test fun mstack_incrementsNEveryThirdJollySale() {
        val sim = Sim("j_cry_mstack")                    // seeded n=1
        sim.sell("j_jolly"); sim.sell("j_jolly")         // counter 0→1→2, n unchanged
        assertEquals(1, sim["j_cry_mstack"].n)
        sim.sell("j_jolly")                              // 3rd → n+1, counter reset
        assertEquals(2, sim["j_cry_mstack"].n)
        assertEquals(0.0, sim["j_cry_mstack"].chips, 0.0)
        sim.sell("j_not_jolly")                          // non-Jolly ignored
        assertEquals(2, sim["j_cry_mstack"].n)
    }

    @Test fun loopy_countsJollySales() {
        val sim = Sim("j_cry_loopy")
        sim.sell("j_jolly"); sim.sell("j_jolly")
        assertEquals(2, sim["j_cry_loopy"].n)
        sim.sell("j_other")
        assertEquals(2, sim["j_cry_loopy"].n)
    }

    // ── Discarded / CardAdded / BlindSkipped accumulators ────────────────────────────────────────
    @Test fun ramen_depletesPerDiscardedCard_floorsAtOne() {
        val sim = Sim("j_ramen")                         // seeded x=2.0 from initialState
        sim.discard(50);  assertEquals(1.5, sim["j_ramen"].x, 1e-9)   // 2 - 0.01*50
        sim.discard(60);  assertEquals("floors at 1.0", 1.0, sim["j_ramen"].x, 1e-9)  // 1.5 - 0.6 → clamp
    }

    @Test fun iceCream_meltsFiveChipsPerHand_floorsAtZero() {
        val sim = Sim("j_ice_cream")                     // seeded chips=100 from initialState
        assertEquals(100.0, sim["j_ice_cream"].chips, 0.0)
        sim.hand(HandType.PAIR)
        assertEquals(95.0, sim["j_ice_cream"].chips, 0.0)   // -5 per hand played
        repeat(20) { sim.hand(HandType.HIGH_CARD) }
        assertEquals("melts to nothing, never negative", 0.0, sim["j_ice_cream"].chips, 0.0)
    }

    @Test fun popcorn_losesFourMultPerRound_notPerHand_floorsAtZero() {
        val sim = Sim("j_popcorn")                       // seeded mult=20 from initialState
        assertEquals(20.0, sim["j_popcorn"].mult, 0.0)
        sim.hand(HandType.PAIR); sim.hand(HandType.PAIR) // hands played must NOT decay popcorn
        assertEquals("decays per ROUND, not per hand", 20.0, sim["j_popcorn"].mult, 0.0)
        sim.roundEnd()
        assertEquals("-4 per round (extra=4)", 16.0, sim["j_popcorn"].mult, 0.0)
        repeat(5) { sim.roundEnd() }
        assertEquals("floors at 0, never negative", 0.0, sim["j_popcorn"].mult, 0.0)
    }

    @Test fun turtleBean_handSizeCounterDecaysPerRound() {
        val sim = Sim("j_turtle_bean")                   // seeded n=5 (h_size) from initialState
        assertEquals(5, sim["j_turtle_bean"].n)
        sim.hand(HandType.PAIR)                          // hands played must NOT shrink it
        assertEquals("decays per ROUND, not per hand", 5, sim["j_turtle_bean"].n)
        sim.roundEnd()
        assertEquals("-1 per round (h_mod=1)", 4, sim["j_turtle_bean"].n)
        repeat(4) { sim.roundEnd() }                     // 3, 2, 1, 0 → RunScreen prunes at <=0
        assertEquals("reaches 0 (eaten threshold)", 0, sim["j_turtle_bean"].n)
    }

    @Test fun yorick_gainsXMultEvery23Discards() {
        val sim = Sim("j_yorick")
        sim.discard(22); assertEquals("not yet at 23", 1.0, sim["j_yorick"].x, 1e-9)
        sim.discard(1);  assertEquals("23rd card → +X1", 2.0, sim["j_yorick"].x, 1e-9)
        sim.discard(46); assertEquals("two more thresholds", 4.0, sim["j_yorick"].x, 1e-9)
    }

    @Test fun hitTheRoad_scalesPerJackDiscarded_resetsEachRound() {
        val spec = JOKER_MANIFEST.getValue("j_hit_the_road")
        var s = spec.reduce!!(FJokerState(x = 1.0), GameEvent.Discarded(listOf(PlayingCard(Suit.S, 11), PlayingCard(Suit.H, 11), PlayingCard(Suit.S, 5))))
        assertEquals("+X0.5 per Jack (2 Jacks, the 5 ignored)", 2.0, s.x, 1e-9)
        s = spec.reduce!!(s, GameEvent.RoundEnd(0))
        assertEquals("resets to X1 at end of round", 1.0, s.x, 1e-9)
    }

    @Test fun hologram_scalesPerCardAdded() {
        val sim = Sim("j_hologram")
        sim.cardAdded(1);  assertEquals(1.25, sim["j_hologram"].x, 1e-9)
        sim.cardAdded(3);  assertEquals(2.0, sim["j_hologram"].x, 1e-9)   // +0.25*3
    }

    @Test fun throwback_scalesPerBlindSkipped() {
        val sim = Sim("j_throwback")
        sim.blindSkipped(); sim.blindSkipped()
        assertEquals(1.5, sim["j_throwback"].x, 1e-9)
    }

    // ── RoundEnd accumulators / lifecycle ────────────────────────────────────────────────────────
    @Test fun chiliPepper_scalesPerRound_countsDownPerishable() {
        val sim = Sim("j_cry_chili_pepper")              // seeded n=8 (rounds_remaining)
        sim.roundEnd()
        assertEquals(1.5, sim["j_cry_chili_pepper"].x, 1e-9)
        assertEquals(7, sim["j_cry_chili_pepper"].n)     // perishable countdown
    }

    @Test fun mondrian_scalesOnlyWhenNoDiscardsUsed() {
        val sim = Sim("j_cry_mondrian")
        sim.roundEnd(discardsUsed = 0);  assertEquals(1.25, sim["j_cry_mondrian"].x, 1e-9)
        sim.roundEnd(discardsUsed = 2);  assertEquals("a discard was used → no accrual", 1.25, sim["j_cry_mondrian"].x, 1e-9)
        sim.roundEnd(discardsUsed = 0);  assertEquals(1.5, sim["j_cry_mondrian"].x, 1e-9)
    }

    @Test fun biggestm_resetsActivationAtRoundEnd() {
        val sim = Sim("j_cry_biggestm")
        sim["j_cry_biggestm"].n = 1                      // activated by the before-pass
        sim.roundEnd()
        assertEquals(0, sim["j_cry_biggestm"].n)
    }

    @Test fun jollysus_rearmsAtRoundEnd() {
        val sim = Sim("j_cry_jollysus")
        sim["j_cry_jollysus"].n = 0                      // disarmed after spawning this round
        sim.roundEnd()
        assertEquals("re-arms once per round", 1, sim["j_cry_jollysus"].n)
    }

    @Test fun facile_resetsPassCountEachHand() {
        val sim = Sim("j_cry_facile")
        sim["j_cry_facile"].n = 7                         // accumulated during a prior hand's scoring
        sim.hand(HandType.PAIR)
        assertEquals(0, sim["j_cry_facile"].n)
    }

    // ── isolation: a joker only reacts to the events it cares about ───────────────────────────────
    @Test fun reducersIgnoreIrrelevantEvents() {
        val sim = Sim("j_square", "j_runner", "j_campfire", "j_ramen")
        sim.sell("j_x"); sim.discard(1); sim.roundEnd(); sim.blindSkipped()  // none of these are square/runner triggers
        assertEquals(0.0, sim["j_square"].chips, 0.0)
        assertEquals(0.0, sim["j_runner"].chips, 0.0)
    }
}
