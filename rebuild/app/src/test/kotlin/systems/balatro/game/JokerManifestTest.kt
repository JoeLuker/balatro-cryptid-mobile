package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payoff of the manifest: each joker's behaviour is PURE and testable in isolation — its scoring hooks
 * return a value from the [Effect] algebra and its state reducer is a pure function, so they can be checked
 * directly, with no scoring cascade. The old scattered when-blocks could only be exercised through the oracle.
 */
class JokerManifestTest {
    private fun spec(key: String) = JOKER_MANIFEST.getValue(key)
    private fun ctx(handType: HandType) = Sctx().apply { scoringName = handType; pokerHands = setOf(handType) }

    @Test fun strongholdFiresOnBulwarkOnly() {
        val s = FJokerState()
        assertEquals(Effect.XMult(5.0), spec("j_cry_stronghold").jokerMain!!(s, ctx(HandType.CRY_BULWARK)))
        assertEquals(Effect.None, spec("j_cry_stronghold").jokerMain!!(s, ctx(HandType.PAIR)))
    }

    @Test fun conditionalMultFamily() {
        assertEquals(Effect.Mult(8.0), spec("j_jolly").jokerMain!!(FJokerState(), ctx(HandType.PAIR)))
        assertEquals(Effect.None, spec("j_jolly").jokerMain!!(FJokerState(), ctx(HandType.FLUSH)))
        assertEquals(Effect.Mult(12.0), spec("j_zany").jokerMain!!(FJokerState(), ctx(HandType.THREE_OF_A_KIND)))
        assertEquals(Effect.Mult(10.0), spec("j_mad").jokerMain!!(FJokerState(), ctx(HandType.TWO_PAIR)))
        assertEquals(Effect.Mult(10.0), spec("j_droll").jokerMain!!(FJokerState(), ctx(HandType.FLUSH)))
    }

    @Test fun individualCardReactors() {
        val s = FJokerState(); val plain = Sctx()
        assertEquals(Effect.Mult(3.0), spec("j_greedy_joker").individual!!(s, plain, PlayingCard(Suit.D, 10)))
        assertEquals(Effect.None, spec("j_greedy_joker").individual!!(s, plain, PlayingCard(Suit.H, 10)))
        assertEquals(Effect.Mult(4.0), spec("j_even_steven").individual!!(s, plain, PlayingCard(Suit.S, 8)))
        assertEquals(Effect.None, spec("j_even_steven").individual!!(s, plain, PlayingCard(Suit.S, 7)))
        assertEquals(Effect.Chips(31.0), spec("j_odd_todd").individual!!(s, plain, PlayingCard(Suit.S, 14)))  // Ace is odd
        assertEquals(Effect.All(listOf(Effect.Chips(20.0), Effect.Mult(4.0))), spec("j_scholar").individual!!(s, plain, PlayingCard(Suit.S, 14)))
        assertEquals(Effect.Chips(80.0), spec("j_clever").jokerMain!!(s, ctx(HandType.TWO_PAIR)))
    }

    @Test fun bonkInitScalingAndPerJoker() {
        val bonk = spec("j_cry_bonk")
        assertEquals(6.0, bonk.initialState.chips, 0.0)
        assertEquals(3.0, bonk.initialState.xc, 0.0)
        val afterPair = bonk.reduce!!(bonk.initialState, GameEvent.BeforeHand(ctx(HandType.PAIR)))
        assertEquals(7.0, afterPair.chips, 0.0)
        assertEquals(6.0, bonk.reduce!!(bonk.initialState, GameEvent.BeforeHand(ctx(HandType.FLUSH))).chips, 0.0)
        assertEquals(Effect.Chips(7.0), bonk.otherJoker!!(afterPair, ctx(HandType.PAIR), FJoker("j_joker")))
        assertEquals(Effect.Chips(21.0), bonk.otherJoker!!(afterPair, ctx(HandType.PAIR), FJoker("j_jolly")))   // jolly: 7*3
    }

