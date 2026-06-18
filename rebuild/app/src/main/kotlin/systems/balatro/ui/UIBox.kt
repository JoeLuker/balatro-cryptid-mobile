package systems.balatro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.sin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A direct port of Balatro's UIBox tree (engine/ui.lua + globals G.UIT). Balatro's whole UI is
 * declarative data — nodes tagged R/C/T/B/O laid out by align + minw/minh + padding, drawn with
 * colour + r(ounded) + emboss. This interpreter renders that data in Compose, 1:1. The screens
 * are then Balatro's ACTUAL trees, not hand-drawn layouts — faithful by construction.
 *
 * Surface (measured across all of Balatro's UI defs): 8 node tags, ~15 config keys. Bounded.
 */

// Balatro never bakes a fixed scale: it fits a virtual room into the live window every resize
// (main.lua love.resize). We do the same the idiomatic Compose way — the root measures the surface
// and provides dp-per-unit here, so the whole interpreted UI sizes to ANY display/stream. See
// ROOM_W/ROOM_H and uiScaleFor() for the fit math. Default is a sane fallback before the root sets it.
val LocalUIScale = staticCompositionLocalOf { 32f }   // dp per Balatro UI unit (set at the root)

// fontSize (sp) per (unit × text-scale). Balatro loads m6x11plus at TILESIZE*10 with FONTSCALE=0.1,
// so a text node's font line-height in UI units == its scale (getHeight*scale*FONTSCALE/TILESIZE =
// scale·1.0). Thus fontSize should be exactly scale×u (ratio 1.0) — measured against the reference
// ("40" digit 112px) confirms ~1.0, not the old eyeballed 0.667 (which rendered text ~1.5× too small).
const val FONT_RATIO = 1.0f
private const val TWO_PI = 6.2831855f   // float-bob period for DynaText idle juice

// Balatro's virtual window in UI units: the room (TILE_W×TILE_H = 20×11.5) plus padding (1, 0.7) on
// each side (globals.lua/game.lua init_window). The whole game is this rect, fit into the surface.
const val ROOM_W = 22f      // 20 + 2×1
const val ROOM_H = 12.9f    // 11.5 + 2×0.7

/** dp-per-unit that fits the 22×12.9 room inside [wDp]×[hDp] preserving aspect (contain) — exactly
 *  the scale Balatro's love.resize computes, expressed as a plain min(). Resolution/density-correct
 *  because the inputs are the surface's own dp dimensions. */
fun uiScaleFor(wDp: Float, hDp: Float): Float = minOf(wDp / ROOM_W, hDp / ROOM_H)

data class Cfg(
    val align: String = "cm",       // <v><h>: t/c/b + l/m/r
    val colour: Color? = null,      // background fill
    val padding: Float = 0f,        // inner padding, in units
    val r: Float = 0f,              // corner radius, in units
    val minw: Float = 0f,
    val minh: Float = 0f,
    val maxw: Float = 0f,
    val maxh: Float = 0f,           // max height constraint in units (config.maxh)
    // config.w / config.h — EXPLICIT leaf size for B (box/spacer) and O (object) nodes
    // (calculate_xywh: `config.w or object.T.w`). null = absent → use object's intrinsic size.
    // Must be nullable to distinguish "absent" (dynatext self-measures) from "explicitly 0"
    // (the flame anchors are w=0,h=0). The old Cfg dropped these → B spacers collapsed to 0.
    val wCfg: Float? = null,
    val hCfg: Float? = null,
    val scale: Float = 1f,          // text scale
    val textColour: Color = Balatro.White,
    val shadow: Boolean = false,    // T shadow: black 0.3a offset pass (config.shadow in ui.lua draw_self)
    val vert: Boolean = false,      // T rotated 90° (config.vert = true — vertical sidebar labels)
    val emboss: Float = 0f,         // 3-D edge depth in units (G.C.emboss float, 0 = off)
    val outline: Float = 0f,        // border thickness in dp (config.outline; rendered at clip boundary)
    val outlineColour: Color = Color.Transparent,  // config.outline_colour
    val onClick: (() -> Unit)? = null,
)

sealed interface UI
class Ro(val cfg: Cfg, val kids: List<UI>) : UI          // G.UIT.R row
class Co(val cfg: Cfg, val kids: List<UI>) : UI          // G.UIT.C column
class Tx(val cfg: Cfg, val text: String) : UI            // G.UIT.T text
class Bx(val cfg: Cfg, val kids: List<UI> = emptyList()) : UI  // G.UIT.B box / spacer
class Ob(val cfg: Cfg, val obj: Obj) : UI                // G.UIT.O embedded object (Sprite/DynaText)

/**
 * An embedded Balatro game object — config.object on a G.UIT.O node. Terminal: it derives its own
 * w/h (calculate_xywh: `config.w or object.T.w`) and has no children. Two kinds cover the whole
 * UI-def surface measured across Balatro: a Sprite (atlas cell) and a DynaText (dynamic text).
 */
sealed interface Obj { val w: Float; val h: Float }      // intrinsic size in Balatro units

/** A Sprite O: one already-cropped atlas cell (CardArt.cache / JokerArt.cache produce the bitmap).
 *  w/h are the object's T.w/T.h in units — passed per call site as the Lua does (cards ~1u, stake
 *  sprites 0.5u); never a hardcoded px->unit constant. */
class Sprite(val bmp: ImageBitmap, override val w: Float, override val h: Float) : Obj

/** One coloured run of a DynaText string. `value` is a provider so bindings stay LIVE: a literal
 *  -> { "Small Blind" }; a binding {ref_table,ref_value} -> { s.handsLeft.toString() }. Reading a
 *  RunState mutableStateOf inside the lambda subscribes the composable (replaces update_text). */
class DynSeg(val value: () -> String, val colour: Color, val scale: Float)

/** A DynaText O: Balatro's dynamic text object. `segs` flow horizontally (its internal layout);
 *  maxw clamps width in units; shadow drives the drop-shadow pass. w/h default 0 => self-measure. */
class DynaText(
    val segs: List<DynSeg>,
    val maxw: Float = 0f,
    val shadow: Boolean = true,
    val spacing: Float = 0f,   // config.spacing — adds 0.0135×spacing per letter to measured width (text.lua:152)
    override val w: Float = 0f,
    override val h: Float = 0f,
) : Obj

// builders that read like the Lua: R(cfg){ ... }
fun R(cfg: Cfg = Cfg(), vararg kids: UI) = Ro(cfg, kids.toList())
fun C(cfg: Cfg = Cfg(), vararg kids: UI) = Co(cfg, kids.toList())
fun T(cfg: Cfg, text: String) = Tx(cfg, text)
fun B(cfg: Cfg = Cfg()) = Bx(cfg)
fun O(cfg: Cfg = Cfg(), obj: Obj) = Ob(cfg, obj)
fun Spr(bmp: ImageBitmap, w: Float, h: Float) = Sprite(bmp, w, h)
fun DynaT(vararg segs: DynSeg, maxw: Float = 0f, shadow: Boolean = true) = DynaText(segs.toList(), maxw, shadow)
/** seg reading live state: seg({ s.handsLeft.toString() }, Balatro.Chips, 2f). MUST read state in the lambda. */
fun seg(value: () -> String, colour: Color = Balatro.White, scale: Float = 1f) = DynSeg(value, colour, scale)
/** seg for a static literal: seg("Choose your Blind", Balatro.White). */
fun seg(text: String, colour: Color = Balatro.White, scale: Float = 1f) = DynSeg({ text }, colour, scale)

private fun vAlign(a: String): Alignment.Vertical = when (a.getOrNull(0)) { 't' -> Alignment.Top; 'b' -> Alignment.Bottom; else -> Alignment.CenterVertically }
private fun hAlign(a: String): Alignment.Horizontal = when (a.getOrNull(1)) { 'l' -> Alignment.Start; 'r' -> Alignment.End; else -> Alignment.CenterHorizontally }

@Composable
private fun Modifier.cfg(c: Cfg): Modifier {
    val u = LocalUIScale.current
    var m = this
    // config.button -> clickable with Balatro's press feel: scale to 0.985 while held (the exact
    // `button_being_pressed and 0.985 or 1` from ui.lua draw_self), and lighten the fill toward
    // G.C.UI.HOVER. Press state is real (finger down) via an interactionSource.
    var pressed = false
    if (c.onClick != null) {
        val interaction = remember { MutableInteractionSource() }
        pressed = interaction.collectIsPressedAsState().value
        m = m
            .graphicsLayer { val s = if (pressed) 0.985f else 1f; scaleX = s; scaleY = s }
            .clickable(interaction, indication = null) { c.onClick.invoke() }
    }
    if (c.minw > 0) m = m.widthIn(min = (c.minw * u).dp)
    if (c.minh > 0) m = m.heightIn(min = (c.minh * u).dp)
    if (c.maxw > 0) m = m.widthIn(max = (c.maxw * u).dp)
    if (c.maxh > 0) m = m.heightIn(max = (c.maxh * u).dp)
    if (c.colour != null) {
        val shape = RoundedCornerShape((c.r * u).dp)
        if (c.emboss > 0) m = m.border((c.emboss * u).dp, Color.Black.copy(alpha = 0.45f), shape)  // 3D edge: depth in units
        val fill = if (pressed) lighten(c.colour) else c.colour                   // ARGS.button_colours[2] HOVER
        m = m.clip(shape).background(fill)
    }
    // outline is a cosmetic border rendered at the clip boundary (config.outline / outline_colour).
    // Thickness is in dp (Balatro's outline=1 ≈ 1dp — sub-unit, pixel-scale value).
    if (c.outline > 0f && c.outlineColour != Color.Transparent) {
        val shape = RoundedCornerShape((c.r * u).dp)
        m = m.border(c.outline.dp, c.outlineColour, shape)
    }
    if (c.padding > 0) m = m.padding((c.padding * u).dp)
    return m
}

/** Lighten toward white — Balatro's hover overlay (G.C.UI.HOVER is a translucent white). */
private fun lighten(c: Color, amt: Float = 0.2f) = Color(
    red = c.red + (1f - c.red) * amt,
    green = c.green + (1f - c.green) * amt,
    blue = c.blue + (1f - c.blue) * amt,
    alpha = c.alpha,
)

/**
 * Render a UIBox tree node — the whole interpreter. The layout rule is Balatro's, straight
 * from calculate_xywh: a container stacks its R-children VERTICALLY and flows everything else
 * (C/B/T/O) HORIZONTALLY. So direction is decided by the CHILDREN's type, not the node's own
 * tag (the tag only says how the node sits in ITS parent: R = block line, C = inline).
 */
@Composable
fun RenderUI(node: UI) {
    when (node) {
        is Tx -> {
            val size = (node.cfg.scale * LocalUIScale.current * FONT_RATIO).sp
            // config.vert=true: text rotated 90° CCW — Balatro's vertical sidebar labels.
            // graphicsLayer rotates the drawing pass; wrapContentSize + rotate in place to avoid layout jump.
            val vertMod = if (node.cfg.vert) Modifier.graphicsLayer { rotationZ = -90f } else Modifier
            if (node.cfg.shadow) {
                Box(vertMod) {
                    BTxt(node.text, Color.Black.copy(alpha = 0.3f), size, Modifier.offset(x = 1.dp, y = 2.dp))
                    BTxt(node.text, node.cfg.textColour, size)
                }
            } else {
                BTxt(node.text, node.cfg.textColour, size, vertMod)
            }
        }
        is Ro -> Container(node.cfg, node.kids)
        is Co -> Container(node.cfg, node.kids)
        is Bx -> Container(node.cfg, node.kids)
        is Ob -> RenderObject(node.cfg, node.obj)   // O is terminal: no container, render the object
    }
}

/**
 * Render a G.UIT.O node. It reserves config.w/h (passed as minw/minh) else the object's intrinsic
 * T.w/T.h — Balatro's calculate_xywh O branch — then draws the embedded object into that footprint.
 */
@Composable
private fun RenderObject(cfg: Cfg, obj: Obj) {
    val box = Modifier.objSize(cfg, obj, LocalUIScale.current).cfg(cfg)   // cfg() still paints colour/r/emboss + button feel
    when (obj) {
        is Sprite -> Box(box, contentAlignment = Alignment.Center) {
            Image(
                bitmap = obj.bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,    // scale the source quad into the fitted rect (VT/T ratio)
                filterQuality = FilterQuality.None,  // pixel art: nearest-neighbour like LÖVE, never blur the cell
            )
        }
        is DynaText -> Box(box) { RenderDynaText(obj) }
    }
}

/** O size = config.w/h (carried as minw/minh in units) when set, else the object's intrinsic w/h. */
private fun Modifier.objSize(cfg: Cfg, obj: Obj, u: Float): Modifier {
    val w = if (cfg.minw > 0) cfg.minw else obj.w
    val h = if (cfg.minh > 0) cfg.minh else obj.h
    var m = this
    if (w > 0) m = m.width((w * u).dp)
    if (h > 0) m = m.height((h * u).dp)
    return m
}

/**
 * A DynaText: a row of coloured segments, each pulling its live value from its provider lambda
 * (reading RunState's mutableStateOf inside it makes Compose recompose on change — no polling).
 * shadow draws the black 0.3a parallax pass under each segment, matching draw_self's text shadow.
 */
/**
 * Balatro idle "juice" — wrap any sprite (joker, consumable, deck card) so it perpetually floats and
 * rotation-wobbles like a Card in a CardArea (align_cards: 0.06u·sin(t) bob, ±1.3° wobble at 2×). One
 * infinite transition drives both (sin(p) and sin(2p) are both seamless at p=2π). `seed` phase-offsets
 * each item so a row ripples instead of moving in lockstep.
 */
@Composable
fun BalatroFloat(seed: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val u = LocalUIScale.current
    val phase by rememberInfiniteTransition(label = "flt").animateFloat(
        0f, TWO_PI, infiniteRepeatable(tween(9430, easing = LinearEasing), RepeatMode.Restart), label = "fltp")
    val ampY = with(LocalDensity.current) { (0.06f * u).dp.toPx() }
    Box(modifier.graphicsLayer {
        translationY = ampY * sin(phase + seed)
        rotationZ = 1.3f * sin(2f * phase + seed)
    }) { content() }
}

@Composable
fun RenderDynaText(dt: DynaText) {
    val u = LocalUIScale.current
    val maxW = if (dt.maxw > 0) Modifier.widthIn(max = (dt.maxw * u).dp) else Modifier
    // Balatro DynaText floats: every dynamic readout perpetually bobs (text.lua:287, the float pass,
    // 2.666 rad/s, phase-offset per letter so the value ripples). The numbers are never dead-static.
    val phase by rememberInfiniteTransition(label = "dyna").animateFloat(
        0f, TWO_PI, infiniteRepeatable(tween(2357, easing = LinearEasing), RepeatMode.Restart), label = "floatPhase")
    val amp = with(LocalDensity.current) { (0.06f * u).dp.toPx() }
    Row(maxW, verticalAlignment = Alignment.CenterVertically) {
        dt.segs.forEachIndexed { i, s ->
            val text = s.value()                    // live read -> recomposes on RunState change
            val size = (s.scale * u * FONT_RATIO).sp
            // bump: when the value changes (chips tick up, money earned), the number pops and settles
            // with a springy overshoot — Balatro's juice_up on update. Initial value doesn't bump.
            val bump = remember { Animatable(1f) }
            var prev by remember { mutableStateOf(text) }
            LaunchedEffect(text) {
                if (text != prev) {
                    prev = text
                    bump.snapTo(1.22f)
                    bump.animateTo(1f, spring(dampingRatio = 0.36f, stiffness = 520f))
                }
            }
            Box(Modifier.graphicsLayer {
                translationY = amp * sin(phase + i * 0.7f)      // perpetual float
                scaleX = bump.value; scaleY = bump.value         // pop on change
            }) {
                if (dt.shadow) {
                    Box {
                        BTxt(text, Color.Black.copy(alpha = 0.3f), size, Modifier.offset(x = 1.dp, y = 2.dp))
                        BTxt(text, s.colour, size)
                    }
                } else {
                    BTxt(text, s.colour, size)
                }
            }
        }
    }
}

@Composable
private fun Container(cfg: Cfg, kids: List<UI>) {
    // Balatro's layout rule (calculate_xywh): a child with UIT==R advances Y (block line); everything
    // else advances X (inline). Any R child in the list makes this container a vertical stack —
    // `any` rather than `all` so a single R among C/T siblings still triggers Column layout,
    // matching the Lua cursor behaviour exactly. Pure non-R children → Row (horizontal flow).
    val vertical = kids.any { it is Ro }
    // calculate_xywh inserts `padding` AFTER every child along the main axis (cursor += size+padding),
    // so total = Σchild + (n+1)·padding. cfg()'s Modifier.padding gives the 2 edge paddings; the
    // (n-1) BETWEEN-child gaps come from spacedBy here — without it the rebuild was tighter than the
    // game. gap=0 (most nodes) makes spacedBy collapse to the plain alignment arrangement.
    val gap = (cfg.padding * LocalUIScale.current).dp
    if (vertical) {
        Column(Modifier.cfg(cfg), verticalArrangement = Arrangement.spacedBy(gap, vAlign(cfg.align)), horizontalAlignment = hAlign(cfg.align)) {
            kids.forEach { RenderUI(it) }
        }
    } else {
        Row(Modifier.cfg(cfg), horizontalArrangement = Arrangement.spacedBy(gap, hAlign(cfg.align)), verticalAlignment = vAlign(cfg.align)) {
            kids.forEach { RenderUI(it) }
        }
    }
}
