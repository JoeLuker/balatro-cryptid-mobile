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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import systems.balatro.bridge.Telemetry
import systems.balatro.content.Content
import systems.balatro.engine.World
import systems.balatro.game.*

/**
 * A real blind: a seeded deck, a target, hands and discards. Each Play scores the selected
 * cards through the SAME engine instance for the whole round — so the jokers' state persists
 * (krusty's x_mult climbs hand over hand, the composition-state architecture made visible).
 * Beat the target before hands run out. This is the engine turned into a game.
 */
private val ROUND_LOADOUT = listOf(
    Triple("j_joker", "Joker", "+4 Mult"),
    Triple("j_cry_krustytheclown", "Krusty the Clown", "x_mult +0.02 per scored card — PERSISTS across hands"),
    Triple("j_cry_cube", "Cube", "+6 Chips"),
)
private const val TARGET = 300.0

@Composable
fun RoundScreen(onClose: () -> Unit) {
    var roundNo by remember { mutableStateOf(0) }
    key(roundNo) { RoundContent(onClose = onClose, onNewRound = { roundNo++ }, seed = (roundNo + 1).toLong()) }
}

@Composable
private fun RoundContent(onClose: () -> Unit, onNewRound: () -> Unit, seed: Long) {
    val ctx = LocalContext.current
    // Engine + deck live for the WHOLE round (jokers accumulate, deck draws down).
    val engine = remember {
        val w = World(); val e = Effects()
        Content.loadout(w, e, ROUND_LOADOUT.map { it.first })
        Triple(w, e, Deck(seed))
    }
    val world = engine.first; val effects = engine.second; val deck = engine.third
    val run = remember { ScoreRun(effects) }

    var hand by remember { mutableStateOf(deck.draw(8)) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var score by remember { mutableStateOf(0.0) }
    var handsLeft by remember { mutableStateOf(4) }
    var discardsLeft by remember { mutableStateOf(3) }
    var status by remember { mutableStateOf("PLAYING") }
    var lastResult by remember { mutableStateOf<ScoreResult?>(null) }
    var lastSteps by remember { mutableStateOf<List<ScoreStep>>(emptyList()) }

    LaunchedEffect(seed) { Telemetry.event("ROUND_START", "seed" to seed, "target" to TARGET) }

    val allCards = remember { Suit.values().flatMap { s -> (2..14).map { PlayingCard(s, it) } } }
    val cells by produceState<Map<PlayingCard, ImageBitmap>>(emptyMap()) {
        value = withContext(Dispatchers.Default) { CardArt.cache(ctx, allCards) }
    }
    val jokerCells by produceState<Map<String, ImageBitmap>>(emptyMap()) {
        value = withContext(Dispatchers.Default) { JokerArt.cache(ctx, ROUND_LOADOUT.map { it.first }) }
    }

    fun refill() {
        val keep = hand.filterIndexed { i, _ -> i !in selected }
        hand = keep + deck.draw(8 - keep.size)
        selected = emptySet()
    }
    fun play() {
        if (status != "PLAYING" || selected.isEmpty()) return
        val sel = hand.filterIndexed { i, _ -> i in selected }
        val trace = ArrayList<ScoreStep>()
        val r = run.scoreDetailed(world, sel, trace)
        score += r.score; handsLeft -= 1
        lastResult = r; lastSteps = trace
        Telemetry.event("ROUND_HAND", "cards" to sel.joinToString("") { it.key },
            "type" to r.handType, "score" to r.score, "total" to score, "handsLeft" to handsLeft)
        refill()
        if (score >= TARGET) { status = "WON"; Telemetry.event("ROUND_WIN", "total" to score, "handsLeft" to handsLeft) }
        else if (handsLeft <= 0) { status = "LOST"; Telemetry.event("ROUND_LOSE", "total" to score) }
    }
    fun discard() {
        if (status != "PLAYING" || selected.isEmpty() || discardsLeft <= 0) return
        discardsLeft -= 1
        Telemetry.event("ROUND_DISCARD", "n" to selected.size, "discardsLeft" to discardsLeft)
        refill()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Small Blind", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }

        // blind progress
        Spacer(Modifier.height(8.dp))
        Text("score ${fmtD(score)} / $TARGET", fontFamily = FontFamily.Monospace, fontSize = 16.sp,
            color = if (score >= TARGET) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        LinearProgressIndicator(
            progress = { (score / TARGET).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).padding(top = 4.dp))
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Hands  $handsLeft", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.width(20.dp))
            Text("Discards  $discardsLeft", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }

        // joker board strip (real art, LOD)
        Spacer(Modifier.height(12.dp))
        Text("Joker board", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val n = ROUND_LOADOUT.size
            val w = minOf(76.dp, (maxWidth - 6.dp * (n - 1).toFloat()) / n.toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (j in ROUND_LOADOUT) {
                    jokerCells[j.first]?.let { Image(it, j.second, Modifier.size(w, w * (190f / 142f))) }
                        ?: Box(Modifier.size(w, w * (190f / 142f)).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }

        // hand
        Spacer(Modifier.height(14.dp))
        Text("Hand — tap to select (${selected.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(hand) { i, card ->
                val isSel = i in selected
                Box(Modifier.clip(RoundedCornerShape(6.dp))
                    .border(if (isSel) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    .clickable(enabled = status == "PLAYING") { selected = if (isSel) selected - i else selected + i }
                    .padding(3.dp)) {
                    cells[card]?.let { Image(it, card.label, Modifier.size(58.dp, 78.dp).alpha(if (isSel) 1f else 0.82f)) }
                        ?: Box(Modifier.size(58.dp, 78.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        if (status == "PLAYING") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { play() }, enabled = selected.isNotEmpty() && cells.isNotEmpty(),
                    modifier = Modifier.weight(1f)) { Text("Play Hand") }
                OutlinedButton(onClick = { discard() }, enabled = selected.isNotEmpty() && discardsLeft > 0,
                    modifier = Modifier.weight(1f)) { Text("Discard ($discardsLeft)") }
            }
        } else {
            val won = status == "WON"
            Text(if (won) "Blind beaten!" else "Out of hands — blind not beaten",
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = if (won) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onNewRound, modifier = Modifier.fillMaxWidth()) { Text("New Round") }
        }

        // last hand's cascade
        Spacer(Modifier.height(16.dp))
        lastResult?.let { r ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("last hand · ${handName(r.handType)}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    for (s in lastSteps) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(s.label, fontSize = 12.sp, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${fmtD(s.chips)} × ${fmtD(s.mult)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                    Text("+ ${fmtD(r.score)}", fontFamily = FontFamily.Monospace, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun fmtD(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
