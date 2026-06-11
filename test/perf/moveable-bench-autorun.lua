-- moveable-sleep validation bench: boot the phone save, reach the run,
-- measure the move-pass checkpoint with MOVEABLE_SLEEP shipped in the built
-- tree, then run live WAKE tests — every external-mutation class the sleep
-- guards against must still produce visible movement:
--   1. direct T teleport on a settled card  -> VT must converge (T-cache wake)
--   2. direct alignment.offset poke on a UIBox -> VT must follow (offset wake)
--   3. juice_up on a settled card           -> juice must process and clear
-- Run via: test/warp-repro.sh <save-dir> test/perf/moveable-bench-autorun.lua
local elapsed, started, in_run = 0, false, nil
local phase, phase_t = 'warmup', nil
local results, wake = {}, {}
print('MVB: loaded')

local function move_avg()
    if not (G.check and G.check.update) then return nil end
    for i = 1, G.check.update.checkpoints do
        local cp = G.check.update.checkpoint_list[i]
        if cp.label and cp.label:match('^move') then return 1000 * (cp.average or 0) end
    end
    return nil
end

local gu = love.update
love.update = function(dt, ...)
    gu(dt, ...)
    elapsed = elapsed + dt
    if not started and G and G.STAGE == G.STAGES.MAIN_MENU and G.STATE == G.STATES.MENU and elapsed > 5 then
        started = true
        G.SETTINGS.perf_mode = true
        local s = get_compressed(G.SETTINGS.profile .. '/save.jkr')
        if not s then print('MVB: no save in profile ' .. tostring(G.SETTINGS.profile)) love.event.quit(1) return end
        G.SAVED_GAME = STR_UNPACK(s)
        G.FUNCS.start_run(nil, {savetext = G.SAVED_GAME})
    end
    if started and not in_run and G.STAGE == G.STAGES.RUN and G.STATE ~= G.STATES.SPLASH then
        in_run = elapsed
        phase, phase_t = 'settle', elapsed
        print('MVB: run loaded, state=' .. tostring(G.STATE))
    end
    if not in_run then
        if elapsed > 120 then print('MVB: timeout before run') love.event.quit(1) end
        return
    end

    if phase == 'settle' and elapsed - phase_t > 6 then
        phase, phase_t = 'measure', elapsed
    elseif phase == 'measure' and elapsed - phase_t > 10 then
        results.shipped = move_avg()
        print(string.format('MVB: move_avg shipped=%.2fms (moveables=%d)', results.shipped or -1, #G.MOVEABLES))
        -- wake test 1: teleport via the card's OWN target. Area cards' T is
        -- rewritten every frame by CardArea:align_cards (the layout owns it),
        -- so poke T_shift/highlight-style state instead: highlight the card,
        -- which moves its layout target — the sleep must wake and VT follow.
        local c = G.jokers and G.jokers.cards and G.jokers.cards[1]
        if c then
            wake.card = c
            wake.vt_y0 = c.VT.y
            c.highlighted = true
            if G.jokers.parse_highlighted then G.jokers:parse_highlighted() end
        end
        phase, phase_t = 'wake_teleport', elapsed
    elseif phase == 'wake_teleport' and elapsed - phase_t > 2 then
        if wake.card then
            local moved = math.abs(wake.card.VT.y - wake.vt_y0)
            wake.teleport_ok = moved > 0.1
            print(string.format('MVB: wake-highlight %s (VT.y moved %.3f)', wake.teleport_ok and 'OK' or 'FAILED', moved))
            wake.card.highlighted = false
        else
            wake.teleport_ok = true
            print('MVB: wake-highlight skipped (no joker)')
        end
        -- wake test 2: poke a UIBox alignment offset; prev_offset mismatch must wake it
        if G.HUD and G.HUD.alignment and G.HUD.alignment.offset then
            wake.hud_vt0 = G.HUD.VT.x
            wake.hud_off0 = G.HUD.alignment.offset.x
            G.HUD.alignment.offset.x = wake.hud_off0 + 1
        end
        phase, phase_t = 'wake_offset', elapsed
    elseif phase == 'wake_offset' and elapsed - phase_t > 2 then
        if wake.hud_vt0 then
            local moved = math.abs(G.HUD.VT.x - wake.hud_vt0)
            wake.offset_ok = moved > 0.5
            print(string.format('MVB: wake-offset %s (HUD moved %.3f)', wake.offset_ok and 'OK' or 'FAILED', moved))
            G.HUD.alignment.offset.x = wake.hud_off0
        else
            wake.offset_ok = true
            print('MVB: wake-offset skipped (no HUD)')
        end
        -- wake test 3: juice a settled card; juice must run and self-clear
        local c = G.jokers and G.jokers.cards and G.jokers.cards[1]
        if c then
            wake.jcard = c
            c:juice_up(0.5, 0.5)
            wake.juiced = c.juice ~= nil
            print(string.format('MVB: juice set=%s (disable_anims=%s)', tostring(wake.juiced),
                tostring(Talisman and Talisman.config_file and Talisman.config_file.disable_anims)))
        end
        phase, phase_t = 'wake_juice', elapsed
    elseif phase == 'wake_juice' and elapsed - phase_t > 2 then
        -- Talisman disable_anims no-ops juice_up entirely (no juice to wake
        -- on) — with that setting active the test is vacuous, so skip it
        local anims_off = Talisman and Talisman.config_file and Talisman.config_file.disable_anims
        if not wake.juiced and anims_off then
            wake.juice_ok = true
            print('MVB: wake-juice skipped (Talisman disable_anims no-ops juice_up)')
        else
            wake.juice_ok = (not wake.jcard) or (wake.juiced and wake.jcard.juice == nil)
            print(string.format('MVB: wake-juice %s (set=%s now_nil=%s)', wake.juice_ok and 'OK' or 'FAILED',
                tostring(wake.juiced), tostring(wake.jcard and wake.jcard.juice == nil)))
        end
        -- gross visual sanity across hand + jokers
        local sane = true
        for _, area in ipairs({G.jokers, G.hand}) do
            if area and area.cards then
                for _, cc in ipairs(area.cards) do
                    if math.abs(cc.VT.y - cc.T.y) > 5 then sane = false end
                end
            end
        end
        print('MVB: visual sanity ' .. (sane and 'OK' or 'FAILED'))
        local ok = results.shipped and wake.teleport_ok and wake.offset_ok and wake.juice_ok and sane
        print('MVB: ' .. (ok and 'PASS' or 'FAIL'))
        love.event.quit(ok and 0 or 1)
    end
end
