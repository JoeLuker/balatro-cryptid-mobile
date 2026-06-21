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
        // --- hand-detection hooks: each flips the evaluated hand vs the same cards with no joker ---
        // Four Fingers: 4 hearts (A,K,Q,9 — not a 4-run, so Flush not Straight Flush) = Flush (35 base + 11+10+10+9) * 4.
        Case("4 hearts + four_fingers = Flush", PlayingCard.hand("H_A", "H_K", "H_Q", "H_9"), 300.0, j(FJoker("j_four_fingers"))),
        // Shortcut: 5-6-7-9-10 (one gap) = Straight (30 base + 10+9+7+6+5) * 4.
        Case("5,6,7,9,10 + shortcut = Straight", PlayingCard.hand("S_T", "H_9", "D_7", "C_6", "S_5"), 268.0, j(FJoker("j_shortcut"))),
        // Smeared: 3 hearts + 2 diamonds (all "red") = Flush (35 base + 11+10+10+10+9) * 4.
        Case("3H+2D + smeared = Flush", PlayingCard.hand("H_A", "H_K", "H_Q", "D_J", "D_9"), 340.0, j(FJoker("j_smeared"))),
        // --- face / retrigger hooks ---
        // Pareidolia: aces aren't faces, but Pareidolia makes them count → Scary Face fires (+30 each): (10 base + 22 card + 60) * 2.
        Case("Pair of aces + scary_face + pareidolia (+30/ace)", PlayingCard.hand("S_A", "H_A"), 184.0, j(FJoker("j_scary_face"), FJoker("j_pareidolia"))),
        // Sock and Buskin: each face retriggers once → Kings score twice: (10 base + 2*(10+10) card) * 2.
        Case("Pair of Kings + sock_and_buskin (faces 2x)", PlayingCard.hand("S_K", "H_K"), 100.0, j(FJoker("j_sock_and_buskin"))),
        // Hanging Chad: first scored card retriggers twice (3x total): (10 base + (3*11 + 1*11) card) * 2.
        Case("Pair of aces + hanging_chad (first card 3x)", PlayingCard.hand("S_A", "H_A"), 108.0, j(FJoker("j_hanging_chad"))),
        // Interaction: Pareidolia makes aces faces, so Sock and Buskin retriggers both (each 2x): (10 + 2*(11+11)) * 2.
        Case("Pair of aces + sock_and_buskin + pareidolia (all 2x)", PlayingCard.hand("S_A", "H_A"), 108.0, j(FJoker("j_sock_and_buskin"), FJoker("j_pareidolia"))),
        // Dusk: retriggers every played card once on last hand (handsLeft=0); same math as hanging_chad: (10 + 2*11 + 2*11) * 2.
        Case("Pair of aces + dusk (last hand, all 2x)", PlayingCard.hand("S_A", "H_A"), 108.0, j(FJoker("j_dusk")), handsLeft = 0),
        // Hack: retriggers 2/3/4/5 once each — Pair of 2s: (10 + 2*2 + 2*2) * 2.
        Case("Pair of 2s + hack (retrigger 2s)", PlayingCard.hand("S_2", "H_2"), 36.0, j(FJoker("j_hack"))),
        // Mime: retriggers held cards that produce an effect; Steel card held fires X1.5 twice → mult^2:
        // chips=(10+11+11)=32, mult=2*1.5*1.5=4.5, score=floor(32*4.5)=144.
        Case("Pair of aces + mime (steel King held retriggers)", PlayingCard.hand("S_A", "H_A"), 144.0, j(FJoker("j_mime")), held = listOf(en("S_K", Enhancement.STEEL))),
        // --- individual-card jokers (coverage baselines) ---
        // Photograph: X2 on FIRST face only — Pair of Kings: X2 fires on S_K, not H_K: (10+10+10)*4=120.
        Case("Pair of Kings + photograph (X2 first face)", PlayingCard.hand("S_K", "H_K"), 120.0, j(FJoker("j_photograph"))),
        // Smiley: +5 Mult per scored face — Pair of Kings: (10+10+10)*(2+5+5)=360.
        Case("Pair of Kings + smiley (+5 Mult/face)", PlayingCard.hand("S_K", "H_K"), 360.0, j(FJoker("j_smiley"))),
        // Walkie Talkie: 10 or 4 → +10 Chips +4 Mult — Pair of 10s: (10+20+20)*(2+4+4)=500.
        Case("Pair of 10s + walkie_talkie", PlayingCard.hand("S_T", "H_T"), 500.0, j(FJoker("j_walkie_talkie"))),
        // Seeing Double: X2 if Club + non-Club both score — S_A + C_A Pair: (10+11+11)*4=128.
        Case("Pair S_A+C_A + seeing_double (club+non-club X2)", PlayingCard.hand("S_A", "C_A"), 128.0, j(FJoker("j_seeing_double"))),
        // Flower Pot: X3 if all 4 suits score — FoaK aces (all 4 suits): (60+44)*21=2184.
        Case("FoaK aces + flower_pot (all 4 suits X3)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A"), 2184.0, j(FJoker("j_flower_pot"))),
        // Exposed: +2 retriggers per non-face — Pair of 2s (non-faces): (10+3*2+3*2)*2=44.
        Case("Pair of 2s + cry_exposed (+2 retriggers/non-face)", PlayingCard.hand("S_2", "H_2"), 44.0, j(FJoker("j_cry_exposed"))),
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
        // Photograph + spade debuff: debuffed S_K is never a face, so X2 lands on the non-debuffed H_K: (10 + 10) * (2 * 2) = 80.
        Case("Pair S_K,H_K + photograph under The Goad (X2 on H_K)", PlayingCard.hand("S_K", "H_K"), 80.0, j(FJoker("j_photograph")), debuff = Debuff.DebuffSuit(Suit.S)),
        // --- steel held ---
        Case("Pair of aces + 1 steel held", PlayingCard.hand("S_A", "H_A"), 96.0, held = listOf(en("S_K", Enhancement.STEEL))),
        Case("Pair of aces + 2 steel held", PlayingCard.hand("S_A", "H_A"), 144.0, held = listOf(en("S_K", Enhancement.STEEL), en("D_K", Enhancement.STEEL))),
        // --- wild / seal / stone ---
        Case("4 hearts + wild spade = Flush", listOf(PlayingCard.parse("H_A"), PlayingCard.parse("H_K"), PlayingCard.parse("H_Q"), PlayingCard.parse("H_J"), en("S_9", Enhancement.WILD)), 340.0),
        Case("4 hearts + plain spade = High Card", PlayingCard.hand("H_A", "H_K", "H_Q", "H_J", "S_9"), 16.0),
        Case("Pair, red-seal ace retriggers", listOf(PlayingCard.parse("S_A"), PlayingCard.parse("H_A").copy(seal = Seal.RED)), 86.0),
        Case("Pair of aces + a stone card", listOf(PlayingCard.parse("S_A"), PlayingCard.parse("H_A"), en("D_5", Enhancement.STONE)), 164.0),
        // --- held-in-hand jokers ---
        // Baron: King held → X1.5 Mult; chips=32, mult=2*1.5=3 → 96.
        Case("Pair of aces + baron (King held)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_baron")), held = listOf(PlayingCard.parse("S_K"))),
        // Shoot the Moon: Queen held → +13 Mult; chips=32, mult=2+13=15 → 480.
        Case("Pair of aces + shoot_the_moon (Queen held)", PlayingCard.hand("S_A", "H_A"), 480.0, j(FJoker("j_shoot_the_moon")), held = listOf(PlayingCard.parse("S_Q"))),
        // Raised Fist: +2x nominal of lowest held card; 7 held → +14 Mult; chips=32, mult=2+14=16 → 512.
        Case("Pair of aces + raised_fist (7 held, lowest)", PlayingCard.hand("S_A", "H_A"), 512.0, j(FJoker("j_raised_fist")), held = listOf(PlayingCard.parse("S_7"))),
        // --- vanilla n-based jokers (joker_main; pre-set j.n via FJoker constructor) ---
        // Abstract: +3 Mult per joker on board (n=2: abstract + one other) → chips=32, mult=2+6=8 → 256.
        Case("Pair of aces + abstract (n=2 jokers)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_abstract", n = 2))),
        // Supernova: +1 Mult per time this hand type played (n=3) → chips=32, mult=2+3=5 → 160.
        Case("Pair of aces + supernova (n=3 plays)", PlayingCard.hand("S_A", "H_A"), 160.0, j(FJoker("j_supernova", n = 3))),
        // Blue Joker: +2 Chips per deck card (n=52 full deck) → chips=32+104=136, mult=2 → 272.
        Case("Pair of aces + blue_joker (n=52 deck)", PlayingCard.hand("S_A", "H_A"), 272.0, j(FJoker("j_blue_joker", n = 52))),
        // Banner: +30 Chips per remaining discard (n=3) → chips=32+90=122, mult=2 → 244.
        Case("Pair of aces + banner (n=3 discards)", PlayingCard.hand("S_A", "H_A"), 244.0, j(FJoker("j_banner", n = 3))),
        // Steel Joker: X(1+0.2*n); n=2 → x1.4 → chips=32, mult=2*1.4=2.8 → floor(89.6)=89.
        Case("Pair of aces + steel_joker (n=2 steel cards)", PlayingCard.hand("S_A", "H_A"), 89.0, j(FJoker("j_steel_joker", n = 2))),
        // Stone Joker: +25 Chips per stone card (n=2) → chips=32+50=82, mult=2 → 164.
        Case("Pair of aces + stone_joker (n=2 stone cards)", PlayingCard.hand("S_A", "H_A"), 164.0, j(FJoker("j_stone", n = 2))),
        // Driver's License: X3 Mult when >=16 enhanced (n=16) → chips=32, mult=2*3=6 → 192.
        Case("Pair of aces + drivers_license (n=16 enhanced)", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_drivers_license", n = 16))),
        // Baseball Card: X1.5 per Uncommon joker; 1 Uncommon (rarity=2) beside it → chips=32, mult=2*1.5=3 → 96.
        // Use j_cry_bonkers (rarity=2) as dummy: its jokerMain fires only on CRY_BULWARK hand type, never on a Pair.
        Case("Pair of aces + baseball (1 Uncommon on board)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_baseball"), FJoker("j_cry_bonkers", rarity = 2))),
        // --- vanilla accumulator-mult jokers (j.mult set via FJoker) ---
        // Green Joker: +mult per hand (+1); j.mult=4 (4 hands) → chips=32, mult=2+4=6 → 192.
        Case("Pair of aces + green_joker (mult=4)", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_green_joker", mult = 4.0))),
        // Swashbuckler: +Mult per joker sell value (j.mult); j.mult=8 (2 jokers at 4 each) → mult=2+8=10 → 320.
        Case("Pair of aces + swashbuckler (mult=8)", PlayingCard.hand("S_A", "H_A"), 320.0, j(FJoker("j_swashbuckler", mult = 8.0))),
        // Red Card: +3 Mult per skip (j.mult); j.mult=6 (2 skips) → chips=32, mult=2+6=8 → 256.
        Case("Pair of aces + red_card (mult=6)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_red_card", mult = 6.0))),
        // Spare Trousers: +2 Mult per Two Pair / Full House played (j.mult); j.mult=4 → mult=2+4=6 → 192.
        Case("Pair of aces + spare_trousers (mult=4)", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_spare_trousers", mult = 4.0))),
        // Obelisk: Xmult scales +0.2 per hand NOT this type (j.x); x=1.4 (2 non-Pair hands) → mult=2*1.4=2.8 → 89.
        Case("Pair of aces + obelisk (x=1.4)", PlayingCard.hand("S_A", "H_A"), 89.0, j(FJoker("j_obelisk", x = 1.4))),
        // Hologram: Xmult +0.25 per added card (j.x); x=1.75 (3 adds) → mult=2*1.75=3.5 → 112.
        Case("Pair of aces + hologram (x=1.75)", PlayingCard.hand("S_A", "H_A"), 112.0, j(FJoker("j_hologram", x = 1.75))),
        // Ramen: Xmult starts X2 −0.01/discard (j.x); x=1.8 (20 discards) → mult=2*1.8=3.6 → 115.
        Case("Pair of aces + ramen (x=1.8)", PlayingCard.hand("S_A", "H_A"), 115.0, j(FJoker("j_ramen", x = 1.8))),
        // Campfire: Xmult +0.25 per joker sold (j.x); x=1.5 (2 sold) → mult=2*1.5=3 → 96.
        Case("Pair of aces + campfire (x=1.5)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_campfire", x = 1.5))),
        // Loyalty Card: X4 when active (j.x=4.0); chips=32, mult=2*4=8 → 256.
        Case("Pair of aces + loyalty_card (x=4.0 active)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_loyalty_card", x = 4.0))),
        // Throwback: Xmult +0.25/skipped blind (j.x); x=1.5 (2 blinds) → mult=2*1.5=3 → 96.
        Case("Pair of aces + throwback (x=1.5)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_throwback", x = 1.5))),
        // Runner: +Chips per Straight in run (j.chips); chips=30 → chips=32+30=62, mult=2 → 124.
        Case("Pair of aces + runner (chips=30)", PlayingCard.hand("S_A", "H_A"), 124.0, j(FJoker("j_runner", chips = 30.0))),
        // Square Joker: +4 Chips per 5-card hand (j.chips); chips=12 (3 plays) → chips=32+12=44, mult=2 → 88.
        Case("Pair of aces + square (chips=12)", PlayingCard.hand("S_A", "H_A"), 88.0, j(FJoker("j_square", chips = 12.0))),
        // Castle: +3 Chips per suited card discarded (j.chips); chips=9 (3 discards) → chips=32+9=41, mult=2 → 82.
        Case("Pair of aces + castle (chips=9)", PlayingCard.hand("S_A", "H_A"), 82.0, j(FJoker("j_castle", chips = 9.0))),
        // Wee Joker: +8 Chips per 4-card Straight played (j.chips); chips=16 (2 plays) → chips=32+16=48, mult=2 → 96.
        Case("Pair of aces + wee (chips=16)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_wee", chips = 16.0))),
        // --- vanilla suit-individual jokers ---
        // Arrowhead: +50 Chips per Spade scored (INDIVIDUAL). S_A gets +50; H_A doesn't. chips=10+61+11=82, mult=2.
        Case("Pair S_A+H_A + arrowhead", PlayingCard.hand("S_A", "H_A"), 164.0, j(FJoker("j_arrowhead"))),
        // Onyx Agate: +7 Mult per Club scored (INDIVIDUAL). C_A gets +7 mult; S_A doesn't. chips=32, mult=2+7=9.
        Case("Pair C_A+S_A + onyx_agate", PlayingCard.hand("C_A", "S_A"), 288.0, j(FJoker("j_onyx_agate"))),
        // --- vanilla hand-type jokers (JOKER_MAIN, fire once) ---
        // Sly: +50 Chips if Pair. chips=32+50=82, mult=2 → 164.
        Case("Pair of aces + sly", PlayingCard.hand("S_A", "H_A"), 164.0, j(FJoker("j_sly"))),
        // Wily: +100 Chips if Three of a Kind. ToaK aces: chips=63+100=163, mult=3 → 489.
        Case("ToaK aces + wily", PlayingCard.hand("S_A", "H_A", "D_A"), 489.0, j(FJoker("j_wily"))),
        // Devious: +100 Chips if Straight. Straight 5-9: chips=65+100=165, mult=4 → 660.
        Case("Straight 5-9 + devious", PlayingCard.hand("S_5", "H_6", "D_7", "C_8", "S_9"), 660.0, j(FJoker("j_devious"))),
        // Crafty: +80 Chips if Flush. Flush H-A-K-Q-J-9: chips=85+80=165, mult=4 → 660.
        Case("Flush H-A-K-Q-J-9 + crafty", PlayingCard.hand("H_A", "H_K", "H_Q", "H_J", "H_9"), 660.0, j(FJoker("j_crafty"))),
        // Zany: +12 Mult if Three of a Kind. ToaK aces: chips=63, mult=3+12=15 → 945.
        Case("ToaK aces + zany", PlayingCard.hand("S_A", "H_A", "D_A"), 945.0, j(FJoker("j_zany"))),
        // Crazy: +12 Mult if Straight. Straight 5-9: chips=65, mult=4+12=16 → 1040.
        Case("Straight 5-9 + crazy", PlayingCard.hand("S_5", "H_6", "D_7", "C_8", "S_9"), 1040.0, j(FJoker("j_crazy"))),
        // Droll: +10 Mult if Flush. Flush H-A-K-Q-J-9: chips=85, mult=4+10=14 → 1190.
        Case("Flush H-A-K-Q-J-9 + droll", PlayingCard.hand("H_A", "H_K", "H_Q", "H_J", "H_9"), 1190.0, j(FJoker("j_droll"))),
        // --- Cryptid hand-type jokers (JOKER_MAIN) ---
        // cry_dubious: +20 Chips if HIGH_CARD in pokerHands (always true for Pair). chips=32+20=52, mult=2 → 104.
        Case("Pair of aces + cry_dubious", PlayingCard.hand("S_A", "H_A"), 104.0, j(FJoker("j_cry_dubious"))),
        // cry_foxy: +130 Chips if Full House. FH A/A/A/K/K: chips=93+130=223, mult=4 → 892.
        Case("FullHouse A/A/A/K/K + cry_foxy", PlayingCard.hand("S_A", "H_A", "D_A", "S_K", "H_K"), 892.0, j(FJoker("j_cry_foxy"))),
        // cry_tricksy: +170 Chips if Straight Flush. SF S-A-K-Q-J-T: chips=151+170=321, mult=8 → 2568.
        Case("SF S-A-K-Q-J-T + cry_tricksy", PlayingCard.hand("S_A", "S_K", "S_Q", "S_J", "S_T"), 2568.0, j(FJoker("j_cry_tricksy"))),
        // cry_manic: +22 Mult if Straight Flush. SF S-A-K-Q-J-T: chips=151, mult=8+22=30 → 4530.
        Case("SF S-A-K-Q-J-T + cry_manic", PlayingCard.hand("S_A", "S_K", "S_Q", "S_J", "S_T"), 4530.0, j(FJoker("j_cry_manic"))),
        // cry_delirious: +22 Mult if Five of a Kind. 5oaK (Wild S_A + H_A D_A C_A S_A): chips=175, mult=12+22=34 → 5950.
        Case("5oaK Wild+4 aces + cry_delirious", listOf(en("S_A", Enhancement.WILD), PlayingCard.parse("H_A"), PlayingCard.parse("D_A"), PlayingCard.parse("C_A"), PlayingCard.parse("S_A")), 5950.0, j(FJoker("j_cry_delirious"))),
        // cry_savvy: +170 Chips if Five of a Kind. 5oaK: chips=175+170=345, mult=12 → 4140.
        Case("5oaK Wild+4 aces + cry_savvy", listOf(en("S_A", Enhancement.WILD), PlayingCard.parse("H_A"), PlayingCard.parse("D_A"), PlayingCard.parse("C_A"), PlayingCard.parse("S_A")), 4140.0, j(FJoker("j_cry_savvy"))),
        // cry_quintet: X5 if Five of a Kind. 5oaK: chips=175, mult=12*5=60 → 10500.
        Case("5oaK Wild+4 aces + cry_quintet", listOf(en("S_A", Enhancement.WILD), PlayingCard.parse("H_A"), PlayingCard.parse("D_A"), PlayingCard.parse("C_A"), PlayingCard.parse("S_A")), 10500.0, j(FJoker("j_cry_quintet"))),
        // cry_wacky: +30 Mult if Flush House. FlushHouse 3xH_A+2xH_K: chips=193, mult=14+30=44 → 8492.
        Case("FlushHouse 3xH_A+2xH_K + cry_wacky", PlayingCard.hand("H_A", "H_A", "H_A", "H_K", "H_K"), 8492.0, j(FJoker("j_cry_wacky"))),
        // cry_subtle: +240 Chips if Flush House. FlushHouse: chips=193+240=433, mult=14 → 6062.
        Case("FlushHouse 3xH_A+2xH_K + cry_subtle", PlayingCard.hand("H_A", "H_A", "H_A", "H_K", "H_K"), 6062.0, j(FJoker("j_cry_subtle"))),
        // cry_unity: X9 if Flush House. FlushHouse: chips=193, mult=14*9=126 → 24318.
        Case("FlushHouse 3xH_A+2xH_K + cry_unity", PlayingCard.hand("H_A", "H_A", "H_A", "H_K", "H_K"), 24318.0, j(FJoker("j_cry_unity"))),
        // cry_kooky: +30 Mult if Flush Five. FlushFive 5xH_A: chips=215, mult=16+30=46 → 9890.
        Case("FlushFive 5xH_A + cry_kooky", PlayingCard.hand("H_A", "H_A", "H_A", "H_A", "H_A"), 9890.0, j(FJoker("j_cry_kooky"))),
        // cry_discreet: +240 Chips if Flush Five. FlushFive: chips=215+240=455, mult=16 → 7280.
        Case("FlushFive 5xH_A + cry_discreet", PlayingCard.hand("H_A", "H_A", "H_A", "H_A", "H_A"), 7280.0, j(FJoker("j_cry_discreet"))),
        // cry_swarm: X9 if Flush Five. FlushFive: chips=215, mult=16*9=144 → 30960.
        Case("FlushFive 5xH_A + cry_swarm", PlayingCard.hand("H_A", "H_A", "H_A", "H_A", "H_A"), 30960.0, j(FJoker("j_cry_swarm"))),
        // --- Cryptid nosound retrigger ---
        // cry_nosound: +3 retriggers per scored 7. Pair of 7s: each 7 scores 4 times (1+3). chips=10+4*7+4*7=66, mult=2 → 132.
        Case("Pair of 7s + cry_nosound", PlayingCard.hand("S_7", "H_7"), 132.0, j(FJoker("j_cry_nosound"))),
        // --- Cryptid edition-reactor jokers ---
        // cry_exoplanet: +15 Mult per other Holo joker (OTHER_JOKER). Holo j_cry_bonkers: Holo adds +10 mult (edition pass), exoplanet adds +15. chips=32, mult=2+10+15=27 → 864.
        Case("Pair of aces + cry_exoplanet + Holo bonkers", PlayingCard.hand("S_A", "H_A"), 864.0, j(FJoker("j_cry_exoplanet"), FJoker("j_cry_bonkers", edition = "Holo"))),
        // cry_stardust: X2 per other Poly joker (OTHER_JOKER). Poly j_cry_bonkers: Poly ×1.5 (edition pass) → mult=3; stardust X2 → mult=6. chips=32 → 192.
        Case("Pair of aces + cry_stardust + Poly bonkers", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_cry_stardust"), FJoker("j_cry_bonkers", edition = "Poly"))),
        // --- Cryptid Emult jokers ---
        // cry_facile: eMult=3 unconditionally. Pair aces: chips=32, mult=2^3=8 → 256.
        Case("Pair of aces + cry_facile", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_facile"))),
        // cry_stella_mortis: eMult=j.x when j.x>1.0. j.x=2.0: chips=32, mult=2^2=4 → 128.
        Case("Pair of aces + cry_stella_mortis (x=2.0)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_stella_mortis", x = 2.0))),
        // cry_circulus_pistoris: xChipMod=PI, eMult=PI when handsLeft==3. chips=32*PI≈100.53, mult=2^PI≈8.825 → 887.
        Case("Pair of aces + cry_circulus_pistoris (handsLeft=3)", PlayingCard.hand("S_A", "H_A"), 887.0, j(FJoker("j_cry_circulus_pistoris")), handsLeft = 3),
        // cry_filler: xMultMod≈1.0 always (meme joker). Pair aces: mult=2*1.00000000000003≈2.0 → chips=32*2=64.
        Case("Pair of aces + cry_filler (meme x1)", PlayingCard.hand("S_A", "H_A"), 64.0, j(FJoker("j_cry_filler"))),
        // cry_silly: +16 Mult if Full House. FH A/A/A/K/K: chips=93, mult=4+16=20 → 1860.
        Case("FullHouse A/A/A/K/K + cry_silly", PlayingCard.hand("S_A", "H_A", "D_A", "S_K", "H_K"), 1860.0, j(FJoker("j_cry_silly"))),
        // cry_crustulum: +Chips per card scored in play (j.chips accumulated). chips=20 → 32+20=52, mult=2 → 104.
        Case("Pair of aces + cry_crustulum (chips=20)", PlayingCard.hand("S_A", "H_A"), 104.0, j(FJoker("j_cry_crustulum", chips = 20.0))),
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
