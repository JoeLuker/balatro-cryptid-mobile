-- Android Telemetry for Balatro Cryptid Mobile
-- Logs game events to logcat via print() (LÖVE routes to SDL/APP tag)
-- All entries prefixed with [TEL] for easy filtering: adb logcat -s SDL/APP | grep TEL

if love.system.getOS() ~= 'Android' then return end

local TEL = {}
TEL.session_id = string.format("%x", os.time())
TEL.last_state = nil
TEL.last_stage = nil
TEL.run_start_time = nil
TEL.exp_recompute_count = 0
TEL.exp_recompute_window_start = 0
local EXP_REPORT_INTERVAL = 10  -- emit EXP_RECOMPUTE summary every 10 s

local function tel(event, data)
    local parts = {"[TEL]", event}
    if data then
        for k, v in pairs(data) do
            table.insert(parts, k .. "=" .. tostring(v))
        end
    end
    print(table.concat(parts, " "))
end

-- Session start
tel("SESSION_START", {id = TEL.session_id, device = love.system.getOS()})

-- State name lookup
local STATE_NAMES = {}
local function get_state_name(state_num)
    if not next(STATE_NAMES) and G and G.STATES then
        for name, num in pairs(G.STATES) do
            STATE_NAMES[num] = name
        end
    end
    return STATE_NAMES[state_num] or tostring(state_num)
end

local STAGE_NAMES = {[1] = "MAIN_MENU", [2] = "RUN", [3] = "SANDBOX"}

-- Hook Game:update to track state transitions and exp_recompute rate
local _original_game_update = Game.update
function Game:update(dt)
    local exp_dt_before = self._exp_dt
    local result = _original_game_update(self, dt)

    -- Count exp_times recomputes (threshold fired when _exp_dt changed)
    if self._exp_dt ~= exp_dt_before then
        TEL.exp_recompute_count = TEL.exp_recompute_count + 1
    end

    -- Emit summary every EXP_REPORT_INTERVAL seconds of real time
    local now = self.TIMERS and self.TIMERS.UPTIME or 0
    if now - TEL.exp_recompute_window_start >= EXP_REPORT_INTERVAL then
        local elapsed = now - TEL.exp_recompute_window_start
        -- recompute_rate is recomputes/s; at 60fps with no caching it would
        -- equal fps. A rate << fps confirms the cache is effective.
        tel("EXP_RECOMPUTE", {
            count = TEL.exp_recompute_count,
            window_s = math.floor(elapsed),
            rate = string.format("%.1f", TEL.exp_recompute_count / math.max(elapsed, 0.001))
        })
        TEL.exp_recompute_count = 0
        TEL.exp_recompute_window_start = now
    end

    -- Log state changes
    if G.STATE ~= TEL.last_state or G.STAGE ~= TEL.last_stage then
        tel("STATE", {
            from = get_state_name(TEL.last_state),
            to = get_state_name(G.STATE),
            stage = STAGE_NAMES[G.STAGE] or tostring(G.STAGE)
        })
        TEL.last_state = G.STATE
        TEL.last_stage = G.STAGE
    end

    return result
end

-- Hook Game:start_run to log run starts
local _original_start_run = Game.start_run
function Game:start_run(args)
    TEL.run_start_time = os.time()
    local seed = args and args.seed or (G.GAME and G.GAME.pseudorandom and G.GAME.pseudorandom.seed)
    tel("RUN_START", {
        seed = seed or "unknown",
        challenge = args and args.challenge and "true" or "false"
    })
    return _original_start_run(self, args)
end

-- Hook buy_from_shop
local _original_buy = G.FUNCS.buy_from_shop
G.FUNCS.buy_from_shop = function(e)
    local card = e and e.config and e.config.ref_table
    if card then
        tel("BUY", {
            card = card.config and card.config.center and card.config.center.key or "unknown",
            cost = card.cost or 0,
            area = card.area and card.area.config and card.area.config.type or "unknown"
        })
    end
    return _original_buy(e)
end

-- Hook sell_card
local _original_sell = G.FUNCS.sell_card
G.FUNCS.sell_card = function(e)
    local card = e and e.config and e.config.ref_table
    if card then
        tel("SELL", {
            card = card.config and card.config.center and card.config.center.key or "unknown",
            value = card.sell_cost or 0
        })
    end
    return _original_sell(e)
end

-- Hook use_card
local _original_use = G.FUNCS.use_card
G.FUNCS.use_card = function(e, mute, nosave)
    local card = e and e.config and e.config.ref_table
    if card then
        tel("USE", {
            card = card.config and card.config.center and card.config.center.key or "unknown"
        })
    end
    return _original_use(e, mute, nosave)
end

-- Hook play_cards_from_highlighted
local _original_play = G.FUNCS.play_cards_from_highlighted
G.FUNCS.play_cards_from_highlighted = function(e)
    local count = G.hand and G.hand.highlighted and #G.hand.highlighted or 0
    tel("PLAY_HAND", {cards = count})
    return _original_play(e)
end

-- Hook discard_cards_from_highlighted
local _original_discard = G.FUNCS.discard_cards_from_highlighted
G.FUNCS.discard_cards_from_highlighted = function(e, hook)
    local count = G.hand and G.hand.highlighted and #G.hand.highlighted or 0
    tel("DISCARD", {cards = count})
    return _original_discard(e, hook)
end

-- Hook error handler to log crashes with context
local _original_errorhandler = love.errorhandler
love.errorhandler = function(msg)
    tel("CRASH", {
        state = get_state_name(G and G.STATE),
        stage = STAGE_NAMES[G and G.STAGE] or "unknown",
        ante = G and G.GAME and G.GAME.round_resets and G.GAME.round_resets.ante or 0,
        round = G and G.GAME and G.GAME.round or 0,
        error = tostring(msg):sub(1, 200):gsub("\n", " | ")
    })
    if _original_errorhandler then
        return _original_errorhandler(msg)
    end
end

-- Hook save_run to log saves
local _original_save = save_run
if _original_save then
    save_run = function()
        tel("SAVE", {
            ante = G and G.GAME and G.GAME.round_resets and G.GAME.round_resets.ante or 0,
            round = G and G.GAME and G.GAME.round or 0,
            dollars = G and G.GAME and G.GAME.dollars or 0
        })
        return _original_save()
    end
end

-- Log game over
local _original_game_over = Game.update_game_over
if _original_game_over then
    function Game:update_game_over(dt)
        if not TEL.game_over_logged then
            TEL.game_over_logged = true
            local duration = TEL.run_start_time and (os.time() - TEL.run_start_time) or 0
            tel("GAME_OVER", {
                ante = G.GAME and G.GAME.round_resets and G.GAME.round_resets.ante or 0,
                round = G.GAME and G.GAME.round or 0,
                dollars = G.GAME and G.GAME.dollars or 0,
                duration_s = duration,
                won = G.GAME and G.GAME.won and "true" or "false"
            })
        end
        return _original_game_over(self, dt)
    end
end

tel("HOOKS_LOADED", {count = 9})
