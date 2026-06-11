-- telemetry gate test autorun (run by test/telemetry-gate.sh; staged into the
-- game copy and required as the last line of main.lua, smoke.sh-style).
-- TELGATE_MODE=off : boot to menu, observe 12s, assert telemetry.log was
--                    never created (the shell additionally asserts no [TEL]
--                    or LONG DT lines reached stdout).
-- TELGATE_MODE=on  : boot to menu, flip G.SETTINGS.telemetry_log live (the
--                    gates re-read settings each frame — no restart), push a
--                    synthetic press+release through the run loop's deduped
--                    event pump, observe 12s (> one 5s flush + one 5s PERF
--                    window), assert telemetry.log contains PERF_SNAPSHOT and
--                    the input tracer events (G_MPRESS, G_REL).
local mode = os.getenv('TELGATE_MODE') or 'off'
local elapsed, menu_at, enabled_at, done = 0, nil, nil, false
local pressed_at, released, forced_click = nil, false, false
print('TELGATE: loaded mode=' .. mode)
print('TELGATE: savedir=' .. love.filesystem.getSaveDirectory())

local function finish(ok, msg)
    done = true
    print('TELGATE: ' .. (ok and 'PASS' or 'FAIL') .. ' ' .. msg)
    love.event.quit(ok and 0 or 1)
end

local gu = love.update
love.update = function(dt, ...)
    gu(dt, ...)
    if done then return end
    elapsed = elapsed + dt

    if not menu_at and G and G.STAGE == G.STAGES.MAIN_MENU and G.STATE == G.STATES.MENU then
        menu_at = elapsed
        print('TELGATE: menu reached at ' .. string.format('%.1f', elapsed))
        if mode == 'on' then
            G.SETTINGS.telemetry_log = true
            enabled_at = elapsed
            print('TELGATE: telemetry_log enabled live')
            -- input-tracer wiring diagnostic: report where the press-queue
            -- method actually resolves (telemetry wrapper vs raw controller)
            local qi = Controller and debug.getinfo(Controller.queue_L_cursor_press, 'S')
            print('TELGATE: queue_L_cursor_press defined at ' .. (qi and (qi.short_src .. ':' .. qi.linedefined) or 'nil'))
            local gi = G.CONTROLLER and debug.getinfo(G.CONTROLLER.queue_L_cursor_press, 'S')
            print('TELGATE: instance method defined at ' .. (gi and (gi.short_src .. ':' .. gi.linedefined) or 'nil'))
        end
    end

    -- synthetic input through the real event pump: the run loop stashes
    -- mousepressed for its touch-dedupe pass, so this exercises the same
    -- dispatch path a device press takes (minus the touchpressed flag)
    if mode == 'on' and menu_at and not pressed_at and elapsed - menu_at > 2 then
        pressed_at = elapsed
        love.event.push('mousepressed', 800, 450, 1, false, 1)
    end
    if mode == 'on' and pressed_at and not released and elapsed - pressed_at > 0.3 then
        released = true
        love.event.push('mousereleased', 800, 450, 1, false, 1)
    end
    -- force a clicked/released_on identity transition so the G_CLICKT/G_RELON
    -- tracer paths (and their card_key_of calls) execute under the harness:
    -- the synthetic press lands on empty menu space and never produces a real
    -- click, which let an upvalue-scoping crash in those paths ship to device
    -- while this harness stayed green (2026-06-10). handled stays true, so
    -- no dispatch fires — only the tracer sees the change.
    if mode == 'on' and released and not forced_click and elapsed - pressed_at > 1 and G.CONTROLLER then
        forced_click = true
        G.CONTROLLER.clicked.target = G.ROOM
        G.CONTROLLER.released_on.target = G.ROOM
    end

    if menu_at and elapsed - menu_at > 12 then
        local info = love.filesystem.getInfo('telemetry.log')
        if mode == 'off' then
            if info then
                finish(false, 'mode=off but telemetry.log exists (' .. tostring(info.size) .. ' bytes)')
            else
                finish(true, 'mode=off and no telemetry.log was created')
            end
        else
            if not info then
                finish(false, 'mode=on but telemetry.log was never created')
            else
                local content = love.filesystem.read('telemetry.log') or ''
                if not content:find('PERF_SNAPSHOT', 1, true) then
                    finish(false, 'mode=on telemetry.log exists but has no PERF_SNAPSHOT')
                elseif not content:find('G_MPRESS', 1, true) then
                    finish(false, 'mode=on synthetic press did not produce G_MPRESS')
                elseif not content:find('G_PRESS', 1, true) then
                    finish(false, 'mode=on synthetic press did not produce G_PRESS (late hook not installed or clobbered again)')
                elseif not content:find('G_REL', 1, true) then
                    finish(false, 'mode=on synthetic release did not produce G_REL')
                elseif not content:find('G_CLICKT', 1, true) then
                    finish(false, 'mode=on forced transition did not produce G_CLICKT (tracer path broken)')
                else
                    local _, lines = content:gsub('\n', '')
                    finish(true, 'mode=on telemetry.log has ' .. lines .. ' lines incl PERF_SNAPSHOT + input tracer events')
                end
            end
        end
    end

    if elapsed > 120 and not menu_at then
        finish(false, 'timeout: main menu never reached')
    end
end
