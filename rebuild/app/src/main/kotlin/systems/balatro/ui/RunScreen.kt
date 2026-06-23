package systems.balatro.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import systems.balatro.bridge.Telemetry
import systems.balatro.content.Edition
import systems.balatro.engine.EaseSpec
import systems.balatro.engine.EngineHost
import systems.balatro.engine.Event
import systems.balatro.engine.Moveable
import systems.balatro.engine.Room
import kotlin.math.floor
import systems.balatro.game.*
import systems.balatro.save.RunSnapshot
import systems.balatro.save.JokerSnap
import systems.balatro.save.CardSnap
import systems.balatro.save.ConsumableSnap
import systems.balatro.save.OfferSnap
import systems.balatro.save.PlanetSnap
import systems.balatro.save.TarotSnap
import systems.balatro.save.VoucherSnap
import systems.balatro.save.BoosterSnap
import systems.balatro.save.SaveIo
import java.io.File

/**
 * A full run: alternating blinds and a shop, on ONE persistent engine. Beating a blind pays
 * out; the shop spends that money to BUY jokers (register live) or SELL them (unregister +
 * destroy live — the clean-removal payoff ShopSim proves). Jokers and their scaling state
 * carry across blinds because the engine is never rebuilt. This is the game on the engine.
 */
internal enum class Phase { ROUND, BLIND_SELECT, SHOP, RUN_INFO, ROUND_EVAL, OVER, WIN, PACK_OPEN }

/** One row of the cash-out screen (create_UIBox_round_evaluation). `dollars` = gold paid;
 *  `leadNum` is the left-side coloured count (hands/gold cards), null for blind/interest. */
internal enum class EvalKind { BLIND, HANDS, GOLD, INTEREST }
internal data class EvalRow(val kind: EvalKind, val dollars: Int, val label: String, val leadNum: String? = null)
internal data class Offer(val key: String, val name: String, val desc: String, val cost: Int, val rarity: Int = 1, val edition: Edition = Edition.NONE)
internal data class Owned(val offer: Offer, val fj: FJoker)
/** A shop voucher (one per shop). `extra` is the faithful config.extra (game.lua); the effect is
 *  applied by key in RunState.redeemVoucher (Card:apply_to_run, card.lua:2322). */
internal data class VoucherOffer(val key: String, val name: String, val desc: String, val extra: Int, val cost: Int = 10)
/** A booster pack offer (game.lua p_*). `kind` = Arcana/Celestial/Buffoon; `extra` cards shown,
 *  `choose` picks. Buying opens the pack (Phase.PACK_OPEN). */
internal data class BoosterOffer(val key: String, val name: String, val kind: String, val cost: Int, val extra: Int, val choose: Int)
/** One revealed pack item (a tarot/planet/joker the player may pick). */
internal sealed class PackItem {
    data class Tarot(val t: TarotOffer) : PackItem()
    data class Planet(val p: PlanetOffer) : PackItem()
    data class Joker(val o: Offer) : PackItem()
    data class Card(val card: PlayingCard) : PackItem()    // Standard pack: a playing card added to the deck
    data class SpectralItem(val s: Spectral) : PackItem()  // Spectral pack
}
/** An open booster pack: the revealed [items], how many remain to [pick], and which are taken. */
internal class OpenPack(val name: String, val kind: String, val items: List<PackItem>, choose: Int) {
    val picked = mutableStateListOf<Int>()
    var picksLeft by mutableStateOf(choose)
}
/** When a tag's effect fires (tag.lua config.type). */
internal enum class TagTrigger { EVAL, ROUND_START, SHOP_START, SHOP_FINAL }
/** A skip tag (tag.lua / game.lua tag_*). Earned by skipping a Small/Big blind; fires at [trigger]. */
internal enum class Tag(val display: String, val desc: String, val trigger: TagTrigger) {
    INVESTMENT("Investment Tag", "+\$25 after the next Blind", TagTrigger.EVAL),       // config.type 'eval', dollars 25
    JUGGLE("Juggle Tag", "+3 hand size next round", TagTrigger.ROUND_START),           // 'round_start_bonus', h_size 3
    D_SIX("D6 Tag", "Rerolls start at \$0 next shop", TagTrigger.SHOP_START),          // 'shop_start'
    COUPON("Coupon Tag", "Next shop cards & packs are free", TagTrigger.SHOP_FINAL),   // 'shop_final_pass'
}
private val TAG_POOL = Tag.values().toList()
private fun tagForBlind(blindIndex: Int): Tag = TAG_POOL[Random(blindIndex * 6151L + 17).nextInt(TAG_POOL.size)]

/** Spectral cards (the Spectral consumable set) — powerful run-altering effects. The subset here
 *  wires to existing systems (deck/jokers/hand-levels/money/hand-size); rarity-gated ones deferred. */
internal enum class Spectral(val display: String, val desc: String) {
    BLACK_HOLE("Black Hole", "Upgrade every poker hand by 1 level"),
    IMMOLATE("Immolate", "Destroy 5 cards in the deck, gain \$20"),
    ECTOPLASM("Ectoplasm", "Add Negative to a random Joker, -1 hand size"),
    HEX("Hex", "Add Polychrome to a random Joker, destroy the rest"),
    TALISMAN("Talisman", "Add a Gold Seal to a card"),
    DEJA_VU("Deja Vu", "Add a Red Seal to a card"),
    WRAITH("Wraith", "Create a random Joker, set money to \$0"),
}

/** A consumable held in the consumable slots (a tarot, planet, or spectral), used when the player
 *  chooses — rather than the shop/pack applying it instantly. */
internal sealed class Consumable {
    data class TarotC(val t: TarotOffer) : Consumable()
    data class PlanetC(val planet: Planet) : Consumable()
    data class SpectralC(val s: Spectral) : Consumable()
}

/** Jokers that leave the board after a won round (END_OF_ROUND self-destruct), keyed by FJoker key. */
private val SELF_DESTRUCT_KEYS = setOf("j_cry_brokenhome")
internal data class PlanetOffer(val planet: Planet, val cost: Int)
internal data class TarotOffer(val name: String, val enhancement: Enhancement = Enhancement.NONE, val cost: Int, val seal: Seal = Seal.NONE)

/** Cryptid's offline fallback for the live Discord member count (lib/https.lua `member_fallback`). The
 *  rebuild can't fetch the live value, so membershipcard / membershipcardtwo scale off this constant —
 *  bump it to re-snapshot the count. */
internal const val CRYPTID_MEMBER_COUNT = 38598

/** Build a freshly-acquired joker's FJoker with its per-key initial scaling state — the seed values the
 *  scoring engine reads. Shared by buy(), the Wraith spectral, and jollysus' on-sell spawn so every
 *  acquisition path seeds identically (previously only buy() did, so Wraith-/spawn-created jokers were
 *  mis-seeded). [swashSellSum] is the current total sell value of owned jokers — only j_swashbuckler reads
 *  it; passing it in keeps this pure and directly unit-testable. */
internal fun initialFJoker(offer: Offer, swashSellSum: Double): FJoker {
    val ed = when (offer.edition) { Edition.FOIL -> "Foil"; Edition.HOLO -> "Holo"; Edition.POLY -> "Poly"; else -> "" }
    // MANIFEST: a migrated joker's initial scaling state comes from its JokerSpec (co-located with its hooks).
    JOKER_MANIFEST[offer.key]?.let { spec ->
        val s = spec.initialState
        return FJoker(offer.key, edition = ed, rarity = offer.rarity, mult = s.mult, x = s.x, chips = s.chips, n = s.n, xc = s.xc)
    }
    val fjX = if (offer.key == "j_cry_primus") 1.01 else 1.0
    val fjMult = when (offer.key) {
        "j_popcorn"      -> 20.0            // +20 Mult, -1 per hand; self-destructs at 0
        "j_swashbuckler" -> swashSellSum    // sum of current sell values
        else -> 0.0
    }
    val fjXInit = when (offer.key) {
        // (j_ramen x=2.0 / j_campfire x=1.0 now come from their JOKER_MANIFEST initialState — unreachable here.)
        "j_cry_caramel"    -> 1.75  // config.extra.x_mult
        "j_cry_starfruit"  -> 2.0   // config.emult
        "j_cry_biggestm"   -> 7.0   // config.extra.xmult (read once the before-pass activates it)
        "j_cry_mprime"     -> 1.05  // config.extra.mult (^Emult exponent per Jolly/M joker)
        "j_cry_membershipcard" -> 0.1 * CRYPTID_MEMBER_COUNT   // Xmult_mod(0.1) × member count → read as xMultMod
        "j_cry_spy"        -> 0.5   // config.extra.x_mult — static, before-pass never sets this
        else -> fjX
    }
    val fjN = when (offer.key) {
        "j_cry_mstack"       -> 1   // retriggers >= 1
        "j_cry_chili_pepper" -> 8   // perishable countdown
        "j_cry_caramel"      -> 11  // end_of_round countdown
        "j_cry_spaceglobe"   -> HandType.HIGH_CARD.ordinal   // target hand type ordinal
        "j_cry_blacklist"    -> (2..14).random()             // random blacklisted rank id
        "j_cry_busdriver"    -> 4   // before-hand roll odds
        "j_cry_jollysus"     -> 1   // spawn flag armed (config.extra.spawn=true); reset to 1 at end_of_round
        "j_cry_chad"         -> 2   // config.extra.retriggers — retriggers the leftmost board joker j.n times
        else -> 0
    }
    val fjChips = when (offer.key) {
        "j_cry_membershipcardtwo" -> CRYPTID_MEMBER_COUNT.toDouble()  // chips(1) × floor(member count / chips_mod=1)
        "j_cry_clicked_cookie" -> 200.0                               // config.extra.chips — decrements via cry_press (unimplemented)
        else -> 0.0
    }
    return FJoker(offer.key, edition = ed, rarity = offer.rarity, x = fjXInit, mult = fjMult, n = fjN, chips = fjChips)
}

private val CATALOG = listOf(
    // --- vanilla ---
    Offer("j_joker", "Joker", "+4 Mult", 2),
    Offer("j_greedy_joker", "Greedy Joker", "+3 Mult / Diamond", 5),
    Offer("j_lusty_joker", "Lusty Joker", "+3 Mult / Heart", 5),
    Offer("j_wrathful_joker", "Wrathful Joker", "+3 Mult / Spade", 5),
    Offer("j_gluttenous_joker", "Gluttonous Joker", "+3 Mult / Club", 5),
    Offer("j_even_steven", "Even Steven", "+4 Mult / even card", 4),
    Offer("j_odd_todd", "Odd Todd", "+31 Chips / odd card", 4),
    Offer("j_scholar", "Scholar", "Ace: +20 Chips & +4 Mult", 4),
    // --- vanilla, individual (already faithful in calcJoker) ---
    Offer("j_arrowhead", "Arrowhead", "+50 Chips / Spade", 5, rarity = 2),
    Offer("j_onyx_agate", "Onyx Agate", "+7 Mult / Club", 5, rarity = 2),
    Offer("j_fibonacci", "Fibonacci", "+8 Mult / A,2,3,5,8", 8, rarity = 2),
    Offer("j_scary_face", "Scary Face", "+30 Chips / face card", 4),
    Offer("j_smiley", "Smiley Face", "+5 Mult / face card", 4),
    Offer("j_triboulet", "Triboulet", "x2 Mult / King or Queen", 8, rarity = 4),
    Offer("j_walkie_talkie", "Walkie Talkie", "10 or 4: +10 Chips & +4 Mult", 4),
    Offer("j_photograph", "Photograph", "x2 Mult on first face card", 5),
    // --- vanilla, joker_main flat ---
    Offer("j_half", "Half Joker", "+20 Mult if <= 3 cards", 5),
    Offer("j_stuntman", "Stuntman", "+250 Chips", 7, rarity = 3),
    Offer("j_seeing_double", "Seeing Double", "x2 Mult if a Club + non-Club score", 6, rarity = 2),
    Offer("j_flower_pot", "Flower Pot", "x3 Mult if all 4 suits score", 6, rarity = 2),
    // --- vanilla "+Chips if hand contains <type>" family (j_sly..j_crafty) ---
    Offer("j_sly", "Sly Joker", "+50 Chips if hand has a Pair", 3),
    Offer("j_wily", "Wily Joker", "+100 Chips if hand has Three of a Kind", 4),
    Offer("j_clever", "Clever Joker", "+80 Chips if hand has a Two Pair", 4),
    Offer("j_devious", "Devious Joker", "+100 Chips if hand has a Straight", 4),
    Offer("j_crafty", "Crafty Joker", "+80 Chips if hand has a Flush", 4),
    // --- vanilla "+Mult if hand contains <type>" family (j_jolly..j_droll) ---
    Offer("j_jolly", "Jolly Joker", "+8 Mult if hand has a Pair", 3),
    Offer("j_zany", "Zany Joker", "+12 Mult if hand has Three of a Kind", 4),
    Offer("j_mad", "Mad Joker", "+10 Mult if hand has a Two Pair", 4),
    Offer("j_crazy", "Crazy Joker", "+12 Mult if hand has a Straight", 4),
    Offer("j_droll", "Droll Joker", "+10 Mult if hand has a Flush", 4),
    // --- vanilla scoring-set modifier ---
    Offer("j_splash", "Splash", "every played card counts in scoring", 3),
    // --- vanilla hand-detection hooks ---
    Offer("j_four_fingers", "Four Fingers", "Flushes & Straights need only 4 cards", 7, rarity = 2),
    Offer("j_shortcut", "Shortcut", "Straights can skip one rank", 7, rarity = 2),
    Offer("j_smeared", "Smeared Joker", "Hearts/Diamonds & Spades/Clubs count as one suit", 7, rarity = 2),
    // --- vanilla face / retrigger hooks ---
    Offer("j_pareidolia", "Pareidolia", "every card counts as a face card", 5, rarity = 2),
    Offer("j_sock_and_buskin", "Sock and Buskin", "retrigger every played face card", 6, rarity = 2),
    Offer("j_hanging_chad", "Hanging Chad", "retrigger the first scored card 2x", 4),
    Offer("j_dusk", "Dusk", "retrigger all played cards on last hand", 6, rarity = 2),
    Offer("j_hack", "Hack", "retrigger 2s, 3s, 4s, and 5s", 6, rarity = 2),
    Offer("j_mime", "Mime", "retrigger all held-in-hand card abilities", 6, rarity = 2),
    // --- Cryptid ---
    Offer("j_cry_cube", "Cube", "+6 Chips", 4),
    Offer("j_cry_triplet_rhythm", "Triplet Rhythm", "x3 Mult if three 3s", 6),
    Offer("j_cry_lightupthenight", "Light Up the Night", "x1.5 Mult / 2 or 7", 7),
    Offer("j_cry_weegaming", "Wee Gaming", "+2 retriggers / 2", 6),
    Offer("j_cry_krustytheclown", "Krusty the Clown", "x_mult +0.02 / card", 7),
    Offer("j_cry_brokenhome", "Broken Home", "x11.4 Mult (1 round)", 8),
    Offer("j_cry_waluigi", "Waluigi", "x2.5 Mult / other joker", 8),
    Offer("j_cry_oldblueprint", "Old Blueprint", "copy joker to right", 7),
    Offer("j_cry_maximized", "Maximized", "face cards unify rank", 6),
    Offer("j_cry_primus", "Primus", "Emult if all-prime hand", 9),
    // --- Cryptid "type" jokers (fire when the played cards contain that hand) ---
    Offer("j_cry_giggly", "Giggly Joker", "+4 Mult if High Card", 1),
    Offer("j_cry_silly", "Silly Joker", "+16 Mult if Full House", 4),
    Offer("j_cry_nutty", "Nutty Joker", "+19 Mult if Four of a Kind", 4),
    Offer("j_cry_manic", "Manic Joker", "+22 Mult if Straight Flush", 5),
    Offer("j_cry_delirious", "Delirious Joker", "+22 Mult if Five of a Kind", 5),
    Offer("j_cry_wacky", "Wacky Joker", "+30 Mult if Flush House", 6),
    Offer("j_cry_kooky", "Kooky Joker", "+30 Mult if Flush Five", 6),
    Offer("j_cry_dubious", "Dubious", "+20 Chips if High Card", 1),
    Offer("j_cry_shrewd", "Shrewd", "+150 Chips if Four of a Kind", 4),
    Offer("j_cry_tricksy", "Tricksy", "+170 Chips if Straight Flush", 5),
    Offer("j_cry_foxy", "Foxy", "+130 Chips if Full House", 4),
    Offer("j_cry_savvy", "Savvy", "+170 Chips if Five of a Kind", 5),
    Offer("j_cry_subtle", "Subtle", "+240 Chips if Flush House", 6),
    Offer("j_cry_discreet", "Discreet", "+240 Chips if Flush Five", 6),
    Offer("j_cry_nuts", "Nuts", "x5 Mult if Straight Flush", 8),
    Offer("j_cry_quintet", "Quintet", "x5 Mult if Five of a Kind", 8),
    Offer("j_cry_unity", "Unity", "x9 Mult if Flush House", 8),
    Offer("j_cry_swarm", "Swarm", "x9 Mult if Flush Five", 8),
    Offer("j_cry_nice", "Nice", "+420 Chips if hand has a 6 and a 9", 4),
    Offer("j_cry_nosound", "No Sound?", "retrigger scored 7s x3", 5),
    Offer("j_cry_exposed", "Exposed", "retrigger scored non-faces x2", 5),
    Offer("j_cry_mask", "Mask", "retrigger scored faces x3", 5),
    // sock_and_sock: retrigger each played Abstract-enhanced card once (config.extra.retriggers=1; max 40).
    // rarity=2 (Uncommon), cost=7. Confirmed from SpectralPack/Cryptid items/misc_joker.lua.
    Offer("j_cry_sock_and_sock", "Sock and Sock", "retrigger Abstract cards x1", 7, rarity = 2),
    // --- Cryptid joker-retrigger jokers (context.retrigger_joker_check family) ---
    // Chad: retrigger leftmost joker j.n times (config.extra.retriggers=2). rarity=3 (Rare), cost=10. Confirmed.
    Offer("j_cry_chad", "Chad", "retrigger leftmost Joker 2x", 10, rarity = 3),
    // Loopy: retrigger all other jokers j.n times (Jolly Jokers sold). rarity=1 (M-pool), cost=4. Confirmed.
    Offer("j_cry_loopy", "Loopy", "retrigger all Jokers x sold Jolly Jokers", 4, rarity = 1),
    // Spectrogram: retrigger rightmost joker per Echo card scored. rarity=5 (cry_epic), cost=9. Confirmed.
    Offer("j_cry_spectrogram", "Spectrogram", "retrigger rightmost Joker per Echo card scored", 9, rarity = 5),
    // Flip Side: retrigger all double-sided-edition jokers once. rarity=2 (Uncommon), cost=7. Confirmed.
    Offer("j_cry_flip_side", "On the Flip Side", "retrigger all Double-Sided Jokers", 7, rarity = 2),
    Offer("j_cry_wee_fib", "Wee Fibonacci", "+3 Mult per scored A/2/3/5/8 (scaling)", 6),
    Offer("j_cry_meteor", "Meteor", "+75 Chips per Foil joker", 5),
    Offer("j_cry_exoplanet", "Exoplanet", "+15 Mult per Holo joker", 5),
    Offer("j_cry_stardust", "Stardust", "x2 Mult per Poly joker", 7),
    Offer("j_cry_duos", "Duos", "x2.5 Mult if Two Pair or Full House", 7),
    Offer("j_cry_home", "Home", "x3.5 Mult if Full House", 7),
    Offer("j_cry_filler", "Filler", "x1.00000000000003 Mult", 1),
    Offer("j_cry_zooble", "Zooble", "+1 Mult per distinct scored rank (scaling)", 6),
    Offer("j_cry_cursor", "Cursor", "+8 Chips per card bought (scaling)", 5),
    Offer("j_cry_eternalflame", "Eternal Flame", "+0.1 X Mult per card sold (scaling)", 6),
    Offer("j_cry_whip", "The WHIP", "+0.5 X Mult if hand has a 2 and 7 of diff suits (scaling)", 7),
    Offer("j_cry_big_cube", "Big Cube", "x6 Chips", 6),
    Offer("j_cry_antennastoheaven", "Antennas to Heaven", "+0.1 X Chips per scored 4/7 (scaling)", 7),
    Offer("j_cry_night", "Night", "mult^3 (Emult) on the final hand", 8),
    // --- batch-16: accumulator Xmult + Emult jokers ---
    Offer("j_cry_verisimile", "Verisimile", "Xmult scales per pseudorandom_result hit", 7),
    Offer("j_cry_duplicare", "Duplicare", "Xmult scales +1 per post_trigger / card played", 7),
    Offer("j_cry_formidiulosus", "Formidiulosus", "Emult = 1 + 0.01*candy joker count (scaling)", 8),
    Offer("j_cry_happyhouse", "Happy House", "Emult^4 after 114+ hands played", 8),
    // --- exotic jokers (iterum + exponentia + batch-15) ---
    Offer("j_cry_iterum", "Iterum", "x2 Mult per scored card; +1 retrigger per card", 9),
    Offer("j_cry_exponentia", "Exponentia", "Emult scales +0.03 per xmult event; applies mult^Emult", 9),
    Offer("j_cry_kittyprinter", "Kittyprinter", "x2 Mult every hand", 7),
    Offer("j_cry_clicked_cookie", "Clicked Cookie", "+Chips (starts 200, -1 per click)", 6),
    Offer("j_cry_monkey_dagger", "Monkey Dagger", "+10*sell_cost Chips when left joker sold", 7),
    Offer("j_cry_unjust_dagger", "Unjust Dagger", "+0.2*sell_cost Xmult when left joker sold", 8),
    Offer("j_cry_jimball", "Jimball", "Xmult scales +0.15 while this hand type is your most-played", 7),
    Offer("j_cry_pizza_slice", "Pizza Slice", "Xmult scales +0.5 per other pizza slice sold", 6),
    Offer("j_cry_wheelhope", "Wheelhope", "Xmult scales +0.5 per Wheel of Fortune trigger", 7),
    Offer("j_cry_fspinner", "Fspinner", "+6 Chips each hand another type played as much (scaling)", 6),
    Offer("j_cry_pirate_dagger", "Pirate Dagger", "+0.25*sell_cost Xchips when right joker sold", 7),
    // --- Cryptid CRY_* hand-type jokers (CRY_BULWARK/ULTPAIR/CLUSTERFUCK/NONE all LIVE; only CRY_WHOLEDECK dormant) ---
    Offer("j_cry_stronghold", "Stronghold", "x5 Mult if cry_Bulwark", 8),
    Offer("j_cry_wtf", "WTF", "x10 Mult if cry_Clusterfuck", 8),
    Offer("j_cry_clash", "Clash", "x12 Mult if cry_UltPair", 8),
    Offer("j_cry_the", "The", "x2 Mult if cry_None", 7),
    Offer("j_cry_annihalation", "Annihalation", "Emult^5.2 if cry_WholeDeck", 9),
    Offer("j_cry_words_cant_even", "Words Can't Even", "x52000000 Mult if cry_WholeDeck", 9),
    Offer("j_cry_bonkers", "Bonkers Joker", "+20 Mult if cry_Bulwark", 6),
    Offer("j_cry_fuckedup", "Fucked-Up Joker", "+37 Mult if cry_Clusterfuck", 7),
    Offer("j_cry_foolhardy", "Foolhardy Joker", "+42 Mult if cry_UltPair", 7),
    Offer("j_cry_undefined", "Undefined Joker", "+5 Mult if cry_None", 5),
    Offer("j_cry_adroit", "Adroit Joker", "+170 Chips if cry_Bulwark", 6),
    Offer("j_cry_penetrating", "Penetrating Joker", "+270 Chips if cry_Clusterfuck", 7),
    Offer("j_cry_treacherous", "Treacherous Joker", "+300 Chips if cry_UltPair", 7),
    Offer("j_cry_nebulous", "Nebulous Joker", "+30 Chips if cry_None", 5),
    Offer("j_cry_many_lost_minds", "Many Lost Minds", "+52! Chips if cry_WholeDeck", 9),
    Offer("j_cry_jtron", "J-Tron", "mult^(1 + Jokers on board)", 9),
    // Thalia and Melpomene: Xmult = C(n,2) where n = distinct rarities on the board; Legendary (rarity=4).
    // Art position unknown (Cryptid source unavailable); renders without sprite until atlas position is confirmed.
    Offer("j_cry_thalia", "Thalia and Melpomene", "Xmult = C(unique rarities, 2)", 10, rarity = 4),
    // --- Missing wired-but-not-in-catalog batch ---
    // apjoker: X4 Xmult on boss blinds.
    Offer("j_cry_apjoker", "Apjoker", "x4 Mult on boss blinds", 7, rarity = 3),
    // chili_pepper: Xmult accumulator (+0.5 per end_of_round; perishable).
    Offer("j_cry_chili_pepper", "Chili Pepper", "Xmult +0.5 per round (perishable)", 6),
    // dropshot: Xmult accumulator (+0.2 per non-scoring card of random suit each hand).
    Offer("j_cry_dropshot", "Dropshot", "Xmult +0.2 per non-scoring suit card", 6),
    // fading_joker: Xmult accumulator (+1 when perishable expires).
    Offer("j_cry_fading_joker", "Fading Joker", "Xmult +1 when perishable expires", 5),
    // mondrian: Xmult accumulator (+0.25 per end_of_round with 0 discards).
    Offer("j_cry_mondrian", "Mondrian", "Xmult +0.25 per unused-discard round", 6),
    // keychange: Xmult accumulator (+0.25 per new hand type played; resets each round).
    Offer("j_cry_keychange", "Key Change", "Xmult +0.25 per new hand type played", 6),
    // poor_joker: +Mult accumulator (+4 per rent payment).
    Offer("j_cry_poor_joker", "Poor Joker", "+Mult accumulates per rent payment", 4),
    // spaceglobe: Xchip accumulator (+0.2 when target hand type played; target rotates).
    Offer("j_cry_spaceglobe", "Spaceglobe", "Xchip +0.2 per target hand played", 6),
    // supercell: flat +15c X2c +15m X2m every hand.
    Offer("j_cry_supercell", "Supercell", "+15c X2c +15m X2m", 9),
    // universe: Emult^1.2 per other Astral-edition joker (other_joker pass).
    Offer("j_cry_universe", "Universe", "Emult^1.2 per Astral joker", 9, rarity = 3),
    // caramel: X1.75 Mult per scored played card (individual pass; j.x=1.75; perishable).
    Offer("j_cry_caramel", "Caramel", "x1.75 Mult per scored card (perishable)", 8, rarity = 5),
    // --- Cryptid Epic/M/Exotic pool jokers (Epic=5, Exotic=6, M=M-pool) ---
    // clockwork: Xmult accumulator (+0.25 every 3rd hand; starts at 1).
    Offer("j_cry_clockwork", "Clockwork", "Xmult +0.25 every 3rd hand played", 7, rarity = 5),
    // starfruit: Emult from j.x (starts 2, -0.2 per reroll; perishable).
    Offer("j_cry_starfruit", "Starfruit", "Emult from scaling (perishable)", 8, rarity = 5),
    // stella_mortis: Emult accumulator (+0.4 per planet destroyed in shop).
    Offer("j_cry_stella_mortis", "Stella Mortis", "Emult +0.4 per planet destroyed", 9, rarity = 5),
    // circulus_pistoris: Xchip*π and Emult*π when exactly 3 hands left.
    Offer("j_cry_circulus_pistoris", "Circulus Pistoris", "Xchip*π Emult*π at hands_left=3", 9, rarity = 5),
    // facile: Emult=3 each hand (nearly always fires in standard play).
    Offer("j_cry_facile", "Facile", "Emult 3 each hand", 8, rarity = 5),
    // M-pool jokers (special pool; rarity=1 placeholder — not available in standard shop).
    // foodm: +Mult from j.mult accumulator (starts 40, depletes per round; jolly restores).
    Offer("j_cry_foodm", "Foodm", "+Mult from accumulator (M-pool)", 6),
    // mstack: +j.n retriggers per scored card (n=1 base; grows by selling jolly jokers).
    Offer("j_cry_mstack", "Mstack", "+retriggers per scored card (M-pool)", 6),
    // biggestm: X7 Mult when activated by Pair+ hand (before-pass gate).
    Offer("j_cry_biggestm", "Biggestm", "X7 Mult on Pair+ hands (M-pool)", 7),
    // crustulum: +Chips from j.chips accumulator (reroll shop increments).
    Offer("j_cry_crustulum", "Crustulum", "+Chips from reroll accumulator (M-pool)", 6),
    // m: Xmult from j.x (+13 per Jolly Joker sold).
    Offer("j_cry_m", "M", "Xmult +13 per Jolly sold (M-pool)", 7),
    // longboi: Xmult = monstermult (grows each round; M-pool variant).
    Offer("j_cry_longboi", "Longboi", "Xmult = monstermult (M-pool)", 6),
    // circus: Xmult per other joker based on rarity (Rare=X2, Epic=X3, Legendary=X4, Exotic=X20).
    Offer("j_cry_circus", "Circus", "Xmult per rarity: Rare x2, Epic x3, Leg x4, Exotic x20", 9, rarity = 6),
    // broken_sync_catalyst: atomically swaps 10% of chips into mult and 10% of mult into chips.
    // rarity=3 (Rare), cost=8. Confirmed from SpectralPack/Cryptid items/misc_joker.lua.
    // (cry_broken_swap=10 → portion=10%); math: delta=(chips−mult)*0.10. Inline intercept in joker_main.
    Offer("j_cry_broken_sync_catalyst", "Broken Sync Catalyst", "swap 10% of Chips↔Mult", 8, rarity = 3),
    // sync_catalyst: balances Chips and Mult (sets both to their average = (chips+mult)/2).
    // rarity=4 (Legendary), cost=20. Confirmed from SpectralPack/Cryptid items/misc_joker.lua.
    Offer("j_cry_sync_catalyst", "Sync Catalyst", "balance Chips = Mult = average", 20, rarity = 4),
    // --- Cryptid Spooky-Code scoring jokers ---
    // Spy: flat X0.5 Mult every hand (halves mult); x_mult=0.5 is fixed.
    Offer("j_cry_spy", "Spy", "x0.5 Mult every hand", 3),
    // Cut: Xmult from j.x accumulator (+0.5 per Code card destroyed when leaving shop). Rarity/cost: best-guess.
    Offer("j_cry_cut", "Cut", "Xmult +0.5 per Code card destroyed", 6),
    // Python: Xmult from j.x accumulator (+0.15 per Code consumable used). Rarity/cost: best-guess.
    Offer("j_cry_python", "Python", "Xmult +0.15 per Code card used", 5),
    // --- cry jokers implemented in the engine but previously unacquirable (now wired into the shop;
    //     name/desc/cost/rarity from mods/Cryptid/items). Rarity ints: cry_cursed→1, cry_epic→5, cry_exotic→6.
    //     blacklist/membershipcard(two) are partially inert in the rebuild (no random-rank shop pool / no live
    //     Discord member count); jollysus' create-on-sell and bonk's Pair-bonus scaling are unported — engine gaps. ---
    Offer("j_cry_blacklist", "Blacklist", "Zeroes hands holding a chosen rank; self-destructs if it leaves the deck", 0),
    Offer("j_cry_bonk", "Bonk", "+6 Chips per Joker (x3 for Jolly Jokers)", 5, rarity = 2),
    Offer("j_cry_boredom", "Boredom", "1 in 2 chance to retrigger each Joker or card", 14, rarity = 5),
    Offer("j_cry_busdriver", "Bus Driver", "3-in-4: +50 Mult; 1-in-4: -50 Mult each hand", 7, rarity = 2),
    Offer("j_cry_googol_play", "Googol Play Card", "1 in 8 chance for X1e100 Mult", 10, rarity = 5),
    Offer("j_cry_jollysus", "Jolly Joker?", "Create a Joker when a Joker is sold (once per round)", 4),
    Offer("j_cry_membershipcard", "Membership Card", "X0.1 Mult per Cryptid Discord member", 20, rarity = 4),
    Offer("j_cry_membershipcardtwo", "Membership Card", "+1 Chip per Cryptid Discord member", 17, rarity = 5),
    Offer("j_cry_mprime", "M Prime", "^1.05 Emult per Jolly / M Joker", 50, rarity = 6),
    Offer("j_cry_paved_joker", "Paved Joker", "Stones fill 1-gaps in straights/flushes; +X1 per Perishable expired", 4),
    // --- vanilla held-in-hand jokers ---
    Offer("j_baron", "Baron", "each King held gives x1.5 Mult", 8, rarity = 3),
    Offer("j_shoot_the_moon", "Shoot the Moon", "each Queen held gives +13 Mult", 1),
    Offer("j_raised_fist", "Raised Fist", "+2x lowest held card's rank in Mult", 1),
    // --- vanilla n-based flat jokers ---
    Offer("j_abstract", "Abstract Joker", "+3 Mult per joker on board", 1),
    Offer("j_supernova", "Supernova", "+1 Mult per times this hand type played", 1),
    Offer("j_blue_joker", "Blue Joker", "+2 Chips per card left in deck", 1),
    Offer("j_banner", "Banner", "+30 Chips per remaining discard", 1),
    Offer("j_stone", "Stone Joker", "+25 Chips per Stone card in deck", 2, rarity = 2),
    Offer("j_steel_joker", "Steel Joker", "x1.2 Mult per Steel card in hand", 2, rarity = 2),
    Offer("j_drivers_license", "Driver's License", "x3 Mult if 16+ enhanced cards in deck", 8, rarity = 3),
    Offer("j_baseball", "Baseball Card", "x1.5 Mult per Uncommon joker on board", 8, rarity = 3),
    // --- vanilla copy-jokers (copy the target joker's effect in every context) ---
    Offer("j_blueprint", "Blueprint", "copies the joker to the right", 10, rarity = 3),
    Offer("j_brainstorm", "Brainstorm", "copies the leftmost joker", 10, rarity = 3),
    // --- vanilla accumulator-mult jokers ---
    Offer("j_green_joker", "Green Joker", "+1 Mult per hand played, -1 per discard", 1),
    Offer("j_spare_trousers", "Spare Trousers", "+2 Mult each time Two Pair or Full House played", 2, rarity = 2),
    Offer("j_swashbuckler", "Swashbuckler", "+Mult equal to total sell value of jokers", 1),
    Offer("j_red_card", "Red Card", "+3 Mult each time a pack is skipped", 1),
    // --- vanilla accumulator-Xmult jokers ---
    Offer("j_obelisk", "Obelisk", "+0.2 Xmult per hand NOT this type played", 8, rarity = 3),
    Offer("j_hologram", "Hologram", "+0.25 Xmult per card added to deck", 2, rarity = 2),
    Offer("j_ramen", "Ramen", "starts x2 Mult, -0.01 per discarded card", 2, rarity = 2),
    Offer("j_campfire", "Campfire", "+0.25 Xmult per joker sold", 8, rarity = 3),
    Offer("j_loyalty_card", "Loyalty Card", "x4 Mult every 5 hands played", 2, rarity = 2),
    Offer("j_throwback", "Throwback", "+0.25 Xmult per blind skipped this run", 2, rarity = 2),
    // --- vanilla accumulator-chips jokers ---
    Offer("j_runner", "Runner", "+15 Chips each Straight played", 1),
    Offer("j_square", "Square Joker", "+4 Chips each time 5-card hand played", 1),
    Offer("j_castle", "Castle", "+3 Chips per suit discarded from flush", 2, rarity = 2),
    Offer("j_wee", "Wee Joker", "+8 Chips each time 4-card Straight played", 8, rarity = 3),
    // --- vanilla hands/discards-remaining jokers (now wired) ---
    Offer("j_acrobat", "Acrobat", "x3 Mult on the final hand", 6, rarity = 2),
    Offer("j_mystic_summit", "Mystic Summit", "+15 Mult at 0 discards", 5),
)
private const val HANDS = 4
private const val DISCARDS = 3
private const val MAX_JOKERS = 5   // G.GAME.max_jokers default (Balatro game.lua)

