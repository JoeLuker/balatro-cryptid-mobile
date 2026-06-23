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
    private val cache = HashMap<String, JSONObject>()
    fun root(ctx: Context, file: String = "hud_tree.json"): JSONObject? {
        cache[file]?.let { return it }
        return try {
            JSONObject(ctx.assets.open("ui/$file").bufferedReader().use { it.readText() }).also { cache[file] = it }
        } catch (e: Throwable) { Telemetry.event("ASSET", "err" to e.toString(), "file" to file); null }
    }

    /** The extracted create_shop_card_ui templates (per card-set: price / button / buy_and_use). */
    fun cardUi(ctx: Context): JSONObject? = root(ctx, "shop_card_ui.json")

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

    /** Per-offer context for create_shop_card_ui templates (shop_card_ui.json): the price tag binds
     *  `card.cost`→offerCost; the buy/redeem/open button binds to offerAction. Set per offer before build. */
    var offerCost: Int = 0
    var offerAction: (() -> Unit)? = null


    fun colourByName(name: String): Color = when (name) {
        "WHITE", "UI.TEXT_LIGHT", "BACKGROUND_WHITE" -> Balatro.White
        // vanilla G.C.DYN_UI.* and BLACK are all #374244 — stat boxes and their insets share it
        "BLACK", "DYN_UI.MAIN", "DYN_UI.DARK", "DYN_UI.BOSS_MAIN", "DYN_UI.BOSS_DARK", "DYN_UI.BOSS_PALE" -> Balatro.Panel
        "L_BLACK", "UI.TEXT_DARK" -> Balatro.PanelLight
        "GREY", "UI.BACKGROUND_INACTIVE", "UI.TEXT_INACTIVE" -> Balatro.Grey
        "BLUE", "CHIPS", "UI_CHIPS" -> Balatro.Chips
        "RED", "MULT", "UI_MULT" -> Balatro.Mult
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
        "k_ante" -> "Ante"; "k_round" -> "Round"; "k_lower_score" -> "score"
        "$" -> "$"; "b_options" -> "Options"; "b_run_info_1" -> "Run"; "b_run_info_2" -> "Info"
        // shop
        "b_next_round_1" -> "Next"; "b_next_round_2" -> "Round"; "k_reroll" -> "Reroll"
        // offer cards (create_shop_card_ui)
        "b_buy" -> "Buy"; "b_redeem" -> "Redeem"; "b_open" -> "Open"; "b_and_use" -> "and Use"
        else -> if (key is String) key else ""   // {type=variable} loc tables (e.g. ante_x_voucher) -> blank for now
    }

    /** A config.button -> a live RunState action, gated by config.func. Only real action buttons map;
     *  controller pip hints (button='x'/'y' + func='set_button_pip') return null (not clickable). */
    private fun buttonOnClick(c: JSONObject): (() -> Unit)? = when (c.optString("button")) {
        "toggle_shop" -> ({ s.nextBlind() })   // Next Round → leave shop
        "reroll_shop" -> ({ s.reroll() })      // reroll() self-guards on money >= rerollCost
        // offer-card buttons (create_shop_card_ui) → the per-offer action set on the bind
        "buy_from_shop", "redeem_from_shop", "open_booster" -> offerAction
        else -> null
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
        "reroll_cost" -> { { s.rerollCost.toString() } }   // shop reroll button
        "cost" -> { { offerCost.toString() } }             // offer-card price tag ($cost), bound per offer
        "chip_text" -> { { s.chipText2 } }
        "mult_text" -> { { s.multText } }
        // hand name, played-hand chip total, and level share one row (hand_text_area top). They're
        // separate nodes with no inter-node padding in the source, so space them in the text itself
        // ("High Card  75  lvl.1") instead of jamming ("High Card75Lv1").
        "handname_text" -> { { s.handNameText } }
        "chip_total_text" -> { { s.chipTotalText.let { if (it.isBlank()) "" else "  $it" } } }
        "hand_level" -> { { if (s.currentHandLevel > 0) "  lvl.${s.currentHandLevel}" else "" } }
        else -> { { "" } }
    }

    /** Map a JSON config object to the interpreter's Cfg. */
    fun cfg(c: JSONObject): Cfg = Cfg(
        align = c.optString("align", "cm"),
        colour = colour(c.optJSONObject("colour")),
        padding = c.optDouble("padding", 0.0).toFloat(),
        r = c.optDouble("r", 0.0).toFloat(),
        minw = c.optDouble("minw", 0.0).toFloat(),
        // Use Balatro's real minh verbatim. minh=30 on the BOSS_DARK panel is NOT a sentinel to
        // drop — it makes the dark panel intentionally taller than the screen so it bleeds off
        // top/bottom (the full-height sidebar look) with content centered (align "cm"). The render
        // site draws the HUD at fixed scale and lets it overflow, exactly like the real game; the
        // hand-name row keeps its 1.1u reservation (the played-hand name floats into it).
        minh = c.optDouble("minh", 0.0).toFloat(),
        maxw = c.optDouble("maxw", 0.0).toFloat(),
        wCfg = if (c.has("w")) c.optDouble("w", 0.0).toFloat() else null,
        hCfg = if (c.has("h")) c.optDouble("h", 0.0).toFloat() else null,
        scale = c.optDouble("scale", 1.0).toFloat(),
        textColour = colour(c.optJSONObject("colour")) ?: Balatro.White,
        shadow = c.optBoolean("shadow", false),
        emboss = c.optDouble("emboss", 0.0).toFloat(),
        onClick = buttonOnClick(c),   // shop Next-Round / Reroll buttons (null for non-action nodes)
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
            "cardarea" -> CardAreaSlot(o.getString("name"), o.optDouble("w", 0.0).toFloat(), o.optDouble("h", 0.0).toFloat())
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
        val spacing = o.optDouble("spacing", 0.0).toFloat()
        val maxw = o.optDouble("maxw", 0.0).toFloat()
        val segs = (0 until segsJ.length()).map { i ->
            val sj = segsJ.getJSONObject(i)
            // Use the tree's own colour (chips/mult numbers are UI.TEXT_LIGHT = white): they sit
            // INSIDE the blue/red chip/mult boxes, so white reads correctly — colouring the text
            // blue/red (an old workaround for when the boxes had no fill) made them invisible.
            val col = colsJ?.optString(i.coerceAtMost((colsJ.length() - 1).coerceAtLeast(0)))?.let { colourByName(it) } ?: Balatro.White
            val prefix = sj.optJSONObject("prefix")?.let { loc(it.opt("loc")) } ?: (sj.opt("prefix") as? String ?: "")
            val reader: () -> String = when {
                sj.has("value") -> read(sj.getString("value"))
                sj.has("text") -> { val txt = sj.getString("text"); { txt } }
                else -> { { "" } }
            }
            DynSeg({ prefix + reader() }, col, scale)
        }
        return DynaText(segs, maxw = maxw, shadow = shadow, spacing = spacing)
    }
}

