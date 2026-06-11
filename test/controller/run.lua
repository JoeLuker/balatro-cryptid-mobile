-- Gesture regression suite for our touch patches (TAP_DESC_*, DRAG_SELECT_*).
-- Runs the REAL built engine/controller.lua. Usage:
--   luajit test/controller/run.lua          (from the repo root)
--   BALATRO_GAME_DIR=... to point elsewhere

package.path = 'test/controller/?.lua;' .. package.path
local H = dofile('test/controller/harness.lua')
local test, check = H.test, H.check

-- Standard scene: three hand cards side by side, one joker above.
local function scene(opts)
    local w = H.new_world(opts)
    w.A = H.make_node(w, { x = 2, y = 8, area = w.hand })
    w.B = H.make_node(w, { x = 4, y = 8, area = w.hand })
    w.C = H.make_node(w, { x = 6, y = 8, area = w.hand })
    w.J = H.make_node(w, { x = 2, y = 2, area = w.jokers, drag_can = true })
    return w
end

print('controller gesture suite — game dir: ' .. (os.getenv('BALATRO_GAME_DIR') or 'build/game'))

test('quick tap on hand card selects it without showing a description', function()
    local w = scene()
    w.touch_down(2.7, 9)        -- on A
    w.frames(3)                 -- ~0.05s held: below the 0.2s hold gate
    check(not w.A.states.hover.is, 'description must not appear during a sub-0.2s touch (HOLDGATE)')
    w.touch_up()
    w.frames(3)
    check(w.A.highlighted, 'quick tap should select (highlight) the card')
    check(w.ctrl.shown_desc == nil, 'quick tap must not persist a description')
    check(not w.A.states.hover.is, 'no hover left behind after quick tap')
end)

test('holding a hand card shows its description and it persists after lift', function()
    local w = scene()
    w.touch_down(2.7, 9)        -- on A
    w.frames(20)                -- ~0.33s: past the 0.2s hold gate
    check(w.A.states.hover.is, 'hover.is should be set while deliberately holding (>0.2s)')
    local stops_before = w.A.calls.stop_hover or 0
    w.touch_up()
    w.frames(5)
    check(w.ctrl.hovering.target == w.A, 'description (hovering.target) must persist after lift (PERSIST)')
    check((w.A.calls.stop_hover or 0) == stops_before, 'stop_hover must NOT fire on lift — it would destroy the popup (HOLD_NODRAG)')
    check(not w.A.states.hover.is, 'hover.is must relax once the finger is off the card, killing the tilt-warp (RELAX)')
    check(not w.A.highlighted, 'a stationary hold is not a select')
end)

test('after a held description, finger on another card does not warp the first', function()
    local w = scene()
    w.touch_down(2.7, 9); w.frames(20)          -- hold A, description up
    w.touch_up(); w.frames(2)
    w.touch_down(4.7, 9)                         -- press B
    w.frames(8)                                  -- ~0.13s: below B's hold gate
    -- The warp driver is states.hover.is: the card draw aims its mesh tilt at
    -- the live cursor whenever it is true (card.lua tilt branch). RELAX must
    -- keep it false on A the whole time the finger is elsewhere.
    check(not w.A.states.hover.is, 'A must not re-enter hover while touching B (RELAX = no warp)')
    w.frames(12)                                 -- past 0.2s: B earns its own description
    check(w.B.states.hover.is, 'B shows its own description after a deliberate hold')
    check(not w.A.states.hover.is, 'A still relaxed while B is held')
    w.touch_up()
end)

test('tapping another hand card dismisses the held description', function()
    local w = scene()
    w.touch_down(2.7, 9); w.frames(20); w.touch_up(); w.frames(2)
    check(w.ctrl.hovering.target == w.A, 'precondition: A description persisted')
    w.touch_down(4.7, 9); w.frames(2); w.touch_up(); w.frames(3)
    check(w.ctrl.hovering.target ~= w.A, 'tap elsewhere must dismiss the persisted description')
    check(w.B.highlighted, 'and the tap still selects B')
end)

test('drag past the click threshold is a reorder drag, not a hold', function()
    local w = scene()
    w.touch_down(2.7, 9)        -- on A (hand cards draggable for reorder)
    -- DRAG_SELECT_CARD_START: hand pickup is deferred to a ~0.2s hold; an
    -- immediate move would be a slide-select sweep instead
    w.frames(15)
    check(w.A.states.drag.is, 'drag should engage on a draggable hand card after the hold')
    w.touch_move(6.7, 9, 12)    -- well past MIN_CLICK_DIST (0.9)
    w.touch_up()
    w.frames(2)
    check((w.A.calls.stop_drag or 0) > 0, 'drag must stop on release')
    check(w.ctrl.hovering.target ~= w.A, 'a real drag must not leave a persisted description')
end)

