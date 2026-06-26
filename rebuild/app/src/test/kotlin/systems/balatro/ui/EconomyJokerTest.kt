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

    // ── cry-gold Joker: floor(percent% × money) at round end; percent grows +2 per scored Gold card ──
    @Test fun cryGoldJokerPaysPercentOfMoney() {
        val rs = RunState()
        rs.buy(offer("j_cry_goldjoker"), free = true)
        rs.owned.first { it.fj.key == "j_cry_goldjoker" }.fj.x = 50.0   // percent grown to 50% (Gold cards)
        rs.money = 20
        rs.enterRoundEval()
        assertEquals("floor(0.01 × 50 × \$20) = \$10", 10, jokerDollars(rs))
    }

    @Test fun cryGoldJokerStartsAtZeroPercentSoNoPayout() {
        val rs = RunState()
        rs.buy(offer("j_cry_goldjoker"), free = true)
        rs.money = 100
        rs.enterRoundEval()
        assertEquals("percent starts at 0 → \$0", 0, jokerDollars(rs))
    }

    // ── cry-Compound Interest: starts 12%, pays floor(percent% × money), then percent grows +3 (when paid) ──
    @Test fun cryCompoundInterestPaysTwelvePercentThenGrows() {
        val rs = RunState()
        rs.buy(offer("j_cry_compound_interest"), free = true)
        val j = rs.owned.first { it.fj.key == "j_cry_compound_interest" }
        rs.money = 100
        rs.enterRoundEval()
        assertEquals("12% of \$100", 12, jokerDollars(rs))
        rs.cashOut()                                         // pays, then percent grows 12 → 15
        assertEquals("percent grew +3 after paying", 15.0, j.fj.x, 1e-9)
        rs.money = 100
        rs.enterRoundEval()
        assertEquals("now 15% of \$100", 15, jokerDollars(rs))
    }

    @Test fun cryCompoundInterestDoesNotGrowWhenBroke() {
        val rs = RunState()
        rs.buy(offer("j_cry_compound_interest"), free = true)
        val j = rs.owned.first { it.fj.key == "j_cry_compound_interest" }
        rs.money = 0
        rs.enterRoundEval(); rs.cashOut()                    // calc_dollar_bonus scales only when dollars > 0
        assertEquals("no growth at \$0", 12.0, j.fj.x, 1e-9)
    }

    // ── cry-redbloon: pays $20 the round its 2-round countdown reaches 0, then self-destructs ──
    @Test fun cryRedbloonPaysTwentyAfterTwoRoundsThenPops() {
        val rs = RunState()
        rs.buy(offer("j_cry_redbloon"), free = true)
        assertEquals("starts with a 2-round countdown", 2, rs.owned.first { it.fj.key == "j_cry_redbloon" }.fj.n)
        rs.enterRoundEval()
        assertEquals("round 1 (n=2): not expiring → no payout", 0, jokerDollars(rs))
        rs.cashOut()                                                          // RoundEnd reducer: n 2 → 1
        assertEquals("countdown decremented", 1, rs.owned.first { it.fj.key == "j_cry_redbloon" }.fj.n)
        rs.enterRoundEval()
        assertEquals("round 2 (n=1): expires this round → \$20", 20, jokerDollars(rs))
        rs.cashOut()                                                          // n 1 → 0 → pops
        assertEquals("redbloon self-destructed after paying", 0, rs.owned.count { it.fj.key == "j_cry_redbloon" })
    }
}
