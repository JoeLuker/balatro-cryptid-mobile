package systems.balatro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Balatro's EXACT UIBox layout, ported 1:1 from engine/ui.lua + engine/text.lua and rendered with
 * ABSOLUTE positioning. This replaces the old approximate interpreter (Compose Row/Column + spacedBy
 * + widthIn(min)), which diverged from the engine on spacing/sizing/centering — the root cause of the
 * HUD pixel mismatch.
 *
 * Three passes, exactly as UIBox:init runs them:
 *   1. calculate_xywh — sizes + top-left cursor positions (R children stack vertically, others
 *      horizontally; padding inserted after each child; final size = max(content+padding, minw/minh)).
 *   2. set_wh — equalize siblings (every R child → max sibling width, every C child → max sibling height).
 *   3. set_alignments — centering: 'c' vertical-centre, 'm' horizontal-middle, 'b' bottom, 'r' right,
 *      using the parent's content_dimensions; offsets propagate to all descendants (UIElement:align).
 *
 * VERIFIED against the real engine: tools/uiref/verify_layout.py reproduces LÖVE's create_UIBox_HUD
 * geometry dump to 80/80 nodes, worst error 5e-5 units. This Kotlin mirrors that Python exactly.
 */

// ── text metrics ──────────────────────────────────────────────────────────────────────────────
// m6x11plus glyph advances in em (= LÖVE font:getWidth(char @ size N) / N), dumped from the real
// font in tools/uiref. Additivity verified (no kerning): width(s) = Σ adv(c). So a text node's
// width in UI units = Σ adv × scale, byte-identical to the engine. printable ASCII 32..126.
private val GLYPH_EM: FloatArray = floatArrayOf(
    0.44f,0.19f,0.39f,0.505f,0.44f,0.44f,0.63f,0.195f,0.315f,0.315f,0.38f,0.44f,0.195f,0.44f,0.19f,0.44f,
    0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.19f,0.19f,0.38f,0.44f,0.38f,0.44f,
    0.63f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.565f,0.44f,0.44f,
    0.44f,0.505f,0.44f,0.44f,0.44f,0.44f,0.44f,0.565f,0.505f,0.44f,0.44f,0.315f,0.44f,0.315f,0.44f,0.44f,
    0.065f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.19f,0.315f,0.44f,0.19f,0.565f,0.44f,0.44f,
    0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.44f,0.565f,0.505f,0.44f,0.44f,0.38f,0.19f,0.38f,0.44f,
)
private const val DEFAULT_GLYPH = 0.44f
private const val TEXT_H = 0.83f          // TEXT_HEIGHT_SCALE: text node height = scale × 0.83 units
private const val DT_SPACING = 0.0135f    // per-letter spacing contribution = 2.7×spacing×FONTSCALE/TILESIZE

private fun glyphEm(c: Char): Float {
    val i = c.code - 32
    return if (i in GLYPH_EM.indices) GLYPH_EM[i] else DEFAULT_GLYPH
}

/** Text width in UI units: Σ glyph-advance × scale (engine-exact). */
fun textWidthUnits(s: String, scale: Float): Float {
    var sum = 0f
    for (c in s) sum += glyphEm(c)
    return sum * scale
}

/** O-node intrinsic size in units when config.w/h is absent: the embedded object's self-measure. */
private fun objMeasure(o: Obj): Pair<Float, Float> = when (o) {
    is Sprite -> o.w to o.h
    is DynaText -> {
        var w = 0f; var h = 0f
        for (seg in o.segs) {
            val t = seg.value()
            w += textWidthUnits(t, seg.scale) + t.length * DT_SPACING * o.spacing
            if (t.isNotEmpty()) h = maxOf(h, seg.scale * TEXT_H)   // empty seg adds no height (engine loop skips it)
        }
        w to h
    }
}

// ── layout node ─────────────────────────────────────────────────────────────────────────────────
private const val T = 1; private const val B = 2; private const val C = 3
private const val R = 4; private const val O = 5; private const val ROOT = 7

