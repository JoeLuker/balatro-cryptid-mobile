package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import systems.balatro.game.Boss
import systems.balatro.game.Enhancement
import systems.balatro.game.PlayingCard
import systems.balatro.game.Suit

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
        rs.phase = Phase.ROUND                                  // init deals a hand then parks at DECK_SELECT
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
        rs.phase = Phase.ROUND
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

    @Test fun tradingCardDestroysSingleFirstDiscardForThree() {
        // Vanilla j_trading: first discard of the round of exactly 1 card → destroy it, earn $3.
        val rs = RunState()
        rs.buy(offer("j_trading"), free = true)
        val deckBefore = rs.snapshot().deck.size
        val moneyBefore = rs.money
        rs.phase = Phase.ROUND
        rs.selected = setOf(0)                                  // discard one real (drawn) deck card, first discard
        rs.discard()
        assertEquals("+\$3 on single first-discard", moneyBefore + 3, rs.money)
        assertEquals("the discarded card is destroyed", deckBefore - 1, rs.snapshot().deck.size)
    }

    @Test fun tradingCardDoesNotFireOnMultiCardFirstDiscard() {
        // The "exactly one card" gate: discarding 2 cards on the first discard does NOT fire.
        val rs = RunState()
        rs.buy(offer("j_trading"), free = true)
        val deckBefore = rs.snapshot().deck.size
        val moneyBefore = rs.money
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)                              // two cards → gate blocks it
        rs.discard()
        assertEquals("no \$ on multi-card discard", moneyBefore, rs.money)
        assertEquals("nothing destroyed", deckBefore, rs.snapshot().deck.size)
    }

    @Test fun mailInRebatePaysFivePerDiscardedCardOfMailRank() {
        // Vanilla j_mail: +$5 per discarded card matching this round's random rank (mailRank).
        val rs = RunState()
        rs.buy(offer("j_mail"), free = true)
        val r = rs.mailRank
        val other = if (r == 3) 4 else 3
        rs.hand = listOf(PlayingCard(Suit.S, r), PlayingCard(Suit.H, r), PlayingCard(Suit.C, other))
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1, 2)                           // two match the mail rank, one doesn't
        val moneyBefore = rs.money
        rs.discard()
        assertEquals("\$5 × 2 matching cards", moneyBefore + 10, rs.money)
    }

    @Test fun reservedParkingPaysAboutHalfPerHeldFaceCard() {
        // Vanilla j_reserved_parking: each HELD face card has a 1-in-2 chance to give $1.
        val rs = RunState()
        rs.buy(offer("j_reserved_parking"), free = true)
        rs.money = 0
        rs.hand = listOf(PlayingCard(Suit.S, 2)) + (1..20).map { PlayingCard(Suit.H, 13) }  // play a 2, hold 20 Kings
        rs.handSize = rs.hand.size                              // so refill() doesn't draw a negative count
        rs.phase = Phase.ROUND
        rs.selected = setOf(0)
        rs.play(); rs.scoreBank()
        assertTrue("≈half of 20 held faces pay \$1 (got ${rs.money})", rs.money in 4..16)
    }

    @Test fun reservedParkingPaysNothingForHeldNumberCards() {
        // The face-card gate: held number cards (7s) never pay.
        val rs = RunState()
        rs.buy(offer("j_reserved_parking"), free = true)
        rs.money = 0
        rs.hand = listOf(PlayingCard(Suit.S, 2)) + (1..20).map { PlayingCard(Suit.H, 7) }   // held 7s (not face)
        rs.handSize = rs.hand.size                              // so refill() doesn't draw a negative count
        rs.phase = Phase.ROUND
        rs.selected = setOf(0)
        rs.play(); rs.scoreBank()
        assertEquals("no held face cards → no \$", 0, rs.money)
    }

    @Test fun giftCardAddsSellValueEachRoundAndPersistsAcrossSaveLoad() {
        // Vanilla j_gift (Gift Card): +$1 of sell value to every Joker at end of each round.
        val rs = RunState()
        rs.buy(offer("j_gift"), free = true)
        rs.buy(offer("j_cavendish", cost = 6), free = true)     // base sell value = maxOf(1, 6/2) = 3
        val plain = rs.owned.last()
        val baseSell = rs.sellValue(plain)
        assertEquals(3, baseSell)
        rs.enterRoundEval(); rs.cashOut()                       // +1 to all jokers' sell value
        assertEquals("+1 sell value after one round", baseSell + 1, rs.sellValue(plain))
        rs.enterRoundEval(); rs.cashOut()
        assertEquals("+2 after two rounds", baseSell + 2, rs.sellValue(plain))
        // the accumulated sell value survives a save/load round-trip
        val reloaded = RunState().also { it.restore(rs.snapshot()) }
        val plain2 = reloaded.owned.first { it.fj.key == "j_cavendish" }
        assertEquals("sell bonus persists", baseSell + 2, reloaded.sellValue(plain2))
    }

    @Test fun bossEffectAppliesWhenLuchadorNotSold() {
        // Control: THE_TOOTH (−$1 per played card) fires normally — proves the disable shadow is
        // transparent when bossDisabled is false.
        val rs = RunState()
        rs.blindIndex = 2; rs.boss = Boss.THE_TOOTH            // a boss round
        rs.money = 50
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertEquals("THE_TOOTH active → -\$1 per played card", 48, rs.money)
    }

    @Test fun luchadorSellDisablesTheBoss() {
        // Selling Luchador during a boss round disables it — THE_TOOTH no longer drains money.
        val rs = RunState()
        rs.blindIndex = 2; rs.boss = Boss.THE_TOOTH
        rs.buy(offer("j_luchador"), free = true)
        rs.sell(rs.owned.first { it.fj.key == "j_luchador" })
        assertTrue("boss disabled after selling Luchador", rs.bossDisabled)
        rs.money = 50
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertEquals("THE_TOOTH disabled → no per-card money loss", 50, rs.money)
    }

    @Test fun ancientSuitChangesEachRoundNeverRepeating() {
        // Ancient Joker's suit is re-picked every round and is never the same as last round
        // (reset_ancient_card). The X1.5 scoring itself is covered exhaustively by the oracle.
        val rs = RunState()
        var prev = rs.ancientSuit
        for (bi in 1..8) {
            rs.blindIndex = bi
            rs.phase = Phase.BLIND_SELECT
            rs.selectBlind()                                   // runs startRound → re-picks ancientSuit
            assertTrue("round $bi suit (${rs.ancientSuit}) must differ from previous ($prev)", rs.ancientSuit != prev)
            prev = rs.ancientSuit
        }
    }

    @Test fun midasMaskGoldifiesScoredFaceCards() {
        // Vanilla j_midas_mask: each scored face card permanently becomes a Gold card.
        val rs = RunState()
        rs.buy(offer("j_midas_mask"), free = true)
        // King♥ / King♠ value-match the standard deck's Kings (data-class equals), so the deck mutation lands.
        rs.hand = listOf(PlayingCard(Suit.H, 13), PlayingCard(Suit.S, 13))
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)                               // play the pair → both Kings score
        rs.play(); rs.scoreBank()
        val goldKings = rs.snapshot().deck.count { it.rank == 13 && it.enh == "GOLD" }
        assertEquals("both scored Kings became Gold in the deck", 2, goldKings)
    }

    @Test fun midasMaskLeavesNumberCardsUnchanged() {
        // The face gate: scored number cards (7s) do NOT become Gold.
        val rs = RunState()
        rs.buy(offer("j_midas_mask"), free = true)
        rs.hand = listOf(PlayingCard(Suit.H, 7), PlayingCard(Suit.S, 7))
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        val goldSevens = rs.snapshot().deck.count { it.rank == 7 && it.enh == "GOLD" }
        assertEquals("number cards are not gold-ified", 0, goldSevens)
    }

    @Test fun vampireGainsXMultPerScoredEnhancedCard() {
        // Vanilla j_vampire: the joker_main applies this hand's X0.1·n, and the run loop persists the growth.
        val rs = RunState()
        rs.buy(offer("j_vampire"), free = true)
        val v = rs.owned.first { it.fj.key == "j_vampire" }
        assertEquals(1.0, v.fj.x, 0.0)
        rs.hand = listOf(PlayingCard(Suit.S, 14, Enhancement.BONUS), PlayingCard(Suit.H, 14, Enhancement.BONUS))  // 2 enhanced aces
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertEquals("X0.1 per enhanced scored card (2 → x 1.0+0.2)", 1.2, v.fj.x, 1e-9)
    }

    @Test fun vampireDoesNotGrowOnPlainCards() {
        // The enhancement gate: scoring plain cards leaves Vampire's x unchanged.
        val rs = RunState()
        rs.buy(offer("j_vampire"), free = true)
        val v = rs.owned.first { it.fj.key == "j_vampire" }
        rs.hand = listOf(PlayingCard(Suit.S, 14), PlayingCard(Suit.H, 14))  // plain aces
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertEquals("no enhanced cards → x unchanged", 1.0, v.fj.x, 1e-9)
    }

    private fun jokerDollarsOf(rs: RunState) = rs.evalRows.filter { it.kind == EvalKind.JOKER }.sumOf { it.dollars }

    @Test fun rocketIncreasesAndPaysTheIncreasedAmountOnBossDefeat() {
        // Vanilla j_rocket: $1/round, +$2 when a Boss Blind is defeated — applied BEFORE the payout, so
        // the boss round itself pays the increased amount.
        val rs = RunState()
        rs.buy(offer("j_rocket"), free = true)
        val rk = rs.owned.first { it.fj.key == "j_rocket" }
        assertEquals("base payout \$1", 1, rk.fj.n)
        rs.blindIndex = 2; rs.boss = null                       // slot 2 = boss round (no play gate)
        rs.phase = Phase.ROUND
        rs.roundScore = rs.target + 1000.0                      // force the win branch
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertEquals("payout +\$2 on boss defeat", 3, rk.fj.n)
        assertEquals("the boss round pays the increased \$3", 3, jokerDollarsOf(rs))
    }

    @Test fun rocketDoesNotIncreaseOnNonBossRound() {
        val rs = RunState()
        rs.buy(offer("j_rocket"), free = true)
        rs.blindIndex = 0; rs.boss = null                       // slot 0 = small blind
        rs.phase = Phase.ROUND
        rs.roundScore = rs.target + 1000.0
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        val rk = rs.owned.first { it.fj.key == "j_rocket" }
        assertEquals("no increase off a boss", 1, rk.fj.n)
        assertEquals("pays base \$1", 1, jokerDollarsOf(rs))
    }

    @Test fun dietColaSellCreatesADoubleTag() {
        // Vanilla j_diet_cola: selling it creates a free Double Tag — here, +1 to doubleNextTags
        // (which duplicates the next earned skip tag, same mechanism as the Anaglyph deck).
        val rs = RunState()
        rs.buy(offer("j_diet_cola"), free = true)
        rs.buy(offer("j_joker"), free = true)                  // a second joker so Diet Cola is sellable
        val before = rs.doubleNextTags
        rs.sell(rs.owned.first { it.fj.key == "j_diet_cola" })
        assertEquals("selling Diet Cola grants one Double Tag", before + 1, rs.doubleNextTags)
    }

    @Test fun seltzerCountsDownEachHandAndSelfDestructsAtZero() {
        // Vanilla j_selzer: lasts 10 hands (n), decrementing once per hand, then self-destructs. The
        // retrigger-every-card effect itself is verified by the oracle (pair of aces → 108).
        val rs = RunState()
        rs.buy(offer("j_selzer"), free = true)
        val s = rs.owned.first { it.fj.key == "j_selzer" }
        assertEquals(10, s.fj.n)
        rs.hand = listOf(PlayingCard(Suit.S, 14), PlayingCard(Suit.H, 14))
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertEquals("decrements each hand", 9, s.fj.n)
        // one hand before expiry → the next hand self-destructs it
        s.fj.n = 1
        rs.hand = listOf(PlayingCard(Suit.S, 14), PlayingCard(Suit.H, 14))
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertTrue("self-destructs when its countdown reaches 0", rs.owned.none { it.fj.key == "j_selzer" })
    }

    @Test fun luckyCatGainsXMultPerLuckyTrigger() {
        // Vanilla j_lucky_cat: +X0.25 per Lucky-card trigger. The +20 Mult / X0.25 scoring is oracle-verified;
        // this checks the run-loop persistence. Fresh RunState → luckySeed 41; the scoring-index-2 card triggers.
        val rs = RunState()
        rs.buy(offer("j_lucky_cat"), free = true)
        val lc = rs.owned.first { it.fj.key == "j_lucky_cat" }
        assertEquals(1.0, lc.fj.x, 0.0)
        // a heart flush (all 5 score, in order); the 3rd card (index 2) is Lucky → triggers at this seed
        rs.hand = listOf(PlayingCard(Suit.H, 2), PlayingCard(Suit.H, 3),
                         PlayingCard(Suit.H, 5, Enhancement.LUCKY), PlayingCard(Suit.H, 7), PlayingCard(Suit.H, 9))
        rs.handSize = 5
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1, 2, 3, 4)
        rs.play(); rs.scoreBank()
        assertEquals("Lucky Cat persists +X0.25 per trigger", 1.25, lc.fj.x, 1e-9)
    }

    @Test fun luckyCardGrantsTwentyDollarsOnMoneyTrigger() {
        // m_lucky's 1-in-15 → $20, granted via the run loop's onLuckyMoney callback. At blindIndex 2 the
        // scoring-index-1 card hits the money roll.
        val rs = RunState()
        rs.blindIndex = 2; rs.boss = null
        val moneyBefore = rs.money
        rs.hand = listOf(PlayingCard(Suit.H, 2), PlayingCard(Suit.H, 3, Enhancement.LUCKY),
                         PlayingCard(Suit.H, 5), PlayingCard(Suit.H, 7), PlayingCard(Suit.H, 9))
        rs.handSize = 5
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1, 2, 3, 4)
        rs.play(); rs.scoreBank()
        assertEquals("Lucky card pays \$20 on the money trigger", moneyBefore + 20, rs.money)
    }

    @Test fun spareTrousersFiresOnAFlushHouseContainingTwoPair() {
        // End-to-end: a Flush House's top type is FLUSH_HOUSE (above Full House), but it CONTAINS a Two Pair,
        // so Spare Trousers must accrue (the old top-handType check missed it). Verifies the full path:
        // Score → pokerHands (TWO_PAIR present) → scoreBank fires HandScored(contained) → reducer accrues.
        val rs = RunState()
        rs.buy(offer("j_spare_trousers"), free = true)
        val st = rs.owned.first { it.fj.key == "j_spare_trousers" }
        assertEquals(0.0, st.fj.mult, 0.0)
        rs.hand = listOf(PlayingCard(Suit.S, 14), PlayingCard(Suit.S, 14), PlayingCard(Suit.S, 14),
                         PlayingCard(Suit.S, 13), PlayingCard(Suit.S, 13))   // 3 A♠ + 2 K♠ → Flush House
        rs.handSize = 5
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1, 2, 3, 4)
        rs.play(); rs.scoreBank()
        assertEquals("Flush House contains a Two Pair → Spare Trousers +2", 2.0, st.fj.mult, 1e-9)
    }

    @Test fun giftCardRefundEqualsSellValueIncludingBonus() {
        // The sell-value bonus must be consistent: sell()'s refund uses the same sellValue() as the UI.
        val rs = RunState()
        rs.buy(offer("j_gift"), free = true)
        rs.buy(offer("j_cavendish", cost = 6), free = true)     // base sell = 3
        val cav = rs.owned.last()
        repeat(3) { rs.enterRoundEval(); rs.cashOut() }         // Gift Card +1/round → sellBonus 3
        assertEquals("sellValue = base + accumulated bonus", 6, rs.sellValue(cav))
        val moneyBefore = rs.money
        rs.sell(cav)
        assertEquals("refund == sellValue (includes the Gift Card bonus)", moneyBefore + 6, rs.money)
    }

    @Test fun swashbucklerReflectsGiftCardSellValueBumps() {
        // Swashbuckler's mult = total sell value of jokers. Gift Card raises sell values each round, so
        // Swashbuckler must track that — its cached mult was only refreshed on buy/sell before this.
        val rs = RunState()
        rs.buy(offer("j_gift"), free = true)
        rs.buy(offer("j_swashbuckler", cost = 2), free = true)
        rs.buy(offer("j_cavendish", cost = 6), free = true)
        val sb = rs.owned.first { it.fj.key == "j_swashbuckler" }
        rs.enterRoundEval(); rs.cashOut()                       // Gift Card bumps every joker's sellBonus
        val expected = rs.owned.sumOf { rs.sellValue(it).toDouble() }
        assertEquals("Swashbuckler mult reflects the raised sell values", expected, sb.fj.mult, 0.0)
    }

    @Test fun thePsychicBlocksUnderFiveCardPlays() {
        // Control for the Luchador play()-shadow test: THE_PSYCHIC requires exactly 5 cards, so a 2-card
        // play is blocked (play() early-returns → nothing scores).
        val rs = RunState()
        rs.blindIndex = 2; rs.boss = Boss.THE_PSYCHIC
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertEquals("THE_PSYCHIC blocks a 2-card play → no score", 0.0, rs.roundScore, 0.0)
    }

    @Test fun luchadorDisablesThePsychicPlayGate() {
        // Selling Luchador disables THE_PSYCHIC, so the play() boss-gate (shadowed `boss`) lifts and a
        // 2-card hand scores. Verifies the play()-site shadow (scoreBank's was covered by the THE_TOOTH test).
        val rs = RunState()
        rs.blindIndex = 2; rs.boss = Boss.THE_PSYCHIC
        rs.buy(offer("j_luchador"), free = true)
        rs.sell(rs.owned.first { it.fj.key == "j_luchador" })
        assertTrue(rs.bossDisabled)
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertTrue("disabled THE_PSYCHIC lets a 2-card hand score", rs.roundScore > 0.0)
    }

    @Test fun cryBlurredAddsOneHandEachRound() {
        // Vanilla-style passive: cry-Blurred grants +1 hand each round (ease_hands_played, extra_hands=1).
        val rs = RunState()
        val base = rs.handsLeft                                 // init startRound, no Blurred yet
        rs.buy(offer("j_cry_blurred"), free = true)
        rs.phase = Phase.BLIND_SELECT; rs.selectBlind()         // re-run startRound with Blurred owned
        assertEquals("+1 hand each round", base + 1, rs.handsLeft)
    }

    @Test fun cryGardenforkPaysSevenForAceAndSeven() {
        val rs = RunState()
        rs.buy(offer("j_cry_gardenfork"), free = true)
        rs.money = 0
        rs.hand = listOf(PlayingCard(Suit.S, 14), PlayingCard(Suit.H, 7))   // Ace + 7
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertEquals("+\$7 when the played hand has an Ace and a 7", 7, rs.money)
    }

    @Test fun cryGardenforkPaysNothingWithoutBothRanks() {
        val rs = RunState()
        rs.buy(offer("j_cry_gardenfork"), free = true)
        rs.money = 0
        rs.hand = listOf(PlayingCard(Suit.S, 14), PlayingCard(Suit.H, 13))  // Ace + King, no 7
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1)
        rs.play(); rs.scoreBank()
        assertEquals("no \$ without both an Ace and a 7", 0, rs.money)
    }

    @Test fun cryHungerPaysThreePerConsumableUsed() {
        val rs = RunState()
        rs.buy(offer("j_cry_hunger"), free = true)
        rs.money = 0
        rs.consumables.clear()
        rs.consumables.add(Consumable.SpectralC(Spectral.BLACK_HOLE))   // Black Hole — no money effect of its own
        rs.useConsumable(0)
        assertEquals("+\$3 each time a consumable is used", 3, rs.money)
    }

    @Test fun cryLuckyJokerPaysFivePerLuckyTrigger() {
        // cry-Lucky Joker: +$5 per Lucky-card trigger. Reuses the Lucky enhancement — fresh RunState
        // seed 41, a heart flush whose 3rd card (index 2) is Lucky and triggers (1 trigger → +$5).
        val rs = RunState()
        rs.buy(offer("j_cry_lucky_joker"), free = true)
        rs.money = 0
        rs.hand = listOf(PlayingCard(Suit.H, 2), PlayingCard(Suit.H, 3),
                         PlayingCard(Suit.H, 5, Enhancement.LUCKY), PlayingCard(Suit.H, 7), PlayingCard(Suit.H, 9))
        rs.handSize = 5
        rs.phase = Phase.ROUND
        rs.selected = setOf(0, 1, 2, 3, 4)
        rs.play(); rs.scoreBank()
        assertEquals("+\$5 per Lucky trigger (1 trigger)", 5, rs.money)
    }
}