/** 3 shop offers, deterministic per ante; ~60% of the time the first slot rolls an edition (+3 cost). */
// The six base economy vouchers (game.lua:608-618); cost $10, effects per Card:apply_to_run.
private val VOUCHERS = listOf(
    VoucherOffer("v_overstock_norm", "Overstock", "+1 card slot in the shop", 1),
    VoucherOffer("v_clearance_sale", "Clearance Sale", "All shop cards 25% off", 25),
    VoucherOffer("v_reroll_surplus", "Reroll Surplus", "Rerolls cost \$2 less", 2),
    VoucherOffer("v_grabber", "Grabber", "+1 hand each round", 1),
    VoucherOffer("v_wasteful", "Wasteful", "+1 discard each round", 1),
    VoucherOffer("v_seed_money", "Seed Money", "Raise interest cap to \$10", 50),
)
/** One voucher per shop (Balatro shows a single voucher slot); skip ones already redeemed. */
private fun rollVoucher(blind: Int, redeemed: Set<String>): VoucherOffer? =
    VOUCHERS.filterNot { it.key in redeemed }.shuffled(Random(blind * 49157L + 5)).firstOrNull()

// Booster packs (game.lua p_*). Arcana/Celestial/Buffoon map to the tarot/planet/joker buy systems
// (Standard/Spectral need deck-add + spectral effects — deferred). extra cards shown, choose picks.
private val BOOSTERS = listOf(
    BoosterOffer("p_arcana_normal", "Arcana Pack", "Arcana", 4, 3, 1),
    BoosterOffer("p_arcana_jumbo", "Jumbo Arcana Pack", "Arcana", 6, 5, 1),
    BoosterOffer("p_arcana_mega", "Mega Arcana Pack", "Arcana", 8, 5, 2),
    BoosterOffer("p_celestial_normal", "Celestial Pack", "Celestial", 4, 3, 1),
    BoosterOffer("p_buffoon_normal", "Buffoon Pack", "Buffoon", 4, 2, 1),
    BoosterOffer("p_buffoon_jumbo", "Jumbo Buffoon Pack", "Buffoon", 6, 4, 1),
    BoosterOffer("p_standard_normal", "Standard Pack", "Standard", 4, 3, 1),
    BoosterOffer("p_standard_jumbo", "Jumbo Standard Pack", "Standard", 6, 5, 1),
    BoosterOffer("p_spectral_normal", "Spectral Pack", "Spectral", 4, 2, 1),
)
/** Two booster slots per shop (Balatro's shop has 2). */
private fun rollBoosters(blind: Int): List<BoosterOffer> =
    BOOSTERS.shuffled(Random(blind * 80021L + 3)).take(2)

private fun rollShop(blind: Int, slots: Int = 3): List<Offer> {
    val rng = Random(blind * 7919L + 13)
    val base = CATALOG.shuffled(rng).take(slots)
    return base.mapIndexed { i, o ->
        if (i == 0 && rng.nextInt(100) < 60) {
            val ed = listOf(Edition.FOIL, Edition.HOLO, Edition.POLY).random(rng)
            o.copy(edition = ed, name = "${ed.tag} ${o.name}", cost = o.cost + 3)
        } else o
    }
}

/** 2 planet cards per ante, deterministic. Each levels up its hand type ($3). */
private fun rollPlanets(blind: Int): List<PlanetOffer> =
    Planet.values().toList().shuffled(Random(blind * 104729L)).take(2).map { PlanetOffer(it, 3) }

private val TAROTS = listOf(
    TarotOffer("The Hierophant", Enhancement.BONUS, 3),
    TarotOffer("The Empress", Enhancement.MULT, 3),
    TarotOffer("Justice", Enhancement.GLASS, 3),
    TarotOffer("The Chariot", Enhancement.STEEL, 4),      // steel (corrects the earlier mislabel)
    TarotOffer("The Devil", Enhancement.GOLD, 4),
    TarotOffer("The Star", Enhancement.WILD, 4),
    TarotOffer("The Tower", Enhancement.STONE, 4),        // stone: +50 chips, no rank/suit
    TarotOffer("The Sun", cost = 4, seal = Seal.RED),     // red seal: retrigger
    TarotOffer("The Moon", cost = 4, seal = Seal.GOLD),   // gold seal: +$3 when played
)
/** 2 tarots per ante; each enhances a random deck card. */
private fun rollTarots(blind: Int): List<TarotOffer> = TAROTS.shuffled(Random(blind * 1299709L)).take(2)

/** Compose-observable run state; mutations drive recomposition. Scoring is the faithful Score engine. */
internal class RunState {
    var money by mutableStateOf(4)
    var blindIndex by mutableStateOf(0)                  // 0-based global blind counter
    var boss by mutableStateOf<Boss?>(null)              // set on the boss slot
    var phase by mutableStateOf(Phase.ROUND)
    val handLevels = HandLevels()                        // per-hand-type planet levels (run state)

    // ── cash-out (ROUND_EVAL) state — the reward breakdown shown after a blind is beaten ──
    var evalRows by mutableStateOf<List<EvalRow>>(emptyList()); private set
    var cashOutTotal by mutableStateOf(0); private set

    /** Mirrors G.GAME.hands[h].played — cumulative times each hand type was played in the run. */
    private val _handPlayed = mutableStateMapOf<HandType, Int>()
    fun handPlayed(h: HandType): Int = _handPlayed[h] ?: 0
    private fun recordHandPlayed(h: HandType) { _handPlayed[h] = handPlayed(h) + 1 }

    // ── game-over (Phase.OVER) stats — round_scores_row equivalents the run actually tracks ──
    val handsPlayedTotal: Int get() = _handPlayed.values.sum()

    // ── boss round-state (THE_EYE, THE_MOUTH) — reset each round ───────────────────────────────
    /** THE_EYE: hand types already played this round; each type may only be played once. */
    val eyeUsedHands = mutableSetOf<HandType>()
    /** THE_MOUTH: the first hand type played; all subsequent plays must match it (null = not locked yet). */
    var mouthLockedHand: HandType? = null
    /** THE_PILLAR: cards played in any previous hand this Ante are debuffed for THE_PILLAR blind.
     *  Resets at the start of each new Ante (slot 0 = first blind of the ante). */
    val pillarPlayedCards = mutableSetOf<PlayingCard>()
    /** Current joker slot capacity (default 5; THE_MANACLE reduces by 1 for the boss round). */
    var maxJokers by mutableStateOf(MAX_JOKERS)
    /** CRIMSON_HEART: the joker currently disabled for this hand (rotates after each play). */
    var crimsonHeartDisabled: FJoker? = null
    /** CERULEAN_BELL: index into `hand` of the forced-selected card (null = none). */
    var bellForcedIdx: Int? = null
    /** Cumulative hands played this run (used by j_cry_clockwork: fires every 3rd hand). */
    var totalHandsPlayed by mutableStateOf(0); private set
    /** Hand types played this round (j_cry_keychange: +0.25 Xmult per new type first seen; resets on startRound). */
    private val roundHandTypes = mutableSetOf<HandType>()
    /** Discards used this round (mondrian: +0.25 Xmult when 0 used at end_of_round). Reset in startRound(). */
    private var roundDiscardsUsed = 0
    /** Random suit chosen for j_cry_dropshot each round (reset_castle_card pattern, seeded by blindIndex).
     *  Default Spades matches Cryptid's G.GAME.current_round.cry_dropshot_card = { suit = "Spades" }. */
    private var dropShotSuit: Suit = Suit.S
    /** Face-down card indices (THE_HOUSE/MARK/WHEEL/FISH): set of `hand` indices currently showing
     *  the card back. Tapping a face-down card reveals it (removes from faceDown) AND selects it —
     *  the same gesture as vanilla Balatro's hover-reveals + click-selects on desktop. Newly drawn
     *  cards are added here by applyFaceDown(), called from startRound() and refill(). */
    var faceDown by mutableStateOf(setOf<Int>())
    val mostPlayedHand: Pair<HandType, Int>? get() = _handPlayed.entries.maxByOrNull { it.value }?.toPair()
    val timesRerolled: Int get() = rerolls
    /** G.GAME.round_scores['hand'].amt — cumulative chips scored across all rounds. */
    var totalChipsScored by mutableStateOf(0.0); private set
    /** G.GAME.round_scores['cards_played'].amt — total cards played in hands this run. */
    var totalCardsPlayed by mutableStateOf(0); private set
    /** G.GAME.round_scores['cards_discarded'].amt — total cards discarded this run. */
    var totalCardsDiscarded by mutableStateOf(0); private set
    /** G.GAME.round_scores['cards_purchased'].amt — total cards/packs bought this run. */
    var totalCardsPurchased by mutableStateOf(0); private set
    /** G.GAME.pseudorandom.seed — a display seed string generated once at run start. */
    var runSeed: String = java.util.UUID.randomUUID().toString().take(8).uppercase(); private set


    val ante: Int get() = blindIndex / 3 + 1
    private val slot: Int get() = blindIndex % 3          // 0 Small, 1 Big, 2 Boss
    val blindName: String get() = when (slot) { 0 -> "Small Blind"; 1 -> "Big Blind"; else -> boss?.display ?: "Boss Blind" }
    val owned = mutableStateListOf<Owned>()
    /** Joker slots: base 5, +1 per NEGATIVE joker held (e_negative config.extra = 1 → card_limit+1). */
    val jokerSlots: Int get() = 5 + owned.count { it.offer.edition == Edition.NEGATIVE }
    // Voucher run-modifiers (Card:apply_to_run). Persist for the whole run once redeemed.
    var shopSlotsBonus by mutableStateOf(0)        // Overstock: +1 shop card slot
    var discountPercent by mutableStateOf(0)       // Clearance Sale: % off all shop prices
    var interestCap by mutableStateOf(5)           // Seed Money: max interest dollars (5 → 10)
    var baseHands by mutableStateOf(HANDS)         // Grabber: +1 hand/round
    var baseDiscards by mutableStateOf(DISCARDS)   // Wasteful: +1 discard/round
    val redeemedVouchers = mutableStateListOf<String>()
    var shopVoucher by mutableStateOf<VoucherOffer?>(null)
    var shopBoosters by mutableStateOf<List<BoosterOffer>>(emptyList())   // 2 booster slots per shop
    var openPack by mutableStateOf<OpenPack?>(null)                       // the pack being opened (PACK_OPEN)
    private var packSeed = 0                                              // varies pack contents per buy
    // Skip tags: earned by skipping a blind, fired at their trigger. handSize + the per-shop tag
    // transients (reset on each shop entry) are how the timed effects land.
    val tags = mutableStateListOf<Tag>()                                 // earned, awaiting their trigger
    var handSize by mutableStateOf(8)                                    // cards drawn this round (Juggle: +3)
    var baseHandSize by mutableStateOf(8)                                // permanent base hand size (Ectoplasm: -1)
    var freeRerollThisShop by mutableStateOf(false)                      // D6 Tag (this shop only)
    var couponThisShop by mutableStateOf(false)                          // Coupon Tag (this shop only)
    // Consumable slots (G.consumeables): tarots/planets are HELD here and used when chosen.
    val consumables = mutableStateListOf<Consumable>()
    var consumableSlotsBonus by mutableStateOf(0)                        // Crystal Ball voucher (follow-on)
    val consumableSlots: Int get() = 2 + consumableSlotsBonus
    fun hasConsumableRoom(): Boolean = consumables.size < consumableSlots
    /** Use (and consume) the held consumable at [i] — applies its effect, then frees the slot. */
    fun useConsumable(i: Int) {
        when (val c = consumables.getOrNull(i) ?: return) {
            is Consumable.TarotC -> {
                val card = if (c.t.seal != Seal.NONE) deck.sealRandom(c.t.seal) else deck.enhanceRandom(c.t.enhancement)
                Telemetry.event("RUN_USE_TAROT", "tarot" to c.t.name, "card" to (card?.key ?: "none"))
            }
            is Consumable.PlanetC -> { handLevels.levelUp(c.planet.hand); Telemetry.event("RUN_USE_PLANET", "planet" to c.planet.display) }
            is Consumable.SpectralC -> { applySpectral(c.s); Telemetry.event("RUN_USE_SPECTRAL", "spectral" to c.s.name) }
        }
        consumables.removeAt(i)
    }

    /** Apply a spectral card's effect to the run (Spectral consumable use). */
    private fun applySpectral(s: Spectral) {
        when (s) {
            Spectral.BLACK_HOLE -> HandType.values().forEach { handLevels.levelUp(it) }
            Spectral.IMMOLATE -> { repeat(5) { deck.removeRandom() }; money += 20 }
            Spectral.TALISMAN -> deck.sealRandom(Seal.GOLD)
            Spectral.DEJA_VU -> deck.sealRandom(Seal.RED)
            Spectral.ECTOPLASM -> {
                if (owned.isNotEmpty()) { val i = owned.indices.random(); owned[i] = owned[i].copy(offer = owned[i].offer.copy(edition = Edition.NEGATIVE)) }
                baseHandSize = maxOf(1, baseHandSize - 1)
            }
            Spectral.HEX -> if (owned.isNotEmpty()) {
                val keep = owned[owned.indices.random()].let { it.copy(offer = it.offer.copy(edition = Edition.POLY)) }
                owned.clear(); owned.add(keep)
            }
            Spectral.WRAITH -> { val o = CATALOG.random(); owned.add(Owned(o, initialFJoker(o, owned.sumOf { maxOf(1.0, it.offer.cost / 2.0) }))); money = 0 }
        }
    }
    /** The tag offered for skipping the current (Small/Big) blind. */
    val upcomingTag: Tag get() = tagForBlind(blindIndex)
    /** Shop price after the Clearance Sale discount + the Coupon Tag (free this shop). set_cost: floor, min $1. */
    fun price(base: Int): Int {
        val d = maxOf(discountPercent, if (couponThisShop) 100 else 0)
        return if (d >= 100) 0 else maxOf(1, base * (100 - d) / 100)
    }
    var shop by mutableStateOf<List<Offer>>(emptyList())
    var shopPlanets by mutableStateOf<List<PlanetOffer>>(emptyList())
    var shopTarots by mutableStateOf<List<TarotOffer>>(emptyList())
    /** The tarot currently being aimed — non-null while the player is selecting target hand cards. */
    var pendingTarot by mutableStateOf<TarotOffer?>(null)
    /** Hand-card indices selected as targets for [pendingTarot] (up to 2, like vanilla). */
    var tarotTarget by mutableStateOf(setOf<Int>())

    private val deck = Deck(20260614L)   // persistent across the run (tarot enhancements stick)
    val deckRemaining: Int get() = deck.remaining
    var hand by mutableStateOf<List<PlayingCard>>(emptyList())
    var selected by mutableStateOf(setOf<Int>())
    var roundScore by mutableStateOf(0.0)
    var handsLeft by mutableStateOf(HANDS)
    var discardsLeft by mutableStateOf(DISCARDS)
    var lastResult by mutableStateOf<ScoreResult?>(null)
    var lastSteps by mutableStateOf<List<ScoreStep>>(emptyList())

