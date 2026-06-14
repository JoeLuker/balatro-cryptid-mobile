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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import systems.balatro.content.Content
import systems.balatro.content.Edition
import systems.balatro.content.Editions
import systems.balatro.engine.Entity
import systems.balatro.engine.World
import systems.balatro.game.*

/**
 * A full run: alternating blinds and a shop, on ONE persistent engine. Beating a blind pays
 * out; the shop spends that money to BUY jokers (register live) or SELL them (unregister +
 * destroy live — the clean-removal payoff ShopSim proves). Jokers and their scaling state
 * carry across blinds because the engine is never rebuilt. This is the game on the engine.
 */
private enum class Phase { ROUND, BLIND_SELECT, SHOP, OVER }
private data class Offer(val key: String, val name: String, val desc: String, val cost: Int, val edition: Edition = Edition.NONE)
private data class Owned(val entity: Entity, val offer: Offer)
private data class PlanetOffer(val planet: Planet, val cost: Int)
private data class TarotOffer(val name: String, val enhancement: Enhancement = Enhancement.NONE, val cost: Int, val seal: Seal = Seal.NONE)

private val CATALOG = listOf(
    Offer("j_joker", "Joker", "+4 Mult", 2),
    Offer("j_greedy_joker", "Greedy Joker", "+3 Mult / Diamond", 5),
    Offer("j_lusty_joker", "Lusty Joker", "+3 Mult / Heart", 5),
    Offer("j_wrathful_joker", "Wrathful Joker", "+3 Mult / Spade", 5),
    Offer("j_gluttenous_joker", "Gluttonous Joker", "+3 Mult / Club", 5),
    Offer("j_even_steven", "Even Steven", "+4 Mult / even card", 4),
    Offer("j_odd_todd", "Odd Todd", "+31 Chips / odd card", 4),
    Offer("j_scholar", "Scholar", "Ace: +20 Chips & +4 Mult", 4),
    Offer("j_cry_cube", "Cube", "+6 Chips", 4),
    Offer("j_cry_triplet_rhythm", "Triplet Rhythm", "x3 Mult if three 3s", 6),
    Offer("j_cry_lightupthenight", "Light Up the Night", "x1.5 Mult / 2 or 7", 7),
    Offer("j_cry_krustytheclown", "Krusty the Clown", "x_mult +0.02 / card", 7),
    Offer("j_cry_brokenhome", "Broken Home", "x11.4 Mult", 8),
)
private const val HANDS = 4
private const val DISCARDS = 3

