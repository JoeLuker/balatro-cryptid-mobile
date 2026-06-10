-- Android Telemetry for Balatro Cryptid Mobile
-- Every event goes to TWO sinks:
--   1. logcat via print() (LÖVE routes to SDL/APP) — live tailing
--   2. telemetry.log in the save dir — persistent, pullable any time with
--      `just perf-pull` (adb run-as). Survives logcat's ring buffer; no
--      observer needs to be attached when something interesting happens.
-- File lines are "epoch session event k=v ..." — grep/awk friendly.
-- Buffered: lines accumulate in memory and flush every FLUSH_INTERVAL (or
-- immediately on crash / app-background, so dying words are never lost).
-- Rotated at boot: >1 MB moves to telemetry.log.1 (one generation kept).

if love.system.getOS() ~= 'Android' then return end

local TEL = {}
TEL.session_id = string.format("%x", os.time())
TEL.last_state = nil
TEL.last_stage = nil
TEL.run_start_time = nil
TEL.exp_recompute_count = 0
TEL.exp_recompute_window_start = 0
local EXP_REPORT_INTERVAL = 10  -- emit EXP_RECOMPUTE summary every 10 s
local PERF_REPORT_INTERVAL = 5  -- emit PERF_SNAPSHOT every 5 s
TEL.perf_window_start = 0
-- always-on frame stats for the file snapshot (no perf_mode needed)
TEL.frame_count = 0
TEL.frame_dt_sum = 0
TEL.frame_dt_max = 0

local LOG_FILE = "telemetry.log"
local LOG_CAP_BYTES = 1024 * 1024
local FLUSH_INTERVAL = 5
local buf = {}
local last_flush = 0

-- boot-time rotation (love.filesystem has no rename; one read/write at boot)
do
    local info = love.filesystem.getInfo(LOG_FILE)
    if info and info.size and info.size > LOG_CAP_BYTES then
        local old = love.filesystem.read(LOG_FILE)
        if old then
            love.filesystem.write(LOG_FILE .. ".1", old)
            love.filesystem.remove(LOG_FILE)
        end
    end
end

