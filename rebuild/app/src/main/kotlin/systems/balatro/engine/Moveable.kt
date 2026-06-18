package systems.balatro.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Offset(var x: Double = 0.0, var y: Double = 0.0)
class Pinch(var x: Boolean = false, var y: Boolean = false)

/** The damped-sine pop (moveable.lua:256). scale^3 / r^2 decay over 0.4s. */
class Juice(
    var scale: Double = 0.0, val scaleAmt: Double = 0.0,
    var r: Double = 0.0, val rAmt: Double = 0.0,
    val startTime: Double = 0.0, val endTime: Double = 0.0,
    val handledElsewhere: Boolean = false,
)

/** Which alignment letters are set (moveable.lua:119). */
private class AlignFlags(
    val a: Boolean, val m: Boolean, val c: Boolean, val b: Boolean,
    val t: Boolean, val l: Boolean, val r: Boolean, val i: Boolean,
)

/** get_major's return: the resolved Major + the composed offset from this Minor to it. */
class MajorInfo(val major: Moveable, val offsetX: Double, val offsetY: Double)

/**
 * Port of engine/moveable.lua (P0-T6) — promotes the (already exact) spring integrator from
 * ui/Spring.kt into the engine's base class. A Moveable has a TARGET [T] and a VISIBLE [VT] that
 * eases to it each frame; you set T and the engine eases VT regardless of timing. This is THE source
 * of the whole game's feel and the thing the current rebuild skipped for everything but hand cards.
 *
 * Faithfully ported here: the spring integrators (move_xy/r/scale/wh, exact exp(-50/-60/-190·dt) and
 * 70·move_dt clamp), move_juice/juice_up, the RoleHierarchy (Major drives motion; Minor welds to a
 * Major via get_major/move_with_major with the rotated-offset math; Glued copies a major's T), the
 * AlignmentSystem (the 'cm'/'cli'/'bmi' type-string → role offset), shadow parallax, and the
 * FRAME.MOVE-guarded move() dispatcher. Reads all timing from the injected [GameClock].
 *
 * NOTE (base mixin only): the large runtime classes that extend Moveable — Card/CardArea/Blind/Tag/
 * Back — are P0.5. get_major's per-frame FRAME.MAJOR memo is recomputed rather than cached (a perf
 * optimization, not a correctness one); juice_up's default rotation sign is +0.6·amt until PseudoRNG
 * (P1) supplies the ±randomization.
 */