/**
 * Binds the extracted create_shop_card_ui trees (shop_card_ui.json) to a single offer card.
 * The JSON's DynaText uses ref="card"/value="cost" (from pathName[card]="card" in extract.lua);
 * the button ROOT carries button= and func= for the action and its guard.
 *
 * [cost]       — the offer's displayed price (already discounted via RunState.price()).
 * [canAfford]  — true when RunState.money >= cost AND any slot/stack guard passes (joker cap etc.).
 * [onAction]   — fired when the buy/redeem/open button is tapped.
 */
internal class CardBind(val cost: Int, val canAfford: Boolean, val onAction: () -> Unit) {

    fun colourByName(name: String): Color = when (name) {
        "WHITE", "UI.TEXT_LIGHT" -> Balatro.White
        "BLACK", "DYN_UI.MAIN"  -> Balatro.Panel
        "L_BLACK"               -> Balatro.PanelLight
        "GREY"                  -> Balatro.Grey
        "GOLD"                  -> Balatro.Gold
        "GREEN"                 -> Balatro.Green
        "RED", "MULT"           -> Balatro.Mult
        "MONEY"                 -> Balatro.Money
        "CLEAR"                 -> androidx.compose.ui.graphics.Color.Transparent
        else                    -> Balatro.White
    }