-- phone-home: a background thread mirrors every flushed chunk to the dev
-- machine over the tailnet (best-effort — the on-device file is canonical).
-- POSTs happen off the main thread; offline costs one 3s timeout then backs
-- off for 60s; the channel is bounded so an offline session can't grow it.
local TEL_HOME_URL = "http://100.87.221.109:8753/ingest"
local TEL_SENDER = [[
local channel = love.thread.getChannel('tel_home')
local socket = require('socket')
local http = require('socket.http')
local ltn12 = require('ltn12')
http.TIMEOUT = 3
local fail_until = 0
while true do
    local chunk = channel:demand()
    if chunk == '__quit__' then break end
    if socket.gettime() >= fail_until then
        local ok, r = pcall(function()
            local res = http.request{
                url = ']] .. TEL_HOME_URL .. [[',
                method = 'POST',
                headers = { ['content-length'] = #chunk, ['content-type'] = 'text/plain' },
                source = ltn12.source.string(chunk),
            }
            return res
        end)
        if not ok or not r then fail_until = socket.gettime() + 60 end
    end
end
]]
local home_thread
if not os.getenv('BALATRO_FAKE_ANDROID') then
    local ok, thr = pcall(love.thread.newThread, TEL_SENDER)
    if ok and thr then
        local started = pcall(function() thr:start() end)
        if started then home_thread = thr end
    end
end

local function flush()
    if #buf == 0 then return end
    local chunk = table.concat(buf, "\n") .. "\n"
    love.filesystem.append(LOG_FILE, chunk)
    if home_thread then
        local ch = love.thread.getChannel('tel_home')
        if ch:getCount() < 20 then ch:push(chunk) end
    end
    for i = #buf, 1, -1 do buf[i] = nil end
end

local function tel(event, data)
    local parts = {"[TEL]", event}
    if data then
        for k, v in pairs(data) do
            table.insert(parts, k .. "=" .. tostring(v))
        end
    end
    print(table.concat(parts, " "))
    parts[1] = TEL.session_id
    buf[#buf + 1] = os.time() .. " " .. table.concat(parts, " ")
end
-- Global hook so instrumentation injected into game files (e.g. RELEASED_ON_NIL_GUARD
-- in controller.lua) can reach both sinks (logcat + telemetry.log).  Only set on
-- Android; injected sites must guard: if ATLOG then ATLOG(...) else print(...) end
_G.ATLOG = tel

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

    -- always-on frame stats for the file snapshot
    local rdt = self.real_dt or dt
    TEL.frame_count = TEL.frame_count + 1
    TEL.frame_dt_sum = TEL.frame_dt_sum + rdt
    if rdt > TEL.frame_dt_max then TEL.frame_dt_max = rdt end

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

    -- Emit a perf snapshot every PERF_REPORT_INTERVAL seconds. Basic frame
    -- stats are ALWAYS on (fps, avg/max frame ms in the window, heap KB);
    -- per-checkpoint trace averages are added when perf_mode is enabled
    -- (G.check exists; each checkpoint label encodes KB-since-last).
    if now - TEL.perf_window_start >= PERF_REPORT_INTERVAL then
        local snap = {
            fps = love.timer.getFPS(),
            gc_kb = math.floor(collectgarbage("count")),
            state = get_state_name(G.STATE),
            dt_avg_ms = string.format("%.2f", 1000 * TEL.frame_dt_sum / math.max(TEL.frame_count, 1)),
            dt_max_ms = string.format("%.2f", 1000 * TEL.frame_dt_max),
        }
        if G.SETTINGS and G.SETTINGS.perf_mode and G.check then
            local upd_parts = {}
            for i = 1, G.check.update.checkpoints do
                local cp = G.check.update.checkpoint_list[i]
                -- strip the KB suffix from the label ("move: 12" -> "move")
                local name = (cp.label or "?"):match("^([^:]+)") or "?"
                table.insert(upd_parts, name .. "=" .. string.format("%.2f", 1000*(cp.average or 0)))
            end
            local drw_parts = {}
            for i = 1, G.check.draw.checkpoints do
                local cp = G.check.draw.checkpoint_list[i]
                local name = (cp.label or "?"):match("^([^:]+)") or "?"
                table.insert(drw_parts, name .. "=" .. string.format("%.2f", 1000*(cp.average or 0)))
            end
            snap.upd = table.concat(upd_parts, ",")
            snap.drw = table.concat(drw_parts, ",")
        end
        tel("PERF_SNAPSHOT", snap)
        TEL.frame_count = 0
        TEL.frame_dt_sum = 0
        TEL.frame_dt_max = 0
        TEL.perf_window_start = now
    end

    -- flush the file buffer on its own cadence
    if now - last_flush >= FLUSH_INTERVAL then
        last_flush = now
        local ok = pcall(flush)
        if not ok and not TEL.flush_warned then
            TEL.flush_warned = true
            print("[TEL] FLUSH_FAILED telemetry.log writes disabled?")
        end
    end

    -- gesture-debug: trace the description/drag chain as state TRANSITIONS
    -- (cheap comparisons per frame; emits only on change). Reads the live
    -- controller so the device's actual gesture state machine is visible in
    -- the phone-home log.
    local C = G.CONTROLLER
    if C and C.hovering then
        local ht = C.hovering.target
        if ht ~= TEL.g_hover then
            TEL.g_hover = ht
            tel("G_HOVER", {
                card = ht and (ht.config and ht.config.center and ht.config.center.key or "node") or "nil",
                drag = ht and ht.states and ht.states.drag.is and 1 or 0,
                dur = string.format("%.2f", (C.cursor_down and C.cursor_down.duration) or -1),
            })
        end
        local hi = ht and ht.states and ht.states.hover.is or false
        if hi ~= TEL.g_hover_is then
            TEL.g_hover_is = hi
            tel("G_HOVER_IS", {is = hi and 1 or 0, dur = string.format("%.2f", (C.cursor_down and C.cursor_down.duration) or -1)})
        end
        local popup = (ht and ht.children and ht.children.h_popup) and 1 or 0
        if popup ~= TEL.g_popup then
            TEL.g_popup = popup
            tel("G_POPUP", {up = popup})
        end
        local dt_ = C.dragging and C.dragging.target
        if dt_ ~= TEL.g_drag then
            TEL.g_drag = dt_
            tel("G_DRAG", {
                card = dt_ and (dt_.config and dt_.config.center and dt_.config.center.key or "node") or "nil",
                dur = string.format("%.2f", (C.cursor_down and C.cursor_down.duration) or -1),
            })
        end
        local dsa = C.dragSelectActive
        local dss = dsa and (tostring(dsa.active) .. "/" .. tostring(dsa.mode) .. "/" .. (dsa.start_card and "sc" or "-")) or "?"
        if dss ~= TEL.g_dsel then
            TEL.g_dsel = dss
            tel("G_DSEL", {s = dss,
                dur = string.format("%.2f", (C.cursor_down and C.cursor_down.duration) or -1),
                cl = C.collision_list and #C.collision_list or -1,
                tap = string.format("%.2f", ((C.cursor_up and C.cursor_up.time or 0) - (C.cursor_down and C.cursor_down.time or 0)))})
        end
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

-- gesture provenance: wrap the mutation sites the gesture system fights over
-- and tag every call with its CALLER (file:line via debug.getinfo) — the
-- transition events above say WHAT changed; these say WHO did it.
local function src_at(level)
    local info = debug.getinfo(level, "Sl")
    if not info then return "?" end
    local src = (info.short_src or "?"):match("([^/\\]+)$") or info.short_src
    return src .. ":" .. tostring(info.currentline)
end
local function caller_src()
    -- two frames of provenance: immediate caller <- its caller
    return src_at(4) .. "<-" .. src_at(5)
end
local function card_key_of(n)
    return n and ((n.config and n.config.center and n.config.center.key)
        or (n.base and n.base.value and tostring(n.base.value)..tostring(n.base.suit or ""))
        or "node") or "nil"
end
if Node and Node.stop_hover then
    local _sh = Node.stop_hover
    function Node:stop_hover(...)
        if self.children and self.children.h_popup then
            tel("G_STOPHOVER", {card = card_key_of(self), src = caller_src()})
        end
        return _sh(self, ...)
    end
end
if CardArea then
    local _add = CardArea.add_to_highlighted
    function CardArea:add_to_highlighted(card, silent, ...)
        if self == G.hand then
            tel("G_HL", {op = "add", card = card_key_of(card), src = caller_src(), n = #self.highlighted})
        end
        return _add(self, card, silent, ...)
    end
    local _rem = CardArea.remove_from_highlighted
    function CardArea:remove_from_highlighted(card, ...)
        if self == G.hand then
            tel("G_HL", {op = "rem", card = card_key_of(card), src = caller_src(), n = #self.highlighted})
        end
        return _rem(self, card, ...)
    end
end
if Controller and Controller.queue_L_cursor_press then
    local _qp = Controller.queue_L_cursor_press
    function Controller:queue_L_cursor_press(x, y, ...)
        local mx, my = love.mouse.getPosition()
        tel("G_PRESS", {
            qx = string.format("%.0f", x or -1), qy = string.format("%.0f", y or -1),
            mx = string.format("%.0f", mx or -1), my = string.format("%.0f", my or -1),
            dpi = string.format("%.2f", (love.window.getDPIScale and love.window.getDPIScale()) or -1),
            cl = (self.collision_list and #self.collision_list) or -1,
        })
        return _qp(self, x, y, ...)
    end
end
if Card and Card.click then
    local _click = Card.click
    function Card:click(...)
        tel("G_CLICK", {card = card_key_of(self), src = caller_src(), area = self.area and self.area.config and self.area.config.type or "?"})
        return _click(self, ...)
    end
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
    pcall(flush)  -- dying words must reach the file
    if _original_errorhandler then
        return _original_errorhandler(msg)
    end
end

-- flush on app-background too: Android may kill the process while paused
local _original_tel_focus = love.focus
function love.focus(focused)
    if not focused then pcall(flush) end
    if _original_tel_focus then return _original_tel_focus(focused) end
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

tel("HOOKS_LOADED", {count = 10})
