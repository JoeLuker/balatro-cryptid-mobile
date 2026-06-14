package systems.balatro.game

import systems.balatro.engine.Engine
import systems.balatro.content.Jokers

/**
 * Proof the composition core scores correctly and order-dependently — runnable with
 * plain `kotlin Demo.kt` once the toolchain is up, no Android needed. This is the
 * shape every oracle baseline takes: build a board, score, compare to the original.
 */
object Demo {
    @JvmStatic
    fun main(args: Array<String>) {
        val engine = Engine()
        val effects = Effects()
        val run = ScoreRun(effects)

        // A board, left-to-right = registration order = scoring cascade order.
        Jokers.joker(engine.world, effects)       // +4 Mult
        Jokers.greenJoker(engine.world, effects)   // x1.5 Mult

        // Seed a base-chips effect (stands in for the played hand's chips).
        val base = engine.world.create()
        effects.register(base, setOf(Ctx.BEFORE)) { _, ctx -> ctx.tally.chips = BigValue.of(10) }

        val score = run.scoreHand(engine.world, playedCards = emptyList())

        // mult: 1 -> (+4) 5 -> (x1.5) 7.5 ; chips 10 ; score = 10 * 7.5 = 75
        val expected = 75.0
        check(score.v == expected) { "composition scoring wrong: got $score, expected $expected" }
        println("OK  score=$score (order-dependent cascade verified)")
    }
}
