-- SCORE-ORACLE autorun: record the exact chip score produced by a specific
-- hand (with optional jokers) on a specific seed from the CURRENT build.
--
-- Environment variables (all optional):
--   ORACLE_SEED   (default: AAAAAAAA) — 8-char run seed; passed to start_run
--                 so G.GAME.seeded=true (no leaderboard writes, no tutorial)
--   ORACLE_HAND   (default: S_A,H_A,D_A,C_A,S_K) — comma-separated P_CARDS
--                 keys for the cards to force into hand slots 1..N and play.
--                 Keys use the game's own notation: S_A, H_K, D_T, C_2, etc.
--   ORACLE_JOKERS (default: empty) — comma-separated P_CENTERS keys for
--                 jokers to instantiate before playing the hand.
--                 e.g. j_joker,j_greedy_joker,j_mult
--                 Jokers are created with skip_materialize=true (no animation)
--                 and emplaced in order. The joker area auto-expands past the
--                 default 5-slot limit if needed (same as ingame-autorun.lua).
--
-- Protocol (all lines prefixed ORC: for easy grepping):
--   ORC: loaded seed=<S> hand=<H> jokers=<J>
--   ORC: reached menu
--   ORC: start_run ok
--   ORC: blind selected
--   ORC: joker added <key>           (one line per joker)
--   ORC: jokers installed <keys>
--   ORC: hand shaped <cards>
--   ORC: chips_before=<N>
--   ORC: played hand ok
--   ORC: settled state=<N> chips_after=<N> delta=<N>
--   ORC: score=<N>          ← THE ORACLE VALUE — grep this line
--   ORC: PASS seed=<S> hand=<H> jokers=<J> score=<N>
--   ORC: FAIL <reason>      → exit 1
--
-- Run via test/score-oracle.sh. ~60-90 s inside nix-shell.

local SEED       = os.getenv('ORACLE_SEED')   or 'AAAAAAAA'
local HAND_ENV   = os.getenv('ORACLE_HAND')   or 'S_A,H_A,D_A,C_A,S_K'
local JOKERS_ENV = os.getenv('ORACLE_JOKERS') or ''

