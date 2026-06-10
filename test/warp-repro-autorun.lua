-- WARP REPRO autorun v2: injected by test/warp-repro.sh into a disposable copy
-- of build/game, with the phone's save pre-seeded. Boots to the menu, loads the
-- saved run (same path as the Continue button), instruments every mid-run
-- writer of card transforms (set_ability's original_T restore, emplace,
-- set_debuff), then plays hands to drive the blind/scoring/shop transitions
-- where the card-inflation corruption appears on device. Any card whose T.w/T.h
-- leaves the sane range is reported with the traceback of the write that did it.
-- Markers: WARP: ... / WARP: PASS / WARP: CRASH.

local elapsed = 0
local last_report = 0
local run_started = false
local in_run_at = nil
local hooks_installed = false
local plays_done = 0
local last_play_at = 0
local done = false

local BOOT_BUDGET = 120
local PLAY_BUDGET = 90      -- seconds of driven gameplay after run load
local MAX_PLAYS = 6

print('WARP: autorun loaded')
print('WARP: savedir=' .. love.filesystem.getSaveDirectory())

local game_errorhandler = love.errorhandler
love.errorhandler = function(msg)
    print('WARP: CRASH ' .. tostring(msg))
    print(debug.traceback())
    if game_errorhandler then pcall(game_errorhandler, msg) end
    os.exit(70)
end

local function fmt(n)
    if type(n) == 'number' then return string.format('%.3f', n) end
    return tostring(n)
end

local function card_key(c)
    return (c.config and c.config.center and c.config.center.key)
        or (c.base and c.base.value and tostring(c.base.value) .. tostring(c.base.suit or ''))
        or '?'
end

local function tstr(T)
    return string.format('{x=%s y=%s w=%s h=%s r=%s s=%s}',
        fmt(T.x), fmt(T.y), fmt(T.w), fmt(T.h), fmt(T.r), fmt(T.scale))
end

