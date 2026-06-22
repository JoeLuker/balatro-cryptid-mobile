package systems.balatro.content

/** A joker edition (shop tag). Foil +50 Chips, Holo +10 Mult, Poly x1.5 Mult — applied in the
 *  Score engine's joker_main pass (mapped to FJoker.edition by buy()). Negative has NO scoring
 *  effect (buy() maps it to no FJoker edition); it grants +1 joker slot and renders inverted. */
enum class Edition(val tag: String) { NONE(""), FOIL("Foil"), HOLO("Holo"), POLY("Poly"), NEGATIVE("Negative") }
