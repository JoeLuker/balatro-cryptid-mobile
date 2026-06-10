-- Controller gesture fuzzer: random legal touch sequences against the REAL
-- built engine/controller.lua, with the warp-driver invariants checked after
-- every step. Joe's on-device observation: the card-warp correlates with the
-- drag-to-select code. The nominal-flow suite (run.lua) is green, so the bug
-- lives in an interleaving nobody scripted — this hunts it mechanically.
--
-- Invariants (violations = the exact state that warps cards on device):
--   I1  finger up  => NO card has states.hover.is true (a wedged hover.is
--       anchors the card's 3D tilt to the cursor forever — the warp)
--   I2  finger up  => dragSelectActive.active is false
--   I3  at most one card has hover.is at any time
--   I4  #hand.highlighted never exceeds the highlight limit
--   I5  finger up (after settle) => no dragging.target
--
-- Usage: luajit test/controller/fuzz.lua            (from repo root)
--   FUZZ_SEEDS=500 FUZZ_STEPS=600 to widen; FUZZ_DRAG_SELECT=0 to disable the
--   drag-select feature for A/B comparison; FUZZ_HID_FLIPS=0 to disable stray
--   mouse-classification noise.

package.path = 'test/controller/?.lua;' .. package.path
local H = dofile('test/controller/harness.lua')

local SEEDS = tonumber(os.getenv('FUZZ_SEEDS') or 300)
local STEPS = tonumber(os.getenv('FUZZ_STEPS') or 400)
local DRAG_SELECT = (os.getenv('FUZZ_DRAG_SELECT') ~= '0')
local HID_FLIPS = (os.getenv('FUZZ_HID_FLIPS') ~= '0')

local function scene()
    local w = H.new_world({ enable_drag_select = DRAG_SELECT })
    w.cards = {}
    for i = 1, 5 do
        w.cards[i] = H.make_node(w, { x = 1.5 * i, y = 8, area = w.hand })
        w.hand.cards[i] = w.cards[i]
    end
    for i = 6, 7 do
        w.cards[i] = H.make_node(w, { x = 2 * (i - 5), y = 2, area = w.jokers, drag_can = true })
        w.jokers.cards[i - 5] = w.cards[i]
    end
    return w
end

-- random points: biased mix of on-card and empty felt
local function rand_point()
    local r = math.random()
    if r < 0.45 then           -- on a hand card
        return 1.5 * math.random(1, 5) + 0.5, 8.5
    elseif r < 0.6 then        -- on a joker
        return 2 * math.random(1, 2) + 0.5, 2.5
    else                       -- empty felt
        return 1 + math.random() * 12, 4.5 + math.random() * 2.5
    end
end

local function run_seed(seed)
    math.randomseed(seed)
    local w = scene()
    local log = {}
    local finger_down = false

    local function step_op()
        local r = math.random()
        if not finger_down then
            if r < 0.4 then
                local x, y = rand_point()
                log[#log + 1] = string.format('down(%.2f,%.2f)', x, y)
                w.touch_down(x, y)
                finger_down = true
            elseif r < 0.55 then
                -- late position teleport: press carries fresh coords while the
                -- synthetic mouse position is still at the previous touch
                local x, y = rand_point()
                log[#log + 1] = string.format('down_stale(%.2f,%.2f)', x, y)
                w.touches = { 1 }
                w.ctrl:set_HID_flags('touch')
                w.ctrl:queue_L_cursor_press(x, y)
                w.frame()
                w.mx, w.my = x, y
                w.frame()
                finger_down = true
            else
                local n = math.random(1, 20)
                log[#log + 1] = 'frames(' .. n .. ')'
                w.frames(n)
            end
        else
            if r < 0.5 then
                local x, y = rand_point()
                local steps = math.random(1, 8)
                log[#log + 1] = string.format('move(%.2f,%.2f,%d)', x, y, steps)
                w.touch_move(x, y, steps)
            elseif r < 0.8 then
                log[#log + 1] = 'up()'
                w.touch_up()
                finger_down = false
            else
                local n = math.random(1, 15)
                log[#log + 1] = 'frames(' .. n .. ')'
                w.frames(n)
            end
        end
        -- stray classification noise: a mis-batched event flipping the global
        -- HID flag. With touch_env this must be harmless.
        if HID_FLIPS and math.random() < 0.1 then
            log[#log + 1] = 'hid(mouse)'
            w.ctrl:set_HID_flags('mouse')
            w.frame()
        end
    end

    local function invariants(step)
        local hovered = {}
        for i, c in ipairs(w.cards) do
            if c.states.hover.is then hovered[#hovered + 1] = i end
        end
        if not finger_down and #hovered > 0 then
            return string.format('I1 wedged hover.is on card(s) %s with finger up', table.concat(hovered, ','))
        end
        if #hovered > 1 then
            return string.format('I3 multiple cards hovered: %s', table.concat(hovered, ','))
        end
        if not finger_down and w.ctrl.dragSelectActive and w.ctrl.dragSelectActive.active then
            return 'I2 dragSelectActive stuck with finger up'
        end
        if #w.hand.highlighted > w.hand.config.highlighted_limit then
            return string.format('I4 highlight overflow: %d > %d', #w.hand.highlighted, w.hand.config.highlighted_limit)
        end
        return nil
    end

    for step = 1, STEPS do
        local ok, err = pcall(step_op)
        if not ok then
            return string.format('CRASH seed=%d step=%d: %s', seed, step, tostring(err)), log
        end
        local viol = invariants(step)
        if viol then
            -- settle a few frames: transient single-frame states are not wedges
            w.frames(3)
            local still = invariants(step)
            if still then
                return string.format('VIOLATION seed=%d step=%d: %s', seed, step, still), log
            end
        end
    end

    -- end-of-seed: lift finger, settle, then the strict global check
    if finger_down then w.touch_up(); w.frames(5) end
    w.frames(10)
    for i, c in ipairs(w.cards) do
        if c.states.hover.is then
            return string.format('VIOLATION seed=%d end-state: card %d hover.is wedged', seed, i), log
        end
    end
    if w.ctrl.dragging.target then
        return string.format('VIOLATION seed=%d end-state: dragging.target wedged', seed), log
    end
    return nil, log
end

print(string.format('fuzz: %d seeds x %d steps, drag_select=%s, hid_flips=%s, game dir: %s',
    SEEDS, STEPS, tostring(DRAG_SELECT), tostring(HID_FLIPS), os.getenv('BALATRO_GAME_DIR') or 'build/game'))

local fails = 0
for seed = 1, SEEDS do
    local err, log = run_seed(seed)
    if err then
        fails = fails + 1
        print('  ' .. err)
        local from = math.max(1, #log - 14)
        print('    last ops: ' .. table.concat(log, ' ', from))
        if fails >= 5 then print('  (stopping after 5 failures)') break end
    end
end

if fails > 0 then
    print(string.format('\nfuzz FAILED: %d seed(s) violated invariants', fails))
    os.exit(1)
end
print('\nfuzz clean: no invariant violations')
os.exit(0)