private class LNode(val ui: UI, val isRoot: Boolean) {
    val type: Int = when {
        isRoot -> ROOT
        ui is Tx -> T
        ui is Bx -> B
        ui is Co -> C
        ui is Ro -> R
        else -> O
    }
    val cfg: Cfg = when (ui) {
        is Ro -> ui.cfg; is Co -> ui.cfg; is Bx -> ui.cfg; is Tx -> ui.cfg; is Ob -> ui.cfg
    }
    val kids: List<LNode> = when (ui) {
        is Ro -> ui.kids; is Co -> ui.kids; is Bx -> ui.kids; else -> emptyList()
    }.map { LNode(it, false) }
    var x = 0f; var y = 0f; var w = 0f; var h = 0f
    var cw = 0f; var ch = 0f   // content_dimensions (pre-set_wh content size, used by set_alignments)
}

/** calculate_xywh — sets node.{x,y,w,h,cw,ch}; returns (w,h). [fac] is the inherited maxw rescale. */
private fun calc(n: LNode, tx: Float, ty: Float, fac: Float): Pair<Float, Float> {
    val cfg = n.cfg
    val padding = cfg.padding
    val scale = cfg.scale * fac
    if (n.type == T || n.type == B || n.type == O) {
        val w: Float; val h: Float
        when (n.type) {
            T -> { w = textWidthUnits((n.ui as Tx).text, scale); h = scale * TEXT_H }
            else -> {  // B or O: config.w/h wins, else (O) object self-measure, else 0
                if (cfg.wCfg != null || cfg.hCfg != null) { w = cfg.wCfg ?: 0f; h = cfg.hCfg ?: 0f }
                else if (n.type == O) { val m = objMeasure((n.ui as Ob).obj); w = m.first; h = m.second }
                else { w = 0f; h = 0f }
            }
        }
        n.x = tx; n.y = ty; n.w = w; n.h = h
        return w to h
    }
    // container (C / R / ROOT) — column-accumulation; advance axis chosen per child type
    var ctW = 0f; var ctH = 0f
    for (i in 1..2) {
        val exceeded = i == 2 && ((cfg.maxw > 0 && ctW > cfg.maxw) || (cfg.maxh > 0 && ctH > cfg.maxh))
        if (i == 1 || exceeded) {
            var f = fac
            if (i == 2) {
                val restriction = if (cfg.maxw > 0) cfg.maxw else cfg.maxh
                f = fac * restriction / (if (cfg.maxw > 0) ctW else ctH)
            }
            n.x = if (n.type == ROOT) 0f else tx
            n.y = if (n.type == ROOT) 0f else ty
            n.w = cfg.minw; n.h = cfg.minh
            var cx = n.x + padding; var cy = n.y + padding; ctW = 0f; ctH = 0f
            for (ch in n.kids) {
                val (tw, th) = calc(ch, cx, cy, f)
                val emboss = ch.cfg.emboss
                if (ch.type == R) {                       // R child → advance Y (block line)
                    ctH += th + padding; cy += th + padding
                    if (tw + padding > ctW) ctW = tw + padding
                    if (emboss > 0) { ctH += emboss; cy += emboss }
                } else {                                  // everything else → advance X (inline)
                    ctW += tw + padding; cx += tw + padding
                    if (th + padding > ctH) ctH = th + padding
                    if (emboss > 0) ctH += emboss
                }
            }
        }
    }
    n.cw = ctW + padding; n.ch = ctH + padding
    n.w = maxOf(ctW + padding, n.w); n.h = maxOf(ctH + padding, n.h)
    return n.w to n.h
}

/** set_wh — equalize siblings: every R child gets the max child width, every C child the max height. */
private fun setWh(n: LNode): Pair<Float, Float> {
    if (n.kids.isEmpty()) return n.w to n.h
    var mw = 0f; var mh = 0f
    for (c in n.kids) {
        val (cw, ch) = setWh(c)
        if (cw > mw) mw = cw
        if (ch > mh) mh = ch
    }
    for (c in n.kids) {
        if (c.type == R) c.w = mw
        if (c.type == C) c.h = mh
    }
    return n.w to n.h
}