    // --- scoring animation state ---
    var scoring by mutableStateOf(false); private set        // the score sequence is playing out
    var scoreCards by mutableStateOf<List<PlayingCard>>(emptyList()); private set  // played cards, held on screen
    var popIndex by mutableStateOf(-1)                       // which played card is currently popping
    var displayChips by mutableStateOf(0.0)                  // the readout, counting up through the cascade
    var displayMult by mutableStateOf(0.0)
    private var pending: ScoreResult? = null
    private var pendingSel: List<PlayingCard> = emptyList()
    private var pendingHeld: List<PlayingCard> = emptyList()

    var repro by mutableStateOf(false)   // PROOF mode: freeze a fixed state (skip the scoring cascade)

    /** Balatro's small-blind base requirement per ante (ante_base in game.lua) — NOT linear 300×ante.
     *  Big = ×1.5, Boss = ×2 (× boss mult). ante 2 small = 800, matching the reference screenshot. */
    private fun anteBase(a: Int): Double = when (a) {
        1 -> 300.0; 2 -> 800.0; 3 -> 2000.0; 4 -> 5000.0
        5 -> 11000.0; 6 -> 20000.0; 7 -> 35000.0; else -> 50000.0
    }

    val target: Double get() {
        val base = anteBase(ante)
        return when (slot) { 0 -> base; 1 -> base * 1.5; else -> base * (boss?.targetMult ?: 2.0) }
    }

    /** PROOF harness: inject the EXACT state of the reference screenshot (Balatro press kit, Small Blind
     *  ante 2) so a render can be diffed pixel-for-pixel against it. Two Pair 40×2 frozen mid-score. */
    fun loadRepro() {
        repro = true
        // The pixel-gate reference is now the STABLE first-blind SELECTING_HAND frame (vanilla seed
        // REFSHOT1, captured by test/ref.sh → /tmp/bref_3.png). CardArea align_cards is oracle-verified
        // correct for a resting hand, so this can reach true parity — unlike the old transient scoring
        // frame whose mid-slide hand no static layout matched (stuck ~16.9%).
        blindIndex = 0                       // ante 1, slot 0 = Small Blind → target 300
        money = 4
        owned.clear()                        // REFSHOT1's first blind — no jokers yet
        deck.reshuffle(); deck.draw(8)       // remaining → 44/52
        // REFSHOT1's resting hand in G.hand.cards order (rank-desc), dumped by test/ref-autorun.lua.
        hand = listOf(
            PlayingCard(Suit.C, 13), PlayingCard(Suit.H, 12), PlayingCard(Suit.C, 12), PlayingCard(Suit.S, 11),
            PlayingCard(Suit.H, 8), PlayingCard(Suit.D, 6), PlayingCard(Suit.C, 4), PlayingCard(Suit.S, 2),
        )
        selected = emptySet()
        handsLeft = 4; discardsLeft = 4; roundScore = 0.0
        lastResult = null                    // no hand played yet — hand name row must be blank
        lastSteps = emptyList()
        scoreCards = emptyList()
        displayChips = 0.0; displayMult = 0.0; popIndex = -1
        scoring = false                      // resting SELECTING_HAND — no scoring overlay
        phase = Phase.ROUND
    }

    /** Like [loadRepro] but lets the scoring cascade actually RUN (repro = false) — a live-cascade
     *  harness for verifying the P4 animation (chip/mult count-up + card pops via the EventManager).
     *  Sets up the bref_3 jokers/deck, then plays the Two Pair fresh so the cascade fires live. */
    fun loadReproLive() {
        loadRepro()
        repro = false; scoring = false
        // self-contained demo jokers (loadRepro no longer sets any — it's the plain SELECTING_HAND
        // gate state): foil + holo + negative + poly so all four edition shaders show in repro-live.
        owned.clear()
        buy(Offer("j_joker", "Joker", "+4 Mult", 0, edition = Edition.FOIL), free = true)
        buy(Offer("j_clever", "Clever Joker", "+80 Chips if hand has a Two Pair", 0, edition = Edition.HOLO), free = true)
        buy(Offer("j_joker", "Negative Joker", "+1 joker slot", 0, edition = Edition.NEGATIVE), free = true)
        buy(Offer("j_joker", "Poly Joker", "+4 Mult", 0, edition = Edition.POLY), free = true)   // last → demo self-destruct
        // 8-card hand = the Two Pair to play (0..3) + the 4 that REMAIN (4..7, the bref_3 unplayed hand).
        // Playing 0..3 leaves 4..7 in the hand, which then SLIDE 6.986→8.886 as scoring starts.
        // One played card is GLASS + forceGlassBreak so it SHATTERS (dissolve.fs) after the tally;
        // materializeJokers fades the jokers IN at the start (start_materialize) — the create half.
        forceGlassBreak = true
        materializeJokers = true
        demoSelfDestruct = true   // a joker burns away (fiery dissolve) on cash-out — the destroy half
        consumables.add(Consumable.TarotC(TAROTS[0])); consumables.add(Consumable.SpectralC(Spectral.BLACK_HOLE))  // fill the consumable slots (tap to use)
        hand = listOf(
            PlayingCard(Suit.D, 10), PlayingCard(Suit.C, 10),
            PlayingCard(Suit.H, 7, enhancement = Enhancement.GLASS), PlayingCard(Suit.S, 7),
            PlayingCard(Suit.C, 12), PlayingCard(Suit.S, 11), PlayingCard(Suit.D, 6), PlayingCard(Suit.S, 4),
        )
        selected = setOf(0, 1, 2, 3)
        play()
    }

    /** The boss at THIS ante's Boss slot — for the blind-select preview. `boss` is only assigned
     *  once you're ON the boss slot, so the select screen (you're at Small) needs the upcoming one.
     *  Same RNG as the slot-2 assignment, keyed to the boss-slot index (current ante's 3rd blind). */
    val upcomingBoss: Boss
        get() = Boss.pool(ante).random(Random((blindIndex - blindIndex % 3 + 2) * 2654435761L + 1))

    /** Amount for each blind slot in the CURRENT ante (slot 0=Small, 1=Big, 2=Boss).
     *  Mirrors get_blind_amount()*blind.config.mult from Lua. Used by blind-select cards. */
    fun targetForSlot(slotIdx: Int): Double {
        val base = anteBase(ante)
        return when (slotIdx) { 0 -> base; 1 -> base * 1.5; else -> base * upcomingBoss.targetMult }
    }

    /** Reward dollars for each blind slot (config.dollars in Lua: Small=$3, Big=$4, Boss=$5). */
    fun rewardForSlot(slotIdx: Int): Int = 3 + slotIdx

    /** Name label for the upcoming blind slot on the blind-select screen. */
    fun nameForSlot(slotIdx: Int): String = when (slotIdx) {
        0 -> "Small Blind"; 1 -> "Big Blind"; else -> upcomingBoss.display
    }

    /** Description for the blind-select screen (boss ability line, or empty for Small/Big). */
    fun descForSlot(slotIdx: Int): String = if (slotIdx == 2) upcomingBoss.desc else ""

    /** Mirrors G.GAME.blind.chip_text — the chip target as a formatted string for the HUD_blind T node.
     *  scale=0.001 in source; blind_chip_UI_scale springs to 0.5 on round start (implemented in HudColumn). */
    val chipText: String get() = fmtR(target)

    /** The blind's $ reward shown on the HUD blind panel — config.dollars (Small=3/Big=4/Boss=5),
     *  matching the coins Balatro draws on the blind token. (The per-hand/interest bonuses appear at
     *  cash-out, not here.) */
    val dollarsToBeEarned: Int get() = rewardForSlot(slot)

    // ── contents.hand bindings — mirror current_round.current_hand ──────────────
    // These feed the DynaText Os in hudHand(). Compose recomposes when the mutableStateOf
    // fields they read (scoring, displayChips, displayMult, lastResult) change.

    /** Mirrors current_hand.handname_text — shown in the hand-name DynaText. */
    val handNameText: String get() = if (scoring || lastResult != null)
        handName(lastResult?.handType ?: HandType.NONE) else ""

    /** Mirrors current_hand.chip_text — live cascade counter for the chips box (blank when idle). */
    /** contents.dollars_chips round-score readout (G.GAME.chips_text); always shown ("0" at start). */
    val chipsText: String get() = fmtR(roundScore)
    val chipText2: String get() = if (scoring || lastResult != null) fmtR(displayChips) else "0"

    /** Mirrors current_hand.chip_total_text — cumulative round score shown in the top-row readout. */
    val chipTotalText: String get() = if ((scoring || lastResult != null) && roundScore > 0.0) fmtR(roundScore) else ""

    /** Mirrors current_hand.mult_text — the mult DynaText (blank when idle). */
    val multText: String get() = if (scoring || lastResult != null) fmtR(displayMult) else "0"

    /** Mirrors current_hand.hand_level — the Lv badge T node. */
    val currentHandLevel: Int get() = lastResult?.let { handLevel(it.handType) } ?: 0

    init {
        Telemetry.event("RUN_START")
        buy(Offer("j_joker", "Joker", "+4 Mult", 0), free = true)   // start with a Joker
        startRound()
    }

    /** Sync the static FJoker.n fields that mirror run-state counts — deck size, stone/steel card
     *  counts, joker count, enhanced-card count. Called at the start of play() so the score engine
     *  sees live values every hand. (The accumulating fields mult/x/chips are updated incrementally
     *  by the event hooks below; only the *count* fields read from run state every hand.) */
    private fun syncFJokerN() {
        val stoneCount = deck.countEnhancement(Enhancement.STONE)
        val steelCount = deck.countEnhancement(Enhancement.STEEL)
        for (o in owned) when (o.fj.key) {
            "j_blue_joker"       -> o.fj.n = deck.remaining
            "j_stone"            -> o.fj.n = stoneCount
            "j_steel_joker"      -> o.fj.n = steelCount
            "j_abstract"         -> o.fj.n = owned.size
            "j_drivers_license"  -> o.fj.n = deck.enhancedCards
            "j_banner"           -> o.fj.n = discardsLeft
            "j_mystic_summit"    -> {} // reads ctx.discardsLeft directly — no FJoker state needed
        }
    }

    /** Compute which card indices should be face-down after a draw, per the active boss.
     *  [newIndices] is the set of indices in the current `hand` that were JUST drawn (not kept from
     *  a previous hand). Called from startRound() (all cards are new) and refill() (kept cards stay
     *  at their previous reveal state; only newly drawn slots may flip face-down). */
    private fun applyFaceDown(newIndices: Set<Int>) {
        val extra = when (boss) {
            // THE_HOUSE: entire first hand is face-down; refill() passes an empty set so nothing flips.
            Boss.THE_HOUSE -> newIndices
            // THE_MARK: all face cards (J/Q/K) that are newly drawn go face-down.
            Boss.THE_MARK  -> newIndices.filter { hand.getOrNull(it)?.isFace == true }.toSet()
            // THE_WHEEL: 1 in 7 newly drawn cards face-down. Balatro rolls a 1-in-7 per card
            // (pseudorandom per card index). We derive deterministically from the card's identity
            // (hash of suit+rank+blindIndex) so the same card in the same round always agrees.
            Boss.THE_WHEEL -> newIndices.filter { i ->
                val c = hand.getOrNull(i) ?: return@filter false
                ((c.suit.ordinal * 13 + c.rank + blindIndex * 17) and 0x7FFFFFFF) % 7 == 0
            }.toSet()
            // THE_FISH: ALL newly drawn cards are face-down (cards kept from the previous hand
            // were already revealed when the player selected them and stay face-up).
            Boss.THE_FISH  -> newIndices
            else -> emptySet()
        }
        faceDown = faceDown + extra
    }

    private fun startRound() {
        boss = if (slot == 2) Boss.pool(ante).random(Random(blindIndex * 2654435761L + 1)) else null
        handSize = baseHandSize
        applyTags(TagTrigger.ROUND_START)     // Juggle Tag: handSize += 3 for this round
        deck.reshuffle()                  // re-deal the persistent deck (enhancements preserved)
        hand = deck.draw(handSize); selected = emptySet(); faceDown = emptySet()
        roundScore = 0.0
        handsLeft = boss?.hands(baseHands) ?: baseHands          // base (+Grabber); The Needle: 1 hand
        discardsLeft = boss?.discards(baseDiscards) ?: baseDiscards  // base (+Wasteful); The Water: 0 discards
        lastResult = null; lastSteps = emptyList()
        eyeUsedHands.clear(); mouthLockedHand = null    // THE_EYE / THE_MOUTH per-round state
        if (slot == 0) pillarPlayedCards.clear()        // THE_PILLAR: reset at start of new Ante
        // ── Ante-10 showdown per-round state ──────────────────────────────────────────────
        crimsonHeartDisabled = null; bellForcedIdx = null
        roundHandTypes.clear()    // j_cry_keychange resets each round
        roundDiscardsUsed = 0   // mondrian: track discards used this round
        // dropshot: pick a random suit for this round (mirrors reset_castle_card pseudorandom_element
        // on deck cards seeded by ante in Lua). Seeded by blindIndex; Suit.NONE excluded (SMODS.has_no_suit).
        dropShotSuit = Suit.values().random(Random(blindIndex * 998244353L + 7))
        // THE_MANACLE: -1 joker slot for the boss round; restore at round start so it only applies once.
        maxJokers = if (boss == Boss.THE_MANACLE) MAX_JOKERS - 1 else MAX_JOKERS
        if (boss == Boss.AMBER_ACORN && owned.size > 1) owned.shuffle()  // AMBER_ACORN: randomise joker order
        if (boss == Boss.CERULEAN_BELL) bellForcedIdx = hand.indices.random()  // pick forced card after draw
        phase = Phase.ROUND
        applyFaceDown(hand.indices.toSet())   // face-down bosses flip their initial cards
        Telemetry.event("ROUND_START", "ante" to ante, "blind" to blindName, "target" to target, "boss" to (boss?.display ?: "-"))
    }

    private fun refill() {
        val keep = hand.filterIndexed { i, _ -> i !in selected }
        val keepSize = keep.size
        hand = keep + deck.draw(handSize - keepSize)
        // Face-down state: kept cards keep whatever reveal state they had; newly drawn slots
        // (indices keepSize..hand.lastIndex) are candidates for the boss's face-down rule.
        // THE_HOUSE does NOT re-apply on refill (only the opening hand is face-down).
        faceDown = faceDown.filter { it < keepSize }.toSet()   // drop face-down for discarded slots
        if (boss != Boss.THE_HOUSE) applyFaceDown((keepSize until hand.size).toSet())
        selected = emptySet()
    }

    fun toggle(i: Int) {
        if (phase != Phase.ROUND) return
        // When a tarot is pending, taps select TARGET cards (not play-selection).
        if (pendingTarot != null) {
            tarotTarget = if (i in tarotTarget) tarotTarget - i
                          else if (tarotTarget.size < 2) tarotTarget + i
                          else tarotTarget   // already 2 targets; ignore
            return
        }
        // Face-down cards: first tap REVEALS (removes from faceDown) and selects simultaneously,
        // matching vanilla Balatro where hover reveals and click selects in one gesture. The card
        // stays revealed for the rest of the round (faceDown is not re-added on deselect).
        if (i in faceDown) { faceDown = faceDown - i; selected = selected + i; return }
        // CERULEAN_BELL: the forced card is always selected and cannot be deselected
        if (boss == Boss.CERULEAN_BELL && i == bellForcedIdx && i in selected) return
        selected = if (i in selected) selected - i else selected + i
    }

    /** Score the selection now (the engine), but resolve it as an ANIMATION — the UI drives
     *  scoreStep()/scoreCommit() over time so chips/mult tick up and cards pop one by one. */
    fun play() {
        if (phase != Phase.ROUND || selected.isEmpty() || scoring) return
        syncFJokerN()   // update deck-size / stone / steel / abstract / drivers_license counts before scoring
        // ── hand-detection joker hooks (derived early — needed for both boss gates and scoring) ──
        val fjokers = owned.map { it.fj }
        val maxi        = fjokers.any { it.key == "j_cry_maximized" }
        val fourFingers = fjokers.any { it.key == "j_four_fingers" }
        val shortcut    = fjokers.any { it.key == "j_shortcut" }
        val smeared     = fjokers.any { it.key == "j_smeared" }
        val rankOf: (PlayingCard) -> Int = if (maxi) { c -> c.id.let { if (it in 2..10) 10 else if (it in 11..13) 13 else it } } else { c -> c.id }
        // ── boss blind play gates ──────────────────────────────────────────────────────────────
        val selIndices = selected   // capture before we compute sel
        val handType0 = Hands.evaluate(hand.filterIndexed { i, _ -> i in selIndices }, rankOf, fourFingers, shortcut, smeared).first
        if (boss == Boss.THE_PSYCHIC && selIndices.size != 5) return    // THE_PSYCHIC: must play 5
        if (boss == Boss.THE_EYE && handType0 in eyeUsedHands) return  // THE_EYE: no repeat type
        if (boss == Boss.THE_MOUTH && mouthLockedHand != null && handType0 != mouthLockedHand) return  // THE_MOUTH: locked
        val sel = hand.filterIndexed { i, _ -> i in selected }
        val held = hand.filterIndexed { i, _ -> i !in selected }
        // FAITHFUL Score engine: maximized-aware hand type drives the planet level; FJokers carry
        // scaling state and accumulate across hands (krusty/primus mutate during scoring).
        val handType = Hands.evaluate(sel, rankOf, fourFingers, shortcut, smeared).first
        val level = handLevels.level(handType)
        val trace = ArrayList<ScoreStep>()
        // hands_left/discards_left as the engine sees them during evaluate_play: hands_left is the
        // count AFTER this hand (Balatro decrements before scoring), discards_left is the current count.
        // THE_PILLAR: pass previously-played-this-Ante cards as DebuffCards; other bosses use scoringDebuff
        val activeDebuff: Debuff = if (boss == Boss.THE_PILLAR) Debuff.DebuffCards(pillarPlayedCards.toSet())
                                   else boss?.scoringDebuff ?: Debuff.None
        // CERULEAN_BELL: the forced card must be included in every play
        val bellIdx = bellForcedIdx
        if (boss == Boss.CERULEAN_BELL && bellIdx != null && bellIdx !in selIndices) return
        // CRIMSON_HEART: pass the currently-disabled joker key so calcJoker skips it
        val crimsonKey = if (boss == Boss.CRIMSON_HEART) crimsonHeartDisabled?.key else null
        // ── per-hand pseudorandom joker pre-resolution (run loop owns RNG; score engine is pure) ──
        // googol_play: X1e100 Mult with 1-in-odds (default 8) probability each hand (epic.lua:220-228).
        // Roll here; score engine reads j.x (1e100 on hit, 1.0 on miss). Reset each hand before the roll.
        for (o in owned) if (o.fj.key == "j_cry_googol_play") {
            val odds = if (o.fj.n > 0) o.fj.n else 8
            o.fj.x = if (Random.nextInt(odds) == 0) 1e100 else 1.0
        }
        // busdriver: +mult or -mult (default 50) each joker_main with 1-in-odds probability (misc_joker.lua:7653).
        // j.mult = +default_mult on success, -default_mult on fail. j.n stores odds (default 2).
        for (o in owned) if (o.fj.key == "j_cry_busdriver") {
            val base = 50.0   // config.extra.mult_mod (default 50; could be stored in o.fj.chips if run loop tracks it)
            val odds = if (o.fj.n > 0) o.fj.n else 2
            o.fj.mult = if (Random.nextInt(odds) == 0) base else -base
        }
        // ── before-hand joker accumulator hooks (context.before, non-scoring) ──────────────────
        // fspinner: +6 Chips when ANY other visible hand type has been played >= times this type has
        // (misc_joker.lua:1872-1886: for k,v in pairs(G.GAME.hands): k ~= scoring_name and v.played >= played_count).
        // "visible" = the hand is available to play; all standard hands visible in the engine.
        // Approximation: check all other hand types in _handPlayed (played>=1 implies visible+played).
        // But the Lua checks `v.played >= play_more_than` where play_more_than is THIS hand's played count
        // (including the current hand, since played is incremented before the before pass in vanilla).
        // Engine: use handPlayed(handType) for the current count (this hand NOT yet counted → use +1 to match).
        for (o in owned) if (o.fj.key == "j_cry_fspinner") {
            val thisCount = handPlayed(handType) + 1  // +1: vanilla increments played before before-pass
            val yes = _handPlayed.any { (k, v) -> k != handType && v >= thisCount }
            if (yes) o.fj.chips += 6.0
        }
        // spaceglobe: +0.2 Xchip each time the target hand type is played (misc_joker.lua:3432-3453).
        // j.n stores the target hand type as its ordinal (0=HIGH_CARD by default from config.extra.type="High Card").
        // After a match, the target rotates to a random OTHER hand type. Score engine reads j.xc when > 1.
        for (o in owned) if (o.fj.key == "j_cry_spaceglobe") {
            val targetType = HandType.values().getOrNull(o.fj.n) ?: HandType.HIGH_CARD
            if (handType == targetType) {
                o.fj.xc += 0.2
                // Rotate to a random other standard hand type (exclude NONE and CRY_* custom types and current).
                val candidates = HandType.values().filter { it != targetType && it != HandType.NONE
                    && !it.name.startsWith("CRY_") }
                if (candidates.isNotEmpty()) o.fj.n = candidates.random().ordinal
            }
        }
        // dropshot: +0.2 Xmult per non-scoring (held) card of this round's target suit (misc_joker.lua:57-88).
        // "non-scoring" = cards in full_hand but NOT in scoring_hand (cry_dropshot_incompat).
        // dropShotSuit was chosen at startRound() from the deck, seeded by blindIndex.
        for (o in owned) if (o.fj.key == "j_cry_dropshot") {
            val nonScoring = held.filter { it.isSuit(dropShotSuit, smeared) }
            if (nonScoring.isNotEmpty()) o.fj.x += 0.2 * nonScoring.size
        }
        val r = Score.score(sel, fjokers, held, level, activeDebuff, handsLeft - 1, discardsLeft,
                            debuffedJokerKey = crimsonKey, handTypePlays = _handPlayed, trace = trace)
        lastResult = r; lastSteps = trace
        pending = r; pendingSel = sel; pendingHeld = held
        // the played cards LEAVE the hand immediately (they're now in G.play) — so the engine's
        // identity-tracked card Moveables transfer hand→play carrying their VT (the fly-in). refill()
        // keeps the held hand (selected is empty now) and draws back up to 8 at commit.
        // Remap faceDown indices after the played cards leave the hand. Played cards were
        // already revealed (toggle removes from faceDown before adding to selected), so faceDown
        // only contains held-card indices; map each through to its new position in `held`.
        val oldToNew = HashMap<Int, Int>()
        var newIdx = 0
        for (oldIdx in hand.indices) { if (oldIdx !in selected) { oldToNew[oldIdx] = newIdx; newIdx++ } }
        faceDown = faceDown.mapNotNull { oldToNew[it] }.toSet()
        hand = held; selected = emptySet()
        scoreCards = sel; popIndex = -1
        displayChips = trace.firstOrNull()?.chips ?: r.chips    // start at the hand base
        displayMult = trace.firstOrNull()?.mult ?: r.mult
        scoring = true                                          // LaunchedEffect picks it up
        Telemetry.event("ROUND_HAND", "blind" to blindName, "type" to r.handType, "score" to r.score)
    }

    /** Advance the readout to cascade step `i` and pop that card (called on a timer by the UI). */
    fun scoreStep(i: Int) {
        val step = lastSteps.getOrNull(i) ?: return
        displayChips = step.chips; displayMult = step.mult
        popIndex = i - 1                                        // step 0 is the base; 1.. are cards
    }

    /** repro-live demo: force every played glass card to shatter (so the burn animation is visible). */
    var forceGlassBreak = false
    /** repro-live demo: materialize the jokers IN on first frame (start_materialize, the create half
     *  of the destroy/create pair) so the reverse-dissolve is visible. */
    var materializeJokers = false
    /** repro-live demo: at end-of-round, also self-destruct the LAST owned joker (a sprite-backed one)
     *  so the fiery dissolve is visible behind the cash-out panel — exercises the real SELF_DESTRUCT
     *  path with a rendered joker (Broken Home, the only real self-destruct key, may lack art). */
    var demoSelfDestruct = false
    /** m_glass `extra = 4` → a played glass card has a 1-in-4 chance to shatter (Card:shatter) after
     *  it scores. The demo flag forces it; real play rolls it. */
    fun rollGlassBreak(): Boolean = forceGlassBreak || Random.nextInt(4) == 0

    /** A played Glass card shattered after scoring — permanently destroy it from the run's deck
     *  (Card:shatter removes it from G.playing_cards). Shrinks the deck for the rest of the run, so
     *  deck-size jokers (Blue Joker) and stone/steel/enhanced tallies (Driver's License) update, and
     *  it can empty a rank for j_cry_blacklist's self-destruct. Persists via the serialized composition. */
    fun shatterCard(card: PlayingCard) { deck.removeCard(card) }

