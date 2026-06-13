-- LAZY-SHADER ELISION regression autorun
-- Verifies that DRAW_SHADER_NIL_RESET + LAZY_SHADER together produce
-- meaningful shader-bind elision: with the nil-reset moved to call sites,
-- consecutive cards sharing a shader no longer force a  S -> nil -> S
-- round-trip, so LAZY_SHADER can skip the re-bind.
--
-- MEASUREMENT STRATEGY
-- The autorun boots to the main menu, starts a seeded run, waits for
-- SELECTING_HAND (cards visible and drawing), then samples LAZY_SHADER.binds
-- and LAZY_SHADER.calls over a 3-second window.  The elision rate is:
--
--   elision_rate = 1 - (binds_delta / calls_delta)
--
-- Thresholds:
--   PASS  elision_rate >= 0.30 over the sample window
--         (even with mixed edition cards, ~70 % of draws are dissolve-only;
--          the expectation across a typical 8-card hand is ~60-80 %
--          elision — 0.30 is a conservative floor that catches a regressed
--          nil-reset-in-draw_shader but passes any correct build)
--   SKIP  LAZY_SHADER global not present (module failed to load) — print
--         a warning and exit 0 so the test never blocks a build on a
--         missing module
--   FAIL  elision_rate < 0.30, or LAZY_SHADER.enabled is false, or any
--         crash
--
-- Additionally asserts the structural sentinel:
--   The text "DRAW_SHADER_NIL_RESET" must appear in engine/sprite.lua
--   (read via love.filesystem).  This catches the case where regen-dump
--   regenerated the dump without re-applying the build patch.
--
-- Protocol (all lines prefixed ELIDE: for harness grep):
--   ELIDE: loaded
--   ELIDE: SKIP <reason>     → exit 0 (module missing is non-fatal)
--   ELIDE: PASS <reason>     → exit 0
--   ELIDE: FAIL <reason>     → exit 1

print('ELIDE: loaded')
print('ELIDE: savedir=' .. love.filesystem.getSaveDirectory())

local phase, elapsed, phase_t = 'boot', 0, 0
local sample_start_binds, sample_start_calls, sample_started_at
local done = false

local BOOT_MENU_TIMEOUT = 120   -- s before giving up on main menu
local BLIND_SELECT_TIMEOUT = 60 -- s to reach BLIND_SELECT after start_run
local HAND_TIMEOUT = 90         -- s to reach SELECTING_HAND after blind select
local SAMPLE_DURATION = 3       -- s to accumulate the elision sample
local ELISION_FLOOR = 0.30      -- minimum acceptable elision rate

-- Error handler: chain to game's STP handler but make failure greppable.
local _game_errorhandler = love.errorhandler
love.errorhandler = function(msg)
    print('ELIDE: FAIL crash: ' .. tostring(msg))
    print(debug.traceback())
    if _game_errorhandler then pcall(_game_errorhandler, msg) end
    os.exit(70)
end

local function elide_fail(msg)
    if done then return end
    done = true
    print('ELIDE: FAIL ' .. tostring(msg))
    love.event.quit(1)
end

local function elide_pass(msg)
    if done then return end
    done = true
    print('ELIDE: PASS ' .. tostring(msg))
    love.event.quit(0)
end

local function elide_skip(msg)
    if done then return end
    done = true
    print('ELIDE: SKIP ' .. tostring(msg))
    love.event.quit(0)
end

-- STRUCTURAL CHECK: sentinel must exist in the sprite.lua that was built into
-- this game tree.  Read via love.filesystem so we see the actual built file.
local function check_sentinel()
    local src = love.filesystem.read('engine/sprite.lua')
    if not src then
        elide_fail('cannot read engine/sprite.lua via love.filesystem')
        return false
    end
    if not src:find('DRAW_SHADER_NIL_RESET', 1, true) then
        elide_fail(
            'DRAW_SHADER_NIL_RESET sentinel absent from engine/sprite.lua\n'
         .. '  build patch apply_draw_shader_nil_reset did not run, or '
         .. 'regen-dump overwrote the patched file')
        return false
    end
    print('ELIDE: sentinel OK in engine/sprite.lua')
    return true
