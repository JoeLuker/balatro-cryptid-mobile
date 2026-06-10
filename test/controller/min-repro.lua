-- Minimal warp-wedge repro with per-frame state tracing. Replays the gesture
-- class the fuzzer found (press a hand card, drag across the hand, release)
-- and prints every transition of hovering.target / per-card hover.is so the
-- exact orphaning frame and code path is visible.

package.path = 'test/controller/?.lua;' .. package.path
local H = dofile('test/controller/harness.lua')

local w = H.new_world({})
w.cards = {}
for i = 1, 5 do
    w.cards[i] = H.make_node(w, { x = 1.5 * i, y = 8, area = w.hand })
    w.hand.cards[i] = w.cards[i]
end

local function snap(label)
    local hov = {}
    for i, c in ipairs(w.cards) do
        if c.states.hover.is then hov[#hov + 1] = i end
    end
    local ht = '-'
    for i, c in ipairs(w.cards) do
        if w.ctrl.hovering.target == c then ht = tostring(i) end
    end
    if w.ctrl.hovering.target and ht == '-' then ht = 'other' end
    print(string.format('%-28s cursor=(%.1f,%.1f) down=%s hovering.target=%s hover.is={%s} drag=%s chov=%s',
        label, w.mx, w.my, tostring(w.ctrl.is_cursor_down), ht, table.concat(hov, ','),
        tostring(w.ctrl.dragging.target ~= nil),
        (function()
            for i, c in ipairs(w.cards) do if w.ctrl.cursor_hover.target == c then return i end end
            return w.ctrl.cursor_hover.target and 'other' or '-'
        end)()))
end

print('--- press card 4, hold past the 0.2s gate ---')
w.touch_down(6.5, 8.5); snap('down on card4')
w.frames(15); snap('held 0.25s')

print('--- drag left across cards 3, 2, 1 (one frame per step) ---')
for _, x in ipairs({ 5.0, 4.4, 3.5, 2.9, 2.0 }) do
    w.touch_move(x, 8.5, 1)
    snap(string.format('moved to x=%.1f', x))
end

print('--- release ---')
w.touch_up(); snap('released')
w.frames(2); snap('settle +2f')
w.frames(10); snap('settle +12f')

local wedged = {}
for i, c in ipairs(w.cards) do
    if c.states.hover.is then wedged[#wedged + 1] = i end
end
if #wedged > 0 then
    print('\nWEDGED: card(s) ' .. table.concat(wedged, ',') .. ' hover.is stuck with finger up')
    os.exit(1)
end
print('\nclean')
os.exit(0)
