package systems.balatro.game

import systems.balatro.content.Content
import systems.balatro.content.Edition
import systems.balatro.content.Editions
import systems.balatro.engine.World

/**
 * Oracle-parity harness — the net that proves "rebuilt = scores like the original".
 * Runs scenarios through the Kotlin engine and asserts the score equals the value the
 * original LÖVE build recorded (test/score-oracle-baselines.txt). Runnable standalone:
 *   kotlinc <game+engine> -include-runtime -d o.jar && kotlin -cp o.jar systems.balatro.game.Oracle
 * As real jokers are ported, add cases here against their recorded goldens.
 */
object Oracle {
    private data class Case(val name: String, val hand: List<PlayingCard>, val expected: Double, val jokers: (World, Effects) -> Unit = { _, _ -> }, val debuff: Debuff = Debuff.None)

    /** Instantiate ported jokers by original key, in board order — the exact loadout a baseline recorded. */
    private fun jk(vararg keys: String): (World, Effects) -> Unit =
        { w, e -> keys.forEach { Content.byKey.getValue(it)(w, e) } }

    private val cases = listOf(
        // --- no-joker baselines (verifiable by hand, from the oracle file header) ---
        Case("FourOfAKind aces + K kicker", PlayingCard.hand("H_A", "S_A", "D_A", "C_A", "H_K"), 728.0),
        Case("StraightFlush A-K-Q-J-T spades", PlayingCard.hand("S_A", "S_K", "S_Q", "S_J", "S_T"), 1208.0),
        Case("FourOfAKind 2s + 3 kicker", PlayingCard.hand("H_2", "S_2", "D_2", "C_2", "H_3"), 476.0),
        Case("HighCard A (K Q J 9 kickers)", PlayingCard.hand("S_A", "H_K", "D_Q", "C_J", "S_9"), 16.0),
        Case("Pair of aces", PlayingCard.hand("S_A", "H_A"), 64.0),
        // --- ported jokers, each against its recorded golden ---
        Case("Pair + j_joker (+4 Mult)", PlayingCard.hand("S_A", "H_A"), 192.0, jk("j_joker")),
        Case("Pair + 2x j_joker", PlayingCard.hand("S_A", "H_A"), 320.0, jk("j_joker", "j_joker")),
        Case("Pair + 3x j_joker", PlayingCard.hand("S_A", "H_A"), 448.0, jk("j_joker", "j_joker", "j_joker")),
        Case("Flush diamonds + j_greedy_joker", PlayingCard.hand("D_A", "D_K", "D_Q", "D_J", "D_9"), 1615.0, jk("j_greedy_joker")),
        Case("Pair + j_cry_cube (+6 Chips)", PlayingCard.hand("S_A", "H_A"), 76.0, jk("j_cry_cube")),
        Case("ThreeOfAKind 3s + j_cry_triplet_rhythm (x3 Mult)", PlayingCard.hand("S_3", "H_3", "D_3"), 351.0, jk("j_cry_triplet_rhythm")),
        Case("FourOfAKind 2s + j_cry_lightupthenight (x1.5 per 2/7)", PlayingCard.hand("S_2", "H_2", "D_2", "C_2", "S_3"), 2409.0, jk("j_cry_lightupthenight")),
        Case("TwoPair 2s/As + j_cry_weegaming (retrigger 2s)", PlayingCard.hand("S_2", "H_2", "S_A", "H_A"), 108.0, jk("j_cry_weegaming")),
        Case("FourOfAKind aces + j_cry_krustytheclown (scaling)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A", "S_K"), 786.0, jk("j_cry_krustytheclown")),
        Case("Pair + j_cry_brokenhome (x11.4 Mult)", PlayingCard.hand("S_A", "H_A"), 729.0, jk("j_cry_brokenhome")),
        Case("Pair + j_joker,j_cry_waluigi (cross-joker x2.5^2)", PlayingCard.hand("S_A", "H_A"), 1200.0, jk("j_joker", "j_cry_waluigi")),
        Case("Pair + j_cry_oldblueprint,j_joker (blueprint copy)", PlayingCard.hand("S_A", "H_A"), 320.0, jk("j_cry_oldblueprint", "j_joker")),
        Case("J,Q,K + j_cry_maximized (rank patch -> ToaK)", PlayingCard.hand("S_J", "H_Q", "D_K"), 180.0, jk("j_cry_maximized")),
        Case("Flush A-2-3-5-7 + j_cry_primus (Emult pow)", PlayingCard.hand("S_A", "S_2", "S_3", "S_5", "S_7"), 323.0, jk("j_cry_primus")),
        // editions (documented Balatro constants; foil +50 chips is confirmed in the baselines doc's edition note)
        Case("Pair + Foil Joker (+50 Chips)", PlayingCard.hand("S_A", "H_A"), 492.0, jokers = { w, e -> Editions.spawn(w, e, "j_joker", Edition.FOIL) }),
        Case("Pair + Holo Joker (+10 Mult)", PlayingCard.hand("S_A", "H_A"), 512.0, jokers = { w, e -> Editions.spawn(w, e, "j_joker", Edition.HOLO) }),
        Case("Pair + Poly Joker (x1.5 Mult)", PlayingCard.hand("S_A", "H_A"), 288.0, jokers = { w, e -> Editions.spawn(w, e, "j_joker", Edition.POLY) }),
        // planet cards: leveling a hand raises its base by (lChips, lMult). Pair: +15 chips, +1 mult per level.
        Case("Pair Lv2 of aces (Mercury x1)", PlayingCard.hand("S_A", "H_A"), 141.0, jokers = { w, _ -> Levels.ensure(w).levelUp(HandType.PAIR) }),
        Case("Pair Lv3 of aces (Mercury x2)", PlayingCard.hand("S_A", "H_A"), 248.0, jokers = { w, _ -> Levels.ensure(w).levelUp(HandType.PAIR, 2) }),
        // card enhancements (tarots): one ace enhanced. Bonus (10+11+11+30)*2=124; Mult 32*(2+4)=192; Glass 32*(2*2)=128.
        Case("Pair, Bonus ace (+30 Chips)", listOf(PlayingCard.parse("S_A").copy(enhancement = Enhancement.BONUS), PlayingCard.parse("H_A")), 124.0),
        Case("Pair, Mult ace (+4 Mult)", listOf(PlayingCard.parse("S_A").copy(enhancement = Enhancement.MULT), PlayingCard.parse("H_A")), 192.0),
        Case("Pair, Glass ace (x2 Mult)", listOf(PlayingCard.parse("S_A").copy(enhancement = Enhancement.GLASS), PlayingCard.parse("H_A")), 128.0),
        // boss debuffs. Flint halves base: Pair (10/2 + 22) * (2/2) = 27 * 1 = 27.
        Case("Pair of aces + The Flint", PlayingCard.hand("S_A", "H_A"), 27.0, debuff = Boss.THE_FLINT.scoringDebuff),
        // The Club: the club ace scores nothing -> (10 + 11) * 2 = 42.
        Case("Pair S_A,C_A + The Club", PlayingCard.hand("S_A", "C_A"), 42.0, debuff = Boss.THE_CLUB.scoringDebuff),
    )

    fun run(): Pair<Int, Int> {
        var pass = 0
        for (c in cases) {
            val world = World(); val effects = Effects(); c.jokers(world, effects)
            val score = ScoreRun(effects).scoreDetailed(world, c.hand, debuff = c.debuff).score
            val ok = score == c.expected
            if (ok) pass++
            println("${if (ok) "PASS" else "FAIL"}  ${c.name}: got $score expected ${c.expected}")
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
