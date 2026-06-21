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

    // The RUN-stage card areas (set_screen_positions). Their T is the derived ROOM_ATTACH-frame
    // origin; the render applies the device room transform (roomTx/roomTy·u) on top. The areas that
    // hold spread cards are CardAreas (own card Moveables + align_cards); the rest are plain anchors.
    val jokers = cardArea(Room.jokers, "joker", 5)
    val consumeables = cardArea(Room.consumeables, "joker", 2, isConsumeables = true)
    val hand = cardArea(Room.hand, "hand", 8, cardScale = 0.95)    // oracle: hand cards render at 0.95
    val play = cardArea(Room.play, "play", 5, cardScale = 0.95)    // played cards fly in here + juice
    val deck = areaMoveable(Room.deck)
    val discard = areaMoveable(Room.discard)

    private fun areaMoveable(r: Room.AreaRect) = Moveable(scene, Transform(r.x, r.y, r.w, r.h))
    private fun cardArea(r: Room.AreaRect, kind: String, limit: Int, isConsumeables: Boolean = false, cardScale: Double = 1.0) =
        CardArea(scene, Transform(r.x, r.y, r.w, r.h), kind, limit, isConsumeables, cardScale)

    /** Card:start_dissolve / Card:shatter (card.lua:2615 / 2541) — the card DESTROY animation. Juices
     *  the card, then eases its `dissolve` 0→1 over the dissolve time and finally invokes [onGone] so
     *  the caller drops it from its area/game list. [shatter] = glass: a faster (0.35s) white burn vs
     *  the 0.7s fiery dissolve (dissolve_time 0.7; ease over 1×/0.5×, remove at 1.05×/0.55×). */
    fun startDissolve(card: Moveable, shatter: Boolean = false, now: Double, reducedMotion: Boolean = false, onGone: () -> Unit = {}) {
        val dt = 0.7
        card.dissolve = 0.0
        card.shattered = shatter
        card.juiceUp(now = now, reducedMotion = reducedMotion)
        events.addEvent(Event(trigger = "ease", blockable = false, delay = (if (shatter) 0.5 else 1.0) * dt,
            ease = EaseSpec(get = { card.dissolve }, set = { card.dissolve = it }, easeTo = 1.0)))
        events.addEvent(Event(trigger = "after", blockable = false, delay = (if (shatter) 0.55 else 1.05) * dt,
            func = { onGone(); true }))
    }

    /** One simulation step (the body of game.lua Game:update): advance the clock, drain events,
     *  sweep every Moveable's move(), then flush deferred removals. */
    fun tick(dt: Double, paused: Boolean = false) {
        clock.advance(dt, paused = paused)
        events.update(dt, paused = paused)
        for (m in scene.moveables) m.move(clock, paused)
        scene.flushRemovals()
    }
}