    /** Bank the scored hand (the end-of-round evaluation): score, refill, settle the lose/over case.
     *  Returns true if the round was WON (target met) — the cascade then runs the end-of-round
     *  self-destruct-joker dissolve and [enterRoundEval]. (state_events.lua: the end_of_round
     *  calculate destroys self-destruct jokers BEFORE the ROUND_EVAL state, on the board.) */
    fun scoreBank(): Boolean {
        val r = pending ?: return false
        // ── capture pre-increment state for accumulator hooks (must be BEFORE recordHandPlayed) ──
        val isNewTypeThisRound = r.handType !in roundHandTypes && r.handType != HandType.NONE
        val prevMostPlayed = mostPlayedHand?.first                // obelisk: most-played BEFORE this hand
        roundScore += r.score; handsLeft -= 1
        totalChipsScored += r.score
        totalCardsPlayed += pendingSel.size
        if (r.handType != HandType.NONE && r.handType != HandType.CRY_NONE) recordHandPlayed(r.handType)
        // ── per-hand joker accumulator hooks (the run loop owns state; score engine reads it) ──────
        totalHandsPlayed += 1
        roundHandTypes.add(r.handType)
        // MANIFEST: migrated jokers evolve state on the hand-scored event via their reducer
        // (green_joker +1 Mult, spare_trousers +2 on Two Pair/Full House, runner +15 on Straight, square +4 on 5 cards).
        for (o in owned) JOKER_MANIFEST[o.fj.key]?.reduce?.let { o.fj.restore(it(o.fj.snapshot(), GameEvent.HandScored(r.handType, pendingSel.size))) }
        for (o in owned) when (o.fj.key) {
            // j_popcorn: +5 Mult base, -1 per hand played; self-destruct when mult hits 0.
            "j_popcorn"        -> o.fj.mult = maxOf(0.0, o.fj.mult - 1.0)
            // (spare_trousers / runner / square migrated to JOKER_MANIFEST reducers.)
            // j_obelisk: +0.2 Xmult per hand NOT of the most-played type (Balatro: "not the most played hand in this run").
            // Uses prevMostPlayed (before this hand increments the count) so the threshold is consistent.
            "j_obelisk"        -> if (prevMostPlayed != null && r.handType != prevMostPlayed) o.fj.x += 0.2
            // j_cry_clockwork: +0.25 Xmult every 3rd hand played (config.extra clock=3; totalHandsPlayed counts all hands).
            "j_cry_clockwork"  -> if (totalHandsPlayed % 3 == 0) o.fj.x += 0.25
            // j_cry_keychange: +0.25 Xmult each time a new hand type is played (first time this round); resets on startRound.
            "j_cry_keychange"  -> if (isNewTypeThisRound) o.fj.x += 0.25
            // j_cry_duplicare: +1 Xmult per played card this hand (config.extra xmult_mod=1; fires in the "before" context).
            "j_cry_duplicare"  -> o.fj.x += pendingSel.size.toDouble()
            // j_cry_jimball: +0.15 Xmult while THIS hand type is the strict most-played (no other type has
            // been played as often); resets x→1 if another type ties or beats it (misc_joker.lua:1623-1656).
            // _handPlayed already includes this hand (recordHandPlayed ran above), matching the Lua timing.
            "j_cry_jimball"    -> if (r.handType != HandType.NONE && r.handType != HandType.CRY_NONE) {
                val playMoreThan = handPlayed(r.handType)
                val beaten = _handPlayed.any { (h, n) -> h != r.handType && n >= playMoreThan }
                if (beaten) { if (o.fj.x > 1.0) o.fj.x = 1.0 } else o.fj.x += 0.15
            }
            // j_cry_happyhouse: +1 "check" per hand; once check > trigger(114) the joker_main gate (j.n>0)
            // fires Emult^4 (misc_joker.lua:162,190). chips is the check counter (never read as chips here).
            "j_cry_happyhouse" -> { o.fj.chips += 1.0; if (o.fj.chips > 114.0) o.fj.n = 1 }
        }
        // ── per-hand self-destruct: jokers that destroy themselves when their counter hits 0 ────
        // j_popcorn: self-destructs when mult reaches 0 (card.lua: k_eaten_ex, G.jokers:remove_card).
        // j_ramen: self-destructs when x_mult drops to ≤1.0 (card.lua equivalent; default start 2.0).
        // Both fire AFTER the per-hand loop so the score engine still reads the final value this hand.
        val handSelfDestruct = owned.filter { o ->
            (o.fj.key == "j_popcorn" && o.fj.mult <= 0.0) ||
            (o.fj.key == "j_ramen"   && o.fj.x <= 1.0) ||
            // blacklist: self-destructs once its blacklisted rank is absent from the whole deck
            // (spooky.lua:1044-1063 — gone from play∪hand∪discard∪deck, which partition deck.all).
            // n=0 → unset → Ace(14), matching the engine's blacklist default.
            (o.fj.key == "j_cry_blacklist" && !deck.hasRank(if (o.fj.n == 0) 14 else o.fj.n))
        }
        if (handSelfDestruct.isNotEmpty()) {
            owned.removeAll(handSelfDestruct)
            Telemetry.event("HAND_DESTROY", "n" to handSelfDestruct.size,
                "keys" to handSelfDestruct.joinToString { it.fj.key })
        }
        // ── boss blind effects triggered after each scored hand ────────────────────────────────
        pillarPlayedCards.addAll(pendingSel)                                              // THE_PILLAR: track cards played this Ante
        if (boss == Boss.THE_TOOTH) money = maxOf(0, money - pendingSel.size)   // - per played card
        if (boss == Boss.THE_ARM)   handLevels.degrade(r.handType)              // degrade played hand level
        if (boss == Boss.THE_EYE)   eyeUsedHands += r.handType                 // lock this type for the round
        if (boss == Boss.THE_MOUTH && mouthLockedHand == null) mouthLockedHand = r.handType  // lock first type
        if (boss == Boss.THE_OX && r.handType == mostPlayedHand?.first) money = 0            // zero out money
        money += pendingSel.count { it.seal == Seal.GOLD } * 3
        scoring = false; scoreCards = emptyList(); popIndex = -1
        Telemetry.event("ROUND_BANK", "total" to roundScore)
        refill()
        // THE_SERPENT: after refill, discard the new hand and draw again (player never keeps held cards)
        if (boss == Boss.THE_SERPENT) { hand = deck.draw(hand.size); faceDown = emptySet(); applyFaceDown(hand.indices.toSet()); selected = emptySet() }
        // CRIMSON_HEART: after each play, rotate the disabled joker (never the same one twice in a row;
        // if only one joker exists, it is always the chosen one — fallback = first).
        if (boss == Boss.CRIMSON_HEART && owned.isNotEmpty()) {
            val prev = crimsonHeartDisabled
            val candidates = owned.map { it.fj }.filter { it !== prev }
            crimsonHeartDisabled = (if (candidates.isNotEmpty()) candidates else owned.map { it.fj }).random()
        }
        // CERULEAN_BELL: pick a new forced card after the hand is refilled.
        if (boss == Boss.CERULEAN_BELL) bellForcedIdx = if (hand.isNotEmpty()) hand.indices.random() else null
        if (roundScore >= target) {
            buildCashOut()      // faithful reward breakdown → evalRows / cashOutTotal; banked on Cash Out
            Telemetry.event("ROUND_WIN", "blind" to blindName, "total" to roundScore, "reward" to cashOutTotal)
            phase = Phase.ROUND_EVAL
        } else if (handsLeft <= 0) {
            phase = Phase.OVER
            Telemetry.event("ROUND_LOSE", "blind" to blindName, "total" to roundScore)
        }
        return false
    }

    /** Round won → the cash-out screen (entered after the end-of-round self-destruct dissolves start,
     *  so the burning jokers are still visible behind the cash-out panel — see RoundPlay). */
    fun enterRoundEval() {
        applyTags(TagTrigger.EVAL)   // Investment Tag: +$25 after defeating the blind
        buildCashOut()          // faithful reward breakdown → evalRows / cashOutTotal; banked on Cash Out
        Telemetry.event("ROUND_WIN", "blind" to blindName, "total" to roundScore, "reward" to cashOutTotal)
        phase = Phase.ROUND_EVAL
    }

    /** Build the cash-out rows the way evaluate_round (state_events.lua:1147) does — in order:
     *  blind reward (config.dollars 3/4/5), then bonus rows for remaining hands ($1 each), gold-
     *  enhancement cards held ($3 each, their end-of-round calculate_dollar_bonus), and interest
     *  ($1 per $5 held, capped at $5 — interest_amount=1, interest_cap=25). Interest reads `money`
     *  as it stands now (the bankroll BEFORE this cash-out is paid), matching G.GAME.dollars. */
    private fun buildCashOut() {
        val rows = ArrayList<EvalRow>()
        rows += EvalRow(EvalKind.BLIND, rewardForSlot(slot), blindName)
        if (handsLeft > 0) rows += EvalRow(EvalKind.HANDS, handsLeft, "\$1 per remaining hand", handsLeft.toString())
        val gold = pendingHeld.count { it.enhancement == Enhancement.GOLD }
        if (gold > 0) rows += EvalRow(EvalKind.GOLD, gold * 3, "Gold cards held", gold.toString())
        val interest = minOf(money / 5, interestCap)        // Seed Money raises the cap 5 → 10
        if (interest > 0) rows += EvalRow(EvalKind.INTEREST, interest, "1 interest per \$5 (max \$$interestCap)")
        evalRows = rows
        cashOutTotal = rows.sumOf { it.dollars }
    }

    /** Cash Out (button='cash_out'): ease_dollars(current_round.dollars) banks the total, then the
     *  blind→shop transition runs (END_OF_ROUND self-destructs, advance blind, roll the next shop). */
    fun cashOut() {
        if (phase != Phase.ROUND_EVAL) return
        money += cashOutTotal
        // self-destruct jokers (Broken Home) DISSOLVED at end-of-round (the cascade's startDissolve →
        // onGone removes them). Safety net: guarantee the logical removal even if the player cashes out
        // before the 0.735s burn finishes (which unmounts the play field before onGone fires). Idempotent.
        val destroyed = owned.filter { it.fj.key in SELF_DESTRUCT_KEYS }
        if (destroyed.isNotEmpty()) {
            owned.removeAll(destroyed)
            Telemetry.event("END_OF_ROUND_DESTROY", "n" to destroyed.size)
        }
        // ── end-of-round joker accumulator hooks (context.end_of_round, non-scoring) ─────────────
        // chili_pepper: +0.5 Xmult per end_of_round; self-destruct when rounds_remaining hits 0
        // (misc_joker.lua:1119-1177). j.x = Xmult accumulator; j.n = rounds_remaining (default 8).
        // fading_joker / paved_joker: +1 Xmult when another perishable joker expires (perishable_debuffed).
        // Trigger: when chili_pepper's rounds_remaining reaches 0, notify fading/paved before removing.
        val perishableExpired = ArrayList<Owned>()
        for (o in owned) if (o.fj.key == "j_cry_chili_pepper") {
            o.fj.x += 0.5
            o.fj.n = maxOf(0, o.fj.n - 1)  // j.n = rounds_remaining (0 → self-destruct)
            if (o.fj.n <= 0) perishableExpired.add(o)
        }
        // caramel: counts down rounds_remaining (j.n, init 11) each end_of_round; self-destructs at 0
        // (epic.lua:1273-1312). j.x=1.75 (individual xMult per scored card) does NOT change — only
        // j.n ticks. No Xmult scaling; the x_mult is fixed for caramel's lifetime.
        for (o in owned) if (o.fj.key == "j_cry_caramel") {
            o.fj.n = maxOf(0, o.fj.n - 1)
            if (o.fj.n <= 0) perishableExpired.add(o)
        }
        // Notify fading_joker and paved_joker of each expiring perishable.
        if (perishableExpired.isNotEmpty()) {
            for (rem in owned) when (rem.fj.key) {
                "j_cry_fading_joker", "j_cry_paved_joker" -> rem.fj.x += 1.0
            }
        }
        // Remove expired perishables from the board.
        if (perishableExpired.isNotEmpty()) {
            owned.removeAll(perishableExpired)
            Telemetry.event("END_OF_ROUND_DESTROY", "n" to perishableExpired.size, "reason" to "perishable")
        }
        // mondrian: +0.25 Xmult when 0 discards were used this round (misc_joker.lua:3228-3246).
        for (o in owned) if (o.fj.key == "j_cry_mondrian" && roundDiscardsUsed == 0) o.fj.x += 0.25
        // biggestm: reset j.n to 0 at end_of_round (m.lua: the before-pass check persists until reset).
        for (o in owned) if (o.fj.key == "j_cry_biggestm") o.fj.n = 0
        // jollysus: re-arm the once-per-round spawn flag at end_of_round (m.lua:27-30).
        for (o in owned) if (o.fj.key == "j_cry_jollysus") o.fj.n = 1
        val wasSlot = blindIndex % 3      // slot of the round just beaten (before increment)
        val wasAnte = blindIndex / 3 + 1   // ante of the round just beaten
        blindIndex += 1
        resetRerollCost()                            // fresh shop → reroll cost back to base
        freeRerollThisShop = false; couponThisShop = false      // per-shop tag effects reset, then re-applied
        applyTags(TagTrigger.SHOP_START); applyTags(TagTrigger.SHOP_FINAL)   // D6 / Coupon
        shop = rollShop(blindIndex, 3 + shopSlotsBonus); shopPlanets = rollPlanets(blindIndex); shopTarots = rollTarots(blindIndex)
        shopVoucher = rollVoucher(blindIndex, redeemedVouchers.toSet())   // one voucher per shop
        shopBoosters = rollBoosters(blindIndex)                           // two booster slots per shop
        // Win condition: beating the boss blind of Ante 8 (standard) or Ante 10 (showdown).
        if (wasSlot == 2 && wasAnte in setOf(8, 10)) {
            phase = Phase.WIN
            Telemetry.event("RUN_WIN", "ante" to wasAnte, "money" to money)
        } else {
            // Pre-seed boss so blind-select and shop screens show correct name/desc.
            // startRound() re-derives the same deterministic value.
            boss = if (slot == 2) Boss.pool(ante).random(Random(blindIndex * 2654435761L + 1)) else null
            phase = Phase.SHOP
        }
        Telemetry.event("CASH_OUT", "total" to cashOutTotal, "money" to money)
    }

    fun discard() {
        if (phase != Phase.ROUND || selected.isEmpty() || discardsLeft <= 0) return
        // THE_HOOK: override selection with 2 random cards from the current hand
        if (boss == Boss.THE_HOOK) {
            val hookIndices = hand.indices.shuffled().take(2).toSet()
            selected = hookIndices
            faceDown = faceDown - hookIndices   // force-discarded face-down cards are revealed as they leave
        }
        discardsLeft -= 1
        roundDiscardsUsed += 1   // mondrian: track discards used this round for end_of_round check
        totalCardsDiscarded += selected.size
        // ── per-discard joker accumulator hooks ───────────────────────────────────────────────
        val discardedCards = hand.filterIndexed { i, _ -> i in selected }
        val jackCount = discardedCards.count { it.id == 11 }
        // For j_castle: count cards of the flush suit in the discard (if the discarded set is a flush).
        val discardSuits = discardedCards.map { it.suit }.distinct()
        val flushSuit = if (discardSuits.size == 1) discardSuits.first() else null
        // MANIFEST: migrated jokers evolve state on the discard event via their reducer (e.g. green_joker -1 Mult).
        for (o in owned) JOKER_MANIFEST[o.fj.key]?.reduce?.let { o.fj.restore(it(o.fj.snapshot(), GameEvent.Discarded(discardedCards))) }
        for (o in owned) when (o.fj.key) {
            // (j_ramen depletion migrated to its JOKER_MANIFEST reducer on the Discarded event.)
            // j_mail: +2 Mult per Jack discarded (config.extra mult=2, rank=11).
            "j_mail"        -> if (jackCount > 0) o.fj.mult += 2.0 * jackCount
            // j_castle: +3 Chips per suit in a FLUSH discard (config.extra chips=3; only counts matching suit cards).
            // Faithful: fires in context.discard for flush hands; we approximate as "all cards same suit".
            "j_castle"      -> if (flushSuit != null) o.fj.chips += 3.0 * discardedCards.count { it.suit == flushSuit }
        }
        Telemetry.event("ROUND_DISCARD", "n" to selected.size)
        refill()
    }

    fun buy(offer: Offer, free: Boolean = false) {
        if (owned.size >= maxJokers) return           // joker slot full
        val cost = price(offer.cost)
        if (!free && money < cost) return
        if (!free) { money -= cost; totalCardsPurchased += 1 }
        // The faithful Score engine scores via FJoker (carries scaling state, persisted across hands).
        onCardBought()                               // context.buying_card: scale cursors before the new card lands
        owned.add(Owned(offer, initialFJoker(offer, owned.sumOf { maxOf(1.0, it.offer.cost / 2.0) })))
        shop = shop.filterNot { it === offer }
        if (!free) Telemetry.event("RUN_BUY", "key" to offer.key, "edition" to offer.edition.name, "cost" to cost, "money" to money)
    }

    /** Put a random Joker on the board with its correct initial state — jollysus' on-sell spawn
     *  (Cryptid create_card("Joker")). Respects the joker slot cap. */
    private fun createRandomJoker() {
        if (owned.size >= maxJokers) return
        val offer = CATALOG.random()
        owned.add(Owned(offer, initialFJoker(offer, owned.sumOf { maxOf(1.0, it.offer.cost / 2.0) })))
        Telemetry.event("RUN_SPAWN_JOKER", "key" to offer.key)
    }

    fun sell(o: Owned) {
        if (owned.size <= 1) return                  // keep at least one joker
        owned.remove(o)
        val refund = maxOf(1, o.offer.cost / 2)
        money += refund
        // ── per-sell joker accumulator hooks ──────────────────────────────────────────────────
        val soldKey = o.fj.key
        val sellCost = refund   // maxOf(1, cost/2) — used for sell_cost >= 2 gates below
        // MANIFEST: migrated jokers react to the sale via their reducer on the Sold event
        // (campfire +0.25 Xmult per sale; eternalflame +0.1 when the sold joker's sell_cost >= 2).
        for (rem in owned) JOKER_MANIFEST[rem.fj.key]?.reduce?.let { rem.fj.restore(it(rem.fj.snapshot(), GameEvent.Sold(soldKey, sellCost))) }
        for (rem in owned) when (rem.fj.key) {
            // j_swashbuckler: +Mult = total sell value of all remaining jokers (recalculate on each sell).
            "j_swashbuckler"   -> rem.fj.mult = owned.sumOf { maxOf(1.0, it.offer.cost / 2.0) }
            // j_cry_m: +13 Xmult per Jolly Joker sold.
            "j_cry_m"          -> if (soldKey == "j_jolly") rem.fj.x += 13.0
            // j_cry_loopy: +1 retrigger count per Jolly Joker sold.
            "j_cry_loopy"      -> if (soldKey == "j_jolly") rem.fj.n += 1
            // j_cry_mstack: retriggers +1 per sell_req (3) Jolly Joker sells (m.lua selling_card hook).
            // fj.chips repurposed as the sell-progress counter (0–2); never read as chips for this key.
            "j_cry_mstack"     -> if (soldKey == "j_jolly") {
                if (rem.fj.chips + 1 >= 3) { rem.fj.n += 1; rem.fj.chips = 0.0 } else rem.fj.chips += 1.0
            }
        }
        // j_cry_jollysus: selling any Joker (including a jollysus itself) makes each armed jollysus spawn a
        // random Joker, once per round; fj.n is the spawn flag (1=armed, reset to 1 at end_of_round). Done
        // after the per-sell loop since createRandomJoker mutates `owned` (m.lua:37-48).
        val armedJollys = owned.filter { it.fj.key == "j_cry_jollysus" && it.fj.n == 1 } +
            (if (soldKey == "j_cry_jollysus" && o.fj.n == 1) listOf(o) else emptyList())
        armedJollys.forEach { it.fj.n = 0; createRandomJoker() }
        // VERDANT_LEAF: selling any joker during the boss blind defeats it immediately
        if (boss == Boss.VERDANT_LEAF && phase == Phase.ROUND) { roundScore = target; buildCashOut(); phase = Phase.ROUND_EVAL }
        Telemetry.event("RUN_SELL", "key" to o.offer.key, "refund" to refund, "money" to money)
    }

    /** context.buying_card — every Cryptid Cursor on the board gains +8 Chips when any card is bought. */
    private fun onCardBought() { owned.forEach { if (it.fj.key == "j_cry_cursor") it.fj.chips += 8.0 } }

    fun buyPlanet(po: PlanetOffer, free: Boolean = false) {
        if (!hasConsumableRoom()) return          // no consumable slot → can't take it
        val cost = price(po.cost)
        if (!free && money < cost) return
        if (!free) { money -= cost; totalCardsPurchased += 1 }
        onCardBought()
        consumables.add(Consumable.PlanetC(po.planet))    // HELD until used (was: insta-level-up)
        shopPlanets = shopPlanets.filterNot { it === po }
        // ── per-planet-buy joker accumulator hooks ────────────────────────────────────────────
        for (o in owned) when (o.fj.key) {
            // j_constellation: +0.1 Xmult per planet bought (config.extra xmult=0.1).
            "j_constellation" -> o.fj.x += 0.1
            // j_hiker: +5 Chips per planet bought (config.extra chips=5).
            "j_hiker"         -> o.fj.chips += 5.0
        }
        Telemetry.event("RUN_BUY_PLANET", "planet" to po.planet.display, "hand" to po.planet.hand.name, "money" to money)
    }

    fun buyTarot(t: TarotOffer, free: Boolean = false) {
        if (!hasConsumableRoom()) return
        val cost = price(t.cost)
        if (!free && money < cost) return
        if (!free) { money -= cost; totalCardsPurchased += 1 }
        onCardBought()
        consumables.add(Consumable.TarotC(t))             // HELD until used (was: insta-enhance)
        shopTarots = shopTarots.filterNot { it === t }
        Telemetry.event("RUN_BUY_TAROT", "tarot" to t.name, "money" to money)
    }

    /** Redeem the shop voucher — a run-persistent modifier (Card:apply_to_run, card.lua:2322). */
    fun redeemVoucher(v: VoucherOffer) {
        val cost = price(v.cost)
        if (money < cost) return
        money -= cost; totalCardsPurchased += 1
        redeemedVouchers.add(v.key)
        shopVoucher = null
        when (v.key) {
            "v_overstock_norm" -> {                            // change_shop_size: +1 slot, now + future
                shopSlotsBonus += v.extra
                shop = shop + CATALOG.filterNot { c -> shop.any { it.key == c.key } }
                    .shuffled(Random(blindIndex * 31L + shop.size)).take(v.extra)
            }
            "v_clearance_sale" -> discountPercent = v.extra    // discount_percent = 25
            "v_reroll_surplus" -> rerollBase = maxOf(0, rerollBase - v.extra)  // round_resets.reroll_cost -= 2
            "v_grabber" -> baseHands += v.extra                // round_resets.hands += 1
            "v_wasteful" -> baseDiscards += v.extra            // round_resets.discards += 1
            "v_seed_money" -> interestCap = v.extra / 5         // interest_cap = 50 → $10 max
        }
        Telemetry.event("RUN_VOUCHER", "key" to v.key, "money" to money)
    }

    /** Buy a booster pack → open it (Phase.PACK_OPEN) with `extra` revealed items of its kind. */
    fun buyBooster(b: BoosterOffer) {
        val cost = price(b.cost)
        if (money < cost) return
        money -= cost; totalCardsPurchased += 1
        shopBoosters = shopBoosters.filterNot { it === b }
        packSeed += 1
        val rng = Random(blindIndex * 7253L + packSeed * 131L)
        val items: List<PackItem> = when (b.kind) {
            "Arcana" -> TAROTS.shuffled(rng).take(b.extra).map { PackItem.Tarot(it) }
            "Celestial" -> Planet.values().toList().shuffled(rng).take(b.extra).map { PackItem.Planet(PlanetOffer(it, 0)) }
            "Buffoon" -> CATALOG.filterNot { c -> owned.any { it.offer.key == c.key } }.shuffled(rng).take(b.extra).map { PackItem.Joker(it) }
            "Standard" -> (0 until b.extra).map { PackItem.Card(PlayingCard(Suit.values().random(rng), (2..14).random(rng))) }
            "Spectral" -> Spectral.values().toList().shuffled(rng).take(b.extra).map { PackItem.SpectralItem(it) }
            else -> emptyList()
        }
        openPack = OpenPack(b.name, b.kind, items, minOf(b.choose, items.size))
        phase = Phase.PACK_OPEN
        Telemetry.event("RUN_PACK_OPEN", "key" to b.key, "kind" to b.kind, "money" to money)
    }

    /** Pick item [i] from the open pack — applies its effect (pre-paid), advances picks, closes when done. */
    fun pickPackItem(i: Int) {
        val p = openPack ?: return
        if (p.picksLeft <= 0 || i in p.picked) return
        when (val item = p.items[i]) {
            is PackItem.Card -> deck.add(item.card)          // Standard pack → card joins the deck
            is PackItem.SpectralItem -> if (hasConsumableRoom()) consumables.add(Consumable.SpectralC(item.s))   // held to use
            is PackItem.Tarot -> buyTarot(item.t, free = true)
            is PackItem.Planet -> buyPlanet(item.p, free = true)
            is PackItem.Joker -> buy(item.o, free = true)
        }
        p.picked.add(i)
        p.picksLeft -= 1
        if (p.picksLeft <= 0) closePack()
    }

    fun skipPack() = closePack()
    private fun closePack() { openPack = null; phase = Phase.SHOP }

    /** Tap a held tarot to enter targeting mode (or cancel if already aiming this tarot). */
    fun aimTarot(t: TarotOffer) {
        if (phase != Phase.ROUND || scoring) return
        pendingTarot = if (pendingTarot === t) null else t
        tarotTarget = emptySet()
    }

    /** Apply the pending tarot to [targets] — the currently selected hand indices.
     *  Each target card gets the enhancement or seal, then the tarot is consumed. */
    fun useTarot() {
        val t = pendingTarot ?: return
        if (tarotTarget.isEmpty()) return
        val targetCards = tarotTarget.mapNotNull { hand.getOrNull(it) }
        var applied = 0
        for (card in targetCards) {
            val ok = if (t.seal != Seal.NONE) deck.sealCard(card, t.seal)
                     else deck.enhanceCard(card, t.enhancement)
            if (ok) applied++
        }
        // Reflect the changes in the live hand (PlayingCard is immutable; replace the instances).
        // The Deck methods update `all` and `drawPile`; if the card is CURRENTLY in the hand we
        // need to rebuild `hand` so Compose sees the new enhancement/seal on the visible card.
        val newHand = hand.map { c ->
            val match = targetCards.find { it == c } ?: return@map c
            if (t.seal != Seal.NONE) c.copy(seal = t.seal) else c.copy(enhancement = t.enhancement)
        }
        hand = newHand
        consumables.removeAll { it is Consumable.TarotC && it.t === t }
        pendingTarot = null; tarotTarget = emptySet()
        Telemetry.event("RUN_USE_TAROT", "tarot" to t.name, "n" to applied)
    }

