-- ANDROID_RESIZE_CONTAIN harness: boot the built game (Android paths faked)
-- to the menu, then drive love.resize through the foldable's real surface
-- geometries and assert the contain invariants after each:
--   - TILESCALE positive and finite
--   - the room's pixel extent fits inside the effective surface
--   - G.CANVAS matches the effective surface (real h for ratios >= 0.4;
--     sliver-clamped h below that)
--   - idempotence: A -> B -> A reproduces A's geometry exactly
-- Run via test/resize/run.sh. Prints RSZ: lines; PASS/FAIL verdict.
local elapsed, phase = 0, 'boot'
local fails = {}
print('RSZ: loaded')

local function chk(cond, label)
    if cond then
        print('RSZ: ok ' .. label)
    else
        fails[#fails + 1] = label
        print('RSZ: BADCHK ' .. label)
    end
end

-- {w, h, label} — inner landscape/portrait, cover landscape/portrait
-- (px and the dp dims the device actually reports), half-split, ribbon
-- sliver (exercises the 0.4 clamp)
local GEOMS = {
    { 2208, 1840, 'inner-landscape' },
    { 1840, 2208, 'inner-portrait' },
    { 2424, 1080, 'cover-landscape' },
    { 1080, 2424, 'cover-portrait' },
    { 411, 722, 'cover-portrait-dp' },
    { 1104, 1840, 'half-split' },
    { 600, 1840, 'ribbon-sliver' },
}

local function effective_h(w, h)
    if w / h < 0.4 then return w / 0.4 end
    return h
end

local function geometry_snapshot()
    return {
        tilescale = G.TILESCALE,
        room_x = G.ROOM and G.ROOM.T.x, room_y = G.ROOM and G.ROOM.T.y,
        canvas_w = G.CANVAS and G.CANVAS:getWidth(), canvas_h = G.CANVAS and G.CANVAS:getHeight(),
    }
end

local function assert_geometry(w, h, label)
    local eh = effective_h(w, h)
    local ts = G.TILESCALE
    chk(type(ts) == 'number' and ts > 0 and ts < math.huge, label .. ':tilescale-sane (' .. tostring(ts) .. ')')
    if G.ROOM then
        local room_px_w = G.ROOM.T.w * G.TILESIZE * ts
        local room_px_h = G.ROOM.T.h * G.TILESIZE * ts
        chk(room_px_w <= w + 2, label .. ':room-fits-w (' .. math.floor(room_px_w) .. '<=' .. w .. ')')
        chk(room_px_h <= eh + 2, label .. ':room-fits-h (' .. math.floor(room_px_h) .. '<=' .. math.floor(eh) .. ')')
        chk(G.ROOM.T.x >= -0.01 and G.ROOM.T.y >= -0.01, label .. ':room-on-screen')
    end
    if G.CANVAS then
        chk(math.abs(G.CANVAS:getWidth() - w * G.CANV_SCALE) <= 1, label .. ':canvas-w')
        chk(math.abs(G.CANVAS:getHeight() - eh * G.CANV_SCALE) <= 1, label .. ':canvas-h ('
            .. G.CANVAS:getHeight() .. ' vs ' .. math.floor(eh * G.CANV_SCALE) .. ')')
    end
end

local gu = love.update
love.update = function(dt, ...)
    gu(dt, ...)
    elapsed = elapsed + dt
    if phase == 'boot' and G and G.STAGE == G.STAGES.MAIN_MENU
        and G.STATE == G.STATES.MENU and elapsed > 5 then
        phase = 'run'
        for _, g in ipairs(GEOMS) do
            local ok, err = pcall(love.resize, g[1], g[2])
            chk(ok, g[3] .. ':resize-no-error (' .. tostring(err) .. ')')
            if ok then assert_geometry(g[1], g[2], g[3]) end
        end
        -- idempotence: inner-landscape -> cover-landscape -> inner-landscape
        pcall(love.resize, 2208, 1840)
        local a1 = geometry_snapshot()
        pcall(love.resize, 2424, 1080)
        pcall(love.resize, 2208, 1840)
        local a2 = geometry_snapshot()
        for k, v in pairs(a1) do
            chk(a2[k] == v, 'idempotent:' .. k .. ' (' .. tostring(v) .. ' vs ' .. tostring(a2[k]) .. ')')
        end
        phase = 'verdict'
    end
    if phase == 'verdict' then
        if #fails == 0 then
            print('RSZ: PASS')
            love.event.quit(0)
        else
            print('RSZ: FAIL (' .. #fails .. '): ' .. table.concat(fails, ', '))
            love.event.quit(1)
        end
        phase = 'done'
    end
    if elapsed > 90 and phase ~= 'done' then
        print('RSZ: FAIL timeout phase=' .. phase)
        love.event.quit(1)
        phase = 'done'
    end
end