test('slide from empty space across the hand multi-selects (DRAG_SELECT)', function()
    local w = scene()
    w.touch_down(0.5, 5)        -- empty space: no card there
    w.frames(2)
    check(w.ctrl.dragSelectActive and w.ctrl.dragSelectActive.active, 'drag-select must arm on empty-space touch')
    w.touch_move(2.7, 9, 8)     -- sweep onto A
    w.touch_move(4.7, 9, 8)     -- across B
    w.touch_move(6.7, 9, 8)     -- across C
    check(w.A.highlighted, 'A selected by sweep')
    check(w.B.highlighted, 'B selected by sweep')
    check(w.C.highlighted, 'C selected by sweep')
    w.touch_up()
    check(not w.ctrl.dragSelectActive.active, 'drag-select must disarm on lift')
end)

test('slide starting on a selected card deselects consistently (mode lock)', function()
    local w = scene()
    w.hand:add_to_highlighted(w.A)
    w.hand:add_to_highlighted(w.B)
    w.touch_down(0.5, 5)
    w.touch_move(2.7, 9, 8)     -- first touched card A is highlighted -> deselect mode
    w.touch_move(4.7, 9, 8)     -- B also deselected
    w.touch_move(6.7, 9, 8)     -- C is unhighlighted; mode=deselect must NOT select it
    check(not w.A.highlighted, 'A deselected')
    check(not w.B.highlighted, 'B deselected')
    check(not w.C.highlighted, 'mode lock: a deselect sweep must not select C')
    w.touch_up()
end)

test('drag-select respects the settings toggle', function()
    local w = scene({ enable_drag_select = false })
    w.touch_down(0.5, 5)
    w.frames(2)
    check(not (w.ctrl.dragSelectActive and w.ctrl.dragSelectActive.active), 'must not arm when toggle is off')
    w.touch_move(4.7, 9, 10)
    check(not w.B.highlighted, 'no selection when toggle is off')
    w.touch_up()
end)

test('hand selection limit holds during a sweep', function()
    local w = scene({ highlighted_limit = 2 })
    w.touch_down(0.5, 5)
    w.touch_move(2.7, 9, 6); w.touch_move(4.7, 9, 6); w.touch_move(6.7, 9, 6)
    local count = 0
    for _, c in ipairs({ w.A, w.B, w.C }) do if c.highlighted then count = count + 1 end end
    check(count == 2, 'sweep must respect highlighted_limit (got ' .. count .. ')')
    w.touch_up()
end)

test('joker tap toggles a persistent description (non-hand behaviour)', function()
    local w = scene()
    w.touch_down(2.7, 2.5)      -- on J (joker)
    w.frames(2)
    check(w.J.states.hover.is, 'joker shows description immediately on touch (no hold gate)')
    w.touch_up(); w.frames(3)
    check(w.ctrl.shown_desc == w.J, 'tap toggles the persistent description on')
    check(w.ctrl.hovering.target == w.J, 'description persists after lift')
    w.touch_down(2.7, 2.5); w.frames(2); w.touch_up(); w.frames(3)
    check(w.ctrl.shown_desc == nil, 'second tap dismisses')
end)

-- TAP_DESC_REHOVER: the controller only calls hover() (which creates the
-- description popup) when hovering.target CHANGES. After an in-place
-- press-release on a draggable card, hovering.target stays pointed at that
-- card with its hover state dead — so re-pressing the SAME card never
-- re-fires hover() and the description cannot be shown twice in a row
-- without visiting a different card first (trace-confirmed on-device
-- 2026-06-10, j_cry_coin: first press popup up, second press hover re-
-- acquired but no popup). The fix clears hovering.prev_target on a press
-- whose target is the stale hovering.target, forcing the change-detection
-- to re-fire hover() through the normal MIN_HOVER_TIME path.
test('re-pressing the same stale-hovered card re-fires hover (TAP_DESC_REHOVER)', function()
    local w = scene()
    w.touch_down(2.7, 2.5); w.frames(12)  -- past MIN_HOVER_TIME so the delayed hover() event fires
    local hovers_after_first = w.J.calls.hover or 0
    check(hovers_after_first >= 1, 'precondition: first press fires hover()')
    w.touch_up(); w.frames(3)
    check(w.ctrl.hovering.target == w.J, 'precondition: hovering.target stays on J after in-place release')
    w.touch_down(2.7, 2.5); w.frames(12)
    check((w.J.calls.hover or 0) > hovers_after_first,
        'second press on the stale-hovered card must re-fire hover() (description re-summon)')
    w.touch_up(); w.frames(2)
end)