    private fun colourOp(v: JSONObject): Color {
        fun resolve(j: JSONObject): Color = when (j.optString("\$")) {
            "colour"   -> colourByName(j.getString("name"))
            "colourop" -> colourOp(j)
            else       -> Balatro.White
        }
        val amt = v.optDouble("amt", 0.0).toFloat()
        return when (v.getString("op")) {
            "darken"  -> resolve(v.getJSONObject("base")).let {
                androidx.compose.ui.graphics.Color(it.red*(1-amt), it.green*(1-amt), it.blue*(1-amt), it.alpha) }
            "lighten" -> resolve(v.getJSONObject("base")).let {
                androidx.compose.ui.graphics.Color(it.red+(1-it.red)*amt, it.green+(1-it.green)*amt, it.blue+(1-it.blue)*amt, it.alpha) }
            "mix"     -> { val a=resolve(v.getJSONObject("a")); val c=resolve(v.getJSONObject("b"))
                androidx.compose.ui.graphics.Color(a.red*(1-amt)+c.red*amt, a.green*(1-amt)+c.green*amt, a.blue*(1-amt)+c.blue*amt, 1f) }
            "alpha"   -> resolve(v.getJSONObject("base")).copy(alpha = amt)
            else      -> Balatro.White
        }
    }

    private fun colour(v: JSONObject?): Color? {
        v ?: return null
        return when (v.optString("\$")) {
            "colour"   -> colourByName(v.getString("name"))
            "colourop" -> colourOp(v)
            else       -> null   // {"$":"ref"} ref_table anchor — not a colour, ignore
        }
    }

    /** The func= guard maps to canAfford; button= maps to onAction. Returns null → node not clickable. */
    private fun buttonOnClick(c: JSONObject): (() -> Unit)? {
        val func   = c.optString("func")
        val button = c.optString("button")
        // Only nodes carrying a real action button get a click; pip-helper nodes do not.
        if (button.isEmpty()) return null
        // func= is the vanilla guard ("can_buy", "can_redeem", "can_open", "can_buy_and_use").
        // Map all guards to canAfford — if the player can't afford, the button is inert.
        val gated = when (func) {
            "can_buy", "can_redeem", "can_open", "can_buy_and_use" -> canAfford
            else -> true
        }
        if (!gated) return null
        return when (button) {
            "buy_from_shop", "redeem_from_shop", "open_booster" -> onAction
            else -> null
        }
    }

    /** Resolved price string: "$N" — the DynaText prefix={loc:"$"} + ref="card"/value="cost". */
    fun read(value: String): () -> String = when (value) {
        "cost" -> { { cost.toString() } }
        else   -> { { "" } }
    }

    fun loc(key: Any?): String = when (key) {
        "$"         -> "$"
        "b_buy"     -> "Buy"
        "b_redeem"  -> "Redeem"
        "b_open"    -> "Open"
        "b_and_use" -> "& Use"
        else        -> if (key is String) key else ""
    }

    fun cfg(c: JSONObject): Cfg {
        // When canAfford=false the button colour shifts to GREY so the disabled state is visible,
        // matching Balatro's G.C.UI.BACKGROUND_INACTIVE used for unavailable buttons.
        val rawColour = colour(c.optJSONObject("colour"))
        val func = c.optString("func")
        val isGuardedButton = func.startsWith("can_")
        val fill = if (isGuardedButton && !canAfford) Balatro.Grey else rawColour
        return Cfg(
            align       = c.optString("align", "cm"),
            colour      = fill,
            padding     = c.optDouble("padding", 0.0).toFloat(),
            r           = c.optDouble("r", 0.0).toFloat(),
            minw        = c.optDouble("minw", 0.0).toFloat(),
            minh        = c.optDouble("minh", 0.0).toFloat(),
            maxw        = c.optDouble("maxw", 0.0).toFloat(),
            wCfg        = if (c.has("w")) c.optDouble("w", 0.0).toFloat() else null,
            hCfg        = if (c.has("h")) c.optDouble("h", 0.0).toFloat() else null,
            scale       = c.optDouble("scale", 1.0).toFloat(),
            textColour  = rawColour ?: Balatro.White,
            shadow      = c.optBoolean("shadow", false),
            emboss      = c.optDouble("emboss", 0.0).toFloat(),
            outline     = c.optDouble("outline", 0.0).toFloat(),
            outlineColour = colour(c.optJSONObject("outline_colour")) ?: androidx.compose.ui.graphics.Color.Transparent,
            onClick     = buttonOnClick(c),
        )
    }

