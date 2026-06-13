-- SCORE-ORACLE autorun: record the exact chip score produced by a specific
-- hand on a specific seed from the CURRENT build.
--
-- Environment variables (all optional):
--   ORACLE_SEED  (default: AAAAAAAA) — 8-char run seed; passed to start_run
--                so G.GAME.seeded=true (no leaderboard writes, no tutorial)
--   ORACLE_HAND  (default: S_A,H_A,D_A,C_A,S_K) — comma-separated P_CARDS
--                keys for the cards to force into hand slots 1..N and play.
--                Keys use the game's own notation: S_A, H_K, D_T, C_2, etc.
--
-- Protocol (all lines prefixed ORC: for easy grepping):
--   ORC: loaded seed=<S> hand=<H>
--   ORC: reached menu
--   ORC: start_run ok
--   ORC: blind selected
--   ORC: hand shaped <cards>
--   ORC: chips_before=<N>
--   ORC: played hand ok
--   ORC: settled chips_after=<N> delta=<N>
--   ORC: score=<N>          ← THE ORACLE VALUE — grep this line
--   ORC: PASS seed=<S> hand=<H> score=<N>
--   ORC: FAIL <reason>      → exit 1
--
-- Run via test/score-oracle.sh. ~60-90 s inside nix-shell.

local SEED = os.getenv('ORACLE_SEED') or 'AAAAAAAA'
local HAND_ENV = os.getenv('ORACLE_HAND') or 'S_A,H_A,D_A,C_A,S_K'