    @Test fun statefulAccumulators() {
        val trousers = spec("j_spare_trousers")
        var s = trousers.reduce!!(FJokerState(), GameEvent.HandScored(HandType.TWO_PAIR, 4))
        s = trousers.reduce!!(s, GameEvent.HandScored(HandType.FULL_HOUSE, 5))
        s = trousers.reduce!!(s, GameEvent.HandScored(HandType.PAIR, 2))   // not Two Pair / Full House -> no-op
        assertEquals(4.0, s.mult, 0.0)
        assertEquals(Effect.Mult(4.0), trousers.jokerMain!!(s, ctx(HandType.PAIR)))

        val runner = spec("j_runner")
        val rs = runner.reduce!!(FJokerState(), GameEvent.HandScored(HandType.STRAIGHT, 5))
        assertEquals(Effect.Chips(15.0), runner.jokerMain!!(rs, ctx(HandType.PAIR)))

        val square = spec("j_square")  // accrues only on a 5-card hand (uses playedCount)
        assertEquals(4.0, square.reduce!!(FJokerState(), GameEvent.HandScored(HandType.HIGH_CARD, 5)).chips, 0.0)
        assertEquals(0.0, square.reduce!!(FJokerState(), GameEvent.HandScored(HandType.HIGH_CARD, 4)).chips, 0.0)
    }

    @Test fun soldEventAccumulators() {
        // campfire: +0.25 Xmult per any joker sold (sell value irrelevant); joker_main reads the total once x > 1.
        val campfire = spec("j_campfire")
        var c = campfire.reduce!!(FJokerState(), GameEvent.Sold("j_jolly", 3))
        c = campfire.reduce!!(c, GameEvent.Sold("j_blueprint", 1))
        assertEquals(1.5, c.x, 1e-9)
        assertEquals(Effect.XMult(1.5), campfire.jokerMain!!(c, ctx(HandType.PAIR)))
        assertEquals(Effect.None, campfire.jokerMain!!(FJokerState(), ctx(HandType.PAIR)))   // x == 1 -> no Xmult yet

        // eternalflame: +0.1 Xmult only when the sold joker's sell value >= 2.
        val flame = spec("j_cry_eternalflame")
        var f = flame.reduce!!(FJokerState(), GameEvent.Sold("j_x", 2))   // >= 2 -> +0.1
        f = flame.reduce!!(f, GameEvent.Sold("j_y", 1))                   // < 2  -> no-op
        assertEquals(1.1, f.x, 1e-9)

        // ramen: starts x2 (manifest initialState), -0.01 per discarded card, floored at 1.
        val ramen = spec("j_ramen")
        assertEquals(2.0, ramen.initialState.x, 0.0)
        val discarded = listOf(PlayingCard(Suit.S, 2), PlayingCard(Suit.H, 3), PlayingCard(Suit.D, 4))
        assertEquals(1.97, ramen.reduce!!(ramen.initialState, GameEvent.Discarded(discarded)).x, 1e-9)
    }

    @Test fun greenJokerAccumulatesAndFloorsAtZero() {
        val green = spec("j_green_joker")
        var st = FJokerState()
        st = green.reduce!!(st, GameEvent.HandScored(HandType.PAIR))   // +1
        st = green.reduce!!(st, GameEvent.HandScored(HandType.PAIR))   // +1
        st = green.reduce!!(st, GameEvent.Discarded(emptyList()))      // -1
        assertEquals(1.0, st.mult, 0.0)
        assertEquals(Effect.Mult(1.0), green.jokerMain!!(st, ctx(HandType.PAIR)))
        val floored = green.reduce!!(FJokerState(), GameEvent.Discarded(emptyList()))
        assertEquals(0.0, floored.mult, 0.0)
        assertEquals(Effect.None, green.jokerMain!!(floored, ctx(HandType.PAIR)))
    }
}

/** dispatchManifest routes ctx.repetition=true through the individual hook so retrigger jokers
 *  contribute fx.repetitions in the pre-score repetition-collection pass. This was broken before the fix:
 *  manifested jokers with individual retrigger hooks silently returned null during the collection pass. */
