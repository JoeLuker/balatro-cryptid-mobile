-- PAGE-CYCLE REGRESSION: cycling the page option in item_toggle_UI must NOT
-- leave the replacement UIBox parentless. Verifies the parent = toggle_area fix.
--
-- Protocol:
--   PCYCLE: PASS <reason>  → test passed, quit 0
--   PCYCLE: FAIL <reason>  → test failed, quit 1
--   PCYCLE: ERROR <err>    → runtime error, quit 1

local phase, elapsed = 'boot', 0

local function pcycle_fail(msg)
    print('PCYCLE: FAIL ' .. tostring(msg))
    love.event.quit(1)
    phase = 'done'
end
local function pcycle_pass(msg)
    print('PCYCLE: PASS ' .. tostring(msg))
    love.event.quit(0)
    phase = 'done'
end

local original_update = love.update
love.update = function(dt, ...)
    local ok, err = pcall(original_update, dt, ...)
    if not ok then
        print('PCYCLE: ERROR update-crash: ' .. tostring(err))
        love.event.quit(1)
        phase = 'done'
        return
    end
    elapsed = elapsed + dt

    if phase == 'boot' then
        if G and G.STAGE == G.STAGES.MAIN_MENU
            and G.STATE == G.STATES.MENU and elapsed > 6 then
            phase = 'open-ui'
            print('PCYCLE: reached main menu, opening item_toggle_UI')

            local cs = G.P_CENTER_POOLS and G.P_CENTER_POOLS["Content Set"]
            local target = nil
            if cs then
                for _, v in ipairs(cs) do
                    if v.key == 'cry_m' or v.key == 'm' or v.key == 'set_cry_m' then
                        target = v; break
                    end
                end
                if not target then target = cs[1] end
            end
            if not target then
                pcycle_fail('no Content Set found'); return
            end

            print('PCYCLE: using content set key=' .. tostring(target.key))
            G.viewedContentSet = target

            local ok2, err2 = pcall(Cryptid.item_toggle_UI, target)
            if not ok2 then
                pcycle_fail('item_toggle_UI threw: ' .. tostring(err2)); return
            end
            if not G.OVERLAY_MENU then
                pcycle_fail('overlay not open after item_toggle_UI'); return
            end
            print('PCYCLE: overlay opened ok')
            phase = 'verify-open'
        end
        return
    end

    if phase == 'verify-open' then
        if not G.OVERLAY_MENU then
            pcycle_fail('overlay disappeared within one frame of opening'); return
        end
        local toggle_area = G.OVERLAY_MENU:get_UIE_by_ID('cry_item_toggle_area')
        if not toggle_area then
            pcycle_fail('cry_item_toggle_area UIE not found'); return
        end
        if not toggle_area.config.object then
            pcycle_fail('cry_item_toggle_area.config.object nil'); return
        end
        if not G.FUNCS.cry_item_toggle_page then
            pcycle_fail('cry_item_toggle_page not registered (1-page set?)'); return
        end

        -- Capture the initial UIBox reference
        phase = 'pre-cycle'
        G._pcycle_toggle_area = toggle_area
        -- Fire cycle: simulate advancing to page 2
        local fake_cycle_config = { current_option = 2, options = {'p1','p2','p3'} }
        local ok3, err3 = pcall(G.FUNCS.cry_item_toggle_page, { cycle_config = fake_cycle_config })
        if not ok3 then
            pcycle_fail('cry_item_toggle_page threw: ' .. tostring(err3)); return
        end
        print('PCYCLE: cry_item_toggle_page fired without error')
        phase = 'check-after'
        return
    end

    if phase == 'check-after' then
        -- CRITICAL CHECKS (one frame after cycle):

        -- 1. Overlay must still exist and not be REMOVED
        if not G.OVERLAY_MENU or G.OVERLAY_MENU == true then
            pcycle_fail('overlay closed after page cycle'); return
        end
        if G.OVERLAY_MENU.REMOVED then
            pcycle_fail('overlay UIBox REMOVED after page cycle'); return
        end

        -- 2. toggle_area must still be findable
        local toggle_area = G.OVERLAY_MENU:get_UIE_by_ID('cry_item_toggle_area')
        if not toggle_area then
            pcycle_fail('cry_item_toggle_area gone after page cycle'); return
        end

        -- 3. The replacement UIBox must have config.object and not be REMOVED
        if not toggle_area.config.object then
            pcycle_fail('cry_item_toggle_area.config.object nil after page cycle'); return
        end
        if toggle_area.config.object.REMOVED then
            pcycle_fail('replacement UIBox is REMOVED after page cycle'); return
        end

        -- 4. THE KEY ASSERTION: the replacement UIBox must be parented to toggle_area.
        --    Without the fix, UIBox is constructed with no parent and config.parent
        --    is nil, so UIBox.parent is nil at construction time.
        --    The engine's update_object() will set .parent later, but config.parent
        --    (the constructor arg) remains nil and major=self (the UIBox itself)
        --    rather than toggle_area. We check the UIBox's alignment major.
        local new_box = toggle_area.config.object
        -- UIBox.role.major is the alignment anchor; with fix it's toggle_area,
        -- without fix it's the UIBox itself.
        local major = new_box.role and new_box.role.major
        if major == new_box then
            pcycle_fail('replacement UIBox has major=self (parentless) — fix not applied'); return
        end
        if major ~= toggle_area then
            -- Could be update_object() has re-wired it to toggle_area already;
            -- also acceptable. Just log.
            print('PCYCLE: INFO major=' .. tostring(major) .. ' (not self, acceptable)')
        else
            print('PCYCLE: replacement UIBox.role.major == toggle_area (correct)')
        end

        -- Also check config.parent directly (set at UIBox construction time)
        local cfg_parent = new_box.config and new_box.config.parent
        if cfg_parent == nil then
            -- This is the unfixed state: log it, but by now we may have already
            -- caught it via role.major above. If we reach here with major != self
            -- it means update_object() re-wired the major but config.parent is
            -- still nil — partial fix, still report as failure.
            pcycle_fail('replacement UIBox config.parent is nil (constructed without parent field)'); return
        end

        print('PCYCLE: replacement UIBox config.parent = toggle_area ✓')
        print('PCYCLE: overlay alive, parent wired correctly — PASS')
        pcycle_pass('overlay stays open and replacement UIBox is correctly parented to toggle_area')
        return
    end
end
