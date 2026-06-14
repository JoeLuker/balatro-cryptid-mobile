package systems.balatro.game

import systems.balatro.engine.World

/**
 * Oracle-parity harness — the net that proves "rebuilt = scores like the original".
 * Runs scenarios through the Kotlin engine and asserts the score equals the value the
 * original LÖVE build recorded (test/score-oracle-baselines.txt). Runnable standalone:
 *   kotlinc <game+engine> -include-runtime -d o.jar && kotlin -cp o.jar systems.balatro.game.Oracle
 * As real jokers are ported, add ORACLE_JOKERS cases here against their recorded goldens.
 */
object Oracle {
    private data class Case(val name: String, val hand: List<PlayingCard>, val expected: Double, val jokers: (World, Effects) -> Unit = { _, _ -> })

    private val cases = listOf(
        // --- no-joker baselines (verifiable by hand, from the oracle file header) ---
        Case("FourOfAKind aces + K kicker", PlayingCard.hand("H_A", "S_A", "D_A", "C_A", "H_K"), 728.0),
        Case("StraightFlush A-K-Q-J-T spades", PlayingCard.hand("S_A", "S_K", "S_Q", "S_J", "S_T"), 1208.0),
        Case("FourOfAKind 2s + 3 kicker", PlayingCard.hand("H_2", "S_2", "D_2", "C_2", "H_3"), 476.0),
    )

    fun run(): Pair<Int, Int> {
        var pass = 0
        for (c in cases) {
            val world = World(); val effects = Effects(); c.jokers(world, effects)
            val score = ScoreRun(effects).scoreHand(world, c.hand)
            val ok = score.v == c.expected
            if (ok) pass++
            println("${if (ok) "PASS" else "FAIL"}  ${c.name}: got ${score.v} expected ${c.expected}")
        }
        println("oracle-parity: $pass/${cases.size}")
        return pass to cases.size
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val (pass, total) = run()
        if (pass != total) kotlin.system.exitProcess(1)
    }
}
