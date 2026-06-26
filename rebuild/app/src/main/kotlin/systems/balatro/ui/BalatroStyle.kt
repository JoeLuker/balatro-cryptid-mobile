package systems.balatro.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import systems.balatro.R

/**
 * The vanilla Balatro look: the exact G.C palette from globals.lua and the m6x11plus pixel
 * font (reused from Balatro.love). One place so every screen reads as the real game, not a
 * Material dev UI.
 */
object Balatro {
    // table + panels
    val Felt = Color(0xFF234C44)        // the green felt table
    val FeltDark = Color(0xFF1B3A34)
    // G.C.BLACK is nominally #374244, but Balatro composites a shadow under the HUD so panels render
    // darker on screen (~#2E3A3C, measured across box bodies/insets in the reference). Use the rendered
    // value so the dark panels pixel-match; the ~9/channel shadow offset isn't exposed headlessly.
    val Panel = Color(0xFF2E3A3C)        // G.C.BLACK as composited (dark UI panels)
    val PanelLight = Color(0xFF4F6367)   // G.C.L_BLACK
    val Grey = Color(0xFF5F7377)         // G.C.GREY
    // readout colours
    val Chips = Color(0xFF009DFF)        // G.C.CHIPS / BLUE
    val Mult = Color(0xFFFE5F55)         // G.C.MULT / RED
    val Money = Color(0xFFF3B958)        // G.C.MONEY
    val Gold = Color(0xFFEAC058)         // G.C.GOLD
    val Orange = Color(0xFFFF9A00)       // G.C.FILTER / IMPORTANT
    val OrangeTrue = Color(0xFFFDA200)   // G.C.ORANGE (globals.lua:365) — sort buttons, blind-select button
    val Green = Color(0xFF4BC292)        // G.C.GREEN
    val Purple = Color(0xFF8867A5)       // G.C.PURPLE — tarot accent
    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF374244)          // dark text on light chips

    /** G.C.HAND_LEVELS[1..7] — the 7 level-badge colours cycling from white through lavender.
     *  Source uses HAND_LEVELS[min(7,lvl)]; index 0 is unused (RED for Lv0, a degenerate case). */
    val HandLevels: List<Color> = listOf(
        Mult,                        // [0] = RED — Lv0 degenerate
        Color(0xFFEFEFEF),           // [1] = near white
        Color(0xFF95ACFF),           // [2] = soft blue
        Color(0xFF65EFAF),           // [3] = mint green
        Color(0xFFFAE37E),           // [4] = pale gold
        Color(0xFFFFC052),           // [5] = orange
        Color(0xFFF87D75),           // [6] = salmon
        Color(0xFFCAA0EF),           // [7] = lavender
    )

    /** Returns HAND_LEVELS[clamp(lvl, 0, 7)] — the level-badge fill colour for this hand level. */
    fun handLevelColour(lvl: Int): Color = HandLevels[lvl.coerceIn(0, 7)]

    val font = FontFamily(Font(R.font.m6x11plus))
}

// m6x11plus is a pixel font; Compose adds font padding by default — drop it. Trim.Both trims the
// line-height box to cap-height+baseline so the glyph sits at the correct vertical position inside
// its layout node. The TEXT_VSHIFT correction in UILayout / RenderDynaText handles the remaining
// offset.
//
// Descender fix: Compose's Text composable clips ink at the font's declared winDescent metric
// (256/1024 em for m6x11plus), but glyphs p/g/j/q/y have yMin=-320 — 64/1024 em beyond winDescent.
// BTxt therefore renders via Canvas.drawText (TextMeasurer) on a canvas padded by DESCENT_OVERHANG_EM
// below the measured layout height, which gives the native Skia rasterizer room to draw the full
// glyph outlines without clipping.
private val pixelStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

// m6x11plus: winDescent=256, actual glyph yMin=-320 → overhang = 64/1024 em = 0.0625 em.
// Adding this fraction of fontSize as extra canvas height below the measured text lets Skia draw
// the full descender outline instead of clipping at the declared font descent.
private const val DESCENT_OVERHANG_EM = 0.0625f  // (320 - 256) / 1024

/**
 * Pixel-font text — the default everywhere in the Balatro chrome.
 *
 * Uses Canvas + TextMeasurer rather than Text() so that the native Skia rasterizer renders m6x11plus
 * glyphs at their full yMin bounds. Compose's Text clips ink at the font's declared winDescent; the
 * Canvas path draws into a slightly taller region (DESCENT_OVERHANG_EM × fontSize) to preserve
 * descenders on p/g/j/q/y ("Ootions" → "Options").
 */
@Composable
fun BTxt(text: String, color: Color = Balatro.White, size: TextUnit = 16.sp, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = pixelStyle.copy(color = color, fontFamily = Balatro.font, fontWeight = FontWeight.Normal, fontSize = size)
    val result = measurer.measure(text, style)
    val density = LocalDensity.current
    // Canvas height = text's measured height + descender overhang to unclip p/g/j/q/y ink.
    // size is in sp; convert overhang to dp via density (1 sp = fontScale × 1 dp).
    val wDp = with(density) { result.size.width.toDp() }
    val hDp = with(density) { result.size.height.toDp() }
    val overhangDp = with(density) { (size.value * DESCENT_OVERHANG_EM).sp.toDp() }
    Canvas(modifier.size(wDp, hDp + overhangDp)) {
        drawText(result, color)
    }
}

/** A rounded value chip (Hands/Discards/Money counters). */
@Composable
fun Pill(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BTxt(value, Balatro.White, 18.sp)
        if (label.isNotEmpty()) BTxt(label, Balatro.White, 9.sp)
    }
}

/** A chunky Balatro action button. */
@Composable
fun BButton(text: String, color: Color, enabled: Boolean = true, modifier: Modifier = Modifier, sound: String = "button", onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (enabled) color else Balatro.Grey)
            .clickable(enabled = enabled) { systems.balatro.audio.SoundManager.play(sound); onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { BTxt(text, Balatro.White, 15.sp) }
}

/** The iconic chips X mult readout (blue chips box, red mult box). */
@Composable
fun ScoreReadout(handName: String, chips: String, mult: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BTxt(handName, Balatro.Orange, 16.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Balatro.Chips).padding(horizontal = 14.dp, vertical = 5.dp)) {
                BTxt(chips, Balatro.White, 24.sp)
            }
            BTxt("  X  ", Balatro.Mult, 18.sp)
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Balatro.Mult).padding(horizontal = 14.dp, vertical = 5.dp)) {
                BTxt(mult, Balatro.White, 24.sp)
            }
        }
    }
}

/** A dark rounded Balatro panel. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(Balatro.Panel)
            .border(2.dp, Balatro.PanelLight, RoundedCornerShape(10.dp)).padding(10.dp)
    ) { content() }
}

/** A Balatro HUD stat box: a light label over a dark inset with the big coloured value (Hands/Discards/Ante/Round). */
@Composable
fun HudBox(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(Balatro.Panel).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BTxt(label, Balatro.White, 10.sp)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Balatro.FeltDark).padding(vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) { BTxt(value, valueColor, 20.sp) }
    }
}
