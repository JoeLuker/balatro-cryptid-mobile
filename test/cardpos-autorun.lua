-- CARD-POSITION ORACLE autorun: boots build/game, starts a fixed-seed run, selects the Small Blind,
-- and at SELECTING_HAND dumps the REAL engine transforms of the hand area + each hand card
-- (G.hand.T and every G.hand.cards[i].T / .VT). Ground truth for the rebuild's CardArea align_cards
-- port: compare the dumped card.T against the literal formula, and the dumped VT against the rendered
-- position, to settle the hand draw/position discrepancy. Prints CPS: markers; no screenshot needed.
-- Injected like score-oracle-autorun by test/cardpos.sh.

local elapsed, last_report = 0, 0
local phase = 'boot'
local marks = {}
local BOOT_BUDGET = 120

print('CPS: autorun loaded')

local game_errorhandler = love.errorhandler
love.errorhandler = function(msg)
    print('CPS: CRASH ' .. tostring(msg)); print(debug.traceback())
    if game_errorhandler then pcall(game_errorhandler, msg) end
    os.exit(70)
end

local function at(p) return marks[p] end
local function mark(p) if not marks[p] then marks[p] = elapsed; print(string.format('CPS: %s t=%.1f', p, elapsed)) end end

local function dump_positions()
    local function fmt(t) return string.format('x=%.5f y=%.5f w=%.5f h=%.5f r=%.5f', t.x, t.y, t.w, t.h, t.r or 0) end
    print(string.format('CPS: GLOBALS TILE_W=%.4f TILE_H=%.4f CARD_W=%.5f CARD_H=%.5f', G.TILE_W, G.TILE_H, G.CARD_W, G.CARD_H))
    print('CPS: ROOM.T ' .. fmt(G.ROOM.T))
    print(string.format('CPS: STATE=%s', tostring(G.STATE)))
    for _, name in ipairs({ 'hand', 'jokers', 'play' }) do
        local area = G[name]
        if area then
            print(string.format('CPS: AREA %s ncards=%d temp_limit=%s card_limit=%s T{%s}',
                name, #area.cards, tostring(area.config.temp_limit), tostring(area.config.card_limit), fmt(area.T)))
            for i, c in ipairs(area.cards) do
                print(string.format('CPS:   %s[%d] T{%s} VT{x=%.5f y=%.5f r=%.5f scale=%.5f} hl=%s',
                    name, i, fmt(c.T), c.VT.x, c.VT.y, c.VT.r, c.VT.scale, tostring(c.highlighted)))
            end
        end
    end
end

local game_update = love.update
love.update = function(dt, ...)
    game_update(dt, ...)
    if phase == 'done' then return end
    elapsed = elapsed + dt
    if elapsed - last_report >= 4 then
        last_report = elapsed
        print(string.format('CPS: t=%.0f stage=%s state=%s phase=%s', elapsed, tostring(G and G.STAGE), tostring(G and G.STATE), phase))
    end

    if phase == 'boot' and G and G.STAGE == G.STAGES.MAIN_MENU and G.STATE == G.STATES.MENU then
        mark('MENU')
        pcall(function() if G.SETTINGS then G.SETTINGS.tutorial_complete = true end end)
        local ok, err = pcall(function() G:start_run({ stake = 1, seed = 'REFSHOT1' }) end)
        print('CPS: start_run ok=' .. tostring(ok) .. (ok and '' or (' err=' .. tostring(err))))
        phase = 'started'
    end

    if phase == 'started' and G and G.STATE == G.STATES.BLIND_SELECT and G.blind_select then
        mark('BLIND_SELECT')
        if elapsed - at('BLIND_SELECT') >= 1.5 then
            local ok, err = pcall(function()
                local btn = G.blind_select.UIRoot.children[1].children[1].config.object:get_UIE_by_ID('select_blind_button')
                G.FUNCS.select_blind(btn)
            end)
            print('CPS: select_blind ok=' .. tostring(ok) .. (ok and '' or (' err=' .. tostring(err))))
            phase = 'selecting'
        end
    end

    -- at SELECTING_HAND with cards dealt -> let it settle, then dump the real transforms
    if phase == 'selecting' and G and G.STATE == G.STATES.SELECTING_HAND and G.hand and #G.hand.cards > 0 then
        mark('SELECTING_HAND')
        if elapsed - at('SELECTING_HAND') >= 3.0 then
            print('CPS: ==== DUMP (settled SELECTING_HAND) ====')
            pcall(dump_positions)
            print('CPS: PASS')
            phase = 'done'
            love.event.quit(0)
        end
    end

    if phase ~= 'done' and elapsed > BOOT_BUDGET then
        phase = 'done'
        print(string.format('CPS: FAIL timeout t=%.0f stage=%s state=%s', elapsed, tostring(G and G.STAGE), tostring(G and G.STATE)))
        love.event.quit(72)
    end
end
