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

    // ── cry-Magnet: $10 if you have ≤4 Jokers, else $2 (calc_dollar_bonus) ──
    @Test fun cryMagnetPaysTenWithFourOrFewerJokers() {
        val rs = RunState()
        rs.buy(offer("j_cry_magnet"), free = true)                       // owned.size = 1 (≤4)
        rs.enterRoundEval()
        assertEquals("≤4 Jokers → \$10", 10, jokerDollars(rs))
    }

    @Test fun cryMagnetPaysTwoWithMoreThanFourJokers() {
        val rs = RunState()
        rs.buy(offer("j_cry_magnet"), free = true)
        repeat(4) { rs.buy(offer("j_joker"), free = true) }              // owned.size = 5 (>4); j_joker adds no $
        rs.enterRoundEval()
        assertEquals(">4 Jokers → \$2", 2, jokerDollars(rs))
    }

    // ── cry-Morse: pays its money (starts $1), +$2 each time an edition Joker is sold ──
    @Test fun cryMorsePaysOneAtStart() {
        val rs = RunState()
        rs.buy(offer("j_cry_morse"), free = true)
        rs.enterRoundEval()
        assertEquals("starts at \$1", 1, jokerDollars(rs))
    }

    @Test fun cryMorseGrowsTwoPerEditionJokerSold() {
        val rs = RunState()                                            // note: RunState init owns a starter j_joker
        rs.buy(offer("j_cry_morse"), free = true)
        rs.buy(Offer("j_joker", "j_joker", "", 0, edition = systems.balatro.content.Edition.FOIL), free = true)
        rs.sell(rs.owned.first { it.fj.edition == "Foil" })            // sell MY Foil joker (not the starter)
        assertEquals("payout grew to \$3", 3.0, rs.owned.first { it.fj.key == "j_cry_morse" }.fj.x, 1e-9)
        rs.enterRoundEval()
        assertEquals("now pays \$3", 3, jokerDollars(rs))
    }

    @Test fun cryMorseDoesNotGrowOnNonEditionSell() {
        val rs = RunState()
        rs.buy(offer("j_cry_morse"), free = true)
        rs.sell(rs.owned.first { it.fj.key == "j_joker" })             // sell the (edition-less) starter joker
        assertEquals("no growth selling a plain Joker", 1.0, rs.owned.first { it.fj.key == "j_cry_morse" }.fj.x, 1e-9)
    }

    // ── cry-Familiar Currency: spend $19 at round end to create a random Meme Joker ──
    @Test fun cryFamiliarCurrencyCreatesJokerWhenRich() {
        val rs = RunState()
        rs.buy(offer("j_cry_familiar_currency"), free = true)
        rs.money = 100                                          // ≥ $19
        val before = rs.owned.size
        rs.enterRoundEval(); rs.cashOut()
        assertEquals("spent \$19 to spawn a Joker", before + 1, rs.owned.size)
    }

    @Test fun cryFamiliarCurrencyDoesNothingBelowNineteen() {
        val rs = RunState()
        rs.buy(offer("j_cry_familiar_currency"), free = true)
        rs.money = 10                                          // < $19 (pre-bonus gate)
        val before = rs.owned.size
        rs.enterRoundEval(); rs.cashOut()
        assertEquals("too poor → no spawn", before, rs.owned.size)
    }

    // ── cry-Necromancer: selling a Joker recreates a random previously-sold Joker (no sell value) ──
    @Test fun cryNecromancerRecreatesASoldJokerWithNoSellValue() {
        val rs = RunState()
        rs.buy(offer("j_cry_necromancer"), free = true)
        rs.buy(offer("j_greedy_joker"), free = true)
        val before = rs.owned.size
        rs.sell(rs.owned.first { it.fj.key == "j_greedy_joker" })   // sell value > 0 → Necromancer fires
        assertEquals("sold 1, recreated 1 → size unchanged", before, rs.owned.size)
        val recreated = rs.owned.first { it.fj.key == "j_greedy_joker" }
        assertEquals("recreated Joker has 0 sell value", 0, rs.sellValue(recreated))
    }

    // ── cry-kidnap: $4 per "type" Joker (Jolly/Sly families) sold this run ──
    @Test fun cryKidnapPaysFourPerTypeJokerSold() {
        val rs = RunState()
        rs.buy(offer("j_cry_kidnap"), free = true)
        rs.buy(offer("j_jolly"), free = true)
        rs.sell(rs.owned.first { it.fj.key == "j_jolly" })          // a "type" Joker → counts
        rs.enterRoundEval()
        assertEquals("\$4 per type Joker sold", 4, jokerDollars(rs))
    }

    @Test fun cryKidnapIgnoresNonTypeJokersSold() {
        val rs = RunState()
        rs.buy(offer("j_cry_kidnap"), free = true)
        rs.sell(rs.owned.first { it.fj.key == "j_joker" })          // starter Joker is NOT a type Joker
        rs.enterRoundEval()
        assertEquals("non-type Joker sold → \$0", 0, jokerDollars(rs))
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
