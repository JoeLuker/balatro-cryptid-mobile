package systems.balatro.engine

import kotlin.math.abs

/**
 * Standalone verification for the P0 spine root (GameClock + Event/EventManager). Pure logic, no
 * Android — run with the nix kotlin toolchain:
 *   cd rebuild/app/src/main/kotlin && \
 *     "$(ls -d /nix/store/*kotlin*/bin)/kotlinc" systems/balatro/engine -include-runtime -d /tmp/engine.jar && \
 *     "$(ls -d /nix/store/*kotlin*/bin)/kotlin" -cp /tmp/engine.jar systems.balatro.engine.EngineSpineCheckKt
 * Exits non-zero on any failed check.
 */

private var failures = 0
private fun check(name: String, cond: Boolean, detail: String = "") {
    println((if (cond) "  PASS  " else "  FAIL  ") + name + (if (detail.isNotEmpty()) "   [$detail]" else ""))
    if (!cond) failures++
}

private const val DT = 1.0 / 60.0

fun main() {
    println("== P0 spine checks ==")

    // 1. GameClock: REAL accumulates raw dt; TOTAL freezes while paused.
    run {
        val c = GameClock()
        repeat(60) { c.advance(DT) }                       // 1s running
        val realAfterRun = c.real; val totalAfterRun = c.total
        repeat(60) { c.advance(DT, paused = true) }        // 1s paused
        check("clock REAL accrues raw dt", abs(realAfterRun - 1.0) < 1e-6, "real=$realAfterRun")
        check("clock TOTAL ~= run time", abs(totalAfterRun - 1.0) < 1e-6, "total=$totalAfterRun")
        check("clock REAL runs while paused", abs(c.real - 2.0) < 1e-6, "real=${c.real}")
        check("clock TOTAL frozen while paused", abs(c.total - totalAfterRun) < 1e-9, "total=${c.total}")
        check("exp_times sane", c.expXY in 0.0..1.0 && c.moveDt <= 1.0 / 20.0 + 1e-9, "expXY=${c.expXY} moveDt=${c.moveDt}")
    }

    // 1b. ACC fast-forward: ramps to ≤16 during HAND_PLAYED and lifts SPEEDFACTOR past 1.
    run {
        val c = GameClock()
        repeat(900) { c.advance(DT, gameSpeed = 1.0, handPlayedOrNewRound = true, state = "HAND_PLAYED") }
        check("ACC ramps and is capped at 16", c.acc in 2.0..16.0 + 1e-9, "acc=${c.acc}")
        check("SPEEDFACTOR > 1 once ACC>2", c.speedFactor > 1.0, "sf=${c.speedFactor}")
        // dropping out of HAND_PLAYED resets ACC and SPEEDFACTOR
        c.advance(DT, gameSpeed = 1.0, handPlayedOrNewRound = false, state = "SELECTING")
        check("ACC resets off HAND_PLAYED", c.acc == 0.0 && abs(c.speedFactor - 1.0) < 1e-9, "acc=${c.acc} sf=${c.speedFactor}")
    }

    // 2. 'after' event fires at its delay, then the queue drains to empty.
    run {
        val c = GameClock(); val em = EventManager(c)
        var fired = -1; var n = 0
        em.addEvent(Event(trigger = "after", delay = 0.5, func = { fired = n; true }))
        repeat(60) { n++; c.advance(DT); em.update(DT) }
        check("after-event fired", fired >= 0, "frame=$fired")
        check("after-event fired ~0.5s in", fired in 28..34, "frame=$fired (≈30+capture)")
        check("queue empty after fire", em.pending() == 0)
    }

    // 3. 'ease' interpolates monotonically 0→100 and completes.
    run {
        val c = GameClock(); val em = EventManager(c)
        var value = 0.0; var prev = -1.0; var monotonic = true
        em.addEvent(Event(trigger = "ease", delay = 0.5,
            ease = EaseSpec(get = { value }, set = { value = it }, easeTo = 100.0)))
        repeat(45) { c.advance(DT); em.update(DT); if (value < prev - 1e-9) monotonic = false; prev = value }
        check("ease reached end value", abs(value - 100.0) < 1e-6, "value=$value")
        check("ease was monotonic", monotonic)
        check("ease event completed", em.pending() == 0)
    }

    // 4. a blocking event defers a later blockable event in the same queue until it completes.
    run {
        val c = GameClock(); val em = EventManager(c)
        var aFired = -1; var bFired = -1; var n = 0
        em.addEvent(Event(trigger = "after", delay = 0.3, blocking = true, func = { aFired = n; true }))
        em.addEvent(Event(trigger = "after", delay = 0.0, func = { bFired = n; true }))   // would fire instantly if unblocked
        repeat(60) { n++; c.advance(DT); em.update(DT) }
        check("blocking event fired", aFired >= 0, "aFrame=$aFired")
        check("blocked event fired", bFired >= 0, "bFrame=$bFired")
        check("blocked event waited for the blocker", bFired > aFired, "a=$aFired b=$bFired")
    }

    // 5. Moveable (Major): VT springs to T, converges exactly, rotation lean settles back to T.r.
    run {
        val scene = SceneRegistry(); val c = GameClock()
        val mv = Moveable(scene, Transform(w = 1.0, h = 1.0))
        mv.T.x = 10.0; mv.T.y = 6.0
        repeat(180) { c.advance(DT); scene.moveables.forEach { it.move(c) } }   // 3s
        check("Major VT converged to T", abs(mv.VT.x - 10.0) < 0.05 && abs(mv.VT.y - 6.0) < 0.05, "vt=(${mv.VT.x},${mv.VT.y})")
        check("Major settled (velocity ~0)", abs(mv.velocity.x) < 0.01 && abs(mv.velocity.y) < 0.01)
        check("Major rotation settled to T.r", abs(mv.VT.r) < 0.001, "vr=${mv.VT.r}")
    }

    // 5b. move_r lean: while travelling in +x, VT leans (VT.r ≠ 0) before settling — the iconic tilt.
    run {
        val scene = SceneRegistry(); val c = GameClock()
        val mv = Moveable(scene, Transform(w = 1.0, h = 1.0)); mv.T.x = 10.0
        var leaned = false
        repeat(25) { c.advance(DT); scene.moveables.forEach { it.move(c) }; if (abs(mv.VT.r) > 1e-4) leaned = true }
        check("Major leans into horizontal motion", leaned)
    }

    // 6. Minor aligned 'cm' (center+middle) welds centered on its Major (AlignmentSystem + RoleHierarchy).
    run {
        val scene = SceneRegistry(); val c = GameClock()
        val major = Moveable(scene, Transform(x = 0.0, y = 0.0, w = 10.0, h = 10.0))
        val minor = Moveable(scene, Transform(w = 2.0, h = 2.0))
        minor.setAlignment(major = major, type = "cm", bond = "Strong")
        repeat(120) { c.advance(DT); scene.moveables.forEach { it.move(c) } }
        check("Minor T centered on Major (cm)", abs(minor.T.x - 4.0) < 1e-9 && abs(minor.T.y - 4.0) < 1e-9, "T=(${minor.T.x},${minor.T.y})")
        check("Minor VT tracks centered T", abs(minor.VT.x - 4.0) < 0.05 && abs(minor.VT.y - 4.0) < 0.05, "VT=(${minor.VT.x},${minor.VT.y})")
    }

    // 7. juice_up pops VT.scale below 1, then recovers to T.scale once the 0.4s window passes.
    run {
        val scene = SceneRegistry(); val c = GameClock()
        val mv = Moveable(scene, Transform(w = 1.0, h = 1.0))
        c.advance(DT)
        mv.juiceUp(amount = 0.4, now = c.real)
        val dipped = mv.VT.scale < 0.9
        repeat(45) { c.advance(DT); scene.moveables.forEach { it.move(c) } }
        check("juice dipped scale below 1", dipped, "scale0=${1.0 - 0.6 * 0.4}")
        check("juice recovered to ~1", abs(mv.VT.scale - 1.0) < 0.02 && mv.juice == null, "scale=${mv.VT.scale}")
    }

    // 8. SceneRegistry deferred removal: a removed Moveable drops out only after flush.
    run {
        val scene = SceneRegistry()
        val a = Moveable(scene, Transform()); val b = Moveable(scene, Transform())
        val before = scene.moveables.size
        a.remove()
        check("removal is deferred until flush", scene.moveables.size == before, "size=${scene.moveables.size}")
        scene.flushRemovals()
        check("flush compacts removed", scene.moveables.size == before - 1 && scene.moveables.contains(b), "size=${scene.moveables.size}")
    }

    println(if (failures == 0) "ALL P0 SPINE CHECKS PASSED" else "$failures CHECK(S) FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
