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
    w.frames(2)
    check(w.A.states.drag.is, 'drag should engage on a draggable hand card')
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

H.finish()
