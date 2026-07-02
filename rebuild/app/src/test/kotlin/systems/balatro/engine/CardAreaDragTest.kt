package systems.balatro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drag-to-reorder engine semantics (cardarea.lua align_cards tail + moveable.lua:217 drag):
 *  a dragged card is exempt from align, follows the cursor with its grab offset, and xOrder
 *  reports the vanilla centre-x sort the owner uses to permute its source-of-truth list. */
class CardAreaDragTest {

    private fun area(kind: String = "joker", n: Int = 3): CardArea {
        val scene = SceneRegistry()
        val a = CardArea(scene, Transform(0.0, 0.0, 6.0, 2.0), kind, cardLimit = 5)
        repeat(n) { a.emplace(a.newCard()) }
        a.alignCards(GameClock(), reducedMotion = true)   // settle everyone to slots
        return a
    }

    @Test fun xOrderIsIdentityAtRest() {
        val a = area()
        assertEquals(listOf(0, 1, 2), a.xOrder())
        assertFalse(a.anyDragged())
    }

    @Test fun draggedCardIsExemptFromAlign() {
        val a = area()
        val c = a.cards[0]
        c.startDrag(c.T.x + 0.2, c.T.y + 0.3)
        c.dragTo(c.T.x + 5.2, c.T.y + 0.3)   // same grab point, cursor moved +5 → card at +5
        val draggedX = c.T.x
        a.alignCards(GameClock(), reducedMotion = true)
        assertEquals("align must not move a dragged card", draggedX, c.T.x, 1e-9)
        assertTrue(a.anyDragged())
        c.stopDrag()
        a.alignCards(GameClock(), reducedMotion = true)
        assertTrue("align owns the card again after release", c.T.x != draggedX)
    }

    @Test fun grabOffsetIsPreserved() {
        val a = area(n = 1)
        val c = a.cards[0]
        val startX = c.T.x
        c.startDrag(startX + 0.4, c.T.y + 0.1)      // grab 0.4 into the card
        c.dragTo(startX + 2.4, c.T.y + 0.1)          // cursor +2.0
        assertEquals(startX + 2.0, c.T.x, 1e-9)      // card followed by exactly the cursor delta
    }

    @Test fun xOrderReflectsADragPastANeighbor() {
        val a = area(n = 3)
        val first = a.cards[0]
        // drag card 0 past card 2's centre
        first.startDrag(first.T.x, first.T.y)
        first.dragTo(a.cards[2].T.x + CardArea.CARD_W, first.T.y)
        val ord = a.xOrder()
        assertEquals("dragged card sorts last by centre-x", listOf(1, 2, 0), ord)
    }

    @Test fun dragBetweenTwoCardsYieldsMiddleSlot() {
        val a = area(n = 3)
        val last = a.cards[2]
        last.startDrag(last.T.x, last.T.y)
        // park its centre between card 0 and card 1
        val mid = (a.cards[0].T.x + a.cards[1].T.x) / 2
        last.dragTo(mid, last.T.y)
        assertEquals(listOf(0, 2, 1), a.xOrder())
    }
}