class RepetitionRoutingTest {
    private fun fj(key: String) = FJoker(key)

    @Test fun hackRetriggersViaRepetitionPass() {
        val j = fj("j_hack")
        val spec = JOKER_MANIFEST.getValue("j_hack")
        val face2 = PlayingCard(Suit.S, 2)
        val face9 = PlayingCard(Suit.S, 9)
        // Repetition-collection pass: ctx.repetition=true
        val ctx = Sctx().apply { repetition = true; otherCard = face2 }
        val fx = dispatchManifest(spec, j, ctx)
        assertEquals(1, fx?.repetitions ?: 0)   // 2 is in 2..5 → retrigger
        val ctx9 = Sctx().apply { repetition = true; otherCard = face9 }
        assertEquals(null, dispatchManifest(spec, j, ctx9))  // 9 not in 2..5 → no retrigger
    }

    @Test fun sockAndBuskinRetriggersViaRepetitionPass() {
        val j = fj("j_sock_and_buskin")
        val spec = JOKER_MANIFEST.getValue("j_sock_and_buskin")
        val king = PlayingCard(Suit.H, 13)  // face
        val two  = PlayingCard(Suit.H, 2)   // not face
        val ctxFace = Sctx().apply { repetition = true; otherCard = king }
        assertEquals(1, dispatchManifest(spec, j, ctxFace)?.repetitions ?: 0)
        val ctxNonFace = Sctx().apply { repetition = true; otherCard = two }
        assertEquals(null, dispatchManifest(spec, j, ctxNonFace))
    }

    @Test fun duskRetriggersOnLastHandViaRepetitionPass() {
        val j = fj("j_dusk")
        val spec = JOKER_MANIFEST.getValue("j_dusk")
        val card = PlayingCard(Suit.S, 7)
        val ctxLast = Sctx().apply { repetition = true; handsLeft = 0; otherCard = card }
        assertEquals(1, dispatchManifest(spec, j, ctxLast)?.repetitions ?: 0)
        val ctxNotLast = Sctx().apply { repetition = true; handsLeft = 2; otherCard = card }
        assertEquals(null, dispatchManifest(spec, j, ctxNotLast))
    }
}

/** The Effect -> Fx boundary is the one place the algebra meets the engine; test it exhaustively. */
class EffectTest {
    @Test fun jokerMainMapsToModFields() {
        assertEquals(8.0, Effect.Mult(8.0).intoFx(individual = false).multMod, 0.0)
        assertEquals(5.0, Effect.XMult(5.0).intoFx(individual = false).xMultMod, 0.0)
        assertEquals(6.0, Effect.Chips(6.0).intoFx(individual = false).chipMod, 0.0)
        assertEquals(2.5, Effect.XChips(2.5).intoFx(individual = false).xChipMod, 0.0)
        assertEquals(1.1, Effect.EMult(1.1).intoFx(individual = false).eMult, 0.0)
        assertEquals(2, Effect.Retrigger(2).intoFx(individual = false).repetitions)
        assertTrue(Effect.Nullify.intoFx(individual = false).nullify)
    }

    @Test fun individualMapsToPlainFields() {
        assertEquals(4.0, Effect.Mult(4.0).intoFx(individual = true).mult, 0.0)
        assertEquals(2.0, Effect.XMult(2.0).intoFx(individual = true).xMult, 0.0)
        assertEquals(11.0, Effect.Chips(11.0).intoFx(individual = true).chips, 0.0)
    }

    @Test fun noneIsEmptyAndAllComposes() {
        assertTrue(Effect.None.intoFx(individual = false).empty)
        val fx = Effect.All(listOf(Effect.Chips(50.0), Effect.Mult(10.0))).intoFx(individual = false)
        assertEquals(50.0, fx.chipMod, 0.0)
        assertEquals(10.0, fx.multMod, 0.0)
    }
}

