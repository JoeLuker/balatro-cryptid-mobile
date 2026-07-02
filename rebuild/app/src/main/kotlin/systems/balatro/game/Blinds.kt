package systems.balatro.game

/**
 * A boss blind's SCORING debuff — applied during scoreDetailed, composable like every other
 * modifier (None for Small/Big blinds). Structural debuffs (fewer hands, bigger target,
 * round-state gates) live on the Boss itself and shape the round, not the score.
 */
sealed interface Debuff {
    object None : Debuff
    object Flint : Debuff                          // base Chips and Mult halved (floored)
    data class DebuffSuit(val suit: Suit) : Debuff // cards of this suit score nothing & trigger nothing
    object DebuffFace : Debuff                     // all face cards (J/Q/K, incl. Pareidolia) score/trigger nothing
    /** THE_PILLAR: specific card instances that were played in a previous hand this Ante are debuffed.
     *  Matched by structural equality (PlayingCard is a data class; suit+rank+enhancement+seal). */
    data class DebuffCards(val cards: Set<PlayingCard>) : Debuff
    /** VERDANT_LEAF: all played cards are debuffed — only joker effects contribute to scoring. */
    object DebuffAllCards : Debuff
}

/**
 * Boss blinds — 23 regular bosses + 5 showdown finishers (P_BLINDS, game.lua:264-294).
 *
 * targetMult = the FULL blind multiplier (all regular bosses = 2.0, THE_WALL = 4.0,
 * VIOLET_VESSEL = 6.0). RunScreen uses "base * (boss?.targetMult ?: 2.0)".
 *
 * minAnte = vanilla `boss.min` — the earliest ante the boss may appear at. showdown bosses
 * appear ONLY on win_ante multiples (ante 8, and 16/24… in endless) and ignore minAnte
 * (get_new_boss's showdown branch never reads it; the P_BLINDS min=10 there is vestigial).
 *
 * Regular bosses with round-state effects (THE_EYE, THE_MOUTH, THE_ARM, THE_OX, THE_TOOTH,
 * THE_HOOK, THE_PSYCHIC, THE_SERPENT, THE_MANACLE) are wired in RunState.
 * Face-down bosses (THE_HOUSE, THE_MARK, THE_WHEEL, THE_FISH) have no scoring impact; their
 * face-down visuals are handled by RunState.faceDown + applyFaceDown() + the CardFace render branch.
 *
 * Showdown finishers:
 *   VERDANT_LEAF   — all played cards are debuffed (only jokers score); selling a joker defeats it
 *   VIOLET_VESSEL  — ×6 score target (no other mechanic)
 *   AMBER_ACORN    — joker order is shuffled when the blind starts
 *   CRIMSON_HEART  — a random joker is disabled after each play (rotates each hand)
 *   CERULEAN_BELL  — one card in hand is forced-selected (always included in play)
 */
