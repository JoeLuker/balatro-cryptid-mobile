package systems.balatro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/** Balatro UI unit -> dp / sp. Calibrated against the headless reference: the HUD's real
 *  computed width is 3.23 units (tools/uiref/hud_geometry.ref.txt), so U*3.23 ~= the 175dp
 *  sidebar. FONT pairs with it (Balatro text height in units = scale*0.83). */
const val U = 54f      // dp per Balatro UI unit
const val FONT = 36f   // sp per text scale=1

data class Cfg(
    val align: String = "cm",       // <v><h>: t/c/b + l/m/r
    val colour: Color? = null,      // background fill
    val padding: Float = 0f,        // inner padding, in units
    val r: Float = 0f,              // corner radius, in units
    val minw: Float = 0f,
    val minh: Float = 0f,
    val maxw: Float = 0f,
    val scale: Float = 1f,          // text scale
    val textColour: Color = Balatro.White,
    val emboss: Boolean = false,
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

private fun vArr(a: String): Arrangement.Vertical = when (a.getOrNull(0)) { 't' -> Arrangement.Top; 'b' -> Arrangement.Bottom; else -> Arrangement.Center }
private fun hArr(a: String): Arrangement.Horizontal = when (a.getOrNull(1)) { 'l' -> Arrangement.Start; 'r' -> Arrangement.End; else -> Arrangement.Center }
private fun vAlign(a: String): Alignment.Vertical = when (a.getOrNull(0)) { 't' -> Alignment.Top; 'b' -> Alignment.Bottom; else -> Alignment.CenterVertically }
private fun hAlign(a: String): Alignment.Horizontal = when (a.getOrNull(1)) { 'l' -> Alignment.Start; 'r' -> Alignment.End; else -> Alignment.CenterHorizontally }

@Composable
private fun Modifier.cfg(c: Cfg): Modifier {
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
    if (c.minw > 0) m = m.widthIn(min = (c.minw * U).dp)
    if (c.minh > 0) m = m.heightIn(min = (c.minh * U).dp)
    if (c.maxw > 0) m = m.widthIn(max = (c.maxw * U).dp)
    if (c.colour != null) {
        val shape = RoundedCornerShape((c.r * U).dp)
        if (c.emboss) m = m.border(2.dp, Color.Black.copy(alpha = 0.25f), shape)  // the embossed 3D edge
        val fill = if (pressed) lighten(c.colour) else c.colour                   // ARGS.button_colours[2] HOVER
        m = m.clip(shape).background(fill)
    }
    if (c.padding > 0) m = m.padding((c.padding * U).dp)
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
        is Tx -> BTxt(node.text, node.cfg.textColour, (node.cfg.scale * FONT).sp)
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
    val box = Modifier.objSize(cfg, obj).cfg(cfg)   // cfg() still paints colour/r/emboss + button feel
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
private fun Modifier.objSize(cfg: Cfg, obj: Obj): Modifier {
    val w = if (cfg.minw > 0) cfg.minw else obj.w
    val h = if (cfg.minh > 0) cfg.minh else obj.h
    var m = this
    if (w > 0) m = m.width((w * U).dp)
    if (h > 0) m = m.height((h * U).dp)
    return m
}

/**
 * A DynaText: a row of coloured segments, each pulling its live value from its provider lambda
 * (reading RunState's mutableStateOf inside it makes Compose recompose on change — no polling).
 * shadow draws the black 0.3a parallax pass under each segment, matching draw_self's text shadow.
 */
@Composable
private fun RenderDynaText(dt: DynaText) {
    val maxW = if (dt.maxw > 0) Modifier.widthIn(max = (dt.maxw * U).dp) else Modifier
    Row(maxW, verticalAlignment = Alignment.CenterVertically) {
        dt.segs.forEach { s ->
            val text = s.value()                    // live read -> recomposes on RunState change
            val size = (s.scale * FONT).sp
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

@Composable
private fun Container(cfg: Cfg, kids: List<UI>) {
    val vertical = kids.isNotEmpty() && kids.all { it is Ro }   // R children stack; otherwise flow across
    if (vertical) {
        Column(Modifier.cfg(cfg), verticalArrangement = vArr(cfg.align), horizontalAlignment = hAlign(cfg.align)) {
            kids.forEach { RenderUI(it) }
        }
    } else {
        Row(Modifier.cfg(cfg), horizontalArrangement = hArr(cfg.align), verticalAlignment = vAlign(cfg.align)) {
            kids.forEach { RenderUI(it) }
        }
    }
}
