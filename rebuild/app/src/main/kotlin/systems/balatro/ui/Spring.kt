package systems.balatro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import systems.balatro.game.PlayingCard
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A direct port of Balatro's engine/moveable.lua spring integrator — the source of the whole
 * game's feel. Every card/joker has a TARGET transform (T) and a VISIBLE transform (VT) that
 * springs toward it with momentum each frame:
 *   - move_xy: velocity blends old momentum with the pull to target; VT += velocity (capped).
 *   - move_r:  the card LEANS into its horizontal velocity (des_r = T.r + 0.015*velX/dt) — the
 *              iconic tilt-while-moving — plus the juice wobble.
 *   - move_scale: hover +0.05, drag +0.1, plus juice.
 *   - move_juice: a damped sine (sin*decay^3 for scale, ^2 for rotation) — the pop when a card
 *                 is scored/triggered (juiceUp shrinks to 1-0.6*amt then bounces back).
 * Constants are Balatro's exact ones (exp(-50/-60/-190 * dt), max_vel 70*dt).
 */
class BalatroSpring(x: Float = 0f, y: Float = 0f) {
    // target
    var tx = x; var ty = y; var tr = 0f; var tscale = 1f
    // visible (what you draw)
    var vx = x; var vy = y; var vr = 0f; var vscale = 1f
    private var velX = 0f; private var velY = 0f; private var velR = 0f; private var velScale = 0f
    var hover = false
    var drag = false
    // juice
    private var jStart = 0f; private var jEnd = 0f; private var jScaleAmt = 0f; private var jRAmt = 0f
    private var jScale = 0f; private var jR = 0f

    /** The "pop": shrink to 1-0.6*amount, then bounce back over 0.4s via the damped sine. */
    fun juiceUp(amount: Float = 0.4f, rotAmt: Float = 0.6f * 0.4f, now: Float) {
        jStart = now; jEnd = now + 0.4f; jScaleAmt = amount; jRAmt = rotAmt
        vscale = 1f - 0.6f * amount
    }

    fun move(dt: Float, now: Float) {
        if (dt <= 0f) return
        // move_juice
        if (jEnd > now && jEnd > jStart) {
            val p = (jEnd - now) / (jEnd - jStart)
            jScale = jScaleAmt * sin(50.8f * (now - jStart)) * max(0f, p * p * p)
            jR = jRAmt * sin(40.8f * (now - jStart)) * max(0f, p * p)
        } else { jScale = 0f; jR = 0f }

        val expXY = exp(-50f * dt)
        val expScale = exp(-60f * dt)
        val expR = exp(-190f * dt)
        val maxVel = 70f * dt

        // move_xy
        velX = expXY * velX + (1f - expXY) * (tx - vx) * 35f * dt
        velY = expXY * velY + (1f - expXY) * (ty - vy) * 35f * dt
        val v2 = velX * velX + velY * velY
        if (v2 > maxVel * maxVel) { val a = sqrt(v2); velX = maxVel * velX / a; velY = maxVel * velY / a }
        vx += velX; vy += velY
        if (kotlin.math.abs(vx - tx) < 0.01f && kotlin.math.abs(velX) < 0.01f) { vx = tx; velX = 0f }
        if (kotlin.math.abs(vy - ty) < 0.01f && kotlin.math.abs(velY) < 0.01f) { vy = ty; velY = 0f }

        // move_r — lean into horizontal velocity + juice
        val desR = tr + 0.015f * velX / dt + jR * 2f
        velR = expR * velR + (1f - expR) * (desR - vr)
        vr += velR

        // move_scale — hover/drag/juice
        val desScale = tscale + (if (hover) 0.05f else 0f) + (if (drag) 0.1f else 0f) + jScale
        velScale = expScale * velScale + (1f - expScale) * (desScale - vscale)
        vscale += velScale
    }
}

