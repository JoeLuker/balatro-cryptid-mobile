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
