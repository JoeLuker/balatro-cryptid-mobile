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
        -- conf.lua sets t.window.width/height = 0 → LÖVE uses the desktop size, so the 3840x2160 Xvfb
        -- screen already gives a 3840x2160 window (the pixel gate needs that exact size). No mid-run
        -- setMode — resizing after boot glitches the menu logo over the board.
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
        pcall(function() if G.hand.unhighlight_all then G.hand:unhighlight_all() end end)  -- clean resting row (no popped card)
        if elapsed - at('SELECTING_HAND') >= 3.0 then
            -- start_run from MENU (vs the Play button) may leave leftover main-menu elements:
            -- G.SPLASH_LOGO (BALATRO logo overlay), G.MAIN_MENU_UI, and G.PROFILE_BUTTON.
            -- Remove them so the frame matches a normal mid-run state (no menu chrome).
            -- NOTE: do NOT remove G.SPLASH_BACK — that is the animated green felt background
            -- shader and removing it leaves the board area black, corrupting the reference.
            pcall(function()
                if G.SPLASH_FRONT then G.SPLASH_FRONT:remove(); G.SPLASH_FRONT = nil end
                if G.SPLASH_LOGO then G.SPLASH_LOGO:remove(); G.SPLASH_LOGO = nil end
                if G.MAIN_MENU_UI then G.MAIN_MENU_UI:remove(); G.MAIN_MENU_UI = nil end
                if G.PROFILE_BUTTON then G.PROFILE_BUTTON:remove(); G.PROFILE_BUTTON = nil end
            end)
            -- dump Balatro's room→screen transform so the capture window can be sized to WIDTH-FILL
            -- (match the rebuild's repro framing) instead of fit-to-contain letterboxing.
            pcall(function()
                print(string.format('REF: ROOM x=%.4f y=%.4f w=%.4f h=%.4f TILESIZE=%s TILE_W=%s TILE_H=%s scr=%dx%d',
                    G.ROOM.T.x, G.ROOM.T.y, G.ROOM.T.w, G.ROOM.T.h, tostring(G.TILESIZE), tostring(G.TILE_W), tostring(G.TILE_H),
                    love.graphics.getWidth(), love.graphics.getHeight()))
            end)
            -- dump the resting hand + HUD facts so the rebuild's loadRepro can mirror this exact frame
            pcall(function()
                print(string.format('REF: HUD dollars=%s ante=%s chips_needed=%s hands=%s discards=%s',
                    tostring(G.GAME.dollars), tostring(G.GAME.round_resets.ante),
                    tostring(G.GAME.blind and G.GAME.blind.chips), tostring(G.GAME.current_round.hands_left),
                    tostring(G.GAME.current_round.discards_left)))
                for i, c in ipairs(G.hand.cards) do
                    print(string.format('REF: hand[%d] id=%s suit=%s value=%s', i, tostring(c.base.id), tostring(c.base.suit), tostring(c.base.value)))
                end
            end)
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
