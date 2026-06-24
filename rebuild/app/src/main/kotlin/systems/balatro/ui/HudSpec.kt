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

    fun loc(key: Any?): String {
        // {type=variable} loc tables carry a key + vars (e.g. ante_x_voucher = "ANTE #1# VOUCHER").
        if (key is JSONObject) return when (key.optString("key")) {
            "ante_x_voucher" -> "ANTE ${s.ante} VOUCHER"   // #1# = current ante (round_resets.ante)
            else -> ""
        }
        return when (key) {
            "k_hud_hands" -> "Hands"; "k_hud_discards" -> "Discards"
            "k_ante" -> "Ante"; "k_round" -> "Round"; "k_lower_score" -> "score"
            "$" -> "$"; "b_options" -> "Options"; "b_run_info_1" -> "Run"; "b_run_info_2" -> "Info"
            // shop
            "b_next_round_1" -> "Next"; "b_next_round_2" -> "Round"; "k_reroll" -> "Reroll"
            // offer cards (create_shop_card_ui)
            "b_buy" -> "Buy"; "b_redeem" -> "Redeem"; "b_open" -> "Open"; "b_and_use" -> "and Use"
            else -> if (key is String) key else ""
        }
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
        maxh = c.optDouble("maxh", 0.0).toFloat(),
        wCfg = if (c.has("w")) c.optDouble("w", 0.0).toFloat() else null,
        hCfg = if (c.has("h")) c.optDouble("h", 0.0).toFloat() else null,
        scale = c.optDouble("scale", 1.0).toFloat(),
        textColour = colour(c.optJSONObject("colour")) ?: Balatro.White,
        shadow = c.optBoolean("shadow", false),
        vert = c.optBoolean("vert", false),   // vertical sidebar label (e.g. "ANTE x VOUCHER")
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
    val mostPlayedHand: String = "High Card",   // for the boss debuff "#1#" var (vanilla: most_played_poker_hand)
) {
    fun colourByName(name: String): Color = when {
        name == "WHITE" || name == "UI.TEXT_LIGHT" -> Balatro.White
        name == "UI.TEXT_INACTIVE"                 -> Color(0xFFC2C9CF)   // legible light grey — the in-card "Skip Blind" label
        name == "UI.BACKGROUND_INACTIVE"           -> Color(0xFF5A656B)   // distinct darker grey — the inactive skip-button fill
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
            // The status button reads loc_blind_states via a {$:"ref", path:…} ref_table → "Select" for
            // the active slot, "Upcoming" otherwise.
            c.optJSONObject("ref_table")?.optString("\$") == "ref" -> if (enabled) "Select" else "Upcoming"
            // Any other ref node (the boss debuff prefix is ref_value=val, ref_table={val:""}) reads its
            // ref_table value verbatim — here empty, so it must NOT echo the blind state.
            c.has("ref_value") -> c.optJSONObject("ref_table")?.optString("val", "") ?: ""
            // Chip target: extracted as literal (e.g. "300" at ante 1) — inject dynamic value.
            // Identified by colour=RED (chip count) and scale≈0.675.
            t is String && colourName == "RED" && t.all { it.isDigit() || it == ',' || it == '.' } -> chipTarget
            // Reward string: extracted as "$$$+" etc. — inject dynamic "$".repeat(reward)+"+".
            t is String && colourName == "MONEY" && t.startsWith("$") -> "${"$".repeat(reward)}+"
            // Boss debuff (The Ox etc.) carries a "#1#" var — vanilla substitutes the most-played hand name.
            t is String -> t.replace("#1#", mostPlayedHand)
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
        mostPlayedHand: String = "High Card",
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
            mostPlayedHand = mostPlayedHand,
        )
        return buildBlind(tree, bind)
    }
}

// ── PackSpec: extracted create_UIBox_*_pack trees ────────────────────────────────────────────────

/**
 * Binds a pack_*_tree.json (extracted create_UIBox_arcana/spectral/standard/buffoon/celestial_pack)
 * to live RunState. The pack frame has:
 *  - O(CardAreaSlot "pack_cards"): filled by [cardAreaContent] with the revealed items
 *  - DynaText("Arcana Pack" / etc.): static literal, already baked in JSON — no binding needed
 *  - DynaText(ref=G.GAME/val=pack_choices): mapped to [picksLeft]
 *  - button=skip_booster / func=can_skip_booster: always active → [onSkip]
 *
 * [picksLeft]  — RunState.openPack.picksLeft (how many items can still be picked)
 * [onSkip]     — called when the Skip button is tapped
 */
internal class PackBind(val picksLeft: Int, val onSkip: () -> Unit) {