-- parse comma-separated card keys into a list
local function parse_hand(s)
    local t = {}
    for k in s:gmatch('[^,]+') do
        k = k:match('^%s*(.-)%s*$') -- trim whitespace
        if k ~= '' then t[#t + 1] = k end
    end
    return t
end

local HAND_KEYS = parse_hand(HAND_ENV)

print('ORC: loaded seed=' .. SEED .. ' hand=' .. HAND_ENV)

local phase, elapsed, phase_t = 'boot', 0, 0
local chips_before = nil
local fails = {}

local function orc_fail(msg)
    print('ORC: FAIL ' .. tostring(msg))
    love.event.quit(1)
    phase = 'done'
end

-- Error handler: chain to game's handler so the STP traceback still lands in
-- the log, but make the failure greppable and ensure nonzero exit.
local game_errorhandler = love.errorhandler
love.errorhandler = function(msg)
    print('ORC: FAIL crash: ' .. tostring(msg))
    print(debug.traceback())
    if game_errorhandler then pcall(game_errorhandler, msg) end
    os.exit(70)
end

local original_update = love.update
love.update = function(dt, ...)
    local ok, err = pcall(original_update, dt, ...)
    if not ok then
        orc_fail('update-error: ' .. tostring(err))
        return
    end
    elapsed = elapsed + dt

    -- ------------------------------------------------------------------ boot
    -- Suppress the Jimbo gameset intro as soon as the profile table is live
    -- (same guard used in vanilla-autorun.lua and gameset tests).
    if G and G.PROFILES and G.SETTINGS and G.PROFILES[G.SETTINGS.profile]
        and not G.PROFILES[G.SETTINGS.profile].cry_intro_complete then
        G.PROFILES[G.SETTINGS.profile].cry_intro_complete = true
    end

    if phase == 'boot' then
        if G and G.STAGE == G.STAGES.MAIN_MENU
            and G.STATE == G.STATES.MENU and elapsed > 5 then
            print('ORC: reached menu')
            -- Suppress the tutorial pause that wedges the event queue on a
            -- fresh (no save-pull) XDG profile.
            G.SETTINGS.tutorial_complete = true

            local start_ok, start_err = pcall(G.FUNCS.start_run, nil,
                { stake = 1, seed = SEED })
            if not start_ok then
                orc_fail('start_run failed: ' .. tostring(start_err))
                return
            end
            print('ORC: start_run ok')
            phase, phase_t = 'wait_blind', elapsed
        elseif elapsed > 120 then
            orc_fail('timeout waiting for main menu')
        end
        return
    end

    -- ---------------------------------------------------------- wait_blind
    -- Fresh runs open at BLIND_SELECT. Select the on-deck blind directly
    -- (same synthetic-event pattern as vanilla-autorun.lua lines 88-91).
    if phase == 'wait_blind' then
        if G.STATE == G.STATES.BLIND_SELECT and G.GAME
            and G.GAME.blind_on_deck and elapsed - phase_t > 2 then
            local key = G.GAME.round_resets.blind_choices[G.GAME.blind_on_deck]
            local sel_ok, sel_err = pcall(G.FUNCS.select_blind,
                { config = { ref_table = G.P_BLINDS[key] } })
            if not sel_ok then
                orc_fail('select_blind failed: ' .. tostring(sel_err))
                return
            end
            print('ORC: blind selected key=' .. tostring(key))
            phase, phase_t = 'wait_hand', elapsed
        elseif elapsed - phase_t > 60 then
            orc_fail('timeout waiting for BLIND_SELECT state='
                .. tostring(G.STATE))
        end
        return
    end

    -- ----------------------------------------------------------- wait_hand
    -- Wait until cards are dealt. Then overwrite the first N hand slots with
    -- the requested card identities and play them. We need at least as many
    -- cards in hand as HAND_KEYS requests.
    if phase == 'wait_hand' then
        if G.STATE == G.STATES.SELECTING_HAND and G.hand and G.hand.cards
            and #G.hand.cards >= #HAND_KEYS and elapsed - phase_t > 2 then

            -- Validate all requested keys before touching anything
            for i, key in ipairs(HAND_KEYS) do
                if not G.P_CARDS[key] then
                    orc_fail('unknown P_CARDS key: ' .. tostring(key)
                        .. ' (hand slot ' .. i .. ')')
                    return
                end
            end

            -- Shape the hand: overwrite card bases in-place
            local shaped = {}
            for i, key in ipairs(HAND_KEYS) do
                local c = G.hand.cards[i]
                c:set_base(G.P_CARDS[key])
                shaped[#shaped + 1] = key
            end
            print('ORC: hand shaped ' .. table.concat(shaped, ','))

            -- Capture chip total before play
            chips_before = G.GAME.chips
            print('ORC: chips_before=' .. tostring(chips_before))

            -- Highlight the shaped cards and play
            for i = 1, #HAND_KEYS do
                G.hand:add_to_highlighted(G.hand.cards[i])
            end
            local play_ok, play_err = pcall(G.FUNCS.play_cards_from_highlighted)
            if not play_ok then
                orc_fail('play_cards_from_highlighted failed: '
                    .. tostring(play_err))
                return
            end
            print('ORC: played hand ok')
            phase, phase_t = 'wait_scored', elapsed

        elseif elapsed - phase_t > 90 then
            orc_fail('timeout waiting for SELECTING_HAND (#hand='
                .. tostring(G.hand and #G.hand.cards or '?')
                .. ' need=' .. #HAND_KEYS
                .. ' state=' .. tostring(G.STATE) .. ')')
        end
        return
    end

    -- ---------------------------------------------------------- wait_scored
    -- State leaves SELECTING_HAND while scoring animates, then returns to
    -- SELECTING_HAND when the draw is complete and the next hand is ready.
    -- We wait for that return, with a generous settle buffer so the ease
    -- animation on G.GAME.chips has finished before we read it.
    if phase == 'wait_scored' then
        if G.STATE ~= G.STATES.SELECTING_HAND then
            -- Scoring kicked off — wait for it to settle back
            phase, phase_t = 'wait_return', elapsed
        elseif elapsed - phase_t > 60 then
            orc_fail('timeout: state never left SELECTING_HAND after play')
        end
        return
    end

    if phase == 'wait_return' then
        -- Back to SELECTING_HAND + a small settle so the ease on G.GAME.chips
        -- has finished (the ease event has delay=0.5 and is blocking=false, so
        -- we give it an extra couple of seconds of real time).
        if G.STATE == G.STATES.SELECTING_HAND and elapsed - phase_t > 4 then
            local chips_after = G.GAME.chips
            local delta = chips_after - chips_before
            print('ORC: settled chips_after=' .. tostring(chips_after)
                .. ' delta=' .. tostring(delta))
            print('ORC: score=' .. tostring(delta))
            print(string.format('ORC: PASS seed=%s hand=%s score=%s',
                SEED, HAND_ENV, tostring(delta)))
            love.event.quit(0)
            phase = 'done'
        elseif elapsed - phase_t > 120 then
            orc_fail('timeout waiting for state to return to SELECTING_HAND'
                .. ' (state=' .. tostring(G.STATE) .. ')')
        end
        return
    end

    -- Global timeout
    if elapsed > 300 and phase ~= 'done' then
        orc_fail('global timeout phase=' .. phase)
    end
end