-- one-line tracebacks: keep only file:line frames, drop the noise
local function short_trace()
    local t = debug.traceback('', 3)
    local frames = {}
    for line in t:gmatch('[^\n]+') do
        local frame = line:match('%s*(.-%.lua:%d+)')
        if frame then frames[#frames + 1] = frame end
        if #frames >= 6 then break end
    end
    return table.concat(frames, ' <- ')
end

local function install_hooks()
    -- set_ability: the original_T restore resets T.w/h/x/y/r/scale to BIRTH
    -- values — harmless only if it is never re-called on cards born oversized.
    local orig_set_ability = Card.set_ability
    function Card:set_ability(center, initial, delay_sprites)
        local before_w, before_h = self.T.w, self.T.h
        local r = orig_set_ability(self, center, initial, delay_sprites)
        if math.abs(self.T.w - before_w) > 0.01 or math.abs(self.T.h - before_h) > 0.01 then
            print(string.format('WARP: SET_ABILITY-RESIZE %s initial=%s w %s->%s h %s->%s orig_T=%s\nWARP: trace %s',
                card_key(self), tostring(initial), fmt(before_w), fmt(self.T.w),
                fmt(before_h), fmt(self.T.h), tstr(self.original_T), short_trace()))
        end
        return r
    end

    local orig_emplace = CardArea.emplace
    function CardArea:emplace(card, location, stay_flipped)
        local before_w = card.T.w
        local r = orig_emplace(self, card, location, stay_flipped)
        if math.abs(card.T.w - before_w) > 0.01 then
            print(string.format('WARP: EMPLACE-RESIZE %s area=%s w %s->%s\nWARP: trace %s',
                card_key(card), tostring(self.config and self.config.type), fmt(before_w), fmt(card.T.w), short_trace()))
        end
        return r
    end

    if Card.set_debuff then
        local orig_set_debuff = Card.set_debuff
        function Card:set_debuff(should_debuff)
            local before_w = self.T.w
            local r = orig_set_debuff(self, should_debuff)
            if math.abs(self.T.w - before_w) > 0.01 then
                print(string.format('WARP: SET_DEBUFF-RESIZE %s w %s->%s\nWARP: trace %s',
                    card_key(self), fmt(before_w), fmt(self.T.w), short_trace()))
            end
            return r
        end
    end
    print('WARP: hooks installed')
end

-- per-frame watchdog: report each card once when its target size leaves the
-- sane range (standard card is ~2.05 x 2.75 tiles at scale 0.95)
local flagged = {}
local function watchdog()
    for _, area in ipairs({ G.jokers, G.hand, G.consumeables, G.play }) do
        if area and area.cards then
            for _, c in ipairs(area.cards) do
                if not flagged[c] and (c.T.w > 2.3 or c.T.w < 1.5 or c.T.h > 3.1) then
                    flagged[c] = true
                    print(string.format('WARP: OVERSIZED %s area=%s T=%s VT=%s orig_T=%s hover=%s drag=%s',
                        card_key(c), tostring(area.config and area.config.type),
                        tstr(c.T), tstr(c.VT), tstr(c.original_T),
                        tostring(c.states.hover.is), tostring(c.states.drag.is)))
                end
            end
        end
    end
end

local function try_play_hand()
    -- drive gameplay: highlight up to 5 hand cards and play them
    if G.STATE ~= G.STATES.SELECTING_HAND then return end
    if #G.hand.highlighted > 0 then return end
    if elapsed - last_play_at < 6 then return end
    last_play_at = elapsed
    plays_done = plays_done + 1
    for i = 1, math.min(5, #G.hand.cards) do
        G.hand:add_to_highlighted(G.hand.cards[i], true)
    end
    print(string.format('WARP: PLAY %d (highlighted %d cards, state=%s)', plays_done, #G.hand.highlighted, tostring(G.STATE)))
    G.FUNCS.play_cards_from_highlighted()
end

local game_update = love.update
love.update = function(dt, ...)
    game_update(dt, ...)
    elapsed = elapsed + dt

    if elapsed - last_report >= 10 then
        last_report = elapsed
        print(string.format('WARP: t=%.0f stage=%s state=%s fps=%d plays=%d',
            elapsed, tostring(G and G.STAGE), tostring(G and G.STATE), love.timer.getFPS(), plays_done))
    end

    if not run_started and G and G.STAGE == G.STAGES.MAIN_MENU and G.STATE == G.STATES.MENU and elapsed > 5 then
        run_started = true
        local saved = get_compressed(G.SETTINGS.profile .. '/save.jkr')
        if not saved then
            print('WARP: FAIL no save.jkr for profile ' .. tostring(G.SETTINGS.profile))
            love.event.quit(1)
            return
        end
        print('WARP: continuing saved run (profile ' .. tostring(G.SETTINGS.profile) .. ')')
        G.SAVED_GAME = STR_UNPACK(saved)
        G.FUNCS.start_run(nil, { savetext = G.SAVED_GAME })
    end

    if run_started and not in_run_at and G.STAGE == G.STAGES.RUN and G.STATE ~= G.STATES.SPLASH then
        in_run_at = elapsed
        print('WARP: RUN-LOADED state=' .. tostring(G.STATE))
    end

    if in_run_at then
        if not hooks_installed then
            hooks_installed = true
            install_hooks()
        end
        watchdog()
        if not done and plays_done < MAX_PLAYS then
            local ok, err = pcall(try_play_hand)
            if not ok then print('WARP: PLAY-ERROR ' .. tostring(err)) end
        end
        -- leave the shop for the next round if we land there
        if not done and G.STATE == G.STATES.SHOP and elapsed - last_play_at > 8 then
            last_play_at = elapsed
            print('WARP: SHOP — pressing next round')
            local ok, err = pcall(function() G.FUNCS.toggle_shop({}) end)
            if not ok then print('WARP: SHOP-ERROR ' .. tostring(err)) end
        end
    end

    if in_run_at and not done and (elapsed - in_run_at >= PLAY_BUDGET or plays_done >= MAX_PLAYS) then
        done = true
        love.graphics.captureScreenshot(function(imgdata)
            imgdata:encode('png', 'warp.png')
            print('WARP: SHOT-WRITTEN')
        end)
    end

    if done and elapsed - in_run_at >= PLAY_BUDGET + 4 then
        print('WARP: PASS')
        love.event.quit(0)
    end

    if elapsed > BOOT_BUDGET and not in_run_at then
        print('WARP: FAIL run not loaded within budget')
        love.event.quit(1)
    end
end
