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
}

/**
 * Boss blinds — all 22 regular (non-Ante-10) bosses from vanilla Balatro.
 * Ante-10 showdowns (Verdant Leaf, Violet Vessel, Amber Acorn, Crimson Heart, Cerulean Bell)
 * are out of scope until the full ante progression is wired.
 *
 * targetMult = the FULL blind multiplier (replaces the old "base * 2.0 * targetMult" formula;
 * RunScreen now uses "base * (boss?.targetMult ?: 2.0)" so all regular bosses default to 2.0).
 *
 * Bosses with round-state effects (THE_EYE, THE_MOUTH, THE_ARM, THE_OX, THE_TOOTH,
 * THE_HOOK, THE_PSYCHIC, THE_SERPENT, THE_MANACLE) are wired in RunState.
 * Face-down bosses (THE_HOUSE, THE_MARK, THE_WHEEL, THE_FISH, THE_PILLAR) have no scoring
 * impact; their face-down visuals are a UI stub.
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
    // ── face-down stubs (no scoring effect) ─────────────────────────────────────────────────
    THE_HOUSE   ("The House",    "First hand is drawn face down"),
    THE_MARK    ("The Mark",     "All face cards are drawn face down"),
    THE_WHEEL   ("The Wheel",    "1 in 7 cards is drawn face down"),
    THE_FISH    ("The Fish",     "Cards drawn face down after each play"),
    THE_PILLAR  ("The Pillar",   "Previously played cards are debuffed");

    val scoringDebuff: Debuff
        get() = when (this) {
            THE_FLINT  -> Debuff.Flint
            THE_CLUB   -> Debuff.DebuffSuit(Suit.C)
            THE_GOAD   -> Debuff.DebuffSuit(Suit.S)
            THE_WINDOW -> Debuff.DebuffSuit(Suit.D)
            THE_HEAD   -> Debuff.DebuffSuit(Suit.H)
            THE_PLANT  -> Debuff.DebuffFace
            else       -> Debuff.None
        }

    /**
     * Full blind multiplier — RunScreen applies `base * (boss?.targetMult ?: 2.0)`.
     * Most bosses are x2 (standard boss). THE_WALL is x4, Violet Vessel would be x6
     * (not in pool until Ante-10 is wired).
     */
    val targetMult: Double
        get() = when (this) {
            THE_WALL -> 4.0
            else     -> 2.0
        }

    fun hands(default: Int): Int = when (this) {
        THE_NEEDLE  -> 1
        THE_MANACLE -> default + 1    // +1 Hand; TODO: also grants -1 Joker slot (not yet modelled)
        else        -> default
    }

    fun discards(default: Int): Int = if (this == THE_WATER) 0 else default
}
