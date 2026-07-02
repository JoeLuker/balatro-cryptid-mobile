-- Emulator smoke-check: baked into every build, but dormant unless armed.
-- test/emulator/run.sh pushes an EMULATOR_SMOKE_TEST marker file into the
-- save dir before launch; when present, this runs a battery of specific
-- regression checks once boot reaches the main menu, writes PASS/FAIL lines
-- to emulator_smoke_results.txt, then quits — no manual on-device diagnosis
-- needed for the class of bug this session spent hours chasing by hand
-- (a RunSelect page silently missing its localization/pool wiring).
--
-- Add a check by appending to CHECKS: {name = "...", fn = function()
-- return true end / return false, "reason" end}. fn runs in a pcall, so a
-- Lua error in a check counts as that check failing, not a harness crash.

if love.system.getOS() ~= 'Android' then return end

local MARKER = 'EMULATOR_SMOKE_TEST'
local RESULTS_FILE = 'emulator_smoke_results.txt'

if not love.filesystem.getInfo(MARKER) then return end

local CHECKS = {
    {
        name = 'runselect_casl_sleeve_choice_registered',
        fn = function()
            local pages = SMODS and SMODS.RunSelect and SMODS.RunSelect.Internals
                and SMODS.RunSelect.Internals.pages
            if not pages then return false, 'SMODS.RunSelect.Internals.pages missing' end
            for _, k in ipairs(pages) do
                if k == 'casl_sleeve_choice' then return true end
            end
            return false, 'casl_sleeve_choice not in pages: ' .. table.concat(pages, ',')
        end,
    },
    {
        name = 'runselect_casl_sleeve_choice_localized',
        fn = function()
            local d = G.localization and G.localization.misc and G.localization.misc.dictionary
            if not d then return false, 'no misc.dictionary table' end
            if d.run_select_casl_sleeve_choice == nil then
                return false, 'run_select_casl_sleeve_choice is nil'
            end
            if d.run_select_casl_sleeve_choice_random == nil then
                return false, 'run_select_casl_sleeve_choice_random is nil'
            end
            return true
        end,
    },
}

local elapsed = 0
local done = false

local function write_results(text)
    love.filesystem.write(RESULTS_FILE, text)
end

local original_update = love.update
love.update = function(dt, ...)
    local ok, err = pcall(original_update, dt, ...)
    if not ok then
        write_results('FAIL update-error: ' .. tostring(err) .. '\n')
        love.event.quit()
        return
    end
    elapsed = elapsed + dt

    if not done and G and G.STAGE == G.STAGES.MAIN_MENU
        and G.STATE == G.STATES.MENU and elapsed > 5 then
        done = true
        local lines, all_pass = {}, true
        for _, check in ipairs(CHECKS) do
            local ok2, pass, reason = pcall(check.fn)
            if not ok2 then
                lines[#lines + 1] = 'FAIL ' .. check.name .. ' (error: ' .. tostring(pass) .. ')'
                all_pass = false
            elseif pass then
                lines[#lines + 1] = 'PASS ' .. check.name
            else
                lines[#lines + 1] = 'FAIL ' .. check.name .. ' (' .. tostring(reason) .. ')'
                all_pass = false
            end
        end
        lines[#lines + 1] = all_pass and 'OVERALL PASS' or 'OVERALL FAIL'
        write_results(table.concat(lines, '\n') .. '\n')
        love.event.quit()
    elseif not done and elapsed > 60 then
        write_results('FAIL timeout waiting for menu\n')
        love.event.quit()
    end
end
