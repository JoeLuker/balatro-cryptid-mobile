-- SMOKE HARNESS autorun: injected (appended require) into a disposable copy of
-- build/game by test/smoke.sh. Runs after ALL of main.lua (so after SMODS,
-- Talisman, telemetry, and every wrapper has installed) and hooks the outermost
-- love.update. Prints SMOKE: markers to stdout for the harness to grep,
-- screenshots once the main menu is reached, then quits.
--
-- PASS criteria (asserted by smoke.sh from the log):
--   SMOKE: MENU-REACHED   — G.STAGE==MAIN_MENU and G.STATE==MENU within budget
--   SMOKE: SHOT-WRITTEN   — screenshot encoded into the save dir
--   SMOKE: PASS           — clean quit after both
--   absence of SMOKE: CRASH / love boot errors

local elapsed = 0
local last_report = 0
local menu_at = nil
local shot_requested = false
local shot_written = false
local done = false

local BOOT_BUDGET = 90      -- seconds to reach the menu before we call it a fail
local SETTLE_AFTER_MENU = 4 -- let the menu animate in before screenshotting

print('SMOKE: autorun loaded')
print('SMOKE: savedir=' .. love.filesystem.getSaveDirectory())

-- Outermost crash trap: chain to whatever handler the game installed so we
-- still get the STP traceback in the log, but make the failure greppable and
-- ensure the process exits nonzero instead of sitting on a crash screen.
local game_errorhandler = love.errorhandler
love.errorhandler = function(msg)
    print('SMOKE: CRASH ' .. tostring(msg))
    print(debug.traceback())
    if game_errorhandler then pcall(game_errorhandler, msg) end
    os.exit(70)
end

local game_update = love.update
love.update = function(dt, ...)
    game_update(dt, ...)
    if done then return end
    elapsed = elapsed + dt

    if elapsed - last_report >= 5 then
        last_report = elapsed
        local stage = (G and G.STAGE) or '?'
        local state = (G and G.STATE) or '?'
        print(string.format('SMOKE: t=%.0f stage=%s state=%s fps=%d',
            elapsed, tostring(stage), tostring(state), love.timer.getFPS()))
    end

    if not menu_at and G and G.STAGE == G.STAGES.MAIN_MENU and G.STATE == G.STATES.MENU then
        menu_at = elapsed
        print(string.format('SMOKE: MENU-REACHED t=%.1f', elapsed))
    end

    if menu_at and not shot_requested and elapsed - menu_at >= SETTLE_AFTER_MENU then
        shot_requested = true
        love.graphics.captureScreenshot(function(imgdata)
            local ok, err = pcall(function() imgdata:encode('png', 'smoke.png') end)
            if ok then
                shot_written = true
                print('SMOKE: SHOT-WRITTEN smoke.png')
            else
                print('SMOKE: SHOT-FAILED ' .. tostring(err))
            end
        end)
    end

    -- captureScreenshot callbacks fire at end-of-frame; give it a beat
    if shot_requested and (shot_written or elapsed - menu_at >= SETTLE_AFTER_MENU + 3) then
        done = true
        print(shot_written and 'SMOKE: PASS' or 'SMOKE: FAIL no-screenshot')
        love.event.quit(shot_written and 0 or 71)
    end

    if not menu_at and elapsed > BOOT_BUDGET then
        done = true
        print(string.format('SMOKE: FAIL menu-not-reached t=%.0f stage=%s state=%s',
            elapsed, tostring(G and G.STAGE), tostring(G and G.STATE)))
        love.event.quit(72)
    end
end
