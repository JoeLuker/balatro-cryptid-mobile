package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Score-time hook harness — the read-path counterpart to [RunLoopReducerTest].
 *
 * Covers the per-card (`individual` / `held`) and per-joker (`otherJoker`) scoring reactors, plus the
 * `dispatchManifest` ROUTING that selects them. The oracle exercises these only through a hand's final
 * score (integration), so a wrong Effect can hide behind compensating arithmetic — the same way the
 * #44 perCard double-count and the #59 reducer bugs slipped past green oracle cases. This pins each
 * hook's exact Effect in isolation AND drives the real `dispatchManifest`, so the routing class
 * (perCard firing on the wrong pass, individual-vs-joker_main field mapping, self-exclusion) is
 * caught directly rather than via score arithmetic.
 */
class ScoreHookTest {

    private fun ctx(smeared: Boolean = false, pareidolia: Boolean = false) =
        Sctx().apply { this.smeared = smeared; this.pareidolia = pareidolia }
    private fun card(suit: Suit, id: Int) = PlayingCard(suit, id)

    /** Direct hook calls — assert the exact Effect a scored/held card or other joker produces. */
    private fun ind(key: String, c: PlayingCard, ctx: Sctx = ctx()): Effect =
        JOKER_MANIFEST[key]!!.individual!!(FJokerState(), ctx, c)
    private fun heldHook(key: String, c: PlayingCard): Effect =
        JOKER_MANIFEST[key]!!.held!!(FJokerState(), Sctx(), c)
    private fun onJoker(key: String, other: FJoker): Effect =
        JOKER_MANIFEST[key]!!.otherJoker!!(FJokerState(), Sctx().apply { selfJoker = FJoker(key) }, other)

    // ── individual: suit reactors (+3 Mult on their suit) ─────────────────────────────────────────
    @Test fun suitMultReactors() {
        assertEquals(Effect.Mult(3.0), ind("j_greedy_joker", card(Suit.D, 10)))
        assertEquals(Effect.None, ind("j_greedy_joker", card(Suit.H, 10)))
        assertEquals(Effect.Mult(3.0), ind("j_lusty_joker", card(Suit.H, 9)))
        assertEquals(Effect.Mult(3.0), ind("j_wrathful_joker", card(Suit.S, 9)))
        assertEquals(Effect.Mult(3.0), ind("j_gluttenous_joker", card(Suit.C, 9)))
        assertEquals(Effect.None, ind("j_gluttenous_joker", card(Suit.S, 9)))
    }

    @Test fun suitChipAndMultReactors() {
        assertEquals(Effect.Chips(50.0), ind("j_arrowhead", card(Suit.S, 9)))   // Spade → +50 chips
        assertEquals(Effect.None, ind("j_arrowhead", card(Suit.H, 9)))
        assertEquals(Effect.Mult(7.0), ind("j_onyx_agate", card(Suit.C, 9)))    // Club → +7 mult
    }

    // ── individual: rank reactors ─────────────────────────────────────────────────────────────────
    @Test fun rankReactors() {
        assertEquals(Effect.Mult(4.0), ind("j_even_steven", card(Suit.S, 8)))   // even
        assertEquals(Effect.None, ind("j_even_steven", card(Suit.S, 7)))
        assertEquals(Effect.Chips(31.0), ind("j_odd_todd", card(Suit.S, 14)))   // Ace counts as odd
        assertEquals(Effect.Chips(31.0), ind("j_odd_todd", card(Suit.S, 7)))
        assertEquals(Effect.None, ind("j_odd_todd", card(Suit.S, 8)))
        assertEquals(Effect.All(listOf(Effect.Chips(20.0), Effect.Mult(4.0))), ind("j_scholar", card(Suit.S, 14)))  // Ace
        assertEquals(Effect.Mult(8.0), ind("j_fibonacci", card(Suit.S, 8)))     // 8 is Fibonacci
        assertEquals(Effect.Mult(8.0), ind("j_fibonacci", card(Suit.S, 14)))    // Ace is Fibonacci
        assertEquals(Effect.None, ind("j_fibonacci", card(Suit.S, 4)))
        assertEquals(Effect.All(listOf(Effect.Chips(10.0), Effect.Mult(4.0))), ind("j_walkie_talkie", card(Suit.S, 10)))
        assertEquals(Effect.All(listOf(Effect.Chips(10.0), Effect.Mult(4.0))), ind("j_walkie_talkie", card(Suit.S, 4)))
        assertEquals(Effect.None, ind("j_walkie_talkie", card(Suit.S, 5)))
    }

