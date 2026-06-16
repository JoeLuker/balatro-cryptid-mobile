package systems.balatro.content

/** A joker edition (shop tag). Foil +50 Chips, Holo +10 Mult, Poly x1.5 Mult — applied in the
 *  Score engine's joker_main pass (mapped to FJoker.edition by buy()). */
enum class Edition(val tag: String) { NONE(""), FOIL("Foil"), HOLO("Holo"), POLY("Poly") }