/** Verify that ctx.selfJoker identity guard works for circus and joker-retrigger-check jokers. */
class SelfJokerIdentityGuardTest {
    @Test fun circusExcludesSelf() {
        val circus = FJoker("j_cry_circus", rarity = 4)  // Legendary rarity=4 → would be X4 if not excluded
        val spec = JOKER_MANIFEST.getValue("j_cry_circus")
        val ctx = Sctx().apply { selfJoker = circus }
        // Self → excluded (oj === selfJoker)
        assertEquals(Effect.None, spec.otherJoker!!(circus.snapshot(), ctx, circus))
        // Other Rare joker → X2
        val rare = FJoker("j_joker", rarity = 3)
        assertEquals(Effect.XMult(2.0), spec.otherJoker!!(circus.snapshot(), ctx, rare))
        // Other Legendary → X4
        val legend = FJoker("j_other", rarity = 4)
        assertEquals(Effect.XMult(4.0), spec.otherJoker!!(circus.snapshot(), ctx, legend))
    }

    @Test fun chadRetriggersLeftmostNotSelf() {
        val spec = JOKER_MANIFEST.getValue("j_cry_chad")
        val chad = FJoker("j_cry_chad").also { it.restore(spec.initialState) }  // apply n=2 seed
        val other = FJoker("j_joker")

        // board=[other, chad]: other is leftmost. chad votes to retrigger other (+2) since other !== chad.
        val ctx1 = Sctx().apply { board = listOf(other, chad); retriggeredJoker = other; selfJoker = chad }
        assertEquals(2, dispatchManifest(spec, chad, ctx1.apply { jokerRetriggerCheck = true })?.repetitions ?: 0)

        // board=[chad, other]: chad is leftmost. chad is also the retrigger target → self-exclusion → null.
        val ctx2 = Sctx().apply { board = listOf(chad, other); retriggeredJoker = chad; selfJoker = chad }
        assertEquals(null, dispatchManifest(spec, chad, ctx2.apply { jokerRetriggerCheck = true }))

        // board=[other, chad]: chad is NOT leftmost. other is retriggered → fires.
        // Non-leftmost joker retriggered while chad is in play — fires because other === board.first().
        // (Confirm it does NOT fire when non-leftmost joker is retriggered)
        val ctx3 = Sctx().apply { board = listOf(other, chad); retriggeredJoker = chad; selfJoker = chad }
        assertEquals(null, dispatchManifest(spec, chad, ctx3.apply { jokerRetriggerCheck = true }))
    }

    @Test fun loopyExcludesSelf() {
        val loopy = FJoker("j_cry_loopy")
        loopy.n = 5
        val other = FJoker("j_joker")
        val spec = JOKER_MANIFEST.getValue("j_cry_loopy")
        // Other is retriggered target → fires
        val ctx1 = Sctx().apply { retriggeredJoker = other; selfJoker = loopy; jokerRetriggerCheck = true }
        assertEquals(5, dispatchManifest(spec, loopy, ctx1)?.repetitions ?: 0)
        // Self is retriggered → excluded
        val ctx2 = Sctx().apply { retriggeredJoker = loopy; selfJoker = loopy; jokerRetriggerCheck = true }
        assertEquals(null, dispatchManifest(spec, loopy, ctx2))
    }
}

/** Verify perCard accumulation + two-phase jokerMain reads. */
class PerCardAccumulationTest {
    @Test fun krustytheclownAccumulatesXPerCard() {
        val j = FJoker("j_cry_krustytheclown")
        val spec = JOKER_MANIFEST.getValue("j_cry_krustytheclown")
        val c = PlayingCard(Suit.S, 5)
        val ctx = Sctx().apply { individual = true; cardarea = "play"; otherCard = c }
        // Before any scoring: x=1.0, jokerMain no-op
        assertEquals(Effect.None, spec.jokerMain!!(j.snapshot(), ctx))
        // After 1 perCard invocation: x=1.02
        j.restore(spec.perCard!!(j.snapshot(), ctx, c))
        assertEquals(1.02, j.x, 1e-9)
        // After 50 cards: x = 1.0 + 50*0.02 = 2.0
        repeat(49) { j.restore(spec.perCard!!(j.snapshot(), ctx, c)) }
        assertEquals(2.0, j.x, 1e-9)
        val xm = spec.jokerMain!!(j.snapshot(), ctx); check(xm is Effect.XMult) { "expected XMult but got $xm" }; assertEquals(2.0, (xm as Effect.XMult).x, 1e-6)
    }

