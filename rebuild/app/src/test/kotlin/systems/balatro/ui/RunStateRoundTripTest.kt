package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import systems.balatro.content.Edition
import systems.balatro.game.FJoker
import systems.balatro.game.HandType
import systems.balatro.game.Planet
import systems.balatro.game.PlayingCard
import systems.balatro.game.Suit
import systems.balatro.game.Boss
import systems.balatro.save.RunSnapshot

/**
 * Save/load round-trip through the `RunState` ⇄ `RunSnapshot` bridge (`snapshot()` / `restore()`).
 *
 * `RunSnapshotTest` already proves the pure model encodes/decodes losslessly. This proves the BRIDGE —
 * that `snapshot()` captures, and `restore()` re-applies, every live run field. A field added to
 * `RunState`/`FJoker` but never wired into the bridge (e.g. the once-flagged "does `FJoker.n` survive
 * reload, or does an assigned rank reset?") is invisible to the model test and surfaces only here.
 */
class RunStateRoundTripTest {

    /** A run carrying non-default state across every bridged field. */
    private fun richRun(): RunState {
        val rs = RunState()
        rs.money = 42; rs.blindIndex = 5
        rs.owned.add(Owned(
            Offer("j_cry_bonk", "Bonk", "+1 Chip per Pair", 6, rarity = 2, edition = Edition.FOIL),
            FJoker("j_cry_bonk", mult = 3.0, edition = "cry_m", x = 2.5, chips = 88.0, n = 7, rarity = 2, xc = 4.0))
            .also { it.sellBonus = 9 })   // Gift Card extra_value — must survive reload
        rs.owned.add(Owned(Offer("j_joker", "Joker", "+4 Mult", 2), FJoker("j_joker")))
        rs.handLevels.setAll(mapOf(HandType.PAIR to 3, HandType.FLUSH to 2))
        rs.shopSlotsBonus = 1; rs.discountPercent = 25; rs.interestCap = 10
        rs.baseHands = 5; rs.baseDiscards = 4; rs.rerollBase = 3
        rs.redeemedVouchers.addAll(listOf("v_grabber", "v_overstock_norm"))
        rs.tags.add(Tag.INVESTMENT); rs.tags.add(Tag.JUGGLE)
        rs.consumables.add(Consumable.PlanetC(Planet.MERCURY))
        rs.baseHandSize = 9
        rs.consumableSlotsBonus = 2
        rs.jokersSold.add("j_gros_michel")
        return rs
    }

    @Test fun bridgeRoundTripIsLossless() {
        val a = richRun()
        val snap1 = a.snapshot()
        val b = RunState()
        b.restore(RunSnapshot.decode(snap1.encode()))   // through JSON, exactly as on-disk save/load does
        val snap2 = b.snapshot()
        // The deck is intentionally re-shuffled on load (Deck.reshuffle), so order is NOT preserved —
        // only the composition is. Compare it as a multiset.
        assertEquals("deck composition (multiset) must survive reload",
            snap1.deck.groupingBy { it }.eachCount(), snap2.deck.groupingBy { it }.eachCount())
        // Every other bridged field must be byte-identical after save -> load -> save.
        assertEquals(snap1.copy(deck = emptyList()), snap2.copy(deck = emptyList()))
    }

    @Test fun fjokerScalingStateSurvivesReload() {
        val a = richRun()
        val b = RunState()
        b.restore(RunSnapshot.decode(a.snapshot().encode()))
        assertEquals(a.owned.size, b.owned.size)
        // owned[0] is the starter Joker seeded in RunState.init; find the joker we gave rich state to.
        val origOwned = a.owned.first { it.fj.key == "j_cry_bonk" }
        val gotOwned = b.owned.first { it.fj.key == "j_cry_bonk" }
        val orig = origOwned.fj; val got = gotOwned.fj
        assertEquals(orig.mult, got.mult, 0.0)
        assertEquals(orig.x, got.x, 0.0)
        assertEquals(orig.chips, got.chips, 0.0)
        assertEquals("FJoker.n (assigned ranks / scaling counters) must survive reload", orig.n, got.n)
        assertEquals(orig.xc, got.xc, 0.0)
        assertEquals(orig.edition, got.edition)
        assertEquals(orig.rarity, got.rarity)
        assertEquals("the Offer edition (cosmetic) survives too", Edition.FOIL, gotOwned.offer.edition)
        assertEquals("Gift Card sell-value bonus (Owned.sellBonus) must survive reload", 9, gotOwned.sellBonus)
    }