-- TAP_DESC_STALE_CLEAR contract: shown_desc (the toggle's memory of whose
-- description is on screen) must die whenever the popup dies, i.e. whenever
-- stop_hover removes it — otherwise the next tap on the same card takes the
-- dismiss branch and the description cannot be re-summoned without visiting
-- a different card first (reported on-device 2026-06-10). The mock node's
-- stop_hover mirrors the patched node.lua; the marker grep in
-- apply_tap_description_persist verifies the real file carries it.
test('stop_hover clears the description-toggle memory, enabling re-summon', function()
    local w = scene()
    w.touch_down(2.7, 2.5); w.frames(2); w.touch_up(); w.frames(3)
    check(w.ctrl.shown_desc == w.J, 'precondition: joker description toggled on')
    -- the popup dies via stop_hover (any external dismissal path)
    w.J:stop_hover()
    check(w.ctrl.shown_desc == nil, 'stale-clear: shown_desc dies with the popup')
    -- the same joker must show its description again on the very next tap
    w.touch_down(2.7, 2.5); w.frames(2); w.touch_up(); w.frames(3)
    check(w.ctrl.shown_desc == w.J, 'description re-summons on the same card (stale shown_desc regression)')
end)

-- HID_ISTOUCH_RELEASE_FIX: HID.touch must be true on the release frame even
-- if set_HID_flags was last called with 'mouse' (simulating a cross-pump-batch
-- scenario where the SDL event scheduler delivers touchreleased and
-- mousereleased in separate pump calls, leaving HID stale between them).
-- TAP_DESC_RELAX (controller.lua:~447) reads HID.touch on the release frame to
-- clear hover state; stale-false leaves tooltip/hover stuck after a tap.
test('HID.touch stays true at release when HID was stale-mouse between batches', function()
    local w = scene()
    w.touch_down(2.7, 9)        -- tap A; sets HID.touch=true
    w.frames(3)
    -- Simulate SDL batch-split: set HID back to mouse as if a stale dispatch
    -- arrived between touchreleased and mousereleased pump batches.
    w.ctrl:set_HID_flags('mouse')
    check(not w.ctrl.HID.touch, 'precondition: HID is stale-mouse before release')
    -- love.mousereleased (patched) calls set_HID_flags(istouch and 'touch' or 'mouse')
    -- before L_cursor_release; mirror that here with istouch=true.
    w.ctrl:set_HID_flags('touch')  -- HID_ISTOUCH_RELEASE_FIX: what the patched handler does
    w.ctrl:L_cursor_release(2.7, 9)
    w.frames(3)
    -- TAP_DESC_RELAX fires in the first update after cursor_up; hover must be cleared.
    check(not w.A.states.hover.is, 'TAP_DESC_RELAX must clear hover on touch lift (stale-HID regression)')
end)

-- HID_ISTOUCH_RELEASE_FIX regression: TAP_DESC_HOLD_NODRAG guard must suppress
-- spurious release() on short touch-drags even when HID was stale-mouse at release.
-- Guard: not (HID.touch AND dist < MIN_CLICK_DIST). With stale-false, the guard
-- is not(false AND ...) = true, so release() fires on the card under the cursor,
-- incorrectly treating a short drag as a drop. With fix, HID.touch is true and the
-- guard correctly suppresses the release.
test('short touch-drag with stale-HID does not fire spurious release on lift (HOLD_NODRAG, HID_ISTOUCH_RELEASE_FIX)', function()
    local w = scene()
    w.B.states.release_on.can = true   -- B is a valid drop target if HOLD_NODRAG misfires
    w.touch_down(2.7, 9)               -- press A (draggable hand card)
    w.frames(15)                       -- past the hold: pickup engages (DRAG_SELECT_HOLD_REORDER)
    check(w.ctrl.dragging.target == w.A, 'precondition: A is being dragged')
    -- Move less than MIN_CLICK_DIST=0.9: short drag, must NOT be treated as a drop
    w.touch_move(3.0, 9, 2)
    check((w.ctrl.cursor_down.distance or 0) < 0.9, 'precondition: distance below MIN_CLICK_DIST')
    -- Simulate stale HID (batch-split) then correct it before release, as the fix does
    w.ctrl:set_HID_flags('mouse')
    check(not w.ctrl.HID.touch, 'precondition: HID is stale-mouse before release')
    w.ctrl:set_HID_flags('touch')      -- HID_ISTOUCH_RELEASE_FIX: fix restores touch before L_cursor_release
    w.ctrl:L_cursor_release(3.0, 9)
    w.frames(2)
    -- With HID.touch=true: guard fires, released_on stays unhandled=true (never set)
    check(w.ctrl.released_on.handled, 'HOLD_NODRAG: short touch-drag must not set released_on')
    check((w.B.calls.release or 0) == 0, 'B must not receive spurious release() from a short drag')
end)