    fun cancelTarot() { pendingTarot = null; tarotTarget = emptySet() }

    fun handLevel(h: HandType): Int = handLevels.level(h)

    /** Capture the run for serialization (P4 RunStateSerialization). Transient ui/animation/scoring
     *  state is not persisted — just the run-defining graph. */
    fun snapshot(): RunSnapshot = RunSnapshot(
        blindIndex = blindIndex, money = money,
        jokers = owned.map { o ->
            JokerSnap(o.offer.key, o.offer.name, o.offer.desc, o.offer.cost, o.offer.edition.name,
                o.fj.edition, o.fj.mult, o.fj.x, o.fj.chips, o.fj.n, o.fj.rarity, o.fj.xc)
        },
        deck = deck.composition().map { CardSnap(it.suit.name, it.rank, it.enhancement.name, it.seal.name) },
        handLevels = handLevels.all().entries.associate { it.key.name to it.value },
        shopSlotsBonus = shopSlotsBonus, discountPercent = discountPercent, interestCap = interestCap,
        baseHands = baseHands, baseDiscards = baseDiscards, rerollBase = rerollBase,
        redeemedVouchers = redeemedVouchers.toList(), tags = tags.map { it.name },
        consumables = consumables.map { c ->
            when (c) {
                is Consumable.TarotC -> ConsumableSnap("tarot", c.t.name, c.t.enhancement.name, c.t.seal.name)
                is Consumable.PlanetC -> ConsumableSnap("planet", c.planet.display, planet = c.planet.name)
                is Consumable.SpectralC -> ConsumableSnap("spectral", c.s.name)
            }
        },
        phase = phase.name,
        shop = shop.map { OfferSnap(it.key, it.name, it.desc, it.cost, it.edition.name) },
        shopPlanets = shopPlanets.map { PlanetSnap(it.planet.name, it.cost) },
        shopTarots = shopTarots.map { TarotSnap(it.name, it.enhancement.name, it.cost, it.seal.name) },
        shopVoucher = shopVoucher?.let { VoucherSnap(it.key, it.name, it.desc, it.extra, it.cost) },
        shopBoosters = shopBoosters.map { BoosterSnap(it.key, it.name, it.kind, it.cost, it.extra, it.choose) },
        rerollIncrease = rerollIncrease,
        freeRerollThisShop = freeRerollThisShop,
        couponThisShop = couponThisShop,
        baseHandSize = baseHandSize,
    )

    /** Restore a run from a snapshot (load). Lands in the shop — a safe inter-blind state. */
    fun restore(s: RunSnapshot) {
        blindIndex = s.blindIndex; money = s.money
        owned.clear()
        s.jokers.forEach { j ->
            owned.add(Owned(
                Offer(j.key, j.name, j.desc, j.cost, edition = Edition.valueOf(j.edition)),
                FJoker(j.key, j.mult, j.fjEdition, j.x, j.chips, j.n, j.rarity, j.xc)))
        }
        deck.setComposition(s.deck.map { PlayingCard(Suit.valueOf(it.suit), it.rank, Enhancement.valueOf(it.enh), Seal.valueOf(it.seal)) })
        handLevels.setAll(s.handLevels.entries.associate { HandType.valueOf(it.key) to it.value })
        shopSlotsBonus = s.shopSlotsBonus; discountPercent = s.discountPercent; interestCap = s.interestCap
        baseHands = s.baseHands; baseDiscards = s.baseDiscards; rerollBase = s.rerollBase
        redeemedVouchers.clear(); redeemedVouchers.addAll(s.redeemedVouchers)
        tags.clear(); s.tags.forEach { tags.add(Tag.valueOf(it)) }
        consumables.clear()
        s.consumables.forEach { cs ->
            consumables.add(when (cs.kind) {
                "tarot" -> Consumable.TarotC(TarotOffer(cs.name, Enhancement.valueOf(cs.enh), 0, Seal.valueOf(cs.seal)))
                "spectral" -> Consumable.SpectralC(Spectral.valueOf(cs.name))
                else -> Consumable.PlanetC(Planet.valueOf(cs.planet))
            })
        }
        // exact shop stock + per-shop state, then land at the saved phase (SHOP resumes the real shop)
        shop = s.shop.map { Offer(it.key, it.name, it.desc, it.cost, edition = Edition.valueOf(it.edition)) }
        shopPlanets = s.shopPlanets.map { PlanetOffer(Planet.valueOf(it.planet), it.cost) }
        shopTarots = s.shopTarots.map { TarotOffer(it.name, Enhancement.valueOf(it.enh), it.cost, Seal.valueOf(it.seal)) }
        shopVoucher = s.shopVoucher?.let { VoucherOffer(it.key, it.name, it.desc, it.extra, it.cost) }
        shopBoosters = s.shopBoosters.map { BoosterOffer(it.key, it.name, it.kind, it.cost, it.extra, it.choose) }
        rerollIncrease = s.rerollIncrease
        freeRerollThisShop = s.freeRerollThisShop; couponThisShop = s.couponThisShop
        baseHandSize = s.baseHandSize
        phase = runCatching { Phase.valueOf(s.phase) }.getOrDefault(Phase.BLIND_SELECT)
    }

    fun nextBlind() { if (phase == Phase.SHOP) phase = Phase.BLIND_SELECT }

    /** Populate the shop and jump to it — for the --es screen shop parity-screenshot deep-link only. */
    fun toShopForPreview() {
        resetRerollCost()
        freeRerollThisShop = false; couponThisShop = false
        applyTags(TagTrigger.SHOP_START); applyTags(TagTrigger.SHOP_FINAL)
        shop = rollShop(blindIndex, 3 + shopSlotsBonus); shopPlanets = rollPlanets(blindIndex); shopTarots = rollTarots(blindIndex)
        shopVoucher = rollVoucher(blindIndex, redeemedVouchers.toSet())
        shopBoosters = rollBoosters(blindIndex)
        phase = Phase.SHOP
    }

    /** Build a sample cash-out and jump to it — for the --es screen eval parity-screenshot deep-link only. */
    fun toEvalForPreview() { buildCashOut(); phase = Phase.ROUND_EVAL }

    // Reroll cost (calculate_reroll_cost, common_events.lua:2686): base 5, +1 per reroll within a
    // shop, reset to base each new shop. rerollBase is reducible by vouchers/back later.
    var rerollBase = 5
    var rerollIncrease by mutableStateOf(0)                  // current_round.reroll_cost_increase
    val rerollCost: Int get() = (if (freeRerollThisShop) 0 else rerollBase) + rerollIncrease
    private var rerolls = 0                                  // global counter → reroll-stock RNG variety
    /** state_events.lua:347 — entering a fresh shop resets the per-shop reroll escalation. */
    fun resetRerollCost() { rerollIncrease = 0 }
    /** Reroll the shop stock (button='reroll_shop'). */
    fun reroll() {
        if (money < rerollCost || phase != Phase.SHOP) return
        money -= rerollCost
        rerollIncrease += 1                                  // +1 each reroll (calculate_reroll_cost)
        rerolls += 1
        val seed = blindIndex + rerolls * 7
        // reroll re-rolls the CARDS only; the voucher slot stays (Balatro keeps the voucher on reroll).
        shop = rollShop(seed, 3 + shopSlotsBonus); shopPlanets = rollPlanets(seed); shopTarots = rollTarots(seed)
        // ── per-reroll joker hooks (context.reroll_shop) ──────────────────────────────────────
        // starfruit: -0.2 Emult per reroll (config.emult_mod=0.2); self-destructs when emult ≤ 1.0
        // (epic.lua:2471-2519). j.x = emult accumulator; fire before joker removal check.
        val rerollSelfDestruct = ArrayList<Owned>()
        for (o in owned) if (o.fj.key == "j_cry_starfruit") {
            o.fj.x = maxOf(1.0, o.fj.x - 0.2)
            if (o.fj.x <= 1.00000001) rerollSelfDestruct.add(o)  // Lua: <= 1.00000001 float guard
        }
        if (rerollSelfDestruct.isNotEmpty()) {
            owned.removeAll(rerollSelfDestruct)
            Telemetry.event("REROLL_DESTROY", "n" to rerollSelfDestruct.size, "reason" to "starfruit")
        }
        // j_cry_crustulum: +4 chips per reroll (config.extra.chip_mod=4, exotic.lua:514).
        // j.chips accumulates; joker_main reads it when > 0.
        for (o in owned) if (o.fj.key == "j_cry_crustulum") o.fj.chips += 4.0
    }

    /** Commit a blind selection and start the round (button = 'select_blind' in Lua source). */
    fun selectBlind() { if (phase == Phase.BLIND_SELECT) startRound() }

    /** Skip the current Small/Big blind for its Tag (can't skip the Boss); advance to the next blind. */
    fun skipBlind() {
        if (phase != Phase.BLIND_SELECT || slot == 2) return
        tags.add(upcomingTag)
        blindIndex += 1
        boss = if (slot == 2) Boss.values().random(Random(blindIndex * 2654435761L + 1)) else null
        Telemetry.event("BLIND_SKIP", "tag" to tags.last().name)
    }

    /** Fire (and consume) every earned tag whose trigger matches (Tag:apply_to_run by config.type). */
    private fun applyTags(trigger: TagTrigger) {
        val firing = tags.filter { it.trigger == trigger }
        for (t in firing) when (t) {
            Tag.INVESTMENT -> money += 25
            Tag.JUGGLE -> handSize += 3
            Tag.D_SIX -> freeRerollThisShop = true
            Tag.COUPON -> couponThisShop = true
        }
        tags.removeAll(firing)
    }

    private var phaseBeforeInfo: Phase = Phase.ROUND

    /** Open the Run Info overlay (button='run_info' in hudButtons). Saves previous phase. */
    fun openRunInfo() { if (phase != Phase.OVER) { phaseBeforeInfo = phase; phase = Phase.RUN_INFO } }

    /** Close the Run Info overlay and return to the phase it was opened from. */
    fun closeRunInfo() { if (phase == Phase.RUN_INFO) phase = phaseBeforeInfo }

    /** config.button = "sort_hand_value" (byRank=true) or "sort_hand_suit" (byRank=false).
     *  Stable sort so cards with the same key stay in their original relative order. */
    fun sortHand(byRank: Boolean) {
        hand = if (byRank)
            hand.sortedWith(compareByDescending { it.rank })
        else
            hand.sortedWith(compareBy { it.suit.ordinal })
        selected = emptySet()
    }
}

@Composable
fun RunScreen(onClose: () -> Unit, startScreen: String? = null) {
    var runNo by remember { mutableStateOf(0) }
    key(runNo) { RunBody(onClose = onClose, onRestart = { runNo++ }, startScreen = startScreen) }
}

@Composable
private fun RunBody(onClose: () -> Unit, onRestart: () -> Unit, startScreen: String? = null) {
    val ctx = LocalContext.current
    val s = remember { RunState() }
    val saveFile = remember(ctx) { File(ctx.filesDir, SaveIo.FILE_NAME) }
    // Deep-link parity screenshots: --es screen blind|shop|play jumps to that phase (play auto-runs
    // a hand so the scoring cascade can be captured) on first composition. A NORMAL launch (no
    // startScreen) RESUMES the saved run if one exists (P4 SaveLoadThreadingModel).
    LaunchedEffect(Unit) {
        when (startScreen) {
            null -> withContext(Dispatchers.IO) { SaveIo.read(saveFile) }?.let { s.restore(RunSnapshot.decode(it)) }
            "blind" -> s.phase = Phase.BLIND_SELECT
            "shop" -> s.toShopForPreview()
            "play" -> { delay(700); repeat(5) { s.toggle(it) }; delay(400); s.play() }
            "eval" -> s.toEvalForPreview()
            "over" -> s.phase = Phase.OVER
            "win" -> s.phase = Phase.WIN
            "runinfo" -> s.openRunInfo()
            "repro" -> s.loadRepro()      // PROOF: freeze the exact reference-screenshot state for pixel-diff
            "repro-live" -> s.loadReproLive()  // live-cascade harness: bref_3 state, plays the Two Pair so scoring animates
        }
    }
    // Autosave the run at each inter-blind boundary (the snapshot captures the run-defining graph; the
    // encode runs on the main thread to read Compose state, the write goes to Dispatchers.IO). On game
    // over the save is deleted so the next launch starts fresh instead of resuming a dead run.
    // re-fire on the shop-mutating state too (money/consumables change on buys/rerolls/uses) so the
    // autosave captures the CURRENT shop stock, not the stock at shop entry (else resume re-offers bought cards).
    LaunchedEffect(s.phase, s.blindIndex, s.money, s.consumables.size) {
        if (startScreen != null) return@LaunchedEffect          // deep-link harnesses don't autosave
        when (s.phase) {
            Phase.SHOP, Phase.BLIND_SELECT -> { val json = s.snapshot().encode(); withContext(Dispatchers.IO) { SaveIo.write(saveFile, json) } }
            Phase.OVER -> withContext(Dispatchers.IO) { SaveIo.delete(saveFile) }
            else -> {}
        }
    }

    val allCards = remember { Suit.values().flatMap { su -> (2..14).map { PlayingCard(su, it) } } }
    val cells by produceState<Map<PlayingCard, ImageBitmap>>(emptyMap()) {
        value = withContext(Dispatchers.Default) { CardArt.cache(ctx, allCards) }
    }
    // The white card-stock base (c_base) drawn under every 8BitDeck rank/suit overlay.
    val cardBase by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.Default) { CardArt.base(ctx) }
    }
    // Red Deck card back for the deck stack (bottom-right).
    val cardBack by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.Default) { CardArt.back(ctx) }
    }
    val jokerCells by produceState<Map<String, ImageBitmap>>(emptyMap()) {
        value = withContext(Dispatchers.Default) { JokerArt.cache(ctx, CATALOG.map { it.key }) }
    }
    // Stake sprite (White Chip, stake 1 — always-active). chips.png 2x: 58×58px, pos={x=0,y=0}.
    val stakeBmp by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.Default) { StakeArt.whiteChip(ctx) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {                                 // full surface
        // Balatro's real paint-swirl felt (background.fs ported to AGSL), full-bleed behind
        // everything — the moving green felt, not a static gradient. Gradient fallback below API 33.
        BalatroFelt(Modifier.matchParentSize())
        // Scale + room transform: Balatro's love.resize (main.lua:1223). The room (ROOM_W×ROOM_H)
        // is fit into the surface by the aspect branch — width-constrained when the surface is squarer
        // than the room (aspect < 22/12.9), else height-constrained — which is exactly min(W/22, H/12.9).
        // The 22u-wide room is centred; ROOM.T.x/y are the room interior's screen-top-left origin (in
        // units), so card areas land at (ROOM.T + cardArea.T) regardless of device aspect.
        // REFSHOT1 was captured at 3840×2160: aspect = 1.778 > 22/12.9 = 1.705 → HEIGHT-constrained
        // (u = min(3840/22, 2160/12.9) = min(174.5, 167.4) = 167.4 px/unit). Repro uses the same
        // min(W/22, H/12.9) formula so pixel positions are apples-to-apples with the reference.
        val u = uiScaleFor(maxWidth.value, maxHeight.value)
        val roomTx = (maxWidth.value / u - ROOM_W) / 2f + ROOM_PADDING_W
        val roomTy = (maxHeight.value / u - ROOM_H) / 2f + ROOM_PADDING_H
        android.util.Log.d("BalatroLayout", "repro=${s.repro} maxW=${maxWidth.value} maxH=${maxHeight.value} u=$u roomTx=$roomTx roomTy=$roomTy uRepro=${maxWidth.value/ROOM_W} uLive=${uiScaleFor(maxWidth.value, maxHeight.value)}")
        CompositionLocalProvider(LocalUIScale provides u, LocalStaticUi provides s.repro) {
            // ROUND's play field is positioned at ABSOLUTE room coordinates over the full surface
            // (set_screen_positions), so it's a full-screen layer drawn UNDER the HUD overlay — not a
            // weight box to the right of the HUD. Card areas span the whole room; the HUD sits on top
            // of the left edge exactly as the game layers G.HUD over the room.
            // ROUND_EVAL keeps the play field mounted too, so the engine host + joker Moveables persist
            // and the end-of-round self-destruct dissolve burns on behind the cash-out panel (which the
            // when-block below overlays). RoundPlay gates its hand/played/action-bar to Phase.ROUND.
            if (s.phase == Phase.ROUND || s.phase == Phase.ROUND_EVAL) RoundPlay(s, cells, jokerCells, cardBase, cardBack, roomTx, roomTy)
            Box(
                // requiredHeight + centre: the 12.9u room centres in the surface — letterboxed when the
                // surface is taller than the room, cropped top/bottom when shorter (16:9). Plain .height()
                // is coerced to the surface height by the parent's max constraint, collapsing the crop.
                Modifier.fillMaxWidth().requiredHeight((ROOM_H * u).dp).align(Alignment.Center)
            ) {
                Row(Modifier.fillMaxSize()) {
                    // Balatro's left sidebar: the REAL create_UIBox_HUD tree at the room scale. Its
                    // dark panel is minh=30u — deliberately taller than the room — so it bleeds off
                    // top & bottom (the full-height sidebar look) with content vertically centred
                    // (align "cm"). Pinned centre-left, vertical overflow clipped — exactly how the
                    // game positions G.HUD ('cli'). No fit-to-height shrink.
                    Box(
                        // Balatro attaches G.HUD with align='cli', offset={x=-0.7} to the play area
                        // (ROOM_ATTACH left = roomTx): HUD left edge = roomTx - 0.7. (The earlier -0.765
                        // fudge over-corrected to a fuzzy panel-left read and left the HUD ~8-11px LEFT
                        // of the reference — confirmed by an empirical shift search.) Tracks the room
                        // horizontally so it's correct under pillarboxing too.
                        Modifier.fillMaxHeight().absoluteOffset(x = ((roomTx - 0.7f) * u).dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        HudColumn(s, Modifier, stakeBmp)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {                  // non-ROUND phase content
                        when (s.phase) {
                            Phase.ROUND -> {}                                  // rendered full-screen above
                            Phase.BLIND_SELECT -> BlindSelectScreen(s, stakeBmp)
                            Phase.SHOP -> ShopPhase(s, jokerCells, cardBase)
                            Phase.RUN_INFO -> RunInfoScreen(s)
                            Phase.ROUND_EVAL -> RoundEvalScreen(s)
                            Phase.OVER, Phase.WIN -> {}   // rendered as a full-screen overlay below (vanilla covers everything)
                            Phase.PACK_OPEN -> PackOpenScreen(s, jokerCells, cardBase, cells)
                        }
                    }
                }
            }
            // Game-over / win are full-screen overlays (G.OVERLAY_MENU) in vanilla — they cover the
            // HUD + board, not a play-area panel. Render the extracted tree full-screen over everything.
            if (s.phase == Phase.OVER) GameOverScreen(s, onRestart = onRestart, onMainMenu = onClose)
            if (s.phase == Phase.WIN)  WinScreen(s, onRestart = onRestart, onMainMenu = onClose)
        }
        // Minimal close affordance (NOT part of Balatro's HUD) — a small corner overlay so the
        // sidebar itself stays a faithful create_UIBox_HUD with no injected dev chrome.
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            BButton("✕", Balatro.Mult) { onClose() }
        }
    }
}

/** Balatro's left sidebar: blind token + score target, round score, Hands/Discards, money, Ante/Round. */
@Composable
private fun HudColumn(s: RunState, modifier: Modifier, stakeBmp: ImageBitmap? = null) {
    val ctx = LocalContext.current
    // Load blind sprite for the current ante's boss (Small row=0, Big row=1, Boss->row from atlas).
    // Re-runs when s.boss changes (next ante always changes the boss). Null while loading -> B spacer.
    val blindArt by produceState<Triple<ImageBitmap?, ImageBitmap?, ImageBitmap?>>(
        Triple(null, null, null), s.boss
    ) { value = withContext(Dispatchers.Default) { BlindArt.cacheRun(ctx, s.boss) } }

    val currentSlot = s.blindIndex % 3
    val blindBmp: ImageBitmap? = when (currentSlot) {
        0 -> blindArt.first; 1 -> blindArt.second; else -> blindArt.third
    }

    // Chip target scale animation — mirrors blind_chip_UI_scale in Balatro.
    // Springs from 0.001 → 0.5 when entering ROUND, resets to 0.001 on blind change.
    val chipTargetVisible = s.phase == Phase.ROUND
    val chipTargetScale by animateFloatAsState(
        targetValue = if (chipTargetVisible) 0.5f else 0.001f,
        animationSpec = if (chipTargetVisible)
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 400f)
        else
            tween(durationMillis = 0),   // instant reset on blind change
        label = "chipTargetScale"
    )

    // The HUD body is Balatro's ACTUAL create_UIBox_HUD tree, loaded from hud_tree.json
    // (tools/uiref/extract.sh) — NO hand-transcription. Descriptors bind to live RunState via
    // HudBind. It's laid out by the EXACT ported engine algorithm (UILayout.kt, verified byte-for-byte
    // against the real LÖVE engine) and rendered with absolute positioning — not the old approximate
    // Compose Row/Column interpreter. The blind token UIBox is NOT injected into the tree (that would
    // perturb the engine-exact layout); instead it's overlaid into the source-empty row_blind rect.
    val root = remember { HudSpec.root(ctx) }
    val u = LocalUIScale.current
    if (root != null) {
        // no blindContent → row_blind stays empty (pure tree); rebuild when the stake sprite loads.
        val ui = remember(root, stakeBmp) { HudSpec.build(root, HudBind(s, stakeBmp)) }
        Box(modifier) {
            RenderUIBoxAbsolute(ui, u, roomH = ROOM_H, blindOverlay = { bx, by, bw, bh ->
                // The blind token UIBox laid out by the SAME exact engine, centred in the row_blind slot.
                RenderUIBoxAt(hudBlind(s, blindBmp = blindBmp, stakeBmp = stakeBmp, chipTargetScale = chipTargetScale),
                    u, bx, by, bw, bh)
            })
        }
    }
}


/**
 * Port of create_UIBox_HUD's contents.round tree (UI_definitions.lua:1283).
 * Three rows separated by spacing R nodes: [Hands|Discards], [Money], [Ante|Round].
 *
 * Source structure notes:
 *  - All label T nodes have shadow=true (ui.lua text shadow pass).
 *  - DynaText scale=2*scale (2*0.4=0.8); money uses 2.2*scale=0.88.
 *  - Discards value row has an extra outer R{} wrapper vs Hands (source line 1298, kept faithful).
 *  - Money: outer C minh=1.15, inner C minh=1 (source lines 1307/1309); money row wraps in R{C{O}}.
 *  - Ante value row: O(ante) + T(" ", 0.3*sc) + T("/ ", 0.7*sc, shadow) + T("8", sc, shadow).
 *  - Round value row: O(round) DynaText, no extra suffix nodes.
 */

/**
 * Port of create_UIBox_HUD's contents.dollars_chips (UI_definitions.lua:1365).
 * Replaces the hand-wired round-score Panel in HudColumn.
 *
 * Structure: R(panel, r=0.1, emboss=0.05) → C(padding=0.1)
 *   → C(minw=1.3) [ R"Round" / R"Score" ]
 *   → C(minw=3.3, minh=0.7, r=0.1, dark) [ O stake_sprite | B | T chips_text ]
 *
 * G.GAME.chips_text (ref_table=G.GAME, ref_value='chips_text') → s.chipsText = fmtR(roundScore).
 * G.C.DYN_UI.BOSS_MAIN / .BOSS_DARK → Balatro.Panel / Balatro.FeltDark.
 */
