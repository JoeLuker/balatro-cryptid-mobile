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
  -- blind-select needs GREY for the inactive select button, and BACKGROUND_INACTIVE for skip button
  GREY = leaf("GREY",hx("666666")),
  -- win/game-over screen colours
  EDITION   = leaf("EDITION",hx("8b73eb")),   -- shimmer purple (ph_you_win DynaText)
  JOKER_GREY = leaf("JOKER_GREY",hx("595959")), -- score-row background
  CHANCE    = leaf("CHANCE",hx("4bc292")),      -- blind extras DynaText
  PURPLE    = leaf("PURPLE",hx("8867a5")),      -- consumable accent
  UI = { TEXT_LIGHT = leaf("UI.TEXT_LIGHT",{1,1,1,1}), TEXT_DARK = leaf("UI.TEXT_DARK",hx("4f6367")),
         TRANSPARENT_DARK = leaf("UI.TRANSPARENT_DARK",{0.18,0.22,0.25,0.3}),
         BACKGROUND_INACTIVE = leaf("UI.BACKGROUND_INACTIVE",hx("666666")),
         TEXT_INACTIVE = leaf("UI.TEXT_INACTIVE",hx("666666")) },
  DYN_UI = { MAIN=leaf("DYN_UI.MAIN",hx("374244")), DARK=leaf("DYN_UI.DARK",hx("374244")),
             BOSS_MAIN=leaf("DYN_UI.BOSS_MAIN",hx("374244")), BOSS_DARK=leaf("DYN_UI.BOSS_DARK",hx("374244")) },
}

-- path-tagged G.GAME subtables (so ref_table=G.GAME.current_round serializes to that path)
local function tag(path, fields) local t = fields or {}; pathName[t] = path; return t end
local GAME = tag("G.GAME", { dollars=0, round=1, win_ante=8, stake=1 })
GAME.current_round = tag("G.GAME.current_round", {
  hands_left=4, discards_left=3,
  most_played_poker_hand='High Card',  -- used by boss blind description var substitution
  dollars_to_be_earned=3,             -- HUD blind reward DynaText
  reroll_cost=5,
  voucher="v_blank",
})
GAME.round_resets  = tag("G.GAME.round_resets",  {
  ante=1,
  -- Blind-select state per slot (Small/Big/Boss)
  blind_states = { Small='Select', Big='Upcoming', Boss='Upcoming' },
  -- Which P_BLINDS key each slot uses this ante
  blind_choices = { Small='bl_small', Big='bl_big', Boss='bl_ox' },
  -- Tag key offered for skipping each blind (nil = no tag block for that slot)
  blind_tags = { Small='tag_uncommon', Big='tag_rare' },
  -- Localized state label (live ref for T node ref_value=type)
  loc_blind_states = tag("G.GAME.round_resets.loc_blind_states", { Small='', Big='', Boss='' }),
  -- ante used for get_blind_amount (same as ante normally)
  blind_ante = 1,
})
GAME.modifiers = { scaling=1, no_blind_reward=nil }
GAME.starting_params = { ante_scaling=1, hands=4, discards=3, consumable_slots=2, joker_slots=5, reroll_cost=5, dollars=4 }
-- orbital_choices: keyed by ante then slot type; used in create_UIBox_blind_choice
GAME.orbital_choices = {}
-- pseudorandom seed state (needed by pseudoseed function)
GAME.pseudorandom = { seed='1', hashed_seed=0 }
-- hands table: one entry per playable hand (only visible=true ones are picked for orbital)
GAME.hands = {
  ['High Card']       = { visible=true },
  ['Pair']            = { visible=true },
  ['Two Pair']        = { visible=true },
  ['Three of a Kind'] = { visible=true },
  ['Straight']        = { visible=true },
  ['Flush']           = { visible=true },
  ['Full House']      = { visible=true },
  ['Four of a Kind']  = { visible=true },
  ['Straight Flush']  = { visible=true },
  ['Royal Flush']     = { visible=true },
}
-- blind object (HUD blind token; during blind-select G.GAME.blind is not yet set — stubs for safety)
GAME.blind = tag("G.GAME.blind", {
  loc_name='Small Blind',
  chip_text='300',
  loc_debuff_lines={[1]='', [2]=''},
  change_dim=function() end,
  config={ blind={ dollars=3 } },
})

