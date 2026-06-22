package systems.balatro.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Oracle tests for j_pareidolia (all id>0 cards count as face; card.lua:1217).
 *
 * Pareidolia is a board flag detected at score() entry — it propagates through ctx.pareidolia
 * to every isFace(ctx.pareidolia) call in calcJoker, covering:
 *   - j_scary_face: +30 chips per face (individual)
 *   - j_smiley: +5 mult per face (individual)
 *   - j_sock_and_buskin: +1 rep per face card (repetition)
 *   - j_cry_mask: +3 reps per face card (repetition)
 *   - j_cry_exposed: +2 reps per NON-face card (repetition) — nullified when all are face
 *
 * Stone (id=-1) and Abstract (id=-2) have id ≤ 0 and are still not face even with Pareidolia
 * (card.lua:1217: condition is "id > 0"; Abstract also explicitly returns nil at line 1213).
 *
 * All baseline scores (no Pareidolia) are confirmed by existing oracle tests.
 */
class PareidoliaTest {

    // Pair of aces: non-face cards (id=14, not in 11..13 without Pareidolia).
    private val pairAces = PlayingCard.hand("S_A", "H_A")
    // Pair of 2s: non-face cards (id=2).
    private val pair2s = PlayingCard.hand("S_2", "H_2")

    // -------------------------------------------------------------------------
    // j_scary_face (+30 Chips per face) + j_pareidolia:
    //   Pair aces base: chips=10, mult=2. Without Pareidolia: Aces are not face → scary_face=0.
    //   With Pareidolia: Aces (id=14 > 0) are face → +30 chips each trigger.
    //     A♠: chips += 11+30=41 → chips=51. A♥: chips += 11+30=41 → chips=92. score=floor(92*2)=184.
    // -------------------------------------------------------------------------
    @Test fun pareidolia_makes_non_face_trigger_scary_face() {
        val result = Score.score(pairAces, listOf(FJoker("j_scary_face"), FJoker("j_pareidolia")))
        assertEquals(184.0, result.score, 0.0)
    }

    @Test fun scary_face_ignores_non_face_without_pareidolia() {
        // Aces are not face → scary_face is a no-op. Baseline pair of aces = 64.
        val result = Score.score(pairAces, listOf(FJoker("j_scary_face")))
        assertEquals(64.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // j_sock_and_buskin (+1 rep per face card) + j_pareidolia:
    //   Pair aces without Pareidolia: Aces not face → no retrigger. score=64.
    //   With Pareidolia: Aces are face → each fires twice.
    //     chips=10 + 11*2 + 11*2 = 54, mult=2, score=108.
    // -------------------------------------------------------------------------
    @Test fun pareidolia_makes_sock_and_buskin_retrigger_non_face_cards() {
        val result = Score.score(pairAces, listOf(FJoker("j_sock_and_buskin"), FJoker("j_pareidolia")))
        assertEquals(108.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // j_cry_mask (+3 reps per face card) + j_pareidolia:
    //   Pair 2s without Pareidolia: 2s not face → no retrigger. score=28.
    //   With Pareidolia: 2s (id=2 > 0) are face → each fires 4× (1+3).
    //     chips=10 + 2*4 + 2*4 = 26, mult=2, score=52.
    // -------------------------------------------------------------------------
    @Test fun pareidolia_makes_cry_mask_retrigger_low_cards() {
        val result = Score.score(pair2s, listOf(FJoker("j_cry_mask"), FJoker("j_pareidolia")))
        assertEquals(52.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // j_cry_exposed (+2 reps per NON-face) + j_pareidolia:
    //   Pair aces without Pareidolia: Aces are not face (id=14 ∉ 11..13) → exposed fires +2 reps.
    //     chips=10 + 11*3 + 11*3 = 76, mult=2, score=152.
    //   With Pareidolia: Aces become face → exposed sees face → NO retrigger. score=64.
    // -------------------------------------------------------------------------
    @Test fun pareidolia_suppresses_cry_exposed_retrigger_on_non_face_cards() {
        // Without Pareidolia: aces are non-face, exposed fires +2 reps each.
        val without = Score.score(pairAces, listOf(FJoker("j_cry_exposed")))
        assertEquals(152.0, without.score, 0.0)
        // With Pareidolia: aces become face, exposed gets nothing.
        val with = Score.score(pairAces, listOf(FJoker("j_cry_exposed"), FJoker("j_pareidolia")))
        assertEquals(64.0, with.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // Stone card is NOT affected by Pareidolia (id=-1 ≤ 0, excluded by card.lua:1217 id>0 check).
    // -------------------------------------------------------------------------
    @Test fun pareidolia_does_not_make_stone_a_face_card() {
        val stoneCard = PlayingCard.parse("D_5").copy(enhancement = Enhancement.STONE)
        // Stone card always scores (+50 chips). With Pareidolia + scary_face: if stone were face,
        // scary_face would add +30 chips. Stone is NOT face → scary_face=0.
        // High Card base: chips=5, mult=1. Stone: chips+=50 → chips=55. score=55.
        val result = Score.score(listOf(stoneCard), listOf(FJoker("j_scary_face"), FJoker("j_pareidolia")))
        assertEquals(55.0, result.score, 0.0)
    }

    // -------------------------------------------------------------------------
    // Abstract card is NOT affected by Pareidolia (id=-2 ≤ 0, also force_no_face=true).
    // card.lua:1213 explicitly returns nil for Abstract before the Pareidolia check.
    // -------------------------------------------------------------------------
    @Test fun pareidolia_does_not_make_abstract_a_face_card() {
        val abstractCard = PlayingCard.parse("D_J").copy(enhancement = Enhancement.ABSTRACT)
        // Abstract J♦: id=-2, not face even with Pareidolia. scary_face fires 0 times.
        // High Card base: chips=5, mult=1. Abstract: chips+=0, eMult=1.15 → mult=1^1.15=1. score=5.
        val result = Score.score(listOf(abstractCard), listOf(FJoker("j_scary_face"), FJoker("j_pareidolia")))
        assertEquals(5.0, result.score, 0.0)
    }
}
