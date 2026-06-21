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
        val handsLeft: Int = -1, val discardsLeft: Int = -1, val bossBlind: Boolean = false,
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
        // --- vanilla flat jokers (faithful, now also catalogued) ---
        Case("Pair of aces + fibonacci (+8 Mult/ace)", PlayingCard.hand("S_A", "H_A"), 576.0, j(FJoker("j_fibonacci"))),
        Case("Pair of aces + half (+20 Mult, <=3 cards)", PlayingCard.hand("S_A", "H_A"), 704.0, j(FJoker("j_half"))),
        Case("Pair of aces + stuntman (+250 Chips)", PlayingCard.hand("S_A", "H_A"), 564.0, j(FJoker("j_stuntman"))),
        Case("Pair of Kings + triboulet (x2/King)", PlayingCard.hand("S_K", "H_K"), 240.0, j(FJoker("j_triboulet"))),
        // "+Chips if hand contains <type>" family: Clever fires on Two Pair containment (20 base + 34 card + 80) * 2.
        Case("TwoPair 10s/7s + clever (Two Pair present, +80 Chips)", PlayingCard.hand("S_T", "H_T", "S_7", "D_7"), 268.0, j(FJoker("j_clever"))),
        // "+Mult if hand contains <type>" family: Jolly on a Pair (32 chips * (2+8)), Mad on Two Pair (54 chips * (2+10)).
        Case("Pair of aces + jolly (Pair present, +8 Mult)", PlayingCard.hand("S_A", "H_A"), 320.0, j(FJoker("j_jolly"))),
        Case("TwoPair 10s/7s + mad (Two Pair present, +10 Mult)", PlayingCard.hand("S_T", "H_T", "S_7", "D_7"), 648.0, j(FJoker("j_mad"))),
        // Splash: every played card scores. Same High Card as above (16 with only the Ace) now scores all five:
        // (5 base + 11+10+10+10+9 card chips) * 1 = 55.
        Case("HighCard A K Q J 9 + splash (all 5 score)", PlayingCard.hand("S_A", "H_K", "D_Q", "C_J", "S_9"), 55.0, j(FJoker("j_splash"))),
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
        // --- Cryptid "type" jokers (fire on context.poker_hands present) ---
        Case("Pair + giggly (High Card present, +4 Mult)", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_cry_giggly"))),
        Case("FourOfAKind aces + nutty (+19 Mult)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A", "H_K"), 2704.0, j(FJoker("j_cry_nutty"))),
        Case("FourOfAKind aces + shrewd (+150 Chips)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A", "H_K"), 1778.0, j(FJoker("j_cry_shrewd"))),
        Case("StraightFlush spades + nuts (x5 Mult)", PlayingCard.hand("S_A", "S_K", "S_Q", "S_J", "S_T"), 6040.0, j(FJoker("j_cry_nuts"))),
        Case("HighCard 9 (+6 kicker) + nice (+420 Chips)", PlayingCard.hand("S_9", "H_6"), 434.0, j(FJoker("j_cry_nice"))),
        Case("Pair of Kings + mask (faces retrigger x3)", PlayingCard.hand("S_K", "H_K"), 180.0, j(FJoker("j_cry_mask"))),
        Case("Pair of aces + wee_fib (2 fibs -> +6 Mult)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_wee_fib"))),
        Case("Pair + Foil Joker + meteor (+75 Chips/Foil joker)", PlayingCard.hand("S_A", "H_A"), 942.0, j(FJoker("j_joker", edition = "Foil"), FJoker("j_cry_meteor"))),
        Case("TwoPair 2s/As + duos (x2.5 Mult)", PlayingCard.hand("S_2", "H_2", "S_A", "H_A"), 230.0, j(FJoker("j_cry_duos"))),
        Case("FullHouse As/Ks + home (x3.5 Mult)", PlayingCard.hand("S_A", "H_A", "D_A", "S_K", "H_K"), 1302.0, j(FJoker("j_cry_home"))),
        Case("TwoPair 2s/As + zooble (2 distinct ranks -> +2 Mult)", PlayingCard.hand("S_2", "H_2", "S_A", "H_A"), 184.0, j(FJoker("j_cry_zooble"))),
        Case("Pair + cursor @16 chips (read branch)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_cursor", chips = 16.0))),
        Case("Pair + eternalflame @x1.3 (read branch)", PlayingCard.hand("S_A", "H_A"), 83.0, j(FJoker("j_cry_eternalflame", x = 1.3))),
        Case("HighCard 7,2 diff suits + whip (+0.5 Xmult)", PlayingCard.hand("S_2", "H_7"), 18.0, j(FJoker("j_cry_whip"))),
        Case("Pair + big_cube (x6 Chips)", PlayingCard.hand("S_A", "H_A"), 384.0, j(FJoker("j_cry_big_cube"))),
        Case("Pair of 4s + antennastoheaven (2x4 -> x1.2 Chips)", PlayingCard.hand("S_4", "H_4"), 43.0, j(FJoker("j_cry_antennastoheaven"))),
        Case("Pair of aces + supercell (+15c x2c +15m x2m)", PlayingCard.hand("S_A", "H_A"), 3196.0, j(FJoker("j_cry_supercell"))),
        Case("Pair of aces + m @x=14 (1 Jolly sold)", PlayingCard.hand("S_A", "H_A"), 896.0, j(FJoker("j_cry_m", x = 14.0))),
        Case("Pair of aces + iterum (x2/card, +1 retrigger/card)", PlayingCard.hand("S_A", "H_A"), 1728.0, j(FJoker("j_cry_iterum"))),
        Case("Pair of aces + iterum + exponentia (emult from 4 xmult events)", PlayingCard.hand("S_A", "H_A"), 2619.0, j(FJoker("j_cry_iterum"), FJoker("j_cry_exponentia"))),
        Case("Pair + jtron + joker (Emult 1+1 joker -> mult^2)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_jtron"), FJoker("j_joker"))),
        // --- Cryptid accumulator-read jokers (run loop sets j.x/j.xc/j.mult; zero-defaults no-op) ---
        Case("Pair of aces + dropshot @x=1.4 (2 suit-hit hands)", PlayingCard.hand("S_A", "H_A"), 89.0, j(FJoker("j_cry_dropshot", x = 1.4))),
        Case("Pair of aces + chili_pepper @x=2.0 (2 end_of_round)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_chili_pepper", x = 2.0))),
        Case("Pair of aces + mondrian @x=1.25 (1 no-discard round)", PlayingCard.hand("S_A", "H_A"), 80.0, j(FJoker("j_cry_mondrian", x = 1.25))),
        Case("Pair of aces + spaceglobe @xc=1.4 (2 type-match hands)", PlayingCard.hand("S_A", "H_A"), 89.0, j(FJoker("j_cry_spaceglobe", xc = 1.4))),
        Case("Pair of aces + fading_joker @x=2.0 (perishable expired)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_fading_joker", x = 2.0))),
        Case("Pair of aces + poor_joker @mult=8 (2 rent payments)", PlayingCard.hand("S_A", "H_A"), 320.0, j(FJoker("j_cry_poor_joker", mult = 8.0))),
        Case("Pair of aces + keychange @x=1.5 (2 new hand types)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_keychange", x = 1.5))),
        // --- batch-15: flat/accumulator jokers ---
        Case("Pair of aces + kittyprinter (flat x2 Xmult)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_kittyprinter"))),
        Case("Pair of aces + clicked_cookie @chips=200 (+200 Chips)", PlayingCard.hand("S_A", "H_A"), 464.0, j(FJoker("j_cry_clicked_cookie", chips = 200.0))),
        Case("Pair of aces + monkey_dagger @chips=50 (+50 Chips)", PlayingCard.hand("S_A", "H_A"), 164.0, j(FJoker("j_cry_monkey_dagger", chips = 50.0))),
        Case("Pair of aces + unjust_dagger @x=1.5 (x1.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_unjust_dagger", x = 1.5))),
        Case("Pair of aces + jimball @x=1.5 (x1.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_jimball", x = 1.5))),
        Case("Pair of aces + pizza_slice @x=1.5 (x1.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_pizza_slice", x = 1.5))),
        Case("Pair of aces + wheelhope @x=1.5 (1 WoF trigger)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_wheelhope", x = 1.5))),
        Case("Pair of aces + fspinner @chips=30 (+30 Chips)", PlayingCard.hand("S_A", "H_A"), 124.0, j(FJoker("j_cry_fspinner", chips = 30.0))),
        Case("Pair of aces + pirate_dagger @xc=1.5 (x1.5 Xchips)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_pirate_dagger", xc = 1.5))),
        // --- batch-16: misc/exotic accumulator-read + triggered eMult ---
        Case("Pair of aces + happyhouse @n=1 (114+ hands played, Emult^4)", PlayingCard.hand("S_A", "H_A"), 512.0, j(FJoker("j_cry_happyhouse", n = 1))),
        Case("Pair of aces + verisimile @x=1.5 (pseudorandom hits)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_verisimile", x = 1.5))),
        Case("Pair of aces + duplicare @x=2.0 (2 scaling triggers)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_duplicare", x = 2.0))),
        Case("Pair of aces + formidiulosus @x=1.03 (3 candy jokers)", PlayingCard.hand("S_A", "H_A"), 65.0, j(FJoker("j_cry_formidiulosus", x = 1.03))),
        // --- batch-19: m.lua scoring jokers: foodm/mstack/biggestm/longboi ---
        Case("Pair of aces + foodm @mult=40 (perishable +40 Mult)", PlayingCard.hand("S_A", "H_A"), 1344.0, j(FJoker("j_cry_foodm", mult = 40.0))),
        Case("Pair of aces + mstack @n=1 (retrigger 1/card)", PlayingCard.hand("S_A", "H_A"), 108.0, j(FJoker("j_cry_mstack", n = 1))),
        Case("Pair of aces + biggestm @x=7,n=1 (x7 when active)", PlayingCard.hand("S_A", "H_A"), 448.0, j(FJoker("j_cry_biggestm", x = 7.0, n = 1))),
        Case("Pair of aces + longboi @x=2.0 (Xmult from monstermult)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_longboi", x = 2.0))),
        // --- batch-18: individual Xmult + joker_main Xmult/Emult accumulators ---
        Case("Pair of aces + caramel @x=1.75 (X1.75/card, 2 aces)", PlayingCard.hand("S_A", "H_A"), 196.0, j(FJoker("j_cry_caramel", x = 1.75))),
        Case("Pair of aces + clockwork @x=1.5 (accumulated Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_clockwork", x = 1.5))),
        Case("Pair of aces + starfruit @x=2.0 (Emult^2, default)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_starfruit", x = 2.0))),
        // --- batch-17: boss-blind gate, flat Xmult-halving, Astral-edition other_joker ---
        Case("Pair of aces + spy (flat x0.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 32.0, j(FJoker("j_cry_spy"))),
        Case("Pair of aces + apjoker on boss blind (x4 Xmult)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_apjoker")), bossBlind = true),
        Case("Pair of aces + universe + Astral joker (Emult^1.2)", PlayingCard.hand("S_A", "H_A"), 274.0, j(FJoker("j_joker", edition = "Astral"), FJoker("j_cry_universe"))),
        // --- hands/discards-remaining jokers (now threaded into the engine) ---
        Case("Pair + acrobat on last hand (x3 Mult)", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_acrobat")), handsLeft = 0),
        Case("Pair + mystic_summit at 0 discards (+15 Mult)", PlayingCard.hand("S_A", "H_A"), 544.0, j(FJoker("j_mystic_summit")), discardsLeft = 0),
        Case("Pair + cry_night on last hand (Emult mult^3)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_night")), handsLeft = 0),
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
            val score = Score.score(c.hand, c.jokers, c.held, c.level, c.debuff, c.handsLeft, c.discardsLeft, c.bossBlind).score
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
