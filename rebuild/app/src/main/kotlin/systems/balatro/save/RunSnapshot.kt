package systems.balatro.save

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The serializable graph of an in-progress run (P4 RunStateSerialization). Distinct from profile
 * persistence — this is `save_run`/`load` over the live run: jokers (+ their FJoker scaling state),
 * the deck composition (enhancements/seals stick), hand levels, the voucher/tag run-modifiers, and
 * the blind/money progression. RunState.snapshot()/restore() bridge the Compose state to this; the
 * model itself is pure (enums captured by name) so it round-trips losslessly via kotlinx-serialization
 * and is verifiable off-device. Kotlin's own JSON format, not LÖVE's STR_PACK byte format.
 */
@Serializable
data class CardSnap(val suit: String, val rank: Int, val enh: String, val seal: String, val permaBonus: Int = 0, val edition: String = "")

@Serializable
data class ConsumableSnap(val kind: String, val name: String, val enh: String = "", val seal: String = "", val planet: String = "")

@Serializable
data class JokerSnap(
    val key: String, val name: String, val desc: String, val cost: Int, val edition: String,
    val fjEdition: String, val mult: Double, val x: Double, val chips: Double,
    val n: Int, val rarity: Int, val xc: Double,
    val sellBonus: Int = 0,   // Gift Card extra_value — accumulated $ added to this joker's sell value
)

// Shop-stock snaps — so a mid-shop save resumes the EXACT shop (the real post-purchase stock,
// not a fresh re-roll, which would re-offer already-bought cards).
@Serializable data class OfferSnap(val key: String, val name: String, val desc: String, val cost: Int, val edition: String)
@Serializable data class PlanetSnap(val planet: String, val cost: Int)
@Serializable data class TarotSnap(val name: String, val enh: String, val cost: Int, val seal: String)
/** One slot of the unified shop pool (vanilla's single mixed CardArea). `kind` selects which payload
 *  is populated. An ordered list of these preserves the per-slot poll order across save/load. */
@Serializable data class ShopItemSnap(
    val kind: String,                       // "joker" | "planet" | "tarot" | "spectral"
    val joker: OfferSnap? = null,
    val planet: PlanetSnap? = null,
    val tarot: TarotSnap? = null,
    val spectral: String? = null,           // Spectral enum name (Ghost-deck shop spectrals)
    val card: CardSnap? = null,             // Magic Trick: a playing card offered in the shop
)
@Serializable data class VoucherSnap(val key: String, val name: String, val desc: String, val extra: Int, val cost: Int)
@Serializable data class BoosterSnap(val key: String, val name: String, val kind: String, val cost: Int, val extra: Int, val choose: Int)

@Serializable
data class RunSnapshot(
    val blindIndex: Int,
    val money: Int,
    val jokers: List<JokerSnap>,
    val deck: List<CardSnap>,
    val handLevels: Map<String, Int>,
    val shopSlotsBonus: Int,
    val discountPercent: Int,
    val interestCap: Int,
    val baseHands: Int,
    val baseDiscards: Int,
    val rerollBase: Int,
    val redeemedVouchers: List<String>,
    val tags: List<String>,
    val consumables: List<ConsumableSnap> = emptyList(),
    // exact mid-shop resume: the phase to land in + the live shop stock + per-shop reroll/tag state
    val phase: String = "BLIND_SELECT",
    val shopItems: List<ShopItemSnap> = emptyList(),
    val shopVoucher: VoucherSnap? = null,
    val shopBoosters: List<BoosterSnap> = emptyList(),
    val rerollIncrease: Int = 0,
    val freeRerollThisShop: Boolean = false,
    val couponThisShop: Boolean = false,
    val baseHandSize: Int = 8,
    val stakeLevel: Int = 1,
    val spectralRate: Double = 0.0,
    val tarotRate: Double = 4.0,
    val planetRate: Double = 4.0,
    val telescope: Boolean = false,
    val greenEconomy: Boolean = false,
    val anaglyph: Boolean = false,
    val doubleNextTags: Int = 0,
    val directorsCut: Boolean = false,
    val retcon: Boolean = false,
    val bossReshuffle: Int = 0,
    val omenGlobe: Boolean = false,
    val cardRate: Double = 0.0,
) {
    fun encode(): String = Json.encodeToString(this)
    companion object {
        fun decode(s: String): RunSnapshot = Json.decodeFromString(s)
    }
}