-- HID_TOUCH_ENV fallback: when HID_ISTOUCH_RELEASE_FIX does not fire (worst case:
-- the per-event fix is absent or the event arrives without the istouch flag), the
-- stable touch_env predicate (set once from love.system.getOS()=='Android') must
-- keep all five touch gates armed. Simulate by forcing HID.touch=false for the
-- whole gesture and confirming TAP_DESC_RELAX still clears hover.is (needs touch_env
-- true, which the harness provides via getOS() returning 'Android').
test('touch_env keeps TAP_DESC_RELAX armed when HID.touch is stuck-false', function()
    local w = scene()
    w.touch_down(2.7, 9)
    w.frames(20)    -- hold past 0.2s so hover.is becomes true via HOLDGATE
    check(w.A.states.hover.is, 'precondition: hover.is set after deliberate hold')
    -- Force HID.touch to false for the whole release, bypassing the fix
    w.ctrl:set_HID_flags('mouse')
    check(not w.ctrl.HID.touch, 'precondition: HID.touch is false')
    check(w.ctrl.HID.touch_env, 'precondition: touch_env is true (Android harness)')
    w.ctrl:L_cursor_release(2.7, 9)
    w.frames(3)
    -- touch_env is true, so (HID.touch or HID.touch_env) fires TAP_DESC_RELAX
    check(not w.A.states.hover.is, 'TAP_DESC_RELAX must clear hover.is via touch_env even with HID.touch=false')
end)

test('touch_env keeps DRAG_SELECT_ACTIVATE armed when HID.touch is stuck-false', function()
    local w = scene()
    -- Force HID.touch to false before the touch-down, keeping touch_env true
    w.ctrl:set_HID_flags('mouse')
    check(not w.ctrl.HID.touch, 'precondition: HID.touch is false')
    check(w.ctrl.HID.touch_env, 'precondition: touch_env is true (Android harness)')
    w.touch_down(0.5, 5)    -- empty space: no card hit
    w.frames(2)
    check(w.ctrl.dragSelectActive and w.ctrl.dragSelectActive.active,
        'drag-select must arm via touch_env even when HID.touch is false')
    w.touch_move(2.7, 9, 8); w.touch_move(4.7, 9, 8)
    check(w.A.highlighted, 'A selected by sweep (touch_env path)')
    check(w.B.highlighted, 'B selected by sweep (touch_env path)')
    w.touch_up()
end)

test('release dispatch tolerates the released-on node dying before dispatch (RELEASED_ON_NIL_GUARD)', function()
    local w = scene()
    -- The booster-screen crash: released_on.handled=false is only ever set
    -- alongside a valid target, but Node:remove nils the controller's
    -- released_on.target when that node is destroyed (temp drag-targets from
    -- sticky-fingers Pull die at drag end). Forge that exact state and step.
    w.touch_down(2.7, 9); w.frames(20)
    w.touch_up(); w.frames(1)
    -- dragging.prev_target is re-stamped from dragging.target every frame
    -- (controller.lua:321), so the live drag must be forged too
    w.ctrl.dragging.target = w.A
    w.ctrl.released_on.handled = false
    w.ctrl.released_on.target = nil
    w.frames(1)             -- pre-guard: attempt to index field 'target' (nil)
    check(w.ctrl.released_on.handled, 'dispatch must mark handled even when the target died')
    w.ctrl.dragging.target = nil
end)

