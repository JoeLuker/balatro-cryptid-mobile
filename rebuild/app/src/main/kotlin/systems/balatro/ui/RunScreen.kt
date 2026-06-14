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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BTxt("Ante ${s.ante}", Balatro.White, 18.sp)
                Spacer(Modifier.width(8.dp))
                BTxt(s.blindName, Balatro.Orange, 15.sp)
                Spacer(Modifier.weight(1f))
                Pill("\$${s.money}", "", Balatro.Money)
                Spacer(Modifier.width(8.dp))
                BButton("X", Balatro.Mult) { onClose() }
            }

            // owned jokers on the felt (LOD: shrink to fit)
            Spacer(Modifier.height(10.dp))
            BTxt("Jokers ${s.owned.size}", Balatro.White, 12.sp)
            Spacer(Modifier.height(4.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val n = maxOf(1, s.owned.size)
                val w = minOf(64.dp, (maxWidth - 6.dp * (n - 1).toFloat()) / n.toFloat())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in s.owned) {
                        jokerCells[o.offer.key]?.let { Image(it, o.offer.name, Modifier.size(w, w * (190f / 142f))) }
                            ?: Box(Modifier.size(w, w * (190f / 142f)).clip(RoundedCornerShape(4.dp)).background(Balatro.FeltDark))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            when (s.phase) {
                Phase.ROUND -> RoundPhase(s, cells)
                Phase.SHOP -> ShopPhase(s, jokerCells)
                Phase.OVER -> {
                    Panel(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            BTxt("Game Over", Balatro.Mult, 22.sp)
                            BTxt("lost ${s.blindName} · Ante ${s.ante}", Balatro.White, 13.sp)
                            Spacer(Modifier.height(10.dp))
                            BButton("New Run", Balatro.Orange, modifier = Modifier.fillMaxWidth()) { onRestart() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundPhase(s: RunState, cells: Map<PlayingCard, ImageBitmap>) {
    // The scoring sequence: walk the cascade trace on a timer so chips/mult tick up and the
    // played cards pop one at a time, then bank the score.
    LaunchedEffect(s.scoring) {
        if (s.scoring) {
            val steps = s.lastSteps
            for (i in steps.indices) { s.scoreStep(i); delay(if (i == 0) 140L else 300L) }
            delay(450L)
            s.scoreCommit()
        }
    }
    val animRound by animateFloatAsState(s.roundScore.toFloat(), tween(700, easing = FastOutSlowInEasing), label = "round")

    Panel(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BTxt(s.blindName, Balatro.Orange, 16.sp)
                Spacer(Modifier.weight(1f))
                BTxt("score at least ", Balatro.White, 11.sp)
                BTxt(fmtR(s.target), Balatro.Chips, 18.sp)
            }
            s.boss?.let { BTxt("⚠ ${it.display}: ${it.desc}", Balatro.Mult, 11.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BTxt("Round score", Balatro.White, 11.sp)
            BTxt(fmtR(animRound.toDouble()), Balatro.White, 30.sp)   // ticks up after a hand banks
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("${s.handsLeft}", "Hands", Balatro.Chips)
        Pill("${s.discardsLeft}", "Discards", Balatro.Mult)
        Pill("\$${s.money}", "Money", Balatro.Money)
    }

    // the chips X mult readout — live count-up while scoring, last result otherwise
    Spacer(Modifier.height(12.dp))
    if (s.scoring) {
        ScoreReadout(handName(s.lastResult?.handType ?: HandType.NONE), fmtR(s.displayChips), fmtR(s.displayMult), Modifier.fillMaxWidth())
        // the played cards, popping one by one as the cascade resolves
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            s.scoreCards.forEachIndexed { i, card ->
                val active = i == s.popIndex
                val popped = i <= s.popIndex
                val scale by animateFloatAsState(if (active) 1.3f else if (popped) 1.04f else 0.9f,
                    spring(Spring.DampingRatioMediumBouncy, 520f), label = "pop$i")
                val lift by animateFloatAsState(if (active) -22f else 0f, spring(Spring.DampingRatioMediumBouncy, 520f), label = "poplift$i")
                Box(Modifier.padding(horizontal = 2.dp).graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift }) {
                    cells[card]?.let { Image(it, card.label, Modifier.size(46.dp, 62.dp).clip(RoundedCornerShape(5.dp))) }
                        ?: Box(Modifier.size(46.dp, 62.dp).clip(RoundedCornerShape(5.dp)).background(Balatro.FeltDark))
                }
            }
        }
    } else s.lastResult?.let { r ->
        ScoreReadout(handName(r.handType), fmtR(r.chips), fmtR(r.mult), Modifier.fillMaxWidth())
    }

    // the hand — alive (wobble + spring select). Frozen during the scoring sequence.
    Spacer(Modifier.height(12.dp))
    LazyRow(
        Modifier.fillMaxWidth().height(126.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        itemsIndexed(s.hand) { i, card ->
            JuicyCard(cells[card], card.label, i in s.selected, i, 62.dp, onClick = { if (!s.scoring) s.toggle(i) }) {
                if (card.enhancement != Enhancement.NONE) {
                    BTxt(card.enhancement.badge, Balatro.White, 9.sp,
                        Modifier.align(Alignment.TopStart).background(Balatro.Orange).padding(horizontal = 2.dp))
                }
                if (card.seal != Seal.NONE) {
                    BTxt(card.seal.badge, Balatro.Ink, 9.sp,
                        Modifier.align(Alignment.TopEnd).background(Balatro.Gold).padding(horizontal = 2.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BButton("Play Hand", Balatro.Chips, enabled = !s.scoring && s.selected.isNotEmpty() && cells.isNotEmpty(), modifier = Modifier.weight(1f)) { s.play() }
        BButton("Discard", Balatro.Mult, enabled = !s.scoring && s.selected.isNotEmpty() && s.discardsLeft > 0, modifier = Modifier.weight(1f)) { s.discard() }
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
