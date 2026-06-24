package systems.balatro.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import systems.balatro.game.HandType

/**
 * Skip-tag effects fired through applyTags (pure RunState; runs off-device). Covers the non-joker
 * economy/hand-level tags — nothing else pins them, so a wrong cap or level delta is silent.
 */
class TagEffectTest {
    private val STD = listOf(
        HandType.HIGH_CARD, HandType.PAIR, HandType.TWO_PAIR, HandType.THREE_OF_A_KIND,
        HandType.STRAIGHT, HandType.FLUSH, HandType.FULL_HOUSE, HandType.FOUR_OF_A_KIND,
        HandType.STRAIGHT_FLUSH, HandType.FIVE_OF_A_KIND, HandType.FLUSH_HOUSE, HandType.FLUSH_FIVE,
    )

    @Test fun economyDoublesMoney() {
        val rs = RunState().apply { money = 10; tags.add(Tag.ECONOMY) }
        rs.applyTags(TagTrigger.SHOP_START)
        assertEquals(20, rs.money)                 // +min(10,40)
    }

    @Test fun economyBonusCapsAt40() {
        val rs = RunState().apply { money = 100; tags.add(Tag.ECONOMY) }
        rs.applyTags(TagTrigger.SHOP_START)
        assertEquals(140, rs.money)                // +min(100,40) = +40
    }

    @Test fun orbitalUpgradesOneHandByThreeLevels() {
        val rs = RunState().apply { tags.add(Tag.ORBITAL) }
        val before = STD.sumOf { rs.handLevels.level(it) }
        rs.applyTags(TagTrigger.ROUND_START)
        assertEquals(before + 3, STD.sumOf { rs.handLevels.level(it) })
    }

    @Test fun tagIsConsumedAfterFiring() {
        val rs = RunState().apply { money = 5; tags.add(Tag.ECONOMY) }
        rs.applyTags(TagTrigger.SHOP_START)
        rs.applyTags(TagTrigger.SHOP_START)        // second fire must be a no-op (tag removed)
        assertEquals(10, rs.money)
    }
}
