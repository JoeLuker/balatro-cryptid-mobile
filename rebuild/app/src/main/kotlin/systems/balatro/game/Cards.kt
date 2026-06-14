package systems.balatro.game

/** A playing card. rank: 2..14 (T=10, J=11, Q=12, K=13, A=14). */
enum class Suit { S, H, D, C }

data class PlayingCard(val suit: Suit, val rank: Int) {
    /** Chip value: 2-9 = pip, T/J/Q/K = 10, A = 11. */
    val chips: Int get() = when {
        rank == 14 -> 11
        rank >= 10 -> 10
        else -> rank
    }

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
