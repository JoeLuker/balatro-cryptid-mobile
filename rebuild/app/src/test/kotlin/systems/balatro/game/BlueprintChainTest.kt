package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Adversarial verification of Blueprint / Brainstorm copy CHAINS — order-dependent interactions made
 * reachable now that jokers can be reordered in the play HUD. Blueprint copies the joker to its RIGHT;
 * Brainstorm copies the LEFTMOST joker; a joker never copies itself; a copy of a copy chains through to
 * the real joker (bounded by board size). Expected values derived from the single-Blueprint baseline
 * (Pair of Aces = 32 chips × mult; +4 Mult Joker), independent of the implementation.
 */
class BlueprintChainTest {
    private fun pairAces(vararg jokers: FJoker): Double =
        Score.score(PlayingCard.hand("S_A", "H_A"), jokers.toList()).score

    @Test fun blueprintChainAppliesTargetOncePerCopy() {
        // [Blueprint, Blueprint, Joker]: left BP copies mid BP (→ Joker), mid BP copies Joker, Joker itself.
        // Joker (+4 Mult) applied 3× → mult 2 + 12 = 14; chips 32 → 448.
        assertEquals(448.0, pairAces(FJoker("j_blueprint"), FJoker("j_blueprint"), FJoker("j_joker")), 0.0)
    }

    @Test fun brainstormLeftmostCopiesItselfIsSkipped() {
        // [Brainstorm, Joker]: Brainstorm is leftmost → its copy target is itself → skipped (no self-copy).
        // Only the Joker scores: mult 2 + 4 = 6; chips 32 → 192.
        assertEquals(192.0, pairAces(FJoker("j_brainstorm"), FJoker("j_joker")), 0.0)
    }

    @Test fun blueprintWithNoJokerToRightCopiesNothing() {
        // [Joker, Blueprint, Blueprint]: rightmost BP has no target; mid BP copies the rightmost BP (→ nothing).
        // Only the Joker scores: mult 2 + 4 = 6; chips 32 → 192.
        assertEquals(192.0, pairAces(FJoker("j_joker"), FJoker("j_blueprint"), FJoker("j_blueprint")), 0.0)
    }
}