private fun hudRound(s: RunState): UI {
    val tc = Balatro.Panel; val tc2 = Balatro.FeltDark; val light = Balatro.White
    val sp = 0.13f
    val sc = 0.4f       // local scale — G.scale local in create_UIBox_HUD

    // Stat box for Hands (canonical form, lines 1285-1292). value provider reads live RunState.
    fun stat(label: String, value: () -> String, color: Color): UI =
        C(Cfg(align = "cm", padding = 0.05f, minw = 1.45f, minh = 1f, colour = tc, emboss = 0.05f, r = 0.1f),
            R(Cfg(align = "cm", minh = 0.33f, maxw = 1.35f),
                T(Cfg(scale = 0.85f * sc, textColour = light, shadow = true), label)),
            R(Cfg(align = "cm", r = 0.1f, minw = 1.2f, colour = tc2),
                O(Cfg(align = "cm"), DynaT(seg(value, color, scale = 2f * sc), shadow = true))))

    // Discards has an extra outer R{} around the value row (source line 1298-1302 quirk).
    fun discardsBox(): UI =
        C(Cfg(align = "cm", padding = 0.05f, minw = 1.45f, colour = tc, emboss = 0.05f, r = 0.1f),
            R(Cfg(align = "cm", minh = 0.33f, maxw = 1.35f),
                T(Cfg(scale = 0.85f * sc, textColour = light, shadow = true), "Discards")),
            R(Cfg(align = "cm"),
                R(Cfg(align = "cm", r = 0.1f, minw = 1.2f, colour = tc2),
                    O(Cfg(align = "cm"), DynaT(seg({ "${s.discardsLeft}" }, Balatro.Mult, scale = 2f * sc), shadow = true)))))

    fun vSpace() = R(Cfg(minh = sp))
    fun hSpace() = C(Cfg(minw = sp))

    // Ante: 4 nodes in value row (source lines 1321-1326). win_ante=8 (vanilla constant).
    val anteBox = C(
        Cfg(align = "cm", padding = 0.05f, minw = 1.45f, minh = 1f, colour = tc, emboss = 0.05f, r = 0.1f),
        R(Cfg(align = "cm", minh = 0.33f, maxw = 1.35f),
            T(Cfg(scale = 0.85f * sc, textColour = light, shadow = true), "Ante")),
        R(Cfg(align = "cm", r = 0.1f, minw = 1.2f, colour = tc2),
            O(Cfg(align = "cm"), DynaT(seg({ "${s.ante}" }, Balatro.Orange, scale = 2f * sc), shadow = true)),
            T(Cfg(scale = 0.3f * sc, textColour = light), " "),
            T(Cfg(scale = 0.7f * sc, textColour = light, shadow = true), "/ "),
            T(Cfg(scale = sc, textColour = light, shadow = true), "8")))

    // Round: label minh=0.33 is on the T node (source line 1331), not the R.
    val roundBox = C(
        Cfg(align = "cm", padding = 0.05f, minw = 1.45f, minh = 1f, colour = tc, emboss = 0.05f, r = 0.1f),
        R(Cfg(align = "cm", maxw = 1.35f),
            T(Cfg(scale = 0.85f * sc, textColour = light, shadow = true, minh = 0.33f), "Round")),
        R(Cfg(align = "cm", r = 0.1f, minw = 1.2f, colour = tc2),
            O(Cfg(align = "cm"), DynaT(seg({ "${s.blindIndex + 1}" }, Balatro.Orange, scale = 2f * sc), shadow = true))))

    return C(Cfg(align = "cm"),
        R(Cfg(align = "cm"), stat("Hands", { "${s.handsLeft}" }, Balatro.Chips), hSpace(), discardsBox()),
        vSpace(),
        // Money row: outer C minh=1.15, wrapped in R, inner C minh=1 (source lines 1306-1314).
        R(Cfg(align = "cm"),
            C(Cfg(align = "cm", padding = 0.05f, minw = 1.45f * 2 + sp, minh = 1.15f, colour = tc, emboss = 0.05f, r = 0.1f),
                R(Cfg(align = "cm"),
                    C(Cfg(align = "cm", r = 0.1f, minw = 1.28f * 2 + sp, minh = 1f, colour = tc2),
                        O(Cfg(align = "cm"), DynaT(seg({ "\$${s.money}" }, Balatro.Money, scale = 2.2f * sc), shadow = true)))))),
        vSpace(),
        R(Cfg(align = "cm"), anteBox, hSpace(), roundBox))
}

/**
 * Port of create_UIBox_HUD's contents.dollars_chips (UI_definitions.lua:1365).
 * Left column: two stacked labels ("Round" / "Score"); right column: stake sprite +
 * B spacer + T(chips_text) which is the running round score (G.GAME.chips_text ref).
 *
 * G.C.DYN_UI.BOSS_MAIN = G.C.DYN_UI.MAIN = Panel; G.C.DYN_UI.BOSS_DARK = Panel.
 * Stake sprite O (0.5u×0.5u): White Chip from chips.png (stake 1); B spacer while loading.
 * chips_text T: scale=0.85 (not scaled by local scale var), shadow=true, id='chip_UI_count'.
 */
private fun hudDollarsChips(s: RunState, stakeBmp: ImageBitmap? = null): UI {
    val panel = Balatro.Panel      // G.C.DYN_UI.BOSS_MAIN
    val panelDark = Balatro.Panel  // G.C.DYN_UI.BOSS_DARK (same colour in globals.lua)
    val light = Balatro.White
    return R(
        Cfg(align = "cm", r = 0.1f, padding = 0f, colour = panel, emboss = 0.05f),
        C(Cfg(align = "cm", padding = 0.1f),
            C(Cfg(align = "cm", minw = 1.3f),
                R(Cfg(align = "cm", padding = 0f, maxw = 1.3f),
                    T(Cfg(scale = 0.42f, textColour = light, shadow = true), "Round")),
                R(Cfg(align = "cm", padding = 0f, maxw = 1.3f),
                    T(Cfg(scale = 0.42f, textColour = light, shadow = true), "score"))),
            C(Cfg(align = "cm", minw = 3.3f, minh = 0.7f, r = 0.1f, colour = panelDark),
                // Stake sprite O: White Chip (0.5u×0.5u) from chips.png; B spacer while loading.
                if (stakeBmp != null) O(Cfg(minw = 0.5f, minh = 0.5f, colour = Balatro.Chips), Spr(stakeBmp, 0.5f, 0.5f))
                else B(Cfg(minw = 0.5f, minh = 0.5f, colour = Balatro.Chips)),
                B(Cfg(minw = 0.1f, minh = 0.1f)),
                // chips_text T: G.GAME.chips_text = fmtR(roundScore); scale=0.85 (source line 1378)
                O(Cfg(align = "cm"),
                    DynaT(seg({ fmtR(s.roundScore) }, light, scale = 0.85f), shadow = true)))))
}

/**
 * Port of create_UIBox_HUD's contents.buttons (UI_definitions.lua:1383).
 * Two sidebar buttons: Run Info (RED/Mult) and Options (ORANGE), stacked in C(CLEAR, padding=0.2).
 *
 * "Run Info" = two T nodes: "Run" (scale=1.2*0.4=0.48) / "Info" (scale=0.4) in separate Rs.
 * "Options" = one T (scale=0.4) inside a C. shadow=true on T = text shadow pass.
 * onRunInfo/onOptions: stubs for now; actions deferred until panels exist.
 * Deferred: focus_args, func='set_button_pip', box-shadow on R containers.
 */
private fun hudButtons(onRunInfo: (() -> Unit)? = null, onOptions: (() -> Unit)? = null): UI {
    val sc = 0.4f
    val light = Balatro.White
    return C(
        Cfg(align = "cm", colour = Color.Transparent, padding = 0.2f),
        R(Cfg(align = "cm", minh = 1.75f, minw = 1.5f, padding = 0.05f, r = 0.1f,
              colour = Balatro.Mult, onClick = onRunInfo),
            R(Cfg(align = "cm", padding = 0f, maxw = 1.4f),
                T(Cfg(scale = 1.2f * sc, textColour = light, shadow = true), "Run")),
            R(Cfg(align = "cm", padding = 0f, maxw = 1.4f),
                T(Cfg(scale = sc, textColour = light, shadow = true), "Info"))),
        R(Cfg(align = "cm", minh = 1.75f, minw = 1.5f, padding = 0.05f, r = 0.1f,
              colour = Balatro.OrangeTrue, onClick = onOptions),   // G.C.ORANGE #FDA200, UI_definitions.lua:1573
            C(Cfg(align = "cm", maxw = 1.4f),
                T(Cfg(scale = sc, textColour = light, shadow = true), "Options"))))
}

/**
 * Port of create_UIBox_HUD_blind (UI_definitions.lua:1210): blind token + name strip + score-target
 * panel for the in-run HUD sidebar. Rendered through the UIBox interpreter — faithful by construction.
 *
 * blindBmp / stakeBmp: atlas cells loaded by a future BlindArt.cache / get_stake_sprite port.
 * Both are nullable; when null the O node is replaced by a B spacer of the same footprint so the
 * layout is identical to what the real engine produces (sizes are locked in the tree, not the art).
 *
 * Deferred: func='HUD_blind_visible' (always shown), func='HUD_blind_reward' (always shown),
 * debuff func callbacks (empty strings). Animate flags (rotate, float, y_offset) on DynaText Os.
 * blind_chip_UI_scale: implemented — chipTargetScale springs in via HudColumn animateFloatAsState.
 */
private fun hudBlind(s: RunState, blindBmp: ImageBitmap?, stakeBmp: ImageBitmap?, chipTargetScale: Float = 0.001f): UI {
    val black = Balatro.Panel   // G.C.BLACK = #374244 (ROOT + score inset)
    val light = Balatro.White   // G.C.UI.TEXT_LIGHT
    // DYN_UI colours tinted by the current blind (blind.lua change_colour). For a non-boss blind:
    //   MAIN = get_blind_main_colour = mix(BLUE|ORANGE, BLACK, 0.6);  DARK = mix(MAIN, BLACK, 0.4).
    // Small = blue (mix(BLUE,BLACK,0.6)=#1679B4), Big = amber, Boss = the boss colour.
    fun mix(a: Color, b: Color, p: Float) = Color(a.red*p + b.red*(1-p), a.green*p + b.green*(1-p), a.blue*p + b.blue*(1-p))
    // DYN_UI.MAIN/DARK per blind. The formula is mix(BLUE|ORANGE,BLACK,0.6) then mix(MAIN,BLACK,0.4),
    // but the on-screen panel is darker than that pure mix (Balatro composites a shadow under the HUD
    // that the headless geometry can't expose); for Small the measured values are #0068AD / #0E4360.
    val (mainCol, darkCol) = when {
        s.boss != null -> Color(0xFF8C3A3A).let { it to mix(it, black, 0.4f) }
        s.blindName.startsWith("Big") -> mix(Balatro.OrangeTrue, black, 0.6f).let { it to mix(it, black, 0.4f) }
        else -> Color(0xFF0068AD) to Color(0xFF0E4360)   // Small Blind (measured from the reference)
    }

    // G.UIT.O blind sprite (1.5u x 1.5u after change_dim). Fallback B spacer keeps layout identical.
    val blindO: UI = if (blindBmp != null) O(Cfg(), Spr(blindBmp, 1.5f, 1.5f)) else B(Cfg(wCfg = 1.5f, hCfg = 1.5f))
    // G.UIT.O stake sprite (config.w/h = 0.5, colour=BLUE). Same fallback.
    val stakeO: UI = if (stakeBmp != null) O(Cfg(wCfg = 0.5f, hCfg = 0.5f, colour = Balatro.Chips), Spr(stakeBmp, 0.5f, 0.5f))
                     else B(Cfg(wCfg = 0.5f, hCfg = 0.5f, colour = Balatro.Chips))

    // Boss-only debuff lines; non-boss blinds have an empty debuff R (no text). Omit unless boss.
    val bossDesc = s.boss?.desc
    val debuffBlock: UI? = if (!bossDesc.isNullOrBlank())
        R(Cfg(align = "cm"), R(Cfg(align = "cm", minh = 0.3f, maxw = 4.2f), T(Cfg(scale = 0.36f, textColour = light), bossDesc)))
    else null

    // create_UIBox_HUD_blind (UI_definitions.lua:1353), 1:1:
    //   ROOT(minw=4.5, colour=BLACK, emboss=0.05, padding=0.05)
    //     R(minh=0.7, colour=DYN_UI.MAIN)        — name strip (blue)
    //     R(minh=2.74, colour=DYN_UI.DARK)       — body (dark blue): blind sprite + score inset(BLACK)
    return R(Cfg(align = "cm", minw = 4.5f, r = 0.1f, colour = black, emboss = 0.05f, padding = 0.05f),
        R(Cfg(align = "cm", minh = 0.7f, r = 0.1f, emboss = 0.05f, colour = mainCol),
            C(Cfg(align = "cm", minw = 3f),
                O(Cfg(), DynaT(seg({ s.blindName }, light, scale = 0.64f), shadow = true)))),
        Ro(Cfg(align = "cm", minh = 2.74f, r = 0.1f, colour = darkCol), listOfNotNull(
            debuffBlock,
            R(Cfg(align = "cm", padding = 0.15f),
                blindO,
                C(Cfg(align = "cm", r = 0.1f, padding = 0.05f, emboss = 0.05f, minw = 2.9f, colour = black),
                    R(Cfg(align = "cm", maxw = 2.8f),
                        T(Cfg(scale = 0.3f, textColour = light, shadow = true), "Score at least")),
                    R(Cfg(align = "cm", minh = 0.6f),
                        stakeO,
                        B(Cfg(wCfg = 0.1f, hCfg = 0.1f)),
                        T(Cfg(scale = chipTargetScale, textColour = Balatro.Mult, shadow = true), s.chipText)),
                    R(Cfg(align = "cm", minh = 0.45f, maxw = 2.8f),
                        T(Cfg(scale = 0.3f, textColour = light), "Reward: "),
                        // reward shown as repeated '$' (one per dollar), as in the real game ("$$$" for $3)
                        O(Cfg(), DynaT(seg({ "$".repeat(s.dollarsToBeEarned.coerceIn(0, 8)) }, Balatro.Money, scale = 0.45f), shadow = true)))))
        ))
    )
}

/**
 * Port of create_UIBox_HUD's contents.hand (UI_definitions.lua:1340): the hand-name +
 * Chips × Mult readout rendered through the UIBox interpreter.
 *
 * Source tree:
 *   R(darken(BLACK,0.1), r=0.1, emboss=0.05, padding=0.03)   — dark inset
 *     C(cm)
 *       R(cm, minh=1.1)
 *         O DynaText(handname_text, TEXT_LIGHT, scale=0.56)   — hand name
 *         O DynaText(chip_total_text, TEXT_LIGHT, scale=0.56) — total chips (blank until scored)
 *         T(hand_level, scale=0.4, TEXT_LIGHT, shadow=true)   — "Lv N"
 *       R(cm, minh=1, padding=0.1)
 *         C(cr, minw=2, minh=1, r=0.1, colour=UI_CHIPS=Chips, emboss=0.05)  — chip box
 *           O DynaText(chip_text, TEXT_LIGHT, font=en-us, scale=0.92, shadow, float)
 *           B(0.1u×0.1u)
 *         C(cm)  "X"  TEXT_LIGHT shadow scale=0.8
 *         C(cl, minw=2, minh=1, r=0.1, colour=UI_MULT=Mult, emboss=0.05)   — mult box
 *           B(0.1u×0.1u)
 *           O DynaText(mult_text, TEXT_LIGHT, font=en-us, scale=0.92, shadow, float)
 *
 * The flame_handler Moveable O nodes (w=0,h=0) are zero-size effects; omitted (no visual area).
 * darken(G.C.BLACK, 0.1) → Panel darkened 10% = Color(0xFF2F3A3B).
 * G.C.UI_CHIPS = G.C.BLUE = Balatro.Chips; G.C.UI_MULT = G.C.RED = Balatro.Mult.
 * func='hand_text_UI_set'/'hand_chip_UI_set'/'hand_mult_UI_set' — always show (deferred).
 * Blank strings when idle (no hand scored yet) — matches Balatro's init state.
 */
private fun hudHand(s: RunState): UI {
    val scale = 0.4f
    val panelDark = Color(0xFF2F3A3B)   // darken(G.C.BLACK, 0.1) — slightly darker than Panel
    val light = Balatro.White            // G.C.UI.TEXT_LIGHT

    val levelStr = if (s.currentHandLevel > 0) "Lv${s.currentHandLevel}" else ""

    return R(
        Cfg(align = "cm", colour = panelDark, r = 0.1f, emboss = 0.05f, padding = 0.03f),
        C(Cfg(align = "cm"),
            // top row: hand name + chip total text + level badge
            R(Cfg(align = "cm", minh = 1.1f),
                // hand name DynaText — func='hand_text_UI_set' deferred; always show
                O(Cfg(),
                    DynaT(seg({ s.handNameText }, light, scale = scale * 1.4f), shadow = true)),
                // chip_total_text DynaText — shown once hand is scored
                O(Cfg(),
                    DynaT(seg({ s.chipTotalText }, light, scale = scale * 1.4f), shadow = true)),
                // hand level T — "Lv N" when a hand result is present
                T(Cfg(scale = scale, textColour = light, shadow = true), levelStr)
            ),
            // bottom row: [chips box] × [mult box]
            R(Cfg(align = "cm", minh = 1f, padding = 0.1f),
                // chips box — C(cr), chip_text = live cascade counter (chipText2)
                C(Cfg(align = "cr", minw = 2f, minh = 1f, r = 0.1f, colour = Balatro.Chips, emboss = 0.05f),
                    O(Cfg(),
                        DynaT(seg({ s.chipText2 }, light, scale = scale * 2.3f), shadow = true)),
                    B(Cfg(minw = 0.1f, minh = 0.1f))),
                // × separator
                C(Cfg(align = "cm"),
                    T(Cfg(scale = scale * 2f, textColour = Balatro.Mult, shadow = true), "X")),
                // mult box — C(cl) so mult text is left-aligned
                C(Cfg(align = "cl", minw = 2f, minh = 1f, r = 0.1f, colour = Balatro.Mult, emboss = 0.05f),
                    B(Cfg(minw = 0.1f, minh = 0.1f)),
                    O(Cfg(),
                        DynaT(seg({ s.multText }, light, scale = scale * 2.3f), shadow = true)))
            )
        )
    )
}

/**
 * Port of create_UIBox_buttons (UI_definitions.lua): the action bar that lives below the hand.
 * Three children flow horizontally — Play Hand (left), sort cluster (centre), Discard (right).
 *
 * Guards: config.func='can_play' / 'can_discard' + config.one_press are mapped to onClick=null
 * when the action is unavailable, which makes cfg() skip the clickable modifier entirely.
 * config.button='sort_hand_value'/'sort_hand_suit' sort the hand in-place (stable sort by rank/suit).
 *
 * play_button_pos: Balatro swaps play/discard when G.SETTINGS.play_button_pos==1; we default to
 * pos=0 (play left, discard right) — no settings screen yet.
 */
private fun buttonsRow(s: RunState, cells: Map<*, *>): UI {
    val ts = 0.45f    // text_scale
    val bh = 1.3f     // button_height (minh on discard only)

    val canPlay = !s.scoring && s.selected.isNotEmpty() && cells.isNotEmpty()
    val canDiscard = !s.scoring && s.selected.isNotEmpty() && s.discardsLeft > 0

    // Source UI_definitions.lua:1052-1058: play button has two R rows — label + sub-label.
    val playButton = C(
        Cfg(
            align   = "tm",
            minw    = 2.5f,
            padding = 0.3f,
            r       = 0.1f,
            colour  = if (canPlay) Balatro.Chips else Balatro.Grey,
            onClick = if (canPlay) ({ s.play() }) else null,
        ),
        R(Cfg(align = "bcm", padding = 0f),
            T(Cfg(scale = ts, textColour = Balatro.White), "Play Hand")),
        R(Cfg(align = "bcm", padding = 0f),
            T(Cfg(scale = ts * 0.65f, textColour = Balatro.White), "")),  // SMODS.hand_limit_strings.play stub
    )

    // Source UI_definitions.lua:1061-1067: discard button has two R rows — label + sub-label.
    val discardButton = C(
        Cfg(
            align   = "tm",
            minw    = 2.5f,
            minh    = bh,
            padding = 0.3f,
            r       = 0.1f,
            colour  = if (canDiscard) Balatro.Mult else Balatro.Grey,
            onClick = if (canDiscard) ({ s.discard() }) else null,
        ),
        R(Cfg(align = "cm", padding = 0f),
            T(Cfg(scale = ts, textColour = Balatro.White), "Discard")),
        R(Cfg(align = "cm", padding = 0f),
            T(Cfg(scale = ts * 0.65f, textColour = Balatro.White), "")),  // SMODS.hand_limit_strings.discard stub
    )

    // Sort cluster: G.C.UI.TRANSPARENT_DARK fill + outline=1.5 in mix(WHITE,JOKER_GREY,0.7)=#D2D8E2
    // Source: UI_definitions.lua:1074. Sort buttons use G.C.ORANGE=#FDA200 (not G.C.FILTER).
    val sortCluster = C(
        Cfg(align = "cm", padding = 0.1f, r = 0.1f, colour = Color(0x22222222),
            outline = 1.5f, outlineColour = Color(0xFFD2D8E2)),  // mix_colours(WHITE, JOKER_GREY, 0.7)
        R(Cfg(align = "cm", padding = 0f),
            R(Cfg(align = "cm", padding = 0f),
                T(Cfg(scale = ts * 0.8f, textColour = Balatro.White), "Sort Hand")),
            R(Cfg(align = "cm", padding = 0.1f),
                C(Cfg(align = "cm", minh = 0.7f, minw = 0.9f, padding = 0.1f, r = 0.1f,
                    colour = Balatro.OrangeTrue, onClick = { s.sortHand(byRank = true) }),
                    T(Cfg(scale = ts * 0.7f, textColour = Balatro.White), "Rank")),
                C(Cfg(align = "cm", minh = 0.7f, minw = 0.9f, padding = 0.1f, r = 0.1f,
                    colour = Balatro.OrangeTrue, onClick = { s.sortHand(byRank = false) }),
                    T(Cfg(scale = ts * 0.7f, textColour = Balatro.White), "Suit")),
            ),
        ),
    )

    return R(
        Cfg(align = "cm", minw = 1f, minh = 0.3f, padding = 0.15f, r = 0.1f, colour = Color.Transparent),
        playButton,
        sortCluster,
        discardButton,
    )
}

/** The play area: jokers across the top, the hand-name + chips X mult readout in the centre,
 *  the fanned hand + deck along the bottom. Landscape, like the real game. */
/**
 * One playing card: the white c_base card stock with the 8BitDeck rank/suit overlay on top. Both
 * are nearest-neighbour (FilterQuality.None) like LÖVE — pixel art is never blurred — and FillBounds
 * so base and overlay co-register exactly in the card's slot. 8BitDeck is a TRANSPARENT overlay, so
 * without the base a card is just floating pips on the felt (the bug this fixes). `badges` overlays
 * enhancement/seal markers.
 */
@Composable
private fun CardFace(
    card: PlayingCard,
    face: ImageBitmap?,
    base: ImageBitmap?,
    modifier: Modifier = Modifier,
    shadowHeight: Float = 0.1f,         // card.lua: 0.1 normal, 0.35 highlighted-in-play / dragged; 0 = no shadow
    badges: @Composable BoxScope.() -> Unit = {},
) {
    val u = LocalUIScale.current
    Box(modifier) {
        // Balatro drop shadow (sprite.lua draw_shader + dissolve.fs): the card silhouette in BLACK at
        // 0.3 alpha, offset by -shadow_parallax*height (parallax.y=-1.5 → +1.5*h units DOWN) and scaled
        // (1-0.2*h). The base (white card stock) is the silhouette; tint it black for the shadow pass.
        if (base != null && shadowHeight > 0f) {
            val sc = 1f - 0.2f * shadowHeight
            Image(base, null,
                Modifier.matchParentSize()
                    .graphicsLayer { scaleX = sc; scaleY = sc; translationY = 1.5f * shadowHeight * u * density; alpha = 0.3f },
                contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None,
                colorFilter = ColorFilter.tint(Color.Black))
        }
        base?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None) }
        face?.let { Image(it, card.label, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None) }
        badges()
    }
}

