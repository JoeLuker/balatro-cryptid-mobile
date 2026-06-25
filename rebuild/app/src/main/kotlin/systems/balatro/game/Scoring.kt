package systems.balatro.game

/**
 * Score breakdown types shared by the faithful Score engine and the UI. (The composition cascade
 * that used to live here — Tally/Effects/Context/ScoreRun — is retired; Score.kt is the only engine.)
 */

/** A scored hand's breakdown, so UI can show the chips x mult = score cascade, not just the total. */
data class ScoreResult(
    val handType: HandType, val chips: Double, val mult: Double, val score: Double,
    val scoringHand: List<PlayingCard> = emptyList(),   // the cards that actually scored (Midas Mask gold-ifies scoring faces)
)

/** One visible step of the cascade — the running chips x mult after `label` resolved. */
data class ScoreStep(val label: String, val chips: Double, val mult: Double)