    fun colourByName(name: String): Color = when (name) {
        "WHITE", "UI.TEXT_LIGHT" -> Balatro.White
        "BLACK"                  -> Balatro.Panel
        "L_BLACK"                -> Balatro.PanelLight
        "GREY"                   -> Balatro.Grey
        "GOLD"                   -> Balatro.Gold
        "GREEN"                  -> Balatro.Green
        "RED", "MULT"            -> Balatro.Mult
        "CLEAR"                  -> Color.Transparent
        else                     -> Balatro.White
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

    private fun buttonOnClick(c: org.json.JSONObject): (() -> Unit)? {
        return when (c.optString("button")) {
            "skip_booster" -> onSkip
            else           -> null
        }
    }

    fun cfg(c: org.json.JSONObject): Cfg {
        // Skip button in vanilla is GREY (func=can_skip_booster gates it). We always allow skip,
        // so render it as Mult (RED) to match what the hand-built Skip button uses.
        val rawColour = colour(c.optJSONObject("colour"))
        val isSkipBtn = c.optString("button") == "skip_booster"
        val fill = if (isSkipBtn) Balatro.Mult else rawColour
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
            outlineColour = colour(c.optJSONObject("outline_colour")) ?: Color.Transparent,
            onClick     = buttonOnClick(c),
        )
    }

    fun text(c: org.json.JSONObject): String {
        val t = c.opt("text")
        return when {
            t is String -> t
            t is org.json.JSONObject && t.optString("\$") == "loc" -> when (t.opt("key")) {
                "b_skip" -> "Skip"
                else     -> t.optString("key", "")
            }
            else -> ""
        }
    }

    fun obj(c: org.json.JSONObject): Obj {
        val o = c.optJSONObject("object") ?: return DynaText(emptyList())
        return when (o.optString("\$")) {
            "dynatext"  -> dynatext(o)
            "cardarea"  -> CardAreaSlot(o.getString("name"), o.optDouble("w",0.0).toFloat(), o.optDouble("h",0.0).toFloat())
            else        -> DynaText(emptyList())
        }
    }

    private fun dynatext(o: org.json.JSONObject): DynaText {
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
            // ref=G.GAME / value=pack_choices → live picksLeft binding
            val reader: () -> String = when {
                sj.has("ref") && sj.optString("value") == "pack_choices" -> { { picksLeft.toString() } }
                sj.has("text") -> { val txt = sj.getString("text"); { txt } }
                else -> { { "" } }
            }
            DynSeg(reader, col, scale)
        }
        return DynaText(segs, maxw = maxw, shadow = shadow, spacing = spacing)
    }
}

