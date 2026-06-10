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
    -- three batches in one boot: intra-run agreement validates the number;
    -- seeded RNG kills cross-run variance from random-driven selection hooks
    for batch = 1, 3 do
        math.randomseed(12345 + batch)
        collectgarbage('collect')
        collectgarbage('collect')
        local before = collectgarbage('count')
        for _ = 1, SELECTION_CYCLES do
            for i = 1, 5 do hand:add_to_highlighted(hand.cards[i], true) end
            hand:unhighlight_all()
            hand:parse_highlighted()
        end
        local after = collectgarbage('count')
        print(string.format('BENCH: selection_kb_cycle=%.3f batch=%d cycles=%d',
            (after - before) / SELECTION_CYCLES, batch, SELECTION_CYCLES))
    end
end

-- attribution profile: wrap every SMODS PokerHandPart func and per-hand
-- evaluate with allocation counters, then run selection cycles and rank where
-- the KB/cycle actually goes (the SMODS registry replaced vanilla
-- evaluate_poker_hand, so the PERF-FINDINGS line numbers are dead code)
local function profile_hand_eval()
    local hand = G.hand
    if not hand or #hand.cards < 5 then return end
    local costs = {}
    local function wrap(tbl, key, fn, label)
        costs[label] = 0
        tbl[key] = function(...)
            local b = collectgarbage('count')
            local a, b2, c, d = fn(...)
            costs[label] = costs[label] + (collectgarbage('count') - b)
            return a, b2, c, d
        end
        return fn
    end
    local saved = {}
    for _, v in ipairs(SMODS.PokerHandPart.obj_buffer) do
        local part = SMODS.PokerHandParts[v]
        saved[#saved + 1] = { part, 'func', part.func }
        wrap(part, 'func', part.func, 'part:' .. v)
    end
    for k, h in pairs(SMODS.PokerHands) do
        saved[#saved + 1] = { h, 'evaluate', h.evaluate }
        wrap(h, 'evaluate', h.evaluate, 'hand:' .. k)
    end
    math.randomseed(777)
    collectgarbage('collect')
    local cycles = 100
    local before = collectgarbage('count')
    for _ = 1, cycles do
        for i = 1, 5 do hand:add_to_highlighted(hand.cards[i], true) end
        hand:unhighlight_all()
        hand:parse_highlighted()
    end
    local total = collectgarbage('count') - before
    for _, s in ipairs(saved) do s[1][s[2]] = s[3] end
    local ranked = {}
    for label, kb in pairs(costs) do ranked[#ranked + 1] = { label, kb } end
    table.sort(ranked, function(a, b) return a[2] > b[2] end)
    print(string.format('BENCH: profile total_kb_cycle=%.2f over %d cycles', total / cycles, cycles))
    local shown = 0
    for _, r in ipairs(ranked) do
        if r[2] > 0.5 and shown < 15 then
            shown = shown + 1
            print(string.format('BENCH: profile %-28s %8.2f kb_total %6.3f kb_cycle', r[1], r[2], r[2] / cycles))
        end
    end
end

-- gross-allocation accumulator: sums positive heap deltas frame-over-frame so
-- collections (nuGC steps, explicit collects in the scoring path) subtract
-- nothing — robust where a single end-minus-start delta goes negative
local alloc_sum = 0
local last_count = nil
local function alloc_tick()
    local c = collectgarbage('count')
    if last_count and c > last_count then alloc_sum = alloc_sum + (c - last_count) end
    last_count = c
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
        ok, err = pcall(profile_hand_eval)
        if not ok then print('BENCH: profile ERROR ' .. tostring(err)) end
    end

    -- scoring measurement: play one hand, heap delta from play press until
    -- the state machine leaves the scoring pipeline
    if selection_done and scoring_state == 'idle' and G.STATE == G.STATES.SELECTING_HAND and #G.hand.highlighted == 0 then
        scoring_state = 'playing'
        math.randomseed(99999)
        for i = 1, math.min(5, #G.hand.cards) do G.hand:add_to_highlighted(G.hand.cards[i], true) end
        collectgarbage('collect')
        alloc_sum = 0
        last_count = collectgarbage('count')
        plays = plays + 1
        local ok, err = pcall(G.FUNCS.play_cards_from_highlighted)
        if not ok then print('BENCH: play ERROR ' .. tostring(err)); scoring_state = 'measured' end
    elseif scoring_state == 'playing' then
        alloc_tick()
        if G.STATE ~= G.STATES.HAND_PLAYED and elapsed - in_run_at > 14 then
            scoring_state = 'measured'
            print(string.format('BENCH: scoring_alloc_kb=%.1f (gross, state now %s)', alloc_sum, tostring(G.STATE)))
        end
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
