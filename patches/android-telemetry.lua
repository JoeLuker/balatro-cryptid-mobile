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
    end
    if (want_log or want_home) and not (log_on or home_on) then
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
end

local function tel(event, data)
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
            reset_draw_stats()
        end
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
