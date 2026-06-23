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

/** RoundEnd reducer: chili_pepper accumulates x and counts down n; self-destruct gate n==0. */
class RoundEndTest {
    private fun spec(key: String) = JOKER_MANIFEST.getValue(key)

    @Test fun chiliPepperAccumulatesXAndCountsDownN() {
        val chili = spec("j_cry_chili_pepper")
        // Initial state: x=1.0, n=8 (rounds_remaining)
        assertEquals(1.0, chili.initialState.x, 0.0)
        assertEquals(8, chili.initialState.n)

        // After 1 RoundEnd: x=1.5, n=7 (not yet expired)
        val s1 = chili.reduce!!(chili.initialState, GameEvent.RoundEnd(0))
        assertEquals(1.5, s1.x, 1e-9)
        assertEquals(7, s1.n)

        // jokerMain fires at x=1.5 > 1.0 → XMult(1.5)
        val ctx = Sctx()
        assertEquals(Effect.XMult(1.5), chili.jokerMain!!(s1, ctx))

        // Non-RoundEnd events are ignored by the reducer
        val unchanged = chili.reduce!!(chili.initialState, GameEvent.HandScored(HandType.PAIR))
        assertEquals(1.0, unchanged.x, 0.0)
        assertEquals(8, unchanged.n)
    }

    @Test fun chiliPepperCountdownFloorsAtZeroAndSignalsSelfDestruct() {
        val chili = spec("j_cry_chili_pepper")
        // Simulate 8 RoundEnd events (all 8 rounds_remaining consumed)
        var s = chili.initialState
        repeat(8) { s = chili.reduce!!(s, GameEvent.RoundEnd(0)) }
        // After 8 rounds: x = 1.0 + 8*0.5 = 5.0, n = 0
        assertEquals(5.0, s.x, 1e-9)
        assertEquals(0, s.n)

        // 9th RoundEnd: n is floored at 0 (no underflow), x continues to grow
        val s9 = chili.reduce!!(s, GameEvent.RoundEnd(0))
        assertEquals(5.5, s9.x, 1e-9)
        assertEquals(0, s9.n)   // still 0, not -1

        // RunScreen removes when n <= 0; jokerMain still reads x until removal fires
        assertEquals(Effect.XMult(5.0), chili.jokerMain!!(s, Sctx()))
    }

    @Test fun chiliPepperJokerMainSilentAtDefaultX() {
        val chili = spec("j_cry_chili_pepper")
        // Fresh joker (x=1.0): jokerMain no-op (first round no Xmult yet)
        assertEquals(Effect.None, chili.jokerMain!!(chili.initialState, Sctx()))
    }

    @Test fun mondrianAccumulatesOnlyWhenZeroDiscards() {
        val mondrian = spec("j_cry_mondrian")
        val s0 = mondrian.initialState     // x=1.0 (FJokerState default)

        // RoundEnd with 0 discards used → +0.25
        val s1 = mondrian.reduce!!(s0, GameEvent.RoundEnd(discardsUsed = 0))
        assertEquals(1.25, s1.x, 1e-9)

        // RoundEnd with 1 discard used → no change
        val s2 = mondrian.reduce!!(s0, GameEvent.RoundEnd(discardsUsed = 1))
        assertEquals(1.0, s2.x, 1e-9)

        // Non-RoundEnd event → no change
        val s3 = mondrian.reduce!!(s0, GameEvent.HandScored(HandType.PAIR))
        assertEquals(1.0, s3.x, 1e-9)

        // jokerMain fires at x=1.25 > 1.0
        assertEquals(Effect.XMult(1.25), mondrian.jokerMain!!(s1, Sctx()))
        // jokerMain silent at default x=1.0
        assertEquals(Effect.None, mondrian.jokerMain!!(s0, Sctx()))
    }

    @Test fun campfireResetsXAtRoundEnd() {
        val campfire = spec("j_campfire")

        // Sold event: +0.25 per sale
        val sSold = campfire.reduce!!(campfire.initialState, GameEvent.Sold("j_joker", 3))
        assertEquals(1.25, sSold.x, 1e-9)

        // RoundEnd: resets to 1.0 regardless of accumulated x
        val sAfterSale = FJokerState(x = 2.0)
        val sReset = campfire.reduce!!(sAfterSale, GameEvent.RoundEnd(discardsUsed = 0))
        assertEquals(1.0, sReset.x, 1e-9)

        // RoundEnd fires even when x was already 1.0 (idempotent)
        val sNoOp = campfire.reduce!!(campfire.initialState, GameEvent.RoundEnd(discardsUsed = 0))
        assertEquals(1.0, sNoOp.x, 1e-9)
    }
}

