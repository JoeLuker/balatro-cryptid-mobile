#!/usr/bin/env python3
"""
Patch main.lua for Android compatibility.
Handles SMODS path and nativefs calls that don't work on Android.
"""

import sys
import re

def patch_main_lua(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Check if already fully patched (all sentinel strings must be present).
    # NOTE (Amulet era, 2026-06-11): the Talisman-specific sections below
    # (config read/write redirects, big-num loads, F_NO_COROUTINE dedup,
    # TAL_BREAKINF_CLAMP) no-op cleanly against the Amulet dump — their anchors
    # only existed in the Talisman-era dump (retired in Phase 5). Kept as inert
    # rollback scaffolding; recover the old dump from git history if ever needed.
    already_android = '-- Android SMODS path fix' in content
    already_focus = '-- Android flush-on-background' in content
    already_istouch = 'HID_ISTOUCH_FIX' in content
    already_istouch_release = 'HID_ISTOUCH_RELEASE_FIX' in content
    already_istouch_move = 'HID_ISTOUCH_MOVE_FIX' in content
    already_jitopt = 'JIT_OPT_RAISE' in content
    already_amulet = 'AMULET_ROOT_MOUNT' in content or '_mod_dir_amulet' not in content
    already_resize = 'ANDROID_RESIZE_CONTAIN' in content
    if already_android and already_focus and already_istouch and already_istouch_release and already_istouch_move and already_jitopt and already_amulet and already_resize:
        print("Already patched")
        return

    # 0. JIT_OPT_RAISE (Tier 0b): raise LuaJIT trace budgets at the earliest
    # possible point. The defaults (maxtrace=1000, maxmcode=2048KB,
    # maxside=100, maxsnap=500, hotloop=56) starve a 128k-line codebase:
    # when the trace pool exhausts, hot paths blacklist and run interpreted
    # forever — the bimodal frame-time cliff measured on-device. jit.opt is
    # a C builtin (lib_jit.c, preregistered), available even in the static
    # liblove.so where plain-Lua jit modules (vmdef) are not. Unconditional
    # (not Android-gated) so desktop benches measure the same configuration;
    # pcall-guarded for any non-LuaJIT runtime.
    if 'JIT_OPT_RAISE' not in content:
        jit_opt_block = """-- JIT_OPT_RAISE: see scripts/patch_main_lua.py section 0
pcall(function()
    require("jit.opt").start(
        "maxtrace=4000", "maxmcode=16384", "maxside=400", "maxsnap=2000", "hotloop=28"
    )
end)
"""
        content = jit_opt_block + content

    # 0a. (removed) LOGCAT_PRINT — folded into the OBS suite. Logcat output was a
    # redundant/unreliable path (its LOVE-tag FFI sink produced nothing on-device);
    # the canonical OBS sinks are the telemetry.log + crash.log files in the save
    # dir (android-telemetry.lua), pulled via `just obs`.

    # 0b. AMULET_ROOT_MOUNT: Amulet's appended loader FFI-mounts
    # <mod_dir>/talisman and <mod_dir>/big-num as PhysFS roots so its modules
    # resolve as require("talisman.*") / load("big-num/..."). Two problems for
    # us: the dump bakes the dump-rig's ABSOLUTE path into _mod_dir_amulet,
    # and PhysFS cannot mount a directory that lives inside game.love on
    # Android. build.sh instead places talisman/ and big-num/ physically at
    # the game root (apply_amulet_root_dirs), which satisfies the same
    # requires natively on every platform — so the mounts are deleted, not
    # ported. The path var stays (smods.lua nil-checks it as an
    # installed-correctly sentinel), rewritten to the embedded location.
    if 'AMULET_ROOT_MOUNT' not in content and '_mod_dir_amulet' in content:
        content = re.sub(
            r'_mod_dir_amulet = "[^"]*"',
            '_mod_dir_amulet = "Mods/Amulet"',
            content,
            count=1,
        )
        mount_block = """local ffi = require("ffi")
ffi.cdef[[int PHYSFS_mount(const char* dir, const char* mountPoint, int appendToPath)]]
local tinymount = (pcall(function() return ffi.C.PHYSFS_mount end) and ffi.C or ffi.load("love")).PHYSFS_mount

local talisman_path = _mod_dir_amulet
assert(tinymount(talisman_path .. '/talisman', 'talisman', 0) ~= 0, 'Amulet: Failed to mount talisman from ' .. talisman_path)
assert(tinymount(talisman_path .. '/big-num', 'big-num', 0) ~= 0, 'Amulet: Failed to mount big-num from ' .. talisman_path)"""
        mount_replacement = """-- AMULET_ROOT_MOUNT: PhysFS mounts removed — talisman/ and big-num/ are
-- placed physically at the game root by build.sh (PhysFS cannot mount
-- paths inside game.love on Android); require("talisman.*") resolves
-- natively. See patch_main_lua.py section 0b."""
        if mount_block in content:
            content = content.replace(mount_block, mount_replacement, 1)
        else:
            print("WARNING: Amulet mount block anchor not found — AMULET_ROOT_MOUNT NOT applied")

    # 0c. ANDROID_RESIZE_CONTAIN: love.resize refuses portrait — `if w/h < 1
    # then h = w/1` — computing layout, canvas size and WINDOWTRANS for a
    # square w×w region while the surface stays w×H (canvas presents at
    # origin: dead band fills the rest). On the foldable, portrait and
    # split-screen surfaces are NORMAL (inner screen portrait is ratio 0.83),
    # so screen switches feel broken. The scale math itself is sound absolute
    # contain (window_prev algebraically cancels), so the fix is narrow:
    # on Android allow any aspect down to 0.4 (real height flows into layout,
    # canvas and touch geometry; the room centers via the existing formula),
    # keeping a far rarer clamp for extreme slivers. The floor sat at 0.6
    # first — but the cover screen itself reports 0.569 and got a 5% dead
    # band; 0.4 also lets half-splits (~0.46-0.55) fill. Trade-off: tall surfaces
    # can show card staging pop-in above/below the room (the clamp's original
    # purpose) — cosmetic, versus a dead fifth of the screen. Also: open
    # overlay menus get the same recalculate() that buttons/HUD already get,
    # or a screen switch with a menu open leaves it on stale geometry.
    if 'ANDROID_RESIZE_CONTAIN' not in content:
        old_clamp = """function love.resize(w, h)
	if w/h < 1 then --Dont allow the screen to be too square, since pop in occurs above and below screen
		h = w/1
	end"""
        new_clamp = """function love.resize(w, h)
	-- ANDROID_RESIZE_CONTAIN: see scripts/patch_main_lua.py section 0c
	-- (RESIZE telemetry: field evidence that fold posture changes actually
	-- deliver resize events — pairs with FOLD_RESIZE manifest flip)
	if ATLOG then ATLOG("RESIZE", { w = math.floor(w), h = math.floor(h) }) end
	if love.system.getOS() == 'Android' then
		-- floor 0.4, NOT 0.6: the cover screen reports 411x722 = 0.569 and
		-- must fill edge-to-edge (0.6 left a 5% dead band on it); only true
		-- ribbon windows clamp
		if w/h < 0.4 then h = w/0.4 end
	elseif w/h < 1 then --Dont allow the screen to be too square, since pop in occurs above and below screen
		h = w/1
	end"""
        if old_clamp in content and content.count(old_clamp) == 1:
            content = content.replace(old_clamp, new_clamp, 1)
        else:
            print("WARNING: resize clamp anchor not found — ANDROID_RESIZE_CONTAIN NOT applied")

        old_recalc = """		if G.buttons then G.buttons:recalculate() end
		if G.HUD then G.HUD:recalculate() end"""
        new_recalc = """		if G.buttons then G.buttons:recalculate() end
		if G.HUD then G.HUD:recalculate() end
		-- ANDROID_RESIZE_CONTAIN: open overlays must re-layout too (pcall:
		-- a mid-removal overlay must not crash the resize handler)
		if G.OVERLAY_MENU then pcall(function() G.OVERLAY_MENU:recalculate() end) end
		if G.OVERLAY_TUTORIAL and G.OVERLAY_TUTORIAL.recalculate then pcall(function() G.OVERLAY_TUTORIAL:recalculate() end) end"""
        if old_recalc in content and content.count(old_recalc) == 1:
            content = content.replace(old_recalc, new_recalc, 1)
        else:
            print("WARNING: resize recalc anchor not found — overlay recalc NOT applied")

    # 1. Android SMODS bootstrap shim — injected UNCONDITIONALLY at the top of
    # main.lua (before game.lua's initSteamodded() and any SMODS require). On
    # Android there is no lovely runtime, so the modules lovely registers (the
    # name= entries in Steamodded's lovely/*.toml) are not require-able. Bake each
    # as a package.preload mapping its lovely module name -> its baked file, and
    # extend the love require path so SMODS's own file-based loader resolves the
    # rest.
    #
    # History: older Steamodded injected `SMODS = {}` inline in main.lua and only
    # version/release needed preloading; the preflight restructure (fdb7442) moved
    # init into game.lua's `require"SMODS.preflight.loader".initSteamodded()` and
    # added the preflight + nativefs/https modules. We inject unconditionally (no
    # `SMODS = {}` anchor — it no longer exists) so both eras boot. The table below
    # mirrors Steamodded's lovely name=/source= registrations; if a require starts
    # failing at boot after a Steamodded bump, re-derive it:
    #   grep -hE '^\\s*(name|source) *=' Mods/Steamodded/lovely/*.toml
    smods_shim = """-- Android SMODS path fix: preload lovely-registered modules + run the preflight core
-- before main.lua. The preflight Steamodded (fdb7442+) creates the global SMODS only
-- in src/preflight/core.lua, which lovely runs via `load_now=true, before="main.lua"`
-- (a runtime action the baked dump cannot capture — nothing require()s core, so without
-- this SMODS stays nil and main.lua's appended core-load `assert(SMODS.path)` crashes).
-- We replicate the module registry (lovely libs.toml/preflight.toml name= entries) and
-- the load_now by eager-require below. nativefs maps to the Android love.filesystem
-- wrapper (nativefs.lua at the game root), not the raw FFI lib.
if love.system.getOS() == 'Android' then
    local _smods_modules = {
        ['SMODS.version']              = 'Mods/Steamodded/version.lua',
        ['SMODS.release']              = 'Mods/Steamodded/release.lua',
        ['SMODS.preflight.sharedUtil'] = 'Mods/Steamodded/src/preflight/sharedUtil.lua',
        ['SMODS.preflight.logging']    = 'Mods/Steamodded/src/preflight/logging.lua',
        ['SMODS.preflight.loader']     = 'Mods/Steamodded/src/preflight/loader.lua',
        ['SMODS.preflight.sharedUI']   = 'Mods/Steamodded/src/preflight/sharedUI.lua',
        ['SMODS.nativefs']             = 'nativefs.lua',
        ['SMODS.https']                = 'Mods/Steamodded/libs/https/smods-https.lua',
        ['json']                       = 'Mods/Steamodded/libs/json/json.lua',
        ['nativefs']                   = 'nativefs.lua',
        ['luajit-curl']                = 'Mods/Steamodded/libs/https/luajit-curl.lua',
    }
    for _name, _path in pairs(_smods_modules) do
        package.preload[_name] = function() return love.filesystem.load(_path)() end
    end
    -- core.lua's `local lovely_path = false` is normally patched by lovely to the
    -- Steamodded directory; without lovely its `assert(lovely_path)` fails. Inject the
    -- baked Android path (matches SMODS.path = 'Mods/Steamodded/' set below).
    package.preload['SMODS.preflight.core'] = function()
        local _src = assert(love.filesystem.read('Mods/Steamodded/src/preflight/core.lua'))
        _src = _src:gsub('local lovely_path = false', "local lovely_path = 'Mods/Steamodded/'", 1)
        -- Force the mod-scan dir to the relative 'Mods' (love.filesystem-resolvable to
        -- files/save/game/Mods + the game.love archive). core.lua sets
        -- SMODS.MODS_DIR = NFS.getWorkingDirectory(), which on Android resolves to a
        -- bogus absolute path (files/save/ASET/Mods) so SMODS scans an empty/stale dir
        -- and NO user mods load. The old override (append after main.lua's
        -- set_mods_dir()) silently no-ops because the de-drifted Steamodded moved that
        -- call out of main.lua. Set it here, right before initLoader()->loadMods().
        _src = _src:gsub("initLoader%(%)", "SMODS.MODS_DIR = 'Mods'; initLoader()", 1)
        return assert(load(_src, '@Mods/Steamodded/src/preflight/core.lua'))()
    end
    local love_paths = 'Mods/Steamodded/libs/?.lua;Mods/Steamodded/libs/?/init.lua;Mods/Steamodded/?.lua;Mods/Steamodded/?/init.lua;Mods/?.lua;Mods/?/init.lua'
    love.filesystem.setRequirePath(love.filesystem.getRequirePath() .. ';' .. love_paths)
    -- Replicate lovely's `load_now=true, before="main.lua"` for the preflight core:
    -- run it now to create the global SMODS + run the preflight, before main.lua's
    -- appended SMODS core-load executes.
    require('SMODS.preflight.core')
end
"""
    if '-- Android SMODS path fix' not in content:
        content = smods_shim + content
    # FAIL LOUD: the shim is boot-critical and must be present after this point.
    if '-- Android SMODS path fix' not in content:
        sys.exit("FATAL patch_main_lua.py: Android SMODS shim failed to inject into main.lua.")

    # 1b. On Android, override SMODS.MODS_DIR to relative path after set_mods_dir()
    # set_mods_dir() resolves to absolute paths which love.filesystem can't use
    # IMPORTANT: Match the standalone function CALL (after the function definition ends),
    # not the function definition line. The call is "set_mods_dir()" on its own line
    # after the "end" that closes the function body.
    set_mods_dir_pattern = r"^(set_mods_dir\(\))$"
    set_mods_dir_replacement = r"""\1
-- Android: force relative MODS_DIR since love.filesystem needs paths relative to game.love
if love.system.getOS() == 'Android' then
    SMODS.MODS_DIR = 'Mods'
end"""
    content = re.sub(set_mods_dir_pattern, set_mods_dir_replacement, content, count=1, flags=re.MULTILINE)

    # 2. Fix SMODS.path = find_self(...)
    find_self_pattern = r"SMODS\.path = find_self\(SMODS\.MODS_DIR, 'core\.lua', '--- STEAMODDED CORE'\)"
    find_self_replacement = """-- Android SMODS.path hardcode since NFS doesn't work with APK assets
if love.system.getOS() == 'Android' then
    SMODS.path = 'Mods/Steamodded/'
else
    SMODS.path = find_self(SMODS.MODS_DIR, 'core.lua', '--- STEAMODDED CORE')
end"""
    content = re.sub(find_self_pattern, find_self_replacement, content)

    # 3. Fix nativefs.getDirectoryItemsInfo for mod discovery
    dir_items_pattern = r"local info = nativefs\.getDirectoryItemsInfo\(lovely\.mod_dir\)"
    dir_items_replacement = """-- Android mod directory fix
local info
if love.system.getOS() == 'Android' then
    info = {}
    for _, name in ipairs({'Cryptid', 'Steamodded', 'Amulet', 'lovely', 'sticky-fingers'}) do
        table.insert(info, {name = name, type = 'directory'})
    end
else
    info = nativefs.getDirectoryItemsInfo(lovely.mod_dir)
end"""
    content = re.sub(dir_items_pattern, dir_items_replacement, content)

    # 3b. On Android, hardcode talisman_path to a relative path since love.filesystem
    # needs relative paths (within game.love), not absolute filesystem paths.
    # Replace the entire talisman detection block.
    talisman_block_pattern = r'local talisman_path = ""\nfor i, v in pairs\(info\) do\n\s*if v\.type == "directory" and nativefs\.getInfo\(lovely\.mod_dir \.\. "/" \.\. v\.name \.\. "/talisman\.lua"\) then talisman_path = lovely\.mod_dir \.\. "/" \.\. v\.name end\nend\n\nif not nativefs\.getInfo\(talisman_path\) then'
    talisman_block_replacement = """local talisman_path = ""
for i, v in pairs(info) do
  if v.type == "directory" and (love.system.getOS() == "Android" and v.name == "Talisman" or nativefs.getInfo(lovely.mod_dir .. "/" .. v.name .. "/talisman.lua")) then talisman_path = lovely.mod_dir .. "/" .. v.name end
end
-- On Android, override talisman_path to relative path for love.filesystem compatibility
if love.system.getOS() == 'Android' and talisman_path ~= "" then
    talisman_path = "Mods/Talisman"
    -- Ensure the save-dir directory tree exists so love.filesystem.write can create config.lua.
    -- love.filesystem.write does not auto-create intermediate directories; without this the
    -- write silently fails (pcall swallows the error) and settings reset on every boot.
    -- createDirectory also does not recurse: create Mods/ first, then Mods/Talisman/.
    love.filesystem.createDirectory("Mods")
    love.filesystem.createDirectory(talisman_path)
end

if love.system.getOS() ~= 'Android' and not nativefs.getInfo(talisman_path) then"""
    content = re.sub(talisman_block_pattern, talisman_block_replacement, content)

    # 4b. Fix load_file_with_fallback2 - uses nativefs.read which doesn't work on Android
    fallback_pattern = r'function load_file_with_fallback2\(a, aa\)\s*\n\s*local success, result = pcall\(function\(\) return assert\(load\(nativefs\.read\(a\)\)\)\(\) end\)'
    fallback_replacement = """function load_file_with_fallback2(a, aa)
    local read_fn = love.system.getOS() == 'Android' and love.filesystem.read or nativefs.read
    local success, result = pcall(function() return assert(load(read_fn(a)))() end)"""
    content = re.sub(fallback_pattern, fallback_replacement, content)

    # Also fix the fallback line in the same function
    fallback2_pattern = r'local fallback_success, fallback_result = pcall\(function\(\) return assert\(load\(nativefs\.read\(aa\)\)\)\(\) end\)'
    fallback2_replacement = """local read_fn2 = love.system.getOS() == 'Android' and love.filesystem.read or nativefs.read
    local fallback_success, fallback_result = pcall(function() return assert(load(read_fn2(aa)))() end)"""
    content = re.sub(fallback2_pattern, fallback2_replacement, content)

    # 5. Fix Talisman config read
    config_read_pattern = r"local config_read_result = nativefs\.read\(talisman_path\.\.\"/config\.lua\"\)"
    config_read_replacement = """local config_read_result
if love.system.getOS() == 'Android' then
    config_read_result = love.filesystem.read(talisman_path.."/config.lua")
else
    config_read_result = nativefs.read(talisman_path.."/config.lua")
end"""
    content = re.sub(config_read_pattern, config_read_replacement, content)

    # 5b. Harden the Talisman config unpack (TAL_CFG_SAFE_UNPACK + TAL_BREAKINF_CLAMP).
    # The config file is user data and must not be able to crash boot:
    # - a truncated file (process killed mid love.filesystem.write, which is
    #   not atomic) makes STR_UNPACK throw -> safe-unpack keeps the defaults;
    # - Talisman's score-type UI cycle persists break_infinity="" for its
    #   "vanilla scoring" option, which makes the big-num loader try
    #   "big-num/.lua", leaves Big nil, and crash-loops number_format at boot
    #   (proven on-device 2026-06-10). This pack requires the omeganum
    #   backend (Cryptid math assumes Big), so clamp anything else at read.
    unpack_pattern = "    Talisman.config_file = STR_UNPACK(config_read_result)"
    unpack_replacement = """    do local _ok, _cfg = pcall(STR_UNPACK, config_read_result) if _ok and type(_cfg) == "table" then Talisman.config_file = _cfg end end -- TAL_CFG_SAFE_UNPACK
    if Talisman.config_file.break_infinity ~= "omeganum" then Talisman.config_file.break_infinity = "omeganum" Talisman.config_file.score_opt_id = 2 end -- TAL_BREAKINF_CLAMP"""
    if unpack_pattern in content:
        content = content.replace(unpack_pattern, unpack_replacement, 1)
    elif '_mod_dir_amulet' not in content:
        # only alarming on a Talisman-era dump; under Amulet the config system
        # moved to talisman/configinit.lua (hardened by build.sh applier)
        print("WARNING: Talisman STR_UNPACK anchor not found - TAL_BREAKINF_CLAMP NOT applied")

    # 6. Fix nativefs.load calls for big-num
    # Match both lines: Big, err = nativefs.load(...) AND if not err then Big = Big() else Big = nil end
    big_load_pattern = r"Big, err = nativefs\.load\(talisman_path\.\.\"/big-num/\"\.\.(.*?)\.\.\"\.lua\"\)\s*\n\s*if not err then Big = Big\(\) else Big = nil end"
    big_load_replacement = r"""if love.system.getOS() == 'Android' then
    Big, err = love.filesystem.load(talisman_path.."/big-num/"..\1..".lua")
    if Big and not err then Big = Big() else Big = nil end
  else
    Big, err = nativefs.load(talisman_path.."/big-num/"..\1..".lua")
    if not err then Big = Big() else Big = nil end
  end"""
    content = re.sub(big_load_pattern, big_load_replacement, content)

    # 7. Fix Notations load
    notations_pattern = r"Notations = nativefs\.load\(talisman_path\.\.\"/big-num/notations\.lua\"\)\(\)"
    notations_replacement = """if love.system.getOS() == 'Android' then
    Notations = love.filesystem.load(talisman_path.."/big-num/notations.lua")()
else
    Notations = nativefs.load(talisman_path.."/big-num/notations.lua")()
end"""
    content = re.sub(notations_pattern, notations_replacement, content)

    # 8. Fix nativefs.write calls (skip on Android since we can't write to APK assets)
    write_pattern = r"nativefs\.write\(talisman_path \.\. \"/config\.lua\", STR_PACK\(Talisman\.config_file\)\)"
    write_replacement = """pcall(function() if love.system.getOS() == 'Android' then love.filesystem.write(talisman_path .. "/config.lua", STR_PACK(Talisman.config_file)) else nativefs.write(talisman_path .. "/config.lua", STR_PACK(Talisman.config_file)) end end)"""
    content = re.sub(write_pattern, write_replacement, content)

    # 10. Remove duplicate Talisman F_NO_COROUTINE block from main.lua.
    #
    # The lovely dump's main.lua contains a verbatim copy of the F_NO_COROUTINE
    # block that talisman.lua already defines (evaluate_play coroutine wrapper,
    # tal_abort, love.update driver, TIME_BETWEEN_SCORING_FRAMES,
    # Card:calculate_joker, Card:use_consumable, calculating_score guard).
    # This causes three bugs:
    #   - Double coroutine launch: talisman.lua:676 creates + first-resumes the
    #     coroutine; main.lua's copy OVERWRITES G.SCORING_COROUTINE with a new
    #     coroutine and first-resumes that one.  Talisman's coroutine is abandoned.
    #   - Double love.update resume: both wrappers fire each frame, each calling
    #     coroutine.resume on G.SCORING_COROUTINE — ~60 ms instead of ~30 ms.
    #   - Double CARD_CALC_COUNTS increment: Card:calculate_joker fires twice per
    #     joker call, making totalCalcs 2× real and jokersYetToScore go negative.
    #
    # The duplicate is identified by the regex matching from the flag line through
    # the trailing commented-out eval_card block (present in the dump's copy but
    # used as the terminal anchor).  Replace the whole thing with a comment.
    # The inner evaluate_play body (main.lua:2010 in the dump, which calls the
    # five phase functions) is OUTSIDE this block and is intentionally preserved —
    # it is what runs inside the coroutine.
    dup_block_pattern = (
        r'Talisman\.F_NO_COROUTINE = false --easy disabling for bugfixing.*?'
        r'end\n--\[\[local ec = eval_card\n.*?end--\]\]'
    )
    dedup_replacement = (
        '-- Duplicate Talisman coroutine harness removed by patch_main_lua.py.\n'
        '-- The canonical F_NO_COROUTINE block (evaluate_play coroutine wrapper,\n'
        '-- tal_abort, love.update driver, Card:calculate_joker, Card:use_consumable,\n'
        '-- calculating_score guard) lives in Mods/Talisman/talisman.lua.\n'
        '-- Duplicating it here caused double coroutine launch, double love.update\n'
        '-- resume (~2x CPU per scoring frame), and double CARD_CALC_COUNTS increment\n'
        '-- (totalCalcs 2x real, jokersYetToScore goes negative prematurely).\n'
        '-- Note: the 0.3s dim gate (G.SCORING_START) lives in talisman.lua now --\n'
        '-- build.sh apply_talisman_dim_fix patches the overlay check there.'
    )
    dedup_result = re.sub(
        dup_block_pattern,
        dedup_replacement,
        content,
        count=1,
        flags=re.DOTALL,
    )
    if dedup_result != content:
        content = dedup_result
    else:
        print("NOTE: F_NO_COROUTINE duplicate block not found in main.lua — already removed or structure changed")

    # NOTE on the FPS cap and LTPO panels (investigated on-device 2026-06-10):
    # G.FPS_CAP is read once from the window's refresh rate at boot
    # (FPS_CAP_DISPLAY in globals.lua). If the app is launched while an LTPO
    # panel idles at 60Hz the cap freezes at half the panel's real rate for
    # the session. Two runtime fixes were tried and REJECTED with live data:
    # - periodic love.window.getMode() polling: returns the frozen
    #   creation-time value on Android, never moves (dumpsys showed 120Hz
    #   while getMode said 60);
    # - love.window.setVSync(1): paces to the compositor but quantizes frame
    #   times up (measured 42-55fps where the cap gave 60+, heap pressure
    #   visible) — reverted.
    # Human launches always read the true rate (a finger wakes the panel to
    # full rate before the window is created); only idle adb/deploy launches
    # hit the freeze, so deploy() nudges the screen awake before am start.

    # 11. Inject love.focus callback for Android flush-on-background.
    #
    # On Android, the OS may kill the process immediately after backgrounding
    # without any further love.update calls.  G.F_SAVE_TIMER is 30s, so up to
    # 30 seconds of staged-but-unflushed save data can be lost.
    #
    # love.focus(focused) fires during the event-pump phase of the run loop,
    # BEFORE love.update is called on that same frame.  Setting force=true here
    # causes Game:update's FILE_HANDLER flush block to dispatch the channel push
    # on the very next (and possibly last) update call.
    #
    # Guard: only acts on focus loss (not gain), only on Android, and only when
    # G and G.FILE_HANDLER exist (not during boot before save manager starts).
    if '-- Android flush-on-background' not in content:
        focus_callback = """
-- Android flush-on-background: force-dispatch any staged saves when the app
-- loses focus so data survives an immediate OS process kill.
function love.focus(focused)
    if love.system.getOS() ~= 'Android' then return end
    if focused then return end
    if G and G.FILE_HANDLER and G.FILE_HANDLER.update_queued then
        G.FILE_HANDLER.force = true
    end
end
"""
        # Insert immediately after the love.quit function body.
        # The anchor is the closing line of love.quit followed by a blank line.
        quit_anchor = "function love.quit()\n\t--Steam integration\n\tif G.SOUND_MANAGER then G.SOUND_MANAGER.channel:push({type = 'stop'}) end\n\tif G.STEAM then G.STEAM:shutdown() end\nend"
        if quit_anchor in content:
            content = content.replace(quit_anchor, quit_anchor + focus_callback, 1)
        else:
            print("WARNING: love.quit anchor not found — love.focus callback NOT injected")

    # 9b. HID touch classification fix. The run loop dedupes the touchpressed +
    # synthetic-mousepressed pair by holding the mousepressed and dispatching it
    # once with `touched` — a flag set only if a touchpressed arrived in the SAME
    # event-pump batch. When SDL delivers the pair across two batches (seen on
    # the 120Hz foldable), the press dispatches with touched=nil and the
    # controller classifies a finger as a MOUSE: every HID.touch-gated patch
    # (TAP_DESC_RELAX, DRAG_SELECT_*) silently deactivates for that press, and
    # vanilla mouse-hover semantics wedge states.hover.is on the card — the
    # tilt-warp Joe sees. LÖVE already passes its native per-event istouch flag
    # as the mousepressed's 4th arg (_d); vanilla discards it. Trust it.
    if 'HID_ISTOUCH_FIX' not in content:
        old_dispatch = "love.handlers['mousepressed'](_a,_b,_c,touched)"
        new_dispatch = "love.handlers['mousepressed'](_a,_b,_c,touched or _d) -- HID_ISTOUCH_FIX"
        if old_dispatch in content:
            content = content.replace(old_dispatch, new_dispatch, 1)
        else:
            print("WARNING: mousepressed dispatch anchor not found — HID istouch fix NOT applied")

    # 9c. HID touch classification fix — release side.
    #
    # love.mousereleased arrives in the same LOVE event-pump batch as touchreleased.
    # The LOVE boot.lua dispatches mousereleased(x,y,b,t,c) where t=istouch, but the
    # game's love.mousereleased only accepts three parameters and never calls
    # set_HID_flags — so HID.touch is stale at the moment L_cursor_release runs and
    # the controller processes cursor_up.
    #
    # Reads of HID.touch on the release frame that corrupt state if stale-false:
    #   controller.lua TAP_DESC_HOLD_NODRAG (line ~377): guard suppressing
    #     released_on on short touch-drags — stale-false lets a spurious card drop
    #     fire on a short touch-drag release.
    #   controller.lua TAP_DESC_RELAX (line ~447): guard clearing hover on touch
    #     lift — stale-false leaves tooltip/hover state stuck after a tap.
    #
    # Fix mirrors the press-side pattern exactly:
    #   1. Intercept 'touchreleased' in the event loop to set touch_released=true
    #      (still dispatches immediately since it's a nil-guarded no-op anyway).
    #   2. Defer 'mousereleased' like 'mousepressed' is deferred.
    #   3. After the loop, dispatch deferred mousereleased with (touch_released or _rd).
    #   4. love.mousereleased accepts istouch as 4th arg and calls set_HID_flags first.
    #
    # Anchors against the upstream (pre-patch) dump event loop. Section 9b must run
    # first to transform the mousepressed dispatch line; this section then layers on
    # top by replacing the entire locals+loop+dispatch block.
    if 'HID_ISTOUCH_RELEASE_FIX' not in content:
        old_event_loop = (
            "\t\t\tlocal _n,_a,_b,_c,_d,_e,_f,touched\n"
            "\t\t\tfor name, a,b,c,d,e,f in love.event.poll() do\n"
            "\t\t\t\tif name == \"quit\" then\n"
            "\t\t\t\t\tif not love.quit or not love.quit() then\n"
            "\t\t\t\t\t\treturn a or 0\n"
            "\t\t\t\t\tend\n"
            "\t\t\t\tend\n"
            "\t\t\t\tif name == 'touchpressed' then\n"
            "\t\t\t\t\ttouched = true\n"
            "\t\t\t\telseif name == 'mousepressed' then \n"
            "\t\t\t\t\t_n,_a,_b,_c,_d,_e,_f = name,a,b,c,d,e,f\n"
            "\t\t\t\telse\n"
            "\t\t\t\t\tlove.handlers[name](a,b,c,d,e,f)\n"
            "\t\t\t\tend\n"
            "\t\t\tend\n"
            "\t\t\tif _n then \n"
            "\t\t\t\tlove.handlers['mousepressed'](_a,_b,_c,touched or _d) -- HID_ISTOUCH_FIX\n"
            "\t\t\tend"
        )
        new_event_loop = (
            "\t\t\tlocal _n,_a,_b,_c,_d,_e,_f,touched\n"
            "\t\t\tlocal _rn,_ra,_rb,_rc,_rd,touch_released\n"
            "\t\t\tfor name, a,b,c,d,e,f in love.event.poll() do\n"
            "\t\t\t\tif name == \"quit\" then\n"
            "\t\t\t\t\tif not love.quit or not love.quit() then\n"
            "\t\t\t\t\t\treturn a or 0\n"
            "\t\t\t\t\tend\n"
            "\t\t\t\tend\n"
            "\t\t\t\tif name == 'touchpressed' then\n"
            "\t\t\t\t\ttouched = true\n"
            "\t\t\t\telseif name == 'mousepressed' then\n"
            "\t\t\t\t\t_n,_a,_b,_c,_d,_e,_f = name,a,b,c,d,e,f\n"
            "\t\t\t\telseif name == 'touchreleased' then\n"
            "\t\t\t\t\ttouch_released = true\n"
            "\t\t\t\t\tlove.handlers[name](a,b,c,d,e,f)\n"
            "\t\t\t\telseif name == 'mousereleased' then\n"
            "\t\t\t\t\t_rn,_ra,_rb,_rc,_rd = name,a,b,c,d\n"
            "\t\t\t\telse\n"
            "\t\t\t\t\tlove.handlers[name](a,b,c,d,e,f)\n"
            "\t\t\t\tend\n"
            "\t\t\tend\n"
            "\t\t\tif _n then\n"
            "\t\t\t\tlove.handlers['mousepressed'](_a,_b,_c,touched or _d) -- HID_ISTOUCH_FIX\n"
            "\t\t\tend\n"
            "\t\t\tif _rn then\n"
            "\t\t\t\tlove.handlers['mousereleased'](_ra,_rb,_rc,touch_released or _rd) -- HID_ISTOUCH_RELEASE_FIX\n"
            "\t\t\tend"
        )
        if old_event_loop in content:
            content = content.replace(old_event_loop, new_event_loop, 1)
        else:
            print("WARNING: event loop anchor not found — HID istouch release fix NOT applied")

        old_mousereleased = "function love.mousereleased(x, y, button)\n    if button == 1 then G.CONTROLLER:L_cursor_release(x, y) end\nend"
        new_mousereleased = (
            "function love.mousereleased(x, y, button, istouch)\n"
            "    G.CONTROLLER:set_HID_flags(istouch and 'touch' or 'mouse') -- HID_ISTOUCH_RELEASE_FIX\n"
            "    if button == 1 then G.CONTROLLER:L_cursor_release(x, y) end\n"
            "end"
        )
        if old_mousereleased in content:
            content = content.replace(old_mousereleased, new_mousereleased, 1)
        else:
            print("WARNING: love.mousereleased anchor not found — HID istouch release fix NOT applied")

    # 9d. HID touch classification fix — move side.
    #
    # love.mousemoved receives istouch as the 5th arg (confirmed from LOVE boot.lua:
    # mousemoved = function(x,y,dx,dy,t)), but the original implementation ignored it
    # in favour of a live love.touch.getTouches() query gated by a 0.2s window stored
    # in last_touch_time.  The window approach works correctly for active drags (finger
    # still down keeps getTouches() non-empty, refreshing last_touch_time on every
    # mousemoved), but it is more complex and indirect than necessary: LOVE already
    # provides the correct istouch flag per-event from the same source as the press and
    # release fixes above.  Using it directly removes the getTouches() overhead, the
    # last_touch_time accumulator, and the 0.2s timing dependency.
    if 'HID_ISTOUCH_MOVE_FIX' not in content:
        old_mousemoved = (
            "function love.mousemoved(x, y, dx, dy, istouch)\n"
            "\tG.CONTROLLER.last_touch_time = G.CONTROLLER.last_touch_time or -1\n"
            "\tif next(love.touch.getTouches()) ~= nil then\n"
            "\t\tG.CONTROLLER.last_touch_time = G.TIMERS.UPTIME\n"
            "\tend\n"
            "    G.CONTROLLER:set_HID_flags(G.CONTROLLER.last_touch_time > G.TIMERS.UPTIME - 0.2 and 'touch' or 'mouse')\n"
            "end"
        )
        new_mousemoved = (
            "function love.mousemoved(x, y, dx, dy, istouch)\n"
            "    G.CONTROLLER:set_HID_flags(istouch and 'touch' or 'mouse') -- HID_ISTOUCH_MOVE_FIX\n"
            "end"
        )
        if old_mousemoved in content:
            content = content.replace(old_mousemoved, new_mousemoved, 1)
        else:
            print("WARNING: love.mousemoved anchor not found — HID istouch move fix NOT applied")


    if '-- Trigger-cascade collapsing' not in content:
        content += """
-- Trigger-cascade collapsing: pure module, hooks self-install on the first
-- frame (SMODS loads after this chunk). NOT Android-gated: the desktop
-- harnesses exercise the same code, and the engine is platform-free.
local tc_ok, tc_err = pcall(function()
    local chunk = love.filesystem.load('trigger-collapse.lua')
    if chunk then chunk() end
end)
if not tc_ok then print('[TC] LOAD_FAILED error=' .. tostring(tc_err)) end
"""

    if '-- Idle-joker perf' not in content:
        content += """
-- Idle-joker perf: align_cards sort gate + handle_card_limit cache. Pure
-- module; hooks self-install on the first Game:update tick (after SMODS).
-- Kill switch: G.SETTINGS.idle_joker_perf = false. NOT Android-gated: the
-- desktop harnesses exercise the same code. (The module was copied into the
-- build but never loaded until this block — docs/REVIEW-2026-07-01.md §3.)
local ijp_ok, ijp_err = pcall(function()
    local chunk = love.filesystem.load('idle-joker-perf.lua')
    if chunk then chunk() end
end)
if not ijp_ok then print('[IJP] LOAD_FAILED error=' .. tostring(ijp_err)) end
"""

    if '-- Lazy shader binding' not in content:
        content += """
-- Lazy shader binding (Tier-2a): must load BEFORE android-telemetry so the
-- telemetry setShader wrapper counts game-issued calls while the lazy
-- flush talks to the captured original. NOT Android-gated: the desktop
-- harnesses exercise the same draw path.
local ls_ok, ls_err = pcall(function()
    local chunk = love.filesystem.load('lazy-shader.lua')
    if chunk then chunk() end
end)
if not ls_ok then print('[LS] LOAD_FAILED error=' .. tostring(ls_err)) end
"""

    if '-- Android telemetry: load after all game hooks are set up' not in content:
        content += """
-- Android telemetry: load after all game hooks are set up
if love.system.getOS() == 'Android' then
    local tel_ok, tel_err = pcall(function()
        local chunk = love.filesystem.load('android-telemetry.lua')
        if chunk then chunk() end
    end)
    -- deliberately ungated by the Debug Logging setting: this is the one
    -- last-resort diagnostic for a BROKEN telemetry build (the gate lives in
    -- the chunk that just failed to load); it never fires on a healthy build
    if not tel_ok then print('[TEL] LOAD_FAILED error=' .. tostring(tel_err)) end
end
"""

    if '-- Emulator smoke-check: load after everything else' not in content:
        content += """
-- Emulator smoke-check: load after everything else so RunSelect pages and
-- localization are fully registered by the time it runs its checks. Dormant
-- on every real device/player build (self-gates on an EMULATOR_SMOKE_TEST
-- marker file test/emulator/run.sh pushes before launch) - see
-- patches/emulator-smoke-check.lua.
local esc_ok, esc_err = pcall(function()
    local chunk = love.filesystem.load('emulator-smoke-check.lua')
    if chunk then chunk() end
end)
if not esc_ok then print('[ESC] LOAD_FAILED error=' .. tostring(esc_err)) end
"""

    # 12. (removed) DEBUGPLUS_CONSOLE_DISABLED — DebugPlus is cut from the build;
    # the dump is regenerated without it (nix/regen-dump.sh + stage-mods.sh), so
    # console.doConsoleRender() no longer exists in main.lua and needs no neutralizing.

    with open(filepath, 'w') as f:
        f.write(content)

    print("Patched successfully")

if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <main.lua>")
        sys.exit(1)
    patch_main_lua(sys.argv[1])