internal fun buildPack(node: org.json.JSONObject, b: PackBind): UI {
    val cfgJ  = node.optJSONObject("config") ?: org.json.JSONObject()
    val cfg   = b.cfg(cfgJ)
    val nodesJ = node.optJSONArray("nodes")
    val kids  = if (nodesJ != null) (0 until nodesJ.length()).map { buildPack(nodesJ.getJSONObject(it), b) } else emptyList()
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
 * Pre-parsed pack-open frame trees from assets. One JSON per pack type; all have the same structure
 * (CardArea slot + title + choose-N readout + Skip button). [forPack] builds the frame UI tree
 * bound to the current pack's picks-left count and skip action.
 */
internal class PackSpec(
    private val arcana:    org.json.JSONObject?,
    private val spectral:  org.json.JSONObject?,
    private val standard:  org.json.JSONObject?,
    private val buffoon:   org.json.JSONObject?,
    private val celestial: org.json.JSONObject?,
) {
    companion object {
        fun load(ctx: android.content.Context): PackSpec = PackSpec(
            arcana    = HudSpec.root(ctx, "pack_arcana_tree.json"),
            spectral  = HudSpec.root(ctx, "pack_spectral_tree.json"),
            standard  = HudSpec.root(ctx, "pack_standard_tree.json"),
            buffoon   = HudSpec.root(ctx, "pack_buffoon_tree.json"),
            celestial = HudSpec.root(ctx, "pack_celestial_tree.json"),
        )
    }

    enum class Kind { ARCANA, SPECTRAL, STANDARD, BUFFOON, CELESTIAL }

    fun forPack(kind: Kind, b: PackBind): UI? {
        val tree = when (kind) {
            Kind.ARCANA    -> arcana
            Kind.SPECTRAL  -> spectral
            Kind.STANDARD  -> standard
            Kind.BUFFOON   -> buffoon
            Kind.CELESTIAL -> celestial
        } ?: return null
        return buildPack(tree, b)
    }
}

// ── RoundEvalSpec: extracted create_UIBox_round_evaluation skeleton ──────────────────────────────

/**
 * Provides the extracted `create_UIBox_round_evaluation` frame (round_eval_tree.json) as a UI
 * tree. The frame has three empty id-slotted R nodes: "base_round_eval", "bonus_round_eval",
 * "eval_bottom". These are filled at render time via [cardAreaContent] using the same slot-name
 * mechanism as CardAreaSlot — the [HudSpec.build] pass converts id-matched R nodes to
 * CardAreaSlot O-nodes so UILayout's existing cardAreaContent callback handles them.
 *
 * The conversion happens in [buildRoundEval]: any empty R node with a known eval-slot id
 * (`base_round_eval`, `bonus_round_eval`, `eval_bottom`) is replaced by an O(CardAreaSlot(id)).
 */
internal object RoundEvalSpec {
    private val EVAL_SLOT_IDS = setOf("base_round_eval", "bonus_round_eval", "eval_bottom")

    fun load(ctx: android.content.Context): org.json.JSONObject? =
        HudSpec.root(ctx, "round_eval_tree.json")

    fun build(node: org.json.JSONObject): UI = buildRoundEval(node)
}

private fun buildRoundEval(node: org.json.JSONObject): UI {
    val cfgJ   = node.optJSONObject("config") ?: org.json.JSONObject()
    val id     = cfgJ.optString("id")
    // Convert known empty eval-slot R nodes into CardAreaSlot O-nodes so the renderer
    // invokes cardAreaContent(id, x, y, w, h) — same pattern as shop/pack card areas.
    if (id in setOf("base_round_eval", "bonus_round_eval", "eval_bottom")) {
        val minw = cfgJ.optDouble("minw", 0.0).toFloat()
        var minh = cfgJ.optDouble("minh", 0.0).toFloat()
        // The extracted slots are empty (0 height — their reward rows are added dynamically at runtime,
        // not captured in the static tree). Give the content slots room so the overlaid rows render
        // instead of collapsing to a zero-height strip. base holds the blind1 chip row (~1.1u) + the
        // dotted divider + the hands row, so it needs more height than the text-only bonus slot.
        if (minh <= 0f) minh = when (id) { "eval_bottom" -> 1.1f; "base_round_eval" -> 2.0f; else -> 1.5f }
        val align = cfgJ.optString("align", "cm")
        val slotCfg = Cfg(align = align, minw = minw, minh = minh)
        return Ob(slotCfg, CardAreaSlot(id, minw, minh))
    }
    val cfg    = buildRoundEvalCfg(cfgJ)
    val nodesJ = node.optJSONArray("nodes")
    val kids   = if (nodesJ != null) (0 until nodesJ.length()).map { buildRoundEval(nodesJ.getJSONObject(it)) } else emptyList()
    return when (node.getString("n")) {
        "R", "ROOT" -> Ro(cfg, kids)
        "C"         -> Co(cfg, kids)
        "B"         -> Bx(cfg, kids)
        "T"         -> Tx(cfg, cfgJ.optString("text", ""))
        "O"         -> Bx(cfg)  // no O-nodes in round_eval skeleton besides the slots
        else        -> Bx(cfg, kids)
    }
}

/** End-screen (game-over / win) callbacks bound to the extracted tree's buttons. */
internal class GameOverBind(val s: RunState, val onRestart: () -> Unit, val onMainMenu: () -> Unit)

/** Renders the REAL create_UIBox_game_over / create_UIBox_win trees (game_over_tree.json /
 *  win_tree.json). Structure + labels + buttons come from the extracted tree; stat values bind to
 *  RunState where tracked (ante/round/rerolls/score/seed), else show "0" (the rest aren't tracked). */
internal object GameOverSpec {
    fun load(ctx: Context, win: Boolean): org.json.JSONObject? =
        HudSpec.root(ctx, if (win) "win_tree.json" else "game_over_tree.json")
    fun build(node: org.json.JSONObject, b: GameOverBind): UI = buildGameOver(node, b)
}

private fun gameOverColour(name: String): Color = when (name) {
    "WHITE", "UI.TEXT_LIGHT" -> Balatro.White
    "BLACK", "DYN_UI.MAIN", "DYN_UI.DARK", "DYN_UI.BOSS_MAIN", "DYN_UI.BOSS_DARK" -> Balatro.Panel
    "L_BLACK" -> Balatro.PanelLight
    "UI.TEXT_DARK" -> Balatro.Ink                 // dark text (run-info level pip) — NOT the light L_BLACK
    "EDITION" -> Color(0xFF8B73EB)                // holographic purple (YOU WIN! title)
    "RED", "MULT" -> Balatro.Mult; "BLUE", "CHIPS" -> Balatro.Chips
    "GREEN" -> Balatro.Green; "MONEY" -> Balatro.Money; "GOLD" -> Balatro.Gold
    "IMPORTANT", "FILTER" -> Balatro.Orange; "GREY", "JOKER_GREY" -> Balatro.Grey
    // run-info poker-hand level pips (white → blue/green/pink/gold/orange → red)
    "HAND_LEVELS.1" -> Color(0xFFE7E7E7); "HAND_LEVELS.2" -> Color(0xFF93C2EB)
    "HAND_LEVELS.3" -> Color(0xFF96E69B); "HAND_LEVELS.4" -> Color(0xFFEB9BBA)
    "HAND_LEVELS.5" -> Color(0xFFF0C97A); "HAND_LEVELS.6" -> Color(0xFFF1A25F)
    "HAND_LEVELS.7" -> Color(0xFFFE5F55)
    "CLEAR" -> Color.Transparent; else -> Color.Transparent
}

private val GAMEOVER_STAT_IDS = setOf("cards_played", "cards_discarded", "cards_purchased",
    "times_rerolled", "hand", "poker_hand", "seed", "furthest_ante", "furthest_round", "defeated_by", "new_collection")

/** The run stat for a round_scores_row id, from RunState (the same values the old hand-built screen showed). */
private fun gameOverStat(id: String, s: RunState): String = when (id) {
    "cards_played"    -> s.totalCardsPlayed.toString()
    "cards_discarded" -> s.totalCardsDiscarded.toString()
    "cards_purchased" -> s.totalCardsPurchased.toString()
    "times_rerolled"  -> s.timesRerolled.toString()
    "hand"            -> s.totalChipsScored.toLong().toString()
    "poker_hand"      -> s.mostPlayedHand?.let { handName(it.first) } ?: "None"
    "seed"            -> s.runSeed
    "furthest_ante"   -> s.ante.toString()
    "furthest_round"  -> (s.blindIndex + 1).toString()
    "defeated_by"     -> s.blindName
    else              -> "0"
}

/** True if the subtree renders any visible text (a T with text or a dynatext O). */
private fun goHasText(node: org.json.JSONObject): Boolean {
    val cfgJ = node.optJSONObject("config")
    if (node.optString("n") == "T" && cfgJ?.opt("text") != null) return true
    if (cfgJ?.optJSONObject("object")?.optString("\$") == "dynatext") return true
    val kids = node.optJSONArray("nodes") ?: return false
    for (i in 0 until kids.length()) if (goHasText(kids.getJSONObject(i))) return true
    return false
}

private fun buildGameOver(node: org.json.JSONObject, b: GameOverBind, statId: String? = null): UI {
    val cfgJ = node.optJSONObject("config") ?: org.json.JSONObject()
    // Prune the decorative text-less columns — the jimbo_spot sprite column (config padding=2) and the
    // overlay_menu_infotip backing. We skip those sprites anyway, but their reserved layout width shoves
    // the centred dialog off-screen (right column + buttons clipped). Collapsing them to zero size lets
    // the dialog centre on its own bounds. Scoped to subtrees that contain ONLY those ids (no text).
    val tag0 = node.optString("n")
    if ((tag0 == "C" || tag0 == "R" || tag0 == "B") && !goHasText(node)) {
        val s = node.toString()
        if ("jimbo_spot" in s || "overlay_menu_infotip" in s) return Bx(Cfg())
    }
    val myId = cfgJ.optString("id")
    val childStat = if (myId in GAMEOVER_STAT_IDS) myId else statId   // thread the stat-row id to its DynaText
    val cv = cfgJ.optJSONObject("colour")
    val fill: Color? = when (cv?.optString("\$")) {
        "colour" -> gameOverColour(cv.getString("name"))
        "colourop" -> {   // darken(base, amt) — run-info hand-row backgrounds
            val amt = cv.optDouble("amt", 0.0).toFloat()
            val base = cv.optJSONObject("base")
            val bc = if (base?.optString("\$") == "colour") gameOverColour(base.getString("name")) else Balatro.Grey
            if (cv.optString("op") == "darken") Color(bc.red*(1-amt), bc.green*(1-amt), bc.blue*(1-amt), bc.alpha) else bc
        }
        else -> null
    }
    val onClick: (() -> Unit)? = when (cfgJ.optString("button")) {
        "notify_then_setup_run" -> b.onRestart            // New Run
        "go_to_menu", "exit_overlay_menu", "x" -> b.onMainMenu
        else -> null
    }
    val cfg = Cfg(
        align = cfgJ.optString("align", "cm"), colour = fill,
        padding = cfgJ.optDouble("padding", 0.0).toFloat(), r = cfgJ.optDouble("r", 0.0).toFloat(),
        minw = cfgJ.optDouble("minw", 0.0).toFloat(), minh = cfgJ.optDouble("minh", 0.0).toFloat(),
        emboss = cfgJ.optDouble("emboss", 0.0).toFloat(), shadow = cfgJ.optBoolean("shadow", false),
        textColour = (cv?.takeIf { it.optString("\$") == "colour" }?.let { gameOverColour(it.getString("name")) }) ?: Balatro.White,
        onClick = onClick,
    )
    val nodesJ = node.optJSONArray("nodes")
    val kids = if (nodesJ != null) (0 until nodesJ.length()).map { buildGameOver(nodesJ.getJSONObject(it), b, childStat) } else emptyList()
    return when (node.getString("n")) {
        "R", "ROOT" -> Ro(cfg, kids)
        "C" -> Co(cfg, kids)
        "B" -> Bx(cfg, kids)
        "T" -> { val t = cfgJ.opt("text"); Tx(cfg, when {
            // Most-played row: the static " (N)" count beside the hand-name dynatext → live count.
            childStat == "poker_hand" && t is String && Regex("^ \\(\\d+\\)$").matches(t) ->
                " (${b.s.mostPlayedHand?.second ?: 0})"
            t is String -> t
            t is Number -> { val d = t.toDouble(); if (d == d.toLong().toDouble()) d.toLong().toString() else t.toString() }  // run-info chips/mult/played are numbers
            t is org.json.JSONObject && t.optString("\$") == "loc" -> t.opt("key")?.toString() ?: ""
            else -> "" }) }
        "O" -> {
            val o = cfgJ.optJSONObject("object")
            if (o?.optString("\$") == "dynatext") {
                val segsJ = o.optJSONArray("segs"); val cols = o.optJSONArray("colours")
                val col = cols?.optString(0)?.let { gameOverColour(it) } ?: Balatro.White
                val scale = o.optDouble("scale", 1.0).toFloat()
                val segs = if (segsJ != null) (0 until segsJ.length()).map { i ->
                    val sj = segsJ.getJSONObject(i)
                    val txt = when {
                        childStat != null -> gameOverStat(childStat, b.s)   // live run stat overrides the captured snapshot
                        sj.has("text") -> sj.getString("text")
                        else -> ""
                    }
                    DynSeg({ txt }, col, scale)
                } else emptyList()
                Ob(cfg, DynaText(segs))
            } else Bx(cfg)   // sprites (jimbo/chips) → skip; structure still renders
        }
        else -> Bx(cfg, kids)
    }
}

private fun buildRoundEvalCfg(c: org.json.JSONObject): Cfg {
    fun colourByName(name: String): Color = when (name) {
        "BLACK"                  -> Color(0xFF1A2123)   // distinct near-black frame — NOT the scene-matching Panel
        "DYN_UI.MAIN"            -> Balatro.Panel
        "DYN_UI.BOSS_DARK"       -> Balatro.Panel
        "UI.TRANSPARENT_DARK"    -> Balatro.Panel.copy(alpha = 0.3f)
        "CLEAR"                  -> Color.Transparent
        else                     -> Color.Transparent
    }
    fun colourOp(v: org.json.JSONObject): Color {
        val amt = v.optDouble("amt", 0.0).toFloat()
        fun resolve(j: org.json.JSONObject): Color = when (j.optString("\$")) {
            "colour" -> colourByName(j.getString("name")); else -> Color.Transparent
        }
        return when (v.getString("op")) {
            "darken" -> resolve(v.getJSONObject("base")).let { Color(it.red*(1-amt), it.green*(1-amt), it.blue*(1-amt), it.alpha) }
            else     -> Color.Transparent
        }
    }
    val cv = c.optJSONObject("colour")
    val fill: Color? = when (cv?.optString("\$")) {
        "colour"   -> colourByName(cv.getString("name"))
        "colourop" -> colourOp(cv)
        else       -> null
    }
    return Cfg(
        align   = c.optString("align", "cm"),
        colour  = fill,
        padding = c.optDouble("padding", 0.0).toFloat(),
        r       = c.optDouble("r", 0.0).toFloat(),
        minw    = c.optDouble("minw", 0.0).toFloat(),
        minh    = c.optDouble("minh", 0.0).toFloat(),
        emboss  = c.optDouble("emboss", 0.0).toFloat(),
        shadow  = c.optBoolean("shadow", false),
    )
}
