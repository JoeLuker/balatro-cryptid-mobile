package systems.balatro.engine

/**
 * Play-field-scoped engine host + the per-frame driver state (P0-T8). Owns the single GameClock,
 * SceneRegistry, and EventManager, plus the play-field CardArea Moveables positioned by
 * Room.set_screen_positions. The Compose layer owns the actual `withFrameNanos` loop and calls
 * [tick] each frame; the render reads each area's VT.
 *
 * At rest VT == T == the derived Room origins, so the play field renders pixel-identically to the old
 * static PF.* placement — but the areas are now engine-driven (one clock, one event queue, one
 * moveable sweep) and can spring once something moves their T. This is the seam where the verified
 * P0 primitives stop being a dormant library and start driving the render.
 *
 * Pure logic (no Compose) so it stays standalone-compilable with the rest of systems.balatro.engine.
 */
class EngineHost {
    val clock = GameClock()
    val scene = SceneRegistry()
    val events = EventManager(clock)

    // The six RUN-stage card areas (set_screen_positions). Major Moveables whose T is the derived
    // ROOM_ATTACH-frame origin; the render applies the device room transform (roomTx/roomTy·u) on top.
    val jokers = areaMoveable(Room.jokers)
    val hand = areaMoveable(Room.hand)
    val play = areaMoveable(Room.play)
    val deck = areaMoveable(Room.deck)
    val consumeables = areaMoveable(Room.consumeables)
    val discard = areaMoveable(Room.discard)

    private fun areaMoveable(r: Room.AreaRect) = Moveable(scene, Transform(r.x, r.y, r.w, r.h))

    /** One simulation step (the body of game.lua Game:update): advance the clock, drain events,
     *  sweep every Moveable's move(), then flush deferred removals. */
    fun tick(dt: Double, paused: Boolean = false) {
        clock.advance(dt, paused = paused)
        events.update(dt, paused = paused)
        for (m in scene.moveables) m.move(clock, paused)
        scene.flushRemovals()
    }
}