    fun text(c: JSONObject): String {
        val t = c.opt("text")
        return when {
            t is JSONObject && t.optString("\$") == "loc" -> loc(t.opt("key"))
            t is String -> t
            c.has("ref_value") -> read(c.getString("ref_value"))()
            else -> ""
        }
    }

    fun obj(c: JSONObject): Obj {
        val o = c.optJSONObject("object") ?: return DynaText(emptyList())
        return when (o.optString("\$")) {
            "dynatext" -> dynatext(o)
            else       -> DynaText(emptyList(), w = 0f, h = 0f)
        }
    }

    private fun dynatext(o: JSONObject): DynaText {
        val segsJ  = o.optJSONArray("segs") ?: return DynaText(emptyList())
        val colsJ  = o.optJSONArray("colours")
        val scale  = o.optDouble("scale", 1.0).toFloat()
        val shadow = o.optBoolean("shadow", true)
        val spacing = o.optDouble("spacing", 0.0).toFloat()
        val maxw   = o.optDouble("maxw", 0.0).toFloat()
        val segs   = (0 until segsJ.length()).map { i ->
            val sj  = segsJ.getJSONObject(i)
            val col = colsJ?.optString(i.coerceAtMost((colsJ.length()-1).coerceAtLeast(0)))
                ?.let { colourByName(it) } ?: Balatro.White
            val prefix = sj.optJSONObject("prefix")?.let { loc(it.opt("loc")) }
                ?: (sj.opt("prefix") as? String ?: "")
            val reader: () -> String = when {
                sj.has("value") -> read(sj.getString("value"))
                sj.has("text")  -> { val txt = sj.getString("text"); { txt } }
                else            -> { { "" } }
            }
            DynSeg({ prefix + reader() }, col, scale)
        }
        return DynaText(segs, maxw = maxw, shadow = shadow, spacing = spacing)
    }
}

// ── HudSpec.build overload for CardBind ──────────────────────────────────────────────────────────

/**
 * Build a UI tree from an offer-card sub-tree JSON (price tag or buy/redeem/open button) bound to
 * the per-offer [CardBind]. Structurally identical to HudSpec.build(JSONObject, HudBind) but drives
 * text/colour/click through [b]'s card-specific binders instead of RunState.
 */
internal fun buildCard(node: JSONObject, b: CardBind): UI {
    val cfgJ  = node.optJSONObject("config") ?: JSONObject()
    val cfg   = b.cfg(cfgJ)
    val nodesJ = node.optJSONArray("nodes")
    val kids  = if (nodesJ != null) (0 until nodesJ.length()).map { buildCard(nodesJ.getJSONObject(it), b) } else emptyList()
    return when (node.getString("n")) {
        "R", "ROOT" -> Ro(cfg, kids)
        "C"         -> Co(cfg, kids)
        "B"         -> Bx(cfg, kids)
        "T"         -> Tx(cfg, b.text(cfgJ))
        "O"         -> Ob(cfg, b.obj(cfgJ))
        else        -> Bx(cfg, kids)
    }
}

/**
 * Pre-parsed offer-card trees loaded once from shop_card_ui.json.
 * Call [forSet] to build the price + button UI pair bound to a specific offer.
 */
internal class OfferCardSpec(
    private val joker:      JSONObject?,
    private val voucher:    JSONObject?,
    private val booster:    JSONObject?,
    private val consumable: JSONObject?,
) {
    companion object {
        fun load(ctx: Context): OfferCardSpec? {
            val root = HudSpec.root(ctx, "shop_card_ui.json") ?: return null
            return OfferCardSpec(
                joker      = root.optJSONObject("joker"),
                voucher    = root.optJSONObject("voucher"),
                booster    = root.optJSONObject("booster"),
                consumable = root.optJSONObject("consumable"),
            )
        }
    }

    enum class Set { JOKER, VOUCHER, BOOSTER, CONSUMABLE }

    /**
     * Build (priceUI, buttonUI, buyAndUseUI?) for the given [set] bound to [b].
     * Returns null if the asset subtree is absent (graceful degradation to ShopCard fallback).
     */
    fun forSet(set: Set, b: CardBind): Triple<UI, UI, UI?>? {
        val sub = when (set) {
            Set.JOKER      -> joker
            Set.VOUCHER    -> voucher
            Set.BOOSTER    -> booster
            Set.CONSUMABLE -> consumable
        } ?: return null
        val priceJ  = sub.optJSONObject("price")  ?: return null
        val buttonJ = sub.optJSONObject("button") ?: return null
        val buyUseJ = sub.optJSONObject("buy_and_use")
        return Triple(
            buildCard(priceJ, b),
            buildCard(buttonJ, b),
            buyUseJ?.let { buildCard(it, b) },
        )
    }
}

