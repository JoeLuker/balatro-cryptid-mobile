-- NUGC v2 harness: balloon the heap past 200MB at the menu, transition
-- into a breath state (-7777 -> MENU; a negative sentinel matches no dispatch branch so the
-- game idles), and assert the opportunistic full collect fires. Then
-- re-balloon and transition again inside the 30s debounce window and
-- assert it does NOT fire. Run via test/nugc/run.sh.
local elapsed, phase, phase_t = 0, 'boot', 0
local fails, balloon, lf1 = {}, nil, nil
print('NGC: loaded')

local function chk(cond, label)
    if cond then print('NGC: ok ' .. label)
    else fails[#fails + 1] = label print('NGC: BADCHK ' .. label) end
end

local function mb() return collectgarbage('count') / 1024 end

local function fill_balloon()
    balloon = {}
    local i = 0
    while mb() < 250 do
        i = i + 1
        balloon[i] = string.rep('x', 1000) .. i
    end
    balloon = nil -- all of it is garbage now
    return mb()
end

local gu = love.update
love.update = function(dt, ...)
    local ok, err = pcall(gu, dt, ...)
    if not ok then
        print('NGC: UPDATE-ERROR ' .. tostring(err))
        love.event.quit(1)
        phase = 'done'
        return
    end
    elapsed = elapsed + dt

    if phase == 'boot' and G and G.STAGE == G.STAGES.MAIN_MENU
        and G.STATE == G.STATES.MENU and elapsed > 6 then
        local m0 = fill_balloon()
        chk(m0 > 230, 'ballooned (' .. math.floor(m0) .. 'MB)')
        G.STATE = -7777 -- inert limbo (999 is taken: SMODS_BOOSTER_OPENED)
        phase, phase_t = 'limbo1', elapsed
    elseif phase == 'limbo1' then
        G.STATE = G.STATES.MENU -- breath-state entry: full collect expected
        phase, phase_t = 'check1', elapsed
    elseif phase == 'check1' and elapsed - phase_t > 1.5 then
        local m = mb()
        chk(m < 120, 'full-collect-fired (now ' .. math.floor(m) .. 'MB)')
        chk(NUGC_ST and NUGC_ST.last_full > 0, 'last_full-stamped')
        lf1 = NUGC_ST and NUGC_ST.last_full
        local m1 = fill_balloon()
        chk(m1 > 230, 're-ballooned (' .. math.floor(m1) .. 'MB)')
        G.STATE = -7777
        phase, phase_t = 'limbo2', elapsed
    elseif phase == 'limbo2' then
        G.STATE = G.STATES.MENU -- inside debounce: must NOT full-collect
        phase, phase_t = 'check2', elapsed
    elseif phase == 'check2' and elapsed - phase_t > 1.5 then
        -- the escalated per-frame budget may legitimately drain the balloon
        -- (dead garbage collects fast), so assert the debounce directly:
        -- no second FULL collect means last_full is unchanged
        chk(NUGC_ST and NUGC_ST.last_full == lf1, 'debounce-held (last_full unchanged)')
        phase = 'verdict'
    end

    if phase == 'verdict' then
        if #fails == 0 then print('NGC: PASS') love.event.quit(0)
        else print('NGC: FAIL (' .. #fails .. '): ' .. table.concat(fails, ', ')) love.event.quit(1) end
        phase = 'done'
    end
    if elapsed > 90 and phase ~= 'done' then
        print('NGC: FAIL timeout phase=' .. phase)
        love.event.quit(1)
        phase = 'done'
    end
end
