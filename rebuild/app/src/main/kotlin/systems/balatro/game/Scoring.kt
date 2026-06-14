package systems.balatro.game

import systems.balatro.engine.Component
import systems.balatro.engine.Entity
import systems.balatro.engine.World

/**
 * Scoring as composition. A joker does NOT override `calculate`; it REGISTERS an
 * Effect — data (which contexts it reacts to) plus a pure handler. Scoring a hand
 * visits, per context, only the jokers SUBSCRIBED to that context, in board order,
 * over a single reused Context object. No per-event table allocation (the old
 * ~340-tables-per-hand GC churn), no monkeypatched dispatch.
 *
 * Correctness contract: for every ported joker, the score this produces on a fixed
 * seed must equal the original LÖVE build's score (verified by score-oracle). The
 * registration order = original SMODS joker order; context identity is the single
 * reused object; big numbers pass through the BigValue type uncoerced.
 */

/** The points being accumulated. BigValue wraps the eventual Talisman/OmegaNum path. */
class Tally {
    var chips: BigValue = BigValue.ZERO
    var mult: BigValue = BigValue.ONE
    fun reset() { chips = BigValue.ZERO; mult = BigValue.ONE }
    fun score(): BigValue = chips * mult
}

/** The contexts a joker can react to. Closed set => a subscription is just a bitmask. */
enum class Ctx { BEFORE, INDIVIDUAL_SCORED, INDIVIDUAL_HELD, JOKER_MAIN, OTHER_JOKER, RETRIGGER, AFTER, END_OF_ROUND }

/**
 * The single, reused dispatch context. One instance per scoring run; its fields are
 * rewritten per visit, never reallocated. Because it is one stable object, any
 * effect comparing context identity still works — and there is nothing to GC.
 */
class Context {
    lateinit var phase: Ctx
    var scoredCard: Entity = 0          // the playing card entity being scored (INDIVIDUAL_*)
    var scoredPlaying: PlayingCard? = null  // its data (suit/rank/chips)
    var scoringCards: List<PlayingCard> = emptyList()  // the scoring cards only (kickers excluded)
    var playedCards: List<PlayingCard> = emptyList()   // the whole played hand (incl. non-scoring)
    var self: Entity = 0                // the joker whose handler is running
    var otherJoker: Entity = 0          // the board joker being offered (OTHER_JOKER pass)
    val tally = Tally()
    var retriggers: Int = 0             // an effect may request repeats of the current card
}

/** A joker's contribution. Pure: reads the context, mutates the tally, returns nothing. */
fun interface Effect { fun apply(world: World, ctx: Context) }

/**
 * Set by a joker's END_OF_ROUND handler to mark itself for destruction at round end.
 * Effects.dispatchEndOfRound sweeps world.store<SelfDestruct>() after the pass completes
 * and destroys every marked entity.
 */
class SelfDestruct : Component

/**
 * The subscription index: ctx -> ordered list of (joker entity, effect). A context
 * dispatch walks ONE list, not every joker. Insertion order = board order =
 * deterministic, matching the original left-to-right scoring cascade.
 */
class Effects {
    private val byCtx = HashMap<Ctx, ArrayList<Pair<Entity, Effect>>>()

    /** A ported joker calls this once when it enters play. */
    fun register(joker: Entity, contexts: Set<Ctx>, effect: Effect) {
        for (c in contexts) byCtx.getOrPut(c) { ArrayList() }.add(joker to effect)
    }

    fun unregister(joker: Entity) {
        for (list in byCtx.values) list.removeAll { it.first == joker }
    }

    /** Visit only the subscribers of `phase`, in board order, over the reused ctx. */
    fun dispatch(world: World, ctx: Context, phase: Ctx) {
        val list = byCtx[phase] ?: return
        ctx.phase = phase
        for ((joker, effect) in list) { ctx.self = joker; effect.apply(world, ctx) }
    }

    /**
     * Run ONLY `joker`'s effect(s) for `phase`, with self set to it — the copy primitive
     * a blueprint joker uses to re-apply the joker beside it. Self is saved/restored so the
     * caller (whose own handler is mid-dispatch) sees no change to the reused ctx.
     */
    fun dispatchJoker(world: World, ctx: Context, phase: Ctx, joker: Entity) {
        val list = byCtx[phase] ?: return
        val saved = ctx.self
        ctx.phase = phase
        for ((j, effect) in list) if (j == joker) { ctx.self = joker; effect.apply(world, ctx) }
        ctx.self = saved
    }

    /**
     * Fire the END_OF_ROUND pass after the blind is beaten. Handlers that want to self-destruct
     * call world.add(ctx.self, SelfDestruct). This method then sweeps, unregisters, and destroys
     * all marked entities, returning the set of destroyed entities so the caller can
     * clean up run-level state (owned list, UI). Uses a fresh Context so no stale scoring
     * state bleeds into end-of-round handlers.
     */
    fun dispatchEndOfRound(world: World): Set<Entity> {
        val eorCtx = Context()
        dispatch(world, eorCtx, Ctx.END_OF_ROUND)
        val destroyed = mutableSetOf<Entity>()
        world.store<SelfDestruct>().each { e, _ -> destroyed.add(e) }
        for (e in destroyed) { unregister(e); world.destroy(e) }
        return destroyed
    }
}

/**
 * Scoring a played hand: the cascade, composed. This is the whole scoring loop —
 * no overrides, no event queue of closures referencing torn-down state (the rewind
 * crash class is structurally gone), just ordered context dispatch over pooled state.
 */
