package systems.balatro.engine

/**
 * Port of engine/event.lua — the timing queue that drives EVERY delay, ease, juice, card-move, and
 * the whole scoring cascade in Balatro (P0-T2). Five FIFO queues are drained at a fixed 1/60 step,
 * decoupled from the render frame rate; events gate each other via blocking/blockable; triggers are
 * immediate / after / ease / condition / before. Replaces the scattered hard-coded `LaunchedEffect`
 * delays in the current rebuild.
 *
 * The Lua `ref_table[ref_value]` in-place mutation (used by `ease`) becomes a get/set lambda pair
 * ([EaseSpec]); `condition` likewise reads through a lambda. Everything else mirrors event.lua 1:1,
 * including the `append_count` front-insert index dance in [EventManager.update].
 */

/** Result scratch for one [Event.handle] (Lua's reused `G.ARGS.event_manager_update` table). */
class EventResults {
    var blocking = false
    var completed = false
    var timeDone = false
    var pauseSkip = false
    fun reset() { blocking = false; completed = false; timeDone = false; pauseSkip = false }
}

/** Ease curve registry — Balatro's `SMODS.ease_types`. `percent` runs 1→0 over the event's life. */
object EaseTypes {
    /** (percent, c1, c2, c3) -> shaped percent. c1=1.70158 etc. feed the back/elastic curves. */
    val map: MutableMap<String, (Double, Double, Double, Double) -> Double> = mutableMapOf(
        // lerp is identity — by far the most common in vanilla; others register on demand (P1+).
        "lerp" to { p, _, _, _ -> p },
    )
    fun shape(type: String, p: Double): Double {
        val c1 = 1.70158; val c2 = c1 * 1.525; val c3 = c1 + 1.0
        return (map[type] ?: map.getValue("lerp")).invoke(p, c1, c2, c3)
    }
}

/** The eased value handle: where to read the start value and where to write each step. */
class EaseSpec(val get: () -> Double, val set: (Double) -> Unit, val easeTo: Double, val type: String = "lerp")

/**
 * One scheduled event. Construct via the trigger that fits; [func] returns whether the event is now
 * complete (immediate/after/before/condition), while [easeFunc] maps the eased value (ease only).
 */
class Event(
    val trigger: String = "immediate",
    val delay: Double = 0.0,
    val blocking: Boolean = true,
    val blockable: Boolean = true,
    val noDelete: Boolean = false,
    timerName: String? = null,
    pauseForce: Boolean = false,
    createdWhilePaused: Boolean = false,
    /** immediate/after/before/condition: return true when complete. */
    val func: () -> Boolean = { true },
    /** ease: map the interpolated value (default identity). */
    val easeFunc: (Double) -> Double = { it },
    val ease: EaseSpec? = null,
    /** condition: completes when this returns true (default for a `condition` event w/o func). */
    val condition: (() -> Boolean)? = null,
) {
    val createdOnPause = pauseForce || createdWhilePaused
    // Lua: timer = config.timer or (created_on_pause and 'REAL') or 'TOTAL'
    val timerName: String = timerName ?: if (createdOnPause) "REAL" else "TOTAL"

    var complete = false
        private set

    private var startTimer = false
    private var time = 0.0

    // ease state
    private var easeStartTime: Double? = null
    private var easeEndTime = 0.0
    private var easeStartVal = 0.0

    /** Mirrors Event:handle (event.lua:50-102). [paused] is G.SETTINGS.paused. */
    fun handle(clock: GameClock, r: EventResults, paused: Boolean) {
        r.blocking = blocking
        r.completed = complete
        if (!createdOnPause && paused) { r.pauseSkip = true; return }
        if (!startTimer) { time = clock[timerName]; startTimer = true }
        val now = clock[timerName]

        when (trigger) {
            "after" -> if (time + delay <= now) { r.timeDone = true; r.completed = func() }

            "ease" -> {
                val e = ease ?: return
                if (easeStartTime == null) {
                    easeStartTime = now
                    easeEndTime = now + delay
                    easeStartVal = e.get()
                }
                if (!complete) {
                    val span = easeEndTime - easeStartTime!!
                    if (easeEndTime >= now && span > 0.0) {
                        val raw = (easeEndTime - now) / span          // 1 at start → 0 at end
                        val p = EaseTypes.shape(e.type, raw)
                        e.set(easeFunc(p * easeStartVal + (1.0 - p) * e.easeTo))
                    } else {
                        e.set(easeFunc(e.easeTo))
                        complete = true; r.completed = true; r.timeDone = true
                    }
                }
            }

            "condition" -> {
                if (!complete) r.completed = condition?.invoke() ?: func()
                r.timeDone = true
            }

            "before" -> {
                if (!complete) r.completed = func()
                if (time + delay <= now) r.timeDone = true
            }

            else /* immediate */ -> { r.completed = func(); r.timeDone = true }
        }

        if (r.completed) complete = true
    }
}

/**
 * The five-queue event manager (event.lua:104-200). Fixed 1/60 drain decoupled from render frames;
 * within a drain each queue is walked front-to-back, a blocking event stops later blockable events
 * in that queue until it completes, and the `append_count` dance keeps the cursor on the right event
 * when a handler front-inserts new events into the same queue.
 */
class EventManager(private val clock: GameClock) {
    private val order = listOf("unlock", "base", "tutorial", "achievement", "other")
    val queues: Map<String, MutableList<Event>> = order.associateWith { mutableListOf() }

    private var queueTimer = clock.real
    private val queueDt = 1.0 / 60.0
    private var queueLastProcessed = clock.real

    private var appendCount = 0
    private var appendQueue: String? = null
    private val results = EventResults()

    fun addEvent(event: Event, queue: String = "base", front: Boolean = false) {
        val q = queues[queue] ?: return
        if (front) {
            if (appendQueue == queue) appendCount += 1
            q.add(0, event)
        } else {
            q.add(event)
        }
    }

    /** Count of pending events across all queues (test/debug). */
    fun pending(): Int = queues.values.sumOf { it.size }

    /** clear_queue (event.lua:135-171): null=all, exception=all-but-one, else one; honors no_delete. */
    fun clearQueue(queue: String? = null, exception: String? = null) {
        fun purge(list: MutableList<Event>) {
            var i = 0
            while (i < list.size) if (!list[i].noDelete) list.removeAt(i) else i++
        }
        when {
            queue == null && exception == null -> queues.values.forEach(::purge)
            exception != null -> queues.forEach { (k, v) -> if (k != exception) purge(v) }
            else -> queues[queue]?.let(::purge)
        }
    }

    /** EventManager:update (event.lua:173-200). [paused] is G.SETTINGS.paused. */
    fun update(dt: Double, forced: Boolean = false, paused: Boolean = false) {
        queueTimer += dt
        if (queueTimer < queueLastProcessed + queueDt && !forced) return
        queueLastProcessed += if (forced) 0.0 else queueDt

        for (k in order) {
            val v = queues.getValue(k)
            var blocked = false
            var i = 0
            while (i < v.size) {
                results.reset()
                appendCount = 0
                appendQueue = k
                if (!blocked || !v[i].blockable) v[i].handle(clock, results, paused)
                i += appendCount   // a front-insert into this queue shifted the current event right
                if (results.pauseSkip) {
                    i += 1
                } else {
                    if (!blocked && results.blocking) blocked = true
                    if (results.completed && results.timeDone) v.removeAt(i) else i += 1
                }
            }
        }
    }
}
