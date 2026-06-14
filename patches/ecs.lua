-- ecs.lua — the composed core. Composition, not inheritance.
--
-- This is the seed of the un-patchification. It does NOT override or wrap any
-- engine function. It establishes three things and then gets out of the way:
--
--   World      — component storage keyed by the EXISTING entity (a Card). We do
--                NOT build a parallel entity store; the Card object IS the entity.
--                Components are split by WHO ALREADY WRITES the underlying field:
--                  * OWNED-NEW    : the core is the sole writer, no legacy writer
--                                   exists (e.g. Active{visible,dirty}). Safe.
--                  * VIEWED-RO    : legacy is the sole writer, the core only reads
--                                   (e.g. VT.{x,y,r,scale}, the welded Sprite DAG).
--                  * CONTESTED    : two legacy writers (e.g. T, written by both
--                                   CardArea:align_cards AND Moveable:move). These
--                                   stay legacy-owned for the whole migration; a
--                                   system may REPLACE a writer with an identical
--                                   one, but never OWN the field.
--
--   Pipeline   — an ordered registry of pure systems. The order is DATA (a sorted
--                list you can query and delete from), not implicit in who-calls-whom.
--                A feature REGISTERS an ordered entry; it never captures-and-rewraps
--                a function reference. Removing a system is one table delete. No
--                system owns the next system's invocation — which is precisely what
--                makes the rewind-crash class (tear-down mid-override-chain) impossible.
--
--   The seam   — the pipeline is driven FROM INSIDE the existing per-frame hot loops
--                (game.lua's `for _,v in pairs(self.MOVEABLES)` move/draw passes and
--                CardArea:align_cards/draw) — loops that SMODS and Cryptid do NOT
--                wrap. It therefore lives BELOW the 5-deep Game:update override stack,
--                not as a 6th wrapper. See Pipeline.run.
--
-- Phase 1 ships this core inert: registered with zero systems, the pipeline is the
-- identity, so behaviour is byte-identical. Systems are added one at a time, each
-- deleting real patch/override surface and each verified by the score oracle
-- (test/score-oracle.sh) plus the active-set-invariant / draw-call-transcript gates.

local ECS = { _VERSION = "0.1.0" }

--==========================================================================
-- World: component tables keyed by entity. Parallel tables (struct-of-arrays
-- in spirit) rather than one fat table per entity, so a system touches only
-- the component it owns and iteration stays cache-coherent once ported native.
--==========================================================================
local World = {}
World.components = {}            -- name -> (entity -> component-data)

function World.component(name)
    local t = World.components[name]
    if not t then t = setmetatable({}, { __mode = "k" }) ; World.components[name] = t end
    return t  -- weak-keyed so a removed Card's components are GC'd with it
end

-- OWNED-NEW: Active{visible, dirty}. Zero legacy writers — the only component the
-- first system touches, which is why it cannot collide with the T/VT writers.
World.Active = World.component("Active")

function World.active(card)
    local a = World.Active[card]
    if not a then a = { visible = true, dirty = true } ; World.Active[card] = a end
    return a
end

--==========================================================================
-- Pipeline: ordered system registry. The anti-override. `order` is explicit
-- data; `register` appends an entry and re-sorts; `run` dispatches in order.
--==========================================================================
local Pipeline = {}
Pipeline.systems = {}            -- phase -> sorted list of {order, key, fn}

-- Register a pure system into a phase. fn signature: fn(collection, dt).
-- Re-registering the same key replaces it (idempotent rebuilds).
function Pipeline.register(spec)
    local phase = assert(spec.phase, "system needs a phase")
    local list = Pipeline.systems[phase]
    if not list then list = {} ; Pipeline.systems[phase] = list end
    for i = #list, 1, -1 do if list[i].key == spec.key then table.remove(list, i) end end
    list[#list + 1] = { order = spec.order or 500, key = assert(spec.key, "system needs a key"), fn = assert(spec.fn) }
    table.sort(list, function(a, b) return a.order < b.order end)
end

function Pipeline.unregister(phase, key)
    local list = Pipeline.systems[phase]
    if not list then return end
    for i = #list, 1, -1 do if list[i].key == key then table.remove(list, i) end end
end

-- Drive a phase over a collection. With zero registered systems this is a no-op,
-- so the seam can be installed and ship inert (identity) before any system lands.
function Pipeline.run(phase, collection, dt)
    local list = Pipeline.systems[phase]
    if not list then return false end           -- nothing registered: caller runs legacy path
    for i = 1, #list do list[i].fn(collection, dt) end
    return true
end

function Pipeline.has(phase)
    local list = Pipeline.systems[phase]
    return list ~= nil and #list > 0
end

ECS.World = World
ECS.Pipeline = Pipeline

-- Published on G so the seam (a single guarded line in game.lua) and future
-- systems reach it without any global-function monkeypatching.
G = G or {}
G.ECS = ECS

return ECS
