package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First unit tests of the run loop itself. RunState holds the game state machine (owned/deck/money,
 * buy/sell/scoreBank/play) separate from the @Composable rendering; with android.util.Log no-op'd in
 * tests it drives directly off-device — the seam previously only reachable through the oracle.
 */
class RunStateTest {
    private fun offer(key: String, cost: Int = 0) = Offer(key, key, "", cost)

    @Test fun buySeedsInitialFJokerState() {
        val rs = RunState()
        val start = rs.owned.size
        rs.buy(offer("j_cry_bonk"), free = true)       // manifest initialState (chips=6, xc=3)
        assertEquals(start + 1, rs.owned.size)
        assertEquals(6.0, rs.owned.last().fj.chips, 0.0)
        assertEquals(3.0, rs.owned.last().fj.xc, 0.0)
        rs.buy(offer("j_cry_biggestm"), free = true)   // legacy fjXInit (x=7)
        assertEquals(7.0, rs.owned.last().fj.x, 0.0)
        rs.buy(offer("j_joker"), free = true)          // defaults
        assertEquals(1.0, rs.owned.last().fj.x, 0.0)
        assertEquals(0.0, rs.owned.last().fj.chips, 0.0)
    }

    @Test fun sellRefundsHalfCost() {
        val rs = RunState()
        rs.buy(offer("j_joker", cost = 6), free = true)
        rs.buy(offer("j_joker", cost = 4), free = true)
        val sizeBefore = rs.owned.size
        val moneyBefore = rs.money
        rs.sell(rs.owned.last())                        // cost 4 -> refund max(1, 4/2)=2
        assertEquals(sizeBefore - 1, rs.owned.size)
        assertEquals(moneyBefore + 2, rs.money)
    }

    @Test fun jollysusSpawnsAJokerWhenAJokerIsSold() {
        val rs = RunState()
        rs.buy(offer("j_cry_jollysus"), free = true)   // spawn armed (fj.n == 1)
        rs.buy(offer("j_joker"), free = true)
        rs.buy(offer("j_joker"), free = true)
        val before = rs.owned.size
        rs.sell(rs.owned.last())                        // sell -> jollysus spawns one (net 0)
        assertEquals(before, rs.owned.size)
    }

    @Test fun popcornDecaysFourPerRoundThenSelfDestructsAtZero() {
        // Vanilla j_popcorn: +20 Mult, loses 4 per ROUND (extra=4, context.end_of_round); self-destructs
        // (eaten) once its mult hits 0. The end-of-round path runs in cashOut() (RoundEnd reduce + removal).
        val rs = RunState()
        rs.buy(offer("j_popcorn"), free = true)
        val p = rs.owned.first { it.fj.key == "j_popcorn" }
        assertEquals(20.0, p.fj.mult, 0.0)
        rs.enterRoundEval(); rs.cashOut()                       // round 1 → 16
        assertEquals(16.0, p.fj.mult, 0.0)
        repeat(3) { rs.enterRoundEval(); rs.cashOut() }         // rounds 2-4 → 12, 8, 4
        assertEquals(4.0, p.fj.mult, 0.0)
        assertTrue("alive at mult 4", rs.owned.any { it.fj.key == "j_popcorn" })
        rs.enterRoundEval(); rs.cashOut()                       // round 5 → 0 → eaten
        assertTrue("self-destructs at 0 mult, end of round", rs.owned.none { it.fj.key == "j_popcorn" })
    }

    @Test fun turtleBeanAddsFiveHandSizeThenSelfDestructsAtZero() {
        // Vanilla j_turtle_bean: +5 hand size (h_size=5), −1 per round (h_mod=1); self-destructs at 0.
        val rs = RunState()
        val baseHand = rs.handSize                              // hand size before Turtle Bean
        rs.buy(offer("j_turtle_bean"), free = true)
        val t = rs.owned.first { it.fj.key == "j_turtle_bean" }
        assertEquals(5, t.fj.n)                                 // initialState h_size=5
        rs.phase = Phase.BLIND_SELECT; rs.selectBlind()         // re-run startRound with Turtle Bean owned
        assertEquals("+5 hand size", baseHand + 5, rs.handSize)
        repeat(4) { rs.enterRoundEval(); rs.cashOut() }         // n: 5→4→3→2→1
        assertEquals(1, t.fj.n)
        assertTrue("alive at n=1", rs.owned.any { it.fj.key == "j_turtle_bean" })
        rs.enterRoundEval(); rs.cashOut()                       // n → 0 → eaten
        assertTrue("self-destructs when h_size hits 0", rs.owned.none { it.fj.key == "j_turtle_bean" })
    }

    @Test fun flashCardGainsTwoMultPerShopReroll() {
        // Vanilla j_flash (Flash Card): +2 Mult each time the shop is rerolled (context.reroll_shop).
        val rs = RunState()
        rs.buy(offer("j_flash"), free = true)
        val f = rs.owned.first { it.fj.key == "j_flash" }
        assertEquals(0.0, f.fj.mult, 0.0)
        rs.money = 100; rs.phase = Phase.SHOP                   // reroll requires SHOP phase + funds
        rs.reroll(); rs.reroll(); rs.reroll()
        assertEquals("+2 Mult per reroll", 6.0, f.fj.mult, 0.0)
    }

    @Test fun vagabondCreatesTarotWhenPlayingAtFourOrLess() {
        // Vanilla j_vagabond: playing a hand while at $4 or less creates a Tarot (if consumable room).
        val rs = RunState()
        rs.consumables.clear()                                  // free the 2 starter consumable slots
        rs.buy(offer("j_vagabond"), free = true)
        rs.money = 4                                            // <= extra(4) → fires
        rs.selected = setOf(0, 1)                               // play two cards from the drawn hand
        rs.play(); rs.scoreBank()
        assertTrue("Tarot created when played at <=\$4", rs.consumables.any { it is Consumable.TarotC })
    }

    @Test fun vagabondCreatesNothingWhenPlayingAboveFour() {
        // The dollar gate: at $5 (> extra), no Tarot is created.
        val rs = RunState()
        rs.consumables.clear()
        rs.buy(offer("j_vagabond"), free = true)
        rs.money = 5                                            // > extra(4) → does not fire
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertTrue("no Tarot above \$4", rs.consumables.none { it is Consumable.TarotC })
    }

    @Test fun hallucinationCreatesTarotsOnPackOpenAboutHalfTheTime() {
        // Vanilla j_hallucination: 1-in-2 chance to create a Tarot whenever a Booster Pack is opened.
        val rs = RunState()
        rs.buy(offer("j_hallucination"), free = true)
        rs.money = 100_000
        val pack = BoosterOffer("p_arcana", "Arcana Pack", "Arcana", 0, 3, 1)
        var hits = 0
        repeat(40) {
            rs.consumables.clear()                              // always leave room so only the roll gates it
            rs.buyBooster(pack)
            if (rs.consumables.any { it is Consumable.TarotC }) hits++
        }
        assertTrue("≈1-in-2 over 40 opens (got $hits)", hits in 10..30)   // generous band around 20
    }

    @Test fun noHallucinationMeansNoPackOpenTarot() {
        val rs = RunState()
        rs.money = 100_000
        val pack = BoosterOffer("p_arcana", "Arcana Pack", "Arcana", 0, 3, 1)
        repeat(10) { rs.consumables.clear(); rs.buyBooster(pack) }
        assertTrue("no Joker → no pack-open Tarot", rs.consumables.none { it is Consumable.TarotC })
    }
}
