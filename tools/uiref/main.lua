-- Headless reference renderer: stands up Balatro's REAL UIBox engine in isolation and renders
-- a tree to a PNG, so the native interpreter can be diffed against the actual game's layout.
-- Run:  xvfb-run -a love tools/uiref  (writes uiref.png)

local function hx(s)
    return { tonumber(s:sub(1,2),16)/255, tonumber(s:sub(3,4),16)/255, tonumber(s:sub(5,6),16)/255, 1 }
end

-- The minimal G the engine (object/node/moveable/ui) actually touches.
G = {
    UIT = { T=1, B=2, C=3, R=4, O=5, ROOT=7, S=8, I=9, padding=0 },
    TILESIZE = 20, TILESCALE = 3.65,
    SETTINGS = { paused = false, reduced_motion = true, GRAPHICS = { shadows = "Off" } },
    FRAMES = { MOVE = 1 },
    TIMERS = { REAL = 0, TOTAL = 0, UPTIME = 0 },
    exp_times = { xy = 0, scale = 0, r = 0, max_vel = 1 },
    ROOM = { T = { x = 0, y = 0, w = 24, h = 38, r = 0, scale = 1 } },
    MOVEABLES = {}, I = { MOVEABLE = {}, NODE = {}, UIBOX = {}, CARD = {}, SPRITE = {} },
    ID = 1, COLLISION_BUFFER = {}, CONTROLLER = nil, DEBUG = false,
    STAGE = 1, STAGE_OBJECTS = {{}}, under_overlay = nil, REFRESH_FRAME_MAJOR_CACHE = 0,
    FUNCS = {}, ARGS = {},
}
G.C = {
    WHITE = {1,1,1,1}, BLACK = hx("374244"), L_BLACK = hx("4f6367"), CLEAR = {0,0,0,0},
    BLUE = hx("009dff"), RED = hx("fe5f55"), MONEY = hx("f3b958"), GOLD = hx("eac058"),
    IMPORTANT = hx("ff9a00"), GREEN = hx("4bc292"), CHIPS = hx("009dff"), MULT = hx("fe5f55"),
    UI = { TEXT_LIGHT = {1,1,1,1}, TEXT_DARK = hx("4f6367"), BACKGROUND_WHITE = {1,1,1,1} },
}
local FELT = hx("234c44"); local PANEL = G.C.BLACK; local INSET = hx("1b3a34")

