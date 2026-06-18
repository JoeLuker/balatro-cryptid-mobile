package systems.balatro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

// m6x11plus is a pixel font; Compose's default font padding + leading trim soften it. Drop the
// padding and trim line spacing to first/last baseline so the glyphs sit tight and crisp.
private val pixelStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/** Pixel-font text — the default everywhere in the Balatro chrome. */
@Composable
fun BTxt(text: String, color: Color = Balatro.White, size: TextUnit = 16.sp, modifier: Modifier = Modifier) =
    Text(text, color = color, fontFamily = Balatro.font, fontWeight = FontWeight.Normal, fontSize = size, style = pixelStyle, modifier = modifier)

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
fun BButton(text: String, color: Color, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(if (enabled) color else Balatro.Grey)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp),
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
