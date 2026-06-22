package systems.balatro.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** P4 RunStateSerialization: the run snapshot must round-trip losslessly through JSON. Pure (no
 *  Compose/Android), so it runs as a fast off-device unit test — the verification gate the flaky
 *  emulator can't give. */
class RunSnapshotTest {

    @Test fun roundTripsAFullRun() {
        val snap = RunSnapshot(
            blindIndex = 7, money = 23,
            jokers = listOf(
                JokerSnap("j_joker", "Joker", "+4 Mult", 2, "FOIL", "Foil", 0.0, 1.0, 0.0, 0, 1, 1.0),
                JokerSnap("j_cry_cursor", "Cursor", "+8 Chips per buy", 6, "NEGATIVE", "", 0.0, 1.0, 88.0, 3, 2, 1.0),
            ),
            deck = listOf(CardSnap("S", 14, "GLASS", "RED"), CardSnap("H", 7, "NONE", "NONE")),
            handLevels = mapOf("PAIR" to 3, "FLUSH" to 2),
            shopSlotsBonus = 1, discountPercent = 25, interestCap = 10, baseHands = 5, baseDiscards = 4, rerollBase = 3,
            redeemedVouchers = listOf("v_overstock_norm", "v_grabber"),
            tags = listOf("INVESTMENT", "JUGGLE"),
        )
        assertEquals(snap, RunSnapshot.decode(snap.encode()))
    }

    @Test fun roundTripsAFreshRun() {
        val snap = RunSnapshot(0, 4, emptyList(), emptyList(), emptyMap(), 0, 0, 5, 4, 3, 5, emptyList(), emptyList())
        assertEquals(snap, RunSnapshot.decode(snap.encode()))
    }

    @Test fun savesLoadsAndDeletesOnDisk() {
        val tmp = File.createTempFile("balatro_run", ".json")
        val snap = RunSnapshot(3, 12, emptyList(), listOf(CardSnap("S", 14, "GLASS", "RED")),
            mapOf("PAIR" to 2), 1, 0, 5, 4, 3, 5, emptyList(), listOf("D_SIX"))
        SaveIo.write(tmp, snap.encode())
        assertEquals(snap, RunSnapshot.decode(SaveIo.read(tmp)!!))
        SaveIo.delete(tmp)
        assertNull(SaveIo.read(tmp))
        tmp.delete()
    }
}