// ── BlindSpec: extracted create_UIBox_blind_choice trees ────────────────────────────────────────

/**
 * Binds a blind_*_tree.json (extracted create_UIBox_blind_choice) to a single slot's live state.
 *
 * Three instances are created per blind-select render — one per slot. The JSON carries:
 *   - select button: colour ORANGE (enabled) / UI.BACKGROUND_INACTIVE (upcoming); button="select_blind"
 *   - T ref_value=<type>: reads loc_blind_states for the button label ("Select" / "Upcoming")
 *   - name band: DynaText segs=[{text:"<blind name>"}]; colour-op darken(get_blind_main_colour, 0.3)
 *   - blind sprite O: __sprite="animated" → blindBmp
 *   - stake sprite O: __sprite="stake"    → stakeBmp
 *   - chip target T: literal text from extraction (static per slot/ante)
 *   - reward T: "$$$+" literal
 *   - tag block O: __sprite="tag" → tinted placeholder (tag art deferred)
 *   - skip button: button="skip_blind" → skipAction (null for Boss slot)
 *   - boss extras: ante-up DynaText blocks (static strings)
 *
 * [slotType]   — "Small", "Big", or "Boss" (matches the `id` on the outer R node)
 * [enabled]    — true = this is the active slot (select button orange + clickable)
 * [bossColour] — the current boss's tint (null for Small/Big; used for "boss:*" colour names)
 * [blindBmp]   — animated sprite frame 0 crop from BlindChips.png for this slot
 * [stakeBmp]   — stake sprite (White Chip etc.)
 * [chipTarget] — formatted chip target string (e.g. "300")
 * [reward]     — dollars reward (3/4/5)
 * [selectAction] — called when the select button is tapped (null when !enabled)
 * [skipAction]   — called when the skip button is tapped (null for Boss slot)
 */