-- capture-stubs: leaf builders return tagged tables instead of constructing real engine objects
function localize(a, misc_cat)
  -- Simple-key string lookup (e.g. localize('ph_blind_score_at_least'))
  if type(a) == 'string' then
    local dict = {
      ph_blind_score_at_least = 'Score at least',
      ph_blind_reward = 'Reward: ',
      ph_up_ante_1 = 'Up the Ante!',
      ph_up_ante_2 = 'Increasing requirements',
      ph_up_ante_3 = 'and new enemies await!',
      k_or = 'or',
      b_skip_blind = 'Skip Blind',
      b_skip_reward = 'Skip Reward',
      ph_choose_blind_1 = 'Choose',
      ph_choose_blind_2 = 'Your Blind',
      ['$'] = '$',
      -- HUD keys
      k_hud_hands='Hands', k_hud_discards='Discards', k_ante='Ante', k_round='Round', k_lower_score='score',
      b_options='Options', b_run_info_1='Run', b_run_info_2='Info',
      b_next_round_1='Next', b_next_round_2='Round', k_reroll='Reroll',
      -- pack open keys
      k_choose='Choose', k_arcana_pack='Arcana Pack', k_spectral_pack='Spectral Pack',
      k_standard_pack='Standard Pack', k_buffoon_pack='Buffoon Pack', k_celestial_pack='Celestial Pack',
      b_skip='Skip',
      -- win / game-over keys
      ph_you_win='YOU WIN!', ph_game_over='GAME OVER',
      b_start_new_run='New Run', b_main_menu='Main Menu', b_endless='Endless Mode',
      b_copy='Copy', b_next='Next', b_wishlist='Wishlist', b_playbalatro='Play Balatro',
      -- round_scores_row labels
      ph_score_hand='Chips Scored', ph_score_poker_hand='Most Played Hand',
      ph_score_cards_played='Cards Played', ph_score_cards_discarded='Cards Discarded',
      ph_score_cards_purchased='Cards Purchased', ph_score_times_rerolled='Times Rerolled',
      ph_score_new_collection='Collection', ph_score_seed='Seed',
      k_defeated_by='Defeated by', k_none='None', k_seed='Seed',
      ph_demo_thanks_1='Thanks', ph_demo_thanks_2='for playing!',
    }
    if misc_cat then return { __loc = {key=a, cat=misc_cat} } end
    return dict[a] or { __loc = a }
  end
  -- Table-form: localize{type=..., key=..., set=..., vars=...}
  if type(a) == 'table' then
    if a.type == 'name_text' then
      -- Return a 1-element array so DynaText({string=loc_name}) iterates correctly in encdyna
      local names = {
        bl_small='Small Blind', bl_big='Big Blind',
        bl_ox='The Ox', bl_hook='The Hook', bl_mouth='The Mouth', bl_fish='The Fish',
        bl_club='The Club', bl_manacle='The Manacle', bl_tooth='The Tooth',
        bl_wall='The Wall', bl_house='The House', bl_mark='The Mark',
        bl_wheel='The Wheel', bl_arm='The Arm', bl_psychic='The Psychic',
        bl_goad='The Goad', bl_water='The Water', bl_eye='The Eye',
        bl_plant='The Plant', bl_needle='The Needle', bl_head='The Head',
        bl_window='The Window', bl_serpent='The Serpent', bl_pillar='The Pillar',
        bl_flint='The Flint',
        bl_final_bell='Cerulean Bell', bl_final_leaf='Verdant Leaf',
        bl_final_vessel='Violet Vessel', bl_final_acorn='Amber Acorn', bl_final_heart='Crimson Heart',
      }
      return { names[a.key] or a.key }
    elseif a.type == 'raw_descriptions' then
      -- Return array of description lines. Small/Big = no lines. Bosses = 1 line placeholder.
      local descs = {
        bl_small={}, bl_big={},
        bl_ox={'Prevents the #1# most played hand'},
        bl_hook={'Discards 2 random cards each hand'},
        bl_mouth={'Play only 1 hand type this round'},
        bl_fish={'Cards drawn face down after each hand played'},
        bl_club={'All Club cards are debuffed'},
        bl_manacle={'Reduces hand size by 1'},
        bl_tooth={'Lose $1 per card played'},
        bl_wall={'Extra large Blind'},
        bl_house={'First hand is drawn face down'},
        bl_mark={'Unscored cards are face down after each hand'},
        bl_wheel={'1 in 7 cards are drawn face down'},
        bl_arm={'Decrease level of played poker hand'},
        bl_psychic={'Must play 5 cards'},
        bl_goad={'All Spade cards are debuffed'},
        bl_water={'0 discards this round'},
        bl_eye={'No repeat hand types this round'},
        bl_plant={'All face cards are debuffed'},
        bl_needle={'Play only 1 hand'},
        bl_head={'All Heart cards are debuffed'},
        bl_window={'All Diamond cards are debuffed'},
        bl_serpent={'After each hand played, discard and draw to hand'},
        bl_pillar={'Cards played previously this Ante are debuffed'},
        bl_flint={'Starting Chips and Mult are halved'},
        bl_final_bell={'Cerulean Bell ability'},
        bl_final_leaf={'Verdant Leaf ability'},
        bl_final_vessel={'Violet Vessel ability'},
        bl_final_acorn={'Amber Acorn ability'},
        bl_final_heart={'Crimson Heart ability'},
      }
      return descs[a.key] or {}
    elseif a.type == 'variable' then
      return { __loc = a }
    else
      return { __loc = a }
    end
  end
  return { __loc = a }
