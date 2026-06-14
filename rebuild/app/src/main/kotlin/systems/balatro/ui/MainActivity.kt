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
import systems.balatro.bridge.Telemetry
import systems.balatro.content.Jokers
import systems.balatro.engine.Engine
import systems.balatro.game.*

/**
 * Native chrome over the composition engine. A 120-joker board is scored by the engine
 * and managed in a native ModalBottomSheet + virtualized LazyVerticalGrid with the real
 * reused art — the huge-stack UI LÖVE could not do, at native scroll speed.
 */
class MainActivity : ComponentActivity() {

    private data class Joke(val name: String, val col: Int, val row: Int)

    private fun board(n: Int): Pair<Double, List<Joke>> {
        val engine = Engine(); val effects = Effects()
        Jokers.makeBoard(engine.world, effects, n)
        val base = engine.world.create()
        effects.register(base, setOf(Ctx.BEFORE)) { _, c -> c.tally.chips = BigValue.of(10) }
        val score = ScoreRun(effects).scoreHand(engine.world, emptyList())
        Telemetry.event("BOARD", "n" to n, "score" to score.v)
        // varied real sprites across the 10x16 atlas so the grid shows distinct cards
        val names = listOf("Joker", "Green Joker", "Scaler")
        val jokes = (0 until n).map { Joke(names[it % 3], it % 10, (it / 10) % 16) }
        return score.v to jokes
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Telemetry.event("ACTIVITY", "stage" to "onCreate")
        val n = 120
        val (score, jokes) = board(n)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ctx = LocalContext.current
                val atlas = remember { loadAtlas(ctx) }
                var showManager by remember { mutableStateOf(false) }

                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("Balatro Native", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("clean-slate rebuild · composition core", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                atlas?.let { Image(cell(it, 0, 0), "Joker", Modifier.size(56.dp, 75.dp)); Spacer(Modifier.width(14.dp)) }
                                Column {
                                    Text("$n jokers scored on-device · art reused", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("score = $score", fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                                    Text("engine handled $n jokers deterministically", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { showManager = true; Telemetry.event("UI", "open" to "manager", "n" to n) },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Manage $n Jokers  (native grid)")
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
                                items(jokes) { j ->
                                    atlas?.let { Image(cell(it, j.col, j.row), j.name, Modifier.size(60.dp, 80.dp)) }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }

    private fun loadAtlas(ctx: Context): Bitmap? = try {
        ctx.assets.open("textures/Jokers.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString()); null }

    private fun cell(atlas: Bitmap, col: Int, row: Int): ImageBitmap =
        Bitmap.createBitmap(atlas, col * 142, row * 190, 142, 190).asImageBitmap()
}