/** UIElement:align — shift this node and all descendants by (dx,dy) (centering offset propagation). */
private fun shift(n: LNode, dx: Float, dy: Float) {
    n.x += dx; n.y += dy
    for (c in n.kids) shift(c, dx, dy)
}

/** set_alignments — apply the parent's align string to each child, then recurse. */
private fun setAlignments(n: LNode) {
    val cfg = n.cfg
    val padding = cfg.padding
    val a = cfg.align
    for (c in n.kids) {
        if (a.isNotEmpty()) {
            if (a.contains('c')) {
                if (c.type == T || c.type == B || c.type == O) shift(c, 0f, 0.5f * (n.h - 2 * padding - c.h))
                else shift(c, 0f, 0.5f * (n.h - n.ch))
            }
            if (a.contains('m')) shift(c, 0.5f * (n.w - n.cw), 0f)
            if (a.contains('b')) shift(c, 0f, n.h - n.ch)
            if (a.contains('r')) shift(c, n.w - n.cw, 0f)
        }
        setAlignments(c)
    }
}

/** Run all three passes over a UI tree; return the laid-out root (absolute units, centering folded in). */
private fun layout(root: UI): LNode {
    val n = LNode(root, isRoot = true)
    calc(n, 0f, 0f, 1f)
    setWh(n)
    setAlignments(n)
    return n
}

// ── absolute renderer ───────────────────────────────────────────────────────────────────────────
/** Emboss lip colour: the fill darkened ~40% (the shaded 3-D edge under a box). */
private fun embossLip(c: Color) = Color(c.red * 0.6f, c.green * 0.6f, c.blue * 0.6f, c.alpha)

/** A flattened node with its absolute rect (units) — pre-order so parents paint under children. */
private class Placed(val node: LNode, val x: Float, val y: Float)

private fun flatten(n: LNode, dx: Float, dy: Float, out: MutableList<Placed>) {
    out.add(Placed(n, n.x + dx, n.y + dy))
    for (c in n.kids) flatten(c, dx, dy, out)
}

/**
 * Render a UIBox tree at EXACT engine geometry, absolutely positioned. [u] is dp-per-unit. The box
 * sizes to the tree's natural width × [roomH] units and clips — the HUD panel is minh=30u (taller
 * than the 12.9u room) so it's vertically centred and bleeds off top/bottom, exactly like the game.
 * Live DynaText values are read during layout, so the box re-lays-out when a value's width changes
 * (each value stays centred in its fixed box).
 *
 * [blindOverlay] receives the source-empty row_blind reservation's absolute rect (units) so the
 * rebuild's blind token+target panel can be drawn into it WITHOUT disturbing the engine-exact layout.
 */
@Composable
fun RenderUIBoxAbsolute(
    root: UI,
    u: Float,
    roomH: Float,
    fontRatio: Float = FONT_RATIO,
    blindOverlay: (@Composable (x: Float, y: Float, w: Float, h: Float) -> Unit)? = null,
) {
    val laid = layout(root)
    val yShift = (roomH - laid.h) / 2f          // centre the tall panel; it bleeds off top/bottom
    val placed = ArrayList<Placed>()
    flatten(laid, 0f, yShift, placed)
    Box(Modifier.size((laid.w * u).dp, (roomH * u).dp).clipToBounds()) {
        for (p in placed) renderNode(p, u, fontRatio)
        if (blindOverlay != null) {
            val rb = findRowBlind(laid)
            if (rb != null) blindOverlay(rb.x, rb.y + yShift, rb.w, rb.h)
        }
    }
}