    /** Run-lifetime counters (docs/REVIEW-2026-07-01.md: previously never persisted) must survive
     *  reload. Most have private setters, so this drives restore() from a handcrafted snapshot and
     *  asserts snapshot() reads the same values back — proving both directions of the bridge. */
    @Test fun runLifetimeCountersSurviveReload() {
        val snap = RunSnapshot(
            blindIndex = 5, money = 42, jokers = emptyList(), deck = emptyList(),
            handLevels = emptyMap(), shopSlotsBonus = 0, discountPercent = 0, interestCap = 5,
            baseHands = 4, baseDiscards = 3, rerollBase = 5,
            redeemedVouchers = emptyList(), tags = emptyList(),
            consumableSlotsBonus = 2,
            handPlayed = mapOf("PAIR" to 7, "FLUSH" to 2),
            totalHandsPlayed = 9, runHighScore = 1234.5,
            totalChipsScored = 9876.0, totalCardsPlayed = 41,
            totalCardsDiscarded = 17, totalCardsPurchased = 6,
            rerolls = 3, runSeed = "ABCD1234", jokersSold = listOf("j_joker", "j_cry_kidnap"),
            bossesUsed = mapOf("THE_HOOK" to 1), anteBossFor = 2, anteBoss = "THE_HOOK",
        )
        val b = RunState()
        b.restore(RunSnapshot.decode(snap.encode()))
        val out = b.snapshot()
        assertEquals(2, out.consumableSlotsBonus)
        assertEquals(mapOf("PAIR" to 7, "FLUSH" to 2), out.handPlayed)
        assertEquals(9, out.totalHandsPlayed)
        assertEquals(1234.5, out.runHighScore, 0.0)
        assertEquals(9876.0, out.totalChipsScored, 0.0)
        assertEquals(41, out.totalCardsPlayed)
        assertEquals(17, out.totalCardsDiscarded)
        assertEquals(6, out.totalCardsPurchased)
        assertEquals(3, out.rerolls)
        assertEquals("ABCD1234", out.runSeed)
        assertEquals(listOf("j_joker", "j_cry_kidnap"), out.jokersSold)
        assertEquals(mapOf("THE_HOOK" to 1), out.bossesUsed)
        assertEquals(2, out.anteBossFor)
        assertEquals("THE_HOOK", out.anteBoss)
    }

    /** A save taken mid-shop must resume the EXACT post-roll stock (jokers/planets/tarots, the voucher,
     *  the boosters) and the per-shop reroll state — not a fresh re-roll that re-offers bought cards. */
    @Test fun midShopRoundTripPreservesShopStock() {
        val a = RunState().apply { money = 30 }
        a.toShopForPreview()            // roll the real shop stock (items + voucher + boosters), phase = SHOP
        a.freeRerollThisShop = true
        a.couponThisShop = true
        a.reroll()                      // bump rerollIncrease and re-roll the live stock once
        val snap1 = a.snapshot()
        // we genuinely captured a populated mid-shop state, not an empty default
        assertEquals("SHOP", snap1.phase)
        assertTrue("shop stock populated", snap1.shopItems.isNotEmpty())

        val b = RunState()
        b.restore(RunSnapshot.decode(snap1.encode()))
        val snap2 = b.snapshot()
        assertEquals(snap1.deck.groupingBy { it }.eachCount(), snap2.deck.groupingBy { it }.eachCount())
        // shopItems / shopVoucher / shopBoosters / rerollIncrease / free-reroll / coupon / phase all survive.
        assertEquals(snap1.copy(deck = emptyList()), snap2.copy(deck = emptyList()))
    }
}

/** Mid-round resume (schema v2, parity blocker): vanilla checkpoints after every hand — killing
 *  the process mid-round must restore the exact hand, pile order, counters, and boss state. */
class MidRoundResumeTest {
    @Test fun midRoundSurvivesSnapshotRestore() {
        val rs = RunState()
        rs.phase = Phase.ROUND
        rs.hand = listOf(PlayingCard(Suit.S, 10), PlayingCard(Suit.H, 3), PlayingCard(Suit.D, 14))
        rs.handsLeft = 2; rs.discardsLeft = 1; rs.roundScore = 123.0
        rs.boss = Boss.THE_HOUSE; rs.bossDisabled = false
        rs.faceDown = setOf(0, 2)

        val snap1 = rs.snapshot()
        assertTrue("mid-round fields captured", snap1.roundHandsLeft == 2 && snap1.roundHand.size == 3)
        val rs2 = RunState()
        rs2.restore(RunSnapshot.decode(snap1.encode()))
        val snap2 = rs2.snapshot()

        assertEquals(Phase.ROUND, rs2.phase)
        assertEquals("hand survives by value", rs.hand, rs2.hand)
        assertEquals(2, rs2.handsLeft); assertEquals(1, rs2.discardsLeft)
        assertEquals(123.0, rs2.roundScore, 1e-9)
        assertEquals(Boss.THE_HOUSE, rs2.boss)
        assertEquals(setOf(0, 2), rs2.faceDown)
        assertEquals("pile order survives the bridge", snap1.roundDrawPile, snap2.roundDrawPile)
        assertEquals("selection cleared on resume (indices are not portable)", emptySet<Int>(), rs2.selected)
    }

    @Test fun v1SaveWithoutRoundFieldsFallsBackToBlindSelect() {
        val rs = RunState()
        rs.phase = Phase.ROUND
        // simulate a v1 snapshot: phase says ROUND but no round payload (roundHandsLeft = -1)
        val v1 = rs.snapshot().copy(roundHand = emptyList(), roundHandsLeft = -1)
        val rs2 = RunState()
        rs2.restore(RunSnapshot.decode(v1.encode()))
        assertEquals(Phase.BLIND_SELECT, rs2.phase)
    }
}