@Composable
private fun RoundPlay(s: RunState, cells: Map<PlayingCard, ImageBitmap>, jokerCells: Map<String, ImageBitmap>, cardBase: ImageBitmap? = null, cardBack: ImageBitmap? = null, roomTx: Float = 1f, roomTy: Float = 0.4375f) {
    // Play field laid out at ABSOLUTE room coordinates — set_screen_positions (common_events.lua),
    // resolved to screen-top-left room units by the playfield-coords analysis (LÖVE oracle +
    // reference measurement). Card areas (jokers/play/hand/deck/consumeables) are placed via
    // absoluteOffset(xu·u, yu·u); the coords already bake in the room origin + vertical crop, so no
    // extra term is needed. Card = G.CARD_W/H = 2.04878 × 2.75122 units.
    val u = LocalUIScale.current
    val cardW = (PF.CARD_W * u).dp
    val cardH = (PF.CARD_H * u).dp
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val cardWpx = with(dens) { cardW.toPx() }; val cardHpx = with(dens) { cardH.toPx() }
    // DEBUG: log u and card pixel size once
    androidx.compose.runtime.LaunchedEffect(Unit) {
        android.util.Log.d("BalatroDebug", "u=$u cardWpx=$cardWpx cardHpx=$cardHpx density=${dens.density} roomTx=$roomTx roomTy=$roomTy")
    }
    // edition shaders are AGSL (API 33+); only on the live path (the static repro frame has no editions).
    val foilOn = !s.repro && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val countSp = (0.5f * u * FONT_RATIO).sp
    val badgeSp = (0.33f * u * FONT_RATIO).sp
    // card areas are in the ROOM_ATTACH frame (set_screen_positions); add the room origin (ROOM.T)
    // so they land correctly at the device's scale/letterboxing.
    fun off(xu: Float, yu: Float) = Modifier.absoluteOffset(((roomTx + xu) * u).dp, ((roomTy + yu) * u).dp)

    // ── Engine-driven play field (P0-T8). One EngineHost owns the clock + event queue + the CardArea
    // Moveables (T from Room.set_screen_positions); the per-frame loop below ticks them (advance clock
    // → drain events → sweep moveables). Area ORIGINS now come from each area's VT — which equals T
    // (the derived Room origin) at rest, so the play field is pixel-identical to the old static PF.*
    // placement, but is now engine-driven and can spring once something moves an area's T.
    val host = remember { EngineHost() }
    val frame = remember { mutableStateOf(0L) }    // bumped each engine tick → redraw cards at their VT
    // Per-card engine Moveable, by card identity — persists as a card moves hand→play (the transfer
    // carries its VT). IdentityHashMap so value-equal duplicates (two 10s) stay distinct.
    val cardMv = remember { java.util.IdentityHashMap<PlayingCard, Moveable>() }
    // Per-joker engine Moveable, by joker identity (mirrors cardMv) — so a SPECIFIC self-destruct
    // joker can dissolve and be pruned, rather than setCardCount dropping the last by count.
    val jokerMv = remember { java.util.IdentityHashMap<Owned, Moveable>() }
    // Staggered deal: each newly-dealt card's clock time to start flying in from the deck; held at the
    // deck until then so cards arrive one-by-one (~0.07s apart). dealClock[0] is the next reveal slot.
    val dealAt = remember { java.util.IdentityHashMap<PlayingCard, Double>() }
    val dealClock = remember { doubleArrayOf(0.0) }
    val jokerMatDone = remember { booleanArrayOf(false) }   // one-shot: jokers materialized IN (demo)
    val debugFrameCount = remember { intArrayOf(0) }
    LaunchedEffect(host) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1.0 / 60.0 else ((now - last) / 1e9).coerceIn(1e-4, 0.05)
                last = now
                debugFrameCount[0]++
                // The engine always RUNS (cards spring to rest); repro only freezes the idle wobble
                // (reducedMotion) — not movement — so cards still settle to their resting T for the
                // parity screenshot. (Pausing move() would strand cards at their spawn point.)
                val frozen = s.repro
                // MainUpdateLoop body (game.lua Game:update order): advance clock → align card areas
                // (CardArea:update) → drain events → sweep every Moveable's move → flush removals.
                host.clock.advance(dt, reducedMotion = frozen)
                // IDENTITY-TRACKED jokers (like cardMv): rebuild host.jokers.cards from s.owned each
                // frame, REUSING each joker's Moveable, so a dissolving joker keeps its Moveable (and
                // dissolve value) and survives the burn until onGone prunes it. newCard() spawns at the
                // area origin exactly like the old setCardCount, so the resting render is unchanged.
                host.jokers.cards.clear(); for (o in s.owned) host.jokers.cards.add(jokerMv.getOrPut(o) { host.jokers.newCard() })
                if (jokerMv.size > s.owned.size) {                  // prune Moveables for removed jokers
                    val keep = java.util.IdentityHashMap<Owned, Boolean>(); for (o in s.owned) keep[o] = true
                    val it2 = jokerMv.entries.iterator()
                    while (it2.hasNext()) { val e = it2.next(); if (!keep.containsKey(e.key)) { e.value.remove(); it2.remove() } }
                }
                // one-shot: materialize the jokers IN (start_materialize) — the create half of the pair.
                if (s.materializeJokers && !jokerMatDone[0] && host.jokers.cards.size == s.owned.size && s.owned.isNotEmpty()) {
                    host.jokers.cards.forEach { host.startMaterialize(it, now = host.clock.real) }
                    jokerMatDone[0] = true
                }
                host.jokers.alignCards(host.clock, reducedMotion = frozen)
                // HAND slide: the area Y is state-dependent (oracle: 6.986 selecting → 8.886 scoring).
                // Setting host.hand.T.y on the transition makes each card's align target jump, so the
                // card VT springs (slides, with overshoot) into the scoring position — the motion bref_3
                // froze. Cards stay on CardArea; align_cards sets the fan/arc/lift each frame.
                host.hand.T.y = (if (s.scoring) Room.handScoringY else Room.handSelectingY)
                // IDENTITY-TRACKED cards: every PlayingCard keeps its own Moveable (cardMv), born in
                // the hand. host.hand/host.play.cards are rebuilt each frame from the game lists,
                // REUSING those Moveables — so a played card (now in s.scoreCards, gone from s.hand)
                // carries its exact hand VT into the play area: real hand→play transfer, the fly-in.
                // newly-dealt hand cards are born at the DECK and fly into the hand (deck→hand deal);
                // played cards born at the hand (hand→play). Cards already in cardMv keep their VT.
                host.hand.cards.clear(); for (cd in s.hand) host.hand.cards.add(cardMv.getOrPut(cd) {
                    if (dealClock[0] < host.clock.real) dealClock[0] = host.clock.real   // new batch starts now
                    dealAt[cd] = dealClock[0]; dealClock[0] += 0.07                       // stagger 0.07s/card
                    host.hand.newCard(host.deck.T.x, host.deck.T.y)
                })
                host.play.cards.clear(); for (cd in s.scoreCards) host.play.cards.add(cardMv.getOrPut(cd) { host.play.newCard(host.hand.T.x, host.hand.T.y) })
                if (cardMv.size > s.hand.size + s.scoreCards.size) {        // prune Moveables for gone cards
                    val keep = java.util.IdentityHashMap<PlayingCard, Boolean>()
                    for (cd in s.hand) keep[cd] = true; for (cd in s.scoreCards) keep[cd] = true
                    val it2 = cardMv.entries.iterator()
                    while (it2.hasNext()) { val e = it2.next(); val k = e.key; if (!keep.containsKey(k)) { e.value.remove(); it2.remove(); dealAt.remove(k) } }
                }
                host.hand.highlighted.clear(); host.hand.highlighted.addAll(s.selected)
                host.hand.alignCards(host.clock, reducedMotion = frozen, tempLimit = 8)
                host.play.alignCards(host.clock, reducedMotion = frozen, tempLimit = maxOf(s.scoreCards.size, 1))
                // staggered deal: hold each card at the deck until its reveal time so they fly in
                // one-by-one. (skipped in repro — the static frame uses SpringHand, not these cards.)
                if (!frozen) host.hand.cards.forEachIndexed { i, m ->
                    val at = s.hand.getOrNull(i)?.let { dealAt[it] }
                    if (at != null && host.clock.real < at) { m.T.x = host.deck.T.x; m.T.y = host.deck.T.y }
                }
                host.events.update(dt)
                for (m in host.scene.moveables) m.move(host.clock)
                host.scene.flushRemovals()
                // DEBUG: log hand card T.r and VT.r every 300 frames (~5s)
                if (debugFrameCount[0] % 300 == 0) {
                    android.util.Log.d("BalatroDebug", "frame=${debugFrameCount[0]} u=$u cardWpx=${cardWpx.toInt()} cardHpx=${cardHpx.toInt()} roomTx=$roomTx roomTy=$roomTy repro=${s.repro}")
                    host.hand.cards.forEachIndexed { i, m ->
                        val pxLeft = ((roomTx + m.VT.x.toFloat()) * u * dens.density)
                        val pxTop = ((roomTy + m.VT.y.toFloat()) * u * dens.density)
                        val pxBot = pxTop + cardHpx
                        val pxRight = pxLeft + cardWpx
                        android.util.Log.d("BalatroDebug", "hand[$i] VT.x=${m.VT.x.let { String.format("%.3f", it) }} VT.y=${m.VT.y.let { String.format("%.3f", it) }} VT.r=${m.VT.r.let { String.format("%.4f", it) }} VT.scale=${m.VT.scale.let { String.format("%.4f", it) }} pxLeft=${pxLeft.toInt()} pxTop=${pxTop.toInt()} pxRight=${pxRight.toInt()} pxBot=${pxBot.toInt()}")
                    }
                }
                frame.value = now
            }
        }
    }
    val jokersX = host.jokers.VT.x.toFloat(); val jokersY = host.jokers.VT.y.toFloat()
    val handX = host.hand.VT.x.toFloat();     val handY = host.hand.VT.y.toFloat()
    val playX = host.play.VT.x.toFloat()
    val deckX = host.deck.VT.x.toFloat();     val deckY = host.deck.VT.y.toFloat()
    val consumX = host.consumeables.VT.x.toFloat()

    // ── Scoring cascade (P4): drive the chip/mult readout + card pops off the engine EventManager
    // instead of hard-coded coroutine delays. The steps are BLOCKING `after` events on the host's
    // 'base' queue, so they fire strictly in sequence (each delay starts when the previous completes)
    // exactly like Balatro chains its scoring events — and the one host loop drains them. Step 0 fires
    // immediately; the same 140ms/300ms gaps and the 450ms pre-commit pause are preserved.
    // (repro freezes the scored frame — no cascade/commit.)
    LaunchedEffect(s.scoring) {
        if (s.scoring && !s.repro) {
            s.scoreStep(0)                                          // base readout (the starting value)
            for (i in 1 until s.lastSteps.size) {
                val step = s.lastSteps[i]
                host.events.addEvent(Event(trigger = "after", delay = if (i == 1) 0.14 else 0.30, func = {
                    s.popIndex = i - 1                              // pop the scored card
                    // ease_chips/ease_mult (common_events.lua): the readout COUNTS UP to this step's
                    // value over 0.3s instead of jumping. Non-blocking so the cascade keeps marching;
                    // chips floor to integers (ease_chips uses math.floor), mult stays fractional.
                    host.events.addEvent(Event(trigger = "ease", delay = 0.3, blocking = false,
                        ease = EaseSpec(get = { s.displayChips }, set = { s.displayChips = it }, easeTo = step.chips),
                        easeFunc = { floor(it) }))
                    host.events.addEvent(Event(trigger = "ease", delay = 0.3, blocking = false,
                        ease = EaseSpec(get = { s.displayMult }, set = { s.displayMult = it }, easeTo = step.mult)))
                    true
                }))
            }
            host.events.addEvent(Event(trigger = "after", delay = 0.30, func = { true }))          // trailing post-step gap
            // post-tally: played GLASS cards shatter (Card:shatter, 1-in-4) — they DISSOLVE away (fast
            // white burn) rather than being discarded. Roll per glass card; if any burns, hold the
            // commit/refill until the dissolve finishes (0.55×0.7s) so it doesn't pop out mid-burn.
            host.events.addEvent(Event(trigger = "after", delay = 0.45, func = {
                var burning = false
                s.scoreCards.forEachIndexed { i, card ->
                    if (card.enhancement == Enhancement.GLASS && s.rollGlassBreak()) {
                        host.play.cards.getOrNull(i)?.let { host.startDissolve(it, shatter = true, now = host.clock.real) }
                        s.shatterCard(card)   // game state: the shattered card is gone from the run's deck, not just the screen
                        burning = true
                    }
                }
                host.events.addEvent(Event(trigger = "after", delay = if (burning) 0.55 * 0.7 else 0.0, func = {
                    if (s.scoreBank()) {                            // round WON → end-of-round evaluation
                        // self-destruct jokers (SELF_DESTRUCT_KEYS, e.g. Broken Home) DISSOLVE on the
                        // board (fiery start_dissolve), then are pruned from owned (onGone). Faithful:
                        // state_events.lua runs this in the end_of_round calculate, before ROUND_EVAL.
                        val doomed = s.owned.filter { it.fj.key in SELF_DESTRUCT_KEYS } +
                            (if (s.demoSelfDestruct) listOfNotNull(s.owned.lastOrNull()) else emptyList())
                        doomed.distinct().forEach { o ->
                            jokerMv[o]?.let { mv -> host.startDissolve(mv, now = host.clock.real) { s.owned.remove(o) } }
                        }
                        // RoundPlay stays mounted through ROUND_EVAL, so the burn continues behind the
                        // cash-out panel (the dump's eval state appears ~0.3s in, mid-dissolve).
                        s.enterRoundEval()
                    }
                    true
                }))
                true
            }))
        }
    }
    // juice the played card the cascade is popping (live; repro uses ScoredCardsRow's own juice).
    LaunchedEffect(s.popIndex) {
        if (!s.repro) host.play.cards.getOrNull(s.popIndex)?.juiceUp(amount = 0.4, now = host.clock.real)
    }

    Box(Modifier.fillMaxSize()) {
        // ── JOKERS (G.jokers): each joker is an engine Moveable (a Card IS a Moveable) owned by the
        // host's joker CardArea. align_cards (cardarea.lua:565) sets each card's target T (spread +
        // fan + idle wobble) in the loop above; move() springs VT toward it; here we just draw at VT.
        // This retires the old ad-hoc BalatroFloat sine — jokers now lean/settle through the engine.
        // (repro freezes the wobble, so VT==T==the spread slot → pixel-identical to the static render.)
        frame.value.let {}    // subscribe to the engine tick so the cards redraw at their new VT each frame
        s.owned.forEachIndexed { i, o ->
            val m = host.jokers.cards.getOrNull(i) ?: return@forEachIndexed
            val rDeg = -(m.VT.r * 57.2958).toFloat()
            Box(off(m.VT.x.toFloat(), m.VT.y.toFloat())) {
                Box(Modifier.graphicsLayer { rotationZ = rDeg }) {
                    jokerCells[o.offer.key]?.let {
                        // drop shadow: joker silhouette black @0.3a, +0.15u down, scaled 0.98 (h=0.1)
                        Image(it, null, Modifier.size(cardW, cardH)
                            .graphicsLayer { scaleX = 0.98f; scaleY = 0.98f; translationY = 0.15f * u * density; alpha = 0.3f },
                            contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
                            colorFilter = ColorFilter.tint(Color.Black))
                        // base art — burned through dissolve.fs while the joker is materializing IN
                        // (dissolve 1→0, green edge) or dissolving OUT (e.g. self-destruct, fiery).
                        val diss = m.dissolve.toFloat()
                        Image(it, o.offer.name, Modifier.size(cardW, cardH).graphicsLayer {
                            renderEffect = when {
                                // burning (dissolve/shatter/materialize) takes precedence over the edition
                                foilOn && diss > 0f -> dissolveRenderEffect(
                                    diss, host.clock.real.toFloat(), cardWpx, cardHpx, glass = m.shattered, materialize = m.materializing)
                                // NEGATIVE transforms the base art itself (not an additive overlay)
                                foilOn && o.offer.edition == Edition.NEGATIVE -> negativeBaseRenderEffect(cardWpx, cardHpx)
                                else -> null
                            }
                        }, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                        // EDITION (foil/holo/poly → AGSL): overlay the shimmer over the base art,
                        // animated by the engine clock. (frame.value above forces the per-tick redraw.)
                        // Suppressed mid-burn — the card isn't whole yet, so the shimmer would look wrong.
                        val edEffect = if (foilOn && o.offer.edition != Edition.NONE && diss <= 0f)
                            editionRenderEffect(o.offer.edition.tag, cardWpx, cardHpx, host.clock.real.toFloat()) else null
                        if (edEffect != null) {
                            Image(it, null, Modifier.size(cardW, cardH).graphicsLayer { renderEffect = edEffect },
                                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                        }
                    } ?: Box(Modifier.size(cardW, cardH).clip(RoundedCornerShape(4.dp)).background(Balatro.FeltDark))
                    // CRIMSON_HEART: semi-transparent dark wash + red "×" badge marks the disabled joker.
                    // Rendered inside the rotation layer so the overlay tilts with the card. The wash
                    // dims the joker art; the badge at TopCenter is the clearest affordance at this scale.
                    if (s.boss == Boss.CRIMSON_HEART && o.fj === s.crimsonHeartDisabled) {
                        Box(Modifier.size(cardW, cardH).background(Color.Black.copy(alpha = 0.55f)))
                        Box(Modifier.size(cardW, cardH), contentAlignment = Alignment.TopCenter) {
                            BTxt("×", Balatro.Mult, badgeSp, Modifier.background(Balatro.Panel).padding(horizontal = 2.dp))
                        }
                    }
                }
            }
        }
        // Slot counts at the BOTTOM-left of each card area (jokers N/5, consumables 0/2).
        Box(off(jokersX, jokersY + PF.CARD_H + 0.05f)) {
            BTxt("${s.owned.size}/${minOf(s.maxJokers, s.jokerSlots)}", Balatro.White, countSp, Modifier.padding(start = (0.05f * u).dp))
        }
        // ── CONSUMABLES (G.consumeables): held tarots/planets/spectrals, drawn in the consumable area.
        // Tarots enter aim-mode on tap (pendingTarot set); planets/spectrals apply immediately.
        if (s.consumables.isNotEmpty()) {
            Box(off(consumX, jokersY)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    s.consumables.forEachIndexed { i, c ->
                        val label = when (c) { is Consumable.TarotC -> c.t.name; is Consumable.PlanetC -> c.planet.display; is Consumable.SpectralC -> c.s.display }
                        val accent = when (c) { is Consumable.TarotC -> Balatro.Purple; is Consumable.PlanetC -> Balatro.Chips; is Consumable.SpectralC -> Balatro.Mult }
                        val isAiming = c is Consumable.TarotC && s.pendingTarot === c.t
                        Box(Modifier.size(cardW, cardH)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isAiming) Balatro.Purple.copy(alpha = 0.7f) else Balatro.Panel)
                            .border(1.dp, if (isAiming) Balatro.Purple else Balatro.PanelLight, RoundedCornerShape(4.dp))
                            .clickable(enabled = s.phase == Phase.ROUND && !s.scoring) {
                                if (c is Consumable.TarotC) s.aimTarot(c.t) else s.useConsumable(i)
                            },
                            contentAlignment = Alignment.Center) {
                            cardBase?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None) }
                            BTxt(label, accent, countSp, Modifier.padding(horizontal = 2.dp))
                        }
                    }
                }
            }
        }
        Box(off(consumX, jokersY + PF.CARD_H + 0.05f)) {
            BTxt("${s.consumables.size}/${s.consumableSlots}", Balatro.White, countSp)
        }
        // ── PLAYED (G.play). STATIC repro: ScoredCardsRow, frozen at PLAY_SCORING_Y (bref_3's lifted
        // frame). LIVE: the played cards are engine Moveables on host.play — they fly up from the hand
        // into the play area and juice as the cascade pops each (juiceUp wired below). Drawn at VT.
        if (s.scoring && s.repro) {
            Box(off(playX, PF.PLAY_SCORING_Y).size((PF.PLAY_W * u).dp, cardH), contentAlignment = Alignment.TopCenter) {
                ScoredCardsRow(s, cells, cardBase)
            }
        } else if (s.scoring) {
            s.scoreCards.forEachIndexed { i, card ->
                val m = host.play.cards.getOrNull(i) ?: return@forEachIndexed
                val diss = m.dissolve.toFloat()      // >0 once this card is shattering (dissolve.fs burn)
                Box(off(m.VT.x.toFloat(), m.VT.y.toFloat()).size(cardW, cardH).graphicsLayer {
                    rotationZ = -(m.VT.r * 57.2958).toFloat()
                    scaleX = m.VT.scale.toFloat(); scaleY = m.VT.scale.toFloat()
                    if (foilOn && diss > 0f)
                        renderEffect = dissolveRenderEffect(diss, host.clock.real.toFloat(), cardWpx, cardHpx, glass = m.shattered)
                }) { CardFace(card, cells[card], cardBase, Modifier.fillMaxSize()) {} }
            }
        }
        // ── HAND (G.hand). Each card is an engine Moveable owned by host.hand (CardArea); the
        // state-driven area-Y in the loop makes the hand SLIDE on play, and align_cards sets the
        // fan/arc/lift each frame — drawn at VT, scale 0.95 (oracle). In repro the engine places the
        // cards at REST (reducedMotion, line ~1634), so this same CardArea path renders the static
        // parity frame and live alike — the verified-correct stable layout.
        if (s.phase == Phase.ROUND) {   // hand is drawn back at end-of-round → hidden on ROUND_EVAL
            s.hand.forEachIndexed { i, card ->
                val m = host.hand.cards.getOrNull(i) ?: return@forEachIndexed
                val interaction = remember(i) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                m.states.drag.isOn = pressed
                val isFaceDown = i in s.faceDown
                val isTarotTarget = s.pendingTarot != null && i in s.tarotTarget
                // Love2D rotate(theta) is CCW in math (CW on screen because y-down).
                // Compose rotationZ positive = CW on screen. Negate to match Love2D sign convention.
                val rotZDeg = -(m.VT.r * 57.2958).toFloat()
                val scaleFactor = m.VT.scale.toFloat()
                Box(
                    off(m.VT.x.toFloat(), m.VT.y.toFloat()).size(cardW, cardH).graphicsLayer {
                        rotationZ = rotZDeg
                        scaleX = scaleFactor; scaleY = scaleFactor
                    }.clickable(interaction, indication = null, enabled = !s.scoring) { s.toggle(i) }
                    .then(if (isTarotTarget) Modifier.border(2.dp, Balatro.Purple, RoundedCornerShape(4.dp)) else Modifier)
                ) {
                    if (isFaceDown) {
                        // Face-down (THE_HOUSE/MARK/WHEEL/FISH): show the card back, no rank/suit/badges visible.
                        // CardFace with face=null and base=cardBack renders exactly the Red Deck card back.
                        CardFace(card, null, cardBack, Modifier.fillMaxSize())
                    } else {
                        CardFace(card, cells[card], cardBase, Modifier.fillMaxSize()) {
                            if (card.enhancement != Enhancement.NONE) BTxt(card.enhancement.badge, Balatro.White, badgeSp,
                                Modifier.align(Alignment.TopStart).background(Balatro.Orange).padding(horizontal = 2.dp))
                            if (card.seal != Seal.NONE) BTxt(card.seal.badge, Balatro.Ink, badgeSp,
                                Modifier.align(Alignment.TopEnd).background(Balatro.Gold).padding(horizontal = 2.dp))
                            // CERULEAN_BELL: blue "!" badge at TopCenter marks the forced-selected card.
                            // The card is already lifted (always in `selected`); the badge makes the reason
                            // visible so the player knows they can't deselect it.
                            if (s.boss == Boss.CERULEAN_BELL && i == s.bellForcedIdx) {
                                BTxt("!", Balatro.Ink, badgeSp,
                                    Modifier.align(Alignment.TopCenter).background(Balatro.Chips).padding(horizontal = 2.dp))
                            }
                        }
                    }
                }
            }
        }
        // hand-size count (N/8) under the hand area.
        if (s.phase == Phase.ROUND) Box(off(handX, handY + PF.CARD_H).width((PF.HAND_W * u).dp), contentAlignment = Alignment.TopCenter) {
            BTxt("${s.hand.size}/8", Balatro.White, countSp)
        }
        // action bar: tarot-use mode (Use / Cancel) or normal (Play / Sort / Discard).
        // contentAlignment=TopCenter: Balatro centres the button row within the hand width — the UI
        // tree width is narrower than HAND_W, so without centring it left-aligns ~400px too far left.
        if (!s.scoring && s.phase == Phase.ROUND) Box(off(handX, handY + PF.CARD_H + 0.45f).width((PF.HAND_W * u).dp), contentAlignment = Alignment.TopCenter) {
            if (s.pendingTarot != null) {
                // Tarot-use mode: Use (enabled when ≥1 target) + Cancel buttons.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    val canUse = s.tarotTarget.isNotEmpty()
                    BButton("Use ${s.pendingTarot!!.name}", Balatro.Purple, enabled = canUse) { s.useTarot() }
                    BButton("Cancel", Balatro.Grey, enabled = true) { s.cancelTarot() }
                }
            } else {
                // Absolute engine: the tree is laid out at its natural width, so the outer Box's
                // TopCenter alignment correctly centres the action bar on the hand without tricks.
                RenderUIBoxNatural(buttonsRow(s, cells), u)
            }
        }
        // ── DECK (G.deck): card-back stack RIGHT-anchored in its 2.25u box + N/52 count.
        Box(off(deckX, deckY).size((PF.DECK_W * u).dp, cardH), contentAlignment = Alignment.TopEnd) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                cardBack?.let { Image(it, "deck", Modifier.size(cardW, cardH), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None) }
                BTxt("${s.deckRemaining}/52", Balatro.White, countSp, Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * Play-field GEOMETRY constants (card + area dimensions, in UI units). The area ORIGINS that used to
 * live here (JOKERS_X/Y, HAND_X/Y, PLAY_X, DECK_X/Y, CONSUM_X, PLAY_RESTING_Y) are gone — they're now
 * derived by set_screen_positions in engine/Room.kt and read off each area's live Moveable VT (the
 * EngineHost) at render time, closing the curve-fit debt. What remains here is genuine dimension data
 * plus the two repro/spring fixtures (PLAY_SCORING_Y, HAND_SPRING_OFFSET). These mirror Room's CAI
 * dims (Room.CARD_W etc.) as Floats for the render; sourcing them from Room is a later cleanup.
 */
private object PF {
    const val CARD_W = 2.04878f; const val CARD_H = 2.75122f
    const val PLAY_W = 10.8585f                                     // CAI.play_W = 5.3·G.CARD_W
    const val PLAY_SCORING_Y = 3.7925f          // cards lifted while scoring (bref_3 frozen frame — a repro fixture)
    const val HAND_W = 12.2927f                                     // CAI.hand_W = 6·G.CARD_W
    const val HAND_SPRING_OFFSET = 1.4f          // SpringHand card-top resting offset below its box top
    const val DECK_W = 2.2537f                                      // CAI.deck_W = 1.1·G.CARD_W
}

/**
 * The played cards popping during the scoring cascade — each springs with Balatro's juice_up
 * (Spring.kt's damped sine: scale = amt·sin(50.8·t)·decay³, the iconic card.lua juice_up(0.3)),
 * fired when the cascade's popIndex reaches that card. Replaces the old sustained scale tween,
 * which never bounced. A single frame clock ticks every card's spring.
 */
@Composable
private fun ScoredCardsRow(s: RunState, cells: Map<PlayingCard, ImageBitmap>, cardBase: ImageBitmap?) {
    val springs = remember(s.scoreCards) { s.scoreCards.map { BalatroSpring() } }
    var frame by remember(s.scoreCards) { mutableStateOf(0L) }
    var nowSec by remember(s.scoreCards) { mutableStateOf(0f) }
    LaunchedEffect(s.scoreCards) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceIn(0.0001f, 0.05f)
                last = t; nowSec = t / 1e9f
                springs.forEach { it.move(dt, nowSec) }
                frame = t
            }
        }
    }
    // Pop the scored card when the cascade reaches it (popIndex 0..n-1; step 0 is the hand base).
    LaunchedEffect(s.popIndex) {
        s.popIndex.takeIf { it in springs.indices }?.let { springs[it].juiceUp(amount = 0.4f, now = nowSec) }
    }
    val u = LocalUIScale.current
    val cardW = (2.0488f * u).dp
    val cardH = (2.7512f * u).dp
    frame.let {}    // read frame -> recompose each tick so graphicsLayer re-reads the spring values
    Row(verticalAlignment = Alignment.Bottom) {
        s.scoreCards.forEachIndexed { i, card ->
            val sp = springs[i]
            Box(
                Modifier.padding(horizontal = (0.04f * u).dp).graphicsLayer {
                    scaleX = sp.vscale; scaleY = sp.vscale; rotationZ = -(sp.vr * 57.2958f)
                }
            ) { CardFace(card, cells[card], cardBase, Modifier.size(cardW, cardH), shadowHeight = 0.1f) }
        }
    }
}

