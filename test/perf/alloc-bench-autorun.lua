-- ALLOC BENCH autorun: injected by test/perf/alloc-bench.sh into a disposable
-- copy of build/game with a real save pre-seeded. Boots, continues the run,
-- then measures allocation pressure for the Tier-1 perf targets:
--
--   BENCH: texmem_mb=...        texture memory after boot (mipmaps finding)
--   BENCH: selection_kb_cycle=  heap KB allocated per highlight-toggle cycle
--                               (parse_highlighted: option tables + joker scan)
--   BENCH: scoring_kb=          heap KB allocated across one played hand
--                               (card_eval_status_text et al; directional)
--
-- Each synchronous measurement runs inside a single love.update tick: nuGC
-- steps once at the top of Game:update and disables automatic GC otherwise,
-- so collectgarbage('count') deltas inside one tick are pure allocation.

local elapsed = 0
local run_started = false
local in_run_at = nil
local texmem_done = false
local selection_done = false
local scoring_state = 'idle'   -- idle -> playing -> measured
local scoring_kb_start = 0
local plays = 0
local done = false

local SELECTION_CYCLES = 300

print('BENCH: autorun loaded')
print('BENCH: savedir=' .. love.filesystem.getSaveDirectory())

local game_errorhandler = love.errorhandler
love.errorhandler = function(msg)
    print('BENCH: CRASH ' .. tostring(msg))
    if game_errorhandler then pcall(game_errorhandler, msg) end
    os.exit(70)
end

local function measure_selection()
    local hand = G.hand
    if not hand or #hand.cards < 5 then
        print('BENCH: selection skipped (hand too small)')
        return
    end
    collectgarbage('collect')
    collectgarbage('collect')
    local before = collectgarbage('count')
    for _ = 1, SELECTION_CYCLES do
        for i = 1, 5 do hand:add_to_highlighted(hand.cards[i], true) end
        hand:unhighlight_all()
        hand:parse_highlighted()
    end
    local after = collectgarbage('count')
    print(string.format('BENCH: selection_kb_cycle=%.3f total_kb=%.1f cycles=%d',
        (after - before) / SELECTION_CYCLES, after - before, SELECTION_CYCLES))
end

local game_update = love.update
love.update = function(dt, ...)
    game_update(dt, ...)
    elapsed = elapsed + dt

    if not run_started and G and G.STAGE == G.STAGES.MAIN_MENU and G.STATE == G.STATES.MENU and elapsed > 5 then
        run_started = true
        if not texmem_done then
            texmem_done = true
            local stats = love.graphics.getStats()
            print(string.format('BENCH: texmem_mb=%.1f images=%d', stats.texturememory / 1024 / 1024, stats.images or -1))
        end
        local saved = get_compressed(G.SETTINGS.profile .. '/save.jkr')
        if not saved then print('BENCH: FAIL no save') love.event.quit(1) return end
        G.SAVED_GAME = STR_UNPACK(saved)
        G.FUNCS.start_run(nil, { savetext = G.SAVED_GAME })
    end

    if run_started and not in_run_at and G.STAGE == G.STAGES.RUN and G.STATE ~= G.STATES.SPLASH then
        in_run_at = elapsed
        print('BENCH: RUN-LOADED state=' .. tostring(G.STATE))
    end

    if in_run_at and not selection_done and elapsed - in_run_at > 6 and G.STATE == G.STATES.SELECTING_HAND then
        selection_done = true
        local ok, err = pcall(measure_selection)
        if not ok then print('BENCH: selection ERROR ' .. tostring(err)) end
    end

    -- scoring measurement: play one hand, heap delta from play press until
    -- the state machine leaves the scoring pipeline
    if selection_done and scoring_state == 'idle' and G.STATE == G.STATES.SELECTING_HAND and #G.hand.highlighted == 0 then
        scoring_state = 'playing'
        for i = 1, math.min(5, #G.hand.cards) do G.hand:add_to_highlighted(G.hand.cards[i], true) end
        collectgarbage('collect')
        -- neuter the budgeted GC for the window so the delta is pure allocation
        _G.bench_nuGC = _G.nuGC
        _G.nuGC = function() end
        scoring_kb_start = collectgarbage('count')
        plays = plays + 1
        local ok, err = pcall(G.FUNCS.play_cards_from_highlighted)
        if not ok then print('BENCH: play ERROR ' .. tostring(err)); scoring_state = 'measured' end
    elseif scoring_state == 'playing' and G.STATE ~= G.STATES.HAND_PLAYED and elapsed - in_run_at > 14 then
        scoring_state = 'measured'
        print(string.format('BENCH: scoring_kb=%.1f (state now %s)', collectgarbage('count') - scoring_kb_start, tostring(G.STATE)))
        if _G.bench_nuGC then _G.nuGC = _G.bench_nuGC end
    end

    if not done and (scoring_state == 'measured' or (in_run_at and elapsed - in_run_at > 45)) then
        done = true
        print('BENCH: PASS')
        love.event.quit(0)
    end
    if elapsed > 120 and not in_run_at then
        print('BENCH: FAIL boot budget')
        love.event.quit(1)
    end
end
