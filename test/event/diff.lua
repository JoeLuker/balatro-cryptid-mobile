-- EVQ_COMPACT differential soak: drive the ORIGINAL EventManager (pristine
-- dump, table.remove mid-walk) and the PATCHED one (build tree, identity-mark
-- + compact) with byte-identical randomized event scripts and assert the
-- observable state never diverges: queue contents (by spec id, in order),
-- per-event .complete flags, and the exact sequence of handler invocations.
-- Covers: all four scripted triggers, blocking/blockable mixes, multi-step
-- completions, front-inserts and tail-inserts during handle, pause flips,
-- forced updates, and clear_queue() fired from inside a handler.
--
-- Run: luajit test/event/diff.lua [n_seeds] [n_ticks]
-- Exits 0 on full agreement, 1 with a seed + tick report on divergence.

local N_SEEDS = tonumber(arg and arg[1]) or 300
local N_TICKS = tonumber(arg and arg[2]) or 120

local ROOT = (arg and arg[0] or ''):match('^(.*)/test/event/diff%.lua$') or '.'
-- ORIG = the COMMITTED pinned pre-patch tree (vendor/dump), not the gitignored
-- desktop dump (src/dump, retired) — so the soak runs on a fresh clone too.
local ORIG = ROOT .. '/vendor/dump/engine/event.lua'
local PATCHED = ROOT .. '/build/game/engine/event.lua'

-- ── minimal world ───────────────────────────────────────────────────────
-- Object base mirrors engine/object.lua (SNKRX-style); Event() construction
-- goes through the metatable __call chain exactly like the game.
Object = {}
Object.__index = Object
function Object:init() end
function Object:extend()
    local cls = {}
    for k, v in pairs(self) do
        if k:find("__") == 1 then cls[k] = v end
    end
    cls.__index = cls
    cls.super = self
    setmetatable(cls, self)
    return cls
end
function Object:is(T)
    local mt = getmetatable(self)
    while mt do
        if mt == T then return true end
        mt = getmetatable(mt)
    end
    return false
end
Object.__call = function(cls, ...)
    local obj = setmetatable({}, cls)
    obj:init(...)
    return obj
end

G = { TIMERS = { REAL = 0, TOTAL = 0 }, SETTINGS = { paused = false }, ARGS = {} }
SMODS = { ease_types = {}, log_crash_info = function() return '' end }

local function load_impl(path)
    local prev_event, prev_em = Event, EventManager
    local chunk = assert(loadfile(path))
    chunk()
    local E, M = Event, EventManager
    Event, EventManager = prev_event, prev_em
    return E, M
end

local OrigEvent, OrigEM = load_impl(ORIG)
local NewEvent, NewEM = load_impl(PATCHED)
assert(OrigEvent ~= NewEvent, 'implementations did not load independently')

-- ── scripted, mirrored event behavior ───────────────────────────────────
-- Each spec describes one event's lifetime; build() instantiates it against
-- a given (EventClass, manager, side log) so both managers run the same
-- script with their own objects. Handler side effects (inserts, clears) are
-- driven by the spec and recorded per side.
local function lcg(seed)
    local s = seed % 2147483647
    if s <= 0 then s = s + 2147483646 end
    return function(n)
        s = (s * 16807) % 2147483647
        return (s % n) + 1
    end
end

local QUEUES = { 'base', 'base', 'base', 'other', 'tutorial' } -- base-heavy
local TRIGGERS = { 'immediate', 'immediate', 'after', 'before', 'condition' }