/** 3 shop offers, deterministic per ante; ~60% of the time the first slot rolls an edition (+3 cost). */
private fun rollShop(blind: Int): List<Offer> {
    val rng = Random(blind * 7919L + 13)
    val base = CATALOG.shuffled(rng).take(3)
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

/** Compose-observable run state; mutations drive recomposition. The engine is persistent. */
private class RunState {
    val world = World()
    val effects = Effects()
    private val scorer = ScoreRun(effects)

    var money by mutableStateOf(4)
    var blindIndex by mutableStateOf(0)                  // 0-based global blind counter
    var boss by mutableStateOf<Boss?>(null)              // set on the boss slot
    var phase by mutableStateOf(Phase.ROUND)

    val ante: Int get() = blindIndex / 3 + 1
    private val slot: Int get() = blindIndex % 3          // 0 Small, 1 Big, 2 Boss
    val blindName: String get() = when (slot) { 0 -> "Small Blind"; 1 -> "Big Blind"; else -> boss?.display ?: "Boss Blind" }
    val owned = mutableStateListOf<Owned>()
    var shop by mutableStateOf<List<Offer>>(emptyList())
    var shopPlanets by mutableStateOf<List<PlanetOffer>>(emptyList())
    var shopTarots by mutableStateOf<List<TarotOffer>>(emptyList())
    var enhancedCount by mutableStateOf(0)

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

    val target: Double get() {
        val base = 300.0 * ante
        return when (slot) { 0 -> base; 1 -> base * 1.5; else -> base * 2.0 * (boss?.targetMult ?: 1.0) }
    }

    /** Amount for each blind slot in the CURRENT ante (slot 0=Small, 1=Big, 2=Boss).
     *  Mirrors get_blind_amount()*blind.config.mult from Lua. Used by blind-select cards. */
    fun targetForSlot(slotIdx: Int): Double {
        val base = 300.0 * ante
        return when (slotIdx) { 0 -> base; 1 -> base * 1.5; else -> base * 2.0 * (boss?.targetMult ?: 1.0) }
    }

    /** Reward dollars for each blind slot (config.dollars in Lua: Small=$3, Big=$4, Boss=$5). */
    fun rewardForSlot(slotIdx: Int): Int = 3 + slotIdx

    /** Name label for the upcoming blind slot on the blind-select screen. */
    fun nameForSlot(slotIdx: Int): String = when (slotIdx) {
        0 -> "Small Blind"; 1 -> "Big Blind"; else -> boss?.display ?: "Boss Blind"
    }

    /** Description for the blind-select screen (boss ability line, or empty for Small/Big). */
    fun descForSlot(slotIdx: Int): String = if (slotIdx == 2) boss?.desc ?: "" else ""

    /** Mirrors G.GAME.blind.chip_text — the chip target as a formatted string for the HUD_blind T node.
     *  scale=0.001 in the source means it starts invisible; blind_chip_UI_scale animates it in (deferred).
     *  We render at scale=0.001 faithfully; the string itself must still be correct. */
    val chipText: String get() = fmtR(target)

    /** Mirrors G.GAME.current_round.dollars_to_be_earned — reward payout shown on the blind panel.
     *  Balatro formula: 4 + hands_left (+ gold cards, deferred). Matches scoreCommit's reward calc. */
    val dollarsToBeEarned: Int get() = 4 + handsLeft

    // ── contents.hand bindings — mirror current_round.current_hand ──────────────
    // These feed the DynaText Os in hudHand(). Compose recomposes when the mutableStateOf
    // fields they read (scoring, displayChips, displayMult, lastResult) change.

    /** Mirrors current_hand.handname_text — shown in the hand-name DynaText. */
    val handNameText: String get() = if (scoring || lastResult != null)
        handName(lastResult?.handType ?: HandType.NONE) else ""

    /** Mirrors current_hand.chip_text — live cascade counter for the chips box (blank when idle). */
    val chipText2: String get() = if (scoring || lastResult != null) fmtR(displayChips) else ""

    /** Mirrors current_hand.chip_total_text — cumulative round score shown in the top-row readout. */
    val chipTotalText: String get() = if (scoring || lastResult != null) fmtR(roundScore) else ""

    /** Mirrors current_hand.mult_text — the mult DynaText (blank when idle). */
    val multText: String get() = if (scoring || lastResult != null) fmtR(displayMult) else ""

    /** Mirrors current_hand.hand_level — the Lv badge T node. */
    val currentHandLevel: Int get() = lastResult?.let { handLevel(it.handType) } ?: 0

    init {
        Telemetry.event("RUN_START")
        buy(Offer("j_joker", "Joker", "+4 Mult", 0), free = true)   // start with a Joker
        startRound()
    }

    private fun startRound() {
        boss = if (slot == 2) Boss.values().random(Random(blindIndex * 2654435761L + 1)) else null
        deck.reshuffle()                  // re-deal the persistent deck (enhancements preserved)
        hand = deck.draw(8); selected = emptySet()
        roundScore = 0.0
        handsLeft = boss?.hands(HANDS) ?: HANDS          // The Needle: 1 hand
        discardsLeft = boss?.discards(DISCARDS) ?: DISCARDS  // The Water: 0 discards
        lastResult = null; lastSteps = emptyList()
        phase = Phase.ROUND
        Telemetry.event("ROUND_START", "ante" to ante, "blind" to blindName, "target" to target, "boss" to (boss?.display ?: "-"))
    }

    private fun refill() {
        val keep = hand.filterIndexed { i, _ -> i !in selected }
        hand = keep + deck.draw(8 - keep.size)
        selected = emptySet()
    }

    fun toggle(i: Int) { if (phase == Phase.ROUND) selected = if (i in selected) selected - i else selected + i }

    /** Score the selection now (the engine), but resolve it as an ANIMATION — the UI drives
     *  scoreStep()/scoreCommit() over time so chips/mult tick up and cards pop one by one. */
    fun play() {
        if (phase != Phase.ROUND || selected.isEmpty() || scoring) return
        val sel = hand.filterIndexed { i, _ -> i in selected }
        val held = hand.filterIndexed { i, _ -> i !in selected }
        val trace = ArrayList<ScoreStep>()
        val r = scorer.scoreDetailed(world, sel, trace, boss?.scoringDebuff ?: Debuff.None, held)
        lastResult = r; lastSteps = trace
        pending = r; pendingSel = sel; pendingHeld = held
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

    /** Commit the scored hand: bank the score, refill, advance the run. */
    fun scoreCommit() {
        val r = pending ?: return
        roundScore += r.score; handsLeft -= 1
        money += pendingSel.count { it.seal == Seal.GOLD } * 3
        scoring = false; scoreCards = emptyList(); popIndex = -1
        Telemetry.event("ROUND_BANK", "total" to roundScore)
        refill()
        if (roundScore >= target) {
            val gold = pendingHeld.count { it.enhancement == Enhancement.GOLD }
            val reward = 4 + handsLeft + gold * 3
            money += reward
            Telemetry.event("ROUND_WIN", "blind" to blindName, "total" to roundScore, "reward" to reward)
            blindIndex += 1
            shop = rollShop(blindIndex); shopPlanets = rollPlanets(blindIndex); shopTarots = rollTarots(blindIndex)
            phase = Phase.SHOP
        } else if (handsLeft <= 0) {
            phase = Phase.OVER
            Telemetry.event("ROUND_LOSE", "blind" to blindName, "total" to roundScore)
        }
    }

    fun discard() {
        if (phase != Phase.ROUND || selected.isEmpty() || discardsLeft <= 0) return
        discardsLeft -= 1
        Telemetry.event("ROUND_DISCARD", "n" to selected.size)
        refill()
    }

    fun buy(offer: Offer, free: Boolean = false) {
        if (!free && money < offer.cost) return
        if (!free) money -= offer.cost
        val e = Editions.spawn(world, effects, offer.key, offer.edition)   // register live, with edition
        owned.add(Owned(e, offer))
        shop = shop.filterNot { it === offer }
        if (!free) Telemetry.event("RUN_BUY", "key" to offer.key, "edition" to offer.edition.name, "cost" to offer.cost, "money" to money)
    }

    fun sell(o: Owned) {
        if (owned.size <= 1) return                  // keep at least one joker
        effects.unregister(o.entity); world.destroy(o.entity)        // remove live, no residue
        owned.remove(o)
        val refund = maxOf(1, o.offer.cost / 2)
        money += refund
        Telemetry.event("RUN_SELL", "key" to o.offer.key, "refund" to refund, "money" to money)
    }

    fun buyPlanet(po: PlanetOffer) {
        if (money < po.cost) return
        money -= po.cost
        Levels.ensure(world).levelUp(po.planet.hand)        // raises the hand's base for the whole run
        shopPlanets = shopPlanets.filterNot { it === po }
        Telemetry.event("RUN_PLANET", "planet" to po.planet.display, "hand" to po.planet.hand.name, "money" to money)
    }

    fun buyTarot(t: TarotOffer) {
        if (money < t.cost) return
        money -= t.cost
        val card = if (t.seal != Seal.NONE) deck.sealRandom(t.seal) else deck.enhanceRandom(t.enhancement)
        if (card != null) enhancedCount += 1
        shopTarots = shopTarots.filterNot { it === t }
        Telemetry.event("RUN_TAROT", "tarot" to t.name, "enh" to t.enhancement.name, "seal" to t.seal.name, "card" to (card?.key ?: "none"), "money" to money)
    }

    fun handLevel(h: HandType): Int = Levels.get(world)?.level(h) ?: 1

    fun nextBlind() { if (phase == Phase.SHOP) phase = Phase.BLIND_SELECT }

    /** Commit a blind selection and start the round (button = 'select_blind' in Lua source). */
    fun selectBlind() { if (phase == Phase.BLIND_SELECT) startRound() }

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
fun RunScreen(onClose: () -> Unit) {
    var runNo by remember { mutableStateOf(0) }
    key(runNo) { RunBody(onClose = onClose, onRestart = { runNo++ }) }
}

@Composable
private fun RunBody(onClose: () -> Unit, onRestart: () -> Unit) {
    val ctx = LocalContext.current
    val s = remember { RunState() }

    val allCards = remember { Suit.values().flatMap { su -> (2..14).map { PlayingCard(su, it) } } }
    val cells by produceState<Map<PlayingCard, ImageBitmap>>(emptyMap()) {
        value = withContext(Dispatchers.Default) { CardArt.cache(ctx, allCards) }
    }
    val jokerCells by produceState<Map<String, ImageBitmap>>(emptyMap()) {
        value = withContext(Dispatchers.Default) { JokerArt.cache(ctx, CATALOG.map { it.key }) }
    }

    Box(Modifier.fillMaxSize().background(Balatro.Felt)) {                       // the green felt table
        Row(Modifier.fillMaxSize().padding(10.dp)) {
            HudColumn(s, Modifier.width(180.dp).fillMaxHeight(), onClose)        // Balatro's left sidebar
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {                          // the play area
                when (s.phase) {
                    Phase.ROUND -> RoundPlay(s, cells, jokerCells)
                    Phase.BLIND_SELECT -> BlindSelectScreen(s)
                    Phase.SHOP -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ShopPhase(s, jokerCells) }
                    Phase.OVER -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Panel {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                BTxt("Game Over", Balatro.Mult, 24.sp)
                                BTxt("lost ${s.blindName} · Ante ${s.ante}", Balatro.White, 13.sp)
                                Spacer(Modifier.height(10.dp))
                                BButton("New Run", Balatro.Orange) { onRestart() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Balatro's left sidebar: blind token + score target, round score, Hands/Discards, money, Ante/Round. */
@Composable
private fun HudColumn(s: RunState, modifier: Modifier, onClose: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BButton("✕", Balatro.Mult) { onClose() }
            Spacer(Modifier.weight(1f))
            BTxt("Ante ${s.ante}/8", Balatro.White, 12.sp)
        }
        // Blind token + target: Balatro's create_UIBox_HUD_blind tree through the UIBox interpreter.
        // blindBmp/stakeBmp are null until BlindArt.cache is wired; B spacers hold the layout.
        RenderUI(hudBlind(s, blindBmp = null, stakeBmp = null))
        // Round score: Balatro's contents.dollars_chips through the UIBox interpreter.
        RenderUI(hudDollarsChips(s))
        // Row-round: Balatro's R(id='row_round') containing C{buttons} + C{round} (source line 1408-1411).
        // hudButtons (C column) and hudRound (C column) are siblings inside a wrapping R row.
        RenderUI(R(Cfg(align = "cm"),
            C(Cfg(align = "cm"), hudButtons()),
            C(Cfg(align = "cm"), hudRound(s))))
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
 * stake sprite O (w=0.5, h=0.5) → B placeholder until stake atlas is wired.
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
 * Left column: two stacked labels ("Round" / "Score"); right column: stake sprite placeholder +
 * B spacer + T(chips_text) which is the running round score (G.GAME.chips_text ref).
 *
 * G.C.DYN_UI.BOSS_MAIN = G.C.DYN_UI.MAIN = Panel; G.C.DYN_UI.BOSS_DARK = Panel.
 * Stake sprite O (0.5u×0.5u): B spacer until BlindArt is wired.
 * chips_text T: scale=0.85 (not scaled by local scale var), shadow=true, id='chip_UI_count'.
 */
private fun hudDollarsChips(s: RunState): UI {
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
                // Stake sprite O: B spacer until BlindArt.cache is wired (same 0.5u×0.5u footprint)
                B(Cfg(minw = 0.5f, minh = 0.5f)),
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
              colour = Balatro.Orange, onClick = onOptions),
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
 * Deferred: func='HUD_blind_visible' (always shown), func='blind_chip_UI_scale' (chip T stays at
 * scale=0.001 — essentially invisible until animated in), func='HUD_blind_reward' (always shown),
 * debuff func callbacks (empty strings). Animate flags (rotate, float, y_offset) on DynaText Os.
 */
private fun hudBlind(s: RunState, blindBmp: ImageBitmap?, stakeBmp: ImageBitmap?): UI {
    val panel = Balatro.Panel   // G.C.BLACK = G.C.DYN_UI.MAIN = G.C.DYN_UI.DARK = #374244
    val light = Balatro.White   // G.C.UI.TEXT_LIGHT

    // G.UIT.O blind sprite (1.5u x 1.5u after change_dim). When bitmap not yet loaded, a B spacer
    // of the same size preserves the layout so nothing shifts when art arrives.
    val blindO: UI = if (blindBmp != null)
        O(Cfg(), Spr(blindBmp, 1.5f, 1.5f))
    else
        B(Cfg(minw = 1.5f, minh = 1.5f))

    // G.UIT.O stake sprite (0.5u x 0.5u, colour=Chips tint). Same fallback pattern.
    val stakeO: UI = if (stakeBmp != null)
        O(Cfg(minw = 0.5f, minh = 0.5f, colour = Balatro.Chips), Spr(stakeBmp, 0.5f, 0.5f))
    else
        B(Cfg(minw = 0.5f, minh = 0.5f, colour = Balatro.Chips))

    return R(Cfg(align = "cm", minw = 4.5f, r = 0.1f, colour = panel, emboss = 0.05f, padding = 0.05f),
        // ── name strip (G.C.DYN_UI.MAIN = panel) ──────────────────────────────
        R(Cfg(align = "cm", minh = 0.7f, r = 0.1f, colour = panel, emboss = 0.05f),
            C(Cfg(align = "cm", minw = 3f),
                // DynaText: blind.loc_name — scale=1.6*0.4=0.64, shadow=true; animate flags deferred
                O(Cfg(),
                    DynaT(seg({ s.blindName }, light, scale = 0.64f), shadow = true))
            )
        ),
        // ── body panel (G.C.DYN_UI.DARK = panel) ──────────────────────────────
        R(Cfg(align = "cm", minh = 2.74f, r = 0.1f, colour = panel),
            // debuff rows — loc_debuff_lines[1] = boss description; [2] = second line (unused in vanilla)
            // HUD_blind_debuff_prefix T is always "" until the func system is wired
            R(Cfg(align = "cm", padding = 0.05f),
                R(Cfg(align = "cm", minh = 0.3f, maxw = 4.2f),
                    T(Cfg(scale = 0.36f, textColour = light), ""),              // HUD_blind_debuff_prefix
                    T(Cfg(scale = 0.36f, textColour = light), s.boss?.desc ?: "") // loc_debuff_lines[1]
                ),
                R(Cfg(align = "cm", minh = 0.3f, maxw = 4.2f),
                    T(Cfg(scale = 0.36f, textColour = light), "")               // loc_debuff_lines[2]
                )
            ),
            // blind sprite + chip-target card
            R(Cfg(align = "cm", padding = 0.15f),
                blindO,
                C(Cfg(align = "cm", r = 0.1f, padding = 0.05f, emboss = 0.05f, minw = 2.9f, colour = panel),
                    // "Score at least" — localize('ph_blind_score_at_least'), shadow=true per source
                    R(Cfg(align = "cm", maxw = 2.8f),
                        T(Cfg(scale = 0.3f, textColour = light, shadow = true), "Score at least")
                    ),
                    // stake sprite + 0.1u spacer + chip target (scale=0.001: blind_chip_UI_scale deferred)
                    R(Cfg(align = "cm", minh = 0.6f),
                        stakeO,
                        B(Cfg(minw = 0.1f, minh = 0.1f)),
                        // chip_text T: scale=0.001 intentional — pop-in animation deferred; shadow=true
                        T(Cfg(scale = 0.001f, textColour = Balatro.Mult, shadow = true), s.chipText)
                    ),
                    // reward row — func=HUD_blind_reward (always show until wired)
                    // "Reward: " has NO shadow per source; DynaText has shadow=true
                    R(Cfg(align = "cm", minh = 0.45f, maxw = 2.8f),
                        T(Cfg(scale = 0.3f, textColour = light), "Reward: "),
                        O(Cfg(),
                            DynaT(seg({ "\$${s.dollarsToBeEarned}" }, Balatro.Money, scale = 0.45f), shadow = true))
                    )
                )
            )
        )
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
    )

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
    )

    val sortCluster = C(
        Cfg(align = "cm", padding = 0.1f, r = 0.1f, colour = Color(0x22222222)),
        R(Cfg(align = "cm", padding = 0f),
            R(Cfg(align = "cm", padding = 0f),
                T(Cfg(scale = ts * 0.8f, textColour = Balatro.White), "Sort Hand")),
            R(Cfg(align = "cm", padding = 0.1f),
                C(Cfg(align = "cm", minh = 0.7f, minw = 0.9f, padding = 0.1f, r = 0.1f,
                    colour = Balatro.Orange, onClick = { s.sortHand(byRank = true) }),
                    T(Cfg(scale = ts * 0.7f, textColour = Balatro.White), "Rank")),
                C(Cfg(align = "cm", minh = 0.7f, minw = 0.9f, padding = 0.1f, r = 0.1f,
                    colour = Balatro.Orange, onClick = { s.sortHand(byRank = false) }),
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
@Composable
private fun RoundPlay(s: RunState, cells: Map<PlayingCard, ImageBitmap>, jokerCells: Map<String, ImageBitmap>) {
    LaunchedEffect(s.scoring) {
        if (s.scoring) {
            for (i in s.lastSteps.indices) { s.scoreStep(i); delay(if (i == 0) 140L else 300L) }
            delay(450L)
            s.scoreCommit()
        }
    }
    Column(Modifier.fillMaxSize()) {
        // jokers across the top
        Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
            s.owned.forEach { o ->
                jokerCells[o.offer.key]?.let { Image(it, o.offer.name, Modifier.padding(end = 4.dp).size(52.dp, 70.dp)) }
                    ?: Box(Modifier.padding(end = 4.dp).size(52.dp, 70.dp).clip(RoundedCornerShape(4.dp)).background(Balatro.FeltDark))
            }
        }

        // centre: hand name + chips X mult readout (Balatro's contents.hand through the UIBox
        // interpreter), and the played cards popping while scoring.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // contents.hand tree: always rendered — shows blank when idle, live during/after scoring
                RenderUI(hudHand(s))
                if (!s.scoring && s.lastResult == null) {
                    Spacer(Modifier.height(4.dp))
                    BTxt("select up to 5 cards, then Play", Balatro.White, 13.sp)
                }
                if (s.scoring) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        s.scoreCards.forEachIndexed { i, card ->
                            val active = i == s.popIndex
                            val popped = i <= s.popIndex
                            val sc by animateFloatAsState(if (active) 1.3f else if (popped) 1.05f else 0.9f,
                                spring(Spring.DampingRatioMediumBouncy, 520f), label = "pop$i")
                            val lf by animateFloatAsState(if (active) -20f else 0f, spring(Spring.DampingRatioMediumBouncy, 520f), label = "pl$i")
                            Box(Modifier.padding(horizontal = 2.dp).graphicsLayer { scaleX = sc; scaleY = sc; translationY = lf }) {
                                cells[card]?.let { Image(it, card.label, Modifier.size(44.dp, 60.dp).clip(RoundedCornerShape(5.dp))) }
                            }
                        }
                    }
                }
            }
        }

        // bottom: fanned hand + action bar (Play Hand / Sort / Discard)
        // The action bar is Balatro's create_UIBox_buttons tree rendered through the UIBox interpreter.
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    SpringHand(s.hand, s.selected, enabled = !s.scoring, cardWidth = 56.dp, onToggle = { s.toggle(it) }) { card ->
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Balatro.FeltDark)) {
                            cells[card]?.let { Image(it, card.label, Modifier.fillMaxSize()) }
                            if (card.enhancement != Enhancement.NONE) BTxt(card.enhancement.badge, Balatro.White, 8.sp,
                                Modifier.align(Alignment.TopStart).background(Balatro.Orange).padding(horizontal = 2.dp))
                            if (card.seal != Seal.NONE) BTxt(card.seal.badge, Balatro.Ink, 8.sp,
                                Modifier.align(Alignment.TopEnd).background(Balatro.Gold).padding(horizontal = 2.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // Balatro's create_UIBox_buttons: play (left), sort cluster (centre), discard (right).
            // Guards map to config.func='can_play'/'can_discard': onClick=null disables the button.
            RenderUI(buttonsRow(s, cells))
            BTxt("deck ${s.deckRemaining}", Balatro.White, 10.sp, Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp))
        }
    }
}

@Composable
private fun ShopPhase(s: RunState, jokerCells: Map<String, ImageBitmap>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BTxt("Shop", Balatro.Orange, 20.sp)
        Spacer(Modifier.weight(1f))
        Pill("\$${s.money}", "", Balatro.Money)
    }
    Spacer(Modifier.height(8.dp))
    for (offer in s.shop) {
        Panel(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                jokerCells[offer.key]?.let { Image(it, offer.name, Modifier.size(44.dp, 60.dp)); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f)) {
                    BTxt(offer.name, Balatro.White, 14.sp)
                    BTxt(offer.desc, Balatro.Green, 11.sp)
                }
                BButton("\$${offer.cost}", Balatro.Money, enabled = s.money >= offer.cost) { s.buy(offer) }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    BTxt("Planets — level a hand", Balatro.Chips, 13.sp)
    for (po in s.shopPlanets) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BTxt("${po.planet.display} → ${handName(po.planet.hand)}", Balatro.White, 13.sp)
                BTxt("now Lv${s.handLevel(po.planet.hand)}", Balatro.Green, 11.sp)
            }
            BButton("\$${po.cost}", Balatro.Chips, enabled = s.money >= po.cost) { s.buyPlanet(po) }
        }
    }

    Spacer(Modifier.height(8.dp))
    BTxt("Tarots — enhance a card (${s.enhancedCount})", Balatro.Mult, 13.sp)
    for (t in s.shopTarots) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BTxt(t.name, Balatro.White, 13.sp)
                val effect = if (t.seal != Seal.NONE) "${t.seal.name.lowercase()} seal" else t.enhancement.name.lowercase()
                BTxt("random card → $effect", Balatro.Green, 11.sp)
            }
            BButton("\$${t.cost}", Balatro.Mult, enabled = s.money >= t.cost) { s.buyTarot(t) }
        }
    }

    Spacer(Modifier.height(8.dp))
    BTxt("Sell jokers", Balatro.White, 12.sp)
    for (o in s.owned) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            BTxt(o.offer.name, Balatro.White, 13.sp, Modifier.weight(1f))
            BButton("Sell \$${maxOf(1, o.offer.cost / 2)}", Balatro.Grey, enabled = s.owned.size > 1) { s.sell(o) }
        }
    }
    Spacer(Modifier.height(12.dp))
    BButton("Next  →  ${s.blindName} (Ante ${s.ante})", Balatro.Green, modifier = Modifier.fillMaxWidth()) { s.nextBlind() }
}

