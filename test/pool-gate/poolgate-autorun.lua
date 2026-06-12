-- CRY_DISABLED_POOL_GATE + FORCED_KEY_GUARD regression: on the modest
-- gameset profile, c_cry_gateway is gameset-disabled (scrubbed from
-- G.P_CENTERS) yet still listed in SMODS.Consumable.legendaries — the
-- inconsistency behind the 2026-06-12 field crash (common_events.lua:2446,
-- Spectral pack soul-roll forced a missing center). Assert:
--   1. the inconsistency exists (test exercises the real condition)
--   2. add_to_pool refuses disabled prototypes (root gate)
--   3. create_card with a forced missing key survives via pool fallback
--   4. a 300-card soulable spectral storm survives the legendaries roll
local elapsed, phase, phase_t = 0, 'boot', 0
local fails = {}
print('PGT: loaded')

local function chk(cond, label)
    if cond then print('PGT: ok ' .. label)
    else fails[#fails + 1] = label print('PGT: BADCHK ' .. label) end
end

local gu = love.update
love.update = function(dt, ...)
    local ok, err = pcall(gu, dt, ...)
    if not ok then
        print('PGT: UPDATE-ERROR ' .. tostring(err))
        love.event.quit(1)
        phase = 'done'
        return
    end
    elapsed = elapsed + dt

    if phase == 'boot' and G and G.STAGE == G.STAGES.MAIN_MENU
        and G.STATE == G.STATES.MENU and elapsed > 6 then
        local ok2 = pcall(G.FUNCS.start_run, nil, { stake = 1 })
        chk(ok2, 'start_run')
        phase, phase_t = 'wait_run', elapsed
    elseif phase == 'wait_run' then
        if G.STATE == G.STATES.BLIND_SELECT and elapsed - phase_t > 3 then
            -- 1. field condition: center scrubbed, legendaries still lists it
            chk(G.P_CENTERS.c_cry_gateway == nil, 'gateway-scrubbed-from-P_CENTERS')
            local proto
            for _, v in ipairs(SMODS.Consumable.legendaries or {}) do
                if v.key == 'c_cry_gateway' then proto = v break end
            end
            chk(proto ~= nil, 'gateway-still-in-legendaries')
            chk(proto and proto.cry_disabled ~= nil, 'gateway-marked-disabled')

            -- 2. root gate: disabled prototypes are non-spawnable
            if proto then
                chk(SMODS.add_to_pool(proto) == false, 'add_to_pool-refuses-disabled')
            end

            -- 3. deterministic field-crash repro: forced missing key
            local ok3, card = pcall(create_card, 'Spectral', G.consumeables, nil, nil,
                true, true, 'c_cry_gateway', 'pgt')
            chk(ok3, 'forced-missing-key-survives (' .. tostring(card) .. ')')
            chk(ok3 and type(card) == 'table' and card.config and card.config.center
                and card.config.center.key ~= 'c_cry_gateway',
                'fell-through-to-pool (got ' .. tostring(ok3 and card.config
                    and card.config.center and card.config.center.key) .. ')')
            if ok3 and type(card) == 'table' and card.remove then pcall(function() card:remove() end) end

            -- 4. soulable storm through the legendaries roll
            local storm_ok = true
            for i = 1, 300 do
                local o, c = pcall(create_card, 'Spectral', G.consumeables, nil, nil,
                    true, true, nil, 'pgt' .. i)
                if not o then storm_ok = false print('PGT: storm-crash i=' .. i .. ' ' .. tostring(c)) break end
                if type(c) == 'table' and c.remove then pcall(function() c:remove() end) end
            end
            chk(storm_ok, 'soulable-storm-300')
            phase = 'verdict'
        elseif elapsed - phase_t > 40 then
            chk(false, 'timeout-wait-run state=' .. tostring(G.STATE))
            phase = 'verdict'
        end
    end

    if phase == 'verdict' then
        if #fails == 0 then print('PGT: PASS') love.event.quit(0)
        else print('PGT: FAIL (' .. #fails .. '): ' .. table.concat(fails, ', ')) love.event.quit(1) end
        phase = 'done'
    end
    if elapsed > 90 and phase ~= 'done' then
        print('PGT: FAIL timeout phase=' .. phase)
        love.event.quit(1)
        phase = 'done'
    end
end
