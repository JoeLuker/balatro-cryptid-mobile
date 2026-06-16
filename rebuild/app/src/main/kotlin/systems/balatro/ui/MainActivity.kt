package systems.balatro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import systems.balatro.content.Jokers
import systems.balatro.engine.Engine
import systems.balatro.game.*

/**
 * Native chrome over the composition engine: a 120-joker board scored on-device and
 * managed in a native ModalBottomSheet + virtualized LazyVerticalGrid with the real
 * reused art. Atlas cells are decoded+cropped ONCE off the main thread into a cache,
 * so opening the grid stays at ~120fps (no synchronous per-cell cropping hitch).
 */
class MainActivity : ComponentActivity() {

    private data class Joke(val name: String, val col: Int, val row: Int)
    private data class BootState(val pass: Int, val total: Int, val score: Double, val jokes: List<Joke>)

    private fun board(n: Int): Pair<Double, List<Joke>> {
        // Landing-card demo: score a pair through n jokers on the FAITHFUL Score engine.
        val score = Score.score(PlayingCard.hand("S_A", "H_A"), List(n) { FJoker("j_joker") }).score
        Telemetry.event("BOARD", "n" to n, "score" to score)
        val names = listOf("Joker", "Green Joker", "Scaler")
        val jokes = (0 until n).map { Joke(names[it % 3], it % 10, (it / 10) % 16) }
        return score to jokes
    }

    /** Decode the reused atlas and crop every distinct cell ONCE, off the main thread. */
    private fun buildCellCache(ctx: Context, jokes: List<Joke>): Map<Pair<Int, Int>, ImageBitmap> {
        val atlas = try {
            ctx.assets.open("textures/Jokers.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString()); return emptyMap() }
        val out = HashMap<Pair<Int, Int>, ImageBitmap>()
        for (key in (jokes.map { it.col to it.row } + (0 to 0)).toSet()) {
            out[key] = Bitmap.createBitmap(atlas, key.first * 142, key.second * 190, 142, 190).asImageBitmap()
        }
        Telemetry.event("CELLCACHE", "cells" to out.size)
        return out
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Telemetry.event("ACTIVITY", "stage" to "onCreate")
        val n = 120
        // Deep-link straight into the in-run HUD: `am start -n …/.ui.MainActivity --ez run true`.
        // Package-targeted, deterministic entry for parity screenshots (no blind tap through the
        // foldable's rotated display); absent the extra it boots to the menu as before.
        val bootRun = intent?.getBooleanExtra("run", false) == true
        // Optional --es screen blind|shop|round to land on a specific phase for parity screenshots.
        val bootScreen = intent?.getStringExtra("screen")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ctx = LocalContext.current
                // Boot work — the on-device oracle self-check (parity harness on the phone's
                // ART/ARM runtime) + 120-joker board scoring — runs OFF the main thread, so the
                // first frame is never blocked (this was a ~2s cold-start hitch in onCreate).
                val boot by produceState<BootState?>(null) {
                    value = withContext(Dispatchers.Default) {
                        val (pass, total) = Oracle.run()
                        Telemetry.event("ORACLE", "pass" to pass, "total" to total)
                        val (sc, jk) = board(n)
                        BootState(pass, total, sc, jk)
                    }
                }
                val jokes = boot?.jokes ?: emptyList()
                // atlas cropping also off the main thread, keyed on the (eventual) jokes
                val cells by produceState(initialValue = emptyMap<Pair<Int, Int>, ImageBitmap>(), jokes) {
                    value = withContext(Dispatchers.Default) { if (jokes.isEmpty()) emptyMap() else buildCellCache(ctx, jokes) }
                }
                var showManager by remember { mutableStateOf(false) }
                var showRun by remember { mutableStateOf(bootRun || bootScreen != null) }

                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("Balatro Native", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("clean-slate rebuild · composition core", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                cells[0 to 0]?.let { Image(it, "Joker", Modifier.size(56.dp, 75.dp)); Spacer(Modifier.width(14.dp)) }
                                Column {
                                    Text("$n jokers scored on-device · art reused", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("score = ${boot?.score?.toString() ?: "running…"}", fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                                    Text("oracle parity: ${boot?.let { "${it.pass}/${it.total}" } ?: "…"} on-device",
                                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                        color = if (boot == null || boot!!.pass == boot!!.total) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                    Text("10 Cryptid archetypes ported · scores like the original", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { showManager = true; Telemetry.event("UI", "open" to "manager", "n" to n) },
                            modifier = Modifier.fillMaxWidth(), enabled = cells.isNotEmpty()) {
                            Text(if (cells.isEmpty()) "Loading art…" else "Manage $n Jokers  (native grid)")
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { showRun = true; Telemetry.event("UI", "open" to "run") },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Play  (the one game: blinds + shop)")
                        }
                        Spacer(Modifier.weight(1f))
                        Text("telemetry on · systems.balatro.rebuild · your LÖVE build untouched",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                    }

                    if (showManager) {
                        ModalBottomSheet(onDismissRequest = { showManager = false }) {
                            Text("  Jokers ($n) — native virtualized grid", fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(12.dp))
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(64.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(jokes) { j -> cells[j.col to j.row]?.let { Image(it, j.name, Modifier.size(60.dp, 80.dp)) } }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }

                    if (showRun) {
                        Surface(Modifier.fillMaxSize()) { RunScreen(onClose = { showRun = false }, startScreen = bootScreen) }
                    }
                }
            }
        }
    }
}