/** BlindSkipped reducer: throwback accumulates +0.25 Xmult per blind skipped. */
class BlindSkippedTest {
    private fun spec(key: String) = JOKER_MANIFEST.getValue(key)

    @Test fun throwbackAccumulatesXPerBlindSkipped() {
        val throwback = spec("j_throwback")
        val s0 = throwback.initialState     // x=1.0

        // BlindSkipped → x += 0.25 each time
        val s1 = throwback.reduce!!(s0, GameEvent.BlindSkipped)
        assertEquals(1.25, s1.x, 1e-9)

        val s2 = throwback.reduce!!(s1, GameEvent.BlindSkipped)
        assertEquals(1.5, s2.x, 1e-9)   // Oracle: 2 skips → x=1.5 → 32*2*1.5=96

        // Non-BlindSkipped event → no change
        val sNop = throwback.reduce!!(s0, GameEvent.HandScored(HandType.PAIR))
        assertEquals(1.0, sNop.x, 1e-9)

        // jokerMain fires at x=1.5
        assertEquals(Effect.XMult(1.5), throwback.jokerMain!!(s2, Sctx()))

        // jokerMain silent at x=1.0 (no skips yet)
        assertEquals(Effect.None, throwback.jokerMain!!(s0, Sctx()))
    }
}

/** BeforeHand reducer: cry_whip accumulates +0.5 Xmult when played hand has a 2 and a 7 of different suits. */
class BeforeHandWhipTest {
    private fun spec(key: String) = JOKER_MANIFEST.getValue(key)

    private fun ctx(vararg cards: PlayingCard, smeared: Boolean = false): Sctx =
        Sctx().apply { fullHand = cards.toList(); this.smeared = smeared }

    @Test fun whipAccumulatesWhen2And7DifferentSuits() {
        val whip = spec("j_cry_whip")
        val s0 = whip.initialState   // x=1.0

        // 2H + 7S: different suits → triggers
        val twoH = PlayingCard(rank = 2, suit = Suit.H)
        val sevenS = PlayingCard(rank = 7, suit = Suit.S)
        val s1 = whip.reduce!!(s0, GameEvent.BeforeHand(ctx(twoH, sevenS)))
        assertEquals(1.5, s1.x, 1e-9)   // Oracle: HighCard + whip x=1.5 → triggered once

        // 2H + 7H: same suit → does NOT trigger
        val sevenH = PlayingCard(rank = 7, suit = Suit.H)
        val sNo = whip.reduce!!(s0, GameEvent.BeforeHand(ctx(twoH, sevenH)))
        assertEquals(1.0, sNo.x, 1e-9)

        // No 2 or 7 → does NOT trigger
        val aceS = PlayingCard(rank = 14, suit = Suit.S)
        val sNone = whip.reduce!!(s0, GameEvent.BeforeHand(ctx(aceS, sevenS)))
        assertEquals(1.0, sNone.x, 1e-9)

        // Non-BeforeHand event → no change
        val sNop = whip.reduce!!(s0, GameEvent.HandScored(HandType.HIGH_CARD))
        assertEquals(1.0, sNop.x, 1e-9)

        // jokerMain fires at x=1.5
        assertEquals(Effect.XMult(1.5), whip.jokerMain!!(s1, Sctx()))

        // jokerMain silent at x=1.0
        assertEquals(Effect.None, whip.jokerMain!!(s0, Sctx()))
    }

    @Test fun whipSmeared2HAnd7H() {
        // Smeared: H and D are the same colour-pair; 2H and 7H become {H,D} ∩ {H,D} — still same →
        // under smeared, H/D collapse into one pool, so 2H=={H,D} and 7H=={H,D}; single-element
        // intersection ts={H,D}, ss={H,D} with size>1 → triggered (any 2 in one colour vs any 7
        // in the same colour expands to 2 suits each, so ts.size>1 fires).
        val whip = spec("j_cry_whip")
        val s0 = whip.initialState
        val twoH = PlayingCard(rank = 2, suit = Suit.H)
        val sevenH = PlayingCard(rank = 7, suit = Suit.H)
        // With Smeared: 2H expands to {H,D}, 7H expands to {H,D} — ts.size=2 → triggered
        val sSmear = whip.reduce!!(s0, GameEvent.BeforeHand(ctx(twoH, sevenH, smeared = true)))
        assertEquals(1.5, sSmear.x, 1e-9)
    }