internal class BlindBind(
    val slotType: String,
    val enabled: Boolean,
    val bossColour: Color?,
    val blindBmp: ImageBitmap?,
    val stakeBmp: ImageBitmap?,
    val chipTarget: String,
    val reward: Int,
    val selectAction: (() -> Unit)?,
    val skipAction: (() -> Unit)?,
) {
    fun colourByName(name: String): Color = when {
        name == "WHITE" || name == "UI.TEXT_LIGHT" -> Balatro.White
        name == "UI.TEXT_INACTIVE"                 -> Balatro.Grey
        name == "UI.BACKGROUND_INACTIVE"           -> Balatro.Grey
        name == "BLACK"                            -> Balatro.Panel
        name == "L_BLACK"                          -> Balatro.PanelLight
        name == "GREY"                             -> Balatro.Grey
        name == "BLUE"  || name == "CHIPS"         -> Balatro.Chips
        name == "RED"   || name == "MULT"          -> Balatro.Mult
        name == "MONEY"                            -> Balatro.Money
        name == "GOLD"                             -> Balatro.Gold
        name == "ORANGE"                           -> Balatro.OrangeTrue
        name == "CLEAR"                            -> Color.Transparent
        name.startsWith("boss:")                   -> bossColour ?: Balatro.Mult
        else                                       -> Balatro.White
    }

    private fun colourOp(v: org.json.JSONObject): Color {
        fun resolve(j: org.json.JSONObject): Color = when (j.optString("\$")) {
            "colour"   -> colourByName(j.getString("name"))
            "colourop" -> colourOp(j)
            else       -> Balatro.White
        }
        val amt = v.optDouble("amt", 0.0).toFloat()
        return when (v.getString("op")) {
            "darken"  -> resolve(v.getJSONObject("base")).let { Color(it.red*(1-amt), it.green*(1-amt), it.blue*(1-amt), it.alpha) }
            "lighten" -> resolve(v.getJSONObject("base")).let { Color(it.red+(1-it.red)*amt, it.green+(1-it.green)*amt, it.blue+(1-it.blue)*amt, it.alpha) }
            "mix"     -> { val a = resolve(v.getJSONObject("a")); val c = resolve(v.getJSONObject("b"))
                Color(a.red*(1-amt)+c.red*amt, a.green*(1-amt)+c.green*amt, a.blue*(1-amt)+c.blue*amt, 1f) }
            "alpha"   -> resolve(v.getJSONObject("base")).copy(alpha = amt)
            else      -> Balatro.White
        }
    }

    private fun colour(v: org.json.JSONObject?): Color? {
        v ?: return null
        return when (v.optString("\$")) {
            "colour"   -> colourByName(v.getString("name"))
            "colourop" -> colourOp(v)
            else       -> null
        }
    }

    private fun buttonOnClick(c: org.json.JSONObject): (() -> Unit)? = when (c.optString("button")) {
        "select_blind" -> selectAction
        "skip_blind"   -> skipAction
        else           -> null
    }

    fun cfg(c: org.json.JSONObject): Cfg = Cfg(
        align        = c.optString("align", "cm"),
        colour       = colour(c.optJSONObject("colour")),
        padding      = c.optDouble("padding", 0.0).toFloat(),
        r            = c.optDouble("r", 0.0).toFloat(),
        minw         = c.optDouble("minw", 0.0).toFloat(),
        minh         = c.optDouble("minh", 0.0).toFloat(),
        maxw         = c.optDouble("maxw", 0.0).toFloat(),
        wCfg         = if (c.has("w")) c.optDouble("w", 0.0).toFloat() else null,
        hCfg         = if (c.has("h")) c.optDouble("h", 0.0).toFloat() else null,
        scale        = c.optDouble("scale", 1.0).toFloat(),
        textColour   = colour(c.optJSONObject("colour")) ?: Balatro.White,
        shadow       = c.optBoolean("shadow", false),
        emboss       = c.optDouble("emboss", 0.0).toFloat(),
        outline      = c.optDouble("outline", 0.0).toFloat(),
        outlineColour = colour(c.optJSONObject("outline_colour")) ?: Color.Transparent,
        onClick      = buttonOnClick(c),
    )

    fun text(c: org.json.JSONObject): String {
        val t = c.opt("text")
        val colourName = c.optJSONObject("colour")?.optString("name") ?: ""
        return when {
            // ref_value=type reads loc_blind_states: "Select" for active slot, "Upcoming" otherwise
            c.has("ref_value") -> if (enabled) "Select" else "Upcoming"
            // Chip target: extracted as literal (e.g. "300" at ante 1) — inject dynamic value.
            // Identified by colour=RED (chip count) and scale≈0.675.
            t is String && colourName == "RED" && t.all { it.isDigit() || it == ',' || it == '.' } -> chipTarget
            // Reward string: extracted as "$$$+" etc. — inject dynamic "$".repeat(reward)+"+".
            t is String && colourName == "MONEY" && t.startsWith("$") -> "${"$".repeat(reward)}+"
            t is String -> t
            else -> ""
        }
    }

    fun obj(c: org.json.JSONObject): Obj {
        val o = c.optJSONObject("object") ?: return DynaText(emptyList())
        return when (o.optString("\$")) {
            "dynatext" -> dynatext(o)
            "sprite"   -> when (o.optString("name")) {
                "animated" -> blindBmp?.let { Sprite(it, 1.4f, 1.4f) }
                              ?: DynaText(emptyList(), w = 1.4f, h = 1.4f)
                "stake"    -> stakeBmp?.let { Sprite(it, 0.5f, 0.5f) }
                              ?: DynaText(emptyList(), w = 0.5f, h = 0.5f)
                // tag sprite: tinted placeholder square (tag art atlas crop deferred)
                "tag"      -> DynaText(emptyList(), w = 0.8f, h = 0.8f)
                else       -> DynaText(emptyList(), w = 0f, h = 0f)
            }
            else       -> DynaText(emptyList(), w = 0f, h = 0f)
        }
    }

    private fun dynatext(o: org.json.JSONObject): DynaText {
        val segsJ  = o.optJSONArray("segs") ?: return DynaText(emptyList())
        val colsJ  = o.optJSONArray("colours")
        val scale  = o.optDouble("scale", 1.0).toFloat()
        val shadow = o.optBoolean("shadow", true)
        val maxw   = o.optDouble("maxw", 0.0).toFloat()
        val segs   = (0 until segsJ.length()).map { i ->
            val sj  = segsJ.getJSONObject(i)
            val col = colsJ?.optString(i.coerceAtMost((colsJ.length()-1).coerceAtLeast(0)))
                ?.let { colourByName(it) } ?: Balatro.White
            val reader: () -> String = when {
                sj.has("text") -> { val txt = sj.getString("text"); { txt } }
                else           -> { { "" } }
            }
            DynSeg(reader, col, scale)
        }
        return DynaText(segs, maxw = maxw, shadow = shadow)
    }
}

