-- UI_O_NIL_DIMS repro: a UIT.O node whose config.object was detached
-- (mid-teardown) gets T.w/T.h = nil written by calculate_xywh on
-- recalculate; the next frame's move_wh crashes. Mirrors the fold-close
-- field crash (who=UIElement T(x,y,nil,nil)).
local phase, elapsed = 'boot', 0
local gu = love.update
love.update = function(dt, ...)
    local ok, err = pcall(gu, dt, ...)
    if not ok then
        print('UIO: UPDATE-ERROR ' .. tostring(err))
        love.event.quit(tostring(err):find('moveable') and 42 or 1)
        phase = 'done'
        return
    end
    elapsed = elapsed + dt
    if phase == 'boot' and G and G.STAGE == G.STAGES.MAIN_MENU
        and G.STATE == G.STATES.MENU and elapsed > 6 then
        phase = 'armed'
        local atlas = G.ASSET_ATLAS and (G.ASSET_ATLAS['White'] or select(2, next(G.ASSET_ATLAS)))
        local spr = Sprite(0, 0, 1, 1, atlas, { x = 0, y = 0 })
        G.uio_box = UIBox{
            definition = { n = G.UIT.ROOT, config = { align = 'cm', minw = 2, minh = 2, colour = G.C.CLEAR }, nodes = {
                { n = G.UIT.O, config = { object = spr } },
            } },
            config = { major = G.ROOM_ATTACH, align = 'cm', bond = 'Weak' },
        }
        local o_node = G.uio_box.UIRoot.children[1]
        o_node.config.object = nil -- teardown detaches the object
        local rok, rerr = pcall(function() G.uio_box:recalculate() end)
        print('UIO: recalc ok=' .. tostring(rok) .. ' err=' .. tostring(rerr))
        print('UIO: post-recalc T.w=' .. tostring(o_node.T.w) .. ' T.h=' .. tostring(o_node.T.h))
    elseif phase == 'armed' and elapsed > 8 then
        local o_node = G.uio_box.UIRoot.children[1]
        local numeric = type(o_node.T.w) == 'number' and type(o_node.T.h) == 'number'
        print('UIO: ' .. (numeric and 'PASS dims-numeric survived' or 'FAIL dims nil'))
        love.event.quit(numeric and 0 or 1)
        phase = 'done'
    end
end