-- DynaText measurement helpers (base-game functions/misc_functions.lua + format_ui_value).
-- For ASCII HUD values these are exact; numeric formatting (number_format) isn't exercised
-- by the HUD's pre-formatted string values, but is stubbed faithfully for completeness.
function EMPTY(t) if not t then return {} end for k in pairs(t) do t[k] = nil end return t end
function number_format(n) return tostring(n) end
function format_ui_value(value) if type(value) ~= "number" then return tostring(value) end return number_format(value) end
utf8 = utf8 or {}
utf8.chars = function(s) return coroutine.wrap(function() for i = 1, #s do coroutine.yield(i, s:sub(i, i)) end end) end

require("engine/object")
require("engine/node")
require("engine/moveable")
require("engine/sprite")
require("engine/text")     -- DynaText: the O-node object the real HUD counters embed
require("engine/ui")

-- ---- FULL-HUD stubs (USE_FULL_HUD=1): enough of G.GAME / G.C / the leaf builders that the REAL
-- vanilla create_UIBox_HUD lays out through the real engine. Colours/values don't affect geometry;
-- only the text STRINGS and node minw/minh/padding/scale do, so those are realistic.
G.C.DYN_UI = { MAIN = G.C.BLACK, DARK = G.C.BLACK, BOSS_MAIN = G.C.BLACK, BOSS_DARK = G.C.BLACK }
G.C.UI.TRANSPARENT_DARK = {0,0,0,0.3}
G.C.UI_CHIPS = hx("009dff"); G.C.UI_MULT = hx("fe5f55"); G.C.ORANGE = hx("fda200")
G.GAME = {
  stake = 1, dollars = 4, round = 1, win_ante = 8, chips_text = "300",
  current_round = { hands_left = 4, discards_left = 3,
    current_hand = { handname_text = "", chip_total_text = "", hand_level = "", chip_text = "0", mult_text = "0" } },
  round_resets = { ante = 1, ante_disp = 1 },
}
function localize(key)
  local m = { k_hud_hands="Hands", k_hud_discards="Discards", k_ante="Ante", k_round="Round",
              k_lower_score="score", b_run_info_1="Run", b_run_info_2="Info", b_options="Options", ["$"]="$" }
  if type(key) == "table" then return "" end
  return m[key] or tostring(key)
end
function get_stake_sprite(stake, scale) return Moveable(0, 0, scale or 0.5, scale or 0.5) end
function scale_number(n, scale) return scale or 1 end
function darken(c) return c end
-- calculate_xywh fires node.config.func (text-update callbacks) during layout; they're runtime-only
-- and irrelevant to geometry, so any G.FUNCS lookup returns a harmless no-op. Likewise set_values
-- registers focusable buttons with G.CONTROLLER — irrelevant to geometry, so stub it permissively.
G.FUNCS = setmetatable({}, { __index = function() return function() end end })
G.CONTROLLER = setmetatable({}, { __index = function() return function() end end })

local uibox

-- a HUD stat box, in Balatro's node format (the real create_UIBox_HUD structure). The real game
-- wraps the value in an O/DynaText ({n=UIT.O, object=DynaText({ref_table=…})}); the headless
-- harness was confirmed (set USE_DYNATEXT=1) to lay out that O node at byte-identical w/h to this
-- self-measuring T value node — so the tracked reference keeps the simpler T form, and the native
-- interpreter's O-sizing (object.T.w) is validated against the real engine via that toggle.
local USE_DYNATEXT = os.getenv("USE_DYNATEXT") == "1"
local function value_node(value, colour)
    if USE_DYNATEXT then
        return { n=G.UIT.O, config={ object = DynaText({ string = {value}, colours = {colour}, scale = 0.8, shadow = true }) } }
    end
    return { n=G.UIT.T, config={ text=value, scale=0.8, colour=colour } }
end

-- Sprite O probe (USE_SPRITE_O=1): the real HUD stake/blind sprites are O nodes with EXPLICIT
-- config.w/h (UI_definitions.lua:1238 `w=0.5,h=0.5`). The O branch sizes via `config.w or
-- object.T.w` (ui.lua:131), so config.w wins and the atlas isn't consulted for layout. The stub
-- object exposes a DIFFERENT T.w/h (0.9) so the dump proves config.w (0.5) takes precedence —
-- exactly what the native interpreter's objSize encodes (`if cfg.minw>0 cfg.minw else obj.w`).
local USE_SPRITE_O = os.getenv("USE_SPRITE_O") == "1"
local function sprite_o()
    -- A real Moveable (extends the same base as Sprite, has set_role/T) sized to a DIFFERENT
    -- intrinsic 0.9x0.9 (UI_definitions.lua:1350 uses Moveable() for placeholder O objects). The
    -- node sets explicit config.w/h=0.5, so the dump must show 0.5x0.5 — proving config.w wins over
    -- object.T.w, the same precedence the native interpreter's objSize encodes.
    local obj = Moveable(0, 0, 0.9, 0.9)
    return { n=G.UIT.O, config={ w = 0.5, h = 0.5, object = obj } }
end
local function stat(label, value, colour)
    return { n=G.UIT.C, config={ align="cm", padding=0.05, minw=1.45, minh=1, colour=PANEL, r=0.1, emboss=0.05 }, nodes={
        { n=G.UIT.R, config={ align="cm", minh=0.33, maxw=1.35 }, nodes={ { n=G.UIT.T, config={ text=label, scale=0.34, colour=G.C.UI.TEXT_LIGHT } } } },
        { n=G.UIT.R, config={ align="cm", r=0.1, minw=1.2, colour=INSET }, nodes={ value_node(value, colour) } },
    } }
end

function love.load()
    local font = love.graphics.newFont("m6x11plus.ttf", G.TILESIZE * 10)   -- render_scale 200
    G.LANG = { font = { FONT = font, squish = 1, FONTSCALE = 0.1, TEXT_HEIGHT_SCALE = 0.83, TEXT_OFFSET = {x=10,y=-20} } }
    G.FONTS = { G.LANG.font }
    G.LANGUAGES = { ["en-us"] = G.LANG }

    local rows = {
        { n=G.UIT.R, config={ align="cm" }, nodes={ stat("Hands","4",G.C.BLUE), { n=G.UIT.C, config={ minw=0.13 }, nodes={} }, stat("Discards","3",G.C.RED) } },
        { n=G.UIT.R, config={ minh=0.13 }, nodes={} },
        { n=G.UIT.R, config={ align="cm" }, nodes={ stat("Money","$4",G.C.MONEY) } },
        { n=G.UIT.R, config={ minh=0.13 }, nodes={} },
        { n=G.UIT.R, config={ align="cm" }, nodes={ stat("Ante","1/8",G.C.IMPORTANT), { n=G.UIT.C, config={ minw=0.13 }, nodes={} }, stat("Round","1",G.C.IMPORTANT) } },
    }
    if USE_SPRITE_O then rows[#rows+1] = { n=G.UIT.R, config={ align="cm" }, nodes={ sprite_o() } } end
    local def = { n=G.UIT.ROOT, config={ align="cm", colour=FELT, padding=0.1, r=0.1 }, nodes=rows }

    -- USE_FULL_HUD=1: lay out the REAL, COMPLETE create_UIBox_HUD (vanilla — Cryptid doesn't touch
    -- the HUD structure) so the dump is the authoritative full-sidebar geometry, not just the stats column.
    if os.getenv("USE_FULL_HUD") == "1" then
        dofile("UI_definitions.vanilla.lua")
        def = create_UIBox_HUD()
    end

    uibox = UIBox{ T = { x = 1, y = 1, w = 0, h = 0 }, definition = def, config = {} }
    uibox:recalculate()   -- run calculate_xywh -> each node's T is the computed geometry

    -- Dump the LAID-OUT geometry: the layout reference (sidesteps the draw machinery entirely).
    local out = {}
    local function dump(node, depth)
        local c = node.config or {}
        local label = c.text or c.id or ""
        out[#out+1] = string.format("%s%s  x=%.4f y=%.4f w=%.4f h=%.4f  %s",
            string.rep("| ", depth), tostring(node.UIT), node.T.x, node.T.y, node.T.w, node.T.h, label)
        if node.children then for _, ch in ipairs(node.children) do dump(ch, depth + 1) end end
    end
    dump(uibox.UIRoot, 0)
    print("=== UIBOX GEOMETRY (units) ===\n" .. table.concat(out, "\n"))
    love.event.quit()
end

function love.draw() end