/** Build a UI tree from a blind_*_tree.json node, bound to [b]. */
internal fun buildBlind(node: org.json.JSONObject, b: BlindBind): UI {
    val cfgJ   = node.optJSONObject("config") ?: org.json.JSONObject()
    val cfg    = b.cfg(cfgJ)
    val nodesJ = node.optJSONArray("nodes")
    val kids   = if (nodesJ != null) (0 until nodesJ.length()).map { buildBlind(nodesJ.getJSONObject(it), b) } else emptyList()
    return when (node.getString("n")) {
        "R", "ROOT" -> Ro(cfg, kids)
        "C"         -> Co(cfg, kids)
        "B"         -> Bx(cfg, kids)
        "T"         -> Tx(cfg, b.text(cfgJ))
        "O"         -> Ob(cfg, b.obj(cfgJ))
        else        -> Bx(cfg, kids)
    }
}

/**
 * Pre-loaded blind choice trees (blind_small_tree.json / _big_ / _boss_).
 * Call [forSlot] to build the choice card UI for one slot bound to live state.
 */
internal class BlindSpec(
    private val small: org.json.JSONObject?,
    private val big:   org.json.JSONObject?,
    private val boss:  org.json.JSONObject?,
) {
    companion object {
        fun load(ctx: android.content.Context): BlindSpec = BlindSpec(
            small = HudSpec.root(ctx, "blind_small_tree.json"),
            big   = HudSpec.root(ctx, "blind_big_tree.json"),
            boss  = HudSpec.root(ctx, "blind_boss_tree.json"),
        )
    }

    /**
     * Build the UI tree for slot [slotIdx] (0=Small, 1=Big, 2=Boss).
     * Returns null if the JSON asset for that slot failed to load.
     *
     * [enabled]      — true when this slot is the current active one (select button orange)
     * [bossColour]   — the upcoming boss's tint; only meaningful for slotIdx=2
     * [blindBmp]     — cropped blind chip sprite for this slot (null → B spacer)
     * [stakeBmp]     — stake chip sprite (null → B spacer)
     * [chipTarget]   — formatted chip requirement string (e.g. "300")
     * [reward]       — dollar count for this slot (3/4/5)
     * [selectAction] — fired when "Select" is tapped; null when !enabled
     * [skipAction]   — fired when "Skip Blind" is tapped; null for Boss slot
     */
    fun forSlot(
        slotIdx: Int,
        enabled: Boolean,
        bossColour: Color?,
        blindBmp: ImageBitmap?,
        stakeBmp: ImageBitmap?,
        chipTarget: String,
        reward: Int,
        selectAction: (() -> Unit)?,
        skipAction: (() -> Unit)?,
    ): UI? {
        val (tree, slotType) = when (slotIdx) {
            0 -> (small ?: return null) to "Small"
            1 -> (big   ?: return null) to "Big"
            2 -> (boss  ?: return null) to "Boss"
            else -> return null
        }
        val bind = BlindBind(
            slotType    = slotType,
            enabled     = enabled,
            bossColour  = bossColour,
            blindBmp    = blindBmp,
            stakeBmp    = stakeBmp,
            chipTarget  = chipTarget,
            reward      = reward,
            selectAction = selectAction,
            skipAction   = skipAction,
        )
        return buildBlind(tree, bind)
    }
}
