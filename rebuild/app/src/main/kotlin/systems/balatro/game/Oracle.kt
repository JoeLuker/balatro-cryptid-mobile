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
        val debuffedJokerKey: String? = null,
        val handTypePlays: Map<HandType, Int> = emptyMap(),   // prior run-total plays per hand type (supernova)
        val totalHandsPlayed: Int = 0,              // G.GAME.hands_played — loyalty_card jokerMain
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
        // Red seal retriggers a HELD card (no joker needed): a red-seal Steel King held fires X1.5 twice →
        // mult=2*1.5*1.5=4.5 → floor(32*4.5)=144. Before the fix the held pass ignored Red seal → x1.5 once → 96.
        Case("Pair of aces + red-seal Steel King held (red seal retriggers held)", PlayingCard.hand("S_A", "H_A"), 144.0,
            held = listOf(en("S_K", Enhancement.STEEL).copy(seal = Seal.RED))),
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
        // Blueprint copies the joker to its RIGHT (j_joker, +4 Mult): mult 2+4+4 = 10, chips 32 → 320.
        Case("Pair + blueprint,joker (copies right)", PlayingCard.hand("S_A", "H_A"), 320.0, j(FJoker("j_blueprint"), FJoker("j_joker"))),
        // Brainstorm copies the LEFTMOST joker (j_joker): same 2+4+4 = 10 → 320.
        Case("Pair + joker,brainstorm (copies leftmost)", PlayingCard.hand("S_A", "H_A"), 320.0, j(FJoker("j_joker"), FJoker("j_brainstorm"))),
        // Blueprint copies an INDIVIDUAL per-card joker (Greedy, +3 Mult/Diamond): only D_A scores it, via greedy AND blueprint → mult 2+3+3 = 8, chips 32 → 256.
        Case("Pair D_A,H_A + blueprint,greedy (per-card copy)", PlayingCard.hand("D_A", "H_A"), 256.0, j(FJoker("j_blueprint"), FJoker("j_greedy_joker"))),
        // Blueprint copies a RETRIGGER joker (Sock and Buskin): each King retriggers via sock AND blueprint → 3x each: (10 + 6*10) * 2 = 140.
        Case("Pair Kings + blueprint,sock_and_buskin (retrigger copy)", PlayingCard.hand("S_K", "H_K"), 140.0, j(FJoker("j_blueprint"), FJoker("j_sock_and_buskin"))),
        Case("J,Q,K + maximized (rank patch -> ToaK)", PlayingCard.hand("S_J", "H_Q", "D_K"), 180.0, j(FJoker("j_cry_maximized"))),
        Case("Flush A-2-3-5-7 + primus (Emult pow)", PlayingCard.hand("S_A", "S_2", "S_3", "S_5", "S_7"), 323.0, j(FJoker("j_cry_primus", x = 1.01))),
        // Primus mixed-prime hand: FullHouse K/K/K/2/2 — 2 is prime so before-pass fires (bug was: only
        // fired when ALL cards prime). chips=40+10+10+10+2+2=74, mult=4; x: 1.01→1.18; floor(74×4^1.18)=379.
        Case("FullHouse K/K/K/2/2 + primus (mixed prime, before fires)", PlayingCard.hand("S_K", "H_K", "D_K", "S_2", "H_2"), 379.0, j(FJoker("j_cry_primus", x = 1.01))),
        // --- Cryptid "type" jokers (fire on context.poker_hands present) ---
        Case("Pair + giggly (High Card present, +4 Mult)", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_cry_giggly"))),
        Case("FourOfAKind aces + nutty (+19 Mult)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A", "H_K"), 2704.0, j(FJoker("j_cry_nutty"))),
        Case("FourOfAKind aces + shrewd (+150 Chips)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A", "H_K"), 1778.0, j(FJoker("j_cry_shrewd"))),
        // --- downgrade chain (misc_functions.lua:551-561): a 4oak CONTAINS Three of a Kind + a Pair; a 3oak contains a Pair ---
        // 4oak (4 aces score = 104 chips) + Wily (+100c if hand contains Three of a Kind): (104+100)*7 = 1428.
        Case("FourOfAKind aces + wily (4oak contains ToaK → +100c)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A", "H_K"), 1428.0, j(FJoker("j_wily"))),
        // 4oak + Sly (+50c if hand contains a Pair, via 4oak→3oak→pair downgrade): (104+50)*7 = 1078.
        Case("FourOfAKind aces + sly (4oak contains Pair → +50c)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A", "H_K"), 1078.0, j(FJoker("j_sly"))),
        // 3oak (3 aces = 63 chips) + Sly (+50c via 3oak→pair downgrade): (63+50)*3 = 339.
        Case("ThreeOfAKind aces + sly (3oak contains Pair → +50c)", PlayingCard.hand("S_A", "H_A", "D_A"), 339.0, j(FJoker("j_sly"))),
        Case("StraightFlush spades + nuts (x5 Mult)", PlayingCard.hand("S_A", "S_K", "S_Q", "S_J", "S_T"), 6040.0, j(FJoker("j_cry_nuts"))),
        Case("HighCard 9 (+6 kicker) + nice (+420 Chips)", PlayingCard.hand("S_9", "H_6"), 434.0, j(FJoker("j_cry_nice"))),
        Case("Pair of Kings + mask (faces retrigger x3)", PlayingCard.hand("S_K", "H_K"), 180.0, j(FJoker("j_cry_mask"))),
        Case("Pair of aces + wee_fib (2 fibs -> +6 Mult)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_wee_fib"))),
        Case("Pair + Foil Joker + meteor (+75 Chips/Foil joker)", PlayingCard.hand("S_A", "H_A"), 942.0, j(FJoker("j_joker", edition = "Foil"), FJoker("j_cry_meteor"))),
        // Baseball Card (rarity=3 Rare) fires X1.5 per Uncommon (rarity=2) board joker.
        // [Baseball(r=3), 2× Fibonacci(r=2)]: Baseball = X1.5 per Uncommon → X1.5² = X2.25. KINGS chosen so the two
        // Fibonaccis (fire only on A/2/3/5/8) add nothing, isolating Baseball: Pair chips=30, mult=2*2.25=4.5 → 135.
        Case("Pair Kings + baseball(r=3) + 2 Uncommon (x1.5^2 = x2.25)", PlayingCard.hand("S_K", "H_K"), 135.0, j(FJoker("j_baseball", rarity = 3), FJoker("j_fibonacci", rarity = 2), FJoker("j_fibonacci", rarity = 2))),
        // Thalia: Xmult = C(n,2) where n = distinct rarities on the board (Thalia itself is rarity=4).
        // Board [Thalia(r=4), Joker(r=1), Fibonacci(r=2)]: rarities {4,1,2} → n=3 → C(3,2)=3 → X3 Mult.
        // Per-card: S_A chips+11 fib+8 + H_A chips+11 fib+8 → chips=32, mult=18.
        // joker_main: Thalia X3 → mult=54; Joker +4 → mult=58. floor(32*58)=1856.
        Case("Pair + thalia(r=4),joker(r=1),fib(r=2) (3 rarities → C(3,2)=3 → X3)", PlayingCard.hand("S_A", "H_A"), 1856.0, j(FJoker("j_cry_thalia", rarity = 4), FJoker("j_joker", rarity = 1), FJoker("j_fibonacci", rarity = 2))),
        // Circus (Exotic, r=6): Xmult per rarity of offered joker. Board [Circus(r=6), Baseball(r=3/Rare)].
        // other_joker pass: other=Baseball(r=3) offered to Circus → rarity=3→X2 Mult. Baseball: oj===j→skip.
        // No joker_main effects. Pair aces: chips=32, mult=2; X2 → mult=4. floor(32×4)=128.
        Case("Pair + circus(r=6,Exotic) + baseball(r=3,Rare) → circus X2 per Rare", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_circus", rarity = 6), FJoker("j_baseball", rarity = 3))),
        // Joker-retrigger pass (context.retrigger_joker_check): chad, flip_side, loopy, spectrogram.
        // Chad(n=2): retrigger leftmost joker 2 extra times. Board [Joker(r=1,leftmost), Chad(n=2,r=3)].
        // HighCard S_2: base chips=5, mult=1; S_2 +2c → chips=7.
        // joker_main: Joker +4m → mult=5; retrigger: Chad votes rj===board.first()→2 reps; Joker fires 2 more: mult=13.
        //   Chad: null; retrigger: rj=Chad≠leftmost → 0. Score: floor(7×13)=91.
        Case("HighCard S_2 + joker(leftmost),chad(n=2) — chad retriggers leftmost 2x → 91", PlayingCard.hand("S_2"), 91.0, j(FJoker("j_joker", rarity = 1), FJoker("j_cry_chad", n = 2, rarity = 3))),
        // Loopy(n=1): retrigger all OTHER board jokers once. Board [Joker1, Joker2, Loopy(n=1)].
        // joker_main: Joker1 +4→mult=5; Loopy votes j=Loopy≠rj=Joker1,n=1→1 rep; Joker1 re-fires→mult=9.
        //   Joker2 +4→mult=13; Loopy votes j≠rj=Joker2→1 rep; Joker2 re-fires→mult=17.
        //   Loopy: no effect; Loopy vs rj=Loopy: j===rj→0 reps. Score: floor(7×17)=119.
        Case("HighCard S_2 + 2x joker + loopy(n=1) — loopy retriggers both others once → 119", PlayingCard.hand("S_2"), 119.0, j(FJoker("j_joker"), FJoker("j_joker"), FJoker("j_cry_loopy", n = 1))),
        // Flip Side: retrigger all double-sided-edition jokers once. Board [Joker(edition=cry_double_sided), FlipSide].
        // HighCard S_2: chips=7 (as above).
        // joker_main: Joker +4m → mult=5; retrigger: FlipSide: rj.edition=="cry_double_sided"→reps=1; Joker fires once more: mult=9.
        //   FlipSide: null; retrigger: rj.edition=""→0. Score: floor(7×9)=63.
        Case("HighCard S_2 + joker(cry_double_sided),flip_side — flip retriggers double-sided once → 63", PlayingCard.hand("S_2"), 63.0, j(FJoker("j_joker", edition = "cry_double_sided"), FJoker("j_cry_flip_side"))),
        // Spectrogram(n=1): retrigger the RIGHTMOST board joker j.n times. Board [Spectrogram(n=1), Joker(rightmost)].
        // Pair aces: chips=32, mult=2. Spectrogram has no joker_main effect.
        // Spectrogram before-pass resets j.n=0 each hand (epic.lua:2047-2053 resets echonum per hand).
        // No Echo cards in hand → j.n stays 0 → no extra retriggers for j_joker.
        // joker_main: Spectrogram no-op; Joker +4→mult=6. Score: floor(32×6)=192.
        // (Note: pre-seeded n is wiped by the before-pass reset; to test retriggers use actual Echo cards.)
        Case("Pair aces + spectrogram(no Echo cards, n reset→0),joker → no retrigger → 192", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_cry_spectrogram", n = 1), FJoker("j_joker"))),
        // Spectrogram accumulator: j.n starts at 0; each scored Echo card increments j.n by 1.
        // Board [Spectrogram(n=0,leftmost), Joker(rightmost)]; hand = 2 Echo Aces.
        // Per-card: S_A Echo: chips+=11→21; spectrogram sees ECHO→j.n=1. H_A Echo: chips+=11→32; j.n=2.
        // joker_main: Spectrogram no-op; Joker+4→mult=6; Spectrogram votes rj=Joker===last,n=2→2 reps;
        //   Joker fires twice more: mult=6+4+4=14. Score: floor(32×14)=448.
        Case("Pair Echo-Aces + spectrogram(n=0 accumulates to 2),joker → joker fires 3x → 448",
            listOf(en("S_A", Enhancement.ECHO), en("H_A", Enhancement.ECHO)), 448.0, j(FJoker("j_cry_spectrogram"), FJoker("j_joker"))),
        // Spectrogram retrigger-safety regression: a retriggered Echo card must NOT inflate j.n.
        // Board [spectrogram(leftmost), hanging_chad, j_joker(rightmost)]. Hand: [S_A Echo] (HighCard).
        // Before-pass: scoringHand has 1 Echo card → j.n = 1 (Lua: echonum=1, set once in context.before).
        // Repetition collection: hanging_chad sees c===first → Retrigger(2) → reps=3.
        // repeat(3): eval S_A → +11 chips each rep → chips = 5 + 11×3 = 38; mult stays 1.
        //   hanging_chad individual: Effect.Retrigger(2) — only repetitions field is read by apply(); no score effect.
        // Joker main: j_joker +4 → mult=5; retrigger sub-loop: spectrogram sees rj=j_joker===last,
        //   selfJoker≠rj, n=1→Retrigger(1) → j_joker fires once more → mult=9. Score: floor(38×9)=342.
        // Buggy (perCard path): S_A fires 3 scoring instances → j.n=3 → j_joker fires 3 extra times → mult=17 → 646.
        Case("HighCard Echo-Ace + spectrogram + hanging_chad + joker — retrigger must NOT inflate echonum → 342",
            listOf(en("S_A", Enhancement.ECHO)), 342.0,
            j(FJoker("j_cry_spectrogram"), FJoker("j_hanging_chad"), FJoker("j_joker"))),
        // boredom: 1-in-odds pseudorandom retrigger of any other joker (epic.lua:868).
        // Run loop pre-resolves: j.n=1 (roll wins) → retrigger, j.n=0 (roll loses) → no retrigger.
        // Board [boredom(j.n=1), j_joker]. joker_main: j_joker+4→mult=6 → score=192.
        // Retrigger sub-loop: boredom sees rj=j_joker, j.n=1, j!==rj → repetitions=1 → j_joker fires again: mult=10.
        // Final: floor(32×10)=320.
        Case("Pair aces + boredom(n=1,roll-wins)+joker — boredom retriggers joker → 320", PlayingCard.hand("S_A", "H_A"), 320.0, j(FJoker("j_cry_boredom", n = 1), FJoker("j_joker"))),
        // boredom(j.n=0, roll lost): no retrigger — j_joker fires once only → floor(32×6)=192.
        Case("Pair aces + boredom(n=0,roll-loses)+joker — no retrigger → 192", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_cry_boredom", n = 0), FJoker("j_joker"))),
        // busdriver: +mult or -mult each joker_main, pseudorandom (misc_joker.lua:7653).
        // Run loop pre-resolves: j.mult=50 (success) or j.mult=-50 (fail, default odds=4).
        // Success: chips=32, mult=2+50=52 → floor(32×52)=1664.
        Case("Pair aces + busdriver(mult=50, success) → 1664", PlayingCard.hand("S_A", "H_A"), 1664.0, j(FJoker("j_cry_busdriver", mult = 50.0))),
        // Fail: mult=2+(-50)=-48 → floor(32×-48)=-1536.
        Case("Pair aces + busdriver(mult=-50, fail) → -1536", PlayingCard.hand("S_A", "H_A"), -1536.0, j(FJoker("j_cry_busdriver", mult = -50.0))),
        Case("TwoPair 2s/As + duos (x2.5 Mult)", PlayingCard.hand("S_2", "H_2", "S_A", "H_A"), 230.0, j(FJoker("j_cry_duos"))),
        Case("FullHouse As/Ks + home (x3.5 Mult)", PlayingCard.hand("S_A", "H_A", "D_A", "S_K", "H_K"), 1302.0, j(FJoker("j_cry_home"))),
        Case("TwoPair 2s/As + zooble (2 distinct ranks -> +2 Mult)", PlayingCard.hand("S_2", "H_2", "S_A", "H_A"), 184.0, j(FJoker("j_cry_zooble"))),
        Case("Pair + cursor @16 chips (read branch)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_cursor", chips = 16.0))),
        Case("Pair + eternalflame @x1.3 (read branch)", PlayingCard.hand("S_A", "H_A"), 83.0, j(FJoker("j_cry_eternalflame", x = 1.3))),
        Case("HighCard 7,2 diff suits + whip (+0.5 Xmult)", PlayingCard.hand("S_2", "H_7"), 18.0, j(FJoker("j_cry_whip"))),
        // whip + Smeared: 2 and 7 of the SAME printed suit (both Hearts) count as different suits under
        // Smeared (red H↔D collide) → whip fires +0.5 → x1.5. Without the smear fix it scores 12 (no trigger).
        Case("HighCard 2H,7H same suit + smeared + whip (smear → x1.5) → 18", PlayingCard.hand("H_2", "H_7"), 18.0,
            j(FJoker("j_smeared"), FJoker("j_cry_whip"))),
        Case("Pair + big_cube (x6 Chips)", PlayingCard.hand("S_A", "H_A"), 384.0, j(FJoker("j_cry_big_cube"))),
        Case("Pair of 4s + antennastoheaven (2x4 -> x1.2 Chips)", PlayingCard.hand("S_4", "H_4"), 43.0, j(FJoker("j_cry_antennastoheaven"))),
        Case("Pair of aces + supercell (+15c x2c +15m x2m)", PlayingCard.hand("S_A", "H_A"), 3196.0, j(FJoker("j_cry_supercell"))),
        Case("Pair of aces + m @x=14 (1 Jolly sold)", PlayingCard.hand("S_A", "H_A"), 896.0, j(FJoker("j_cry_m", x = 14.0))),
        Case("Pair of aces + iterum (x2/card, +1 retrigger/card)", PlayingCard.hand("S_A", "H_A"), 1728.0, j(FJoker("j_cry_iterum"))),
        Case("Pair of aces + iterum + exponentia (emult from 4 xmult events)", PlayingCard.hand("S_A", "H_A"), 2619.0, j(FJoker("j_cry_iterum"), FJoker("j_cry_exponentia"))),
        // exponentia joker-main xMultMod path: brokenhome fires xMultMod=11.4 in applyJokerFx → exponentia.x+=0.03.
        // Per-card: chips=32, mult=2. No individual xMult. joker_main: brokenhome mult×11.4=22.8, exp.x=1.03.
        // exponentia eMult=1.03 → mult=22.8^1.03≈25.042. floor(32×25.042)=801.
        Case("Pair of aces + brokenhome + exponentia (joker-main xMultMod triggers exp)", PlayingCard.hand("S_A", "H_A"), 801.0, j(FJoker("j_cry_brokenhome"), FJoker("j_cry_exponentia"))),
        // exponentia Poly-edition path: bonkers has no joker_main effect on a Pair; its Poly edition fires
        // mult×1.5 (x_mult_mod in Lua) → exponentia.x+=0.03. exponentia eMult=1.03 → mult=3.0^1.03≈3.100. floor(32×3.100)=99.
        Case("Pair of aces + Poly-bonkers + exponentia (Poly edition triggers exp)", PlayingCard.hand("S_A", "H_A"), 99.0, j(FJoker("j_cry_bonkers", edition = "Poly"), FJoker("j_cry_exponentia"))),
        // exponentia other_joker path: waluigi fires xMultMod=2.5 in applyOtherJokerFx → must trigger exponentia.
        // Board: [waluigi, exponentia(x=1.0)]. Pair aces: chips=32, mult=2.
        // Joker main pass (waluigi first, exponentia second):
        //   j=waluigi joker_main=null; other_joker(oj=waluigi): voter=waluigi→X2.5 → mult=5, exp.x=1.03.
        //   j=exponentia joker_main: exp.x=1.03>1 → eMult=1.03 → mult=5^1.03≈5.248.
        //                other_joker(oj=exponentia): voter=waluigi→X2.5 → mult≈13.12, exp.x=1.06.
        // Score: floor(32×13.12)=419. Pre-fix: exp.x never incremented in other_joker → eMult never
        // fires (x=1.0 fails guard) → mult=5×2.5=12.5 → floor(32×12.5)=400.
        Case("Pair of aces + waluigi + exponentia (other_joker xMult triggers exp) → 419",
            PlayingCard.hand("S_A", "H_A"), 419.0, j(FJoker("j_cry_waluigi"), FJoker("j_cry_exponentia"))),
        Case("Pair + jtron + joker (Emult 1+1 joker -> mult^2)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_jtron"), FJoker("j_joker"))),
        // --- Cryptid accumulator-read jokers (run loop sets j.x/j.xc/j.mult; zero-defaults no-op) ---
        Case("Pair of aces + dropshot @x=1.4 (2 suit-hit hands)", PlayingCard.hand("S_A", "H_A"), 89.0, j(FJoker("j_cry_dropshot", x = 1.4))),
        Case("Pair of aces + chili_pepper @x=2.0 (2 end_of_round)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_chili_pepper", x = 2.0))),
        Case("Pair of aces + mondrian @x=1.25 (1 no-discard round)", PlayingCard.hand("S_A", "H_A"), 80.0, j(FJoker("j_cry_mondrian", x = 1.25))),
        Case("Pair of aces + spaceglobe @xc=1.4 (2 type-match hands)", PlayingCard.hand("S_A", "H_A"), 89.0, j(FJoker("j_cry_spaceglobe", xc = 1.4))),
        Case("Pair of aces + fading_joker @x=2.0 (perishable expired)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_fading_joker", x = 2.0))),
        Case("Pair of aces + poor_joker @mult=8 (2 rent payments)", PlayingCard.hand("S_A", "H_A"), 320.0, j(FJoker("j_cry_poor_joker", mult = 8.0))),
        Case("Pair of aces + keychange @x=1.5 (2 new hand types)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_keychange", x = 1.5))),
        // Spooky-Code scalers: cut (+0.5 Xmult/Code destroyed) and python (+0.15 Xmult/Code used).
        // Both use the same if (j.x > 1.0) → xMultMod pattern; test branch with x=1.5.
        // Pair aces: chips=32, mult=2; Xmult=1.5 → mult=3; floor(32×3)=96.
        Case("Pair of aces + cut @x=1.5 (spooky Code-card scaler)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_cut", x = 1.5))),
        Case("Pair of aces + python @x=1.5 (spooky Code-card scaler)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_python", x = 1.5))),
        // --- batch-15: flat/accumulator jokers ---
        Case("Pair of aces + kittyprinter (flat x2 Xmult)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_kittyprinter"))),
        Case("Pair of aces + clicked_cookie @chips=200 (+200 Chips)", PlayingCard.hand("S_A", "H_A"), 464.0, j(FJoker("j_cry_clicked_cookie", chips = 200.0))),
        Case("Pair of aces + monkey_dagger @chips=50 (+50 Chips)", PlayingCard.hand("S_A", "H_A"), 164.0, j(FJoker("j_cry_monkey_dagger", chips = 50.0))),
        Case("Pair of aces + unjust_dagger @x=1.5 (x1.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_unjust_dagger", x = 1.5))),
        Case("Pair of aces + jimball @x=1.5 (x1.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_jimball", x = 1.5))),
        Case("Pair of aces + pizza_slice @x=1.5 (x1.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_pizza_slice", x = 1.5))),
        // spy: unconditional Xmult each joker_main (spooky.lua:664). j.x = card.ability.x_mult (default 0.5).
        // j.x=0.5 (debuff, default): mult = 2*0.5=1.0 → score = floor(32*1.0)=32.
        Case("Pair aces + spy(x=0.5, default debuff) → 32", PlayingCard.hand("S_A", "H_A"), 32.0, j(FJoker("j_cry_spy", x = 0.5))),
        // j.x=1.5 (revealed/boosted): mult = 2*1.5=3.0 → score = floor(32*3.0)=96.
        Case("Pair aces + spy(x=1.5, boosted) → 96", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_spy", x = 1.5))),
        // cry_paved_joker: j.x accumulates on perishable_debuffed; joker_main reads it. Same pattern as fading_joker.
        Case("Pair of aces + paved_joker @x=1.5 (x1.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_paved_joker", x = 1.5))),
        // cry_membershipcard: j.x = Xmult_mod * member_count (pre-computed by run loop); joker_main reads it.
        Case("Pair of aces + membershipcard @x=1.5 (x1.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_membershipcard", x = 1.5))),
        // cry_pizza: has NO joker_main scoring path in Lua (misc_joker.lua:10139 only has end_of_round countdown
        // and selling_self pizza-slice spawn). j.x is never read for scoring — no-op regardless of x value.
        Case("Pair of aces + pizza @x=1.5 (no joker_main scoring path → base only)", PlayingCard.hand("S_A", "H_A"), 64.0, j(FJoker("j_cry_pizza", x = 1.5))),
        Case("Pair of aces + wheelhope @x=1.5 (1 WoF trigger)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_wheelhope", x = 1.5))),
        // alt_wheel_of_fortune: NOT a Joker at all — only a UI tooltip key (set="Other") used in wheelhope's
        // loc_vars (misc_joker.lua:7325). Can never appear on the board; no scoring effect.
        Case("Pair of aces + alt_wheel_of_fortune @x=1.5 (not a joker → base only)", PlayingCard.hand("S_A", "H_A"), 64.0, j(FJoker("j_cry_alt_wheel_of_fortune", x = 1.5))),
        Case("Pair of aces + fspinner @chips=30 (+30 Chips)", PlayingCard.hand("S_A", "H_A"), 124.0, j(FJoker("j_cry_fspinner", chips = 30.0))),
        // membershipcardtwo: j.chips = chips * floor(member_count/chips_mod) pre-computed by run loop; joker_main adds it.
        // j.chips=18 → chips=32+18=50, mult=2 → floor(50×2)=100.
        Case("Pair of aces + membershipcardtwo @chips=18 (+18 Chips)", PlayingCard.hand("S_A", "H_A"), 100.0, j(FJoker("j_cry_membershipcardtwo", chips = 18.0))),
        Case("Pair of aces + pirate_dagger @xc=1.5 (x1.5 Xchips)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_pirate_dagger", xc = 1.5))),
        // --- batch-16: misc/exotic accumulator-read + triggered eMult ---
        Case("Pair of aces + happyhouse @n=1 (114+ hands played, Emult^4)", PlayingCard.hand("S_A", "H_A"), 512.0, j(FJoker("j_cry_happyhouse", n = 1))),
        Case("Pair of aces + verisimile @x=1.5 (pseudorandom hits)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_verisimile", x = 1.5))),
        Case("Pair of aces + duplicare @x=2.0 (2 scaling triggers)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_duplicare", x = 2.0))),
        Case("Pair of aces + formidiulosus @x=1.03 (3 candy jokers)", PlayingCard.hand("S_A", "H_A"), 65.0, j(FJoker("j_cry_formidiulosus", x = 1.03))),
        // --- batch-19: m.lua scoring jokers: foodm/mstack/biggestm/longboi ---
        Case("Pair of aces + foodm @mult=40 (perishable +40 Mult)", PlayingCard.hand("S_A", "H_A"), 1344.0, j(FJoker("j_cry_foodm", mult = 40.0))),
        Case("Pair of aces + mstack @n=1 (retrigger 1/card)", PlayingCard.hand("S_A", "H_A"), 108.0, j(FJoker("j_cry_mstack", n = 1))),
        // mstack retrigger count is capped at max_retriggers=40 (m.lua). @n=41 each Ace scores 1+min(41,40)=41×:
        // chips 10 + 2·41·11 = 912, ×2 mult = 1824 (uncapped would be 1+41=42 → 934×2 = 1868).
        Case("Pair of aces + mstack @n=41 (retrigger capped at 40/card)", PlayingCard.hand("S_A", "H_A"), 1824.0, j(FJoker("j_cry_mstack", n = 41))),
        Case("Pair of aces + biggestm @x=7,n=1 (x7 when active)", PlayingCard.hand("S_A", "H_A"), 448.0, j(FJoker("j_cry_biggestm", x = 7.0, n = 1))),
        // biggestm before-pass activation (parity audit batch-13): n=0 on Pair hand → before-pass sets n=1 → fires X7.
        // Pair aces: chips=32, mult=2; xMultMod=7 → mult=14; floor(32×14)=448.
        Case("Pair of aces + biggestm @x=7,n=0 (before-pass activates on Pair → x7) → 448",
            PlayingCard.hand("S_A", "H_A"), 448.0, j(FJoker("j_cry_biggestm", x = 7.0, n = 0))),
        // biggestm before-pass NOT activated on non-Pair: n=0, High Card → n stays 0 → no fire → base score.
        // HighCard S_A: base=5c/1m + 11c = 16c * 1m = 16.
        Case("HighCard S_A + biggestm @x=7,n=0 (no Pair → stays inactive → base 16) → 16",
            PlayingCard.hand("S_A"), 16.0, j(FJoker("j_cry_biggestm", x = 7.0, n = 0))),
        Case("Pair of aces + longboi @x=2.0 (Xmult from monstermult)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_longboi", x = 2.0))),
        // --- batch-18: individual Xmult + joker_main Xmult/Emult accumulators ---
        Case("Pair of aces + caramel @x=1.75 (X1.75/card, 2 aces)", PlayingCard.hand("S_A", "H_A"), 196.0, j(FJoker("j_cry_caramel", x = 1.75))),
        Case("Pair of aces + clockwork @x=1.5 (accumulated Xmult)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_cry_clockwork", x = 1.5))),
        Case("Pair of aces + starfruit @x=2.0 (Emult^2, default)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_starfruit", x = 2.0))),
        // starfruit depleted to x=1.2 (4 rerolls: 2.0 − 4×0.2 = 1.2): Emult=1.2 → mult=2^1.2≈2.2974 → floor(32×2.2974)=73.
        Case("Pair of aces + starfruit @x=1.2 (4 rerolls, partial depletion) → 73",
            PlayingCard.hand("S_A", "H_A"), 73.0, j(FJoker("j_cry_starfruit", x = 1.2))),
        // starfruit at self-destruct threshold x=1.0: guard j.x > 1.0 → false → no eMult → base score=64.
        // (RunScreen removes starfruit when emult ≤ 1.00000001; score engine must return base if it sees x=1.0.)
        Case("Pair of aces + starfruit @x=1.0 (dead threshold, no Emult) → 64",
            PlayingCard.hand("S_A", "H_A"), 64.0, j(FJoker("j_cry_starfruit", x = 1.0))),
        // caramel at n=0 (rounds_remaining=0, dying round): Score.kt reads only j.x, not j.n.
        // xMult=1.75 still fires per scored card. Same math as x=1.75 case: floor(10 + 11^1.75 + 11^1.75) * 2 = 196.
        Case("Pair of aces + caramel @x=1.75,n=0 (dying round, xMult still fires) → 196",
            PlayingCard.hand("S_A", "H_A"), 196.0, j(FJoker("j_cry_caramel", x = 1.75, n = 0))),
        // --- batch-17: boss-blind gate, flat Xmult-halving, Astral-edition other_joker ---
        // broken_sync_catalyst: swaps 10% of chips and mult (atomic). Pair aces: chips=32, mult=2.
        // delta=(32−2)*0.10=3.0; chips=32−3=29, mult=2+3=5. Score: floor(29×5)=145.
        Case("Pair of aces + broken_sync_catalyst (10% chip↔mult swap) → 145",
            PlayingCard.hand("S_A", "H_A"), 145.0, j(FJoker("j_cry_broken_sync_catalyst"))),
        // sync_catalyst: sets chips = mult = (chips+mult)/2. Pair aces: chips=32, mult=2 → avg=17.
        // Score: floor(17×17)=289.
        Case("Pair of aces + sync_catalyst (balance chips=mult=avg) → 289",
            PlayingCard.hand("S_A", "H_A"), 289.0, j(FJoker("j_cry_sync_catalyst"))),
        Case("Pair of aces + spy (flat x0.5 Xmult)", PlayingCard.hand("S_A", "H_A"), 32.0, j(FJoker("j_cry_spy", x = 0.5))),
        Case("Pair of aces + apjoker on boss blind (x4 Xmult)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_apjoker")), bossBlind = true),
        // cry_blacklist: j.n=0 → blacklist rank 14 (Ace). Pair of Aces has 2× id=14 → nullify fires → chips=0, mult=0 → score=0.
        Case("Pair of aces + blacklist(rank=14=Ace) → nullified → 0", PlayingCard.hand("S_A", "H_A"), 0.0, j(FJoker("j_cry_blacklist"))),
        // cry_blacklist with non-matching rank: j.n=7 → blacklist rank 7. Pair of Aces has no 7 → normal score=64.
        Case("Pair of aces + blacklist(rank=7, no 7 in hand) → normal → 64", PlayingCard.hand("S_A", "H_A"), 64.0, j(FJoker("j_cry_blacklist", n = 7))),
        // cry_googol_play: x=1e100 (probability roll won externally). Pair of Aces: chips=32, mult=2.
        // joker_main: x=1e100 > 1 → xMultMod=1e100 → mult=2×1e100=2e100. score=floor(32×2e100)=6.4e101.
        Case("Pair of aces + googol_play(x=1e100, triggered) → 6.4e101", PlayingCard.hand("S_A", "H_A"), 6.4e101, j(FJoker("j_cry_googol_play", x = 1e100))),
        // Astral j_joker: own e_mult^1.1 (post_joker) then universe's e_mult^1.2 reaction → ((2+4)^1.1)^1.2.
        // (Was 274 before the Astral self-edition fix — that omitted the joker's OWN ^1.1.)
        Case("Pair + universe + Astral joker (self ^1.1 then universe ^1.2) → 340", PlayingCard.hand("S_A", "H_A"), 340.0, j(FJoker("j_joker", edition = "Astral"), FJoker("j_cry_universe"))),
        // cry_universe individual-card path (batch-10 parity audit): fires Emult^1.2 per Astral-edition scored card
        // (misc_joker.lua:8281-8288). Previously only the other_joker pass was implemented.
        // Pair Astral-Aces: chips=10, mult=2. Per-card: S_A→chips=21, mult=2^1.2; H_A→chips=32, mult=2^1.44.
        // 2^1.44≈2.7130. score=floor(32×2.7130)=86.
        Case("Pair Astral-Aces + universe (Emult^1.2 per Astral scored card, individual pass) → 86",
            listOf(PlayingCard.parse("S_A").copy(edition = "Astral"), PlayingCard.parse("H_A").copy(edition = "Astral")),
            86.0, j(FJoker("j_cry_universe"))),
        // --- hands/discards-remaining jokers (now threaded into the engine) ---
        Case("Pair + acrobat on last hand (x3 Mult)", PlayingCard.hand("S_A", "H_A"), 192.0, j(FJoker("j_acrobat")), handsLeft = 0),
        Case("Pair + mystic_summit at 0 discards (+15 Mult)", PlayingCard.hand("S_A", "H_A"), 544.0, j(FJoker("j_mystic_summit")), discardsLeft = 0),
        Case("Pair + cry_night on last hand (Emult mult^3)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_night")), handsLeft = 0),
        // --- editions ---
        Case("Pair + Foil Joker (+50 Chips)", PlayingCard.hand("S_A", "H_A"), 492.0, j(FJoker("j_joker", edition = "Foil"))),
        Case("Pair + Holo Joker (+10 Mult)", PlayingCard.hand("S_A", "H_A"), 512.0, j(FJoker("j_joker", edition = "Holo"))),
        Case("Pair + Poly Joker (x1.5 Mult)", PlayingCard.hand("S_A", "H_A"), 288.0, j(FJoker("j_joker", edition = "Poly"))),
        // CARD editions (a scored card applies its own edition; previously unapplied → all scored as plain 64):
        // Foil ace +50 Chips → chips=32+50=82, mult=2 → 164.  Holo ace +10 Mult → mult=12 → 384.  Poly ace x1.5 → mult=3 → 96.
        Case("Pair of aces, Foil ace (card +50 Chips) → 164",
            listOf(PlayingCard.parse("S_A").copy(edition = "Foil"), PlayingCard.parse("H_A")), 164.0),
        Case("Pair of aces, Holo ace (card +10 Mult) → 384",
            listOf(PlayingCard.parse("S_A").copy(edition = "Holo"), PlayingCard.parse("H_A")), 384.0),
        Case("Pair of aces, Poly ace (card x1.5 Mult) → 96",
            listOf(PlayingCard.parse("S_A").copy(edition = "Poly"), PlayingCard.parse("H_A")), 96.0),
        // Joker-edition ORDERING: Foil/Holo are pre_joker — they boost chips/mult BEFORE the joker's
        // xmult/xchip main (so the multiply applies to the boosted value); Poly stays post_joker. The
        // j_joker cases above are additive (order-independent) so they're unchanged; these xmult/xchip
        // jokers are not. (Previously editions applied AFTER the main → under-scored.)
        // Holo seeing_double (X2): (2+10)*2 → mult 24, chips 32 → 768 (was X2-then-+10 = 14 → 448).
        Case("Pair S_A,C_A + Holo seeing_double (Holo pre_joker → (2+10)*2) → 768", PlayingCard.hand("S_A", "C_A"), 768.0, j(FJoker("j_seeing_double", edition = "Holo"))),
        // Foil big_cube (X6 Chips): (32+50)*6 → chips 492, mult 2 → 984 (was 32*6+50 = 242 → 484).
        Case("Pair aces + Foil big_cube (Foil pre_joker → (32+50)*6) → 984", PlayingCard.hand("S_A", "H_A"), 984.0, j(FJoker("j_cry_big_cube", edition = "Foil"))),
        // Holo brokenhome (X11.4): (2+10)*11.4 → mult 136.8, chips 32 → 4377 (was 32*(2*11.4+10) = 1049).
        Case("Pair aces + Holo brokenhome (Holo pre_joker → (2+10)*11.4) → 4377", PlayingCard.hand("S_A", "H_A"), 4377.0, j(FJoker("j_cry_brokenhome", edition = "Holo"))),
        // Cryptid joker editions (post_joker, multiplicative): Mosaic ×2.5 Chips (x_chips), Astral Mult^1.1 (e_mult).
        // Mosaic bonkers (no-op on a Pair) → chips 32*2.5=80, mult 2 → 160.
        Case("Pair aces + Mosaic bonkers (chips x2.5 post_joker) → 160", PlayingCard.hand("S_A", "H_A"), 160.0, j(FJoker("j_cry_bonkers", edition = "cry_mosaic"))),
        // Astral j_joker (+4 Mult, then ^1.1): mult (2+4)^1.1=7.177, chips 32 → floor(229.6)=229.
        Case("Pair aces + Astral j_joker (mult^1.1 post_joker) → 229", PlayingCard.hand("S_A", "H_A"), 229.0, j(FJoker("j_joker", edition = "Astral"))),
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
        // Seeing Double + The Club: the only club (C_K) is debuffed, so it's not counted (no bypass_debuff) → X2 does NOT fire: (10 + 10) * 2 = 40.
        Case("Pair C_K,H_K + seeing_double under The Club (no fire)", PlayingCard.hand("C_K", "H_K"), 40.0, j(FJoker("j_seeing_double")), debuff = Debuff.DebuffSuit(Suit.C)),
        // Flower Pot + The Goad: is_suit uses bypass_debuff=true, so the debuffed S_A still counts → all 4 suits, X3 STILL fires: (60 + 11+11+11) * (7*3) = 1953.
        Case("FoaK aces + flower_pot under The Goad (still fires)", PlayingCard.hand("S_A", "H_A", "D_A", "C_A"), 1953.0, j(FJoker("j_flower_pot")), debuff = Debuff.DebuffSuit(Suit.S)),
        // THE_PLANT (DebuffFace): face cards (J/Q/K) score and trigger nothing; non-faces are unaffected.
        // Aces (id=14) are NOT faces in Balatro — isFace checks id in 11..13 only.
        // Case 1: Pair of kings — both K's debuffed, no per-card scoring at all.
        //   PAIR base=10c/2m; S_K debuffed, H_K debuffed → chips=10, mult=2 → floor(10*2)=20.
        Case("Pair S_K,H_K + THE_PLANT (both kings debuffed → base only)", PlayingCard.hand("S_K", "H_K"), 20.0, debuff = Debuff.DebuffFace),
        // Case 2: Pair of aces — aces (id=14) are NOT faces, both score normally.
        //   PAIR base=10c/2m; S_A +11c, H_A +11c → chips=32, mult=2 → floor(32*2)=64.
        Case("Pair S_A,H_A + THE_PLANT (aces not faces, both score)", PlayingCard.hand("S_A", "H_A"), 64.0, debuff = Debuff.DebuffFace),
        // Case 3: Pair of kings + j_scary_face (+30c/face) under THE_PLANT.
        //   Both K's debuffed → scary_face never fires → chips=10, mult=2 → floor(10*2)=20.
        Case("Pair S_K,H_K + scary_face + THE_PLANT (no +30c trigger)", PlayingCard.hand("S_K", "H_K"), 20.0, j(FJoker("j_scary_face")), debuff = Debuff.DebuffFace),
        // Case 4: Flush [K,Q,J,A,2] spades + j_photograph under THE_PLANT.
        //   K/Q/J debuffed; A(+11c) and 2(+2c) score; Photograph firstOrNull finds no non-debuffed
        //   face → X2 does NOT fire. FLUSH base=35c/4m; +11+2=48c → floor(48*4)=192.
        Case("Flush S_K,S_Q,S_J,S_A,S_2 + photograph + THE_PLANT (Photograph no fire)",
            PlayingCard.hand("S_K", "S_Q", "S_J", "S_A", "S_2"), 192.0, j(FJoker("j_photograph")), debuff = Debuff.DebuffFace),
        // Case 5: Pair of 10s + j_walkie_talkie (+10c+4m per 10 or 4) under THE_PLANT.
        //   10s are NOT faces (id=10 < 11), so walkie fires normally on both.
        //   PAIR base=10c/2m; S_10→+10c+4m→30c/6m; H_10→+10c+4m→50c/10m → floor(50*10)=500.
        Case("Pair S_10,H_10 + walkie_talkie + THE_PLANT (non-faces unaffected)", PlayingCard.hand("S_10", "H_10"), 500.0, j(FJoker("j_walkie_talkie")), debuff = Debuff.DebuffFace),
        // --- steel held ---
        Case("Pair of aces + 1 steel held", PlayingCard.hand("S_A", "H_A"), 96.0, held = listOf(en("S_K", Enhancement.STEEL))),
        Case("Pair of aces + 2 steel held", PlayingCard.hand("S_A", "H_A"), 144.0, held = listOf(en("S_K", Enhancement.STEEL), en("D_K", Enhancement.STEEL))),
        // --- wild / seal / stone ---
        Case("4 hearts + wild spade = Flush", listOf(PlayingCard.parse("H_A"), PlayingCard.parse("H_K"), PlayingCard.parse("H_Q"), PlayingCard.parse("H_J"), en("S_9", Enhancement.WILD)), 340.0),
        Case("4 hearts + plain spade = High Card", PlayingCard.hand("H_A", "H_K", "H_Q", "H_J", "S_9"), 16.0),
        Case("Pair, red-seal ace retriggers", listOf(PlayingCard.parse("S_A"), PlayingCard.parse("H_A").copy(seal = Seal.RED)), 86.0),
        Case("Pair of aces + a stone card", listOf(PlayingCard.parse("S_A"), PlayingCard.parse("H_A"), en("D_5", Enhancement.STONE)), 164.0),
        // Abstract enhancement (^Emult=1.15 when played; confirmed from SpectralPack/Cryptid items/misc.lua; not a face).
        // Pair [S_A(Mult), H_A(Abstract)]: chips=10, mult=2.
        //   S_A Mult: chips+=11→21, mult+=4→6. H_A Abstract: chips+=11→32, eMult→mult=6^1.15≈7.850.
        //   Score: floor(32×7.850)=251.
        Case("Pair Mult-A + Abstract-A (Abstract ^Emult=1.15 → mult≈7.850) → 251",
            listOf(en("S_A", Enhancement.MULT), en("H_A", Enhancement.ABSTRACT)), 251.0),
        // sock_and_sock retriggers each Abstract card once.
        // Same Pair + sock_and_sock: H_A Abstract fires twice.
        //   Rep1: chips+=11→43, mult=6^1.15≈7.850. Rep2: chips+=11(wait: chips already at 43 after S_A),
        //   actually rep1 is the first H_A fire: chips=21+11=32, mult=6^1.15; rep2: chips=32+11=43, mult=(6^1.15)^1.15=6^1.3225≈10.693.
        //   Score: floor(43×10.693)=459.
        Case("Pair Mult-A + Abstract-A + sock_and_sock (retrigger Abstract once) → 459",
            listOf(en("S_A", Enhancement.MULT), en("H_A", Enhancement.ABSTRACT)), 459.0, j(FJoker("j_cry_sock_and_sock"))),
        // --- held-in-hand jokers ---
        // Baron: King held → X1.5 Mult; chips=32, mult=2*1.5=3 → 96.
        Case("Pair of aces + baron (King held)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_baron")), held = listOf(PlayingCard.parse("S_K"))),
        // baron + exponentia: Baron fires x_mult=1.5 in held pass → exponentia.x+=0.03 → Emult=1.03.
        // joker_main: baron null; exponentia eMult=1.03 → mult=3.0^1.03≈3.1005. floor(32×3.1005)=99.
        // (Pre-fix engine skipped exponentia increment for held-card xMult, giving floor(32×3.0)=96 instead.)
        Case("Pair of aces + baron + exponentia (held King → exp increments)", PlayingCard.hand("S_A", "H_A"), 99.0, j(FJoker("j_baron"), FJoker("j_cry_exponentia")), held = listOf(PlayingCard.parse("S_K"))),
        // Shoot the Moon: Queen held → +13 Mult; chips=32, mult=2+13=15 → 480.
        Case("Pair of aces + shoot_the_moon (Queen held)", PlayingCard.hand("S_A", "H_A"), 480.0, j(FJoker("j_shoot_the_moon")), held = listOf(PlayingCard.parse("S_Q"))),
        // Raised Fist: +2x nominal of lowest held card; 7 held → +14 Mult; chips=32, mult=2+14=16 → 512.
        Case("Pair of aces + raised_fist (7 held, lowest)", PlayingCard.hand("S_A", "H_A"), 512.0, j(FJoker("j_raised_fist")), held = listOf(PlayingCard.parse("S_7"))),
        // --- vanilla n-based jokers (joker_main; pre-set j.n via FJoker constructor) ---
        // Abstract: +3 Mult per joker on board (n=2: abstract + one other) → chips=32, mult=2+6=8 → 256.
        Case("Pair of aces + abstract (n=2 jokers)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_abstract", n = 2))),
        // Supernova: +Mult = times this hand type was played this run, INCLUDING the current hand (vanilla
        // increments G.GAME.hands[name].played before the joker pass). Read from ctx.scoringPlays, not j.n.
        // First Pair: scoringPlays=1 → mult=2+1=3 → 96 (the old run-loop wiring scored +0 here — off-by-one).
        Case("Pair of aces + supernova (1st play → +1)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_supernova"))),
        // 2 prior Pair plays → scoringPlays=3 → mult=2+3=5 → 160.
        Case("Pair of aces + supernova (2 prior plays → +3)", PlayingCard.hand("S_A", "H_A"), 160.0,
            j(FJoker("j_supernova")), handTypePlays = mapOf(HandType.PAIR to 2)),
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
        // Ramen at self-destruct threshold: j.x=1.0 exactly; guard is j.x > 1.0 → NO fire → base score=64.
        // (RunScreen removes ramen when x ≤ 1.0, but score engine must return 64 if it ever receives x=1.0.)
        Case("Pair of aces + ramen (x=1.0 — dead threshold, no xMult) → 64",
            PlayingCard.hand("S_A", "H_A"), 64.0, j(FJoker("j_ramen", x = 1.0))),
        // j_popcorn: +Mult = j.mult (starts 20, −1/hand). mult=19 (after 1 hand): chips=32, mult=2+19=21 → 672.
        Case("Pair of aces + popcorn (mult=19, hand 1) → 672",
            PlayingCard.hand("S_A", "H_A"), 672.0, j(FJoker("j_popcorn", mult = 19.0))),
        // j_popcorn last hand before death: mult=1 → chips=32, mult=2+1=3 → 96.
        Case("Pair of aces + popcorn (mult=1, last hand before death) → 96",
            PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_popcorn", mult = 1.0))),
        // Campfire: Xmult +0.25 per joker sold (j.x); x=1.5 (2 sold) → mult=2*1.5=3 → 96.
        Case("Pair of aces + campfire (x=1.5)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_campfire", x = 1.5))),
        // Loyalty Card: X4 when active (j.x=4.0); chips=32, mult=2*4=8 → 256.
        Case("Pair of aces + loyalty_card (x=4.0 active)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_loyalty_card")), totalHandsPlayed = 5),
        // Throwback: Xmult +0.25/skipped blind (j.x); x=1.5 (2 blinds) → mult=2*1.5=3 → 96.
        Case("Pair of aces + throwback (x=1.5)", PlayingCard.hand("S_A", "H_A"), 96.0, j(FJoker("j_throwback", x = 1.5))),
        // Runner: +Chips per Straight in run (j.chips); chips=30 → chips=32+30=62, mult=2 → 124.
        Case("Pair of aces + runner (chips=30)", PlayingCard.hand("S_A", "H_A"), 124.0, j(FJoker("j_runner", chips = 30.0))),
        // Square Joker: +4 Chips per 5-card hand (j.chips); chips=12 (3 plays) → chips=32+12=44, mult=2 → 88.
        Case("Pair of aces + square (chips=12)", PlayingCard.hand("S_A", "H_A"), 88.0, j(FJoker("j_square", chips = 12.0))),
        // Castle: +3 Chips per suited card discarded (j.chips); chips=9 (3 discards) → chips=32+9=41, mult=2 → 82.
        Case("Pair of aces + castle (chips=9)", PlayingCard.hand("S_A", "H_A"), 82.0, j(FJoker("j_castle", chips = 9.0))),
        // Hiker: permaBonus=10 on S_A (2 prior hiker triggers × +5). S_A chipBonus=11+10=21, H_A chipBonus=11.
        //   Pair base: chips=10+21+11=42, mult=2 → 84. H_A unchanged (permaBonus=0). No joker on board.
        Case("Pair S_A(perma=10)+H_A — permaBonus in chipBonus",
            listOf(PlayingCard(Suit.S, 14, permaBonus = 10), PlayingCard(Suit.H, 14)), 84.0),
        // Hiker with joker on board: joker fires individual (no immediate chips — uses old permaBonus=0).
        //   The +5 takes effect from the NEXT hand via the onPermaBonusGained callback (not wired in Oracle).
        //   Score this hand: chips=10+11+11=32, mult=2 → 64 (identical to no-hiker baseline).
        Case("Pair of aces + hiker (permaBonus=0, first hand)", PlayingCard.hand("S_A", "H_A"), 64.0, j(FJoker("j_hiker"))),
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
        // cry_mprime: Emult^j.x (default 1.05) per Jolly-type joker via other_joker pass (m.lua:1534).
        // Pair aces: chips=32, mult=2. j_jolly (PAIR) → +8 mult → mult=10. mprime sees j_jolly (isJolly) → eMult=1.05.
        // finalMult = 10^1.05 = 11.2202… → score = floor(32 × 11.2202) = 359.
        Case("Pair of aces + cry_mprime + j_jolly (Emult^1.05 via other_joker)", PlayingCard.hand("S_A", "H_A"), 359.0, j(FJoker("j_cry_mprime", x = 1.05), FJoker("j_jolly"))),
        // mprime counts an M-pool joker (foodm) as Jolly-equivalent (m.lua:1538 is_jolly() OR pools.M), so its
        // Emult^1.05 fires. foodm(+40 Mult) → mult 42, then mprime ^1.05 → 42^1.05; chips 32 → floor(32*42^1.05).
        // Without the M-pool fix mprime wouldn't recognize foodm → stays 1344 (= the foodm-alone case above).
        Case("Pair + cry_mprime + foodm@40 (M-pool → Emult^1.05) → 1620", PlayingCard.hand("S_A", "H_A"), 1620.0, j(FJoker("j_cry_mprime", x = 1.05), FJoker("j_cry_foodm", mult = 40.0))),
        // cry_bonk: +chips per board joker via other_joker pass (m.lua:695). j.chips per non-Jolly, j.chips*j.xc per Jolly.
        // The before-pass scales chips +1 (bonus) on a Pair (m.lua:669-678), so this Pair hand uses chips 6→7.
        // Board [bonk(6→7,xc=3), j_joker]: j_joker→+4 mult. bonk sees j_joker (non-Jolly)→+7; sees itself→+7.
        // chips=32+7+7=46, mult=2+4=6 → floor(46×6)=276.
        Case("Pair of aces + bonk(chips=6,xc=3) + j_joker (Pair scales chips 6→7)", PlayingCard.hand("S_A", "H_A"), 276.0, j(FJoker("j_cry_bonk", chips = 6.0, xc = 3.0), FJoker("j_joker"))),
        // Board [bonk(6→7,xc=3), j_jolly]: j_jolly(PAIR)→+8 mult. bonk sees j_jolly (Jolly)→7×3=21; sees itself→+7.
        // chips=32+21+7=60, mult=2+8=10 → floor(60×10)=600.
        Case("Pair of aces + bonk(chips=6,xc=3) + j_jolly (Jolly x-chips, Pair scales 6→7)", PlayingCard.hand("S_A", "H_A"), 600.0, j(FJoker("j_cry_bonk", chips = 6.0, xc = 3.0), FJoker("j_jolly"))),
        // Non-Pair hand → bonk's before-pass scaling does NOT fire (chips stays 6). High Card [A,K,Q,J,9]: only the Ace
        // scores → chips=5+11=16; bonk sees j_joker(+6) + itself(+6) → 28; j_joker +4 mult → 5. floor(28×5)=140.
        Case("HighCard + bonk(chips=6) + j_joker (non-Pair → no scale) → 140", PlayingCard.hand("S_A", "H_K", "D_Q", "C_J", "S_9"), 140.0,
            j(FJoker("j_cry_bonk", chips = 6.0, xc = 3.0), FJoker("j_joker"))),
        // --- Cryptid Emult jokers ---
        // cry_facile: eMult=3 when scored-card passes (check2) <=10 (exotic.lua:1005-1013).
        // Pair aces (2 cards, 0 retriggers): check2=2 ≤ 10 → fires. chips=32, mult=2^3=8 → 256.
        Case("Pair of aces + cry_facile (check2=2, fires)", PlayingCard.hand("S_A", "H_A"), 256.0, j(FJoker("j_cry_facile"))),
        // cry_facile suppressed when check2>10: j_cry_exposed adds +2 retriggers per non-face card.
        // Two Pair 2s/3s (S_2,H_2,S_3,H_3): 4 non-face cards × 3 passes = 12 > 10 → facile does NOT fire.
        // base chips=20, mult=2. Card chips: (2+2+3+3)×3 reps=30. chips=50, mult=2. Score=floor(50×2)=100.
        // (Without the guard the engine would fire mult^3=8 → 400 — the bug this test anchors.)
        Case("TwoPair 2s/3s + exposed + facile (check2=12, suppressed)", PlayingCard.hand("S_2", "H_2", "S_3", "H_3"), 100.0,
            j(FJoker("j_cry_exposed"), FJoker("j_cry_facile"))),
        // cry_stella_mortis: eMult=j.x when j.x>1.0. j.x=2.0: chips=32, mult=2^2=4 → 128.
        Case("Pair of aces + cry_stella_mortis (x=2.0)", PlayingCard.hand("S_A", "H_A"), 128.0, j(FJoker("j_cry_stella_mortis", x = 2.0))),
        // cry_circulus_pistoris: echips=PI (exponentiation), emult=PI when handsLeft==3.
        // chips=32^PI≈53526.41, mult=2^PI≈8.825 → floor(53526.41 * 8.825)=472369.
        // (Old value of 887 used xChipMod=PI i.e. chips*PI≈100.53 — that was wrong: echips is exponentiation.)
        Case("Pair of aces + cry_circulus_pistoris (handsLeft=3)", PlayingCard.hand("S_A", "H_A"), 472369.0, j(FJoker("j_cry_circulus_pistoris")), handsLeft = 3),
        // cry_filler: xMultMod≈1.0 always (meme joker). Pair aces: mult=2*1.00000000000003≈2.0 → chips=32*2=64.
        Case("Pair of aces + cry_filler (meme x1)", PlayingCard.hand("S_A", "H_A"), 64.0, j(FJoker("j_cry_filler"))),
        // cry_silly: +16 Mult if Full House. FH A/A/A/K/K: chips=93, mult=4+16=20 → 1860.
        Case("FullHouse A/A/A/K/K + cry_silly", PlayingCard.hand("S_A", "H_A", "D_A", "S_K", "H_K"), 1860.0, j(FJoker("j_cry_silly"))),
        // cry_crustulum: +Chips per card scored in play (j.chips accumulated). chips=20 → 32+20=52, mult=2 → 104.
        Case("Pair of aces + cry_crustulum (chips=20)", PlayingCard.hand("S_A", "H_A"), 104.0, j(FJoker("j_cry_crustulum", chips = 20.0))),
        // --- Cryptid custom hand types (CRY_NONE, CRY_BULWARK, CRY_ULTPAIR now live in Hands.evaluate) ---
        // Base values confirmed from SpectralPack/Cryptid lib/content.lua:
        //   CRY_NONE:  chips=0,  mult=0  (not 1 — score is 0×anything=0 with chips alone).
        //   CRY_BULWARK: chips=100, mult=10.  CRY_ULTPAIR: chips=220, mult=22.
        // CRY_NONE: mult=0 means chip-only jokers score 0. Need both chip AND mult jokers for non-zero.
        // j_cry_nebulous(+30c) + j_cry_undefined(+5m): chips=0+30=30, mult=0+5=5 → floor(30×5)=150.
        Case("CRY_NONE (empty hand) + cry_nebulous (+30c) + cry_undefined (+5m) → 150",
            emptyList(), 150.0, j(FJoker("j_cry_nebulous"), FJoker("j_cry_undefined"))),
        // + j_cry_the (X2 on CRY_NONE) — applied LAST (joker_main runs in board order, base mult=0): chips=30,
        // mult=(0+5)*2=10 → floor(30×10)=300. (cry_the FIRST would give mult=0*2+5=5 → 150, so order matters here.)
        Case("CRY_NONE + cry_nebulous(+30c) + cry_undefined(+5m) + cry_the(X2 last) → 300",
            emptyList(), 300.0, j(FJoker("j_cry_nebulous"), FJoker("j_cry_undefined"), FJoker("j_cry_the"))),
        // CRY_BULWARK: 5 Stone cards. Each Stone scores +50 chips (rank id=-1 → 0 rank chips; +50 bonus).
        // Base=100 chips, 10 mult. 5×50=250 stone chips → chips=350.
        // j_cry_stronghold X5 → mult=10×5=50 → floor(350×50)=17500.
        Case("CRY_BULWARK (5 stones) + cry_stronghold (X5) → 17500",
            listOf(en("S_2", Enhancement.STONE), en("H_3", Enhancement.STONE), en("D_4", Enhancement.STONE), en("C_5", Enhancement.STONE), en("S_6", Enhancement.STONE)),
            17500.0, j(FJoker("j_cry_stronghold"))),
        // 5 Stones + a non-Stone King → still CRY_BULWARK (#stones>=5, not all-stone). The King isn't in the
        // scoring hand (only stones score), so chips=350, and stronghold's containment guard fires X5 → 17500.
        // Before the fix this evaluated as HIGH_CARD (King), stronghold didn't fire → 265.
        Case("CRY_BULWARK (5 stones + 1 King) + cry_stronghold (X5) → 17500",
            listOf(en("S_2", Enhancement.STONE), en("H_3", Enhancement.STONE), en("D_4", Enhancement.STONE), en("C_5", Enhancement.STONE), en("S_6", Enhancement.STONE), PlayingCard.parse("H_K")),
            17500.0, j(FJoker("j_cry_stronghold"))),
        // poker_hands for a Bulwark also contains HIGH_CARD (get_highest is non-empty), so giggly (+4 Mult if
        // hand contains High Card) fires: chips=350, mult=10+4=14 → 4900. (Without the HIGH_CARD fix: 3500.)
        Case("CRY_BULWARK (5 stones) + giggly (HIGH_CARD present → +4 Mult) → 4900",
            listOf(en("S_2", Enhancement.STONE), en("H_3", Enhancement.STONE), en("D_4", Enhancement.STONE), en("C_5", Enhancement.STONE), en("S_6", Enhancement.STONE)),
            4900.0, j(FJoker("j_cry_giggly"))),
        // j_cry_adroit +170 Chips: chips=350+170=520, mult=10 → floor(520×10)=5200.
        Case("CRY_BULWARK (5 stones) + cry_adroit (+170 Chips) → 5200",
            listOf(en("S_2", Enhancement.STONE), en("H_3", Enhancement.STONE), en("D_4", Enhancement.STONE), en("C_5", Enhancement.STONE), en("S_6", Enhancement.STONE)),
            5200.0, j(FJoker("j_cry_adroit"))),
        // CRY_ULTPAIR: Two Two-Pairs each of a single suit (two different suits).
        // [Wild_S_A, S_A, H_K, H_K]: pair-of-Aces all Spades (Wild+S), pair-of-Kings all Hearts.
        // Base=220 chips, 22 mult. evalCard: Wild_S_A +11, S_A +11, H_K +10, H_K +10 → +42 chips.
        // Total chips=220+42=262, mult=22.
        // j_cry_clash X12 → mult=22×12=264 → floor(262×264)=69168.
        Case("CRY_ULTPAIR [WildA♠,A♠,K♥,K♥] + cry_clash (X12) → 69168",
            listOf(en("S_A", Enhancement.WILD), PlayingCard.parse("S_A"), PlayingCard.parse("H_K"), PlayingCard.parse("H_K")),
            69168.0, j(FJoker("j_cry_clash"))),
        // j_cry_treacherous +300 Chips: chips=262+300=562, mult=22 → floor(562×22)=12364.
        Case("CRY_ULTPAIR [WildA♠,A♠,K♥,K♥] + cry_treacherous (+300 Chips) → 12364",
            listOf(en("S_A", Enhancement.WILD), PlayingCard.parse("S_A"), PlayingCard.parse("H_K"), PlayingCard.parse("H_K")),
            12364.0, j(FJoker("j_cry_treacherous"))),
        // j_cry_foolhardy +42 Mult: chips=262, mult=22+42=64 → floor(262×64)=16768.
        Case("CRY_ULTPAIR [WildA♠,A♠,K♥,K♥] + cry_foolhardy (+42 Mult) → 16768",
            listOf(en("S_A", Enhancement.WILD), PlayingCard.parse("S_A"), PlayingCard.parse("H_K"), PlayingCard.parse("H_K")),
            16768.0, j(FJoker("j_cry_foolhardy"))),
        // Regression: Full House with suit-distinct triplet+pair must NOT be classified as CRY_ULTPAIR.
        // [S_A, S_A, S_A(triplet), H_K, H_K(pair)] → Full House, not CRY_ULTPAIR. pairSuit(AAA)=Spades, pairSuit(KK)=Hearts
        // (different suits) but top=FULL_HOUSE before Two Pair branch → CRY_ULTPAIR guard fires → no clash bonus.
        // Full House base: chips=40, mult=4. All 5 cards score: A,A,A,K,K = 11+11+11+10+10 = 53 card chips →
        // (40+53)*4 = 372. cry_clash checks scoringName==CRY_ULTPAIR → false (it's FULL_HOUSE), so no X12 fires.
        Case("FullHouse S_A×3,H_K×2 + cry_clash: scoringName=FULL_HOUSE (not CRY_ULTPAIR) → 372",
            PlayingCard.hand("S_A", "S_A", "S_A", "H_K", "H_K"), 372.0, j(FJoker("j_cry_clash"))),
        // --- CRY_CLUSTERFUCK hand type ---
        // CRY_CLUSTERFUCK: ≥8 non-Gold cards with no pairs, no flush, no straight.
        // Source: cry_cfpart (SpectralPack/Cryptid lib/content.lua): #eligible > 7 AND no _flush/_straight/_all_pairs.
        // Base: chips=200, mult=19. Scoring hand = all 8 eligible cards.
        // Case 1: 8 distinct-rank cards [A,K,J,10,9,8,6,5] — 4 suits (no flush), no 5-run (max run=4), no pairs.
        //   chips = 200+11+10+10+10+9+8+6+5 = 269, mult=19 → floor(269×19) = 5111.
        Case("CRY_CLUSTERFUCK 8 distinct cards (A,K,J,10,9,8,6,5) → 5111",
            PlayingCard.hand("S_A", "C_K", "H_J", "S_10", "D_9", "D_8", "S_6", "C_5"), 5111.0),
        // poker_hands for a Clusterfuck also contains HIGH_CARD, so giggly (+4 Mult) fires: chips=269, mult=19+4=23 → 6187. (Without fix: 5111.)
        Case("CRY_CLUSTERFUCK + giggly (HIGH_CARD present → +4 Mult) → 6187",
            PlayingCard.hand("S_A", "C_K", "H_J", "S_10", "D_9", "D_8", "S_6", "C_5"), 6187.0, j(FJoker("j_cry_giggly"))),
        // Case 2: same 8 cards + j_cry_wtf (X10 Mult on CRY_CLUSTERFUCK).
        //   chips=269, mult=19; wtf xMultMod=10 → mult=190. floor(269×190) = 51110.
        Case("CRY_CLUSTERFUCK + cry_wtf (X10 Mult) → 51110",
            PlayingCard.hand("S_A", "C_K", "H_J", "S_10", "D_9", "D_8", "S_6", "C_5"),
            51110.0, j(FJoker("j_cry_wtf"))),
        // Case 3: same cards + j_cry_fuckedup (+37 Mult on CRY_CLUSTERFUCK).
        //   chips=269, mult=19+37=56. floor(269×56) = 15064.
        Case("CRY_CLUSTERFUCK + cry_fuckedup (+37 Mult) → 15064",
            PlayingCard.hand("S_A", "C_K", "H_J", "S_10", "D_9", "D_8", "S_6", "C_5"),
            15064.0, j(FJoker("j_cry_fuckedup"))),
        // Case 4: same cards + j_cry_penetrating (+270 Chips on CRY_CLUSTERFUCK).
        //   chips=269+270=539, mult=19. floor(539×19) = 10241.
        Case("CRY_CLUSTERFUCK + cry_penetrating (+270 Chips) → 10241",
            PlayingCard.hand("S_A", "C_K", "H_J", "S_10", "D_9", "D_8", "S_6", "C_5"),
            10241.0, j(FJoker("j_cry_penetrating"))),
        // Case 5: 8 cards WITH a pair (S_A, H_A share rank) → NOT CRY_CLUSTERFUCK → falls to Pair.
        //   PAIR base=10c/2m; S_A+11→21, H_A+11→32. floor(32×2) = 64.
        Case("8 cards with pair of aces → Pair (not CRY_CLUSTERFUCK) → 64",
            PlayingCard.hand("S_A", "H_A", "C_K", "D_J", "S_10", "D_9", "H_8", "C_6"), 64.0),
        // --- THE_PILLAR (DebuffCards) ---
        // THE_PILLAR: cards played previously this Ante are debuffed by reference/value equality.
        // Case 1: Pair A/A where S_A was played before (in debuffCards set) → only H_A scores.
        //   PAIR base=10c/2m; S_A debuffed (skip); H_A +11c → chips=21, mult=2 → floor(21×2) = 42.
        Case("Pair S_A,H_A + THE_PILLAR (S_A previously played → debuffed)",
            PlayingCard.hand("S_A", "H_A"), 42.0,
            debuff = Debuff.DebuffCards(setOf(PlayingCard.parse("S_A")))),
        // Case 2: No previously-played cards → both aces score normally.
        //   PAIR base=10c/2m; S_A +11, H_A +11 → chips=32 → floor(32×2) = 64.
        Case("Pair S_A,H_A + THE_PILLAR (empty debuffed set → both score)",
            PlayingCard.hand("S_A", "H_A"), 64.0,
            debuff = Debuff.DebuffCards(emptySet())),
        // Case 3: Both aces previously played → both debuffed → base-only score.
        //   chips=10, mult=2 → floor(10×2) = 20.
        Case("Pair S_A,H_A + THE_PILLAR (both aces debuffed → base only)",
            PlayingCard.hand("S_A", "H_A"), 20.0,
            debuff = Debuff.DebuffCards(setOf(PlayingCard.parse("S_A"), PlayingCard.parse("H_A")))),
        // --- VERDANT_LEAF (DebuffAllCards) ---
        // All played cards are debuffed: only the joker_main pass contributes.
        // Case 1: Pair A/A + DebuffAllCards (no jokers) → base only.
        //   PAIR base=10c/2m; both aces debuffed → chips=10, mult=2 → floor(10×2) = 20.
        Case("Pair A/A + DebuffAllCards (VERDANT_LEAF, no jokers) → base only",
            PlayingCard.hand("S_A", "H_A"), 20.0,
            debuff = Debuff.DebuffAllCards),
        // Case 2: Pair A/A + DebuffAllCards + j_joker (+4 Mult in joker_main pass, which still fires).
        //   chips=10, mult=2; j_joker +4m → mult=6. floor(10×6) = 60.
        Case("Pair A/A + DebuffAllCards + j_joker → joker_main still fires",
            PlayingCard.hand("S_A", "H_A"), 60.0, j(FJoker("j_joker")),
            debuff = Debuff.DebuffAllCards),
        // --- CRIMSON_HEART (debuffedJokerKey) ---
        // The named joker key is silenced for this hand; other jokers score normally.
        // Case 1: Pair A/A + j_joker where j_joker is the disabled key → base only.
        //   PAIR base=10c/2m; S_A+11, H_A+11 → chips=32, mult=2; j_joker suppressed.
        //   floor(32×2) = 64.
        Case("Pair A/A + j_joker disabled (CRIMSON_HEART) → j_joker suppressed → 64",
            PlayingCard.hand("S_A", "H_A"), 64.0, j(FJoker("j_joker")),
            debuffedJokerKey = "j_joker"),
        // Case 2: Pair A/A + j_joker + j_fibonacci, j_joker disabled → only fibonacci fires.
        //   fibonacci: +8 Mult per scored Fibonacci card (A/2/3/5/8): A+A = +16m → mult=2+16=18.
        //   chips=32, mult=18 → floor(32×18) = 576.
        Case("Pair A/A + j_joker(disabled) + j_fibonacci → fibonacci still scores → 576",
            PlayingCard.hand("S_A", "H_A"), 576.0, j(FJoker("j_joker"), FJoker("j_fibonacci")),
            debuffedJokerKey = "j_joker"),
    )

    fun run(): Pair<Int, Int> {
        var pass = 0
        for (c in cases) {
            val score = Score.score(c.hand, c.jokers, c.held, c.level, c.debuff, c.handsLeft, c.discardsLeft, c.bossBlind, c.debuffedJokerKey, c.handTypePlays, c.totalHandsPlayed).score
            val ok = score == c.expected
            if (ok) pass++
            println("${if (ok) "PASS" else "FAIL"}  ${c.name}: got $score expected ${c.expected}")
        }
        println("oracle-parity: $pass/${cases.size}")
        return pass to cases.size
    }

    /** Multi-call regression tests that require the SAME joker object to persist across two score() calls. */
    fun runMultiCall(): Pair<Int, Int> {
        var pass = 0; var total = 0
        // spectrogram cross-hand reset regression (epic.lua:2047-2053 resets echonum=0 in before pass each hand).
        // Fix: before-pass sets j.n = scoringHand.count { Echo } each call — always overwrites, never accumulates.
        // Hand 1: Pair Echo-Aces + spectrogram(n=0) + joker → j.n=2 → joker fires 3x → 448.
        // Hand 2: same jokers, same hand → before-pass overwrites j.n=2 again → joker again fires 3x → 448.
        // (Old accumulation bug: hand 2 entered with j.n=2, added 2 more → j.n=4 → 5x → 32*22=704.)
        run {
            val spectr = FJoker("j_cry_spectrogram")
            val joker  = FJoker("j_joker")
            val board  = listOf(spectr, joker)
            val echoHand = listOf(en("S_A", Enhancement.ECHO), en("H_A", Enhancement.ECHO))
            for (handNum in 1..2) {
                total++
                val s = Score.score(echoHand, board, emptyList(), 1).score
                val ok = s == 448.0
                if (ok) pass++
                println("${if (ok) "PASS" else "FAIL"}  spectrogram cross-hand reset (hand $handNum): got $s expected 448.0")
            }
        }
        println("oracle-multi-call: $pass/$total")
        return pass to total
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val (pass, total) = run()
        val (mPass, mTotal) = runMultiCall()
        if (pass != total || mPass != mTotal) kotlin.system.exitProcess(1)
    }
}
