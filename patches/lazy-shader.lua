-- LAZY_SHADER (Tier-2a, v1): Balatro's sprite pipeline brackets every
-- sprite draw with setShader(S) ... setShader() (engine/sprite.lua:123/:131),
-- so consecutive sprites that all use the same shader still ping-pong
-- S -> default -> S at the GPU — ~830 binds/frame at blind select, ~375
-- steady (SHSW telemetry). Make binding lazy: setShader records the wanted
-- shader, and the actual GPU bind happens just before the next draw call,
-- only when it differs from what is bound. Identical draw-time semantics
-- (every draw still runs under exactly the shader the game asked for);
-- pipeline switches collapse from 2-per-sprite to one per shader CHANGE in
-- draw order.
--
-- Loaded from main.lua BEFORE android-telemetry.lua on purpose: telemetry's
-- setShader wrapper then counts game-issued calls while our flush talks to
-- the captured original — its real-bind count is exposed as
-- LAZY_SHADER.binds for the PERF_SNAPSHOT (calls vs binds = elision win).
--
-- Kill switch: G.SETTINGS.lazy_shader = false (default on), re-read once
-- per frame in the love.draw wrap; flipping it resyncs the GPU state.

local LS = {
    enabled = false,   -- armed in the love.draw wrap once G.SETTINGS exists
    want = nil, want2 = nil,
    bound = nil, bound2 = nil,
    binds = 0, calls = 0,   -- lifetime; telemetry drains via _last marks
}
LAZY_SHADER = LS

local lg = love.graphics
local _setShader = lg.setShader

local function flush()
    if LS.want ~= LS.bound or LS.want2 ~= LS.bound2 then
        _setShader(LS.want, LS.want2)
        LS.bound, LS.bound2 = LS.want, LS.want2
        LS.binds = LS.binds + 1
    end
end

lg.setShader = function(s, s2)
    if not LS.enabled then return _setShader(s, s2) end
    LS.calls = LS.calls + 1
    LS.want, LS.want2 = s, s2
end

-- the game must observe the shader it asked for, bound or not
local _getShader = lg.getShader
lg.getShader = function()
    if LS.enabled then return LS.want end
    return _getShader()
end

-- every draw-issuing love.graphics function flushes the pending bind first
for _, fname in ipairs({ 'draw', 'drawInstanced', 'drawLayer', 'print',
        'printf', 'rectangle', 'circle', 'ellipse', 'line', 'polygon',
        'points', 'arc' }) do
    local f = lg[fname]
    if f then
        lg[fname] = function(...)
            if LS.enabled then flush() end
            return f(...)
        end
    end
end

-- per-frame gate refresh + GPU resync on toggle flips
local _draw = love.draw
function love.draw(...)
    local want_on = not (G and G.SETTINGS and G.SETTINGS.lazy_shader == false)
    if want_on ~= LS.enabled then
        if LS.enabled then
            -- turning OFF: make the GPU match the last requested state, then
            -- pass-through mode keeps it in sync
            _setShader(LS.want, LS.want2)
        else
            -- turning ON: adopt the actual current binding as ground truth
            LS.want, LS.want2 = _getShader(), nil
            LS.bound, LS.bound2 = LS.want, nil
        end
        LS.enabled = want_on
    end
    return _draw(...)
end