open class Moveable(
    scene: SceneRegistry,
    t: Transform = Transform(),
    container: Node? = null,
    createdWhilePaused: Boolean = false,
) : Node(scene, t, container, createdWhilePaused) {

    val originalT: Transform = T.copy()
    val VT = VisibleTransform(T.x, T.y, T.w, T.h, T.r, T.scale)
    val velocity = Velocity()

    // role (moveable.lua:42)
    var roleType = "Major"          // Major | Minor | Glued
    val roleOffset = Offset()
    var major: Moveable? = null
    var drawMajor: Moveable = this
    var xyBond = "Strong"; var whBond = "Strong"; var rBond = "Strong"; var scaleBond = "Strong"

    // alignment (moveable.lua:53)
    var alignType = "a"
    val alignOffset = Offset()
    private var alignPrevType = ""
    private var typeList: AlignFlags? = null
    var lrClamp = false
    var newAlignment = false

    val pinch = Pinch()
    /** self.Mid (moveable.lua:72) — the sub-object alignment centers on; defaults to self. */
    var mid: Moveable = this
    val shadowParallax = Offset(0.0, -1.5)
    val layeredParallax = Offset()
    var shadowHeight = 0.2

    var stationary = true
    var calcing = false
    var zoom = false                // cards set this so hover/drag nudges scale (move_scale)

    var juice: Juice? = null

    init {
        scene.registerMoveable(this)
        calculateParallax()
    }

    // ── alignment ────────────────────────────────────────────────────────────────────────────
    fun setAlignment(
        major: Moveable? = null, bond: String? = null, offset: Offset? = null, type: String? = null,
        whBond: String? = null, rBond: String? = null, scaleBond: String? = null, lrClamp: Boolean = false,
    ) {
        if (major != null) setRole(roleType = "Minor", major = major, xyBond = bond ?: "Weak",
            whBond = whBond, rBond = rBond, scaleBond = scaleBond)
        alignType = type ?: alignType
        if (offset != null) { alignOffset.x = offset.x; alignOffset.y = offset.y }
        this.lrClamp = lrClamp
    }

    /** moveable.lua:117 — resolve the alignment type-string into a role offset, then set T from major. */
    fun alignToMajor() {
        if (alignType != alignPrevType) {
            typeList = AlignFlags(
                a = alignType == "a",
                m = alignType.contains("m"), c = alignType.contains("c"),
                b = alignType.contains("b"), t = alignType.contains("t"),
                l = alignType.contains("l"), r = alignType.contains("r"),
                i = alignType.contains("i"),
            )
            alignPrevType = alignType
        }
        val tl = typeList ?: return
        newAlignment = true
        val m = major
        if (tl.a || m == null) return

        if (tl.m) roleOffset.x = 0.5 * m.T.w - mid.T.w / 2 + alignOffset.x - mid.T.x + T.x
        if (tl.c) roleOffset.y = 0.5 * m.T.h - mid.T.h / 2 + alignOffset.y - mid.T.y + T.y
        if (tl.b) roleOffset.y = if (tl.i) alignOffset.y + m.T.h - T.h else alignOffset.y + m.T.h
        if (tl.r) roleOffset.x = if (tl.i) alignOffset.x + m.T.w - T.w else alignOffset.x + m.T.w
        if (tl.t) roleOffset.y = if (tl.i) alignOffset.y else alignOffset.y - T.h
        if (tl.l) roleOffset.x = if (tl.i) alignOffset.x else alignOffset.x - T.w

        T.x = m.T.x + roleOffset.x
        T.y = m.T.y + roleOffset.y
    }

    // ── role hierarchy ───────────────────────────────────────────────────────────────────────
    fun setRole(
        roleType: String? = null, major: Moveable? = null, offset: Offset? = null,
        xyBond: String? = null, whBond: String? = null, rBond: String? = null,
        scaleBond: String? = null, drawMajor: Moveable? = null,
    ) {
        this.roleType = roleType ?: this.roleType
        if (offset != null) { roleOffset.x = offset.x; roleOffset.y = offset.y }
        this.major = major ?: this.major
        this.xyBond = xyBond ?: this.xyBond
        this.whBond = whBond ?: this.whBond
        this.rBond = rBond ?: this.rBond
        this.scaleBond = scaleBond ?: this.scaleBond
        this.drawMajor = drawMajor ?: this.drawMajor
        if (this.roleType == "Major") this.major = null
    }

    /** moveable.lua:486 — walk up to the controlling Major, composing offsets (memo skipped). */
    fun getMajor(): MajorInfo {
        val m = major
        return if (roleType != "Major" && m != null && m != this && xyBond != "Weak" && rBond != "Weak") {
            val parent = m.getMajor()
            MajorInfo(parent.major,
                parent.offsetX + roleOffset.x + layeredParallax.x,
                parent.offsetY + roleOffset.y + layeredParallax.y)
        } else {
            MajorInfo(this, 0.0, 0.0)
        }
    }

    // ── the per-frame move dispatcher (moveable.lua:278) ───────────────────────────────────────
    fun move(clock: GameClock, paused: Boolean = false) {
        if (frameMove >= clock.frameMove) return
        frameMove = clock.frameMove
        if (!createdOnPause && paused) return

        alignToMajor()
        calcing = false
        when (roleType) {
            "Glued" -> major?.let { glueToMajor(it) }
            "Minor" -> {
                val m = major
                if (m != null) {
                    if (m.frameMove < clock.frameMove) m.move(clock, paused)
                    stationary = m.stationary
                    if (!stationary || newAlignment || juice != null ||
                        config["refresh_movement"] == true || xyBond == "Weak" || rBond == "Weak") {
                        calcing = true
                        moveWithMajor(clock)
                    }
                }
            }
            else /* Major */ -> {
                stationary = true
                moveJuice(clock)
                moveXY(clock)
                moveR(clock)
                moveScale(clock)
                moveWH(clock)
                calculateParallax()
            }
        }
        if (lrClamp) lrClamp(clock)
        newAlignment = false
    }

    private fun lrClamp(@Suppress("UNUSED_PARAMETER") clock: GameClock) {
        val roomW = scene.room?.T?.w ?: return
        if (T.x < 0) T.x = 0.0
        if (VT.x < 0) VT.x = 0.0
        if (T.x + T.w > roomW) T.x = roomW - T.w
        if (VT.x + VT.w > roomW) VT.x = roomW - VT.w
    }

    private fun glueToMajor(m: Moveable) {
        T.setFrom(m.T)   // Lua aliases the table; we copy values each frame (Glued is re-glued every move)
        VT.x = m.VT.x + (0.5 * (1 - m.VT.w / m.T.w) * T.w)
        VT.y = m.VT.y; VT.w = m.VT.w; VT.h = m.VT.h; VT.r = m.VT.r; VT.scale = m.VT.scale
        pinch.x = m.pinch.x; pinch.y = m.pinch.y
        shadowParallax.x = m.shadowParallax.x; shadowParallax.y = m.shadowParallax.y
    }

    private fun moveWithMajor(clock: GameClock) {
        if (roleType != "Minor") return
        val m = major ?: return
        val majorTab = m.getMajor()
        val mm = majorTab.major
        moveJuice(clock)

        val roX: Double; val roY: Double
        if (rBond == "Weak" || (mm.VT.r < 0.0001 && mm.VT.r > -0.0001)) {
            roX = roleOffset.x + majorTab.offsetX
            roY = roleOffset.y + majorTab.offsetY
        } else {
            val cosA = cos(mm.VT.r); val sinA = sin(mm.VT.r)
            val wW = -T.w / 2 + mm.T.w / 2; val wH = -T.h / 2 + mm.T.h / 2
            val ox = roleOffset.x + majorTab.offsetX - wW
            val oy = roleOffset.y + majorTab.offsetY - wH
            roX = ox * cosA - oy * sinA + wW
            roY = ox * sinA + oy * cosA + wH
        }

        T.x = mm.T.x + roX; T.y = mm.T.y + roY
        if (xyBond == "Strong") { VT.x = mm.VT.x + roX; VT.y = mm.VT.y + roY }
        else if (xyBond == "Weak") moveXY(clock)
        if (rBond == "Strong") VT.r = T.r + mm.VT.r + (juice?.r ?: 0.0)
        else if (rBond == "Weak") moveR(clock)
        if (scaleBond == "Strong") VT.scale = T.scale * (mm.VT.scale / mm.T.scale) + (juice?.scale ?: 0.0)
        else if (scaleBond == "Weak") moveScale(clock)
        if (whBond == "Strong") {
            VT.x += 0.5 * (1 - mm.VT.w / mm.T.w) * T.w
            VT.w = T.w * (mm.VT.w / mm.T.w); VT.h = T.h * (mm.VT.h / mm.T.h)
        } else if (whBond == "Weak") moveWH(clock)
        calculateParallax()
    }

    // ── spring integrators (moveable.lua:405-461) ──────────────────────────────────────────────
    private fun moveXY(clock: GameClock) {
        val dt = clock.moveDt
        if ((T.x != VT.x || abs(velocity.x) > 0.01) || (T.y != VT.y || abs(velocity.y) > 0.01)) {
            velocity.x = clock.expXY * velocity.x + (1 - clock.expXY) * (T.x - VT.x) * 35 * dt
            velocity.y = clock.expXY * velocity.y + (1 - clock.expXY) * (T.y - VT.y) * 35 * dt
            val v2 = velocity.x * velocity.x + velocity.y * velocity.y
            if (v2 > clock.maxVel * clock.maxVel) {
                val a = sqrt(v2)
                velocity.x = clock.maxVel * velocity.x / a
                velocity.y = clock.maxVel * velocity.y / a
            }
            stationary = false
            VT.x += velocity.x; VT.y += velocity.y
            if (abs(VT.x - T.x) < 0.01 && abs(velocity.x) < 0.01) { VT.x = T.x; velocity.x = 0.0 }
            if (abs(VT.y - T.y) < 0.01 && abs(velocity.y) < 0.01) { VT.y = T.y; velocity.y = 0.0 }
        }
    }

    private fun moveR(clock: GameClock) {
        val dt = clock.moveDt
        val desR = T.r + (if (dt > 0) 0.015 * velocity.x / dt else 0.0) + (juice?.let { it.r * 2 } ?: 0.0)
        if (desR != VT.r || abs(velocity.r) > 0.001) {
            stationary = false
            velocity.r = clock.expR * velocity.r + (1 - clock.expR) * (desR - VT.r)
            VT.r += velocity.r
        }
        if (abs(VT.r - T.r) < 0.001 && abs(velocity.r) < 0.001) { VT.r = T.r; velocity.r = 0.0 }
    }

    private fun moveScale(clock: GameClock) {
        val zoomExtra = if (zoom) (if (states.drag.isOn) 0.1 else 0.0) + (if (states.hover.isOn) 0.05 else 0.0) else 0.0
        val desScale = T.scale + zoomExtra + (juice?.scale ?: 0.0)
        if (desScale != VT.scale || abs(velocity.scale) > 0.001) {
            stationary = false
            velocity.scale = clock.expScale * velocity.scale + (1 - clock.expScale) * (desScale - VT.scale)
            VT.scale += velocity.scale
        }
    }

    private fun moveWH(clock: GameClock) {
        val dt = clock.moveDt
        if ((T.w != VT.w && !pinch.x) || (T.h != VT.h && !pinch.y) ||
            (VT.w > 0 && pinch.x) || (VT.h > 0 && pinch.y)) {
            stationary = false
            VT.w += 8 * dt * (if (pinch.x) -1 else 1) * T.w
            VT.h += 8 * dt * (if (pinch.y) -1 else 1) * T.h
            VT.w = max(min(VT.w, T.w), 0.0)
            VT.h = max(min(VT.h, T.h), 0.0)
        }
    }

    // ── juice (moveable.lua:250-276) ───────────────────────────────────────────────────────────
    fun juiceUp(amount: Double = 0.4, rotAmt: Double? = null, reducedMotion: Boolean = false, now: Double) {
        if (reducedMotion) return
        juice = Juice(scaleAmt = amount, rAmt = rotAmt ?: (0.6 * amount), startTime = now, endTime = now + 0.4)
        VT.scale = 1 - 0.6 * amount
    }

    private fun moveJuice(clock: GameClock) {
        val j = juice ?: return
        if (j.handledElsewhere) return
        if (j.endTime < clock.real) { juice = null; return }
        val span = j.endTime - j.startTime
        if (span <= 0) return
        val p = (j.endTime - clock.real) / span
        j.scale = j.scaleAmt * sin(50.8 * (clock.real - j.startTime)) * max(0.0, p * p * p)
        j.r = j.rAmt * sin(40.8 * (clock.real - j.startTime)) * max(0.0, p * p)
    }

    fun calculateParallax() {
        val roomW = scene.room?.T?.w ?: return
        shadowParallax.x = (T.x + T.w / 2 - roomW / 2) / (roomW / 2) * 1.5
    }

    // ── hard sets (moveable.lua:190-215) ───────────────────────────────────────────────────────
    fun hardSetT(x: Double, y: Double, w: Double, h: Double) {
        T.x = x; T.y = y; T.w = w; T.h = h
        velocity.x = 0.0; velocity.y = 0.0; velocity.r = 0.0; velocity.scale = 0.0
        VT.x = x; VT.y = y; VT.w = w; VT.h = h; VT.r = T.r; VT.scale = T.scale
        calculateParallax()
    }

    fun hardSetVT() { VT.x = T.x; VT.y = T.y; VT.w = T.w; VT.h = T.h }

    override fun remove() {
        // deferred removal also drops us from scene.moveables (SceneRegistry.flushRemovals)
        super.remove()
    }
}
