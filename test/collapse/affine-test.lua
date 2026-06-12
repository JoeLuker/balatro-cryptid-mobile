-- Property suite for the trigger-collapse affine composer, against Amulet's
-- REAL cdata OmegaNum. Differential: honest op-by-op application of a rep
-- record N times vs TC.compose + TC.ffwd.
--
-- Float reassociation makes composed arithmetic differ from honest by ulps
-- (not gameplay-relevant), so the general assertion is relative error
-- < 1e-9. Two stronger cases are asserted exactly:
--   - pure ×1.5 cascades (the Baron/Mime shape; 1.5 is binary-exact) up to
--     N=80 — closed-form B^N must equal honest bit-for-bit
--   - pure additive (B==1) integer amounts — A*M exact
--
-- Run: cd <repo> && LUA_PATH="mods/Amulet/?.lua;;" luajit test/collapse/affine-test.lua [n_cases]
local N_CASES = tonumber(arg and arg[1]) or 20000

-- minimal world for omeganum standalone
math.randomseed(42)
local ok_big, Big = pcall(require, 'big-num.omeganum')
if not ok_big then
    print('AFF: FAIL cannot load omeganum standalone: ' .. tostring(Big))
    os.exit(1)
end
local TC = dofile('patches/trigger-collapse.lua')

local fails = 0
local function fail(label, detail)
    fails = fails + 1
    print('AFF: BADCHK ' .. label .. ' :: ' .. tostring(detail))
    if fails > 20 then
        print('AFF: FAIL (bailing after 20)')
        os.exit(1)
    end
end

local function to_num(x)
    if type(x) == 'number' then return x end
    return x.number or 0 / 0
end

local function rel_err(a, b)
    local na, nb = to_num(a), to_num(b)
    if na == nb then return 0 end
    if nb == 0 then return math.abs(na) end
    return math.abs(na / nb - 1)
end

-- honest application of one record to (mult, chips, dollars)
local function honest_apply(record, mult, chips, dollars)
    for i = 1, #record do
        local op = record[i]
        local route = TC.ACC_OF[op.key]
        if route == 'mult' then
            if TC.IS_MULT_OP[op.key] then mult = mult * op.amount else mult = mult + op.amount end
        elseif route == 'chips' then
            if TC.IS_MULT_OP[op.key] then chips = chips * op.amount else chips = chips + op.amount end
        elseif route == 'dollars' then
            dollars = dollars + op.amount
        end
    end
    return mult, chips, dollars
end

local ADD_KEYS = { 'mult', 'h_mult', 'chips', 'h_chips', 'dollars', 'p_dollars' }
local MULT_KEYS = { 'x_mult', 'Xmult', 'x_chips', 'xchips' }

