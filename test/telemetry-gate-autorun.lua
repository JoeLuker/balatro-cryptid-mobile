-- telemetry gate test autorun (run by test/telemetry-gate.sh; staged into the
-- game copy and required as the last line of main.lua, smoke.sh-style).
-- TELGATE_MODE=off : boot to menu, observe 12s, assert telemetry.log was
--                    never created (the shell additionally asserts no [TEL]
--                    or LONG DT lines reached stdout).
-- TELGATE_MODE=on  : boot to menu, flip G.SETTINGS.telemetry_log live (the
--                    gates re-read settings each frame — no restart), observe
--                    12s (> one 5s flush + one 5s PERF window), assert
--                    telemetry.log exists and contains a PERF_SNAPSHOT.
local mode = os.getenv('TELGATE_MODE') or 'off'
local elapsed, menu_at, enabled_at, done = 0, nil, nil, false
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
        end
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
                if content:find('PERF_SNAPSHOT', 1, true) then
                    local _, lines = content:gsub('\n', '')
                    finish(true, 'mode=on telemetry.log has ' .. lines .. ' lines incl PERF_SNAPSHOT')
                else
                    finish(false, 'mode=on telemetry.log exists but has no PERF_SNAPSHOT')
                end
            end
        end
    end

    if elapsed > 120 and not menu_at then
        finish(false, 'timeout: main menu never reached')
    end
end
