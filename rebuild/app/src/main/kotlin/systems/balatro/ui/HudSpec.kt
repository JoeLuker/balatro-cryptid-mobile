package systems.balatro.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONObject
import systems.balatro.bridge.Telemetry

/**
 * Renders Balatro's ACTUAL create_UIBox_HUD tree (assets/ui/hud_tree.json, extracted by
 * tools/uiref/extract.sh) — no hand-transcription. The JSON carries binding DESCRIPTORS (colour
 * names, ref paths, localize keys, DynaText bindings); [HudBind] wires them to live RunState. So
 * the HUD IS the real definition: a node can't be silently dropped or altered (e.g. the
 * flame_handler nodes are present, sized w=0/h=0 exactly as the source, flame visual pending).
 */
internal object HudSpec {
    @Volatile private var cached: JSONObject? = null
    fun root(ctx: Context): JSONObject? {
        cached?.let { return it }
        return try {
            JSONObject(ctx.assets.open("ui/hud_tree.json").bufferedReader().use { it.readText() }).also { cached = it }
        } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString(), "file" to "hud_tree.json"); null }
    }

    /** Build the UIBox AST from the loaded tree, binding descriptors to [b]. */
    fun build(node: JSONObject, b: HudBind): UI {
        val cfgJ = node.optJSONObject("config") ?: JSONObject()
        // row_blind is empty in create_UIBox_HUD (the blind token UIBox is attached separately);
        // inject the rebuild's blind content there so the sidebar is complete.
        if (cfgJ.optString("id") == "row_blind" && b.blindContent != null) {
            return Ro(b.cfg(cfgJ), listOf(b.blindContent!!))
        }
        val cfg = b.cfg(cfgJ)
        val nodesJ = node.optJSONArray("nodes")
        val kids = if (nodesJ != null) (0 until nodesJ.length()).map { build(nodesJ.getJSONObject(it), b) } else emptyList()
        return when (node.getString("n")) {
            "R", "ROOT" -> Ro(cfg, kids)
            "C" -> Co(cfg, kids)
            "B" -> Bx(cfg, kids)
            "T" -> Tx(cfg, b.text(cfgJ))
            "O" -> Ob(cfg, b.obj(cfgJ))
            else -> Bx(cfg, kids)
        }
    }
}

/** Binds the JSON's descriptors (colour names, ref/value, localize keys) to live RunState. */
internal class HudBind(val s: RunState, val stakeBmp: ImageBitmap?) {
    /** The rebuild's blind token+target UI, injected into the (empty) row_blind node. */
    var blindContent: UI? = null


    fun colourByName(name: String): Color = when (name) {
        "WHITE", "UI.TEXT_LIGHT", "BACKGROUND_WHITE" -> Balatro.White
        // vanilla G.C.DYN_UI.* and BLACK are all #374244 — stat boxes and their insets share it
        "BLACK", "DYN_UI.MAIN", "DYN_UI.DARK", "DYN_UI.BOSS_MAIN", "DYN_UI.BOSS_DARK", "DYN_UI.BOSS_PALE" -> Balatro.Panel
        "L_BLACK", "UI.TEXT_DARK" -> Balatro.PanelLight
        "GREY" -> Balatro.Grey
        "BLUE", "CHIPS" -> Balatro.Chips
        "RED", "MULT" -> Balatro.Mult
        "MONEY" -> Balatro.Money
        "GOLD" -> Balatro.Gold
        "IMPORTANT", "FILTER" -> Balatro.Orange
        "ORANGE" -> Balatro.OrangeTrue
        "GREEN" -> Balatro.Green
        "PURPLE" -> Balatro.Purple
        "CLEAR" -> Color.Transparent
        else -> Balatro.White
    }

    /** A colour value from config: {"$":"colour"} | {"$":"colourop"} | absent -> null. */
    private fun colour(v: JSONObject?): Color? {
        v ?: return null
        return when (v.optString("\$")) {
            "colour" -> colourByName(v.getString("name"))
            "colourop" -> colourOp(v)
            else -> null
        }
    }

    /** darken/lighten/mix on a base colour, matching Balatro's misc_functions implementations. */
    private fun colourOp(v: JSONObject): Color {
        fun resolve(j: JSONObject): Color = when (j.optString("\$")) {
            "colour" -> colourByName(j.getString("name")); "colourop" -> colourOp(j); else -> Balatro.White
        }
        val amt = v.optDouble("amt", 0.0).toFloat()
        return when (v.getString("op")) {
            "darken" -> resolve(v.getJSONObject("base")).let { Color(it.red * (1 - amt), it.green * (1 - amt), it.blue * (1 - amt), it.alpha) }
            "lighten" -> resolve(v.getJSONObject("base")).let { Color(it.red + (1 - it.red) * amt, it.green + (1 - it.green) * amt, it.blue + (1 - it.blue) * amt, it.alpha) }
            "mix" -> { val a = resolve(v.getJSONObject("a")); val c = resolve(v.getJSONObject("b")); Color(a.red * (1 - amt) + c.red * amt, a.green * (1 - amt) + c.green * amt, a.blue * (1 - amt) + c.blue * amt, 1f) }
            "alpha" -> resolve(v.getJSONObject("base")).copy(alpha = amt)
            else -> Balatro.White
        }
    }

