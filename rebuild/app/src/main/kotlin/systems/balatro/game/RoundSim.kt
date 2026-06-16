package systems.balatro.game

import systems.balatro.content.Content
import systems.balatro.engine.World

/**
 * Headless proof of the round's signature mechanic: ONE engine instance scores many hands,
 * so per-joker scaling state persists across hands (krusty's x_mult climbs). Replaying the
 * SAME hand must score strictly higher each time. This is what makes the round loop in the
 * UI faithful — and it can be checked with no phone, no Compose, just the engine.
 *   kotlinc <engine+game+content> -include-runtime -d r.jar && kotlin -cp r.jar systems.balatro.game.RoundSim
 */
object RoundSim {
    fun run(): Boolean {
        val world = World(); val effects = Effects()
        Content.loadout(world, effects, listOf("j_joker", "j_cry_krustytheclown", "j_cry_cube"))
        val run = ScoreRun(effects)
        val hand = PlayingCard.hand("S_A", "H_A")     // same hand every time
        var prev = -1.0; var ok = true
        for (i in 1..4) {
            val r = run.scoreDetailed(world, hand)
            val rising = r.score > prev
            if (!rising) ok = false
            println("${if (rising) "OK  " else "FAIL"} hand $i: chips=${r.chips} mult=${r.mult} score=${r.score}")
            prev = r.score
        }
        println("round-scaling: ${if (ok) "PASS (krusty persists across hands)" else "FAIL"}")
        return ok
    }

    @JvmStatic
    fun main(args: Array<String>) { if (!run()) kotlin.system.exitProcess(1) }
}