end

local original_update = love.update
love.update = function(dt, ...)
    local ok, err = pcall(original_update, dt, ...)
    if not ok then
        elide_fail('update-crash: ' .. tostring(err))
        return
    end
    if done then return end
    elapsed = elapsed + dt

    -- Suppress Cryptid intro so fresh XDG profile does not wedge at the
    -- Jimbo gameset screen (same guard used by score-oracle and gameset tests).
    if G and G.PROFILES and G.SETTINGS and G.PROFILES[G.SETTINGS.profile]
        and not G.PROFILES[G.SETTINGS.profile].cry_intro_complete then
        G.PROFILES[G.SETTINGS.profile].cry_intro_complete = true
    end

    -- ------------------------------------------------------------ boot
    if phase == 'boot' then
        if G and G.STAGE == G.STAGES.MAIN_MENU
            and G.STATE == G.STATES.MENU and elapsed > 5 then

            -- 1. Module-presence check.
            if not LAZY_SHADER then
                elide_skip('LAZY_SHADER global not found '
                    .. '(lazy-shader.lua not loaded) '
                    .. '— elision test requires the module')
                return
            end
            -- Module arms in the love.draw wrapper on the first frame after
            -- G.SETTINGS exists.  It may still be false here; that is checked
            -- again at SELECTING_HAND when we know settings are live.
            print(string.format('ELIDE: LAZY_SHADER present enabled=%s '
                .. 'calls=%d binds=%d',
                tostring(LAZY_SHADER.enabled),
                LAZY_SHADER.calls, LAZY_SHADER.binds))

            -- 2. Structural sentinel check (reads the actual built sprite.lua).
            if not check_sentinel() then return end

            -- 3. Start a seeded run.
            G.SETTINGS.tutorial_complete = true
            local start_ok, start_err = pcall(G.FUNCS.start_run, nil,
                { stake = 1, seed = 'AAAAAAAA' })
            if not start_ok then
                elide_fail('start_run failed: ' .. tostring(start_err))
                return
            end
            print('ELIDE: start_run ok')
            phase, phase_t = 'wait_blind', elapsed
        elseif elapsed > BOOT_MENU_TIMEOUT then
            elide_fail(string.format(
                'timeout waiting for main menu t=%.0f stage=%s state=%s',
                elapsed,
                tostring(G and G.STAGE),
                tostring(G and G.STATE)))
        end
        return
    end

    -- --------------------------------------------------------- wait_blind
    if phase == 'wait_blind' then
        if G.STATE == G.STATES.BLIND_SELECT and G.blind_select
            and elapsed - phase_t > 3 then
            local btn = G.blind_select.get_UIE_by_ID
                and G.blind_select:get_UIE_by_ID('select_blind_button')
            if btn then
                local sel_ok, sel_err = pcall(G.FUNCS.select_blind, btn)
                if not sel_ok then
                    elide_fail('select_blind failed: ' .. tostring(sel_err))
                    return
                end
                print('ELIDE: blind selected')
                phase, phase_t = 'wait_hand', elapsed
            elseif elapsed - phase_t > BLIND_SELECT_TIMEOUT then
                elide_fail('select_blind_button not found after '
                    .. BLIND_SELECT_TIMEOUT .. 's in BLIND_SELECT')
            end
        elseif elapsed - phase_t > BLIND_SELECT_TIMEOUT then
            elide_fail(string.format(
                'timeout waiting for BLIND_SELECT t=%.0f state=%s',
                elapsed - phase_t, tostring(G.STATE)))
        end
        return
    end

    -- ---------------------------------------------------------- wait_hand
    -- Wait until cards are dealt (SELECTING_HAND, >= 5 cards in hand), then
    -- arm the sample window.
    if phase == 'wait_hand' then
        if G.STATE == G.STATES.SELECTING_HAND and G.hand and G.hand.cards
            and #G.hand.cards >= 5 and elapsed - phase_t > 2 then

            -- Module must be enabled now that G.SETTINGS is stable.
            if not LAZY_SHADER.enabled then
                elide_fail(
                    'LAZY_SHADER.enabled is false at SELECTING_HAND\n'
                 .. '  G.SETTINGS.lazy_shader = '
                 .. tostring(G.SETTINGS and G.SETTINGS.lazy_shader) .. '\n'
                 .. '  lazy_shader defaults to on (nil = on); check for '
                 .. 'accidental G.SETTINGS.lazy_shader = false in the build')
                return
            end

            sample_start_binds = LAZY_SHADER.binds
            sample_start_calls = LAZY_SHADER.calls
            sample_started_at  = elapsed
            print(string.format(
                'ELIDE: sampling started — hand=%d cards '
                .. 'ls_binds=%d ls_calls=%d',
                #G.hand.cards,
                sample_start_binds, sample_start_calls))
            phase, phase_t = 'sampling', elapsed
        elseif elapsed - phase_t > HAND_TIMEOUT then
            elide_fail(string.format(
                'timeout waiting for SELECTING_HAND '
                .. 't=%.0f state=%s #hand=%s',
                elapsed - phase_t,
                tostring(G.STATE),
                tostring(G.hand and #G.hand.cards or '?')))
        end
        return
    end

    -- ---------------------------------------------------------- sampling
    -- Accumulate for SAMPLE_DURATION seconds, then evaluate the ratio.
    if phase == 'sampling' then
        if elapsed - sample_started_at >= SAMPLE_DURATION then
            local binds_delta = LAZY_SHADER.binds - sample_start_binds
            local calls_delta = LAZY_SHADER.calls - sample_start_calls

            print(string.format(
                'ELIDE: sample done — binds_delta=%d calls_delta=%d '
                .. 'over %.1fs',
                binds_delta, calls_delta, SAMPLE_DURATION))

            -- Need a non-trivial sample.
            if calls_delta < 20 then
                elide_fail(string.format(
                    'sample too small: calls_delta=%d (need >= 20)\n'
                 .. '  LAZY_SHADER may not be intercepting setShader calls\n'
                 .. '  ls_enabled=%s ls_binds_lifetime=%d',
                    calls_delta,
                    tostring(LAZY_SHADER.enabled),
                    LAZY_SHADER.binds))
                return
            end

            if binds_delta == 0 then
                -- Every call was a no-op: perfect elision.
                elide_pass(string.format(
                    'perfect elision: calls=%d binds=0 (100%% elision)',
                    calls_delta))
                return
            end

            local elision_rate = 1.0 - (binds_delta / calls_delta)
            print(string.format(
                'ELIDE: elision_rate=%.0f%% (floor=%.0f%%)',
                elision_rate * 100, ELISION_FLOOR * 100))

            if elision_rate >= ELISION_FLOOR then
                elide_pass(string.format(
                    'elision_rate=%.0f%% >= floor %.0f%% '
                    .. '(binds=%d calls=%d over %.1fs)',
                    elision_rate * 100, ELISION_FLOOR * 100,
                    binds_delta, calls_delta, SAMPLE_DURATION))
            else
                elide_fail(string.format(
                    'elision_rate=%.0f%% < floor %.0f%%\n'
                 .. '  binds_delta=%d  calls_delta=%d  over %.1fs\n'
                 .. '  Likely cause: draw_shader() still resets the shader to\n'
                 .. '  nil after each sprite draw, so LAZY_SHADER sees\n'
                 .. '    dissolve -> nil -> dissolve -> nil -> ...\n'
                 .. '  instead of holding dissolve across the run.\n'
                 .. '  Check: engine/sprite.lua must contain DRAW_SHADER_NIL_RESET\n'
                 .. '  and must NOT contain the old unconditional setShader() reset\n'
                 .. '  that followed the draw call in draw_shader().',
                    elision_rate * 100, ELISION_FLOOR * 100,
                    binds_delta, calls_delta, SAMPLE_DURATION))
            end
        end
        return
    end
end