/**
 * Port of create_UIBox_blind_select (UI_definitions.lua:1417).
 * Shows three blind-choice columns (Small, Big, Boss) in a horizontal row;
 * tapping a blind calls s.selectBlind() to start the round.
 *
 * Deferred: G.blind_prompt_box DynaText prompt ("ph_choose_blind_1/2"), blind_tag extras,
 * AnimatedSprite for each blind (B placeholder holds the 1.4u×1.4u footprint),
 * disabled state, run_info view, reroll-boss voucher button.
 */
@Composable
private fun BlindSelectScreen(s: RunState) {
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BTxt("Choose Blind", Balatro.White, 20.sp)
        Spacer(Modifier.height(12.dp))
        // R(align="cm", padding=0.5) containing up to 3 O(UIBox{blind_choice}) nodes
        // Rendered as a horizontal row since each blind card is a C column node.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Top
        ) {
            for (slotIdx in 0..2) {
                Box(Modifier.weight(1f)) {
                    RenderUI(blindChoiceCard(s, slotIdx) { s.selectBlind() })
                }
            }
        }
    }
}

/**
 * Port of create_UIBox_blind_choice (UI_definitions.lua:1485) for one blind slot.
 * slotIdx: 0=Small, 1=Big, 2=Boss. onSelect called when the "Select" button is tapped.
 *
 * Structure (simplified — faithfully follows source tree shape):
 *   R(align="tm", minh=10, r=0.1, padding=0.05)        ← outer card
 *     R(align="cm", colour=darkPanel, r=0.1)
 *       R(align="cm", padding=0.2)
 *         R[select button](align="cm", colour=ORANGE, minh=0.6, minw=2.7, r=0.1, shadow, onClick)
 *           T(blindState label, scale=0.45, TEXT_LIGHT)
 *         R[blind_name](align="cm", padding=0.07)
 *           R(align="cm", r=0.1, outline_colour=blindCol, colour=darken(blindCol), minw=2.9, emboss=0.1)
 *             O(DynaT(blindName, WHITE, scale=0.45, maxw=2.8))
 *         R[blind_desc](align="cm", padding=0.05)
 *           R(align="cm", minh=1.5)   ← blind animation placeholder (B)
 *           R(align="cm", minh=0.7, minw=2.9)  ← description lines
 *             R(maxw=2.8) T(desc line 1, scale=0.32, WHITE)
 *         R[score_target](align="cm", r=0.1, colour=BLACK, minw=3.1, emboss=0.05)
 *           R(maxw=3) T("Score at least", scale=0.3, WHITE)
 *           R(minh=0.6) B(stake_placeholder) B(0.1) T(amount, scale=0.9*sc, RED)
 *           R T("Reward: ", WHITE) T("$$$+", MONEY, scale=0.35)
 *
 * Deferred: AnimatedSprite, debuff prefix T func, outline rendering, float animation on DynaText.
 */