/**
 * Render a self-contained UIBox tree CENTRED in a given rect (units) — used for sub-boxes that the
 * real game attaches separately (e.g. HUD_blind, which floats over the source-empty row_blind slot).
 * Laid out at its natural size; no clip (the caller's box clips). Same exact layout engine.
 */
@Composable
fun RenderUIBoxAt(root: UI, u: Float, rectX: Float, rectY: Float, rectW: Float, rectH: Float, fontRatio: Float = FONT_RATIO) {
    val laid = layout(root)
    val dx = rectX + (rectW - laid.w) / 2f
    val dy = rectY + (rectH - laid.h) / 2f
    val placed = ArrayList<Placed>()
    flatten(laid, dx, dy, placed)
    Box(Modifier) { for (p in placed) renderNode(p, u, fontRatio) }
}

/** row_blind is the single source-empty R reserved at minh=3.75 (the blind UIBox attaches separately). */
private fun findRowBlind(n: LNode): LNode? {
    if (n.type == R && n.kids.isEmpty() && kotlin.math.abs(n.cfg.minh - 3.75f) < 0.01f) return n
    for (c in n.kids) findRowBlind(c)?.let { return it }
    return null
}

@Composable
private fun renderNode(p: Placed, u: Float, fontRatio: Float) {
    val n = p.node
    val cfg = n.cfg
    // requiredSize (NOT size): nodes are positioned via absoluteOffset inside a box that clips. The
    // backing panel is minh=30u — far taller than the box — and sits at a negative offset; plain
    // .size() would be coerced to the box's max height (Compose honours incoming constraints), so the
    // tall panel only showed its top sliver. requiredSize ignores parent constraints; clipToBounds on
    // the box still trims the overflow.
    val at = Modifier.absoluteOffset((p.x * u).dp, (p.y * u).dp)
    when (n.type) {
        R, C, ROOT, B -> {
            // container/box: paint fill at the exact rect (no padding — layout consumed it). emboss is
            // Balatro's 3-D lip: a DARKER rounded rect peeking out the bottom by `emboss` units, drawn
            // BEHIND the full-size fill (an inset border instead shrank the fill ~10% vs the reference).
            if (cfg.colour != null && cfg.colour != Color.Transparent && n.w > 0 && n.h > 0) {
                val shape = RoundedCornerShape((cfg.r * u).dp)
                if (cfg.emboss > 0f) {
                    Box(at.requiredSize((n.w * u).dp, ((n.h + cfg.emboss) * u).dp).clip(shape)
                        .background(embossLip(cfg.colour)))
                }
                Box(at.requiredSize((n.w * u).dp, (n.h * u).dp).clip(shape).background(cfg.colour))
            }
        }
        T -> {
            val tx = (n.ui as Tx)
            val size = (n.cfg.scale * u * fontRatio)
            // Balatro's text box is TEXT_HEIGHT_SCALE (0.83) of the font line height — it trims the
            // DESCENT (bottom), so the glyph sits high in the box. Compose centres the glyph in the
            // line box, so it lands (1-0.83)/2 = 0.085·lineHeight too LOW. Shift up by that to match.
            val vshift = -((1f - TEXT_H) / 2f * n.cfg.scale * u)
            Box(at.requiredSize((n.w * u).dp, (n.h * u).dp), contentAlignment = Alignment.Center) {
                Box(Modifier.offset(y = vshift.dp)) {
                    if (cfg.shadow) Box {
                        BTxt(tx.text, Color.Black.copy(alpha = 0.3f), size.sp, Modifier.offset(y = 2.dp))
                        BTxt(tx.text, cfg.textColour, size.sp)
                    } else BTxt(tx.text, cfg.textColour, size.sp)
                }
            }
        }
        O -> {
            val obj = (n.ui as Ob).obj
            when (obj) {
                is Sprite -> if (n.w > 0 && n.h > 0)
                    Image(obj.bmp, null, at.requiredSize((n.w * u).dp, (n.h * u).dp),
                        contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                is DynaText -> Box(at) { RenderDynaText(obj) }
            }
        }
    }
}
