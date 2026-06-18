package systems.balatro.engine

/**
 * The transform double-buffer at the heart of every Moveable (P0-T3) — a direct port of the `T`/`VT`
 * holders from moveable.lua/node.lua. `T` is the TARGET (where the object wants to be, in game
 * units); `VT` is the VISIBLE transform that springs toward `T` each frame. You set `T` and the
 * engine eases `VT` to it — that double-buffer is what makes all motion in Balatro emergent rather
 * than hand-animated.
 *
 * Plain mutable doubles, deliberately NOT Compose `State`: these are written every frame by the
 * move loop, and wrapping them in snapshot state would thrash recomposition. The render layer reads
 * `VT` once per frame and draws.
 */
class Transform(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var w: Double = 1.0,
    var h: Double = 1.0,
    var r: Double = 0.0,
    var scale: Double = 1.0,
) {
    fun copy() = Transform(x, y, w, h, r, scale)
    fun setFrom(o: Transform) { x = o.x; y = o.y; w = o.w; h = o.h; r = o.r; scale = o.scale }
}

/** VT carries the extra center-adjusted `scale` factor (moveable.lua:23-30). */
class VisibleTransform(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var w: Double = 1.0,
    var h: Double = 1.0,
    var r: Double = 0.0,
    var scale: Double = 1.0,
)

/** Per-frame velocity of VT as it chases T (moveable.lua:33). */
class Velocity(
    var x: Double = 0.0,
    var y: Double = 0.0,
    var r: Double = 0.0,
    var scale: Double = 0.0,
    var mag: Double = 0.0,
)
