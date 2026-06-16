package systems.balatro.game

/**
 * Oracle-parity harness — the net that proves "rebuilt = scores like the original". Each scenario
 * runs through the FAITHFUL Score engine (the 1:1 port of evaluate_play + calculate_joker) and
 * asserts the score equals the value the original LÖVE build recorded (test/score-oracle-baselines.txt).
 * Runnable standalone:
 *   kotlinc game content -include-runtime -d o.jar && kotlin -cp o.jar systems.balatro.game.Oracle
 */
object Oracle {
    private fun en(k: String, e: Enhancement) = PlayingCard.parse(k).copy(enhancement = e)
    private data class Case(
        val name: String, val hand: List<PlayingCard>, val expected: Double,
        val jokers: List<FJoker> = emptyList(), val level: Int = 1,
        val debuff: Debuff = Debuff.None, val held: List<PlayingCard> = emptyList(),
    )
    private fun j(vararg fj: FJoker) = fj.toList()

    private val cases = listOf(
        // --- no-joker baselines ---
        Case("FourOfAKind aces + K kicker", PlayingCard.hand("H_A", "S_A", "D_A", "C_A", "H_K"), 728.0),
        Case("StraightFlush A-K-Q-J-T spades", PlayingCard.hand("S_A", "S_K", "S_Q", "S_J", "S_T"), 1208.0),
        Case("FourOfAKind 2s + 3 kicker", PlayingCard.hand("H_2", "S_2", "D_2", "C_2", "H_3"), 476.0),
        Case("HighCard A (K Q J 9 kickers)", PlayingCard.hand("S_A", "H_K", "D_Q", "C_J", "S_9"), 16.0),
        Case("Pair of aces", PlayingCard.hand("S_A", "H_A"), 64.0),
        // --- vanilla jokers ---
        Case("Pair + j_joker (+4 Mult)", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_joker"))),
        Case("Pair + 2x j_joker", PlayingCard.hand("S_A", "H_A"), 320.0, j(FJoker("j_joker"), FJoker("j_joker"))),
        Case("Pair + 3x j_joker", PlayingCard.hand("S_A", "H_A"), 448.0, j(FJoker("j_joker"), FJoker("j_joker"), FJoker("j_joker"))),
        Case("Flush diamonds + j_greedy_joker", PlayingCard.hand("D_A", "D_K", "D_Q", "D_J", "D_9"), 1615.0, j(FJoker("j_greedy_joker"))),
        Case("Heart flush + Lusty", PlayingCard.hand("H_A", "H_K", "H_Q", "H_J", "H_9"), 1615.0, j(FJoker("j_lusty_joker"))),
        Case("Spade flush + Wrathful", PlayingCard.hand("S_A", "S_K", "S_Q", "S_J", "S_9"), 1615.0, j(FJoker("j_wrathful_joker"))),
        Case("Club flush + Gluttonous", PlayingCard.hand("C_A", "C_K", "C_Q", "C_J", "C_9"), 1615.0, j(FJoker("j_gluttenous_joker"))),
        Case("Pair of 2s + Even Steven", PlayingCard.hand("S_2", "H_2"), 140.0, j(FJoker("j_even_steven"))),
        Case("Pair of 3s + Odd Todd", PlayingCard.hand("S_3", "H_3"), 156.0, j(FJoker("j_odd_todd"))),
        Case("Pair of aces + Scholar", PlayingCard.hand("S_A", "H_A"), 720.0, j(FJoker("j_scholar"))),
        // --- Cryptid jokers ---
        Case("Pair + j_cry_cube (+6 Chips)", PlayingCard.hand("S_A", "H_A"), 76.0, j(FJoker("j_cry_cube"))),
        Case("ThreeOfAKind 3s + triplet_rhythm (x3)", PlayingCard.hand("S_3", "H_3", "D_3"), 351.0, j(FJoker("j_cry_triplet_rhythm"))),
        Case("FourOfAKind 2s + lightupthenight", PlayingCard.hand("S_2", "H_2", "D_2", "C_2", "S_3"), 2409.0, j(FJoker("j_cry_lightupthenight"))),
        Case("TwoPair 2s/As + weegaming (retrigger 2s)", PlayingCard.hand("S_2", "H_2", "S_A", "H_A"), 108.0, j(FJoker("j_cry_weegaming"))),
        Case("FourOfAKind aces + krustytheclown (scaling)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A", "S_K"), 786.0, j(FJoker("j_cry_krustytheclown", x = 1.0))),
        Case("Pair + brokenhome (x11.4)", PlayingCard.hand("S_A", "H_A"), 729.0, j(FJoker("j_cry_brokenhome"))),
        Case("Pair + joker,waluigi (cross-joker x2.5^2)", PlayingCard.hand("S_A", "H_A"), 1200.0, j(FJoker("j_joker"), FJoker("j_cry_waluigi"))),
        Case("Pair + oldblueprint,joker (blueprint copy)", PlayingCard.hand("S_A", "H_A"), 320.0, j(FJoker("j_cry_oldblueprint"), FJoker("j_joker"))),
        Case("J,Q,K + maximized (rank patch -> ToaK)", PlayingCard.hand("S_J", "H_Q", "D_K"), 180.0, j(FJoker("j_cry_maximized"))),
        Case("Flush A-2-3-5-7 + primus (Emult pow)", PlayingCard.hand("S_A", "S_2", "S_3", "S_5", "S_7"), 323.0, j(FJoker("j_cry_primus", x = 1.01))),
        // --- editions ---
        Case("Pair + Foil Joker (+50 Chips)", PlayingCard.hand("S_A", "H_A"), 492.0, j(FJoker("j_joker", edition = "Foil"))),
        Case("Pair + Holo Joker (+10 Mult)", PlayingCard.hand("S_A", "H_A"), 512.0, j(FJoker("j_joker", edition = "Holo"))),
        Case("Pair + Poly Joker (x1.5 Mult)", PlayingCard.hand("S_A", "H_A"), 288.0, j(FJoker("j_joker", edition = "Poly"))),
        // --- planet levels ---
        Case("Pair Lv2 of aces", PlayingCard.hand("S_A", "H_A"), 141.0, level = 2),
        Case("Pair Lv3 of aces", PlayingCard.hand("S_A", "H_A"), 248.0, level = 3),
        // --- card enhancements ---
        Case("Pair, Bonus ace (+30 Chips)", listOf(en("S_A", Enhancement.BONUS), PlayingCard.parse("H_A")), 124.0),
        Case("Pair, Mult ace (+4 Mult)", listOf(en("S_A", Enhancement.MULT), PlayingCard.parse("H_A")), 192.0),
        Case("Pair, Glass ace (x2 Mult)", listOf(en("S_A", Enhancement.GLASS), PlayingCard.parse("H_A")), 128.0),
        // --- boss debuffs ---
        Case("Pair of aces + The Flint", PlayingCard.hand("S_A", "H_A"), 27.0, debuff = Debuff.Flint),
        Case("Pair S_A,C_A + The Club", PlayingCard.hand("S_A", "C_A"), 42.0, debuff = Debuff.DebuffSuit(Suit.C)),
        // --- steel held ---
        Case("Pair of aces + 1 steel held", PlayingCard.hand("S_A", "H_A"), 96.0, held = listOf(en("S_K", Enhancement.STEEL))),
        Case("Pair of aces + 2 steel held", PlayingCard.hand("S_A", "H_A"), 144.0, held = listOf(en("S_K", Enhancement.STEEL), en("D_K", Enhancement.STEEL))),
        // --- wild / seal / stone ---
        Case("4 hearts + wild spade = Flush", listOf(PlayingCard.parse("H_A"), PlayingCard.parse("H_K"), PlayingCard.parse("H_Q"), PlayingCard.parse("H_J"), en("S_9", Enhancement.WILD)), 340.0),
        Case("4 hearts + plain spade = High Card", PlayingCard.hand("H_A", "H_K", "H_Q", "H_J", "S_9"), 16.0),
        Case("Pair, red-seal ace retriggers", listOf(PlayingCard.parse("S_A"), PlayingCard.parse("H_A").copy(seal = Seal.RED)), 86.0),
        Case("Pair of aces + a stone card", listOf(PlayingCard.parse("S_A"), PlayingCard.parse("H_A"), en("D_5", Enhancement.STONE)), 164.0),
    )

    fun run(): Pair<Int, Int> {
        var pass = 0
        for (c in cases) {
            val score = Score.score(c.hand, c.jokers, c.held, c.level, c.debuff).score
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