    @Test fun weeFibAccumulatesOnFibRanksOnly() {
        val j = FJoker("j_cry_wee_fib")
        val spec = JOKER_MANIFEST.getValue("j_cry_wee_fib")
        val ctx = Sctx()   // default rankOf = id
        // Fibonacci ranks: 14(A), 2, 3, 5, 8
        for (rank in listOf(14, 2, 3, 5, 8)) {
            val c = PlayingCard(Suit.S, rank)
            j.restore(spec.perCard!!(j.snapshot(), ctx, c))
        }
        assertEquals(15.0, j.mult, 0.0)   // 5 * 3.0
        // Non-fibonacci ranks should not accumulate
        val c7 = PlayingCard(Suit.S, 7)
        j.restore(spec.perCard!!(j.snapshot(), ctx, c7))
        assertEquals(15.0, j.mult, 0.0)   // unchanged
        assertEquals(Effect.Mult(15.0), spec.jokerMain!!(j.snapshot(), ctx))
    }

    @Test fun facileFiresAtOrBelow10ThenResetsViaReducer() {
        val j = FJoker("j_cry_facile")
        val spec = JOKER_MANIFEST.getValue("j_cry_facile")
        val ctx = Sctx()
        val c = PlayingCard(Suit.S, 5)
        // Fire 10 perCard passes → n=10, jokerMain fires EMult(3.0)
        repeat(10) { j.restore(spec.perCard!!(j.snapshot(), ctx, c)) }
        assertEquals(10, j.n)
        assertEquals(Effect.EMult(3.0), spec.jokerMain!!(j.snapshot(), ctx))
        // 11th card → n=11, jokerMain no-op
        j.restore(spec.perCard!!(j.snapshot(), ctx, c))
        assertEquals(Effect.None, spec.jokerMain!!(j.snapshot(), ctx))
        // HandScored reducer resets n to 0
        j.restore(spec.reduce!!(j.snapshot(), GameEvent.HandScored(HandType.PAIR)))
        assertEquals(0, j.n)
    }
}

/** Blueprint copies through the manifest — verifies the pre-manifest copy block delegates correctly. */
class BlueprintCopyTest {
    @Test fun blueprintCopiesJollyViaManifest() {
        val jolly     = FJoker("j_jolly")
        val blueprint = FJoker("j_blueprint")
        // Blueprint copies the joker to its RIGHT. board=[blueprint, jolly] → blueprint copies jolly.
        // Hand must be a PAIR for jolly (+8 Mult) to fire. A=14, A=14 is a pair.
        val played = listOf(PlayingCard(Suit.S, 14), PlayingCard(Suit.H, 14))
        val result = Score.score(played, listOf(blueprint, jolly))
        // PAIR base mult=2, jolly +8=10, blueprint copies jolly +8=18.
        assertEquals(18.0, result.mult, 0.0)
    }

    @Test fun brainstormCopiesLeftmost() {
        val jolly      = FJoker("j_jolly")
        val brainstorm = FJoker("j_brainstorm")
        // Brainstorm copies leftmost (jolly). board=[jolly, brainstorm] → brainstorm copies jolly.
        val played = listOf(PlayingCard(Suit.S, 14), PlayingCard(Suit.H, 14))
        val result = Score.score(played, listOf(jolly, brainstorm))
        // PAIR base mult=2, jolly +8=10, brainstorm copies jolly +8=18.
        assertEquals(18.0, result.mult, 0.0)
    }
}