end

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
function Sprite(...)         return { __sprite="sprite",   define_draw_steps=function() end, states={drag={can=true}, hover={can=true}} } end
function UIBox(args)         return { __uibox=true, def=args and args.definition, alignment={offset={x=0,y=0}} } end   -- capture def (card.children.price/buy_button)
function Event(args)         return { __event=true, func=args and args.func } end

-- ── Helpers stubs that misc_functions.lua provides at runtime ────────────────────────────────────
-- These are inlined rather than dofile-ing misc_functions.lua (which has heavy SMODS dependencies).

function HEX(hex)
  if #hex <= 6 then hex = hex.."FF" end
  local _,_,r,g,b,a = hex:find('(%x%x)(%x%x)(%x%x)(%x%x)')
  return { tonumber(r,16)/255, tonumber(g,16)/255, tonumber(b,16)/255, tonumber(a,16)/255 }
end

-- get_blind_main_colour: misc_functions.lua:398.
-- Colour used for the blind card outline and name-band fill.
function get_blind_main_colour(blind)
  if blind == 'Boss' or blind == 'Small' or blind == 'Big' then
    local blind_states = G.GAME.round_resets.blind_states
    local defeated = blind_states and (blind_states[blind]=='Defeated' or blind_states[blind]=='Skipped')
    if defeated then return G.C.BLACK end
    blind = G.GAME.round_resets.blind_choices[blind]
  end
  if not G.P_BLINDS[blind] then return G.C.BLACK end
  if G.P_BLINDS[blind].boss_colour then return G.P_BLINDS[blind].boss_colour end
  if blind == 'bl_small' then return mix_colours(G.C.BLUE, G.C.BLACK, 0.6) end
  if blind == 'bl_big'   then return mix_colours(G.C.ORANGE, G.C.BLACK, 0.6) end
  return G.C.BLACK
end

-- get_blind_amount: misc_functions.lua:1060. Returns base chip target for the ante.
function get_blind_amount(ante)
  local amounts = {300, 800, 2000, 5000, 11000, 20000, 35000, 50000}
  if ante < 1 then return 100 end
  if ante <= 8 then return amounts[ante] end
  local a,b,c,d = amounts[8],1.6,ante-8, 1+0.2*(ante-8)
  local amount = math.floor(a*(b+(0.75*c)^d)^c)
  return amount - amount%(10^math.floor(math.log10(amount)-1))
end

-- number_format: misc_functions.lua:1098. Formats a number with commas.
function round_number(num, precision)
  precision = 10^(precision or 0)
  return math.floor(num * precision + 0.4999999999999994) / precision
end

function number_format(num)
  if type(num) ~= 'number' then return tostring(num) end
  local sign = (num >= 0 and "") or "-"
  num = math.abs(num)
  local E_SWITCH = 100000000000
  if num >= E_SWITCH then
    local x = string.format("%.4g",num)
    local fac = math.floor(math.log(tonumber(x),10))
    local mantissa = round_number(x/(10^fac),3)
    if mantissa >= 10 then mantissa=mantissa/10; fac=fac+1 end
    return sign..(string.format(fac>=100 and "%.1fe%i" or fac>=10 and "%.2fe%i" or "%.3fe%i", mantissa, fac))
  end
  local formatted
  if num ~= math.floor(num) and num < 100 then
    formatted = string.format(num>=10 and "%.1f" or "%.2f", num)
    if formatted:sub(-1)=="0" then formatted = formatted:gsub("%.?0+$","") end
    if num < 0.01 then return tostring(num) end
  else
    formatted = string.format("%.0f", num)
  end
  return sign..(formatted:reverse():gsub("(%d%d%d)","%1,"):gsub(",$",""):reverse())
end

-- score_number_scale: misc_functions.lua:1133. Returns scaled text size for large numbers.
function score_number_scale(scale, amt)
  if type(amt) ~= 'number' then return 0.7*(scale or 1) end
  if amt >= 100000000000 then return 0.7*(scale or 1)
  elseif amt >= 1000000 then return 14*0.75/(math.floor(math.log(amt))+4)*(scale or 1)
  else return 0.75*(scale or 1) end
end

-- pseudoseed / pseudorandom_element: misc_functions.lua:333/256.
-- Structural: we only care that orbital_choices[ante][type] gets set to SOME hand name.
function pseudohash(str)
  local num = 1
  for i=#str,1,-1 do num = ((1.1239285023/num)*string.byte(str,i)*math.pi+math.pi*i)%1 end
  return num
end
function pseudoseed(key)
  if not G.GAME.pseudorandom[key] then
    G.GAME.pseudorandom[key] = pseudohash(key..(G.GAME.pseudorandom.seed or ''))
  end
  G.GAME.pseudorandom[key] = math.abs(tonumber(string.format("%.13f",(2.134453429141+G.GAME.pseudorandom[key]*1.72431234)%1)))
  return (G.GAME.pseudorandom[key] + (G.GAME.pseudorandom.hashed_seed or 0))/2
