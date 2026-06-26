package systems.balatro.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import systems.balatro.game.*
import systems.balatro.save.SaveIo
import java.io.File

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
        systems.balatro.audio.SoundManager.init(applicationContext)   // load bundled SFX once
        getSharedPreferences("balatro", MODE_PRIVATE).let {           // apply saved audio settings
            systems.balatro.audio.SoundManager.enabled = it.getBoolean("sfx", true)
            systems.balatro.audio.MusicManager.enabled = it.getBoolean("music", true)
        }
        // Edge-to-edge: let the Compose surface fill the ENTIRE display, drawing under (hidden) system
        // bars. Without this the content area is inset by the landscape nav bar (~200px), so the room
        // scaled to a too-narrow surface (u≈165px/unit instead of the full-width 174.5) and the whole
        // HUD/play field rendered ~6% small. setDecorFitsSystemWindows(false) makes fillMaxSize == the
        // real screen, so u = screenWidth/ROOM_W exactly matches the reference's scale.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // Immersive fullscreen — hide the status bar + nav/taskbar so the game fills the whole
        // surface (like the real game), instead of being inset by system bars (which offset/compress
        // the layout vs a fullscreen reference). Sticky so transient swipes don't permanently show them.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
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
                var showSettings by remember { mutableStateOf(false) }
                var showRun by remember { mutableStateOf(bootRun || bootScreen != null) }
                // A saved run auto-resumes on Play; hasSave drives the Continue/New-Run choice.
                val saveFile = remember { File(ctx.filesDir, SaveIo.FILE_NAME) }
                var hasSave by remember { mutableStateOf(saveFile.exists()) }
                LaunchedEffect(showRun) { if (!showRun) hasSave = saveFile.exists() }   // refresh on return to menu

                // Main menu = the REAL create_UIBox_main_menu_buttons tree (main_menu_tree.json) rendered
                // through the layout engine on the felt — Play / Options / Collection, wired to nav.
                Box(Modifier.fillMaxSize()) {
                    BalatroFelt(Modifier.matchParentSize())
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val u = uiScaleFor(maxWidth.value, maxHeight.value)
                        CompositionLocalProvider(LocalUIScale provides u) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                BTxt("BALATRO", Balatro.Orange, (1.6f * u * FONT_RATIO).sp)
                                Spacer(Modifier.height((0.5f * u).dp))
                                val mroot = remember { HudSpec.root(ctx, "main_menu_tree.json") }
                                if (mroot != null) RenderUIBoxNatural(buildMenu(mroot, MenuBind(
                                    onPlay = { showRun = true; Telemetry.event("UI", "open" to "run", "resume" to hasSave) },
                                    onOptions = { showSettings = true },
                                    onCollection = { showManager = true },
                                )), u)
                            }
                        }
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

                    // OPTIONS — Balatro-styled overlay (felt) replacing the Material dialogs. The vanilla
                    // create_UIBox_options tree is ~90% Android-inapplicable (resolution/vsync/CRT/shake),
                    // so this is the applicable subset: audio toggles + the (orphaned) lifetime stats.
                    if (showSettings) {
                        var sfxOn by remember { mutableStateOf(systems.balatro.audio.SoundManager.enabled) }
                        var musicOn by remember { mutableStateOf(systems.balatro.audio.MusicManager.enabled) }
                        val prefs = remember { ctx.getSharedPreferences("balatro", Context.MODE_PRIVATE) }
                        val st = remember { systems.balatro.save.StatsStore.read(ctx) }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            BalatroFelt(Modifier.matchParentSize())
                            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)))
                            Column(
                                Modifier.widthIn(max = 360.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                    .background(Balatro.FeltDark)
                                    .border(2.dp, Balatro.Orange, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                BTxt("OPTIONS", Balatro.Orange, 26.sp)
                                Spacer(Modifier.height(16.dp))
                                BButton(if (sfxOn) "Sound: ON" else "Sound: OFF", if (sfxOn) Balatro.Chips else Balatro.Grey,
                                    modifier = Modifier.fillMaxWidth()) {
                                    sfxOn = !sfxOn; systems.balatro.audio.SoundManager.enabled = sfxOn
                                    prefs.edit().putBoolean("sfx", sfxOn).apply()
                                }
                                Spacer(Modifier.height(8.dp))
                                BButton(if (musicOn) "Music: ON" else "Music: OFF", if (musicOn) Balatro.Chips else Balatro.Grey,
                                    modifier = Modifier.fillMaxWidth()) {
                                    musicOn = !musicOn
                                    systems.balatro.audio.MusicManager.setEnabled(musicOn, ctx.applicationContext)
                                    prefs.edit().putBoolean("music", musicOn).apply()
                                }
                                Spacer(Modifier.height(18.dp))
                                BTxt("LIFETIME STATS", Balatro.Orange, 15.sp)
                                Spacer(Modifier.height(6.dp))
                                listOf(
                                    "Runs" to "${st.games}", "Wins" to "${st.wins} (${st.winRate}%)",
                                    "Best ante" to "${st.bestAnte}", "Best score" to "${st.bestScore}", "Hands" to "${st.totalHands}",
                                ).forEach { (k, v) ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        BTxt(k, Balatro.White, 12.sp); BTxt(v, Balatro.White, 12.sp)
                                    }
                                }
                                Spacer(Modifier.height(18.dp))
                                BButton("Back", Balatro.Orange, modifier = Modifier.fillMaxWidth()) { showSettings = false }
                            }
                        }
                    }

                    if (showRun) {
                        Surface(Modifier.fillMaxSize()) { RunScreen(onClose = { showRun = false }, startScreen = bootScreen) }
                    }
                }
            }
        }
    }

    // Background music follows the Activity lifecycle (start/resume foregrounded, pause backgrounded).
    override fun onResume() { super.onResume(); systems.balatro.audio.MusicManager.start(applicationContext) }
    override fun onPause() { systems.balatro.audio.MusicManager.pause(); super.onPause() }
    override fun onDestroy() { systems.balatro.audio.MusicManager.release(); systems.balatro.audio.SoundManager.release(); super.onDestroy() }
}
