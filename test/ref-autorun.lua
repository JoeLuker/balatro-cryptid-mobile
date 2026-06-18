-- REFERENCE HARNESS autorun: boots build/game, starts a run, selects the Small
-- Blind, and screenshots the SELECTING_HAND play screen — the real Balatro+Cryptid
-- HUD/board to compare the Kotlin rebuild against. Injected like smoke-autorun by
-- test/ref.sh. Prints REF: markers; screenshot lands at <savedir>/ref.png.

local elapsed, last_report = 0, 0
local phase = 'boot'           -- boot -> started -> selecting -> shot -> done
local marks = {}
local shot_written = false
local BOOT_BUDGET = 120

print('REF: autorun loaded')
print('REF: savedir=' .. love.filesystem.getSaveDirectory())

local game_errorhandler = love.errorhandler
love.errorhandler = function(msg)
    print('REF: CRASH ' .. tostring(msg)); print(debug.traceback())
    if game_errorhandler then pcall(game_errorhandler, msg) end
    os.exit(70)
end

local function at(p) return marks[p] end
local function mark(p) if not marks[p] then marks[p] = elapsed; print(string.format('REF: %s t=%.1f', p, elapsed)) end end

local game_update = love.update
love.update = function(dt, ...)
    game_update(dt, ...)
    if phase == 'done' then return end
    elapsed = elapsed + dt
    if elapsed - last_report >= 4 then
        last_report = elapsed
        print(string.format('REF: t=%.0f stage=%s state=%s phase=%s fps=%d',
            elapsed, tostring(G and G.STAGE), tostring(G and G.STATE), phase, love.timer.getFPS()))
    end

    -- 1) at the menu -> start a run on a fixed seed
    if phase == 'boot' and G and G.STAGE == G.STAGES.MAIN_MENU and G.STATE == G.STATES.MENU then
        mark('MENU')
        pcall(function() if G.SETTINGS then G.SETTINGS.tutorial_complete = true end end)  -- skip the first-run tutorial overlay
        local ok, err = pcall(function() G:start_run({ stake = 1, seed = 'REFSHOT1' }) end)
        print('REF: start_run ok=' .. tostring(ok) .. (ok and '' or (' err=' .. tostring(err))))
        phase = 'started'
    end

    -- 2) at blind select -> fire select_blind on the Small Blind (leftmost choice)
    if phase == 'started' and G and G.STATE == G.STATES.BLIND_SELECT and G.blind_select then
        mark('BLIND_SELECT')
        if elapsed - at('BLIND_SELECT') >= 1.5 then
            local ok, err = pcall(function()
                local btn = G.blind_select.UIRoot.children[1].children[1].config.object:get_UIE_by_ID('select_blind_button')
                G.FUNCS.select_blind(btn)
            end)
            print('REF: select_blind ok=' .. tostring(ok) .. (ok and '' or (' err=' .. tostring(err))))
            phase = 'selecting'
        end
    end

    -- 3) at SELECTING_HAND with cards dealt -> settle, then screenshot
    if phase == 'selecting' and G and G.STATE == G.STATES.SELECTING_HAND and G.hand and #G.hand.cards > 0 then
        mark('SELECTING_HAND')
        if elapsed - at('SELECTING_HAND') >= 3.0 then
            love.graphics.captureScreenshot(function(img)
                local ok = pcall(function() img:encode('png', 'ref.png') end)
                shot_written = ok
                print(ok and 'REF: SHOT-WRITTEN ref.png' or 'REF: SHOT-FAILED')
            end)
            phase = 'shot'
            marks['shot'] = elapsed
        end
    end

    if phase == 'shot' and (shot_written or elapsed - marks['shot'] >= 3) then
        phase = 'done'
        print(shot_written and 'REF: PASS' or 'REF: FAIL no-screenshot')
        love.event.quit(shot_written and 0 or 71)
    end

    if phase ~= 'done' and elapsed > BOOT_BUDGET then
        phase = 'done'
        print(string.format('REF: FAIL timeout t=%.0f stage=%s state=%s phase=%s',
            elapsed, tostring(G and G.STAGE), tostring(G and G.STATE), phase))
        love.event.quit(72)
    end
end
