package systems.balatro.game

/** A playing card. rank: 2..14 (T=10, J=11, Q=12, K=13, A=14). */
enum class Suit { S, H, D, C }

/**
 * A card enhancement (from tarots). Played-card effects: Bonus +30 Chips, Mult +4 Mult,
 * Glass x2 Mult. Held-in-hand effects: Steel x1.5 Mult while held, Gold +$3 held at round end.
 * Cryptid additions:
 *   ABSTRACT (^Emult when played, never a face; Emult=1.15 confirmed from SpectralPack/Cryptid items/misc.lua).
 *   ECHO (m_cry_echo — probabilistic retrigger when scored; no per-card chip/mult change;
 *     triggers the Spectrogram joker accumulator when scored). Retrigger probability and
 *     count are pseudoseed-based and not modelled in the deterministic score engine.
 */
enum class Enhancement(val badge: String) { NONE(""), BONUS("+30c"), MULT("+4m"), GLASS("x2"), STEEL("x1.5h"), GOLD("$"), WILD("wild"), STONE("+50"), ABSTRACT("^E"), ECHO("~") }

/** A card seal. Red retriggers the card when played; Gold pays $3 when played. (Blue/Purple: later.) */
enum class Seal(val badge: String) { NONE(""), RED("R"), GOLD("G") }

data class PlayingCard(val suit: Suit, val rank: Int, val enhancement: Enhancement = Enhancement.NONE, val seal: Seal = Seal.NONE, val edition: String = "") {
    /** Chip value: 2-9 = pip, T/J/Q/K = 10, A = 11. */
    val chips: Int get() = when {
        rank == 14 -> 11
        rank >= 10 -> 10
        else -> rank
    }

    /** Card:get_id() — the poker rank id (A=14). Stone and Abstract cards return a value OUTSIDE
     *  2..14 so they never form rank hands (Stone: -1, Abstract: -2; hand helpers skip non-2..14). */
    val id: Int get() = when (enhancement) {
        Enhancement.STONE -> -1
        Enhancement.ABSTRACT -> -2   // specific_rank = "cry_abstract": not a poker rank
        else -> rank
    }

    /** Card:get_nominal() ordering for High Card — highest rank wins; non-rank cards rank lowest. */
    val nominal: Int get() = when (enhancement) {
        Enhancement.STONE, Enhancement.ABSTRACT -> -1000
        else -> rank
    }

    /** Card:is_face(pareidolia) — J/Q/K (id 11..13); with Pareidolia all id>0 cards are face.
     *  Stone (id=-1) and Abstract (id=-2) are never face (id ≤ 0).
     *  card.lua:1217: (id > 0 and rank.face) or find_joker("Pareidolia"); Abstract explicitly returns nil (line 1213). */
    fun isFace(pareidolia: Boolean = false): Boolean = id > 0 && (pareidolia || id in 11..13)

    /** Card:is_suit(flush_calc) — Stone/Abstract never, Wild any, Smeared makes red/black collide, else exact.
     *  Abstract has specific_suit="cry_abstract" — not a standard suit, never matches. */
    fun isSuit(suit: Suit, smeared: Boolean = false): Boolean = when {
        enhancement == Enhancement.STONE || enhancement == Enhancement.ABSTRACT -> false
        enhancement == Enhancement.WILD -> true
        smeared -> (suit == Suit.H || suit == Suit.D) == (this.suit == Suit.H || this.suit == Suit.D)
        else -> this.suit == suit
    }

    private val rankChar: String get() = when (rank) { 14 -> "A"; 13 -> "K"; 12 -> "Q"; 11 -> "J"; 10 -> "T"; else -> rank.toString() }
    /** Oracle/telemetry key, e.g. "S_A". */
    val key: String get() = "${suit.name}_$rankChar"
    /** Human label, e.g. "A♠". */
    val label: String get() = rankChar + when (suit) { Suit.S -> "♠"; Suit.H -> "♥"; Suit.D -> "♦"; Suit.C -> "♣" }

    companion object {
        // oracle card key: "<suit>_<rank>" e.g. S_A, H_K, D_T, C_7
        fun parse(key: String): PlayingCard {
            val (s, r) = key.split("_")
            val suit = Suit.valueOf(s)
            val rank = when (r) {
                "A" -> 14; "K" -> 13; "Q" -> 12; "J" -> 11; "T" -> 10
                else -> r.toInt()
            }
            return PlayingCard(suit, rank)
        }
        fun hand(vararg keys: String): List<PlayingCard> = keys.map { parse(it) }
    }
}