private fun blindChoiceCard(s: RunState, slotIdx: Int, onSelect: () -> Unit): UI {
    val light = Balatro.White
    val darkPanel = Color(0xFF1A2526)     // mix(BLACK, L_BLACK, 0.5) ≈ panel darker than Panel
    val blindCol = when (slotIdx) { 0 -> Balatro.Chips; 1 -> Balatro.Orange; else -> Balatro.Mult }
    // darken(blindCol, 0.3): approximate by mixing with BLACK at 0.3 weight
    val blindColDark = when (slotIdx) {
        0 -> Color(0xFF006BB2); 1 -> Color(0xFFB26C00); else -> Color(0xFFB24139)
    }
    val blindName = s.nameForSlot(slotIdx)
    val blindDesc = s.descForSlot(slotIdx)
    val amount = s.targetForSlot(slotIdx)
    val reward = s.rewardForSlot(slotIdx)
    val dollarStr = "$".repeat(reward) + "+"

    return R(Cfg(align = "tm", minh = 10f, r = 0.1f, padding = 0.05f),
        R(Cfg(align = "cm", colour = darkPanel, r = 0.1f),
            R(Cfg(align = "cm", padding = 0.2f),
                // ── select button ──
                R(Cfg(align = "cm", colour = Balatro.Orange, minh = 0.6f, minw = 2.7f,
                      padding = 0.07f, r = 0.1f, emboss = 0.05f, onClick = onSelect),
                    T(Cfg(scale = 0.45f, textColour = light, shadow = true), "Select")),
                // ── blind name band ──
                R(Cfg(align = "cm", padding = 0.07f),
                    R(Cfg(align = "cm", r = 0.1f, colour = blindColDark,
                          minw = 2.9f, emboss = 0.1f, padding = 0.07f),
                        O(Cfg(), DynaT(seg({ blindName }, light, scale = 0.45f), shadow = true)))),
                // ── blind art + description ──
                R(Cfg(align = "cm", padding = 0.05f),
                    R(Cfg(align = "cm"),
                        // Blind animation sprite placeholder (AnimatedSprite deferred: B holds 1.4u×1.4u)
                        R(Cfg(align = "cm", minh = 1.5f),
                            B(Cfg(minw = 1.4f, minh = 1.4f, colour = blindCol))),
                        if (blindDesc.isNotEmpty())
                            R(Cfg(align = "cm", minh = 0.7f, padding = 0.05f, minw = 2.9f),
                                R(Cfg(align = "cm", maxw = 2.8f),
                                    T(Cfg(scale = 0.32f, textColour = light, shadow = true), blindDesc)))
                        else
                            B(Cfg(minw = 0.1f, minh = 0.1f)))),
                // ── score target panel ──
                R(Cfg(align = "cm", r = 0.1f, padding = 0.05f, minw = 3.1f,
                      colour = Balatro.Panel, emboss = 0.05f),
                    R(Cfg(align = "cm", maxw = 3f),
                        T(Cfg(scale = 0.3f, textColour = light, shadow = true), "Score at least")),
                    R(Cfg(align = "cm", minh = 0.6f),
                        // stake sprite placeholder
                        B(Cfg(minw = 0.5f, minh = 0.5f, colour = Balatro.Chips)),
                        B(Cfg(minw = 0.1f, minh = 0.1f)),
                        T(Cfg(scale = 0.9f, textColour = Balatro.Mult, shadow = true), fmtR(amount))),
                    R(Cfg(align = "cm"),
                        T(Cfg(scale = 0.35f, textColour = light, shadow = true), "Reward: "),
                        T(Cfg(scale = 0.35f, textColour = Balatro.Money, shadow = true), dollarStr))))))
}

private fun fmtR(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
