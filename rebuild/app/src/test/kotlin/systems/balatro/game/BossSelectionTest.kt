package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Boss.next() — the get_new_boss() port (functions/common_events.lua:2338). Pins the three rules
 * the old pool() got wrong: per-boss min-ante eligibility, showdown blinds exactly on win_ante
 * multiples (ante 8 — NOT "ante 10+"), and the least-used (min_use) anti-repeat filter. Also pins
 * the ordinal<22 regression that silently dropped THE_PILLAR from the pool.
 */
class BossSelectionTest {
    private fun picks(ante: Int, used: Map<Boss, Int> = emptyMap(), seeds: LongRange = 0L..400L) =
        seeds.map { Boss.next(ante, used, Random(it)) }.toSet()

    @Test fun ante1DrawsOnlyMinAnte1Bosses() {
        val seen = picks(1)
        assertEquals(Boss.values().filter { !it.showdown && it.minAnte <= 1 }.toSet(), seen)
        assertTrue(Boss.THE_PILLAR in seen)     // regression: pool()'s ordinal<22 filter dropped it
        assertFalse(Boss.THE_OX in seen)        // min 6
        assertFalse(Boss.THE_PLANT in seen)     // min 4
        assertFalse(Boss.THE_WALL in seen)      // min 2
    }

    @Test fun ante6OpensTheFullRegularPool() {
        assertEquals(Boss.values().filter { !it.showdown }.toSet(), picks(6, seeds = 0L..800L))
    }

    @Test fun ante8IsTheShowdownAnte() {        // win_ante = 8: ante % 8 == 0 → finishers only
        assertEquals(Boss.values().filter { it.showdown }.toSet(), picks(8))
    }

    @Test fun antes9To15AreRegularAgainThen16IsShowdown() {   // endless cadence
        assertTrue(picks(9).none { it.showdown })
        assertTrue(picks(15).none { it.showdown })
        assertTrue(picks(16).all { it.showdown })
    }

    @Test fun leastUsedFilterCyclesTheWholePoolBeforeAnyRepeat() {
        val eligible = Boss.values().filter { !it.showdown && it.minAnte <= 2 }
        val used = mutableMapOf<Boss, Int>()
        val drawn = mutableListOf<Boss>()
        repeat(eligible.size) { i ->
            val b = Boss.next(2, used, Random(i * 7919L))
            used[b] = (used[b] ?: 0) + 1
            drawn += b
        }
        assertEquals(eligible.toSet(), drawn.toSet())   // every eligible boss exactly once
    }

    @Test fun aUsedBossIsExcludedUntilTheRestCatchUp() {   // reroll semantics: old pick deprioritized
        val used = mapOf(Boss.THE_HOOK to 1)
        assertFalse(Boss.THE_HOOK in picks(2, used))
    }

    @Test fun bossDollarsMatchVanillaConfig() {  // config.dollars: regular 5, showdown 8
        assertEquals(5, Boss.THE_HOOK.dollars)
        assertEquals(5, Boss.THE_WALL.dollars)
        assertEquals(8, Boss.VERDANT_LEAF.dollars)
        assertEquals(8, Boss.CERULEAN_BELL.dollars)
    }
}