enum class Boss(val display: String, val desc: String, val minAnte: Int = 1, val showdown: Boolean = false) {
    // ── scoring debuffs ─────────────────────────────────────────────────────────────────────
    THE_FLINT("The Flint",   "Base Chips and Mult are halved",  minAnte = 2),
    THE_CLUB ("The Club",    "All Club cards are debuffed"),
    THE_GOAD ("The Goad",    "All Spade cards are debuffed"),
    THE_WINDOW("The Window", "All Diamond cards are debuffed"),
    THE_HEAD ("The Head",    "All Heart cards are debuffed"),
    THE_PLANT("The Plant",   "All face cards are debuffed",     minAnte = 4),
    // ── structural: target / hands / discards ────────────────────────────────────────────────
    THE_WALL    ("The Wall",     "Extra large blind (x4)",      minAnte = 2),
    THE_NEEDLE  ("The Needle",   "Only 1 hand",                 minAnte = 2),
    THE_WATER   ("The Water",    "Start with 0 discards",       minAnte = 2),
    THE_MANACLE ("The Manacle",  "+1 Hand, -1 Joker slot"),
    // ── round-state effects (wired in RunState) ──────────────────────────────────────────────
    THE_PSYCHIC ("The Psychic",  "Must play exactly 5 cards"),
    THE_HOOK    ("The Hook",     "Discards 2 random cards each play"),
    THE_TOOTH   ("The Tooth",    "Lose \$1 per card played",    minAnte = 3),
    THE_ARM     ("The Arm",      "Played hand level degrades by 1", minAnte = 2),
    THE_EYE     ("The Eye",      "Each hand type can only be played once", minAnte = 3),
    THE_MOUTH   ("The Mouth",    "Only 1 hand type may be played", minAnte = 2),
    THE_OX      ("The Ox",       "Playing most-played hand sets money to \$0", minAnte = 6),
    THE_SERPENT ("The Serpent",  "After each play, draw a new hand", minAnte = 5),
    // ── face-down (no scoring effect; visual flip handled in RunState/RunScreen) ─────────────
    THE_HOUSE   ("The House",    "First hand is drawn face down", minAnte = 2),
    THE_MARK    ("The Mark",     "All face cards are drawn face down", minAnte = 2),
    THE_WHEEL   ("The Wheel",    "1 in 7 cards is drawn face down", minAnte = 2),
    THE_FISH    ("The Fish",     "Cards drawn face down after each play", minAnte = 2),
    THE_PILLAR  ("The Pillar",   "Previously played cards are debuffed"),
    // ── showdown finishers (win_ante multiples) ──────────────────────────────────────────────
    VERDANT_LEAF  ("Verdant Leaf",  "All played cards are debuffed",       minAnte = 10, showdown = true),
    VIOLET_VESSEL ("Violet Vessel", "Requires 6x more Chips to beat",      minAnte = 10, showdown = true),
    AMBER_ACORN   ("Amber Acorn",   "Jokers are shuffled at blind start",  minAnte = 10, showdown = true),
    CRIMSON_HEART ("Crimson Heart", "One random Joker disabled each hand", minAnte = 10, showdown = true),
    CERULEAN_BELL ("Cerulean Bell", "One card is forced to be selected",   minAnte = 10, showdown = true);

    val scoringDebuff: Debuff
        get() = when (this) {
            THE_FLINT    -> Debuff.Flint
            THE_CLUB     -> Debuff.DebuffSuit(Suit.C)
            THE_GOAD     -> Debuff.DebuffSuit(Suit.S)
            THE_WINDOW   -> Debuff.DebuffSuit(Suit.D)
            THE_HEAD     -> Debuff.DebuffSuit(Suit.H)
            THE_PLANT    -> Debuff.DebuffFace
            VERDANT_LEAF -> Debuff.DebuffAllCards
            else         -> Debuff.None
        }

    /**
     * Full blind multiplier — RunScreen applies `base * (boss?.targetMult ?: 2.0)`.
     * Most bosses are x2 (standard boss). THE_WALL is x4, VIOLET_VESSEL is x6.
     */
    val targetMult: Double
        get() = when (this) {
            THE_WALL      -> 4.0
            VIOLET_VESSEL -> 6.0
            else          -> 2.0
        }

    fun hands(default: Int): Int = when (this) {
        THE_NEEDLE  -> 1
        THE_MANACLE -> default + 1    // +1 Hand; -1 Joker slot wired in RunState (maxJokers -= 1)
        else        -> default
    }

    fun discards(default: Int): Int = if (this == THE_WATER) 0 else default

    /** Reward for defeating this boss — vanilla config.dollars (regular $5, showdown $8). */
    val dollars: Int get() = if (showdown) 8 else 5

    companion object {
        /**
         * Pick the boss for [ante] — faithful port of get_new_boss()
         * (functions/common_events.lua:2338):
         *  - showdown antes (ante % winAnte == 0 && ante >= 2) draw ONLY showdown bosses;
         *  - every other ante draws regular bosses whose minAnte <= max(1, ante);
         *  - among the eligible, only the least-picked this run (min_use over [used]) are drawable,
         *    so the full pool cycles before any boss repeats.
         * The caller owns [used] (G.GAME.bosses_used) and increments the count of the returned boss.
         */
        fun next(ante: Int, used: Map<Boss, Int>, rng: kotlin.random.Random, winAnte: Int = 8): Boss {
            val showdownAnte = ante % winAnte == 0 && ante >= 2
            val eligible = values().filter {
                if (it.showdown) showdownAnte else !showdownAnte && it.minAnte <= maxOf(1, ante)
            }
            val minUse = eligible.minOf { used[it] ?: 0 }
            val pool = eligible.filter { (used[it] ?: 0) == minUse }
            return pool[rng.nextInt(pool.size)]
        }
    }
}