/**
 * Port of create_UIBox_round_evaluation (UI_definitions.lua:1808): the cash-out panel shown
 * after a blind is beaten, before the shop. A dark panel lists the reward rows built by
 * evaluate_round (state_events.lua:1147) — blind reward, $/remaining-hand, gold cards, interest —
 * with an ORANGE "Cash Out: $N" button (button='cash_out', b_cash_out + current_round.dollars).
 * Each row shows a description on the left and the gold $ payout on the right.
 */
@Composable
/**
 * Port of create_UIBox_round_evaluation (UI_definitions.lua:1612). The extracted round_eval_tree.json
 * provides the frame skeleton (three id-tagged empty R nodes); [RoundEvalSpec] converts those R nodes
 * to CardAreaSlot O-nodes so [RenderUIBoxNatural]'s cardAreaContent callback fills each slot:
 *   base_round_eval  — BLIND + HANDS rows (earned during the round)
 *   bonus_round_eval — GOLD + INTEREST rows (bonus payout lines)
 *   eval_bottom      — Cash Out button
 */
private fun RoundEvalScreen(s: RunState) {
    val ctx = LocalContext.current
    val u = LocalUIScale.current
    val evalRoot = remember(ctx) { RoundEvalSpec.load(ctx) }
    if (evalRoot == null) {
        // Fallback: hand-built panel if asset missing.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Panel(Modifier.width(300.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BButton("Cash Out: \$${s.cashOutTotal}", Balatro.OrangeTrue, modifier = Modifier.fillMaxWidth()) { s.cashOut() }
                    Spacer(Modifier.height(2.dp))
                    s.evalRows.forEach { EvalRowView(it) }
                }
            }
        }
        return
    }
    val baseRows  = s.evalRows.filter { it.kind == EvalKind.BLIND || it.kind == EvalKind.HANDS }
    val bonusRows = s.evalRows.filter { it.kind == EvalKind.GOLD  || it.kind == EvalKind.INTEREST }
    val tree = remember(evalRoot) { RoundEvalSpec.build(evalRoot) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        RenderUIBoxNatural(tree, u, cardAreaContent = { name, x, y, w, h ->
            val slotMod = Modifier.absoluteOffset((x * u).dp, (y * u).dp).size((w * u).dp, (h * u).dp)
            when (name) {
                "base_round_eval"  -> Box(slotMod) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = (0.15f * u).dp),
                           verticalArrangement = Arrangement.spacedBy((0.15f * u).dp)) {
                        baseRows.forEach { EvalRowView(it) }
                    }
                }
                "bonus_round_eval" -> Box(slotMod) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = (0.15f * u).dp),
                           verticalArrangement = Arrangement.spacedBy((0.15f * u).dp)) {
                        bonusRows.forEach { EvalRowView(it) }
                    }
                }
                "eval_bottom"      -> Box(slotMod, contentAlignment = Alignment.Center) {
                    BButton("Cash Out: \$${s.cashOutTotal}", Balatro.OrangeTrue,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = (0.2f * u).dp)) { s.cashOut() }
                }
            }
        })
    }
}

/** One add_round_eval_row: left = [coloured count] description, right = gold $ payout pips. */
@Composable
private fun EvalRowView(row: EvalRow) {
    val accent = when (row.kind) {
        EvalKind.BLIND -> Balatro.Money; EvalKind.HANDS -> Balatro.Chips
        EvalKind.GOLD -> Balatro.Gold; EvalKind.INTEREST -> Balatro.Money
    }
    Row(
        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.leadNum != null) { BTxt(row.leadNum, accent, 16.sp); Spacer(Modifier.width(4.dp)) }
            BTxt(row.label, Balatro.White, 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        // Balatro renders num_dollars gold "$" pips; collapse to "$N" past 7 to stay on one line.
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Balatro.Panel).padding(horizontal = 8.dp, vertical = 2.dp)) {
            BTxt(if (row.dollars <= 7) "\$".repeat(row.dollars) else "\$${row.dollars}", Balatro.Money, 16.sp)
        }
    }
}

/**
 * Port of create_UIBox_game_over (UI_definitions.lua:2862).
 * Two-column layout matching vanilla: left column shows per-run scoring stats
 * (chips scored, most-played hand, cards played/discarded/purchased, rerolls, seed);
 * right column shows ante/round reached, defeated-by blind, and action buttons.
 */
@Composable
private fun GameOverScreen(s: RunState, onRestart: () -> Unit, onMainMenu: () -> Unit) =
    EndScreen(s, win = false, onRestart = onRestart, onMainMenu = onMainMenu)

/** Win screen: the REAL create_UIBox_win tree (win_tree.json), bound like game-over. */
@Composable
private fun WinScreen(s: RunState, onRestart: () -> Unit, onMainMenu: () -> Unit) =
    EndScreen(s, win = true, onRestart = onRestart, onMainMenu = onMainMenu)

/**
 * Game-over / win, rendered from the REAL create_UIBox_game_over / create_UIBox_win trees
 * (game_over_tree.json / win_tree.json) through the ported layout engine via [GameOverSpec] — no
 * hand-built panel. Structure + labels + buttons come from the extracted tree; the round_scores_row
 * stat values bind to RunState (the same values the old hand-built screen showed); New-Run / Main-Menu
 * buttons fire via the tree's bound onClick. Falls back to a minimal panel only if the asset is missing.
 */
@Composable
private fun EndScreen(s: RunState, win: Boolean, onRestart: () -> Unit, onMainMenu: () -> Unit) {
    val ctx = LocalContext.current
    val u = LocalUIScale.current
    val root = remember(ctx, win) { GameOverSpec.load(ctx, win) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        if (root != null) {
            val tree = remember(root) { GameOverSpec.build(root, GameOverBind(s, onRestart, onMainMenu)) }
            // create_UIBox_game_over / _win is a 100×57.5u overlay backing with the dialog centred in
            // it; render it centred over the full surface (RenderUIBoxAt centres tree in the rect) and
            // clip the oversized backing to the screen — exactly how vanilla layers the overlay menu.
            RenderUIBoxAt(tree, u, 0f, 0f, maxWidth.value / u, maxHeight.value / u)
        } else {
            Panel(Modifier.width(360.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BTxt(if (win) "Victory!" else "Game Over", if (win) Balatro.Chips else Balatro.Mult, 26.sp)
                    BButton("New Run",   Balatro.Mult, modifier = Modifier.fillMaxWidth()) { onRestart() }
                    BButton("Main Menu", Balatro.Mult, modifier = Modifier.fillMaxWidth()) { onMainMenu() }
                }
            }
        }
    }
}

/**
 * Port of create_UIBox_shop (UI_definitions.lua:691-739): the dark shop panel holding the
 * Next Round (RED) / Reroll (GREEN) buttons on the left and a row of card slots (L_BLACK inset)
 * on the right, each card showing its price tag ($N, MONEY) above and a Buy tag (GOLD) below —
 * create_shop_card_ui (lines 810-825). Vouchers + booster packs are out of scope (no engine
 * support yet); the existing jokers/planets/tarots fill the slot row as real cards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShopPhase(s: RunState, jokerCells: Map<String, ImageBitmap>, cardBase: ImageBitmap?) {
    // The shop FRAME is Balatro's REAL G.UIDEF.shop() tree (assets/ui/shop_tree.json, extracted by
    // tools/uiref/extract.sh) — Next-Round / Reroll buttons + the 3 CardArea slots — laid out by the
    // ported engine. Slot contents bind from RunState via cardAreaContent. No hand-built shop frame.
    val ctx = LocalContext.current
    val u = LocalUIScale.current
    val root = remember { HudSpec.root(ctx, "shop_tree.json") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (root != null) {
            val tree = HudSpec.build(root, HudBind(s, null))
            RenderUIBoxNatural(tree, u, cardAreaContent = { name, x, y, w, h ->
                ShopSlotOffers(s, name, x, y, w, h, u, jokerCells, cardBase)
            })
        }
        // sell strip — not a Balatro UIBox; the only way to offload jokers until the joker row is interactive
        if (s.owned.isNotEmpty()) {
            Row(Modifier.align(Alignment.BottomCenter).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BTxt("Sell:", Balatro.White, 12.sp)
                s.owned.forEach { o ->
                    BButton("${o.offer.name}  \$${maxOf(1, o.offer.cost / 2)}", Balatro.Grey, enabled = s.owned.size > 1) { s.sell(o) }
                }
            }
        }
    }
}

/** Fill a shop CardArea slot's engine-computed rect (units) with the live offers from RunState:
 *  shop_jokers = jokers + planets + tarots; shop_vouchers = the voucher; shop_booster = the packs.
 *  Each offer renders via [ShopOfferCard]: price tag + art + buy/redeem/open button all from
 *  create_shop_card_ui (shop_card_ui.json), with the card art box hand-built (no UIBox equivalent). */
@Composable
private fun ShopSlotOffers(s: RunState, name: String, x: Float, y: Float, w: Float, h: Float, u: Float,
                           jokerCells: Map<String, ImageBitmap>, cardBase: ImageBitmap?) {
    val ctx = LocalContext.current
    val spec = remember { OfferCardSpec.load(ctx) }
    Box(Modifier.absoluteOffset((x * u).dp, (y * u).dp).size((w * u).dp, (h * u).dp),
        contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            when (name) {
                "shop_jokers" -> {
                    s.shop.forEach { o ->
                        ShopOfferCard(spec, OfferCardSpec.Set.JOKER, o.name, jokerCells[o.key], cardBase,
                            s.price(o.cost), o.desc, Balatro.Mult,
                            s.money >= s.price(o.cost) && s.owned.size < s.maxJokers, u) { s.buy(o) }
                    }
                    s.shopPlanets.forEach { po ->
                        ShopOfferCard(spec, OfferCardSpec.Set.CONSUMABLE, po.planet.display, null, cardBase,
                            s.price(po.cost), handName(po.planet.hand), Balatro.Chips,
                            s.money >= s.price(po.cost), u) { s.buyPlanet(po) }
                    }
                    s.shopTarots.forEach { t ->
                        val fx = if (t.seal != Seal.NONE) "${t.seal.name.lowercase()} seal" else t.enhancement.name.lowercase()
                        ShopOfferCard(spec, OfferCardSpec.Set.CONSUMABLE, t.name, null, cardBase,
                            s.price(t.cost), fx, Balatro.Purple,
                            s.money >= s.price(t.cost), u) { s.buyTarot(t) }
                    }
                }
                "shop_vouchers" -> s.shopVoucher?.let { v ->
                    ShopOfferCard(spec, OfferCardSpec.Set.VOUCHER, v.name, null, cardBase,
                        s.price(v.cost), v.desc, Balatro.Gold,
                        s.money >= s.price(v.cost), u) { s.redeemVoucher(v) }
                }
                "shop_booster" -> s.shopBoosters.forEach { b ->
                    ShopOfferCard(spec, OfferCardSpec.Set.BOOSTER, b.name, null, cardBase,
                        s.price(b.cost), "open ${b.extra}, pick ${b.choose}", Balatro.Chips,
                        s.money >= s.price(b.cost), u) { s.buyBooster(b) }
                }
            }
        }
    }
}

/**
 * One shop offer card: price tag (extracted create_shop_card_ui price tree) + card art box +
 * desc line + buy/redeem/open button (extracted create_shop_card_ui button tree). The price and
 * button panels are rendered via RenderUIBoxNatural using the vanilla create_shop_card_ui geometry
 * (shop_card_ui.json). The card art body has no UIBox equivalent and is hand-built.
 *
 * Falls back to a plain hand-built layout if [spec] failed to load (missing asset).
 */
@Composable
private fun ShopOfferCard(
    spec: OfferCardSpec?, set: OfferCardSpec.Set,
    name: String, art: ImageBitmap?, base: ImageBitmap?,
    cost: Int, desc: String, descColour: Color, canBuy: Boolean, u: Float,
    onAction: () -> Unit,
) {
    val bind = CardBind(cost, canBuy, onAction)
    val trees = spec?.forSet(set, bind)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        if (trees != null) {
            // price tag — create_shop_card_ui price tree: darken(BLACK,0.2) chip + DynaText "$cost" MONEY
            RenderUIBoxNatural(trees.first, u)
        } else {
            // fallback: plain hand-built price badge
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Balatro.FeltDark).padding(horizontal = 8.dp, vertical = 1.dp)) {
                BTxt("\$$cost", Balatro.Money, 13.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
        // card art — no UIBox equivalent; hand-built (live Sprite objects in vanilla)
        Box(Modifier.size(64.dp, 86.dp), contentAlignment = Alignment.Center) {
            if (art != null) {
                Image(art, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            } else {
                base?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None) }
                BTxt(name, Balatro.Ink, 9.sp, Modifier.padding(horizontal = 3.dp))
            }
        }
        BTxt(desc, descColour, 8.sp, Modifier.padding(top = 1.dp))
        Spacer(Modifier.height(3.dp))
        if (trees != null) {
            // buy/redeem/open button — create_shop_card_ui button tree: GOLD/GREEN/GREY rounded rect
            RenderUIBoxNatural(trees.second, u)
        } else {
            // fallback: plain hand-built buy button
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(if (canBuy) Balatro.Gold else Balatro.Grey)
                    .clickable(enabled = canBuy) { onAction() }.padding(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { BTxt("Buy", Balatro.White, 12.sp) }
        }
    }
}


/** (name, desc, accentColour) for one revealed pack item. */
private fun packItemView(item: PackItem): Triple<String, String, Color> = when (item) {
    is PackItem.Tarot -> Triple(
        item.t.name,
        if (item.t.seal != Seal.NONE) "${item.t.seal.name.lowercase()} seal" else item.t.enhancement.name.lowercase(),
        Balatro.Purple,
    )
    is PackItem.Planet -> Triple(item.p.planet.display, handName(item.p.planet.hand), Balatro.Chips)
    is PackItem.Joker -> Triple(item.o.name, item.o.desc, Balatro.Mult)
    is PackItem.Card -> Triple("", if (item.card.enhancement != Enhancement.NONE) item.card.enhancement.name.lowercase() else "", Balatro.White)
    is PackItem.SpectralItem -> Triple(item.s.display, item.s.desc, Balatro.Mult)
}

/**
 * Port of create_UIBox_arcana/spectral/standard/buffoon/celestial_pack.
 * The frame tree (pack_*_tree.json) is rendered by [PackSpec]; the CardArea slot ("pack_cards")
 * is filled by [PackItemsContent] which lays out items from RunState.openPack.items.
 *
 * [PackSpec] pre-loads all five pack JSON trees once per PackOpenScreen composition.
 * [PackBind] wires pack_choices (pick count) and the skip_booster button to live state.
 */
@Composable
private fun PackOpenScreen(s: RunState, jokerCells: Map<String, ImageBitmap>, cardBase: ImageBitmap?, cells: Map<PlayingCard, ImageBitmap>) {
    val p = s.openPack ?: return
    val ctx = LocalContext.current
    val u = LocalUIScale.current
    val spec = remember(ctx) { PackSpec.load(ctx) }
    val kind = when (p.kind) {
        "Arcana"   -> PackSpec.Kind.ARCANA
        "Spectral" -> PackSpec.Kind.SPECTRAL
        "Standard" -> PackSpec.Kind.STANDARD
        "Buffoon"  -> PackSpec.Kind.BUFFOON
        else       -> PackSpec.Kind.CELESTIAL   // "Celestial"
    }
    val tree = remember(kind, p.picksLeft) {
        spec.forPack(kind, PackBind(p.picksLeft) { s.skipPack() })
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BTxt("\$${s.money}", Balatro.Money, 22.sp, Modifier.align(Alignment.TopEnd).padding(8.dp))
        if (tree != null) {
            RenderUIBoxNatural(tree, u, cardAreaContent = { name, x, y, w, h ->
                if (name == "pack_cards") {
                    PackItemsContent(p, x, y, w, h, u, jokerCells, cardBase, cells, s)
                }
            })
        } else {
            // Fallback if JSON missing: plain column layout.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BTxt(p.name, Balatro.White, 18.sp)
                BTxt(if (p.picksLeft > 0) "Pick ${p.picksLeft}" else "Done", Balatro.Gold, 13.sp)
                Spacer(Modifier.height(8.dp))
                BButton("Skip", Balatro.Mult) { s.skipPack() }
            }
        }
    }
}

/**
 * Fills the pack_cards CardAreaSlot with the revealed items. Each item is sized to CARD_W × CARD_H
 * Balatro units and spaced evenly across the slot width. Items outside the slot are clipped.
 * Tap to pick (if picks remain and not already taken); highlight taken items with grey overlay.
 */
@Composable
private fun PackItemsContent(p: OpenPack, x: Float, y: Float, w: Float, h: Float, u: Float,
                             jokerCells: Map<String, ImageBitmap>, cardBase: ImageBitmap?,
                             cells: Map<PlayingCard, ImageBitmap>, s: RunState) {
    val n = p.items.size
    if (n == 0) return
    val cardW = w / n                   // Balatro units per item (CARD_W ≈ 2.049, spaced to fill slot)
    val cardH = h                       // slot height = CARD_H
    Box(Modifier.absoluteOffset((x * u).dp, (y * u).dp).size((w * u).dp, (h * u).dp).clip(RectangleShape)) {
        p.items.forEachIndexed { i, item ->
            val taken    = i in p.picked
            val canPick  = !taken && p.picksLeft > 0
            val (name, desc, accent) = packItemView(item)
            val iX = i * cardW
            Box(
                Modifier
                    .absoluteOffset((iX * u).dp, 0.dp)
                    .size((cardW * u).dp, (cardH * u).dp)
                    .clickable(enabled = canPick) { s.pickPackItem(i) },
                contentAlignment = Alignment.Center,
            ) {
                when (item) {
                    is PackItem.Card ->
                        CardFace(item.card, cells[item.card], cardBase, Modifier.fillMaxSize()) {}
                    is PackItem.Joker -> {
                        val art = jokerCells[item.o.key]
                        if (art != null) Image(art, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                        else {
                            cardBase?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None) }
                            BTxt(name, Balatro.Ink, 9.sp, Modifier.padding(horizontal = 3.dp))
                        }
                    }
                    else -> {
                        cardBase?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None) }
                        BTxt(name, Balatro.Ink, 9.sp, Modifier.padding(horizontal = 3.dp))
                    }
                }
                // Taken overlay
                if (taken) Box(Modifier.fillMaxSize().background(Balatro.Panel.copy(alpha = 0.55f)))
                // Item label under card
                BTxt(desc, accent, 7.sp, Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
            }
        }
    }
}

/**
 * Port of create_UIBox_blind_select (UI_definitions.lua:1417) + create_UIBox_blind_choice
 * (line 1485). Each slot's card tree is extracted JSON: blind_small/big/boss_tree.json.
 * [BlindSpec] pre-loads all three; [BlindBind] wires per-slot state at build time.
 *
 * Outer composition: Column with Row of three BlindSpec-built trees, skip button, earned tags.
 * (The outer Row itself is not a ported UIBox — create_UIBox_blind_select's outer R padding=0.5
 * is reproduced via Compose-level Arrangement.spacedBy. Inner card trees are fully ported.)
 */
@Composable
private fun BlindSelectScreen(s: RunState, stakeBmp: ImageBitmap? = null) {
    val ctx = LocalContext.current
    // Load all three blind sprites in one atlas pass — including the UPCOMING boss.
    // Re-fires per ante (blindIndex changes each blind pick).
    val u = LocalUIScale.current
    val blindArt by produceState<Triple<ImageBitmap?, ImageBitmap?, ImageBitmap?>>(
        Triple(null, null, null), s.blindIndex
    ) { value = withContext(Dispatchers.Default) { BlindArt.cacheRun(ctx, s.upcomingBoss) } }

    // Pre-load JSON trees once (cached by HudSpec.root); no recompose cost after first render.
    val spec = remember(ctx) { BlindSpec.load(ctx) }

    val currentSlot = s.blindIndex % 3

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Top
        ) {
            for (slotIdx in 0..2) {
                val blindBmp: ImageBitmap? = when (slotIdx) {
                    0 -> blindArt.first; 1 -> blindArt.second; else -> blindArt.third
                }
                val enabled = slotIdx == currentSlot
                val bossColour = if (slotIdx == 2) bossColourOf(s.upcomingBoss) else null

                val tree: UI? = spec.forSlot(
                    slotIdx    = slotIdx,
                    enabled    = enabled,
                    bossColour = bossColour,
                    blindBmp   = blindBmp,
                    stakeBmp   = stakeBmp,
                    chipTarget = fmtR(s.targetForSlot(slotIdx)),
                    reward     = s.rewardForSlot(slotIdx),
                    selectAction = if (enabled) { { s.selectBlind() } } else null,
                    skipAction   = if (slotIdx != 2) { { s.skipBlind() } } else null,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    if (tree != null) RenderUIBoxNatural(tree, u)
                }
            }
        }
        // Skip the Small/Big blind for its Tag (the Boss can't be skipped).
        if (currentSlot != 2) {
            Spacer(Modifier.height(14.dp))
            BButton("Skip Blind  →  ${s.upcomingTag.display}", Balatro.Mult) { s.skipBlind() }
            BTxt(s.upcomingTag.desc, Balatro.Gold, 11.sp, Modifier.padding(top = 3.dp))
        }
        // earned tags awaiting their trigger
        if (s.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            BTxt("Tags: " + s.tags.joinToString { it.display }, Balatro.Chips, 12.sp)
        }
    }
}

/**
 * Boss colour from G.P_BLINDS[boss.key].boss_colour (game.lua).
 * Each boss blind has a unique tint registered in extract.lua as "boss:bl_*" in colourName.
 * The hex values here match the blindcolour() calls in extract.lua's G.P_BLINDS table.
 */
private fun bossColourOf(boss: Boss?): Color = when (boss) {
    Boss.THE_OX        -> Color(0xFFB95B08)
    Boss.THE_CLUB      -> Color(0xFF235955)
    Boss.THE_FLINT     -> Color(0xFF6E3B3F)
    Boss.THE_MARK      -> Color(0xFF594F6A)
    Boss.THE_FISH      -> Color(0xFF1E5F69)
    Boss.THE_PSYCHIC   -> Color(0xFF5F2565)
    Boss.THE_GOAD      -> Color(0xFF5F5330)
    Boss.THE_WATER     -> Color(0xFF2D5E57)
    Boss.THE_EYE       -> Color(0xFF5A4A2B)
    Boss.THE_MOUTH     -> Color(0xFF5F2027)
    Boss.THE_WINDOW    -> Color(0xFF4A3A5F)
    Boss.THE_PLANT     -> Color(0xFF34592E)
    Boss.THE_NEEDLE    -> Color(0xFF2B3E5F)
    Boss.THE_HEAD      -> Color(0xFF5F3530)
    Boss.THE_TOOTH     -> Color(0xFF5F5530)
    Boss.THE_WALL      -> Color(0xFF3B3B3B)
    Boss.THE_WHEEL     -> Color(0xFF3B4B5F)
    Boss.THE_HOUSE     -> Color(0xFF4B3A5F)
    Boss.THE_HOOK      -> Color(0xFF5F2B45)
    Boss.THE_ARM       -> Color(0xFF3B5F4F)
    Boss.THE_PILLAR    -> Color(0xFF5F4B3A)
    Boss.THE_SERPENT   -> Color(0xFF3B5F3B)
    Boss.THE_MANACLE   -> Color(0xFF5F3A3A)
    Boss.VERDANT_LEAF  -> Color(0xFF2F5F2F)
    Boss.VIOLET_VESSEL -> Color(0xFF4B2F5F)
    Boss.AMBER_ACORN   -> Color(0xFF5F4B1F)
    Boss.CRIMSON_HEART -> Color(0xFF5F1F1F)
    Boss.CERULEAN_BELL -> Color(0xFF1F4F5F)
    null               -> Color(0xFF5F3A2B)
}


/**
 * Run Info — the poker-hands level table, rendered from the REAL create_UIBox_current_hands tree
 * (run_info_tree.json) through the ported engine via [GameOverSpec]'s generic builder. G.UIDEF.run_info()
 * itself extracts only to the overlay shell (its tabbed content is built dynamically by the overlay-menu
 * system at display time), so the iconic poker-hands tab — 12 rows of level pip + name + per-level
 * chips/mult + lifetime play count — is extracted directly. Back returns to the prior phase.
 * Deferred: the blinds/vouchers tabs, and live per-hand level/play binding (the table shows base values).
 */
@Composable
private fun RunInfoScreen(s: RunState) {
    val ctx = LocalContext.current
    val u = LocalUIScale.current
    val root = remember(ctx) { HudSpec.root(ctx, "run_info_tree.json") }
    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BTxt("Run Info", Balatro.White, 20.sp)
            Spacer(Modifier.weight(1f))
            BButton("Back", Balatro.Orange) { s.closeRunInfo() }
        }
        if (root != null) {
            val tree = remember(root) { GameOverSpec.build(root, GameOverBind(s, { s.closeRunInfo() }, { s.closeRunInfo() })) }
            RenderUIBoxNatural(tree, u)
        }
    }
}


private fun fmtR(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
