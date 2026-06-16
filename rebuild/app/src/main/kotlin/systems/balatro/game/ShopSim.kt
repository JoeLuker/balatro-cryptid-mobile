package systems.balatro.game

import systems.balatro.content.Content
import systems.balatro.engine.World

/**
 * Headless proof of the composition payoff the shop depends on: a joker can LEAVE play
 * with one delete and leave NO residue. Buy a joker live (register into the running engine),
 * score, then sell it (effects.unregister + world.destroy) and score again — the score must
 * return EXACTLY to the pre-buy value. No override chain to unwind, no dangling effect; the
 * thing that made the LÖVE build's mid-teardown fragile cannot happen here.
 *   kotlinc <engine+game+content> -include-runtime -d s.jar && kotlin -cp s.jar systems.balatro.game.ShopSim
 */
object ShopSim {
    fun run(): Boolean {
        val w = World(); val e = Effects(); val run = ScoreRun(e)
        val hand = PlayingCard.hand("S_A", "H_A")          // pair of aces
        Content.loadout(w, e, listOf("j_joker"))           // +4 Mult
        val base = run.scoreDetailed(w, hand).score        // (10+22) * (2+4) = 192

        val cube = Content.byKey.getValue("j_cry_cube")(w, e)   // BUY +6 Chips, live
        val withCube = run.scoreDetailed(w, hand).score    // (32+6) * 6 = 228

        e.unregister(cube); w.destroy(cube)                // SELL — one delete each
        val afterSell = run.scoreDetailed(w, hand).score   // back to 192, exactly

        val ok = base == 192.0 && withCube == 228.0 && afterSell == base
        println("buy/sell: base=$base  +cube=$withCube  -cube=$afterSell  -> ${if (ok) "PASS (clean removal)" else "FAIL"}")
        return ok
    }

    @JvmStatic
    fun main(args: Array<String>) { if (!run()) kotlin.system.exitProcess(1) }
}