local spec_n = 0
local function make_spec(rnd, depth)
    spec_n = spec_n + 1
    local spec = {
        id = spec_n,
        trigger = TRIGGERS[rnd(#TRIGGERS)],
        blocking = rnd(3) ~= 1,            -- mostly blocking, like the game
        blockable = rnd(4) ~= 1,
        delay = (rnd(4) - 1) * 0.05,       -- 0 .. 0.15s
        queue = QUEUES[rnd(#QUEUES)],
        front = rnd(6) == 1,
        completes_after = rnd(4) - 1,      -- handler returns true on Nth call
        pause_force = rnd(8) == 1,
        -- side effects on the COMPLETING call:
        spawn_front = rnd(8) == 1 and depth < 3,
        spawn_tail = rnd(8) == 1 and depth < 3,
        clear_queue = rnd(40) == 1,        -- rare but covered
        child_seed = rnd(2 ^ 20),
        depth = depth,
    }
    return spec
end

-- build(spec) -> a constructor closure usable for one side
local function build(spec, Ev, manager, log)
    local calls = 0
    local cfg = {
        trigger = spec.trigger,
        blocking = spec.blocking,
        blockable = spec.blockable,
        delay = spec.delay,
        pause_force = spec.pause_force,
    }
    if spec.trigger == 'condition' then
        -- model a flag that flips true once the handler has been polled
        -- completes_after times; ref must live per-side
        local flag = { done = false }
        cfg.ref_table = flag
        cfg.ref_value = 'done'
        cfg.stop_val = true
        cfg.func = function()
            calls = calls + 1
            log[#log + 1] = spec.id
            if calls > spec.completes_after then flag.done = true end
            return flag.done
        end
    else
        cfg.func = function()
            calls = calls + 1
            log[#log + 1] = spec.id
            local done = calls > spec.completes_after
            if done then
                if spec.spawn_front then
                    local crnd = lcg(spec.child_seed)
                    local child = make_spec_cached(spec, 'front', crnd)
                    manager:add_event(build(child, Ev, manager, log), spec.queue, true)
                end
                if spec.spawn_tail then
                    local crnd = lcg(spec.child_seed + 1)
                    local child = make_spec_cached(spec, 'tail', crnd)
                    manager:add_event(build(child, Ev, manager, log), spec.queue)
                end
                if spec.clear_queue then manager:clear_queue(spec.queue == 'base' and 'other' or 'base') end
            end
            return done
        end
    end
    local ev = Ev(cfg)
    ev.__spec_id = spec.id
    return ev
end

-- children must be the SAME spec object on both sides: cache by parent+slot
local child_cache = {}
function make_spec_cached(parent, slot, rnd)
    local key = parent.id .. slot
    if not child_cache[key] then
        child_cache[key] = make_spec(rnd, parent.depth + 1)
    end
    return child_cache[key]
end

-- ── state comparison ────────────────────────────────────────────────────
local function snapshot(manager)
    local out = {}
    for k, q in pairs(manager.queues) do
        local ids = {}
        for i = 1, #q do
            ids[i] = (q[i].__spec_id or '?') .. (q[i].complete and 'C' or '')
        end
        out[k] = table.concat(ids, ',')
    end
    return out
end

local function compare(a, b)
    for k, v in pairs(a) do
        if b[k] ~= v then return false, k, v, b[k] end
    end
    for k, v in pairs(b) do
        if a[k] ~= v then return false, k, a[k], v end
    end
    return true
end

-- ── soak ────────────────────────────────────────────────────────────────
local fails = 0
for seed = 1, N_SEEDS do
    local rnd = lcg(seed * 7919)
    child_cache = {}
    local log_a, log_b = {}, {}
    local ma, mb = OrigEM(), NewEM()
    -- managers were inited with G.TIMERS.REAL at construction; reset clocks
    G.TIMERS.REAL, G.TIMERS.TOTAL = 0, 0
    ma.queue_timer, ma.queue_last_processed = 0, 0
    mb.queue_timer, mb.queue_last_processed = 0, 0

    for tick = 1, N_TICKS do
        -- mirrored additions
        local n_add = rnd(4) - 1
        for _ = 1, n_add do
            local spec = make_spec(rnd, 0)
            ma:add_event(build(spec, OrigEvent, ma, log_a), spec.queue, spec.front)
            mb:add_event(build(spec, NewEvent, mb, log_b), spec.queue, spec.front)
        end
        -- occasional pause flips and forced updates
        if rnd(10) == 1 then G.SETTINGS.paused = not G.SETTINGS.paused end
        local dt = 1 / 60
        G.TIMERS.REAL = G.TIMERS.REAL + dt
        G.TIMERS.TOTAL = G.TIMERS.TOTAL + dt
        local forced = rnd(12) == 1
        ma:update(dt, forced)
        mb:update(dt, forced)

        local sa, sb = snapshot(ma), snapshot(mb)
        local same, qk, va, vb = compare(sa, sb)
        local la, lb = table.concat(log_a, ','), table.concat(log_b, ',')
        if not same or la ~= lb then
            fails = fails + 1
            print(string.format('DIVERGED seed=%d tick=%d', seed, tick))
            if not same then
                print(string.format('  queue %s:\n    orig: %s\n    new : %s', qk, tostring(va), tostring(vb)))
            end
            if la ~= lb then
                print('  handled-order orig: ' .. la:sub(-160))
                print('  handled-order new : ' .. lb:sub(-160))
            end
            break
        end
    end
    G.SETTINGS.paused = false
end

if fails == 0 then
    print(string.format('EVQ_DIFF: PASS (%d seeds x %d ticks, %d specs built, states + handled-order identical)',
        N_SEEDS, N_TICKS, spec_n))
    os.exit(0)
else
    print(string.format('EVQ_DIFF: FAIL (%d diverging seeds)', fails))
    os.exit(1)
end
