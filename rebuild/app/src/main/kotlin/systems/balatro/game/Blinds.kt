package systems.balatro.game

/**
 * A boss blind's SCORING debuff — applied during scoreDetailed, composable like every other
 * modifier (None for Small/Big blinds). Structural debuffs (fewer hands, bigger target) live
 * on the Boss itself and shape the round, not the score.
 */
sealed interface Debuff {
    object None : Debuff
    object Flint : Debuff                       // base Chips and Mult halved (floored)
    data class DebuffSuit(val suit: Suit) : Debuff   // cards of this suit score nothing & trigger nothing
}

/** Boss blinds. Each carries either a scoring debuff or a structural one (target/hands/discards). */
enum class Boss(val display: String, val desc: String) {
    THE_WALL("The Wall", "Extra large blind (x2)"),
    THE_NEEDLE("The Needle", "Only 1 hand"),
    THE_WATER("The Water", "Start with 0 discards"),
    THE_FLINT("The Flint", "Base Chips and Mult are halved"),
    THE_CLUB("The Club", "All Club cards are debuffed"),
    THE_GOAD("The Goad", "All Spade cards are debuffed"),
    THE_WINDOW("The Window", "All Diamond cards are debuffed"),
    THE_HEAD("The Head", "All Heart cards are debuffed");

    val scoringDebuff: Debuff
        get() = when (this) {
            THE_FLINT -> Debuff.Flint
            THE_CLUB -> Debuff.DebuffSuit(Suit.C)
            THE_GOAD -> Debuff.DebuffSuit(Suit.S)
            THE_WINDOW -> Debuff.DebuffSuit(Suit.D)
            THE_HEAD -> Debuff.DebuffSuit(Suit.H)
            else -> Debuff.None
        }

    val targetMult: Double get() = if (this == THE_WALL) 2.0 else 1.0
    fun hands(default: Int): Int = if (this == THE_NEEDLE) 1 else default
    fun discards(default: Int): Int = if (this == THE_WATER) 0 else default
}
