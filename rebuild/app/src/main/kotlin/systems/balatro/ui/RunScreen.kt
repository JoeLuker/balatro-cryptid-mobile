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
private enum class Phase { ROUND, SHOP, OVER }
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

    /** Mirrors G.GAME.blind.chip_text — the chip target as a formatted string for the HUD_blind T node.
     *  scale=0.001 in the source means it starts invisible; blind_chip_UI_scale animates it in (deferred).
     *  We render at scale=0.001 faithfully; the string itself must still be correct. */
    val chipText: String get() = fmtR(target)

    /** Mirrors G.GAME.current_round.dollars_to_be_earned — reward payout shown on the blind panel.
     *  Balatro formula: 4 + hands_left (+ gold cards, deferred). Matches scoreCommit's reward calc. */
    val dollarsToBeEarned: Int get() = 4 + handsLeft

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

    fun nextBlind() { if (phase == Phase.SHOP) startRound() }

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
    val animRound by animateFloatAsState(s.roundScore.toFloat(), tween(700, easing = FastOutSlowInEasing), label = "round")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BButton("✕", Balatro.Mult) { onClose() }
            Spacer(Modifier.weight(1f))
            BTxt("Ante ${s.ante}/8", Balatro.White, 12.sp)
        }
        // Blind token + target: Balatro's create_UIBox_HUD_blind tree through the UIBox interpreter.
        // blindBmp/stakeBmp are null until BlindArt.cache is wired; B spacers hold the layout.
        RenderUI(hudBlind(s, blindBmp = null, stakeBmp = null))
        // round score
        Panel(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BTxt("Round score", Balatro.White, 9.sp)
                BTxt(fmtR(animRound.toDouble()), Balatro.White, 26.sp)
            }
        }
        // Balatro's actual HUD stat tree (create_UIBox_HUD), rendered through the UIBox interpreter
        RenderUI(hudRound(s))
    }
}


/**
 * Port of create_UIBox_HUD's `contents.round` tree (UI_definitions.lua): Hands/Discards row,
 * Money, Ante/Round row — each stat a column (outer box) with a light label over a dark inset
 * holding the coloured value. This is DATA (Balatro's tree), rendered by the generic interpreter.
 */
private fun hudRound(s: RunState): UI {
    val tc = Balatro.Panel; val tc2 = Balatro.FeltDark; val light = Balatro.White
    val sp = 0.13f
    // a stat box: outer column (label over value). The value is a live-bound DynaText O node — the
    // faithful port of UI_definitions.lua's HUD counters ({n=UIT.O, object=DynaText({ref_table=…})}).
    // `value` is a provider so the binding stays live: reading RunState's mutableStateOf inside it
    // makes Compose recompose on change, exactly as Balatro's update_text polls ref_table/ref_value.
    fun stat(label: String, value: () -> String, color: Color): UI =
        C(Cfg(align = "cm", padding = 0.05f, minw = 1.45f, minh = 1f, colour = tc, emboss = 0.05f, r = 0.1f),
            R(Cfg(align = "cm", minh = 0.33f, maxw = 1.35f), T(Cfg(scale = 0.34f, textColour = light), label)),
            R(Cfg(align = "cm", r = 0.1f, minw = 1.2f, colour = tc2),
                O(Cfg(align = "cm"), DynaT(seg(value, color, scale = 0.8f)))))
    fun vSpace() = R(Cfg(minh = sp))          // Balatro's vertical spacers are R nodes
    fun hSpace() = C(Cfg(minw = sp))          // ...horizontal spacers are C nodes
    return C(Cfg(align = "cm"),               // all children are R -> stacks vertically
        R(Cfg(align = "cm"), stat("Hands", { "${s.handsLeft}" }, Balatro.Chips), hSpace(), stat("Discards", { "${s.discardsLeft}" }, Balatro.Mult)),
        vSpace(),
        R(Cfg(align = "cm"),
            C(Cfg(align = "cm", padding = 0.05f, minw = 1.45f * 2 + sp, minh = 1f, colour = tc, emboss = 0.05f, r = 0.1f),
                C(Cfg(align = "cm", r = 0.1f, minw = 1.28f * 2 + sp, minh = 0.9f, colour = tc2),
                    O(Cfg(align = "cm"), DynaT(seg({ "\$${s.money}" }, Balatro.Money, scale = 0.88f)))))),
        vSpace(),
        R(Cfg(align = "cm"), stat("Ante", { "${s.ante}/8" }, Balatro.Orange), hSpace(), stat("Round", { "${s.blindIndex + 1}" }, Balatro.Orange)))
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

        // centre: hand name + chips X mult, and the played cards popping while scoring
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (s.scoring) {
                    ScoreReadout(handName(s.lastResult?.handType ?: HandType.NONE), fmtR(s.displayChips), fmtR(s.displayMult))
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
                } else s.lastResult?.let { r ->
                    ScoreReadout(handName(r.handType), fmtR(r.chips), fmtR(r.mult))
                } ?: BTxt("select up to 5 cards, then Play", Balatro.White, 13.sp)
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

private fun fmtR(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
