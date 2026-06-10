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

-- draw-path timing: wall-time of love.draw and of Card:draw specifically,
-- sampled over a window of in-run frames (items 10/11 premeasurement — the
-- listed findings were stale against live SMODS; decide from data)
local draw_samples = {}
local card_draw_ms = 0
local card_draw_calls = 0
local draw_hooked = false
local function hook_draw()
    if draw_hooked then return end
    draw_hooked = true
    local orig_draw = love.draw
    if orig_draw then
        love.draw = function(...)
            local t0 = love.timer.getTime()
            orig_draw(...)
            draw_samples[#draw_samples + 1] = (love.timer.getTime() - t0) * 1000
        end
    end
    local orig_card_draw = Card.draw
    function Card:draw(layer)
        local t0 = love.timer.getTime()
        orig_card_draw(self, layer)
        card_draw_ms = card_draw_ms + (love.timer.getTime() - t0) * 1000
        card_draw_calls = card_draw_calls + 1
    end
end
local function report_draw()
    if #draw_samples < 50 then
        print('BENCH: drawtime skipped (' .. #draw_samples .. ' samples)')
        return
    end
    table.sort(draw_samples)
    local sum = 0
    for _, v in ipairs(draw_samples) do sum = sum + v end
    local n = #draw_samples
    print(string.format('BENCH: drawtime avg=%.3fms p95=%.3fms frames=%d', sum / n, draw_samples[math.floor(n * 0.95)], n))
    print(string.format('BENCH: card_draw total=%.1fms calls=%d avg_per_frame=%.3fms',
        card_draw_ms, card_draw_calls, card_draw_ms / n))
end

-- hover popup rebuild cost (Tier-3 item 12 premeasurement): full
-- hover->stop_hover cycles on a joker and a hand card, synchronous
local function measure_hover()
    local targets = {}
    if G.jokers and G.jokers.cards[1] then targets[#targets + 1] = { 'joker', G.jokers.cards[1] } end
    if G.hand and G.hand.cards[1] then targets[#targets + 1] = { 'hand', G.hand.cards[1] } end
    for _, t in ipairs(targets) do
        local label, card = t[1], t[2]
        local N = 30
        collectgarbage('collect')
        collectgarbage('stop')
        local kb0 = collectgarbage('count')
        local t0 = love.timer.getTime()
        for _ = 1, N do
            card:hover()
            card:stop_hover()
        end
        local ms = (love.timer.getTime() - t0) * 1000 / N
        local kb = (collectgarbage('count') - kb0) / N
        collectgarbage('restart')
        print(string.format('BENCH: hover_%s ms=%.2f kb=%.1f per open (n=%d)', label, ms, kb, N))
        -- phase breakdown: ability table vs popup definition vs UIBox instantiation
        local function phase(name, fn)
            local p0 = love.timer.getTime()
            for _ = 1, N do fn() end
            print(string.format('BENCH: hover_%s phase %s=%.2fms', label, name, (love.timer.getTime() - p0) * 1000 / N))
        end
        phase('ability_table', function() card.ability_UIBox_table = card:generate_UIBox_ability_table() end)
        phase('popup_def', function() card.config.h_popup = G.UIDEF.card_h_popup(card); card.config.h_popup_config = card:align_h_popup() end)
        -- NOTE: no raw Node.hover/stop_hover probe here. It errored mid-UIBox
        -- construction (ui.lua 'object' nil) and leaked a half-built popup into
        -- G.MOVEABLES, crashing the move loop at the next hand-play — the bench
        -- was sabotaging its own scoring phase. Instantiation cost = total
        -- hover ms minus the two phases above.
    end
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

-- diagnostic: identify any moveable reaching move() without a FRAME
do
    local _mv = Moveable and Moveable.move
    if _mv then
        function Moveable:move(dt)
            if not self.FRAME then
                local kind = (self.is and ((UIBox and self:is(UIBox) and 'UIBox') or (Card and self:is(Card) and 'Card') or (DynaText and self:is(DynaText) and 'DynaText') or 'Moveable')) or '?'
                local count = 0
                for _, m in ipairs(G.MOVEABLES) do if m == self then count = count + 1 end end
                print(string.format('BENCH: FRAMELESS kind=%s REMOVED=%s in_moveables=%d uiroot=%s def=%s keys=%s',
                    kind, tostring(self.REMOVED), count, tostring(self.UIRoot ~= nil),
                    tostring(self.definition ~= nil),
                    (function() local ks = {} for k in pairs(self) do ks[#ks+1] = tostring(k) end return table.concat(ks, ',') end)()))
                self.FRAME = { MOVE = 0, DRAW = 0 }
                return
            end
            return _mv(self, dt)
        end
    end
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
        hook_draw()
    end

    if in_run_at and not selection_done and elapsed - in_run_at > 6 and G.STATE == G.STATES.SELECTING_HAND then
        selection_done = true
        local ok, err = pcall(measure_selection)
        if not ok then print('BENCH: selection ERROR ' .. tostring(err)) end
        ok, err = pcall(measure_hover)
        if not ok then print('BENCH: hover ERROR ' .. tostring(err)) end
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
        report_draw()
        print('BENCH: PASS')
        love.event.quit(0)
    end
    if elapsed > 120 and not in_run_at then
        print('BENCH: FAIL boot budget')
        love.event.quit(1)
    end
end