test('RELEASED_ON_NIL_GUARD calls ATLOG when available, falls back to print otherwise', function()
    local w = scene()
    -- stub ATLOG to intercept the call
    local logged = {}
    _G.ATLOG = function(event, data) logged[#logged + 1] = { event = event, data = data } end
    w.ctrl.dragging.target = w.A
    w.ctrl.released_on.handled = false
    w.ctrl.released_on.target = nil
    w.frames(1)
    _G.ATLOG = nil
    check(#logged == 1, 'ATLOG must be called exactly once on nil-target dispatch')
    check(logged[1].event == 'G_REL_SKIP', 'event name must be G_REL_SKIP')
    check(logged[1].data and logged[1].data.state ~= nil, 'data must carry state field')
    w.ctrl.dragging.target = nil
end)

test('press with a late position teleport targets the press coords, not the stale cursor (TOUCH_PRESS_POS_SYNC)', function()
    local w = scene()
    -- tap card A; the synthetic cursor parks there
    w.touch_down(2.7, 9); w.frames(3); w.touch_up(); w.frames(5)
    -- press empty felt, but the synthetic mouse teleport arrives a frame late:
    -- queue the press at the TRUE coords while world.mx/my still sit on A
    w.touches = { 1 }
    w.ctrl:set_HID_flags('touch')
    w.ctrl:queue_L_cursor_press(8.0, 5.0)
    w.frame()
    check(w.ctrl.dragSelectActive and w.ctrl.dragSelectActive.active,
        'drag-select must arm from the press coordinates (empty felt), not the stale cursor position')
    w.mx, w.my = 8.0, 5.0
    w.frame()
    -- sweep onto B (A is already highlighted from the tap; ending on A would
    -- correctly enter deselect mode — that's the feature, not the bug)
    w.touch_move(4.7, 9, 6)
    check(w.B.highlighted, 'sweep after late-teleport press must still select')
    w.touch_up()
end)

test('card-start slide: quick sweep from a hand card multi-selects, no reorder (DRAG_SELECT_CARD_START)', function()
    local w = scene()
    w.touch_down(2.7, 9)                 -- on A
    w.frames(3)                          -- well under the 0.2s hold
    check(not w.A.states.drag.is, 'no instant pickup on a hand card (deferred to hold)')
    check(not w.A.highlighted, 'start card not toggled until the sweep begins')
    w.touch_move(4.7, 9, 4)              -- cross onto B
    check(w.A.highlighted, 'sweep start toggles the start card')
    check(w.B.highlighted, 'and the crossed card')
    check(w.ctrl.dragging.target == nil, 'sweeping must not reorder')
    w.touch_move(6.7, 9, 4)              -- on to C
    check(w.C.highlighted, 'sweep continues across the hand')
    w.touch_up()
    w.frames(2)
end)

test('card-start slide from a highlighted card deselects (mode seeded from start card)', function()
    local w = scene()
    w.hand:add_to_highlighted(w.A)
    w.hand:add_to_highlighted(w.B)
    w.touch_down(2.7, 9)                 -- on highlighted A
    w.frames(3)
    w.touch_move(4.7, 9, 4)              -- cross onto highlighted B
    check(not w.A.highlighted, 'start card deselected')
    check(not w.B.highlighted, 'crossed card deselected (deselect mode)')
    w.touch_up()
end)

test('hold-then-drag still reorders: pickup engages at the hold threshold (DRAG_SELECT_HOLD_REORDER)', function()
    local w = scene()
    w.touch_down(2.7, 9)
    w.frames(15)                         -- ~0.25s: past the hold
    check(w.ctrl.dragging.target == w.A, 'hold picks the card up for reorder')
    check(not (w.ctrl.dragSelectActive and w.ctrl.dragSelectActive.active), 'slide arming ends when reorder begins')
    w.touch_move(6.7, 9, 8)
    check(not w.B.highlighted and not w.C.highlighted, 'reorder drag must not multi-select')
    w.touch_up()
end)

test('lifting right after the description appears keeps it (TAP_DESC_HOLD_KEEP / HOLD_NOSELECT)', function()
    local w = scene()
    -- the killer duration: past the 0.2s description threshold but inside the
    -- 0.3s click_timeout — this registered as a tap and dismissed+selected
    w.touch_down(2.7, 9)
    w.frames(15)                 -- ~0.25s
    check(w.A.states.hover.is, 'description up during the hold')
    w.touch_up()
    w.frames(3)
    check(w.ctrl.hovering.target == w.A, 'description must persist after a 0.25s hold-release')
    check(not w.A.highlighted, 'a hold-release must not select the card')
end)

H.finish()