    @Test fun whipWildCard2TriggersWithAny7() {
        // WILD 2: expands to all suits; any 7 (even same rank suit) gives diff-suit pair
        val whip = spec("j_cry_whip")
        val s0 = whip.initialState
        val wild2 = PlayingCard(rank = 2, suit = Suit.H, enhancement = Enhancement.WILD)
        val sevenH = PlayingCard(rank = 7, suit = Suit.H)
        // WILD expands 2 to {H,D,S,C}; ts.size=4 → triggered
        val sWild = whip.reduce!!(s0, GameEvent.BeforeHand(ctx(wild2, sevenH)))
        assertEquals(1.5, sWild.x, 1e-9)
    }
}

/** CardAdded reducer: hologram accumulates +0.25 Xmult per card added to the deck. */
class CardAddedTest {
    private fun spec(key: String) = JOKER_MANIFEST.getValue(key)

    @Test fun hologramAccumulatesXPerCardAdded() {
        val hologram = spec("j_hologram")
        val s0 = hologram.initialState     // x=1.0 (FJokerState default)

        // CardAdded(count=1) → +0.25
        val s1 = hologram.reduce!!(s0, GameEvent.CardAdded(count = 1))
        assertEquals(1.25, s1.x, 1e-9)

        // CardAdded(count=3) → +0.75 from fresh state
        val s3 = hologram.reduce!!(s0, GameEvent.CardAdded(count = 3))
        assertEquals(1.75, s3.x, 1e-9)

        // Non-CardAdded event → no change
        val sNop = hologram.reduce!!(s0, GameEvent.HandScored(HandType.PAIR))
        assertEquals(1.0, sNop.x, 1e-9)

        // jokerMain fires at x=1.75 (oracle: 32 chips × 1.75 mult = 56; with PAIR base 2+0 mult: floor(32*1.75*2)... wait)
        // Oracle Case: "Pair of aces + hologram (x=1.75)" → 112: chips=32, mult=2, xMultMod=1.75 → floor(32*2*1.75)=112 ✓
        assertEquals(Effect.XMult(1.75), hologram.jokerMain!!(s3, Sctx()))

        // jokerMain silent at x=1.0 (no cards yet added this run)
        assertEquals(Effect.None, hologram.jokerMain!!(s0, Sctx()))
    }
}

/** HandScored reducer: obelisk grows x when a non-top hand is played, resets to 1.0 when current hand becomes top. */
class ObeliskHandScoredTest {
    private fun spec() = JOKER_MANIFEST.getValue("j_obelisk")

    @Test fun obeliskGrowsWhenAnotherHandLeads() {
        // PAIR played 1 time; HIGH_CARD also played 1 time → another type ties → grow
        val obelisk = spec()
        val s0 = obelisk.initialState   // x=1.0
        val plays = mapOf(HandType.PAIR to 1, HandType.HIGH_CARD to 1)
        val s1 = obelisk.reduce!!(s0, GameEvent.HandScored(HandType.PAIR, 2, plays))
        assertEquals(1.2, s1.x, 1e-9)   // PAIR is not the sole top → +0.2
    }

    @Test fun obeliskResetsWhenCurrentHandBecomesTop() {
        // PAIR played 3 times; no other hand type → PAIR is the sole top → reset to 1.0
        val obelisk = spec()
        val s0 = obelisk.initialState.copy(x = 1.4)   // already accumulated
        val plays = mapOf(HandType.PAIR to 3)   // only PAIR in history
        val s1 = obelisk.reduce!!(s0, GameEvent.HandScored(HandType.PAIR, 2, plays))
        assertEquals(1.0, s1.x, 1e-9)   // PAIR is sole top → reset
    }

    @Test fun obeliskNoOpWithEmptyHandPlays() {
        // Empty handPlays map (legacy compat: event fired without play-count context) → no change
        val obelisk = spec()
        val s0 = obelisk.initialState.copy(x = 1.4)
        val s1 = obelisk.reduce!!(s0, GameEvent.HandScored(HandType.PAIR, 2))
        assertEquals(1.4, s1.x, 1e-9)
    }

    @Test fun obeliskJokerMainFiresAboveOne() {
        // Oracle: x=1.4 → XMult(1.4) → chips=32, mult=2*1.4=2.8 → 89
        val obelisk = spec()
        assertEquals(Effect.XMult(1.4), obelisk.jokerMain!!(FJokerState(x = 1.4), Sctx()))
        assertEquals(Effect.None, obelisk.jokerMain!!(FJokerState(x = 1.0), Sctx()))
    }
}