/** A scored hand's breakdown, so UI can show the chips x mult = score cascade, not just the total. */
data class ScoreResult(val handType: HandType, val chips: Double, val mult: Double, val score: Double)

/** One visible step of the cascade — the running chips x mult after `label` resolved. */
data class ScoreStep(val label: String, val chips: Double, val mult: Double)

class ScoreRun(private val effects: Effects) {
    private val ctx = Context()

    /** The final score only (what the oracle asserts). Delegates to the detailed cascade. */
    fun scoreHand(world: World, played: List<PlayingCard>): BigValue = BigValue.of(scoreDetailed(world, played).score)

    /**
     * Score a hand, optionally recording the cascade into `trace` (base -> each scoring
     * card -> jokers -> final) so the UI can show it building. trace=null => no overhead,
     * which is the oracle/hot path.
     */
    fun scoreDetailed(world: World, played: List<PlayingCard>, trace: MutableList<ScoreStep>? = null, debuff: Debuff = Debuff.None, held: List<PlayingCard> = emptyList()): ScoreResult {
        // Compose any active rank remaps (maximized &c.) into the effective rank hand
        // detection sees. Empty store => identity, so the no-remap path is unchanged.
        val remaps = ArrayList<(Int) -> Int>()
        world.store<RankMod>().each { _, m -> remaps.add(m.map) }
        val rankOf: (PlayingCard) -> Int = { c -> remaps.fold(c.rank) { r, m -> m(r) } }
        val (handType, scoring) = Hands.evaluate(played, rankOf)   // base chips/mult + the scoring cards
        ctx.tally.reset()
        // Hand base, raised by its planet level (level 1 => unchanged), then halved by Flint.
        val lvl = Levels.get(world)?.level(handType) ?: 1
        var baseChips = handType.baseChips + (lvl - 1) * handType.lChips
        var baseMult = handType.baseMult + (lvl - 1) * handType.lMult
        if (debuff is Debuff.Flint) { baseChips /= 2; baseMult /= 2 }
        ctx.tally.chips = BigValue.of(baseChips)
        ctx.tally.mult = BigValue.of(baseMult)
        ctx.scoringCards = scoring                                      // shape-aware jokers inspect the scoring cards
        ctx.playedCards = played                                        // ...or the full played hand (primus prime-check)
        fun step(label: String) = trace?.add(ScoreStep(label, ctx.tally.chips.v, ctx.tally.mult.v))
        effects.dispatch(world, ctx, Ctx.BEFORE)
        step("base · ${handType.name.lowercase().replace('_', ' ')}")
        for (card in scoring) {
            val debuffed = debuff is Debuff.DebuffSuit && card.suit == debuff.suit  // scores nothing, triggers nothing
            ctx.scoredPlaying = card
            ctx.retriggers = 0                                          // subscribers may add repeats
            if (!debuffed) {
                effects.dispatch(world, ctx, Ctx.RETRIGGER)
                if (card.seal == Seal.RED) ctx.retriggers += 1         // red seal: one extra trigger
            }
            val triggers = 1 + ctx.retriggers
            repeat(triggers) {                                         // each trigger re-scores the card whole
                if (!debuffed) {
                    val cardChips = if (card.enhancement == Enhancement.STONE) 50 else card.chips  // stone: flat 50, no rank
                    ctx.tally.chips = ctx.tally.chips + BigValue.of(cardChips)
                    when (card.enhancement) {                          // the card's own enhancement, then jokers react
                        Enhancement.BONUS -> ctx.tally.chips = ctx.tally.chips + BigValue.of(30)
                        Enhancement.MULT -> ctx.tally.mult = ctx.tally.mult + BigValue.of(4)
                        Enhancement.GLASS -> ctx.tally.mult = ctx.tally.mult * BigValue.of(2)
                        Enhancement.STEEL, Enhancement.GOLD, Enhancement.WILD, Enhancement.STONE, Enhancement.NONE -> {}  // held / hand-eval / handled above
                    }
                    effects.dispatch(world, ctx, Ctx.INDIVIDUAL_SCORED)
                }
            }
            step("+ ${card.label}" + if (debuffed) " (debuffed)" else if (triggers > 1) " ×$triggers" else "")
        }
        effects.dispatch(world, ctx, Ctx.JOKER_MAIN)
        // The for-each-other-joker pass: every board joker is offered to the OTHER_JOKER
        // subscribers once, in board order (no self-exclusion — a joker is offered itself too).
        for (other in Board.order(world)) { ctx.otherJoker = other; effects.dispatch(world, ctx, Ctx.OTHER_JOKER) }
        // held-in-hand pass: Steel cards x1.5 Mult while held; jokers may also react (INDIVIDUAL_HELD).
        for (card in held) {
            ctx.scoredPlaying = card
            if (card.enhancement == Enhancement.STEEL) ctx.tally.mult = ctx.tally.mult * BigValue.of(1.5)
            effects.dispatch(world, ctx, Ctx.INDIVIDUAL_HELD)
        }
        effects.dispatch(world, ctx, Ctx.AFTER)
        if (held.any { it.enhancement == Enhancement.STEEL }) step("held steel")
        step("jokers")
        return ScoreResult(handType, ctx.tally.chips.v, ctx.tally.mult.v,
            kotlin.math.floor(ctx.tally.score().v))                     // Balatro floors the final
    }
}
