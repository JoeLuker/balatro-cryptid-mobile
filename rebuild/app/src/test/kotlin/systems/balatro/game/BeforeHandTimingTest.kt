package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The context.before timing class (parity audit blockers 4/8): scaling jokers grow BEFORE the
 *  joker_main pass of the SAME hand — vanilla card.lua ~3411-3569 — so the growth counts for the
 *  hand that triggers it, not one hand late. Plus The Flint's exact halving (blind.lua:511-513). */
class BeforeHandTimingTest {

    private fun fj(key: String) = FJoker(key)

    @Test fun greenJokerCountsTheTriggeringHand() {
        // fresh Green Joker, first hand ever: vanilla gives +1 Mult ON this hand
        val pair = PlayingCard.hand("S_9", "H_9")
        val base = Score.score(pair, emptyList()).score
        val with = Score.score(pair, listOf(fj("j_green_joker"))).score
        // Pair base 10c x2m; +1 mult -> x3m. Any increase proves in-hand growth.
        assertTrue("green joker must add its Mult on the hand that grows it (base=$base with=$with)", with > base)
    }

    @Test fun runnerCountsTheTriggeringStraight() {
        val straight = PlayingCard.hand("S_5", "H_6", "D_7", "C_8", "S_9")
        val base = Score.score(straight, emptyList()).score
        val with = Score.score(straight, listOf(fj("j_runner"))).score
        assertTrue("runner's +15 chips counts the straight that triggers it", with > base)
    }

    @Test fun squareJokerCountsTheTriggering4CardHand() {
        val four = PlayingCard.hand("S_2", "H_5", "D_8", "C_10")
        val base = Score.score(four, emptyList()).score
        val with = Score.score(four, listOf(fj("j_square"))).score
        assertTrue("square joker's +4 chips counts the 4-card hand that grows it", with > base)
    }

    @Test fun rideTheBusGrowsOnFacelessAndResetsOnFace() {
        val faceless = PlayingCard.hand("S_2", "H_5", "D_8")
        val bus = fj("j_ride_the_bus")
        val r1 = Score.score(faceless, listOf(bus))
        val base = Score.score(faceless, emptyList())
        assertTrue("faceless hand grows the bus and counts it this hand", r1.score > base.score)
        // state persisted on the FJoker: next faceless hand grows again
        val r2 = Score.score(faceless, listOf(bus))
        assertTrue("second faceless hand scores higher (mult 2 vs 1)", r2.score > r1.score)
        // a face card resets the accumulator to 0 -> no bonus this hand
        val faced = PlayingCard.hand("S_K", "H_5", "D_8")
        Score.score(faced, listOf(bus))
        assertEquals("face reset the accumulated mult", 0.0, bus.mult, 1e-9)
    }

    @Test fun obeliskGrowsWhenAnotherHandTypeLeads() {
        val ob = fj("j_obelisk")
        // PAIR has been played 5 times before; now play a straight -> pair still leads -> grow +0.2
        val straight = PlayingCard.hand("S_5", "H_6", "D_7", "C_8", "S_9")
        Score.score(straight, listOf(ob), handTypePlays = mapOf(HandType.PAIR to 5))
        assertEquals(1.2, ob.x, 1e-9)
        // now play PAIR when pair (incl. this play) is the sole leader -> reset to 1.0
        val pair = PlayingCard.hand("S_9", "H_9")
        Score.score(pair, listOf(ob), handTypePlays = mapOf(HandType.PAIR to 5, HandType.STRAIGHT to 1))
        assertEquals("sole-leader hand resets obelisk", 1.0, ob.x, 1e-9)
    }

    @Test fun constellationXMultApplies() {
        // RunScreen grows x on planet use; the jokerMain reader must apply it at scoring
        val con = fj("j_constellation").also { it.x = 1.3 }
        val pair = PlayingCard.hand("S_9", "H_9")
        val base = Score.score(pair, emptyList()).score
        val with = Score.score(pair, listOf(con)).score
        assertTrue("constellation X1.3 must scale the hand (base=$base with=$with)", with > base * 1.2)
    }

    @Test fun flintRoundsHalfUpWithMultFloor() {
        // Pair base: 10 chips x 2 mult. Flint: chips floor(10*.5+.5)=5, mult floor(2*.5+.5)=1
        val pair = PlayingCard.hand("S_9", "H_9")
        val flint = Score.score(pair, emptyList(), debuff = Debuff.Flint)
        // 9s add 9+9 chips: (5 + 18) * 1 = 23
        assertEquals(23.0, flint.score, 1e-9)
        // High Card base 5x1: chips floor(5*.5+.5)=3, mult max(floor(1*.5+.5),1)=1 -> the mult FLOOR case
        val hc = PlayingCard.hand("S_7")
        val hcFlint = Score.score(hc, emptyList(), debuff = Debuff.Flint)
        assertEquals("half-up chips=3, mult floored at 1, +7 card chips", 10.0, hcFlint.score, 1e-9)
    }
}
