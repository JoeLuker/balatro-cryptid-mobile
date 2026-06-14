package systems.balatro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
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
 * Card art from the REUSED 8BitDeck atlas (1846x760, 13 ranks x 4 suits, cell 142x190).
 * Mapping is authoritative from Balatro's P_CARDS: col = rank-2, row = suit (H,C,D,S).
 * Cells are decoded+cropped ONCE off the main thread, same cache pattern as the joker board.
 */
object CardArt {
    private fun cell(c: PlayingCard): Pair<Int, Int> {
        val col = c.rank - 2                                  // 2->0 ... T->8, J->9, Q->10, K->11, A->12
        val row = when (c.suit) { Suit.H -> 0; Suit.C -> 1; Suit.D -> 2; Suit.S -> 3 }
        return col to row
    }

    fun cache(ctx: Context, cards: List<PlayingCard>): Map<PlayingCard, ImageBitmap> {
        val atlas = try {
            ctx.assets.open("textures/8BitDeck.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString()); return emptyMap() }
        val out = HashMap<PlayingCard, ImageBitmap>()
        for (c in cards.toSet()) {
            val (col, row) = cell(c)
            out[c] = Bitmap.createBitmap(atlas, col * 142, row * 190, 142, 190).asImageBitmap()
        }
        return out
    }
}

/** A small Cryptid loadout for the lab, shown as readable chips. Keys resolve via Content.byKey. */
private data class JokerChip(val key: String, val label: String, val desc: String)
private val DEMO_LOADOUT = listOf(
    JokerChip("j_joker", "Joker", "+4 Mult"),
    JokerChip("j_cry_triplet_rhythm", "Triplet Rhythm", "x3 Mult if exactly three 3s"),
    JokerChip("j_cry_lightupthenight", "Light Up the Night", "x1.5 Mult per scored 2 or 7"),
)

/**
 * Scoring Lab: deal a hand from reused art, tap up to 5 cards, Play -> the REAL ported
 * engine (Content jokers through ScoreRun) resolves the cascade and shows chips x mult =
 * score. Every play phones home a PLAY event. This is the proven engine made interactive.
 */
@Composable
fun ScoringLab(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val hand = remember { PlayingCard.hand("S_3", "H_3", "D_3", "S_2", "H_7", "D_A", "C_K", "S_9") }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var result by remember { mutableStateOf<ScoreResult?>(null) }
    var steps by remember { mutableStateOf<List<ScoreStep>>(emptyList()) }

    val cells by produceState<Map<PlayingCard, ImageBitmap>>(emptyMap(), hand) {
        value = withContext(Dispatchers.Default) { CardArt.cache(ctx, hand) }
    }

    fun play() {
        val sel = hand.filterIndexed { i, _ -> i in selected }
        if (sel.isEmpty()) return
        val world = World(); val effects = Effects()
        Content.loadout(world, effects, DEMO_LOADOUT.map { it.key })
        val trace = ArrayList<ScoreStep>()
        val r = ScoreRun(effects).scoreDetailed(world, sel, trace)
        result = r; steps = trace
        Telemetry.event("PLAY", "cards" to sel.joinToString("") { it.key } ,
            "type" to r.handType, "chips" to r.chips, "mult" to r.mult, "score" to r.score)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scoring Lab", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        Text("real ported engine · reused card art · ${DEMO_LOADOUT.size} jokers active",
            color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)

        Spacer(Modifier.height(12.dp))
        Text("Jokers", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        for (j in DEMO_LOADOUT) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(j.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.width(130.dp))
                Text(j.desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Hand — tap up to 5 (selected = ${selected.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(hand) { i, card ->
                val isSel = i in selected
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(if (isSel) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                        .clickable {
                            selected = if (isSel) selected - i
                                else if (selected.size < 5) selected + i else selected
                        }
                        .padding(3.dp)) {
                    cells[card]?.let {
                        Image(it, card.label, Modifier.size(58.dp, 78.dp).alpha(if (isSel) 1f else 0.82f))
                    } ?: Box(Modifier.size(58.dp, 78.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { play() }, enabled = selected.isNotEmpty() && cells.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()) {
            Text(if (cells.isEmpty()) "Loading art…" else "Play Hand")
        }

        Spacer(Modifier.height(16.dp))
        result?.let { r ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(handName(r.handType), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    // the cascade, step by step — running chips x mult after each card/joker phase
                    for (s in steps) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.label, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${fmt(s.chips)} × ${fmt(s.mult)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("= ${fmt(r.score)}", fontFamily = FontFamily.Monospace,
                        fontSize = 26.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun handName(h: HandType): String =
    h.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