    // ── individual: face reactors (+ Pareidolia treats every card as a face) ──────────────────────
    @Test fun faceReactorsRespectPareidolia() {
        assertEquals(Effect.Chips(30.0), ind("j_scary_face", card(Suit.S, 13)))         // King is a face
        assertEquals(Effect.None, ind("j_scary_face", card(Suit.S, 5)))                 // 5 is not
        assertEquals(Effect.Chips(30.0), ind("j_scary_face", card(Suit.S, 5), ctx(pareidolia = true)))  // …unless Pareidolia
        assertEquals(Effect.Mult(5.0), ind("j_smiley", card(Suit.S, 12)))               // Queen is a face
        assertEquals(Effect.Mult(5.0), ind("j_smiley", card(Suit.S, 2), ctx(pareidolia = true)))
    }

    // ── individual: card retrigger ────────────────────────────────────────────────────────────────
    @Test fun hackRetriggersLowCards() {
        assertEquals(Effect.Retrigger(1), ind("j_hack", card(Suit.S, 2)))
        assertEquals(Effect.Retrigger(1), ind("j_hack", card(Suit.S, 5)))
        assertEquals(Effect.None, ind("j_hack", card(Suit.S, 6)))
    }

    // ── held-card reactors ────────────────────────────────────────────────────────────────────────
    @Test fun heldCardReactors() {
        assertEquals(Effect.XMult(1.5), heldHook("j_baron", card(Suit.S, 13)))          // held King → X1.5
        assertEquals(Effect.None, heldHook("j_baron", card(Suit.S, 12)))
        assertEquals(Effect.Mult(13.0), heldHook("j_shoot_the_moon", card(Suit.S, 12))) // held Queen → +13
    }

    // ── other-joker reactors ──────────────────────────────────────────────────────────────────────
    @Test fun otherJokerReactors() {
        assertEquals(Effect.XMult(1.5), onJoker("j_baseball", FJoker("x", rarity = 2))) // Uncommon → X1.5
        assertEquals(Effect.None, onJoker("j_baseball", FJoker("x", rarity = 1)))
        assertEquals(Effect.XMult(2.0), onJoker("j_cry_circus", FJoker("x", rarity = 3)))  // Rare
        assertEquals(Effect.XMult(4.0), onJoker("j_cry_circus", FJoker("x", rarity = 4)))  // Legendary
        assertEquals(Effect.None, onJoker("j_cry_circus", FJoker("x", rarity = 1)))        // Common
    }

    // ── dispatchManifest routing: the #44 class (caught directly, not via score arithmetic) ───────
    @Test fun perCard_accumulatesOnScoringPass_notCollectionPass() {
        val fib = card(Suit.S, 14)  // Ace is Fibonacci → wee_fib perCard adds +3 mult
        val onRepetition = FJoker("j_cry_wee_fib")
        dispatchManifest(JOKER_MANIFEST["j_cry_wee_fib"]!!, onRepetition,
            Sctx().apply { repetition = true; otherCard = fib })
        assertEquals("perCard must NOT fire on the repetition-collection pass (bug #44)", 0.0, onRepetition.mult, 0.0)

        val onScoring = FJoker("j_cry_wee_fib")
        dispatchManifest(JOKER_MANIFEST["j_cry_wee_fib"]!!, onScoring,
            Sctx().apply { individual = true; cardarea = "play"; otherCard = fib })
        assertEquals("perCard fires once on the individual scoring pass", 3.0, onScoring.mult, 0.0)
    }

    @Test fun routing_individualToPlainField_otherJokerToModField() {
        // an `individual` hook lands in the PLAIN Fx fields …
        val indFx = dispatchManifest(JOKER_MANIFEST["j_greedy_joker"]!!, FJoker("j_greedy_joker"),
            Sctx().apply { individual = true; cardarea = "play"; otherCard = card(Suit.D, 5) })!!
        assertEquals(3.0, indFx.mult, 0.0)
        assertEquals(0.0, indFx.multMod, 0.0)
        // … an `otherJoker` (joker_main-class) hook lands in the *Mod fields.
        val ojFx = dispatchManifest(JOKER_MANIFEST["j_baseball"]!!, FJoker("j_baseball"),
            Sctx().apply { otherJoker = FJoker("x", rarity = 2) })!!
        assertEquals(1.5, ojFx.xMultMod, 0.0)
    }

    @Test fun otherJoker_selfExclusion() {
        val self = FJoker("j_cry_circus", rarity = 3)        // Rare: would X2 a *different* Rare joker
        val fx = dispatchManifest(JOKER_MANIFEST["j_cry_circus"]!!, self,
            Sctx().apply { otherJoker = self })              // selfJoker === other → must be excluded
        assertNull("circus must not X-mult itself", fx)
    }
}
