package systems.balatro.game

/**
 * The number type for scores. Balatro+Cryptid+Talisman scores blow past f64 (e1000+),
 * so every chip/mult value is a BigValue, not a Double. This is the seam where the
 * Talisman/OmegaNum semantics live — for now a thin wrapper to get the architecture
 * compiling and the scoring loop correct at human scale; the mantissa/exponent
 * (OmegaNum) representation drops in behind this same surface without touching callers.
 *
 * Kept allocation-light deliberately: arithmetic returns new immutable values, but the
 * scoring hot loop reuses one Tally, so per-hand allocation stays bounded.
 */
@JvmInline
value class BigValue(val v: Double) {
    operator fun plus(o: BigValue) = BigValue(v + o.v)
    operator fun times(o: BigValue) = BigValue(v * o.v)
    operator fun compareTo(o: BigValue): Int = v.compareTo(o.v)

    companion object {
        val ZERO = BigValue(0.0)
        val ONE = BigValue(1.0)
        fun of(x: Double) = BigValue(x)
        fun of(x: Int) = BigValue(x.toDouble())
    }
    override fun toString() = v.toString()  // TODO: OmegaNum notation
}
