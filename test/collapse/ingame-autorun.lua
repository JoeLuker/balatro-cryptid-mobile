-- Trigger-collapse in-game differential: boot the pulled profile, start a
-- fresh run, shape a deterministic retrigger cascade (red-seal King scored
-- under six Sock and Buskin = 8 identical reps, no RNG anywhere in the
-- cascade), then score one hand with collapse OFF and one with collapse ON.
-- The per-hand chip delta must be identical, the ON hand must actually
-- collapse (RLE stats), and mismatches must be zero.
-- Run via test/collapse/run.sh (warp-repro staging).
local elapsed, phase, phase_t = 0, 'boot', 0
local fails, results = {}, {}
print('TCD: loaded')

local function chk(cond, label)
    if cond then print('TCD: ok ' .. label)
    else fails[#fails + 1] = label print('TCD: BADCHK ' .. label) end
end

local function tc() return TRIGGER_COLLAPSE end

local function shape_hand_and_jokers()
    -- six Sock and Buskin (retrigger face cards), deterministic
    if #G.jokers.cards == 0 then
        for _ = 1, 6 do
            local card = create_card('Joker', G.jokers, nil, nil, true, nil, 'j_sock_and_buskin')
            card:add_to_deck()
            G.jokers:emplace(card)
        end
    end
    -- first hand card becomes a red-seal King of Hearts (retriggered by
    -- seal + all six jokers when scored)
    local c = G.hand.cards[1]
    c:set_base(G.P_CARDS['H_K'])
    c:set_seal('Red', true, true)
    return c
end

local function play_one(card)
    G.hand:add_to_highlighted(card)
    local ok, err = pcall(G.FUNCS.play_cards_from_highlighted)
    return ok, err
end

local function chips_total()
    -- round score accumulator (Big-safe tostring for reporting)
    return G.GAME.chips
end

local gu = love.update
love.update = function(dt, ...)
    gu(dt, ...)
    elapsed = elapsed + dt

    if phase == 'boot' and G and G.STAGE == G.STAGES.MAIN_MENU
        and G.STATE == G.STATES.MENU and elapsed > 5 then
        chk(tc() ~= nil, 'module-loaded')
        tc().debug = true
        local ok = pcall(G.FUNCS.start_run, nil, { stake = 1 })
        chk(ok, 'start_run')
        phase, phase_t = 'wait_blind', elapsed
    elseif phase == 'wait_blind' then
        -- click the REAL Select button: a synthetic e crashes select_blind
        -- later inside queued events (needs e.UIBox and friends)
        if G.STATE == G.STATES.BLIND_SELECT and G.blind_select
            and elapsed - phase_t > 3 then
            chk(tc() and tc()._installed == true, 'hooks-installed')
            local btn = G.blind_select.get_UIE_by_ID
                and G.blind_select:get_UIE_by_ID('select_blind_button')
            if btn then
                local ok, err = pcall(G.FUNCS.select_blind, btn)
                chk(ok, 'select_blind-real-button (' .. tostring(err) .. ')')
                phase, phase_t = 'hand_off', elapsed
            elseif elapsed - phase_t > 30 then
                chk(false, 'select-button-not-found')
                phase = 'verdict'
            end
        elseif elapsed - phase_t > 40 then
            chk(false, 'timeout-blind-select state=' .. tostring(G.STATE))
            phase = 'verdict'
        end
    elseif phase == 'hand_off' then
        if G.STATE == G.STATES.SELECTING_HAND and G.hand and #G.hand.cards > 4
            and elapsed - phase_t > 2 then
            G.SETTINGS.trigger_collapse = false
            tc().stats_total.runs, tc().stats_total.collapsed_reps, tc().stats_total.mismatches = 0, 0, 0
            local card = shape_hand_and_jokers()
            results.before_off = chips_total()
            local ok, err = play_one(card)
            chk(ok, 'play-off (' .. tostring(err) .. ')')
            phase, phase_t = 'settle_off', elapsed
        elseif elapsed - phase_t > 60 then
            chk(false, 'timeout-selecting-hand-off state=' .. tostring(G.STATE))
            phase = 'verdict'
        end
    elseif phase == 'settle_off' then
        if G.STATE == G.STATES.SELECTING_HAND and elapsed - phase_t > 8 then
            results.delta_off = chips_total() - results.before_off
            chk(tc().stats_total.runs == 0 and tc().stats_total.collapsed_reps == 0,
                'off-hand-ran-honest (runs=' .. tc().stats_total.runs .. ')')
            phase, phase_t = 'hand_on', elapsed
        elseif elapsed - phase_t > 90 then
            chk(false, 'timeout-settle-off state=' .. tostring(G.STATE))
            phase = 'verdict'
        end
    elseif phase == 'hand_on' then
        if G.hand and #G.hand.cards > 0 then
            G.SETTINGS.trigger_collapse = true
            local card = shape_hand_and_jokers()
            results.before_on = chips_total()
            local ok, err = play_one(card)
            chk(ok, 'play-on (' .. tostring(err) .. ')')
            phase, phase_t = 'settle_on', elapsed
        elseif elapsed - phase_t > 30 then
            chk(false, 'timeout-hand-on')
            phase = 'verdict'
        end
    elseif phase == 'settle_on' then
        if G.STATE == G.STATES.SELECTING_HAND and elapsed - phase_t > 8 then
            results.delta_on = chips_total() - results.before_on
            local s = tc().stats_total
            chk(s.collapsed_reps > 0, 'on-hand-collapsed (collapsed=' .. s.collapsed_reps
                .. ' runs=' .. s.runs .. ')')
            chk(s.mismatches == 0, 'zero-mismatches (got ' .. s.mismatches .. ')')
            local d_off, d_on = results.delta_off, results.delta_on
            local equal = pcall(function() assert(d_off == d_on) end) and d_off == d_on
            chk(equal, 'identical-chip-delta (off=' .. tostring(d_off) .. ' on=' .. tostring(d_on) .. ')')
            phase = 'verdict'
        elseif elapsed - phase_t > 90 then
            chk(false, 'timeout-settle-on state=' .. tostring(G.STATE))
            phase = 'verdict'
        end
    end

    if phase == 'verdict' then
        if #fails == 0 then
            print('TCD: PASS')
            love.event.quit(0)
        else
            print('TCD: FAIL (' .. #fails .. '): ' .. table.concat(fails, ', '))
            love.event.quit(1)
        end
        phase = 'done'
    end
    if elapsed > 240 and phase ~= 'done' then
        print('TCD: FAIL timeout phase=' .. phase)
        love.event.quit(1)
        phase = 'done'
    end
end
