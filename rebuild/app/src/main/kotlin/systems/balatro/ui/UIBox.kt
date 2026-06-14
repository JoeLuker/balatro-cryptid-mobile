package systems.balatro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** Balatro UI unit -> dp / sp. The single tuning knob for overall scale (Balatro's `scale`). */
const val U = 34f      // dp per Balatro UI unit
const val FONT = 30f   // sp per text scale=1

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

// builders that read like the Lua: R(cfg){ ... }
fun R(cfg: Cfg = Cfg(), vararg kids: UI) = Ro(cfg, kids.toList())
fun C(cfg: Cfg = Cfg(), vararg kids: UI) = Co(cfg, kids.toList())
fun T(cfg: Cfg, text: String) = Tx(cfg, text)
fun B(cfg: Cfg = Cfg()) = Bx(cfg)

private fun vArr(a: String): Arrangement.Vertical = when (a.getOrNull(0)) { 't' -> Arrangement.Top; 'b' -> Arrangement.Bottom; else -> Arrangement.Center }
private fun hArr(a: String): Arrangement.Horizontal = when (a.getOrNull(1)) { 'l' -> Arrangement.Start; 'r' -> Arrangement.End; else -> Arrangement.Center }
private fun vAlign(a: String): Alignment.Vertical = when (a.getOrNull(0)) { 't' -> Alignment.Top; 'b' -> Alignment.Bottom; else -> Alignment.CenterVertically }
private fun hAlign(a: String): Alignment.Horizontal = when (a.getOrNull(1)) { 'l' -> Alignment.Start; 'r' -> Alignment.End; else -> Alignment.CenterHorizontally }

@Composable
private fun Modifier.cfg(c: Cfg): Modifier {
    var m = this
    if (c.onClick != null) m = m.clickable { c.onClick.invoke() }
    if (c.minw > 0) m = m.widthIn(min = (c.minw * U).dp)
    if (c.minh > 0) m = m.heightIn(min = (c.minh * U).dp)
    if (c.maxw > 0) m = m.widthIn(max = (c.maxw * U).dp)
    if (c.colour != null) {
        val shape = RoundedCornerShape((c.r * U).dp)
        if (c.emboss) m = m.border(2.dp, Color.Black.copy(alpha = 0.25f), shape)  // the embossed 3D edge
        m = m.clip(shape).background(c.colour)
    }
    if (c.padding > 0) m = m.padding((c.padding * U).dp)
    return m
}

/** Render a UIBox tree node — the whole interpreter. */
@Composable
fun RenderUI(node: UI) {
    when (node) {
        is Tx -> BTxt(node.text, node.cfg.textColour, (node.cfg.scale * FONT).sp)
        is Ro -> Row(Modifier.cfg(node.cfg), horizontalArrangement = hArr(node.cfg.align), verticalAlignment = vAlign(node.cfg.align)) {
            node.kids.forEach { RenderUI(it) }
        }
        is Co -> Column(Modifier.cfg(node.cfg), verticalArrangement = vArr(node.cfg.align), horizontalAlignment = hAlign(node.cfg.align)) {
            node.kids.forEach { RenderUI(it) }
        }
        is Bx -> Box(Modifier.cfg(node.cfg), contentAlignment = Alignment.Center) {
            node.kids.forEach { RenderUI(it) }
        }
    }
}
