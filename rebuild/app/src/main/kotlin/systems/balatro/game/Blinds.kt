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
 * Boss blinds — 22 regular bosses (Antes 1-9) + 5 Ante-10 showdowns.
 *
 * targetMult = the FULL blind multiplier (all regular bosses = 2.0, THE_WALL = 4.0,
 * VIOLET_VESSEL = 6.0). RunScreen uses "base * (boss?.targetMult ?: 2.0)".
 *
 * Regular bosses with round-state effects (THE_EYE, THE_MOUTH, THE_ARM, THE_OX, THE_TOOTH,
 * THE_HOOK, THE_PSYCHIC, THE_SERPENT, THE_MANACLE) are wired in RunState.
 * Face-down bosses (THE_HOUSE, THE_MARK, THE_WHEEL, THE_FISH) have no scoring impact; their
 * face-down visuals are handled by RunState.faceDown + applyFaceDown() + the CardFace render branch.
 *
 * Ante-10 showdowns:
 *   VERDANT_LEAF   — all played cards are debuffed (only jokers score); selling a joker defeats it
 *   VIOLET_VESSEL  — ×6 score target (no other mechanic)
 *   AMBER_ACORN    — joker order is shuffled when the blind starts
 *   CRIMSON_HEART  — a random joker is disabled after each play (rotates each hand)
 *   CERULEAN_BELL  — one card in hand is forced-selected (always included in play)
 */
enum class Boss(val display: String, val desc: String) {
    // ── scoring debuffs ─────────────────────────────────────────────────────────────────────
    THE_FLINT("The Flint",   "Base Chips and Mult are halved"),
    THE_CLUB ("The Club",    "All Club cards are debuffed"),
    THE_GOAD ("The Goad",    "All Spade cards are debuffed"),
    THE_WINDOW("The Window", "All Diamond cards are debuffed"),
    THE_HEAD ("The Head",    "All Heart cards are debuffed"),
    THE_PLANT("The Plant",   "All face cards are debuffed"),
    // ── structural: target / hands / discards ────────────────────────────────────────────────
    THE_WALL    ("The Wall",     "Extra large blind (x4)"),
    THE_NEEDLE  ("The Needle",   "Only 1 hand"),
    THE_WATER   ("The Water",    "Start with 0 discards"),
    THE_MANACLE ("The Manacle",  "+1 Hand, -1 Joker slot"),
    // ── round-state effects (wired in RunState) ──────────────────────────────────────────────
    THE_PSYCHIC ("The Psychic",  "Must play exactly 5 cards"),
    THE_HOOK    ("The Hook",     "Discards 2 random cards each play"),
    THE_TOOTH   ("The Tooth",    "Lose \$1 per card played"),
    THE_ARM     ("The Arm",      "Played hand level degrades by 1"),
    THE_EYE     ("The Eye",      "Each hand type can only be played once"),
    THE_MOUTH   ("The Mouth",    "Only 1 hand type may be played"),
    THE_OX      ("The Ox",       "Playing most-played hand sets money to \$0"),
    THE_SERPENT ("The Serpent",  "After each play, draw a new hand"),
    // ── face-down (no scoring effect; visual flip handled in RunState/RunScreen) ─────────────
    THE_HOUSE   ("The House",    "First hand is drawn face down"),
    THE_MARK    ("The Mark",     "All face cards are drawn face down"),
    THE_WHEEL   ("The Wheel",    "1 in 7 cards is drawn face down"),
    THE_FISH    ("The Fish",     "Cards drawn face down after each play"),
    THE_PILLAR  ("The Pillar",   "Previously played cards are debuffed"),
    // ── Ante-10 showdowns ────────────────────────────────────────────────────────────────────
    VERDANT_LEAF  ("Verdant Leaf",  "All played cards are debuffed"),
    VIOLET_VESSEL ("Violet Vessel", "Requires 6x more Chips to beat"),
    AMBER_ACORN   ("Amber Acorn",   "Jokers are shuffled at blind start"),
    CRIMSON_HEART ("Crimson Heart", "One random Joker disabled each hand"),
    CERULEAN_BELL ("Cerulean Bell", "One card is forced to be selected");

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

    companion object {
        /** Boss pool for a given ante. Antes 1-9 draw from the 22 regular bosses;
         *  Ante 10+ always presents one of the 5 showdown bosses. */
        private val REGULAR  = values().filter { it.ordinal < 22 }   // first 22 entries (index 0..21)
        private val SHOWDOWN = listOf(VERDANT_LEAF, VIOLET_VESSEL, AMBER_ACORN, CRIMSON_HEART, CERULEAN_BELL)
        fun pool(ante: Int): List<Boss> = if (ante >= 10) SHOWDOWN else REGULAR
    }
}
