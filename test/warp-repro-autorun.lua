-- WARP REPRO autorun: injected by test/warp-repro.sh into a disposable copy of
-- build/game, with the phone's save pre-seeded into the save dir. Boots to the
-- menu, loads the saved run (same path as the menu Continue button), waits for
-- the shop to settle, then dumps per-joker transform + move bookkeeping and a
-- screenshot. Greppable markers: WARP: ... / WARP: PASS / WARP: CRASH.

local elapsed = 0
local last_report = 0
local run_started = false
local in_run_at = nil
local done = false
local probe_done = false

local BOOT_BUDGET = 120
local SETTLE = 8

print('WARP: autorun loaded')
print('WARP: savedir=' .. love.filesystem.getSaveDirectory())

local game_errorhandler = love.errorhandler
love.errorhandler = function(msg)
    print('WARP: CRASH ' .. tostring(msg))
    print(debug.traceback())
    if game_errorhandler then pcall(game_errorhandler, msg) end
    os.exit(70)
end

local function fmt(n)
    if type(n) == 'number' then return string.format('%.3f', n) end
    return tostring(n)
end

local function dump_state()
    -- nil-hole audit of the two per-frame iteration arrays
    for _, name in ipairs({'MOVEABLES', 'ANIMATIONS'}) do
        local t = G[name]
        local n_ipairs = 0
        for _ in ipairs(t) do n_ipairs = n_ipairs + 1 end
        local n_pairs, max_k = 0, 0
        for k in pairs(t) do
            n_pairs = n_pairs + 1
            if type(k) == 'number' and k > max_k then max_k = k end
        end
        print(string.format('WARP: %s ipairs=%d pairs=%d maxk=%d holes=%s',
            name, n_ipairs, n_pairs, max_k, tostring(n_pairs ~= max_k or n_ipairs ~= n_pairs)))
    end

    -- index of each joker card in G.MOVEABLES
    local idx = {}
    for i, v in ipairs(G.MOVEABLES) do idx[v] = i end

    print('WARP: FRAMES.MOVE=' .. tostring(G.FRAMES.MOVE))
    for i, c in ipairs(G.jokers.cards) do
        local key = c.config and c.config.center and c.config.center.key or '?'
        print(string.format(
            'WARP: joker[%d] %s mvidx=%s FRAME.MOVE=%s T={x=%s y=%s w=%s h=%s r=%s s=%s} VT={x=%s y=%s w=%s h=%s r=%s s=%s} hover=%s juice=%s',
            i, key, tostring(idx[c]), tostring(c.FRAME and c.FRAME.MOVE),
            fmt(c.T.x), fmt(c.T.y), fmt(c.T.w), fmt(c.T.h), fmt(c.T.r), fmt(c.T.scale),
            fmt(c.VT.x), fmt(c.VT.y), fmt(c.VT.w), fmt(c.VT.h), fmt(c.VT.r), fmt(c.VT.scale),
            tostring(c.states.hover.is), tostring(c.juice ~= nil)))
    end
end

local function shot()
    love.graphics.captureScreenshot(function(imgdata)
        imgdata:encode('png', 'warp.png')
        print('WARP: SHOT-WRITTEN')
    end)
end

local game_update = love.update
love.update = function(dt, ...)
    game_update(dt, ...)
    elapsed = elapsed + dt

    if elapsed - last_report >= 5 then
        last_report = elapsed
        print(string.format('WARP: t=%.0f stage=%s state=%s fps=%d',
            elapsed, tostring(G and G.STAGE), tostring(G and G.STATE), love.timer.getFPS()))
    end

    if not run_started and G and G.STAGE == G.STAGES.MAIN_MENU and G.STATE == G.STATES.MENU and elapsed > 5 then
        run_started = true
        local saved = get_compressed(G.SETTINGS.profile .. '/save.jkr')
        if not saved then
            print('WARP: FAIL no save.jkr found for profile ' .. tostring(G.SETTINGS.profile))
            done = true
            love.event.quit(1)
            return
        end
        print('WARP: continuing saved run (profile ' .. tostring(G.SETTINGS.profile) .. ')')
        G.SAVED_GAME = STR_UNPACK(saved)
        G.FUNCS.start_run(nil, { savetext = G.SAVED_GAME })
    end

    if run_started and not in_run_at and G.STAGE == G.STAGES.RUN and G.STATE ~= G.STATES.SPLASH then
        in_run_at = elapsed
        print('WARP: RUN-LOADED state=' .. tostring(G.STATE))
    end

    if in_run_at and not done and elapsed - in_run_at >= SETTLE then
        done = true
        dump_state()
        shot()
        -- DRAG_SELECT probe: touch-press empty felt (above the hand, below the
        -- jokers) and see whether the arming condition (#collision_list == 0)
        -- can ever hold in the real game, unlike the harness's synthetic world.
        G.CONTROLLER:set_HID_flags('touch')
        G.CONTROLLER:queue_L_cursor_press(love.graphics.getWidth() * 0.55, love.graphics.getHeight() * 0.55)
    end

    if done and not probe_done and in_run_at and elapsed - in_run_at >= SETTLE + 1 then
        probe_done = true
        local cl = G.CONTROLLER.collision_list or {}
        print(string.format('WARP: dragselect setting=%s active=%s collision_list=%d',
            tostring(G.SETTINGS.enable_drag_select),
            tostring(G.CONTROLLER.dragSelectActive and G.CONTROLLER.dragSelectActive.active),
            #cl))
        for i, v in ipairs(cl) do
            local kind = (v.is and v:is(Card) and 'Card')
                or (v.is and v:is(CardArea) and 'CardArea')
                or (v.is and UIElement and v:is(UIElement) and 'UIElement')
                or (v.is and UIBox and v:is(UIBox) and 'UIBox')
                or 'Node'
            print(string.format('WARP: collide[%d] %s T={x=%s y=%s w=%s h=%s}',
                i, kind, fmt(v.T.x), fmt(v.T.y), fmt(v.T.w), fmt(v.T.h)))
        end
        G.CONTROLLER:L_cursor_release(love.graphics.getWidth() * 0.55, love.graphics.getHeight() * 0.55)
    end

    if done and probe_done and elapsed - in_run_at >= SETTLE + 3 then
        print('WARP: PASS')
        love.event.quit(0)
    end

    if elapsed > BOOT_BUDGET and not in_run_at then
        print('WARP: FAIL run not loaded within budget')
        love.event.quit(1)
    end
end
