package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hand leveling — the `HandLevels` store, the `Planet → HandType` targets, and the level → scored-base
 * effect (a planet card raises a hand's base chips/mult by its per-level increments). The store and the
 * Planet map are plain data the oracle never exercises directly; the level→score case pins the
 * `baseChips + (L-1)·lChips` formula through the real scoring entry point.
 */
class LevelsTest {

    // ── HandLevels store ──────────────────────────────────────────────────────────────────────────
    @Test fun levelsStartAtOneAndLevelUpIndependently() {
        val h = HandLevels()
        assertEquals("unseen hand defaults to level 1", 1, h.level(HandType.PAIR))
        h.levelUp(HandType.PAIR)
        assertEquals(2, h.level(HandType.PAIR))
        h.levelUp(HandType.PAIR, by = 3)
        assertEquals(5, h.level(HandType.PAIR))
        assertEquals("other hands untouched", 1, h.level(HandType.FLUSH))
    }

    @Test fun degradeFloorsAtOne() {
        val h = HandLevels()
        h.levelUp(HandType.STRAIGHT, by = 2)                         // level 3
        h.degrade(HandType.STRAIGHT)
        assertEquals(2, h.level(HandType.STRAIGHT))
        h.degrade(HandType.STRAIGHT); h.degrade(HandType.STRAIGHT)  // 1, then clamp
        assertEquals("THE_ARM never drops a hand below level 1", 1, h.level(HandType.STRAIGHT))
    }

    @Test fun allAndSetAllRoundTripTheLevels() {
        val h = HandLevels()
        h.setAll(mapOf(HandType.PAIR to 3, HandType.FLUSH to 2))
        assertEquals(mapOf(HandType.PAIR to 3, HandType.FLUSH to 2), h.all())
        assertEquals(3, h.level(HandType.PAIR))
    }

    // ── Planet → hand mapping ───────────────────────────────────────────────────────────────────────
    @Test fun everyPlanetTargetsTheRightHand() {
        assertEquals(HandType.HIGH_CARD, Planet.PLUTO.hand)
        assertEquals(HandType.PAIR, Planet.MERCURY.hand)
        assertEquals(HandType.TWO_PAIR, Planet.URANUS.hand)
        assertEquals(HandType.THREE_OF_A_KIND, Planet.VENUS.hand)
        assertEquals(HandType.STRAIGHT, Planet.SATURN.hand)
        assertEquals(HandType.FLUSH, Planet.JUPITER.hand)
        assertEquals(HandType.FULL_HOUSE, Planet.EARTH.hand)
        assertEquals(HandType.FOUR_OF_A_KIND, Planet.MARS.hand)
        assertEquals(HandType.STRAIGHT_FLUSH, Planet.NEPTUNE.hand)
    }

    // ── level → score (a planet upgrade raises the played hand's base) ──────────────────────────────
    @Test fun handLevelRaisesTheScoredBase() {
        val pairAces = PlayingCard.hand("S_A", "H_A")
        // level 1: PAIR base (10 chips, 2 mult) + 2 Aces (22 chips) = 32 × 2 = 64
        assertEquals(64.0, Score.score(pairAces, emptyList(), level = 1).score, 0.0)
        // level 3: base raised to (10 + 2·15 = 40 chips, 2 + 2·1 = 4 mult) + 22 = 62 × 4 = 248
        assertEquals(248.0, Score.score(pairAces, emptyList(), level = 3).score, 0.0)
    }
}