    fun loc(key: Any?): String = when (key) {
        "k_hud_hands" -> "Hands"; "k_hud_discards" -> "Discards"
        "k_ante" -> "Ante"; "k_round" -> "Round"; "k_lower_score" -> "Score at least"
        "$" -> "$"; "b_options" -> "Options"; "b_run_info_1" -> "Run"; "b_run_info_2" -> "Info"
        else -> key?.toString() ?: ""
    }

    /** ref_value (or current_hand value) -> a live reader of RunState. */
    fun read(value: String): () -> String = when (value) {
        "hands_left" -> { { s.handsLeft.toString() } }
        "discards_left" -> { { s.discardsLeft.toString() } }
        "dollars" -> { { s.money.toString() } }
        "ante" -> { { s.ante.toString() } }
        "round" -> { { "1" } }
        "win_ante" -> { { "8" } }
        "chips_text" -> { { s.chipsText } }   // dollars_chips round-score readout
        "chip_text" -> { { s.chipText2 } }
        "mult_text" -> { { s.multText } }
        "handname_text" -> { { s.handNameText } }
        "chip_total_text" -> { { s.chipTotalText } }
        "hand_level" -> { { if (s.currentHandLevel > 0) "Lv${s.currentHandLevel}" else "" } }
        else -> { { "" } }
    }

    /** Map a JSON config object to the interpreter's Cfg. */
    fun cfg(c: JSONObject): Cfg = Cfg(
        align = c.optString("align", "cm"),
        colour = colour(c.optJSONObject("colour")),
        padding = c.optDouble("padding", 0.0).toFloat(),
        r = c.optDouble("r", 0.0).toFloat(),
        minw = c.optDouble("minw", 0.0).toFloat(),
        // minh=30 on the BOSS_DARK panel is Balatro's "fill the sidebar height" sentinel; taken
        // literally it inflates the natural height to 30u so FitToHeight shrinks the HUD to a sliver.
        // Drop sentinel mins (>=20u) so the panel sizes to its real ~9u content.
        minh = c.optDouble("minh", 0.0).toFloat().let { if (it >= 20f) 0f else it },
        maxw = c.optDouble("maxw", 0.0).toFloat(),
        scale = c.optDouble("scale", 1.0).toFloat(),
        textColour = colour(c.optJSONObject("colour")) ?: Balatro.White,
        shadow = c.optBoolean("shadow", false),
        emboss = c.optDouble("emboss", 0.0).toFloat(),
    )

    /** A T node's text: a literal string, a {loc}, or a ref binding (win_ante) resolved once. */
    fun text(c: JSONObject): String {
        val t = c.opt("text")
        return when {
            t is JSONObject && t.optString("\$") == "loc" -> loc(t.opt("key"))
            t is String -> t
            c.has("ref_value") -> read(c.getString("ref_value"))()
            else -> ""
        }
    }

    /** An O node's object: a DynaText (live), a stake Sprite, or a flame_handler Moveable (w=0/h=0). */
    fun obj(c: JSONObject): Obj {
        val o = c.optJSONObject("object") ?: return DynaText(emptyList())
        return when (o.optString("\$")) {
            "dynatext" -> dynatext(o)
            "sprite" -> stakeBmp?.let { Sprite(it, o.optDouble("scale", 0.5).toFloat(), o.optDouble("scale", 0.5).toFloat()) }
                ?: DynaText(emptyList(), w = o.optDouble("scale", 0.5).toFloat(), h = o.optDouble("scale", 0.5).toFloat())
            else -> DynaText(emptyList(), w = 0f, h = 0f)   // moveable (flame anchor): zero-size, flame visual pending
        }
    }

    private fun dynatext(o: JSONObject): DynaText {
        val segsJ = o.optJSONArray("segs") ?: return DynaText(emptyList())
        val colsJ = o.optJSONArray("colours")
        val scale = o.optDouble("scale", 1.0).toFloat()
        val shadow = o.optBoolean("shadow", true)
        val segs = (0 until segsJ.length()).map { i ->
            val sj = segsJ.getJSONObject(i)
            val col = colsJ?.optString(i.coerceAtMost((colsJ.length() - 1).coerceAtLeast(0)))?.let { colourByName(it) } ?: Balatro.White
            val prefix = sj.optJSONObject("prefix")?.let { loc(it.opt("loc")) } ?: (sj.opt("prefix") as? String ?: "")
            val reader: () -> String = when {
                sj.has("value") -> read(sj.getString("value"))
                sj.has("text") -> { val txt = sj.getString("text"); { txt } }
                else -> { { "" } }
            }
            DynSeg({ prefix + reader() }, col, scale)
        }
        return DynaText(segs, shadow = shadow)
    }
}
