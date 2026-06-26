package systems.balatro.save

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure win-rate derivation on the lifetime Stats (the SharedPreferences IO needs a Context, so
 *  only the math is unit-tested; the store round-trip is verified on-device). */
class StatsStoreTest {
    @Test fun winRateIsZeroWithNoGames() {
        assertEquals(0, StatsStore.Stats(games = 0, wins = 0).winRate)
    }

    @Test fun winRateIsIntegerPercent() {
        assertEquals(50, StatsStore.Stats(games = 4, wins = 2).winRate)
        assertEquals(33, StatsStore.Stats(games = 3, wins = 1).winRate)   // floors
        assertEquals(100, StatsStore.Stats(games = 5, wins = 5).winRate)
    }
}
