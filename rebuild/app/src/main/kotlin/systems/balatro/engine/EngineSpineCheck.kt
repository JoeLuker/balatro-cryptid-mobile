package systems.balatro.engine

import kotlin.math.abs
import kotlin.math.floor

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

    // 9. set_screen_positions DERIVES the play-field origins from TILE_W/TILE_H + CAI dims — the PF.*
    //    constants are no longer measured but computed (closes the curve-fit debt).
    run {
        val hd = Room.hand; val j = Room.jokers; val cs = Room.consumeables
        val pl = Room.play; val ck = Room.deck
        check("hand.x derived = PF.HAND_X (4.8573)", abs(hd.x - 4.8573) < 1e-3, "hand.x=${hd.x}")
        check("hand.y derived = PF.HAND_Y (8.8863)", abs(hd.y - 8.8863) < 1e-3, "hand.y=${hd.y}")
        check("jokers.x derived = PF.JOKERS_X (4.7573)", abs(j.x - 4.7573) < 1e-3, "jokers.x=${j.x}")
        check("joker width derived = PF.JOKER_W (10.039)", abs(j.w - 10.039) < 1e-3, "joker_W=${j.w}")
        check("consum.x derived = PF.CONSUM_X (14.9963)", abs(cs.x - 14.9963) < 1e-3, "consum.x=${cs.x}")
        check("play.x derived = PF.PLAY_X (5.5744)", abs(pl.x - 5.5744) < 1e-3, "play.x=${pl.x}")
        check("play.y derived = PF.PLAY_RESTING_Y (5.2863)", abs(pl.y - 5.2863) < 1e-3, "play.y=${pl.y}")
        check("deck.x derived = PF.DECK_X (17.2463)", abs(ck.x - 17.2463) < 1e-3, "deck.x=${ck.x}")
        // EXPOSED curve-fit error: measured PF.DECK_Y=8.8953, but set_screen_positions gives
        // TILE_H - deck_H = hand.y = 8.8863. The derived value is engine-true; 8.8953 was a ~0.009u
        // measurement error in the hand-tuned constant.
        check("deck.y derived = hand.y, NOT measured 8.8953", abs(ck.y - hd.y) < 1e-9 && abs(ck.y - 8.8863) < 1e-3,
            "deck.y=${ck.y} (PF.DECK_Y was 8.8953 — off by ${"%.4f".format(8.8953 - ck.y)}u)")
    }

    // 10. Faithful love.resize (the live-play transform): width-constrained when the surface is
    //     narrower than the room ratio (22/12.9≈1.705), else height-constrained. (The bref_3 repro
    //     path FORCES width-constrain to match that press-kit capture — a fixture, not this transform.)
    run {
        val tall = Room.transform(1080.0, 2400.0)            // phone portrait, 0.45 < 1.705 → width-fit
        check("tall surface width-constrained (u=W/22)", abs(tall.u - 1080.0 / 22.0) < 1e-9, "u=${tall.u}")
        check("tall surface originX = ROOM_PADDING_W", abs(tall.originX - 1.0) < 1e-9, "tx=${tall.originX}")
        val wide = Room.transform(3840.0, 2160.0)            // 16:9, 1.778 > 1.705 → height-fit
        check("wide 16:9 height-constrained (u=H/12.9)", abs(wide.u - 2160.0 / 12.9) < 1e-6, "u=${wide.u}")
        check("wide surface originY = ROOM_PADDING_H", abs(wide.originY - 0.7) < 1e-9, "ty=${wide.originY}")
    }

    // 11. CardArea joker branch lays out 2 jokers at the align_cards spread + fan (matches the render).
    run {
        val scene = SceneRegistry(); val c = GameClock()
        val area = CardArea(scene, Transform(Room.jokers.x, Room.jokers.y, Room.jokers.w, Room.jokers.h), "joker", 5)
        area.setCardCount(2)
        area.alignCards(c, reducedMotion = true)
        val a = area.cards[0].T; val b = area.cards[1].T
        check("joker0 spread x = 6.7549", abs(a.x - 6.7549) < 1e-3, "x0=${a.x}")
        check("joker1 spread x = 10.7499", abs(b.x - 10.7499) < 1e-3, "x1=${b.x}")
        check("joker fan r (CCW / CW)", abs(a.r + 0.025) < 1e-9 && abs(b.r - 0.025) < 1e-9, "r0=${a.r} r1=${b.r}")
        check("joker y centred in shorter area", abs(a.y - (0.95 * Room.CARD_H - Room.CARD_H) / 2) < 1e-9, "y=${a.y}")
        area.setCardCount(0)
        check("setCardCount(0) deregisters cards", area.cards.isEmpty())
    }

    // 12. Scoring cascade (P4): the blocking after-events RunScreen schedules fire strictly in
    //     sequence at the right cumulative gaps (140ms step0→1, 300ms between steps, 300+450ms to
    //     commit), drained by the one loop — replacing the old hard-coded coroutine delays.
    run {
        val c = GameClock(); val em = EventManager(c)
        val fired = HashMap<String, Double>()
        // mirror RunScreen's schedule for a 3-step cascade (steps 0,1,2); step0 fires immediately.
        em.addEvent(Event(trigger = "after", delay = 0.14, func = { fired["s1"] = c.total; true }))
        em.addEvent(Event(trigger = "after", delay = 0.30, func = { fired["s2"] = c.total; true }))
        em.addEvent(Event(trigger = "after", delay = 0.30, func = { true }))
        em.addEvent(Event(trigger = "after", delay = 0.45, func = { fired["commit"] = c.total; true }))
        repeat(150) { c.advance(DT); em.update(DT) }   // 2.5s
        val t1 = fired["s1"]; val t2 = fired["s2"]; val tc = fired["commit"]
        check("cascade fired step1<step2<commit", t1 != null && t2 != null && tc != null && t1 < t2 && t2 < tc)
        if (t1 != null && t2 != null && tc != null) {
            // gaps are nominal + ~1 fixed-step frame per event (the EventManager start-capture latency)
            check("cascade step1→step2 gap ≈0.30", (t2 - t1) in 0.29..0.35, "gap=${t2 - t1}")
            check("cascade step2→commit gap ≈0.75 (+0.30 trailing,+0.45 commit,+frames)", (tc - t2) in 0.74..0.85, "gap=${tc - t2}")
        }
        check("cascade queue drained", em.pending() == 0)
    }

    // 13. floor-ease (ease_chips): the readout counts UP to the target in integer steps over 0.3s.
    run {
        val c = GameClock(); val em = EventManager(c)
        var v = 40.0; var allInt = true; var sawMid = false
        em.addEvent(Event(trigger = "ease", delay = 0.3,
            ease = EaseSpec(get = { v }, set = { v = it }, easeTo = 88.0), easeFunc = { floor(it) }))
        repeat(30) { c.advance(DT); em.update(DT); if (v != floor(v)) allInt = false; if (v > 40.0 && v < 88.0) sawMid = true }
        check("floor-ease reached integer target", v == 88.0, "v=$v")
        check("floor-ease stayed integer (counts up)", allInt && sawMid)
    }

    // 14. CardArea transfer (draw_card_from): a card moves between areas as the SAME Moveable,
    //     keeping its VT (the deal/play fly-in), then springs to its new slot.
    run {
        val scene = SceneRegistry(); val c = GameClock()
        val deck = CardArea(scene, Transform(17.0, 8.0, 2.0, 2.7), "deck", 52)
        val hand = CardArea(scene, Transform(5.0, 7.0, 12.0, 2.7), "hand", 8)
        deck.setCardCount(1)
        val card = deck.cards[0]   // at the deck (VT == T == 17,8)
        val drawn = hand.drawCardFrom(deck)
        check("transfer moved the card to hand", drawn === card && hand.cards.contains(card) && deck.cards.isEmpty())
        check("transfer CARRIED VT (still at the deck)", abs(card.VT.x - 17.0) < 1e-9 && abs(card.VT.y - 8.0) < 1e-9)
        repeat(120) { c.advance(DT); hand.alignCards(c, reducedMotion = true, tempLimit = 8); scene.moveables.forEach { it.move(c) } }
        check("transfer card springs into the hand area", abs(card.VT.x - card.T.x) < 0.05 && card.T.x in 5.0..17.0)
    }

    // 15. startDissolve / shatter (Card:start_dissolve / Card:shatter): eases `dissolve` 0→1 over the
    //     dissolve time, then fires onGone (the caller's removal). Glass shatter is faster (0.35s ease,
    //     remove ~0.385s); the normal fiery dissolve eases over 0.7s, removes ~0.735s.
    run {
        val host = EngineHost()
        val glass = Moveable(host.scene, Transform(5.0, 7.0, 1.0, 1.4))
        var prev = -1.0; var monotonic = true; var goneFrame = -1; var n = 0
        host.startDissolve(glass, shatter = true, now = host.clock.real, reducedMotion = true,
            onGone = { goneFrame = n })
        repeat(60) { n++; host.tick(DT); if (glass.dissolve < prev - 1e-9) monotonic = false; prev = glass.dissolve }
        check("shatter eased dissolve 0→1", abs(glass.dissolve - 1.0) < 1e-6, "dissolve=${glass.dissolve}")
        check("shatter dissolve monotonic", monotonic)
        check("shatter set the shattered flag", glass.shattered)
        check("shatter removed ~0.385s in", goneFrame in 22..27, "frame=$goneFrame (≈23+capture)")

        val host2 = EngineHost()
        val card = Moveable(host2.scene, Transform(5.0, 7.0, 1.0, 1.4))
        var goneFrame2 = -1; var m = 0
        host2.startDissolve(card, shatter = false, now = host2.clock.real, reducedMotion = true,
            onGone = { goneFrame2 = m })
        repeat(60) { m++; host2.tick(DT) }
        check("normal dissolve reached 1", abs(card.dissolve - 1.0) < 1e-6, "dissolve=${card.dissolve}")
        check("normal dissolve NOT flagged shattered", !card.shattered)
        check("normal dissolve removed ~0.735s in", goneFrame2 in 43..48, "frame=$goneFrame2 (≈44+capture)")
    }

    println(if (failures == 0) "ALL P0 SPINE CHECKS PASSED" else "$failures CHECK(S) FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