/**
 * The hand, driven by the real Balatro spring. Cards are positioned ABSOLUTELY (like the LÖVE
 * CardArea) and spring toward their fan slot — they lean into their own motion, lift on select,
 * and settle with momentum. A single frame clock (withFrameNanos) ticks every card's spring.
 * Positions are in Balatro "units" (a card ≈ 1.8 units wide) so the ported constants apply
 * unchanged, then scaled to px at draw.
 */
@Composable
fun SpringHand(
    hand: List<PlayingCard>,
    selected: Set<Int>,
    enabled: Boolean,
    cardWidth: Dp,
    onToggle: (Int) -> Unit,
    handLimit: Int = 8,     // Balatro temp_limit (hand size) — fan spread normalizes to this
    cardContent: @Composable (PlayingCard) -> Unit,
) {
    val density = LocalDensity.current
    val springs = remember { mutableStateMapOf<Int, BalatroSpring>() }
    var frame by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.0001f, 0.05f)
                last = now
                val t = now / 1e9f
                springs.values.forEach { it.move(dt, t) }
                frame = now
            }
        }
    }
    val cardWpx = with(density) { cardWidth.toPx() }
    val unit = cardWpx / 1.8f                       // a card is ~1.8 Balatro units wide
    val cardHpx = cardWpx * 190f / 142f
    BoxWithConstraints(Modifier.fillMaxWidth().height(with(density) { (cardHpx * 1.5f).toDp() })) {
        val n = hand.size
        if (n == 0) return@BoxWithConstraints
        val areaW = with(density) { maxWidth.toPx() }
        val centerXpx = areaW / 2f
        // Balatro CardArea:align_cards (cardarea.lua:50): cards distribute across the hand area
        // (T.w − card_w), spacing NORMALIZED by max_cards = max(#cards, hand-size limit), so a full
        // hand exactly fills the area and fewer cards left-shift — NOT a fixed per-card spread that
        // spills past the area edge. wu = area width in card-widths; tx is the card-centre offset
        // from the area centre, in the spring's 1.8-units-per-card space.
        val wu = areaW / cardWpx
        val maxCards = maxOf(n, handLimit)
        val denom = maxOf(maxCards - 1, 1).toFloat()
        val t = frame / 1e9f                        // read frame -> recompose each tick; seconds for the idle juice
        hand.forEachIndexed { i, card ->
            val d = i - (n - 1) / 2f
            val frac = i / denom - 0.5f * (n - maxCards) / denom        // align_cards fraction (0..1)
            val tx = 1.8f * (wu - 1f) * (frac - 0.5f)
            val sp = springs.getOrPut(i) { BalatroSpring(tx, 0f) }
            sp.tx = tx
            // Balatro align_cards (cardarea.lua:54): the hand is NEVER still — each card perpetually
            // floats (0.03·sin(0.666·t + x)) and rotation-wobbles (0.02·sin(2·t + x)), phase-offset by
            // the card's x so the row ripples. The spring tracks the moving target → continuous gentle
            // motion (the bob is 0.03 room-units ≈ 0.026 in the spring's 1.8u-per-card space). Plus the
            // static arc (gentle V) + select lift + fan, which already matched.
            sp.ty = (if (i in selected) -0.95f else 0f) + 0.5f * abs(d) / n - 0.2f +
                0.026f * sin(0.666f * t + tx)                                       // + idle float
            sp.tr = 0.2f * d / n + 0.02f * sin(2f * t + tx)                          // fan + idle wobble
            val px = centerXpx + sp.vx * unit - cardWpx / 2f
            val py = (cardHpx * 0.55f) + sp.vy * unit
            Box(
                Modifier
                    .offset { IntOffset(px.roundToInt(), py.roundToInt()) }
                    .size(cardWidth, with(density) { cardHpx.toDp() })
                    .graphicsLayer {
                        rotationZ = sp.vr * 57.2958f
                        scaleX = sp.vscale; scaleY = sp.vscale
                    }
                    .clickable(enabled = enabled) { onToggle(i) }
            ) { cardContent(card) }
        }
    }
}
