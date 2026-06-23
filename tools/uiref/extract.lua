-- UIBox tree extractor: stand up the MINIMAL stubs create_UIBox_HUD needs, run the REAL vanilla
-- function, and serialize its node tree to JSON with binding descriptors (colour names, ref paths,
-- localize keys, DynaText bindings, sprite/moveable tags). No layout, no LÖVE — just capture the
-- structure so the Kotlin interpreter renders Balatro's ACTUAL HUD tree, not a hand-transcription.
--
-- Run:  lua tools/uiref/extract.lua   (writes tools/uiref/hud_tree.json)

-- identity maps: a live reference (colour array, G.GAME subtable) -> its descriptor name/path
local colourName, pathName = {}, {}

-- G.UIT node-type constants + reverse name map
local UIT = { T=1, B=2, C=3, R=4, O=5, ROOT=7, S=8, I=9 }
local uitName = {}; for k,v in pairs(UIT) do uitName[v] = k end

local function hx(s) return { tonumber(s:sub(1,2),16)/255, tonumber(s:sub(3,4),16)/255, tonumber(s:sub(5,6),16)/255, 1 } end
local function leaf(name, arr) colourName[arr] = name; return arr end

-- G.C palette (only what the HUD reads), each leaf tagged by dotted name
local C = {
  WHITE = leaf("WHITE",{1,1,1,1}), BLACK = leaf("BLACK",hx("374244")),
  L_BLACK = leaf("L_BLACK",hx("4f6367")), CLEAR = leaf("CLEAR",{0,0,0,0}),
  BLUE = leaf("BLUE",hx("009dff")), RED = leaf("RED",hx("fe5f55")),
  MONEY = leaf("MONEY",hx("f3b958")), GOLD = leaf("GOLD",hx("eac058")),
  IMPORTANT = leaf("IMPORTANT",hx("ff9a00")), GREEN = leaf("GREEN",hx("4bc292")),
  -- ORANGE (#FDA200) is the Options button fill (UI_definitions.lua:1536, colour=G.C.ORANGE); without
  -- it registered the options button's colour was dropped from the tree and rendered as bare text.
  ORANGE = leaf("ORANGE",hx("fda200")), FILTER = leaf("FILTER",hx("ff9a00")),
  CHIPS = leaf("CHIPS",hx("009dff")), MULT = leaf("MULT",hx("fe5f55")),
  -- globals.lua:475 assigns G.C.UI_CHIPS = BLUE, G.C.UI_MULT = RED at runtime (outside the C literal),
  -- so the chips/mult readout boxes get their fill. Without these the extracted colours were nil.
  UI_CHIPS = leaf("UI_CHIPS",hx("009dff")), UI_MULT = leaf("UI_MULT",hx("fe5f55")),
  UI = { TEXT_LIGHT = leaf("UI.TEXT_LIGHT",{1,1,1,1}), TEXT_DARK = leaf("UI.TEXT_DARK",hx("4f6367")),
         TRANSPARENT_DARK = leaf("UI.TRANSPARENT_DARK",{0.18,0.22,0.25,0.3}) },
  DYN_UI = { MAIN=leaf("DYN_UI.MAIN",hx("374244")), DARK=leaf("DYN_UI.DARK",hx("374244")),
             BOSS_MAIN=leaf("DYN_UI.BOSS_MAIN",hx("374244")), BOSS_DARK=leaf("DYN_UI.BOSS_DARK",hx("374244")) },
}

-- path-tagged G.GAME subtables (so ref_table=G.GAME.current_round serializes to that path)
local function tag(path, fields) local t = fields or {}; pathName[t] = path; return t end
local GAME = tag("G.GAME", { dollars=0, round=1, win_ante=8, stake=1 })
GAME.current_round = tag("G.GAME.current_round", { hands_left=4, discards_left=3 })
GAME.round_resets  = tag("G.GAME.round_resets",  { ante=1 })

-- capture-stubs: leaf builders return tagged tables instead of constructing real engine objects
function localize(a) return { __loc = a } end
function DynaText(args) return { __dynatext = args } end
function Moveable(...) return { __moveable = true } end
function get_stake_sprite(stake, scale) return { __sprite = "stake", scale = scale } end
-- colour ops: capture the operation (Kotlin recomputes identically) rather than a raw hex
function darken(c, amt)       return { __colorop="darken",  base=c, amt=amt } end
function lighten(c, amt)      return { __colorop="lighten", base=c, amt=amt } end
function mix_colours(a, b, t) return { __colorop="mix",     a=a, b=b, amt=t } end
function adjust_alpha(c, a)   return { __colorop="alpha",   base=c, amt=a } end

-- shop/blind extraction stubs: capture CardArea slots (the O-node objects) with their size + config;
-- other engine objects (sprites, separate floating UIBoxes, events) are decorative/non-structural here.
function CardArea(x, y, w, h, config) return { __cardarea=true, T={x=x,y=y,w=w,h=h}, w=w, h=h, config=config or {} } end
function AnimatedSprite(...) return { __sprite="animated", define_draw_steps=function() end } end
function Sprite(...)         return { __sprite="sprite",   define_draw_steps=function() end } end
function UIBox(args)         return { __uibox=true } end   -- e.g. G.SHOP_SIGN — a separate floating box, not in the returned tree
function Event(args)         return { __event=true } end

-- GAME fields the shop reads
GAME.shop = tag("G.GAME.shop", { joker_max=2 })
GAME.current_round.reroll_cost = 5
GAME.current_round.voucher = "v_blank"

G = {
  UIT = UIT, C = C, GAME = GAME,
  LANGUAGES = { ["en-us"] = { font = { __font = "en-us" } } },
  UIDEF = {},
  -- areas/positions the shop def references
  hand = { T = { x=4.857, y=6.986, w=12.293, h=2.614 } },
  ROOM = { T = { x=1.44, y=0.69, w=20, h=11.5 } },
  CARD_W = 2.04878, CARD_H = 2.75122,
  ANIMATION_ATLAS = setmetatable({}, { __index=function() return {} end }),
  E_MANAGER = { add_event = function() end },
  HUD = { get_UIE_by_ID = function() return {} end },
}

-- load the REAL vanilla create_UIBox_HUD (top-level is just G.UIDEF={}; the rest are fn defs)
dofile((arg[0]:gsub("extract%.lua$","")) .. "UI_definitions.vanilla.lua")
local def = create_UIBox_HUD()

-- ---- serialize ----
local function isarray(t) local n=0; for _ in pairs(t) do n=n+1 end; return n == #t and n > 0 end

local enc  -- forward
local function encconfig(cfg)
  -- a node's config table: map each key to a clean descriptor
  local parts = {}
  for k, v in pairs(cfg) do
    if k ~= "nodes" then
      local d
      if colourName[v] then d = '{"$":"colour","name":"'..colourName[v]..'"}'
      elseif pathName[v]  then d = '{"$":"ref","path":"'..pathName[v]..'"}'
      elseif type(v)=="table" and v.__loc ~= nil then d = '{"$":"loc","key":'..enc(v.__loc)..'}'
      elseif type(v)=="table" and v.__sprite then d = '{"$":"sprite","name":"'..v.__sprite..'","scale":'..tostring(v.scale)..'}'
      elseif type(v)=="table" and v.__moveable then d = '{"$":"moveable"}'
      elseif type(v)=="table" and v.__cardarea then d = '{"$":"cardarea","name":"'..(v.__name or "?")..'","w":'..tostring(v.w)..',"h":'..tostring(v.h)..',"limit":'..tostring(v.config and v.config.card_limit or 0)..'}'
      elseif type(v)=="table" and v.__colorop then d = enccolorop(v)
      elseif type(v)=="table" and v.__dynatext then d = '{"$":"dynatext",'..encdyna(v.__dynatext)..'}'
      else d = enc(v) end
      parts[#parts+1] = enc(k)..':'..d
    end
  end
  return "{"..table.concat(parts,",").."}"
end

function encdyna(a)
  local segs = {}
  for _, s in ipairs(a.string or {}) do
    if type(s) == "table" then
      local b = {}
      if s.ref_table then b[#b+1] = '"ref":"'..(pathName[s.ref_table] or "?")..'"' end
      if s.ref_value then b[#b+1] = '"value":'..enc(s.ref_value) end
      if s.prefix then b[#b+1] = '"prefix":'..(type(s.prefix)=="table" and s.prefix.__loc and ('{"loc":'..enc(s.prefix.__loc)..'}') or enc(s.prefix)) end
      if s.string then b[#b+1] = '"text":'..enc(s.string) end
      segs[#segs+1] = "{"..table.concat(b,",").."}"
    else segs[#segs+1] = '{"text":'..enc(s)..'}' end
  end
  local cols = {}
  for _, c in ipairs(a.colours or {}) do cols[#cols+1] = '"'..(colourName[c] or "?")..'"' end
  -- spacing & maxw affect DynaText's own measured W (text.lua:152 adds 2.7*spacing per letter;
  -- maxw scales the whole string down if it overflows). Both are geometry inputs, so capture them.
  local extra = ""
  if a.spacing then extra = extra..',"spacing":'..tostring(a.spacing) end
  if a.maxw then extra = extra..',"maxw":'..tostring(a.maxw) end
  return '"segs":['..table.concat(segs,",")..'],"colours":['..table.concat(cols,",")..'],"scale":'..tostring(a.scale or 1)..',"shadow":'..tostring(a.shadow or false)..extra
end

-- resolve a colour value to a descriptor (named palette colour, nested colour-op, or fallback)
function colref(c)
  if colourName[c] then return '{"$":"colour","name":"'..colourName[c]..'"}'
  elseif type(c)=="table" and c.__colorop then return enccolorop(c)
  else return enc(c) end
end
function enccolorop(v)
  if v.__colorop == "mix" then
    return '{"$":"colourop","op":"mix","a":'..colref(v.a)..',"b":'..colref(v.b)..',"amt":'..tostring(v.amt)..'}'
  end
  return '{"$":"colourop","op":"'..v.__colorop..'","base":'..colref(v.base)..',"amt":'..tostring(v.amt)..'}'
end

local function encnode(n)
  local parts = { '"n":"'..(uitName[n.n] or tostring(n.n))..'"' }
  if n.config then parts[#parts+1] = '"config":'..encconfig(n.config) end
  if n.nodes and #n.nodes > 0 then
    local kids = {}
    for _, ch in ipairs(n.nodes) do kids[#kids+1] = encnode(ch) end
    parts[#parts+1] = '"nodes":['..table.concat(kids,",")..']'
  end
  return "{"..table.concat(parts,",").."}"
end

enc = function(v)
  local t = type(v)
  if t == "string" then return '"'..v:gsub('[\\"]','\\%0'):gsub("\n","\\n")..'"'
  elseif t == "number" then return tostring(v)
  elseif t == "boolean" then return tostring(v)
  elseif t == "nil" then return "null"
  elseif t == "table" then
    if isarray(v) then local a={}; for _,e in ipairs(v) do a[#a+1]=enc(e) end; return "["..table.concat(a,",").."]" end
    local a={}; for k,e in pairs(v) do a[#a+1]=enc(tostring(k))..":"..enc(e) end; return "{"..table.concat(a,",").."}"
  end
  return '"?'..t..'"'
end

local function dump(node, name)
  local json = encnode(node)
  local outpath = (arg[0]:gsub("extract%.lua$","")) .. name
  local f = io.open(outpath, "w"); f:write(json); f:close()
  print("wrote "..outpath.." ("..#json.." bytes)")
end
dump(def, "hud_tree.json")

-- SHOP: run the REAL G.UIDEF.shop(), tag the 3 CardArea slots by name, dump the frame tree.
local shopdef = G.UIDEF.shop()
G.shop_jokers.__name   = "shop_jokers"
G.shop_vouchers.__name = "shop_vouchers"
G.shop_booster.__name  = "shop_booster"
dump(shopdef, "shop_tree.json")
