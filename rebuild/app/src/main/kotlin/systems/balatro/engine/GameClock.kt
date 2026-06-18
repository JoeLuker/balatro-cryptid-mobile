package systems.balatro.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Port of Balatro's per-frame clock + speed model (game.lua `Game:update`, the timer block at
 * ~2616-2666 and the exp_times/move_dt block at ~2824-2828). This is the single source of game time
 * that every Moveable, AnimatedSprite, DynaText, shader, and the [EventManager] fixed-step drain
 * reads — the root of the engine spine (P0-T1). Nothing above it can animate faithfully until it
 * runs per frame.
 *
 * Faithful detail that matters: REAL/UPTIME/BACKGROUND/real_dt accumulate the RAW dt, but ACC and
 * TOTAL use the pause-zeroed dt — so TOTAL (game time) freezes while paused yet REAL (wall time,
 * used by shaders/idle wobble) keeps running. exp_times use the raw real_dt regardless of pause.
 *
 * The G.STATE/G.STAGE machine is P4; until then [advance] takes the few flags that block actually
 * reads (isRun, handPlayedOrNewRound, an opaque state token for the ACC reset, reducedMotion, the
 * background spin amount) rather than inventing the enum early.
 */
class GameClock {
    // G.TIMERS
    var real = 0.0          // raw wall time (idle wobble, shaders)
    var total = 0.0         // SPEEDFACTOR-scaled, pause-aware game time (gameplay events)
    var realShader = 0.0    // = real, or frozen 300 under reduced motion
    var uptime = 0.0
    var background = 0.0    // advanced by the felt spin amount

    // G.FRAMES
    var frameMove = 0L

    // last-frame scalars
    var realDt = 0.0
    var speedFactor = 1.0
    var acc = 0.0
    private var accState: Any? = Unit   // sentinel != any caller token on first advance

    // exp_times (spring decay, recomputed each frame) + the per-frame move cap
    var expXY = 1.0
    var expScale = 1.0
    var expR = 1.0
    var moveDt = 0.0

    /**
     * One simulation step. Mirrors the ordered timer/speed block of `Game:update`.
     *
     * @param dt raw frame delta in seconds (G.real_dt source)
     * @param gameSpeed G.SETTINGS.GAMESPEED
     * @param paused G.SETTINGS.paused — zeroes the dt feeding ACC and TOTAL only
     * @param isRun G.STAGE == RUN && not screenwipe — gates GAMESPEED into SPEEDFACTOR
     * @param handPlayedOrNewRound G.STATE in {HAND_PLAYED, NEW_ROUND} — drives the ACC fast-forward ramp
     * @param state opaque G.STATE token; ACC resets to 0 when it changes (G.ACC_state)
     * @param reducedMotion G.SETTINGS.reduced_motion — freezes REAL_SHADER at 300
     * @param spinAmount G.ARGS.spin.amount — background felt spin
     */
    fun advance(
        dt: Double,
        gameSpeed: Double = 1.0,
        paused: Boolean = false,
        isRun: Boolean = true,
        handPlayedOrNewRound: Boolean = false,
        state: Any? = null,
        reducedMotion: Boolean = false,
        spinAmount: Double = 0.0,
    ) {
        frameMove += 1

        // raw-dt timers (game.lua:2632-2637) — before the pause-zero
        real += dt
        realShader = if (reducedMotion) 300.0 else real
        uptime += dt
        background += dt * spinAmount
        realDt = dt

        // pause zeroes the dt feeding ACC + TOTAL (game.lua:2652)
        val sdt = if (paused) 0.0 else dt

        // ACC fast-forward (game.lua:2654-2661): reset on state change, ramp during HAND_PLAYED/NEW_ROUND
        if (state != accState) acc = 0.0
        accState = state
        acc = if (handPlayedOrNewRound) min(acc + sdt * 0.2 * gameSpeed, 16.0) else 0.0

        // SPEEDFACTOR (game.lua:2663-2664)
        speedFactor = if (isRun && !paused) gameSpeed else 1.0
        speedFactor += maxOf(0.0, abs(acc) - 2.0)

        // game time (game.lua:2666)
        total += sdt * speedFactor

        // exp_times + move cap (game.lua:2824-2828) — raw real_dt, computed every frame
        expXY = exp(-50.0 * realDt)
        expScale = exp(-60.0 * realDt)
        expR = exp(-190.0 * realDt)
        moveDt = min(1.0 / 20.0, realDt)
    }

    /** Resolve a named timer the way Lua's `G.TIMERS[name]` does (Event.timer is "REAL" or "TOTAL"). */
    operator fun get(timer: String): Double = when (timer) {
        "REAL" -> real
        "TOTAL" -> total
        "REAL_SHADER" -> realShader
        "UPTIME" -> uptime
        "BACKGROUND" -> background
        else -> total
    }
}
