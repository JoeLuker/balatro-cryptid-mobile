-- Android Telemetry for Balatro Cryptid Mobile
-- OFF BY DEFAULT — the APK is shareable; on a stranger's phone this file
-- installs its hooks but emits nothing and writes nothing. Two in-game
-- settings (Settings > Game), persisted in settings.jkr and re-read every
-- frame so toggling applies immediately, no restart:
--   G.SETTINGS.telemetry_log  ("Debug Logging")        — logcat prints +
--       telemetry.log file sink (+ ungates the vanilla LONG DT print)
--   G.SETTINGS.telemetry_home ("Phone Home Telemetry") — POST flushed chunks
--       to the dev machine over the tailnet (independent of the file sink)
--
-- Boot order: this chunk runs at the end of main.lua, BEFORE love.load()
-- merges settings.jkr into G.SETTINGS — the gates can't be read yet. Events
-- that fire during load (SESSION_START, HOOKS_LOADED) buffer in memory
-- (bounded at 64) and are replayed into the sinks on the first frame if a
-- sink is enabled, dropped otherwise. A crash before the first frame is the
-- one case that logs nothing (LÖVE's own error output still reaches logcat).
--
-- When enabled, every event goes to TWO sinks:
--   1. logcat via print() (LÖVE routes to SDL/APP) — live tailing
--   2. telemetry.log in the save dir — persistent, pullable any time with
--      `just perf-pull` (adb run-as). Survives logcat's ring buffer; no
--      observer needs to be attached when something interesting happens.
-- File lines are "epoch session event k=v ..." — grep/awk friendly.
-- Buffered: lines accumulate in memory and flush every FLUSH_INTERVAL (or
-- immediately on crash / app-background, so dying words are never lost).
-- Rotated when the file sink first turns on: >1 MB moves to telemetry.log.1
-- (one generation kept).

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

-- gating: nil until the first frame resolves them from G.SETTINGS (booleans
-- after; both are always set together in apply_gates)
local log_on, home_on = nil, nil
local boot_buf = {}     -- pre-settings events: {print_line, file_line} pairs
local rotated = false   -- rotation runs once, when the file sink first opens
local home_thread

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

local function start_sender()
    if home_thread or os.getenv('BALATRO_FAKE_ANDROID') then return end
    local ok, thr = pcall(love.thread.newThread, TEL_SENDER)
    if ok and thr then
        local started = pcall(function() thr:start() end)
        if started then home_thread = thr end
    end
end

local function flush()
    if #buf == 0 then return end
    local chunk = table.concat(buf, "\n") .. "\n"
    if log_on then
        -- love.filesystem.append reports failure by RETURN VALUE, not by
        -- raising — a pcall around flush() never sees a failed write, so the
        -- one-shot warning must live here (and being inside the log_on branch
        -- keeps the logcat print mapped to the Debug Logging toggle)
        local ok_w = love.filesystem.append(LOG_FILE, chunk)
        if not ok_w and not TEL.flush_warned then
            TEL.flush_warned = true
            print("[TEL] FLUSH_FAILED telemetry.log writes failing — file sink lost")
        end
    end
    if home_on and home_thread then
        local ch = love.thread.getChannel('tel_home')
        if ch:getCount() < 20 then ch:push(chunk) end
    end
    for i = #buf, 1, -1 do buf[i] = nil end
end

-- ── Tier-0a collectors ──────────────────────────────────────────────────
-- These must be defined ABOVE apply_gates, which references them (a
-- definition below would compile the reference as a global — the scoping
-- trap that shipped twice on 2026-06-10).

-- Event-handler burst attribution: EVQ_PROF is a deliberate GLOBAL fed by
-- the EVQ_BURST_ATTRIB patch in engine/event.lua — a table while telemetry
-- collects (handlers timed, slow ones bucketed by source:linedefined), nil
-- otherwise (the event loop pays one global nil-check per handle). Drained
-- into EV_SLOW events alongside each PERF_SNAPSHOT.
local function evq_prof_new()
    return {n = 0, ms = 0, thresh_ms = 1, slow = {}}
end

-- Per-frame draw stats (love.graphics.getStats: drawcalls, shaderswitches,
-- canvasswitches, drawcallsbatched) sampled at the end of Game:draw and
-- averaged into PERF_SNAPSHOT — the targeting data for the SpriteBatch work.
TEL.draw_frames = 0
TEL.dc_sum, TEL.dc_max = 0, 0
TEL.dcb_sum = 0
TEL.shsw_sum, TEL.shsw_max = 0, 0
TEL.cnv_sum = 0
local function reset_draw_stats()
    TEL.draw_frames = 0
    TEL.dc_sum, TEL.dc_max = 0, 0
    TEL.dcb_sum = 0
    TEL.shsw_sum, TEL.shsw_max = 0, 0
    TEL.cnv_sum = 0
end

-- forward declaration: tel is ASSIGNED below (after the gates), but the
-- early-defined collectors reference it — without this the references
-- compile as a nil global (the scoping trap, caught a third time 2026-06-12
-- via shsw_frame_end; runtime-only call paths hide it until they fire)
local tel

-- SHSW attribution (Tier-2a targeting): getStats says shader switches track
-- UI element count (455-830/frame at blind select), but not WHO issues them.
-- Wrap love.graphics.setShader: while telemetry collects, count raw calls
-- per frame (vs getStats' actual switches — the delta is redundant binds,
-- i.e. elision potential); and on ONE armed frame per PERF window, bucket
-- every call by caller source:line (~800 debug.getinfo calls once per 5s).
-- Idle cost: one upvalue boolean check per setShader call.
local shsw_on = false           -- mirrors (log_on or home_on); set in apply_gates
local shsw_attr_armed = false   -- one-frame attribution, armed per PERF window
local shsw_calls = 0            -- raw calls this frame
TEL.shsw_calls_sum = 0          -- summed per window (drained with draw stats)
local shsw_sites = {}
local _setShader = love.graphics.setShader
love.graphics.setShader = function(...)
    if shsw_on then
        shsw_calls = shsw_calls + 1
        if shsw_attr_armed then
            local fi = debug.getinfo(2, "Sl")
            local key = fi and ((fi.short_src or "?") .. ":" .. (fi.currentline or 0)) or "?"
            shsw_sites[key] = (shsw_sites[key] or 0) + 1
        end
    end
    return _setShader(...)
end
local function shsw_frame_end()
    -- called from the Game:draw wrapper each sampled frame
    TEL.shsw_calls_sum = TEL.shsw_calls_sum + shsw_calls
    shsw_calls = 0
    if shsw_attr_armed then
        shsw_attr_armed = false
        local top = {}
        for k, n in pairs(shsw_sites) do top[#top + 1] = { k = k, n = n } end
        table.sort(top, function(a, b) return a.n > b.n end)
        for i = 1, math.min(#top, 8) do
            tel("SHSW_AT", { src = top[i].k:gsub("[%s=]", "_"), n = top[i].n })
        end
        if next(shsw_sites) then shsw_sites = {} end
    end
end

-- JIT trace-abort visibility: every abort leaves that path interpreted (the
-- bimodal frame-time cliff measured on-device). Aggregate aborts per
-- (source:line, reason) and flush a summary with each PERF_SNAPSHOT.
-- jit.vmdef (the error-string table) is a plain-Lua module usually NOT
-- embedded in liblove.so — reasons then log as numeric codes; decode offline
-- against LuaJIT 2.1.1700008891 lj_traceerr.h.
local JIT_TR = {n_start = 0, n_stop = 0, n_abort = 0, n_flush = 0, detail = 0, aborts = {}}
local jit_ok, jit_lib = pcall(require, "jit")
local jutil_ok, jutil = pcall(require, "jit.util")
local vmdef_ok, vmdef = pcall(require, "jit.vmdef")
local function trace_cb(what, tr, func, pc, otr, oex)
    -- runs from the JIT engine itself: must never raise, never trace
    if what == "start" then JIT_TR.n_start = JIT_TR.n_start + 1
    elseif what == "stop" then JIT_TR.n_stop = JIT_TR.n_stop + 1
    elseif what == "flush" then JIT_TR.n_flush = JIT_TR.n_flush + 1
    elseif what == "abort" then
        JIT_TR.n_abort = JIT_TR.n_abort + 1
        if JIT_TR.detail < 400 then  -- cap allocation per flush window
            JIT_TR.detail = JIT_TR.detail + 1
            pcall(function()
                local loc = "?"
                if jutil_ok and func then
                    local fi = jutil.funcinfo(func, pc)
                    if fi then
                        loc = tostring(fi.source or "?"):gsub("^@", "")
                            .. ":" .. tostring(fi.currentline or fi.linedefined or 0)
                    end
                end
                local why
                if vmdef_ok and type(otr) == "number" and vmdef.traceerr and vmdef.traceerr[otr] then
                    why = vmdef.traceerr[otr]
                    if why:find("%%") and oex ~= nil then
                        local ok_f, msg = pcall(string.format, why, tostring(oex))
                        why = ok_f and msg or why
                    end
                else
                    why = tostring(otr) .. (oex ~= nil and ("|" .. tostring(oex)) or "")
                end
                -- telemetry lines are space-separated k=v: values must not
                -- contain spaces or '='
                local key = (loc .. "|" .. why):gsub("[%s=]", "_")
                JIT_TR.aborts[key] = (JIT_TR.aborts[key] or 0) + 1
            end)
        end
    end
end
if jit_ok and jit_lib and jit_lib.off then pcall(jit_lib.off, trace_cb, true) end
local jit_attached = false
local function set_jit_hook(on)
    if not (jit_ok and jit_lib and jit_lib.attach) then return end
    if on and not jit_attached then
        jit_attached = pcall(jit_lib.attach, trace_cb, "trace") and true or false
    elseif not on and jit_attached then
        pcall(jit_lib.attach, trace_cb)  -- no event name = detach
        jit_attached = false
    end
end
-- ── end Tier-0a collectors ──────────────────────────────────────────────

local function apply_gates(want_log, want_home)
    -- drain under the OLD gates on ANY change while active: lines buffered
    -- under one consent state must never be delivered under another (e.g.
    -- enabling phone-home must not ship lines captured while it was off, and
    -- enabling the file sink must not write lines from a home-only window)
    if (log_on or home_on) and (log_on ~= want_log or home_on ~= want_home) then
        pcall(flush)
    end
    -- the sender thread, once started, stays for the process lifetime and
    -- idles on an empty channel while home is off — flush() only pushes when
    -- home_on. (Quitting and restarting it on toggle edges invites a race
    -- where a stale '__quit__' kills the replacement thread.)
    if want_log and not rotated then
        rotated = true
        -- deferred boot rotation (love.filesystem has no rename; one read/write)
        local info = love.filesystem.getInfo(LOG_FILE)
        if info and info.size and info.size > LOG_CAP_BYTES then
            local old = love.filesystem.read(LOG_FILE)
            if old then
                love.filesystem.write(LOG_FILE .. ".1", old)
                love.filesystem.remove(LOG_FILE)
            end
        end
    end
    if want_home then start_sender() end
    if not (want_log or want_home) and (log_on or home_on) then
        -- deactivation edge: drop the Tier-0a collectors so the event loop
        -- and JIT engine pay nothing while telemetry is off
        EVQ_PROF = nil
        set_jit_hook(false)
        shsw_on = false
    end
    local activated = (want_log or want_home) and not (log_on or home_on)
    if activated then
        -- activation edge: start the reporting windows now, not at t=0
        local now = (G and G.TIMERS and G.TIMERS.UPTIME) or 0
        TEL.exp_recompute_window_start = now
        TEL.perf_window_start = now
        TEL.exp_recompute_count = 0
        TEL.frame_count, TEL.frame_dt_sum, TEL.frame_dt_max = 0, 0, 0
        last_flush = now
        -- arm the Tier-0a collectors
        EVQ_PROF = evq_prof_new()
        set_jit_hook(true)
        reset_draw_stats()
        shsw_on = true
        TEL.shsw_calls_sum = 0
        -- resync the STATE baseline: a re-enable mid-game would otherwise
        -- emit a transition whose 'from' is the stale pre-disable state.
        -- (Gesture baselines are left alone — their events carry no 'from',
        -- so a first event after re-enable is just a current-state dump.)
        TEL.last_state = G and G.STATE
        TEL.last_stage = G and G.STAGE
    end
    local first = (log_on == nil)
    log_on, home_on = want_log, want_home
    if first then
        if (want_log or want_home) and boot_buf then
            for i = 1, #boot_buf do
                if log_on then print(boot_buf[i][1]) end
                buf[#buf + 1] = boot_buf[i][2]
            end
        end
        boot_buf = nil
    end
    -- OBS self-announce: fires on every off->on activation edge (logging on at
    -- boot OR toggled live), after log_on is set so the lines reach the sinks.
    -- OBS_REGISTRY enumerates the persisted TEL.registry, so components that
    -- registered while logging was off still appear in the inventory.
    if activated then
        tel("OBS_INIT", { level = want_log and "on" or "home-only",
            file = LOG_FILE, crashes = "crash.log",
            home = want_home and "on" or "off" })
        local names = {}
        for n, r in pairs(TEL.registry) do names[#names + 1] = n .. ":" .. (r.status or "?") end
        table.sort(names)
        tel("OBS_REGISTRY", { n = #names, components = table.concat(names, ",") })
    end
end

-- assigns the forward-declared local (see SHSW block above)
function tel(event, data)
    -- OBS: crashes are ALWAYS captured to a dedicated always-on sink (crash.log),
    -- even when telemetry is gated off, so a crash is never lost.
    if event == "CRASH" then
        pcall(function()
            local cp = {os.time(), TEL.session_id}
            for k, v in pairs(data or {}) do cp[#cp + 1] = k .. "=" .. tostring(v) end
            love.filesystem.append("crash.log", table.concat(cp, " ") .. "\n")
        end)
    end
    if log_on == false and home_on == false then return end
    local parts = {"[TEL]", event}
    if data then
        for k, v in pairs(data) do
            table.insert(parts, k .. "=" .. tostring(v))
        end
    end
    local pline = table.concat(parts, " ")
    parts[1] = TEL.session_id
    local fline = os.time() .. " " .. table.concat(parts, " ")
    if log_on == nil then
        if #boot_buf < 64 then boot_buf[#boot_buf + 1] = {pline, fline} end
        return
    end
    if log_on then print(pline) end
    buf[#buf + 1] = fline
end

-- Instrumentation injected into game files by build.sh (G_REL_SKIP in
-- controller.lua) calls this global when present — defining it here routes
-- those events through the same gates and sinks. On desktop the global stays
-- nil and the call sites fall back to print().
ATLOG = tel

-- OBS: the single observability surface. Mods, shims, loaders and the crash
-- handler all report through this, so the parts know about each other and the
-- log is self-describing. ATLOG stays as an alias for existing call sites.
TEL.registry = {}
local function obs_register(name, status, detail)
    TEL.registry[name] = { status = status or "ok", detail = detail }
    tel("REGISTER", { name = name, status = status or "ok", detail = detail })
end
OBS = {
    event    = tel,           -- OBS.event(kind, data)
    register = obs_register,  -- OBS.register(name, status[, detail]) — components self-announce
    registry = TEL.registry,
}

-- Session start
tel("SESSION_START", {id = TEL.session_id, device = love.system.getOS(),
    build = (G and G.CRYPTID_MOBILE_BUILD) or "?"})

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

-- node identity for traces. MUST be defined above every wrapper that calls
-- it (including the Game:update tracer) — a definition below a wrapper
-- compiles the reference as a global and crashes at runtime (shipped that
-- bug twice on 2026-06-10: install_late_hooks caught in review, this one on
-- device).
local function key_of_one(n)
    return (n.config and n.config.center and n.config.center.key)
        or (n.config and n.config.button)                  -- UIBox buttons: the G.FUNCS callback name
        or (n.config and n.config.id and tostring(n.config.id))
        or (n.base and n.base.value and tostring(n.base.value)..tostring(n.base.suit or ""))
end
local function card_key_of(n)
    if not n then return "nil" end
    if G and n == G.ROOM then return "ROOM" end  -- the press-on-nothing fallback target
    local k = key_of_one(n)
    if k then return k end
    -- anonymous node: climb parents for the nearest named ancestor so a
    -- press-stealing child (e.g. a button's text node) is attributable
    local p, depth = n.parent, 0
    while p and depth < 4 do
        local pk = key_of_one(p)
        if pk then return "node<" .. pk end
        p, depth = p.parent, depth + 1
    end
    return "node"
end

-- G_PRESS must install LATE: SMODS mods load during start_up — after this
-- chunk — and Cryptid REPLACES Controller.queue_L_cursor_press outright
-- (lib/overrides.lua:1385), clobbering any chunk-load-time wrap. That is why
-- G_PRESS never fired from any prior build. Installing on the first
-- Game:update frame wraps Cryptid's version instead.
-- HAND_CALCS accumulator (filled by the calculate_joker wrap in
-- install_late_hooks, emitted by the update hook at scoring-run end).
-- hits = calls that returned an effect; the complement is the no-op
-- fraction — the direct measure of how much of the jokers×contexts×reps
-- sweep is wasted work.
local CALC_ATTR = { total = 0, nhit = 0, joks = {}, hits = {}, ctx = {},
    t0 = 0, tc0_runs = 0, tc0_coll = 0 }
local calc_run_active = false

local late_hooks_done = false
local function install_late_hooks()
    late_hooks_done = true
    -- definitive button-level signal: did the game callback actually run?
    for _, fname in ipairs({ 'select_blind', 'skip_blind' }) do
        local _f = G.FUNCS and G.FUNCS[fname]
        if _f then
            G.FUNCS[fname] = function(e, ...)
                tel("G_BTN", { f = fname })
                return _f(e, ...)
            end
        end
    end
    if Controller and Controller.queue_L_cursor_press then
        local _qp = Controller.queue_L_cursor_press
        function Controller:queue_L_cursor_press(x, y, ...)
            if log_on or home_on then
                local mx, my = love.mouse.getPosition()
                tel("G_PRESS", {
                    qx = string.format("%.0f", x or -1), qy = string.format("%.0f", y or -1),
                    mx = string.format("%.0f", mx or -1), my = string.format("%.0f", my or -1),
                    dpi = string.format("%.2f", (love.window.getDPIScale and love.window.getDPIScale()) or -1),
                    cl = (self.collision_list and #self.collision_list) or -1,
                })
            end
            return _qp(self, x, y, ...)
        end
    end
    -- HAND_CALCS attribution: Amulet's "calculations" counter is one
    -- increment per Card:calculate_joker call inside the scoring coroutine
    -- (talisman/coroutine.lua) — a flat count with no memory of which joker
    -- or which context. Wrap the same chokepoint (we load after Amulet, so
    -- this is the outermost wrap and counts 1:1 with its counter) and
    -- accumulate per-joker-key and per-context-kind tallies, scoped to the
    -- coroutine exactly like Amulet's count. Emitted as HAND_CALCS by the
    -- update hook when the scoring run ends.
    if Card and Card.calculate_joker then
        local _cj = Card.calculate_joker
        function Card:calculate_joker(context)
            if not (Talisman and Talisman.scoring_coroutine) then
                return _cj(self, context)
            end
            local A = CALC_ATTR
            A.total = A.total + 1
            local key = self.config and self.config.center and self.config.center.key or '?'
            A.joks[key] = (A.joks[key] or 0) + 1
            local c = context
            local ck = (c.repetition and 'rep') or (c.retrigger_joker and 'retrig')
                or (c.other_joker and 'copy') or (c.individual and 'individual')
                or (c.joker_main and 'main') or (c.before and 'before')
                or (c.after and 'after') or (c.end_of_round and 'eor')
                or (c.setting_blind and 'blind')
                or (c.cardarea == G.play and 'play_pass')
                or (c.cardarea == G.hand and 'hand_pass') or 'other'
            A.ctx[ck] = (A.ctx[ck] or 0) + 1
            local ret, trig = _cj(self, context)
            if ret then
                A.nhit = A.nhit + 1
                A.hits[key] = (A.hits[key] or 0) + 1
            end
            return ret, trig
        end
    end
end

-- Hook Game:update to track state transitions and exp_recompute rate
local _original_game_update = Game.update
function Game:update(dt)
    -- resolve/refresh the gates from settings before anything can emit this
    -- frame. settings.jkr merges in start_up() (inside love.load), so the
    -- first frame's read is already the persisted value; after that this is
    -- two table reads per frame and the toggles apply live.
    if not late_hooks_done then install_late_hooks() end
    local s = self.SETTINGS
    local want_log = not not (s and s.telemetry_log)
    local want_home = not not (s and s.telemetry_home)
    if want_log ~= log_on or want_home ~= home_on then
        apply_gates(want_log, want_home)
    end
    if not (log_on or home_on) then
        return _original_game_update(self, dt)
    end

    -- HAND_CALCS baseline: a scoring run can start AND collapse inside the
    -- update call below, so the collapse-stats baseline must be taken
    -- pre-update while no run is active (a post-detection snapshot reads
    -- counters the run already bumped).
    if not calc_run_active then
        local tcs = TRIGGER_COLLAPSE and TRIGGER_COLLAPSE.stats_total
        CALC_ATTR.tc0_runs = tcs and tcs.runs or 0
        CALC_ATTR.tc0_coll = tcs and tcs.collapsed_reps or 0
        CALC_ATTR.t0 = love.timer.getTime()
    end

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

    -- HAND_CALCS: emit the per-scoring-run calc attribution when the Amulet
    -- coroutine winds down (truthy -> nil transition). One line per scored
    -- hand: total calls, hit count (returned an effect), top jokers as
    -- key:calls:hits, context-kind histogram, and the collapse engine's
    -- delta for this run (runs/collapsed must explain — or indict — the
    -- rep volume).
    do
        local sc = Talisman and Talisman.scoring_coroutine
        if sc and not calc_run_active then
            calc_run_active = true  -- baseline already captured pre-update
        elseif not sc and calc_run_active then
            calc_run_active = false
            local A = CALC_ATTR
            if A.total > 0 then
                local top = {}
                for k, n in pairs(A.joks) do top[#top + 1] = { k = k, n = n } end
                table.sort(top, function(a, b) return a.n > b.n end)
                local jparts = {}
                for i = 1, math.min(#top, 10) do
                    local t = top[i]
                    jparts[i] = t.k .. ':' .. t.n .. ':' .. (A.hits[t.k] or 0)
                end
                local cparts = {}
                for k, n in pairs(A.ctx) do cparts[#cparts + 1] = k .. ':' .. n end
                table.sort(cparts)
                local tcs = TRIGGER_COLLAPSE and TRIGGER_COLLAPSE.stats_total
                tel("HAND_CALCS", {
                    total = A.total,
                    hits = A.nhit,
                    noop_pct = string.format("%.1f", 100 * (A.total - A.nhit) / A.total),
                    -- Amulet's own run clock (co.finish stamps it just before
                    -- the coroutine winds down); wall t0 as fallback
                    secs = string.format("%.2f", (G.GAME and G.GAME.LAST_CALC_TIME)
                        or (love.timer.getTime() - A.t0)),
                    njok = #top,
                    amulet_calcs = (G.GAME and G.GAME.LAST_CALCS) or -1,
                    tc_runs = tcs and (tcs.runs - A.tc0_runs) or -1,
                    tc_collapsed = tcs and (tcs.collapsed_reps - A.tc0_coll) or -1,
                    joks = table.concat(jparts, ","),
                    ctx = table.concat(cparts, ","),
                })
            end
            A.total, A.nhit, A.joks, A.hits, A.ctx = 0, 0, {}, {}, {}
        end
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
        -- object-registry counts: a leak shows up as the counter that climbs.
        -- n_ui_s counts only boxes that go through the uiboxes draw loop
        -- (mirrors the filter at game.lua:3011: no attention_text, no parent,
        -- not the excluded overlay singletons). n_ui_total is the raw registry
        -- size; the delta (n_ui_total - n_ui_s) is mostly transient
        -- attention_text animation boxes that are excluded from the draw loop
        -- and therefore do NOT contribute to the uiboxes timer — correlating
        -- n_ui_total against uiboxes ms produces a misleading floor artifact.
        if G.I then
            snap.n_node = #G.I.NODE
            snap.n_mov = #G.I.MOVEABLE
            snap.n_card = #G.I.CARD
            snap.n_moves = G.MOVEABLES and #G.MOVEABLES or -1
            local n_s = 0
            for _, v in pairs(G.I.UIBOX) do
                if not v.attention_text and not v.parent
                    and v ~= G.OVERLAY_MENU and v ~= G.screenwipe
                    and v ~= G.OVERLAY_TUTORIAL and v ~= G.debug_tools
                    and v ~= G.online_leaderboard
                    and v ~= G.achievement_notification then
                    n_s = n_s + 1
                end
            end
            snap.n_ui_s = n_s
            snap.n_ui_total = #G.I.UIBOX
            -- MOVEABLE_SHADOW_LISTS: UIElement count drives the update= cost
            snap.n_uie = G.MOVEABLES_UE and #G.MOVEABLES_UE or -1
        end
        -- checkpoint breakdowns flow whenever collection is on: Debug Logging
        -- alone enables them headlessly (the on-screen overlay needs the
        -- separate Debug Overlay toggle)
        if G.SETTINGS and (G.SETTINGS.perf_mode or G.SETTINGS.telemetry_log) and G.check then
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
        -- Tier-0a: draw-call stats for the window (Game:draw wrapper below)
        if TEL.draw_frames > 0 then
            local n = TEL.draw_frames
            snap.dc_avg = math.floor(TEL.dc_sum / n + 0.5)
            snap.dc_max = TEL.dc_max
            snap.dcb_avg = math.floor(TEL.dcb_sum / n + 0.5)
            snap.shsw_avg = math.floor(TEL.shsw_sum / n + 0.5)
            snap.shsw_max = TEL.shsw_max
            snap.cnv_avg = math.floor(TEL.cnv_sum / n + 0.5)
            -- raw setShader CALLS vs actual switches: the gap is redundant
            -- binds (elision potential for Tier-2a)
            snap.shsw_set_avg = math.floor(TEL.shsw_calls_sum / n + 0.5)
            TEL.shsw_calls_sum = 0
            -- lazy-shader elision (Tier-2a v1): real GPU binds vs game-issued
            -- calls for the window — binds << calls is the win, live
            if LAZY_SHADER then
                snap.ls_binds = LAZY_SHADER.binds - (TEL.ls_binds_last or 0)
                snap.ls_calls = LAZY_SHADER.calls - (TEL.ls_calls_last or 0)
                TEL.ls_binds_last, TEL.ls_calls_last = LAZY_SHADER.binds, LAZY_SHADER.calls
            end
            reset_draw_stats()
        end
        -- Tier-2a: attribute every setShader call of ONE upcoming frame to
        -- its caller (SHSW_AT events emitted from the draw wrapper)
        shsw_attr_armed = true
        -- Tier-0a: event-handler totals for the window
        local _ep = EVQ_PROF
        if _ep then
            snap.n_evh = _ep.n
            snap.evh_ms = string.format("%.1f", _ep.ms)
        end
        tel("PERF_SNAPSHOT", snap)
        -- Tier-0a: name the slowest event handlers of the window (top 5 by
        -- total ms; src is func source:linedefined — in the lovely-merged
        -- main.lua the line number IS the mod attribution)
        if _ep then
            local top = {}
            for k, b in pairs(_ep.slow) do top[#top + 1] = {k = k, b = b} end
            table.sort(top, function(a, b) return a.b.ms > b.b.ms end)
            for i = 1, math.min(#top, 5) do
                local t = top[i]
                tel("EV_SLOW", {src = t.k:gsub("[%s=]", "_"), n = t.b.n,
                    ms = string.format("%.1f", t.b.ms),
                    max_ms = string.format("%.1f", t.b.max)})
            end
            _ep.n, _ep.ms = 0, 0
            if next(_ep.slow) then _ep.slow = {} end
        end
        -- trigger-collapse stats for the window (engine in
        -- trigger-collapse.lua; mismatches must stay zero)
        local tc = TRIGGER_COLLAPSE
        if tc and tc.stats and (tc.stats.runs > 0 or tc.stats.unstable > 0
                or tc.stats.impure > 0 or tc.stats.mismatches > 0) then
            tel("RLE_STATS", {
                runs = tc.stats.runs,
                collapsed = tc.stats.collapsed_reps,
                honest = tc.stats.honest_reps,
                unstable = tc.stats.unstable,
                impure = tc.stats.impure,
                mismatch = tc.stats.mismatches,
                max_run = tc.stats.max_run,
            })
            tc.stats.runs, tc.stats.collapsed_reps, tc.stats.honest_reps = 0, 0, 0
            tc.stats.unstable, tc.stats.impure, tc.stats.mismatches = 0, 0, 0
            tc.stats.max_run = 0
        end
        -- Tier-0a: JIT trace activity for the window (only emit when the
        -- compiler did something — steady state after warmup is silence)
        if jit_attached and (JIT_TR.n_abort > 0 or JIT_TR.n_stop > 0
                or JIT_TR.n_start > 0 or JIT_TR.n_flush > 0) then
            tel("JIT_TRACE", {n_start = JIT_TR.n_start, n_stop = JIT_TR.n_stop,
                n_abort = JIT_TR.n_abort, n_flush = JIT_TR.n_flush})
            local atop = {}
            for k, n in pairs(JIT_TR.aborts) do atop[#atop + 1] = {k = k, n = n} end
            table.sort(atop, function(a, b) return a.n > b.n end)
            for i = 1, math.min(#atop, 8) do
                tel("JIT_ABORT", {at = atop[i].k, n = atop[i].n})
            end
            JIT_TR.n_start, JIT_TR.n_stop, JIT_TR.n_abort, JIT_TR.n_flush, JIT_TR.detail = 0, 0, 0, 0, 0
            if next(JIT_TR.aborts) then JIT_TR.aborts = {} end
        end
        TEL.frame_count = 0
        TEL.frame_dt_sum = 0
        TEL.frame_dt_max = 0
        TEL.perf_window_start = now

        -- HEAP CENSUS: when the heap crosses a threshold, walk the reachable
        -- table graph from the major roots and report the biggest subtrees —
        -- retained data can hide from samplers but not from a reachability
        -- walk. Budgeted (~150k entries, tens of ms once per threshold), so
        -- it costs one hitch at 130MB and another every +40MB.
        local heap_mb = snap.gc_kb / 1024
        TEL.census_next = TEL.census_next or 130
        if heap_mb >= TEL.census_next then
            TEL.census_next = heap_mb + 40
            local ok_c = pcall(function()
                local visited, budget = {}, 150000
                -- pre-mark the mega-roots so no subtree absorbs them through
                -- back-references (package.loaded._G reaches everything)
                visited[_G] = true; visited[G] = true
                if package then visited[package] = true; visited[package.loaded] = true end
                -- pre-mark alias registries: every entry in these lists is
                -- owned elsewhere (G.jokers, UIBoxes, ...), so letting them
                -- act as census roots just steals whole subtrees from their
                -- real owners by reach order (observed 2026-06-11:
                -- G.MOVEABLES_UE "absorbed" 34.5k entries that belong to the
                -- UI tree). Marking the list table itself stops the walk
                -- from entering through the alias; contents still attribute
                -- to whichever true owner reaches them.
                for _, alias in ipairs({'MOVEABLES', 'MOVEABLES_C', 'MOVEABLES_S',
                        'MOVEABLES_UB', 'MOVEABLES_UE', 'MOVEABLES_O'}) do
                    if type(G[alias]) == 'table' then visited[G[alias]] = true end
                end
                -- G.I (instance registry: I.NODE/I.MOVEABLE/I.CARD/...) is the
                -- same kind of alias — it reaches every live object and
                -- absorbed 61k entries as a root on 2026-06-11
                if type(G.I) == 'table' then
                    visited[G.I] = true
                    for _, reg in pairs(G.I) do
                        if type(reg) == 'table' then visited[reg] = true end
                    end
                end
                -- G.STAGE_OBJECTS (scene-graph attachment root) reaches every
                -- attached node — absorbed the entire 150k walk budget as the
                -- first-reached root on 2026-06-12, blinding the census.
                -- Mark the root and per-stage lists; contents attribute
                -- through semantic owners (G.jokers, G.GAME, ...).
                if type(G.STAGE_OBJECTS) == 'table' then
                    visited[G.STAGE_OBJECTS] = true
                    for _, stage in pairs(G.STAGE_OBJECTS) do
                        if type(stage) == 'table' then visited[stage] = true end
                    end
                end
                -- G.DRAW_HASH: per-frame array of drawn objects (in-place
                -- emptied each frame) — another pure alias; absorbed 111k as
                -- a root on 2026-06-12 once STAGE_OBJECTS was marked
                if type(G.DRAW_HASH) == 'table' then visited[G.DRAW_HASH] = true end
                local function subtree_count(root)
                    if type(root) ~= 'table' or visited[root] then return 0 end
                    visited[root] = true
                    local n, queue, qi = 0, {root}, 1
                    while queue[qi] and budget > 0 do
                        local cur = queue[qi]; queue[qi] = nil; qi = qi + 1
                        local ok_iter = pcall(function()
                            for k, v in pairs(cur) do
                                budget = budget - 1; n = n + 1
                                if type(v) == 'table' and not visited[v] then
                                    visited[v] = true; queue[#queue + 1] = v
                                end
                                if type(k) == 'table' and not visited[k] then
                                    visited[k] = true; queue[#queue + 1] = k
                                end
                                if budget <= 0 then break end
                            end
                        end)
                        if not ok_iter then break end
                    end
                    return n
                end
                local entries = {}
                -- G's fields first (the game lives here), then other globals;
                -- shared structures attribute to whichever root reaches them
                -- first, so ordering is part of the report's semantics
                for k, v in pairs(G) do
                    if type(v) == 'table' then
                        local n = subtree_count(v)
                        if n > 500 then entries[#entries + 1] = {'G.' .. tostring(k), n} end
                    end
                end
                for k, v in pairs(_G) do
                    if type(v) == 'table' and v ~= G and v ~= _G then
                        local n = subtree_count(v)
                        if n > 500 then entries[#entries + 1] = {tostring(k), n} end
                    end
                end
                table.sort(entries, function(a, b) return a[2] > b[2] end)
                for i = 1, math.min(#entries, 20) do
                    tel("CENSUS", {at_mb = math.floor(heap_mb), root = entries[i][1], n = entries[i][2]})
                end
                tel("CENSUS_DONE", {at_mb = math.floor(heap_mb), budget_left = budget})
            end)
            if not ok_c then tel("CENSUS_FAILED", {at_mb = math.floor(heap_mb)}) end
        end
    end

    -- flush the file buffer on its own cadence (write failures are detected
    -- and warned about inside flush(), where they actually surface)
    if now - last_flush >= FLUSH_INTERVAL then
        last_flush = now
        pcall(flush)
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
        -- the description-toggle memory: which card the controller believes
        -- has its description on screen (TAP_DESC_TOGGLE state)
        local sd = C.shown_desc
        if sd ~= TEL.g_sdesc then
            TEL.g_sdesc = sd
            tel("G_SDESC", {t = card_key_of(sd)})
        end
        -- classification outcomes (assigned in Controller:update after the
        -- release is recorded; G_REL can't see them — these transitions can)
        local ck = C.clicked and C.clicked.target
        if ck ~= TEL.g_clicked then
            TEL.g_clicked = ck
            tel("G_CLICKT", {t = card_key_of(ck)})
        end
        local ro = C.released_on and C.released_on.target
        if ro ~= TEL.g_rel_on then
            TEL.g_rel_on = ro
            tel("G_RELON", {t = card_key_of(ro)})
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

-- Tier-0a: sample love.graphics.getStats at the end of Game:draw (stats
-- accumulate from frame start; sampling here captures the whole game draw
-- and excludes any overlay drawn after). Table-arg form avoids a per-frame
-- allocation. Averaged into PERF_SNAPSHOT by the update hook above.
local _original_game_draw = Game.draw
local _gstats = {}
function Game:draw(...)
    local r = _original_game_draw(self, ...)
    if (log_on or home_on) and love.graphics.getStats then
        love.graphics.getStats(_gstats)
        TEL.draw_frames = TEL.draw_frames + 1
        local dc = _gstats.drawcalls or 0
        TEL.dc_sum = TEL.dc_sum + dc
        if dc > TEL.dc_max then TEL.dc_max = dc end
        TEL.dcb_sum = TEL.dcb_sum + (_gstats.drawcallsbatched or 0)
        local ss = _gstats.shaderswitches or 0
        TEL.shsw_sum = TEL.shsw_sum + ss
        if ss > TEL.shsw_max then TEL.shsw_max = ss end
        TEL.cnv_sum = TEL.cnv_sum + (_gstats.canvasswitches or 0)
        shsw_frame_end()
    end
    return r
end

-- gesture provenance: wrap the mutation sites the gesture system fights over
-- and tag every call with its CALLER (file:line via debug.getinfo) — the
-- transition events above say WHAT changed; these say WHO did it.
-- Each wrapper checks the gates itself so the debug.getinfo provenance walk
-- never runs while telemetry is off.
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
if Node and Node.stop_hover then
    local _sh = Node.stop_hover
    function Node:stop_hover(...)
        if (log_on or home_on) and self.children and self.children.h_popup then
            tel("G_STOPHOVER", {card = card_key_of(self), src = caller_src()})
        end
        return _sh(self, ...)
    end
end
if CardArea then
    local _add = CardArea.add_to_highlighted
    function CardArea:add_to_highlighted(card, silent, ...)
        if (log_on or home_on) and self == G.hand then
            tel("G_HL", {op = "add", card = card_key_of(card), src = caller_src(), n = #self.highlighted})
        end
        return _add(self, card, silent, ...)
    end
    local _rem = CardArea.remove_from_highlighted
    function CardArea:remove_from_highlighted(card, ...)
        if (log_on or home_on) and self == G.hand then
            tel("G_HL", {op = "rem", card = card_key_of(card), src = caller_src(), n = #self.highlighted})
        end
        return _rem(self, card, ...)
    end
end
-- G_REL: one event per cursor release at the classification chokepoint —
-- what was under the press, what the release resolved to (click / drop
-- target / nothing), the distance+duration that gated it, and whether the
-- controller lock ate the release entirely (locked releases never classify;
-- a lock window during animations reads as "button didn't respond").
if Controller and Controller.L_cursor_release then
    local _lr = Controller.L_cursor_release
    function Controller:L_cursor_release(x, y)
        if not (log_on or home_on) then return _lr(self, x, y) end
        local down_t = self.cursor_down and self.cursor_down.target
        local was_drag = self.dragging and self.dragging.target
        local r = _lr(self, x, y)
        -- NOTE: classification (clicked/released_on assignment) happens later
        -- in Controller:update, not here — its outcome is traced by the
        -- G_CLICKT/G_RELON transition events in the frame tracer. This event
        -- captures release GEOMETRY plus the down-target's dispatch gates as
        -- they stand at release (the UIElement:click() gates: click.can,
        -- one_press disable_button, visible).
        tel("G_REL", {
            down = card_key_of(down_t),
            drag = was_drag and card_key_of(was_drag) or "-",
            up_t = card_key_of(self.cursor_up and self.cursor_up.target),
            dist = string.format("%.2f", (self.cursor_down.T and self.cursor_up.T and Vector_Dist and Vector_Dist(self.cursor_down.T, self.cursor_up.T)) or -1),
            dur = string.format("%.2f", (self.cursor_up.time or 0) - (self.cursor_down.time or 0)),
            lock = (self.locked and 1 or 0) .. "/" .. (self.locks and self.locks.frame and 1 or 0),
            ccan = down_t and down_t.states and (down_t.states.click.can and 1 or 0) or "-",
            dis = down_t and (down_t.disable_button and 1 or 0) or "-",
            vis = down_t and down_t.states and (down_t.states.visible and 1 or 0) or "-",
        })
        return r
    end
end
-- G_MPRESS: love.mousepressed-level probe (the run loop dispatches every
-- press, touch-synthesized or real, through here) — pins down where the
-- never-firing G_PRESS method wrapper loses the call chain.
if love.mousepressed then
    local _mp = love.mousepressed
    function love.mousepressed(x, y, button, touch, ...)
        if (log_on or home_on) and button == 1 then
            tel("G_MPRESS", {
                x = string.format("%.0f", x or -1), y = string.format("%.0f", y or -1),
                touch = touch and 1 or 0,
                lock = (G.CONTROLLER and G.CONTROLLER.locked and 1 or 0) .. "/" .. (G.CONTROLLER and G.CONTROLLER.locks and G.CONTROLLER.locks.frame and 1 or 0),
            })
        end
        return _mp(x, y, button, touch, ...)
    end
end
if Card and Card.click then
    local _click = Card.click
    function Card:click(...)
        if log_on or home_on then
            tel("G_CLICK", {card = card_key_of(self), src = caller_src(), area = self.area and self.area.config and self.area.config.type or "?"})
        end
        return _click(self, ...)
    end
end

-- Hook Game:start_run to log run starts
local _original_start_run = Game.start_run
function Game:start_run(args)
    TEL.run_start_time = os.time()
    TEL.game_over_logged = nil  -- re-arm GAME_OVER logging for the new run
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
    -- the handler runs on top of the erroring stack (xpcall handler), so
    -- debug.traceback here sees the real error frames — same trick the
    -- vanilla crash screen uses. Keep it compact: strip the header and the
    -- C/boot frames, one line per frame joined with " | ".
    local trace = ""
    pcall(function()
        trace = debug.traceback("", 2) or ""
        trace = trace:gsub("stack traceback:%s*", "")
            :gsub("%s*\n%s*", " | ")
            :gsub("%[C%]: in [^|]*|? ?", "")
            :sub(1, 600)
    end)
    -- the erroring frames are still live under this handler, so pull `self`
    -- out of the innermost method frame (debug.getlocal) and identify the
    -- object that died — the traceback alone is the same per-frame update
    -- chain no matter which moveable crashed.
    local who = ""
    pcall(function()
        for lvl = 2, 24 do
            if not debug.getinfo(lvl, "f") then break end
            local name, val = debug.getlocal(lvl, 1)
            if name == "self" and type(val) == "table" then
                local parts = {}
                for _, cls in ipairs({ "Card", "CardArea", "UIBox", "UIElement",
                    "DynaText", "AnimatedSprite", "Sprite", "Particles", "Blind",
                    "Moveable", "Node" }) do
                    local c = rawget(_G, cls)
                    local ok, r = pcall(function() return c and val.is and val:is(c) end)
                    if ok and r then parts[#parts + 1] = cls; break end
                end
                if val.config and val.config.id then parts[#parts + 1] = "id:" .. tostring(val.config.id) end
                if val.label then parts[#parts + 1] = "label:" .. tostring(val.label) end
                if val.config and val.config.object then parts[#parts + 1] = "obj:" .. tostring(val.config.object) end
                if val.UIBox and val.UIBox.config and val.UIBox.config.id then
                    parts[#parts + 1] = "uibox:" .. tostring(val.UIBox.config.id)
                end
                if val.T then
                    parts[#parts + 1] = string.format("T(%s,%s,%s,%s)",
                        tostring(val.T.x), tostring(val.T.y), tostring(val.T.w), tostring(val.T.h))
                end
                if val.VT then
                    parts[#parts + 1] = string.format("VT(%s,%s)", tostring(val.VT.w), tostring(val.VT.h))
                end
                who = table.concat(parts, " "):sub(1, 300)
                break
            end
        end
    end)
    tel("CRASH", {
        state = get_state_name(G and G.STATE),
        stage = STAGE_NAMES[G and G.STAGE] or "unknown",
        ante = G and G.GAME and G.GAME.round_resets and G.GAME.round_resets.ante or 0,
        round = G and G.GAME and G.GAME.round or 0,
        error = tostring(msg):sub(1, 200):gsub("\n", " | "),
        trace = trace,
        who = who
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