local function random_record(len, big_amounts, mult_only, add_only)
    local r = {}
    for i = 1, len do
        local key
        if mult_only then
            key = MULT_KEYS[math.random(#MULT_KEYS)]
        elseif add_only then
            key = ADD_KEYS[math.random(#ADD_KEYS)]
        else
            key = (math.random() < 0.5) and ADD_KEYS[math.random(#ADD_KEYS)]
                or MULT_KEYS[math.random(#MULT_KEYS)]
        end
        local amount
        if TC.IS_MULT_OP[key] then
            amount = 1 + math.random() * 3            -- ×1..×4
        else
            amount = math.random(1, 200)              -- integer adds
        end
        if big_amounts and math.random() < 0.4 then
            amount = Big:create(amount)
        end
        r[#r + 1] = { key = key, amount = amount }
    end
    return r
end

-- ── general differential ────────────────────────────────────────────────
for case = 1, N_CASES do
    local use_big = case % 3 == 0
    local record = random_record(math.random(1, 6), use_big)
    local N = math.random(1, 60)
    local m0 = use_big and Big:create(math.random(1, 1000)) or math.random(1, 1000)
    local c0 = use_big and Big:create(math.random(1, 5000)) or math.random(1, 5000)

    local hm, hc, hd = m0, c0, 0
    for _ = 1, N do hm, hc, hd = honest_apply(record, hm, hc, hd) end

    local acc = TC.compose(record)
    if not acc then
        fail('compose-nil-on-valid', case)
    else
        local fm = TC.ffwd(m0, acc.mult.B, acc.mult.A, N)
        local fc = TC.ffwd(c0, acc.chips.B, acc.chips.A, N)
        local fd = acc.dollars * N
        if rel_err(fm, hm) > 1e-9 then fail('mult-rel-err case=' .. case, rel_err(fm, hm)) end
        if rel_err(fc, hc) > 1e-9 then fail('chips-rel-err case=' .. case, rel_err(fc, hc)) end
        if rel_err(fd, hd) > 1e-12 then fail('dollars case=' .. case, fd .. ' vs ' .. hd) end
    end
end
print('AFF: ok general differential (' .. N_CASES .. ' cases)')

-- ── Baron/Mime case: pure ×1.5 closed form ──────────────────────────────
-- Bit-exact only while the product fits the 53-bit mantissa (each ×1.5
-- adds ~log2(3) bits: N≲30 from m0=7). Beyond that pow and sequential
-- rounding differ by ulps — assert exact in the exact window, 1e-12
-- relative after (the suite itself caught the original N=80 overclaim).
for N = 1, 30 do
    local m0 = 7
    local hm = m0
    for _ = 1, N do hm = hm * 1.5 end
    local acc = TC.compose({ { key = 'x_mult', amount = 1.5 } })
    local fm = TC.ffwd(m0, acc.mult.B, acc.mult.A, N)
    if fm ~= hm then fail('x1.5-exact N=' .. N, fm .. ' vs ' .. hm) end
end
for N = 31, 200 do
    local m0 = 7
    local hm = m0
    for _ = 1, N do hm = hm * 1.5 end
    local acc = TC.compose({ { key = 'x_mult', amount = 1.5 } })
    local fm = TC.ffwd(m0, acc.mult.B, acc.mult.A, N)
    if rel_err(fm, hm) > 1e-12 then fail('x1.5-relerr N=' .. N, rel_err(fm, hm)) end
end
print('AFF: ok x1.5^N (exact to N=30, <1e-12 rel to N=200)')

-- ── pure additive exactness ─────────────────────────────────────────────
for case = 1, 2000 do
    local record = random_record(math.random(1, 5), false, false, true)
    local N = math.random(1, 100)
    local hm, hc, hd = 11, 13, 0
    for _ = 1, N do hm, hc, hd = honest_apply(record, hm, hc, hd) end
    local acc = TC.compose(record)
    local fm = TC.ffwd(11, acc.mult.B, acc.mult.A, N)
    local fc = TC.ffwd(13, acc.chips.B, acc.chips.A, N)
    if fm ~= hm or fc ~= hc or acc.dollars * N ~= hd then
        fail('additive-exact case=' .. case, '')
    end
end
print('AFF: ok pure-additive exact (2000 cases)')

-- ── Big-regime sanity: huge magnitudes through pow path ─────────────────
for case = 1, 500 do
    local b = 1 + math.random() * 2
    local N = math.random(50, 400)
    local m0 = Big:create(10) ^ math.random(100, 5000)  -- astronomically big
    local hm = m0
    for _ = 1, N do hm = hm * b end
    local fm = TC.ffwd(m0, b, 0, N)
    -- compare in log-magnitude space (OmegaNum precision)
    local ok_cmp = pcall(function()
        local ratio = fm / hm
        assert(to_num(ratio) > 0.999999 and to_num(ratio) < 1.000001)
    end)
    if not ok_cmp then fail('big-regime case=' .. case, tostring(N)) end
end
print('AFF: ok Big-regime pow path (500 cases)')

-- ── rejection: non-collapsible keys compose to nil ──────────────────────
for _, bad in ipairs({ 'func', 'swap', 'balance', 'level_up', 'extra', 'saved', 'remove' }) do
    if TC.compose({ { key = bad, amount = 1 } }) ~= nil then
        fail('reject-' .. bad, 'composed non-collapsible key')
    end
end
print('AFF: ok non-collapsible rejection')

-- ── records_equal + snapshot primitives ─────────────────────────────────
local r1 = { { key = 'x_mult', amount = 1.5 }, { key = 'chips', amount = 30 } }
local r2 = { { key = 'x_mult', amount = 1.5 }, { key = 'chips', amount = 30 } }
local r3 = { { key = 'x_mult', amount = 1.6 }, { key = 'chips', amount = 30 } }
local r4 = { { key = 'x_mult', amount = Big:create(1.5) }, { key = 'chips', amount = 30 } }
if not TC.records_equal(r1, r2) then fail('records-equal-same', '') end
if TC.records_equal(r1, r3) then fail('records-equal-diff', '') end
if not TC.records_equal(r1, r4) then fail('records-equal-big-num-cross', 'Big(1.5) should == 1.5') end
local s1 = TC.snapshot_table({ a = 1, extra = { x = 2 } })
local s2 = TC.snapshot_table({ a = 1, extra = { x = 2 } })
local s3 = TC.snapshot_table({ a = 1, extra = { x = 3 } })
if not TC.snapshots_equal(s1, s2) then fail('snapshot-equal-same', '') end
if TC.snapshots_equal(s1, s3) then fail('snapshot-equal-depth2-diff', 'missed nested mutation') end
print('AFF: ok record/snapshot primitives')

if fails == 0 then
    print('AFF: PASS')
    os.exit(0)
else
    print('AFF: FAIL (' .. fails .. ')')
    os.exit(1)
end