end
function pseudorandom_element(t, seed)
  if seed and type(seed)=="string" then seed=pseudoseed(seed) end
  if seed then math.randomseed(seed) end
  -- collect visible hands (or all keys for generic tables)
  local keys={}; for k,v in pairs(t) do if type(v)=='table' and v.visible~=false then keys[#keys+1]=k end end
  table.sort(keys)
  if #keys==0 then return nil,nil end
  local k=keys[math.random(#keys)]; return t[k],k
end
function pseudorandom(seed) if seed then math.randomseed(seed) end; return math.random() end

-- discover_card: called by add_tag in UI_definitions.vanilla.lua; no-op for extraction.
function discover_card() end

-- ── P_BLINDS: full vanilla table (game.lua:280) ──────────────────────────────────────────────────
-- boss_colour fields use HEX() — identical to game.lua values.
-- We register each boss_colour as a named colour leaf so the serializer emits it symbolically.
local function blindcolour(key, hex)
  local c = HEX(hex); colourName[c] = 'boss:'..key; return c
end

-- ── Tag stub: Tag(key, nil, blind_type) ──────────────────────────────────────────────────────────
-- generate_UI() returns (tag_sprite_tab, tag_sprite) matching what create_UIBox_blind_tag expects.
-- The tag_sprite_tab is a C-node with one O-node (the sprite); tag_sprite needs .states.collide.can.
function Tag(key, _, blind_type)
  local tag_entry = G.P_TAGS and G.P_TAGS[key] or {}
  local _size = 0.8
  local tag_sprite = {
    states = { collide = { can = true } },
    __sprite = 'tag', key = key,
  }
  local tag_sprite_tab = {
    n = G.UIT.C,
    config = { align="cm" },
    nodes = {
      { n=G.UIT.O, config={ w=_size, h=_size, colour=G.C.BLUE, object=tag_sprite } }
    }
  }
  return {
    key = key,
    pos = tag_entry.pos or {x=0,y=0},
    tag_sprite = tag_sprite,
    generate_UI = function(self, size)
      size = size or 0.8
      tag_sprite.w = size; tag_sprite.h = size
      tag_sprite_tab.nodes[1].config.w = size
      tag_sprite_tab.nodes[1].config.h = size
      return tag_sprite_tab, tag_sprite
    end
  }
end

-- copy_table: misc_functions.lua. Shallow copy (win/game_over use it to clone G.C.GREEN/RED).
function copy_table(t)
  local n = {}; for k,v in pairs(t) do n[k]=v end; return n
end

-- ease_value: engine/animation.lua. Animates a table field toward a target value over time.
-- During extraction we just set the field immediately (no animation loop running).
function ease_value(t, key, target, ...) if type(key)=='number' then t[key]=target else t[key]=target end end



-- ── GAME fields the shop reads ────────────────────────────────────────────────────────────────────
GAME.shop = tag("G.GAME.shop", { joker_max=2 })

-- ── GAME fields win/game_over/round_scores_row read ──────────────────────────────────────────────
-- round_scores: each score key has .amt (number); presence gates the label/row.
-- The `defeated_by` key has no .amt but triggers the special animated-blind sub-tree.
GAME.round_scores = {
  hand         = { amt = 12345 },   -- chips scored this run
  poker_hand   = { amt = 42 },      -- (not a raw number; overridden in round_scores_row by hand_usage scan)
  cards_played = { amt = 104 },
  cards_discarded = { amt = 20 },
  cards_purchased = { amt = 8 },
  times_rerolled  = { amt = 2 },
  new_collection  = { amt = 0 },
  seed         = { amt = 0 },       -- seed is string, but .amt present = row renders
  defeated_by  = nil,               -- nil for win screen (boss wasn't the one that ended it)
  furthest_ante  = nil,             -- overridden in round_scores_row by G.GAME.round_resets.ante DynaText
  furthest_round = nil,             -- overridden by G.GAME.round DynaText
}
GAME.pseudorandom = { seed='TEST_SEED', hashed_seed=0 }
GAME.seeded = false
-- hand_usage: keyed by hand name, .count = times played, .order = localized name
GAME.hand_usage = {
  ['High Card'] = { count=15, order='High Card' },
  ['Pair']      = { count=8,  order='Pair' },
}
-- pack opening: pack_size=5 (standard; celestial/arcana typically 5)
GAME.pack_size = 5
GAME.pack_choices = 1
-- blind config: used by defeated_by sub-tree in round_scores_row
GAME.blind = tag("G.GAME.blind", {
  config = { blind = nil },  -- nil = fall back to G.P_BLINDS.bl_small
  loc_name='Small Blind',
  chip_text='300',
  loc_debuff_lines={[1]='', [2]=''},
  change_dim=function() end,
})

G = {
  UIT = UIT, C = C, GAME = GAME,
  LANGUAGES = { ["en-us"] = { font = { __font = "en-us" } } },
  UIDEF = {},
  -- areas/positions the shop def references
  hand = { T = { x=4.857, y=6.986, w=12.293, h=2.614 } },
  ROOM = { T = { x=1.44, y=0.69, w=20, h=11.5 } },
  CARD_W = 2.04878, CARD_H = 2.75122,
  ANIMATION_ATLAS = setmetatable({}, { __index=function() return {} end }),
  -- run deferred events immediately (pcall-guarded) so create_shop_card_ui builds card.children.* now
  E_MANAGER = { add_event = function(_, e) if e and e.func then local ok,err = pcall(e.func); if not ok then print("EVENT ERR: "..tostring(err)) end end end },
  HUD = { get_UIE_by_ID = function() return {} end },
  b_undiscovered = { name='Undiscovered', pos={x=0,y=30} },
  E_SWITCH_POINT = 100000000000,
  -- win/game-over use G.SETTINGS.colourblind_option for sprite atlas selection
  SETTINGS = { colourblind_option = false, SOUND = { volume=100, game_sounds_volume=100 } },
  -- G.FUNCS: only referenced by demo CTA (not win/game_over); no-op stub
  FUNCS = setmetatable({}, { __index=function() return function() end end }),
  -- G.ASSET_ATLAS: score-row 'hand' sub-tree creates a chip Sprite from this; stub as blank
  ASSET_ATLAS = setmetatable({}, { __index=function() return {x=0,y=0} end }),
  -- G.OVERLAY_MENU: infotip injection in create_UIBox_generic_options (pcall-guarded in event stubs)
  OVERLAY_MENU = nil,
}

-- P_BLINDS must go after G is constructed (blindcolour registers in colourName via leaf-like logic)
G.P_BLINDS = {
  bl_small = { key='bl_small', name='Small Blind', dollars=3, mult=1, pos={x=0,y=0} },
  bl_big   = { key='bl_big',   name='Big Blind',   dollars=4, mult=1.5, pos={x=0,y=1} },
  bl_ox      = { key='bl_ox',      name='The Ox',        dollars=5, mult=2, pos={x=0,y=2},  boss={min=6,max=10},  boss_colour=blindcolour('bl_ox','b95b08') },
  bl_hook    = { key='bl_hook',    name='The Hook',      dollars=5, mult=2, pos={x=0,y=7},  boss={min=1,max=10},  boss_colour=blindcolour('bl_hook','a84024') },
  bl_mouth   = { key='bl_mouth',   name='The Mouth',     dollars=5, mult=2, pos={x=0,y=18}, boss={min=2,max=10},  boss_colour=blindcolour('bl_mouth','ae718e') },
  bl_fish    = { key='bl_fish',    name='The Fish',      dollars=5, mult=2, pos={x=0,y=5},  boss={min=2,max=10},  boss_colour=blindcolour('bl_fish','3e85bd') },
  bl_club    = { key='bl_club',    name='The Club',      dollars=5, mult=2, pos={x=0,y=4},  boss={min=1,max=10},  boss_colour=blindcolour('bl_club','b9cb92') },
  bl_manacle = { key='bl_manacle', name='The Manacle',   dollars=5, mult=2, pos={x=0,y=8},  boss={min=1,max=10},  boss_colour=blindcolour('bl_manacle','575757') },
  bl_tooth   = { key='bl_tooth',   name='The Tooth',     dollars=5, mult=2, pos={x=0,y=22}, boss={min=3,max=10},  boss_colour=blindcolour('bl_tooth','b52d2d') },
  bl_wall    = { key='bl_wall',    name='The Wall',      dollars=5, mult=4, pos={x=0,y=9},  boss={min=2,max=10},  boss_colour=blindcolour('bl_wall','8a59a5') },
  bl_house   = { key='bl_house',   name='The House',     dollars=5, mult=2, pos={x=0,y=3},  boss={min=2,max=10},  boss_colour=blindcolour('bl_house','5186a8') },
  bl_mark    = { key='bl_mark',    name='The Mark',      dollars=5, mult=2, pos={x=0,y=23}, boss={min=2,max=10},  boss_colour=blindcolour('bl_mark','6a3847') },
  bl_wheel   = { key='bl_wheel',   name='The Wheel',     dollars=5, mult=2, pos={x=0,y=10}, boss={min=2,max=10},  boss_colour=blindcolour('bl_wheel','50bf7c') },
  bl_arm     = { key='bl_arm',     name='The Arm',       dollars=5, mult=2, pos={x=0,y=11}, boss={min=2,max=10},  boss_colour=blindcolour('bl_arm','6865f3') },
  bl_psychic = { key='bl_psychic', name='The Psychic',   dollars=5, mult=2, pos={x=0,y=12}, boss={min=1,max=10},  boss_colour=blindcolour('bl_psychic','efc03c') },
  bl_goad    = { key='bl_goad',    name='The Goad',      dollars=5, mult=2, pos={x=0,y=13}, boss={min=1,max=10},  boss_colour=blindcolour('bl_goad','b95c96') },
  bl_water   = { key='bl_water',   name='The Water',     dollars=5, mult=2, pos={x=0,y=14}, boss={min=2,max=10},  boss_colour=blindcolour('bl_water','c6e0eb') },
  bl_eye     = { key='bl_eye',     name='The Eye',       dollars=5, mult=2, pos={x=0,y=17}, boss={min=3,max=10},  boss_colour=blindcolour('bl_eye','4b71e4') },
  bl_plant   = { key='bl_plant',   name='The Plant',     dollars=5, mult=2, pos={x=0,y=19}, boss={min=4,max=10},  boss_colour=blindcolour('bl_plant','709284') },
  bl_needle  = { key='bl_needle',  name='The Needle',    dollars=5, mult=1, pos={x=0,y=20}, boss={min=2,max=10},  boss_colour=blindcolour('bl_needle','5c6e31') },
  bl_head    = { key='bl_head',    name='The Head',      dollars=5, mult=2, pos={x=0,y=21}, boss={min=1,max=10},  boss_colour=blindcolour('bl_head','ac9db4') },
  bl_window  = { key='bl_window',  name='The Window',    dollars=5, mult=2, pos={x=0,y=6},  boss={min=1,max=10},  boss_colour=blindcolour('bl_window','a9a295') },
  bl_serpent = { key='bl_serpent', name='The Serpent',   dollars=5, mult=2, pos={x=0,y=15}, boss={min=5,max=10},  boss_colour=blindcolour('bl_serpent','439a4f') },
  bl_pillar  = { key='bl_pillar',  name='The Pillar',    dollars=5, mult=2, pos={x=0,y=16}, boss={min=1,max=10},  boss_colour=blindcolour('bl_pillar','7e6752') },
  bl_flint   = { key='bl_flint',   name='The Flint',     dollars=5, mult=2, pos={x=0,y=24}, boss={min=2,max=10},  boss_colour=blindcolour('bl_flint','e56a2f') },
  bl_final_bell    = { key='bl_final_bell',    name='Cerulean Bell',  dollars=8, mult=2, pos={x=0,y=26}, boss={showdown=true,min=10,max=10}, boss_colour=blindcolour('bl_final_bell','009cfd') },
  bl_final_leaf    = { key='bl_final_leaf',    name='Verdant Leaf',   dollars=8, mult=2, pos={x=0,y=28}, boss={showdown=true,min=10,max=10}, boss_colour=blindcolour('bl_final_leaf','56a786') },
  bl_final_vessel  = { key='bl_final_vessel',  name='Violet Vessel',  dollars=8, mult=6, pos={x=0,y=29}, boss={showdown=true,min=10,max=10}, boss_colour=blindcolour('bl_final_vessel','8a71e1') },
  bl_final_acorn   = { key='bl_final_acorn',   name='Amber Acorn',    dollars=8, mult=2, pos={x=0,y=27}, boss={showdown=true,min=10,max=10}, boss_colour=blindcolour('bl_final_acorn','fda200') },
  bl_final_heart   = { key='bl_final_heart',   name='Crimson Heart',  dollars=8, mult=2, pos={x=0,y=25}, boss={showdown=true,min=10,max=10}, boss_colour=blindcolour('bl_final_heart','ac3232') },
}

-- P_TAGS: minimal table for Tag sprite generation (atlas always "tags" for vanilla tags)
G.P_TAGS = {
  tag_uncommon   = { key='tag_uncommon',   name='Uncommon Tag',     pos={x=0,y=0}, atlas='tags' },
  tag_rare       = { key='tag_rare',       name='Rare Tag',         pos={x=1,y=0}, atlas='tags' },
  tag_negative   = { key='tag_negative',   name='Negative Tag',     pos={x=2,y=0}, atlas='tags' },
  tag_foil       = { key='tag_foil',       name='Foil Tag',         pos={x=3,y=0}, atlas='tags' },
  tag_holo       = { key='tag_holo',       name='Holographic Tag',  pos={x=0,y=1}, atlas='tags' },
  tag_polychrome = { key='tag_polychrome', name='Polychrome Tag',   pos={x=1,y=1}, atlas='tags' },
  tag_investment = { key='tag_investment', name='Investment Tag',   pos={x=2,y=1}, atlas='tags' },
  tag_voucher    = { key='tag_voucher',    name='Voucher Tag',      pos={x=3,y=1}, atlas='tags' },
  tag_boss       = { key='tag_boss',       name='Boss Tag',         pos={x=0,y=2}, atlas='tags' },
  tag_standard   = { key='tag_standard',   name='Standard Tag',     pos={x=1,y=2}, atlas='tags' },
  tag_charm      = { key='tag_charm',      name='Charm Tag',        pos={x=2,y=2}, atlas='tags' },
  tag_meteor     = { key='tag_meteor',     name='Meteor Tag',       pos={x=3,y=2}, atlas='tags' },
  tag_buffoon    = { key='tag_buffoon',    name='Buffoon Tag',      pos={x=4,y=2}, atlas='tags' },
  tag_handy      = { key='tag_handy',      name='Handy Tag',        pos={x=1,y=3}, atlas='tags' },
  tag_garbage    = { key='tag_garbage',    name='Garbage Tag',      pos={x=2,y=3}, atlas='tags' },
  tag_ethereal   = { key='tag_ethereal',   name='Ethereal Tag',     pos={x=3,y=3}, atlas='tags' },
  tag_coupon     = { key='tag_coupon',     name='Coupon Tag',       pos={x=4,y=0}, atlas='tags' },
  tag_double     = { key='tag_double',     name='Double Tag',       pos={x=5,y=0}, atlas='tags' },
  tag_juggle     = { key='tag_juggle',     name='Juggle Tag',       pos={x=5,y=1}, atlas='tags' },
  tag_d_six      = { key='tag_d_six',      name='D6 Tag',           pos={x=5,y=3}, atlas='tags' },
  tag_top_up     = { key='tag_top_up',     name='Top-up Tag',       pos={x=4,y=1}, atlas='tags' },
  tag_skip       = { key='tag_skip',       name='Skip Tag',         pos={x=0,y=3}, atlas='tags' },
  tag_orbital    = { key='tag_orbital',    name='Orbital Tag',      pos={x=5,y=2}, atlas='tags' },
  tag_economy    = { key='tag_economy',    name='Economy Tag',      pos={x=4,y=3}, atlas='tags' },
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
      elseif type(v)=="table" and v.__sprite then d = '{"$":"sprite","name":"'..v.__sprite..'","scale":'..tostring(v.scale or 0.5)..'}'
      elseif type(v)=="table" and v.__moveable then d = '{"$":"moveable"}'
      elseif type(v)=="table" and v.__cardarea then d = '{"$":"cardarea","name":"'..(v.__name or (G.pack_cards and v==G.pack_cards and "pack_cards") or "?")..'","w":'..tostring(v.w)..',"h":'..tostring(v.h)..',"limit":'..tostring(v.config and v.config.card_limit or 0)..'}'
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
  -- vanilla DynaText.string can be a plain string (when localize returns a string directly)
  -- or a table of segment-objects. Normalise to a table so the loop always works.
  local str = a.string or {}
  if type(str) == 'string' then str = { str } end
  for _, s in ipairs(str) do
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

-- OFFER CARD UI: run the REAL create_shop_card_ui per card-set, capturing the price tag + the
-- buy/redeem/open button it attaches to each card (card.children.price / .buy_button / .buy_and_use_button).
-- The price DynaText binds ref_table=card → tag the stub card "card" so it serializes to a ref the
-- Kotlin binds to each offer's cost. (E_MANAGER.add_event runs the deferred build func immediately.)
local function offerCardUI(set, consumeable)
  local card = { ability = { set = set, consumeable = consumeable }, cost = 5, children = {}, opening = false }
  pathName[card] = "card"
  create_shop_card_ui(card, set, nil)
  return card.children
end
local sets = { joker = offerCardUI('Joker', false), voucher = offerCardUI('Voucher', false),
               booster = offerCardUI('Booster', false), consumable = offerCardUI('Tarot', true) }
local parts = {}
for _, name in ipairs({ "joker", "voucher", "booster", "consumable" }) do
  local ch = sets[name]; local entry = {}
  if ch.price and ch.price.def then entry[#entry+1] = '"price":'..encnode(ch.price.def) end
  if ch.buy_button and ch.buy_button.def then entry[#entry+1] = '"button":'..encnode(ch.buy_button.def) end
  if ch.buy_and_use_button and ch.buy_and_use_button.def then entry[#entry+1] = '"buy_and_use":'..encnode(ch.buy_and_use_button.def) end
  parts[#parts+1] = '"'..name..'":{'..table.concat(entry,",")..'}'
end
local cardjson = "{"..table.concat(parts,",").."}"
local ocp = (arg[0]:gsub("extract%.lua$","")) .. "shop_card_ui.json"
local ocf = io.open(ocp, "w"); ocf:write(cardjson); ocf:close()
print("wrote "..ocp.." ("..#cardjson.." bytes)")

-- ── BLIND SELECT: extract the three per-slot choice cards ────────────────────────────────────────
-- Each dump captures one static slot type. The Kotlin renderer will pick the right JSON by slot index:
--   slotIdx 0 = blind_small_tree.json
--   slotIdx 1 = blind_big_tree.json
--   slotIdx 2 = blind_boss_tree.json  (use the currently-configured Boss key, bl_ox by default)
--
-- The outer create_UIBox_blind_select (ROOT + R(padding=0.5) + 3 O slots) is structural glue that
-- BlindSelectScreen.kt already re-creates as a Compose Row; we don't dump it.
-- create_UIBox_blind_tag is called inside create_UIBox_blind_choice and captured inline.

-- Small blind: state=Select (the active slot), tag present (tag_uncommon)
G.GAME.round_resets.blind_states = { Small='Select', Big='Upcoming', Boss='Upcoming' }
G.GAME.blind_on_deck = 'Small'
G.GAME.orbital_choices = {}  -- reset so each call re-derives the orbital choice
local blindSmall = create_UIBox_blind_choice('Small', nil)
dump(blindSmall, "blind_small_tree.json")

-- Big blind: state=Upcoming (greyed out), tag present (tag_rare)
G.GAME.round_resets.blind_states = { Small='Select', Big='Upcoming', Boss='Upcoming' }
G.GAME.blind_on_deck = 'Small'  -- keep Small as on_deck; Big is Upcoming
G.GAME.orbital_choices = {}
local blindBig = create_UIBox_blind_choice('Big', nil)
dump(blindBig, "blind_big_tree.json")

-- Boss blind: state=Upcoming, no tag (Boss slot gets the ante-up DynaText extras block instead)
-- Use bl_ox as the representative boss (has boss_colour + a description line).
G.GAME.round_resets.blind_choices.Boss = 'bl_ox'
G.GAME.round_resets.blind_states = { Small='Select', Big='Upcoming', Boss='Upcoming' }
G.GAME.blind_on_deck = 'Small'
G.GAME.orbital_choices = {}
local blindBoss = create_UIBox_blind_choice('Boss', nil)
dump(blindBoss, "blind_boss_tree.json")

-- ── PACK OPEN: extract the five pack UIBox frames ────────────────────────────────────────────────
-- All five functions (arcana/spectral/standard/buffoon/celestial) share the same outer frame:
-- ROOT(CLEAR,r=0.15) → R(CLEAR,r=0.15,shadow) → R(cm) → C(pad) → C(r=0.2,CLEAR) → O(G.pack_cards)
-- Each packs its own title key and an optional "choose N" readout.
-- The CardArea O-node serializes to {"$":"cardarea","name":"?","w":...,"h":...} — the Kotlin renderer
-- maps it to a CardAreaSlot that the pack-opening composable fills with revealed items.
G.GAME.pack_size = 5

dump(create_UIBox_arcana_pack(),   "pack_arcana_tree.json")
dump(create_UIBox_spectral_pack(), "pack_spectral_tree.json")
dump(create_UIBox_standard_pack(), "pack_standard_tree.json")
dump(create_UIBox_buffoon_pack(),  "pack_buffoon_tree.json")
dump(create_UIBox_celestial_pack(),"pack_celestial_tree.json")

-- ── WIN SCREEN: create_UIBox_win ──────────────────────────────────────────────────────────────────
-- Static structure: ph_you_win DynaText + stat grid (create_UIBox_round_scores_row × 9 keys)
-- + Endless/New Run/Main Menu buttons + Jimbo Moveable. The eased_green bg is set to alpha=0 then
-- animated in — we call ease_value which sets it immediately to 0.5, giving a light green bg.
-- Bindings in stat rows:
--   furthest_ante → DynaText(G.GAME.round_resets.ante, FILTER)
--   furthest_round → DynaText(G.GAME.round, FILTER)
--   hand → Sprite(chip) + DynaText(round_scores.hand.amt, RED)
--   poker_hand → DynaText(most played hand name, WHITE) + T(" (count)", JOKER_GREY)
--   seed → DynaText(G.GAME.pseudorandom.seed, WHITE)
--   others → DynaText(round_scores[key].amt, FILTER)
dump(create_UIBox_win(), "win_tree.json")

-- ── GAME OVER SCREEN: create_UIBox_game_over ─────────────────────────────────────────────────────
-- Identical stat grid structure to win; bg is eased_red (RED for ante<=win_ante, BLUE for ante>8).
-- defeated_by row uses animated blind sprite + localized blind name DynaText.
-- G.GAME.round_scores.defeated_by is nil here → the row is omitted (label check gates it).
-- Buttons: New Run (RED, notify_then_setup_run) + Main Menu (RED, go_to_menu).
dump(create_UIBox_game_over(), "game_over_tree.json")

-- ── ROUND EVAL (cash-out skeleton): create_UIBox_round_evaluation ────────────────────────────────
-- Outer skeleton only — 3 empty id-slotted R nodes (base_round_eval, bonus_round_eval, eval_bottom).
-- Row content is injected at runtime by add_round_eval_row (common_events.lua:1154); the skeleton
-- captures the container geometry (G.hand.T.w-2 wide, padding=0.1, r=0.1, BLACK, emboss=0.05).
-- Extracting gives the frame; Kotlin fills the id-slotted rows from RunState.evalRows at render.
dump(create_UIBox_round_evaluation(), "round_eval_tree.json")
