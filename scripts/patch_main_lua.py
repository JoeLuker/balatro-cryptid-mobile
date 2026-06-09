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

    # Check if already fully patched (all three sentinel strings must be present).
    already_android = '-- Android SMODS path fix' in content
    already_dedup = 'Duplicate Talisman coroutine harness' in content
    already_focus = '-- Android flush-on-background' in content
    if already_android and already_dedup and already_focus:
        print("Already patched")
        return

    # 1. Add require path fix and package.preload before SMODS = {}
    smods_init = "SMODS = {}"
    path_fix = """-- Android SMODS path fix: preload SMODS modules and add Mods directories to require path
if love.system.getOS() == 'Android' then
    -- Preload SMODS.version and SMODS.release to map to Steamodded root files
    package.preload['SMODS.version'] = function() return love.filesystem.load('Mods/Steamodded/version.lua')() end
    package.preload['SMODS.release'] = function() return love.filesystem.load('Mods/Steamodded/release.lua')() end
    -- Add paths for mod requires including Steamodded libs (json, nativefs, https)
    -- LÖVE uses love.filesystem require path, not Lua package.path
    local love_paths = 'Mods/Steamodded/libs/?.lua;Mods/Steamodded/libs/?/init.lua;Mods/Steamodded/?.lua;Mods/Steamodded/?/init.lua;Mods/?.lua;Mods/?/init.lua'
    love.filesystem.setRequirePath(love.filesystem.getRequirePath() .. ';' .. love_paths)
end

SMODS = {}"""
    content = content.replace(smods_init, path_fix, 1)

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
    for _, name in ipairs({'Cryptid', 'Steamodded', 'Talisman', 'lovely', 'sticky-fingers'}) do
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
    write_replacement = """pcall(function() if love.system.getOS() ~= 'Android' then nativefs.write(talisman_path .. "/config.lua", STR_PACK(Talisman.config_file)) end end)"""
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
        '-- Note: G.SCORING_START is not written here because nothing reads it --\n'
        '-- talisman\'s love.update overlay check (talisman.lua:715) has no time gate.'
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
    if focused then return end
    if love.system.getOS() ~= 'Android' then return end
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

    # 9. Inject telemetry loader at end of file (after all game setup)
    if '-- Android telemetry: load after all game hooks are set up' not in content:
        content += """
-- Android telemetry: load after all game hooks are set up
if love.system.getOS() == 'Android' then
    local tel_ok, tel_err = pcall(function()
        local chunk = love.filesystem.load('android-telemetry.lua')
        if chunk then chunk() end
    end)
    if not tel_ok then print('[TEL] LOAD_FAILED error=' .. tostring(tel_err)) end
end
"""

    with open(filepath, 'w') as f:
        f.write(content)

    print("Patched successfully")

if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <main.lua>")
        sys.exit(1)
    patch_main_lua(sys.argv[1])
