-- Controller gesture harness: loads the REAL built engine/controller.lua (with
-- all our build-time patches: TAP_DESC_*, DRAG_SELECT_*, uptime fix) under a
-- stub environment and drives it with scripted touch gestures, so tap/hold/
-- drag/slide behaviour is testable in milliseconds on this machine instead of
-- on the phone. Pure LuaJIT — no LÖVE, no display.
--
-- Coordinate model: G.TILESCALE*G.TILESIZE = 1, so screen px == tile units.
-- Real tuning constants (MIN_CLICK_DIST=0.9, MIN_HOVER_TIME=0.1) are kept.

local M = {}

local GAME_DIR = os.getenv('BALATRO_GAME_DIR') or 'build/game'

-- ---------------------------------------------------------------- love stub
local function make_love(world)
    return {
        system = { getOS = function() return 'Android' end },
        mouse = {
            getPosition = function() return world.mx, world.my end,
            setVisible = function() end,
        },
        touch = { getTouches = function() return world.touches end },
        timer = { getTime = function() return world.G.TIMERS.UPTIME end },
        keyboard = { isDown = function() return false end },
    }
end

-- ------------------------------------------------------------- fake objects
local next_id = 1000
local function recorder(node, name)
    return function(self, ...)
        node.calls[name] = (node.calls[name] or 0) + 1
        node.last_args = { ... }
    end
end

-- A fake Node/Card: carries exactly the state surface controller.lua touches.
function M.make_node(world, opts)
    next_id = next_id + 1
    local n = {
        ID = next_id,
        T = { x = opts.x, y = opts.y, w = opts.w or 1.4, h = opts.h or 1.9, r = 0, scale = 1 },
        states = {
            hover   = { can = (opts.hover_can ~= false), is = false },
            click   = { can = (opts.click_can ~= false), is = false },
            drag    = { can = (opts.drag_can ~= false), is = false },
            collide = { can = (opts.collide_can ~= false), is = false },
            release_on = { can = (opts.release_on_can ~= false), is = false },
            focus   = { can = false, is = false },
            visible = true,
        },
        area = opts.area,
        highlighted = false,
        click_timeout = 0.3, -- matches Card:init (card.lua:60)
        calls = {},
        children = {},
        REMOVED = false,
    }
    n.collides_with_point = function(self, pt)
        return pt.x >= self.T.x and pt.x <= self.T.x + self.T.w
           and pt.y >= self.T.y and pt.y <= self.T.y + self.T.h
    end
    n.set_offset  = recorder(n, 'set_offset')
    n.hover       = recorder(n, 'hover')
    n.stop_hover  = recorder(n, 'stop_hover')
    n.stop_drag   = recorder(n, 'stop_drag')
    n.drag        = recorder(n, 'drag')
    n.juice_up    = recorder(n, 'juice_up')
    n.click = function(self)
        self.calls.click = (self.calls.click or 0) + 1
        if self.area and self.area.config.type == 'hand' then
            if self.highlighted then self.area:remove_from_highlighted(self)
            else self.area:add_to_highlighted(self) end
        end
    end
    n.release = function(self, dragged)
        n.calls.release = (n.calls.release or 0) + 1
        n.released_with = dragged
    end
    n.can_drag = function(self) return self.states.drag.can and self or nil end
    table.insert(world.nodes, n)
    return n
end