-- parse comma-separated tokens into a list (trims whitespace, drops empty)
local function parse_list(s)
    local t = {}
    for k in s:gmatch('[^,]+') do
        k = k:match('^%s*(.-)%s*$')
        if k ~= '' then t[#t + 1] = k end
    end
    return t
end

local HAND_KEYS   = parse_list(HAND_ENV)
local JOKER_KEYS  = parse_list(JOKERS_ENV)

print('ORC: loaded seed=' .. SEED
    .. ' hand=' .. HAND_ENV
    .. ' jokers=' .. (JOKERS_ENV ~= '' and JOKERS_ENV or '(none)'))

local phase, elapsed, phase_t = 'boot', 0, 0
local chips_before = nil

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

-- Install the requested jokers into G.jokers.
-- Must be called after SELECTING_HAND is reached (G.jokers is live).
-- Mirrors the pattern in test/collapse/ingame-autorun.lua:21-26.
local function install_jokers()
    if #JOKER_KEYS == 0 then return true end

    -- Validate all keys first
    for _, key in ipairs(JOKER_KEYS) do
        if not G.P_CENTERS[key] then
            orc_fail('unknown P_CENTERS key for joker: ' .. tostring(key))
            return false
        end
        if G.P_CENTERS[key].set ~= 'Joker' then
            orc_fail('center ' .. key .. ' is not a Joker (set='
                .. tostring(G.P_CENTERS[key].set) .. ')')
            return false
        end
    end

    -- Suppress the edition lottery that create_card() runs for every joker
    -- (poll_edition advances G.GAME.pseudorandom state, so each successive
    -- call to create_card with the same key yields a different edition roll;
    -- for seed AAAAAAAA/ante-1 the second joker lands on Foil (+50 chips)
    -- which makes multi-joker scores seed-dependent in a surprising way).
    -- With bypass set, jokers are created plain — only their explicit
    -- ability fires, giving deterministic oracle results.
    SMODS.bypass_create_card_edition = true
    for _, key in ipairs(JOKER_KEYS) do
        -- create_card(type, area, legendary, rarity, skip_materialize,
        --             soulable, forced_key)
        -- skip_materialize=true suppresses the deal-in animation so the
        -- joker is available synchronously before the hand is played.
        local card = create_card('Joker', G.jokers, nil, nil, true, nil, key)
        card:add_to_deck()
        G.jokers:emplace(card)
        print('ORC: joker added ' .. key)
    end
    SMODS.bypass_create_card_edition = nil
    print('ORC: jokers installed ' .. table.concat(JOKER_KEYS, ','))
    return true
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
    -- (same guard used in vanilla-autorun.lua and gameset tests) — and pin a
    -- gameset: a fresh (no save-pull) profile has no cry_gameset, so Cryptid
    -- raises its gameset-select modal at start_run, which blocks blind_select
    -- creation forever (found 2026-07-02 when `just clean` wiped the
    -- unversioned build/save-pull fixture this harness silently leaned on).
    if G and G.PROFILES and G.SETTINGS and G.PROFILES[G.SETTINGS.profile] then
        local prof = G.PROFILES[G.SETTINGS.profile]
        if not prof.cry_intro_complete then prof.cry_intro_complete = true end
        if not prof.cry_gameset then prof.cry_gameset = 'mainline' end
    end

    -- Fresh profiles also queue vanilla unlock/notify overlays ("Discover at
    -- least 20 items from your collection…", a continue_unlock button) over
    -- the first run's blind select — click through them like a player would.
    if G and G.OVERLAY_MENU and G.FUNCS and G.FUNCS.continue_unlock then
        local btn
        pcall(function()
            local function walk(n, d)
                if btn or d > 8 then return end
                if n.config and n.config.button == 'continue_unlock' then btn = n; return end
                for _, c in ipairs(n.children or {}) do walk(c, d + 1) end
            end
            walk(G.OVERLAY_MENU.UIRoot or G.OVERLAY_MENU, 0)
        end)
        if btn then pcall(G.FUNCS.continue_unlock, btn) end
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
    -- Fresh runs open at BLIND_SELECT. select_blind requires a live UIBox on
    -- its event arg (button_callbacks.lua:2765 indexes e.UIBox). Use the real
    -- Select button from G.blind_select — same approach as ingame-autorun.lua.
    if phase == 'wait_blind' then
        if G.STATE == G.STATES.BLIND_SELECT and G.blind_select
            and elapsed - phase_t > 3 then
            local btn = G.blind_select.get_UIE_by_ID
                and G.blind_select:get_UIE_by_ID('select_blind_button')
            if btn then
                local sel_ok, sel_err = pcall(G.FUNCS.select_blind, btn)
                if not sel_ok then
                    orc_fail('select_blind failed: ' .. tostring(sel_err))
                    return
                end
                print('ORC: blind selected')
                phase, phase_t = 'wait_hand', elapsed
            elseif elapsed - phase_t > 40 then
                orc_fail('select_blind_button not found in G.blind_select'
                    .. ' state=' .. tostring(G.STATE))
            end
        elseif elapsed - phase_t > 60 then
            orc_fail('timeout waiting for BLIND_SELECT state='
                .. tostring(G.STATE))
        end
        return
    end

    -- ----------------------------------------------------------- wait_hand
    -- Wait until cards are dealt. Install jokers (if any), then overwrite
    -- the first N hand slots with the requested card identities and play.
    -- We need at least as many cards in hand as HAND_KEYS requests.
    if phase == 'wait_hand' then
        if G.STATE == G.STATES.SELECTING_HAND and G.hand and G.hand.cards
            and #G.hand.cards >= #HAND_KEYS and elapsed - phase_t > 2 then

            -- Install jokers before validating / shaping hand cards so that
            -- any joker-dependent on_hand_played hooks fire during scoring.
            if not install_jokers() then return end

            -- Validate all requested card keys before touching anything
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
    -- State leaves SELECTING_HAND while scoring animates. Wait for that.
    if phase == 'wait_scored' then
        if G.STATE ~= G.STATES.SELECTING_HAND then
            phase, phase_t = 'wait_return', elapsed
        elseif elapsed - phase_t > 60 then
            orc_fail('timeout: state never left SELECTING_HAND after play')
        end
        return
    end

    if phase == 'wait_return' then
        -- Accept any post-HAND_PLAYED state. G.GAME.chips is updated by a
        -- non-blocking ease (delay=0.5 s, state_events.lua:1025) queued
        -- during HAND_PLAYED, so chips are at their final value by the time
        -- any of these states are reached. Give 4 s real-time regardless.
        --   SELECTING_HAND (1) : didn't beat the blind; more hands this round
        --   NEW_ROUND     (19) : beat blind; end_of_round event chain running
        --   ROUND_EVAL    ( 8) : end_of_round done, cashout screen
        --   SHOP          ( 5) : advanced past cashout
        --   GAME_OVER     ( 4) : lost (hands_left == 0 without beating blind)
        local settled = G.STATE == G.STATES.SELECTING_HAND
            or G.STATE == G.STATES.NEW_ROUND
            or G.STATE == G.STATES.ROUND_EVAL
            or G.STATE == G.STATES.SHOP
            or G.STATE == G.STATES.GAME_OVER
        if settled and elapsed - phase_t > 4 then
            local chips_after = G.GAME.chips
            local delta = chips_after - chips_before
            print('ORC: settled state=' .. tostring(G.STATE)
                .. ' chips_after=' .. tostring(chips_after)
                .. ' delta=' .. tostring(delta))
            print('ORC: score=' .. tostring(delta))
            print(string.format('ORC: PASS seed=%s hand=%s jokers=%s score=%s',
                SEED, HAND_ENV,
                (JOKERS_ENV ~= '' and JOKERS_ENV or '(none)'),
                tostring(delta)))
            love.event.quit(0)
            phase = 'done'
        elseif elapsed - phase_t > 120 then
            orc_fail('timeout waiting for settled state after scoring'
                .. ' (state=' .. tostring(G.STATE) .. ')')
        end
        return
    end

    -- Global timeout
    if elapsed > 300 and phase ~= 'done' then
        orc_fail('global timeout phase=' .. phase)
    end
end