/** Loyalty card jokerMain: fires X4 every 6 hands (elapsed ≡ 5 mod 6). */
class LoyaltyCardJokerMainTest {
    private fun spec() = JOKER_MANIFEST.getValue("j_loyalty_card")

    private fun ctx(total: Int, atCreate: Int): Sctx =
        Sctx().apply { totalHandsPlayed = total; handsPlayedAtCreate = atCreate }

    @Test fun loyaltyCardFiresAtElapsedFive() {
        // elapsed = 5 → (4 - 5) % 6 = (-1) % 6. Kotlin % is remainder; (-1).mod(6) = 5 == every(5) → fires.
        // Use .mod() which is always non-negative in Kotlin.
        val loyalty = spec()
        val s = loyalty.initialState
        // handsPlayedAtCreate=0, total=5: elapsed=5
        assertEquals(Effect.XMult(4.0), loyalty.jokerMain!!(s, ctx(5, 0)))
    }

    @Test fun loyaltyCardSilentAtElapsedZero() {
        val loyalty = spec()
        val s = loyalty.initialState
        // elapsed=0 → (4-0)%6=4 ≠ 5 → no fire
        assertEquals(Effect.None, loyalty.jokerMain!!(s, ctx(0, 0)))
    }

    @Test fun loyaltyCardFiresAgainAtElapsedEleven() {
        // elapsed=11 → (4-11) % 6 = (-7) % 6. Kotlin: (-7).mod(6)=5 → fires again
        val loyalty = spec()
        val s = loyalty.initialState
        assertEquals(Effect.XMult(4.0), loyalty.jokerMain!!(s, ctx(11, 0)))
    }

    @Test fun loyaltyCardSilentAtElapsedFour() {
        // elapsed=4 → (4-4)%6=0 ≠ 5 → no fire
        val loyalty = spec()
        val s = loyalty.initialState
        assertEquals(Effect.None, loyalty.jokerMain!!(s, ctx(4, 0)))
    }

    @Test fun loyaltyCardWithNonZeroAtCreate() {
        // atCreate=3, total=8 → elapsed=5 → fires
        val loyalty = spec()
        val s = loyalty.initialState
        assertEquals(Effect.XMult(4.0), loyalty.jokerMain!!(s, ctx(8, 3)))
        // atCreate=3, total=7 → elapsed=4 → no fire
        assertEquals(Effect.None, loyalty.jokerMain!!(s, ctx(7, 3)))
    }
}

class BlacklistJokerMainTest {
    private fun spec() = JOKER_MANIFEST.getValue("j_cry_blacklist")

    private fun ctx(played: List<PlayingCard>, held: List<PlayingCard> = emptyList()): Sctx =
        Sctx().apply { fullHand = played; heldHand = held }

    @Test fun blacklistNullifiesWhenPlayedHandContainsRank() {
        // Blacklisted rank = 14 (Ace). Played hand contains an Ace.
        val spec = spec()
        val s = FJokerState(n = 14)
        val result = spec.jokerMain!!(s, ctx(played = PlayingCard.hand("S_A", "H_K", "D_Q")))
        assertEquals(Effect.Nullify, result)
    }

    @Test fun blacklistNullifiesWhenHeldHandContainsRank() {
        // Rank in held hand (not played).
        val spec = spec()
        val s = FJokerState(n = 7)
        val result = spec.jokerMain!!(s, ctx(
            played = PlayingCard.hand("S_2", "H_3", "D_4", "C_5", "S_6"),
            held   = PlayingCard.hand("H_7", "D_8"),
        ))
        assertEquals(Effect.Nullify, result)
    }

    @Test fun blacklistSilentWhenRankAbsent() {
        // Rank 14 not present in played or held.
        val spec = spec()
        val s = FJokerState(n = 14)
        val result = spec.jokerMain!!(s, ctx(
            played = PlayingCard.hand("S_2", "H_3", "D_4", "C_5", "S_6"),
            held   = PlayingCard.hand("H_7", "D_8"),
        ))
        assertEquals(Effect.None, result)
    }

    @Test fun blacklistDefaultNZeroTreatedAsAce() {
        // n=0 → default rank = 14 (Ace). Ace in played hand → nullify.
        val spec = spec()
        val s = FJokerState(n = 0)
        val result = spec.jokerMain!!(s, ctx(played = PlayingCard.hand("C_A")))
        assertEquals(Effect.Nullify, result)
    }
}
