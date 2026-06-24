package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Economy jokers — they grant dollars at end of round (calculate_dollar_bonus), surfaced as a JOKER
 * row in the cash-out and banked. Driven through enterRoundEval() (which builds the cash-out) and
 * asserted on the JOKER eval row.
 */
class EconomyJokerTest {
    private fun offer(key: String) = Offer(key, key, "", 0)
    private fun jokerDollars(rs: RunState): Int =
        rs.evalRows.filter { it.kind == EvalKind.JOKER }.sumOf { it.dollars }

    @Test fun goldenJokerPaysFourAtEndOfRound() {
        val rs = RunState()
        rs.buy(offer("j_golden"), free = true)
        rs.enterRoundEval()
        assertEquals(4, jokerDollars(rs))
    }

    @Test fun cloud9PaysOnePerNineInDeck() {
        val rs = RunState()                  // a standard deck has four 9s
        rs.buy(offer("j_cloud_9"), free = true)
        rs.enterRoundEval()
        assertEquals(4, jokerDollars(rs))
    }

    @Test fun noEconomyJokerMeansNoJokerRow() {
        val rs = RunState()
        rs.enterRoundEval()
        assertEquals(0, jokerDollars(rs))
    }

    @Test fun toTheMoonPaysExtraInterestPerFiveUncapped() {
        val rs = RunState()
        rs.money = 30                        // capped interest row stops at $5; To the Moon adds 30/5 = 6 more, uncapped
        rs.buy(offer("j_to_the_moon"), free = true)
        rs.enterRoundEval()
        assertEquals(6, jokerDollars(rs))
    }

    @Test fun delayedGratPaysTwoPerDiscardWhenNoneUsed() {
        val rs = RunState()                  // default round: 3 discards available, none used → $2 × 3 = $6
        rs.buy(offer("j_delayed_grat"), free = true)
        rs.enterRoundEval()
        assertEquals(6, jokerDollars(rs))
    }
}
