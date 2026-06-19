package systems.balatro.engine

/**
 * Port of Balatro's room/viewport transform (globals.lua constants + game.lua `love.resize`) and the
 * RUN-stage branch of `set_screen_positions` (common_events.lua) — P0-T7.
 *
 * The point of this unit is to DERIVE the play-field card-area origins from first principles —
 * `TILE_W`/`TILE_H` + the `CAI` area dimensions — exactly as the engine does, instead of the frozen
 * `PF.*` constants that were measured off the bref_3 screenshot. That closes the "curve-fit debt":
 * the rewrite computes where each area goes rather than hard-coding the numbers (and, as it turns
 * out, the derivation caught a real ~0.009u error in the measured PF.DECK_Y — see EngineSpineCheck).
 *
 * Area rects are in the ROOM_ATTACH frame (0..TILE_W, 0..TILE_H); the render layer adds the room
 * origin from [transform] and multiplies by the scale `u` to land pixels.
 */
object Room {
    // globals.lua:271-277
    const val TILESIZE = 20.0
    const val TILE_W = 20.0
    const val TILE_H = 11.5
    const val ROOM_PADDING_W = 1.0
    const val ROOM_PADDING_H = 0.7
    const val ROOM_W = TILE_W + 2 * ROOM_PADDING_W   // 22
    const val ROOM_H = TILE_H + 2 * ROOM_PADDING_H   // 12.9
    val CARD_W = 2.4 * 35.0 / 41.0                    // 2.048780…
    val CARD_H = 2.4 * 47.0 / 41.0                    // 2.751220…

    // CAI area dimensions (game.lua:2350-2363) — all in terms of CARD_W/CARD_H.
    val handW = 6.0 * CARD_W;        val handH = 0.95 * CARD_H
    val playW = 5.3 * CARD_W;        val playH = 0.95 * CARD_H
    val jokerW = 4.9 * CARD_W;       val jokerH = 0.95 * CARD_H
    val consumeableW = 2.3 * CARD_W; val consumeableH = 0.95 * CARD_H
    val deckW = 1.1 * CARD_W;        val deckH = 0.95 * CARD_H
    val discardW = CARD_W;           val discardH = CARD_H

    class AreaRect(val x: Double, val y: Double, val w: Double, val h: Double)

    // set_screen_positions (common_events.lua:4-23), RUN stage. ROOM_ATTACH-frame coords.
    val hand: AreaRect get() = AreaRect(TILE_W - handW - 2.85, TILE_H - handH, handW, handH)
    val jokers: AreaRect get() = AreaRect(hand.x - 0.1, 0.0, jokerW, jokerH)
    val consumeables: AreaRect get() = AreaRect(jokers.x + jokerW + 0.2, 0.0, consumeableW, consumeableH)
    val play: AreaRect get() = hand.let { AreaRect(it.x + (handW - playW) / 2, it.y - 3.6, playW, playH) }
    val deck: AreaRect get() = AreaRect(TILE_W - deckW - 0.5, TILE_H - deckH, deckW, deckH)
    val discard: AreaRect get() = AreaRect(jokers.x + jokerW / 2 + 0.3 + 15, 4.2, discardW, discardH)

    /** Device room transform (game.lua `love.resize`): scale `u` (UI units→px) + room origin, in UI units. */
    class RoomTransform(val u: Double, val originX: Double, val originY: Double)

    /** Aspect-contain: width-constrained when the surface is narrower than the room ratio, else height. */
    fun transform(screenW: Double, screenH: Double): RoomTransform {
        val w = screenW
        val h = if (screenW / screenH < 1.0) screenW else screenH   // love.resize square clamp
        val origRatio = ROOM_W / ROOM_H
        return if (w / h < origRatio) {
            val u = w / ROOM_W
            RoomTransform(u, ROOM_PADDING_W, (h / u - (TILE_H + ROOM_PADDING_H)) / 2 + ROOM_PADDING_H / 2)
        } else {
            val u = h / ROOM_H
            RoomTransform(u, (w / u - (TILE_W + ROOM_PADDING_W)) / 2 + ROOM_PADDING_W / 2, ROOM_PADDING_H)
        }
    }
}