function M.make_area(world, kind, limit)
    local area
    area = {
        config = { type = kind, highlighted_limit = limit or 5, card_limit = 8 },
        cards = {}, highlighted = {},
        add_to_highlighted = function(self, card, silent)
            if #self.highlighted >= self.config.highlighted_limit then return end
            self.highlighted[#self.highlighted + 1] = card
            card.highlighted = true
        end,
        remove_from_highlighted = function(self, card)
            for i, c in ipairs(self.highlighted) do
                if c == card then table.remove(self.highlighted, i) break end
            end
            card.highlighted = false
        end,
    }
    return area
end

-- --------------------------------------------------------------- the world
function M.new_world(opts)
    opts = opts or {}
    local world = { mx = 0, my = 0, touches = {}, nodes = {}, time = 0 }

    -- fresh global env for each world: the controller module writes globals
    local G = {
        TIMERS = { TOTAL = 0, REAL = 0, REAL_SHADER = 0, UPTIME = 0, BACKGROUND = 0 },
        TILESCALE = 1, TILESIZE = 1,
        TILE_W = 20, TILE_H = 11.5, DRAW_HASH_BUFF = 2,
        MIN_CLICK_DIST = 0.9, MIN_HOVER_TIME = 0.1,
        SPEEDFACTOR = 1, DEADZONE = 0.2, VIBRATION = 0,
        SETTINGS = { paused = false, GAMESPEED = 1,
                     enable_drag_select = (opts.enable_drag_select ~= false) },
        STAGES = { MAIN_MENU = 1, RUN = 2, SANDBOX = 3 }, STAGE = 2,
        STATES = { SELECTING_HAND = 1, HAND_PLAYED = 2, DRAW_TO_HAND = 3, GAME_OVER = 4,
                   SHOP = 5, PLAY_TAROT = 6, BLIND_SELECT = 7, ROUND_EVAL = 8, MENU = 11,
                   SPLASH = 13, NEW_ROUND = 19 },
        STATE = 1,
        FUNCS = {}, GAME = { STOP_USE = 0 }, ARGS = {}, PROFILES = {},
        I = { NODE = {}, MOVEABLE = {}, SPRITE = {}, UIBOX = {}, POPUP = {},
              CARD = {}, CARDAREA = {}, ALERT = {} },
        MOVEABLES = {}, DRAW_HASH = {},
        CURSOR = { T = { x = 0, y = 0 }, VT = { x = 0, y = 0 }, states = { visible = false } },
        OVERLAY_MENU = nil, SAVED_GAME = nil, ACTIVE_MOD_UI = nil,
        ASSET_ATLAS = {}, P_CENTER_POOLS = {}, DEBUG = false, F_PS = false,
        screenwipe = nil,
    }
    world.G = G

    -- minimal event manager: enough for the controller's 'after' events
    local pending = {}
    G.E_MANAGER = {
        add_event = function(self, e)
            local cfg = e.config
            pending[#pending + 1] = {
                due = G.TIMERS[cfg.timer or 'TOTAL'] + (cfg.delay or 0),
                timer = cfg.timer or 'TOTAL',
                func = cfg.func,
            }
        end,
        tick = function(self)
            local i = 1
            while i <= #pending do
                local e = pending[i]
                if G.TIMERS[e.timer] >= e.due then
                    e.func()
                    table.remove(pending, i)
                else i = i + 1 end
            end
        end,
    }

    -- areas + room
    world.hand = M.make_area(world, 'hand', opts.highlighted_limit or 5)
    world.jokers = M.make_area(world, 'joker', 5)
    G.hand, G.jokers = world.hand, world.jokers
    G.play = M.make_area(world, 'play', 5)

    local room = M.make_node(world, { x = 0, y = 0, w = 20, h = 11.5,
        hover_can = false, click_can = false, drag_can = false, collide_can = false })
    room.collides_with_point = function() return false end
    G.ROOM = room
    G.ROOM_ATTACH = room

    -- ------------------------------------------------- load the real module
    local env_love = make_love(world)
    -- the controller module reads/writes real globals; install ours
    _G.G = G
    _G.love = env_love
    _G.EMPTY = function(t)
        if not t then return {} end
        for k in pairs(t) do t[k] = nil end
        return t
    end
    _G.Vector_Dist = function(a, b)
        local dx, dy = a.x - b.x, a.y - b.y
        return math.sqrt(dx * dx + dy * dy)
    end
    _G.Event = function(cfg) return { config = cfg } end
    _G.create_drag_target_from_card = function(card)
        world.drag_target_created = card
        return nil
    end
    _G.Card = setmetatable({}, { __tostring = function() return 'FakeCardClass' end })
    _G.CardArea = setmetatable({}, { __tostring = function() return 'FakeCardAreaClass' end })

    if not _G.Object then
        dofile(GAME_DIR .. '/engine/object.lua')
    end
    dofile(GAME_DIR .. '/engine/controller.lua')

    local ctrl = Controller()
    G.CONTROLLER = ctrl
    ctrl:set_HID_flags('touch')
    world.ctrl = ctrl

    -- ----------------------------------------------------------- the driver
    function world.frame(dt)
        dt = dt or 1 / 60
        world.time = world.time + dt
        G.TIMERS.UPTIME = G.TIMERS.UPTIME + dt
        G.TIMERS.REAL = G.TIMERS.REAL + dt
        G.TIMERS.TOTAL = G.TIMERS.TOTAL + dt * G.SPEEDFACTOR
        -- nodes register into the draw hash each frame (normally during draw)
        G.DRAW_HASH = {}
        for _, n in ipairs(world.nodes) do
            if n.states.collide.can then G.DRAW_HASH[#G.DRAW_HASH + 1] = n end
        end
        G.E_MANAGER:tick()
        ctrl:update(dt)
    end

    function world.frames(n, dt) for _ = 1, n do world.frame(dt) end end

    -- mirrors love.mousepressed(x, y, 1, true) on device
    function world.touch_down(x, y)
        world.mx, world.my = x, y
        world.touches = { 1 }
        ctrl:set_HID_flags('touch')
        ctrl:queue_L_cursor_press(x, y)
        world.frame()
    end

    function world.touch_move(x, y, steps)
        steps = steps or 1
        local sx, sy = world.mx, world.my
        for i = 1, steps do
            world.mx = sx + (x - sx) * i / steps
            world.my = sy + (y - sy) * i / steps
            world.frame()
        end
    end

    -- mirrors love.mousereleased(x, y, 1, istouch) on device.
    -- HID_ISTOUCH_RELEASE_FIX: set_HID_flags must fire before L_cursor_release so
    -- TAP_DESC_HOLD_NODRAG and TAP_DESC_RELAX see HID.touch=true on the release frame.
    function world.touch_up(x, y)
        x, y = x or world.mx, y or world.my
        world.mx, world.my = x, y
        ctrl:set_HID_flags('touch')
        ctrl:L_cursor_release(x, y)
        world.touches = {}
        world.frame()
    end

    return world
end

-- ---------------------------------------------------------------- asserts
local failures = {}
local current_test = '?'

function M.test(name, fn)
    current_test = name
    local ok, err = pcall(fn)
    if ok then
        io.write('  PASS  ', name, '\n')
    else
        io.write('  FAIL  ', name, '\n        ', tostring(err), '\n')
        failures[#failures + 1] = name
    end
end

function M.check(cond, msg)
    if not cond then error(msg or 'assertion failed', 2) end
end

function M.finish()
    if #failures > 0 then
        io.write(('\n%d test(s) FAILED\n'):format(#failures))
        os.exit(1)
    end
    io.write('\nall tests passed\n')
    os.exit(0)
end

return M
