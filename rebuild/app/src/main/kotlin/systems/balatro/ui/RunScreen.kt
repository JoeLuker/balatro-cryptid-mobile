package systems.balatro.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
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

private val CATALOG = listOf(
    Offer("j_joker", "Joker", "+4 Mult", 2),
    Offer("j_greedy_joker", "Greedy Joker", "+3 Mult / Diamond", 5),
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

/** Compose-observable run state; mutations drive recomposition. The engine is persistent. */
private class RunState {
    val world = World()
    val effects = Effects()
    private val scorer = ScoreRun(effects)

    var money by mutableStateOf(4)
    var blind by mutableStateOf(1)
    var phase by mutableStateOf(Phase.ROUND)
    val owned = mutableStateListOf<Owned>()
    var shop by mutableStateOf<List<Offer>>(emptyList())

    private var deck = Deck(1L)
    var hand by mutableStateOf<List<PlayingCard>>(emptyList())
    var selected by mutableStateOf(setOf<Int>())
    var roundScore by mutableStateOf(0.0)
    var handsLeft by mutableStateOf(HANDS)
    var discardsLeft by mutableStateOf(DISCARDS)
    var lastResult by mutableStateOf<ScoreResult?>(null)
    var lastSteps by mutableStateOf<List<ScoreStep>>(emptyList())

    val target: Double get() = 300.0 * blind

    init {
        Telemetry.event("RUN_START")
        buy(Offer("j_joker", "Joker", "+4 Mult", 0), free = true)   // start with a Joker
        startRound()
    }

    private fun startRound() {
        deck = Deck(blind * 7919L)        // deterministic per blind
        hand = deck.draw(8); selected = emptySet()
        roundScore = 0.0; handsLeft = HANDS; discardsLeft = DISCARDS
        lastResult = null; lastSteps = emptyList()
        phase = Phase.ROUND
        Telemetry.event("ROUND_START", "blind" to blind, "target" to target)
    }

    private fun refill() {
        val keep = hand.filterIndexed { i, _ -> i !in selected }
        hand = keep + deck.draw(8 - keep.size)
        selected = emptySet()
    }

    fun toggle(i: Int) { if (phase == Phase.ROUND) selected = if (i in selected) selected - i else selected + i }

    fun play() {
        if (phase != Phase.ROUND || selected.isEmpty()) return
        val sel = hand.filterIndexed { i, _ -> i in selected }
        val trace = ArrayList<ScoreStep>()
        val r = scorer.scoreDetailed(world, sel, trace)
        roundScore += r.score; handsLeft -= 1
        lastResult = r; lastSteps = trace
        Telemetry.event("ROUND_HAND", "blind" to blind, "type" to r.handType, "score" to r.score, "total" to roundScore)
        refill()
        if (roundScore >= target) {
            val reward = 4 + handsLeft
            money += reward
            Telemetry.event("ROUND_WIN", "blind" to blind, "total" to roundScore, "reward" to reward)
            blind += 1
            shop = rollShop(blind)
            phase = Phase.SHOP
        } else if (handsLeft <= 0) {
            phase = Phase.OVER
            Telemetry.event("ROUND_LOSE", "blind" to blind, "total" to roundScore)
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ante ${s.blind}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text("$${s.money}", fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }

        // owned joker board (real art)
        Spacer(Modifier.height(8.dp))
        Text("Jokers (${s.owned.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val n = maxOf(1, s.owned.size)
            val w = minOf(68.dp, (maxWidth - 6.dp * (n - 1).toFloat()) / n.toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (o in s.owned) {
                    jokerCells[o.offer.key]?.let { Image(it, o.offer.name, Modifier.size(w, w * (190f / 142f))) }
                        ?: Box(Modifier.size(w, w * (190f / 142f)).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        when (s.phase) {
            Phase.ROUND -> RoundPhase(s, cells)
            Phase.SHOP -> ShopPhase(s, jokerCells)
            Phase.OVER -> {
                Text("Run over — lost Ante ${s.blind}", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("New Run") }
            }
        }
    }
}

@Composable
private fun RoundPhase(s: RunState, cells: Map<PlayingCard, ImageBitmap>) {
    Text("Blind target ${fmtR(s.target)}  ·  score ${fmtR(s.roundScore)}", fontFamily = FontFamily.Monospace, fontSize = 15.sp)
    LinearProgressIndicator(
        progress = { (s.roundScore / s.target).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 4.dp))
    Spacer(Modifier.height(6.dp))
    Row { Text("Hands ${s.handsLeft}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.width(18.dp)); Text("Discards ${s.discardsLeft}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }

    Spacer(Modifier.height(12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(s.hand) { i, card ->
            val isSel = i in s.selected
            Box(Modifier.clip(RoundedCornerShape(6.dp))
                .border(if (isSel) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                .clickable { s.toggle(i) }.padding(3.dp)) {
                cells[card]?.let { Image(it, card.label, Modifier.size(56.dp, 76.dp).alpha(if (isSel) 1f else 0.82f)) }
                    ?: Box(Modifier.size(56.dp, 76.dp).background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { s.play() }, enabled = s.selected.isNotEmpty() && cells.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Play") }
        OutlinedButton(onClick = { s.discard() }, enabled = s.selected.isNotEmpty() && s.discardsLeft > 0, modifier = Modifier.weight(1f)) { Text("Discard") }
    }
    s.lastResult?.let { r ->
        Spacer(Modifier.height(12.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("last · ${handName(r.handType)}  +${fmtR(r.score)}", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ShopPhase(s: RunState, jokerCells: Map<String, ImageBitmap>) {
    Text("Shop · $${s.money}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
    Spacer(Modifier.height(8.dp))
    for (offer in s.shop) {
        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                jokerCells[offer.key]?.let { Image(it, offer.name, Modifier.size(46.dp, 62.dp)); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(offer.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(offer.desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { s.buy(offer) }, enabled = s.money >= offer.cost) { Text("$${offer.cost}") }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("Your jokers — sell for half", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    for (o in s.owned) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(o.offer.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { s.sell(o) }, enabled = s.owned.size > 1) { Text("Sell $${maxOf(1, o.offer.cost / 2)}") }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = { s.nextBlind() }, modifier = Modifier.fillMaxWidth()) { Text("Next Blind  (Ante ${s.blind})") }
}

private fun fmtR(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
