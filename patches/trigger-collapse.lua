-- Trigger-cascade collapsing ("RLE" for repeated identical triggers).
--
-- Deep runs replay the same trigger cascade hundreds of times (Mime/Baron
-- retriggers, Observatory+Perkeo copies): SMODS.score_card and
-- SMODS.calculate_end_of_round_effects re-run eval_card + the ENTIRE
-- joker-area chain once per repetition. This module proves consecutive
-- repetitions identical, then fast-forwards the remainder algebraically.
--
-- Self-verifying, never trusted blindly:
--   rep 1        honest (builds the reps list)
--   reps 2, 3    honest + recorded through the calculate_individual_effect
--                chokepoint + depth-2 snapshots of joker ability state and
--                G.GAME.current_round
--   reps 4..N-1  collapsed IFF records identical, snapshots unchanged, and
--                every op is in the affine-collapsible set; otherwise the
--                honest loop continues untouched
--   rep N        honest + recorded; mismatch vs expectation => ATLOG
--                COLLAPSE_MISMATCH (loud field signal)
--
-- Math: a rep's ops per accumulator compose to x -> x*B + A. Pure cases use
-- exact closed forms (B==1: x + A*M; A==0: x * B^M); mixed cases iterate
-- the COMPOSED map (two Big ops per rep instead of a chain re-eval) so the
-- arithmetic stays bit-identical to the honest loop.
--
-- Kill switch: G.SETTINGS.trigger_collapse (Settings > Game), default ON,
-- read at each scoring pass. The module is loadable under plain luajit for
-- the property harness: game hooks install only when SMODS exists.

local TC = {}

-- ── op routing ──────────────────────────────────────────────────────────
-- Which accumulator each collapsible key drives, and whether it multiplies.
-- Anything not listed here aborts collapsibility for the run.
TC.ACC_OF = {
    mult = 'mult', h_mult = 'mult', mult_mod = 'mult',
    x_mult = 'mult', Xmult = 'mult', xmult = 'mult',
    x_mult_mod = 'mult', Xmult_mod = 'mult',
    chips = 'chips', h_chips = 'chips', chip_mod = 'chips',
    x_chips = 'chips', xchips = 'chips', Xchip_mod = 'chips',
    dollars = 'dollars', p_dollars = 'dollars', h_dollars = 'dollars',
    message = 'message',
}
TC.IS_MULT_OP = {
    x_mult = true, Xmult = true, xmult = true, x_mult_mod = true, Xmult_mod = true,
    x_chips = true, xchips = true, Xchip_mod = true,
}

-- ── affine composer ─────────────────────────────────────────────────────
-- record: ordered list of {key=, amount=}. Returns per-accumulator affine
-- coefficients plus the dollars sum, or nil if any op is non-collapsible.
-- Message ops are counted (not arithmetic).
function TC.compose(record)
    local acc = {
        mult = { B = 1, A = 0 },
        chips = { B = 1, A = 0 },
        dollars = 0,
        messages = 0,
    }
    for i = 1, #record do
        local op = record[i]
        local route = TC.ACC_OF[op.key]
        if not route then return nil end
        if route == 'dollars' then
            acc.dollars = acc.dollars + op.amount
        elseif route == 'message' then
            acc.messages = acc.messages + 1
        else
            local a = acc[route]
            if TC.IS_MULT_OP[op.key] then
                a.B = a.B * op.amount
                a.A = a.A * op.amount
            else
                a.A = a.A + op.amount
            end
        end
    end
    return acc
end

-- Apply x -> x*B + A exactly M times. Pure cases take exact closed forms;
-- mixed iterates the composed map so results stay bit-identical to the
-- honest per-rep arithmetic (2 ops/rep instead of a full chain re-eval).
function TC.ffwd(x, B, A, M)
    if M <= 0 then return x end
    if B == 1 then
        if A == 0 then return x end
        return x + A * M
    end
    if A == 0 then
        return x * (B ^ M)
    end
    for _ = 1, M do
        x = x * B + A
    end
    return x
end

-- Two records are equivalent iff same length, same keys in order, and
-- amounts compare equal (Big-aware: == works across number/cdata via
-- metamethods; mixed-type compare guarded).
function TC.records_equal(r1, r2)
    if #r1 ~= #r2 then return false end
    for i = 1, #r1 do
        local a, b = r1[i], r2[i]
        if a.key ~= b.key then return false end
        local ok, eq = pcall(function() return a.amount == b.amount end)
        if not ok or not eq then return false end
    end
    return true
end

-- ── depth-2 state snapshots ─────────────────────────────────────────────
-- Scaling jokers mutate ability.extra.* (nested), threshold counters often
-- live one level down too. Snapshot scalar fields at depth 1 and 2.
function TC.snapshot_table(t, out)
    out = out or {}
    for k, v in pairs(t) do
        local tv = type(v)
        if tv == 'number' or tv == 'string' or tv == 'boolean' then
            out[#out + 1] = k
            out[#out + 1] = v
        elseif tv == 'table' then
            for k2, v2 in pairs(v) do
                local tv2 = type(v2)
                if tv2 == 'number' or tv2 == 'string' or tv2 == 'boolean' then
                    out[#out + 1] = k2
                    out[#out + 1] = v2
                end
            end
        end
    end
    return out
end

function TC.snapshots_equal(s1, s2)
    if #s1 ~= #s2 then return false end
    for i = 1, #s1 do
        if s1[i] ~= s2[i] then return false end
    end
    return true
end

function TC.snapshot_world()
    local snap = {}
    if G and G.jokers and G.jokers.cards then
        for i = 1, #G.jokers.cards do
            local c = G.jokers.cards[i]
            if c.ability then TC.snapshot_table(c.ability, snap) end
        end
    end
    if G and G.GAME and G.GAME.current_round then
        TC.snapshot_table(G.GAME.current_round, snap)
    end
    return snap
end

-- ── stats (drained by telemetry alongside PERF_SNAPSHOT via RLE_STATS) ──
TC.stats = {
    runs = 0,            -- rep loops seen with N >= threshold
    collapsed_reps = 0,  -- reps skipped algebraically
    honest_reps = 0,     -- reps run honestly inside collapse-eligible loops
    unstable = 0,        -- loops that failed the stability check
    impure = 0,          -- loops with non-collapsible ops
    mismatches = 0,      -- final-rep verification failures (must stay 0)
    max_run = 0,         -- largest N seen
}

TC.THRESHOLD = 6  -- below this, honest loop always (overhead not worth it)

-- lifetime counters (never reset — the telemetry drain consumes the window
-- stats above every PERF interval; harnesses and debugging read these)
TC.stats_total = { runs = 0, collapsed_reps = 0, mismatches = 0 }

-- ── game integration ────────────────────────────────────────────────────
TC._installed = false
TC._recording = nil   -- active record table while a rep is being recorded

function TC.enabled()
    -- default ON; Settings > Game toggle writes false to disable
    return TC._installed and G and G.SETTINGS and G.SETTINGS.trigger_collapse ~= false
end

-- Side-effect-free application of M collapsed reps: accumulators via
-- Scoring_Parameters:modify (net delta — the same channel x_chips already
-- uses internally), dollars via ease_dollars directly. Deliberately NO
-- calculate_individual_effect calls: cascades (money_altered reactions
-- etc.) fired during the recorded reps and their arithmetic is already in
-- the record — re-firing them here would double-count.
function TC.apply_collapsed(acc, M, card)
    local p = SMODS.Scoring_Parameters
    if acc.chips.B ~= 1 or acc.chips.A ~= 0 then
        local cur = hand_chips or (p.chips and p.chips.current) or 0
        local target = TC.ffwd(cur, acc.chips.B, acc.chips.A, M)
        p.chips:modify(target - cur)
    end
    if acc.mult.B ~= 1 or acc.mult.A ~= 0 then
        local cur = mult or (p.mult and p.mult.current) or 0
        local target = TC.ffwd(cur, acc.mult.B, acc.mult.A, M)
        p.mult:modify(target - cur)
    end
    if acc.dollars ~= 0 then
        ease_dollars(acc.dollars * M)
    end
    percent = (percent or 0) + (percent_delta or 0.08) * M
end

-- Decide whether the loop is collapsible after recording reps 2 and 3.
-- reps entries past 1 must be plain (no retrigger_flag merge chains) so
-- one loop iteration == one rep entry.
local function reps_plain(reps)
    for i = 2, #reps do
        local _, e = next(reps[i])
        if not e or e.retrigger_flag then return false end
    end
    return true
end

-- The collapsing rep loop. `shape` selects the faithful shell:
-- 'scoring' = SMODS.score_card, 'eor' = held-in-hand end-of-round inner
-- loop. Returns nothing (mutates globals exactly like the originals).
local function run_iteration_scoring(card, context, reps, j)
    if reps[j] ~= 1 then
        local _, eff = next(reps[j])
        -- retrigger_flag chains rejected by reps_plain before collapse;
        -- in honest mode this mirrors upstream exactly
        while eff.retrigger_flag do
            SMODS.calculate_effect(eff, eff.card); j = j + 1; _, eff = next(reps[j])
        end
        SMODS.calculate_effect(eff, eff.card)
        percent = percent + percent_delta
    end

    context.main_scoring = true
    local effects = { eval_card(card, context) }
    SMODS.calculate_quantum_enhancements(card, effects, context)
    context.main_scoring = nil
    context.individual = true
    context.other_card = card

    if next(effects) then
        SMODS.calculate_card_areas('jokers', context, effects, { main_scoring = true })
        SMODS.calculate_card_areas('individual', context, effects, { main_scoring = true })
    end

    local flags = SMODS.trigger_effects(effects, card)

    context.individual = nil
    if reps[j] == 1 and flags.calculated then
        context.repetition = true
        context.card_effects = effects
        SMODS.calculate_repetitions(card, context, reps)
        context.repetition = nil
        context.card_effects = nil
    end
    j = j + (flags.calculated and 1 or #reps)
    context.other_card = nil
    card.lucky_trigger = nil
    return j
end

local function run_iteration_eor(card, context, reps, j, i, n_cards)
    percent = (i - 0.999) / (n_cards - 0.998) + (j - 1) * 0.1
    if reps[j] ~= 1 then
        local _, eff = next(reps[j])
        SMODS.calculate_effect(eff, eff.card)
        percent = percent + 0.08
    end

    context.playing_card_end_of_round = true
    local effects = { eval_card(card, context) }
    SMODS.calculate_quantum_enhancements(card, effects, context)

    context.playing_card_end_of_round = nil
    context.individual = true
    context.other_card = card

    SMODS.calculate_card_areas('jokers', context, effects, { main_scoring = true })
    SMODS.calculate_card_areas('individual', context, effects, { main_scoring = true })

    local flags = SMODS.trigger_effects(effects, card)

    context.individual = nil
    context.repetition = true
    context.card_effects = effects
    if reps[j] == 1 then
        SMODS.calculate_repetitions(card, context, reps)
    end
    context.repetition = nil
    context.card_effects = nil
    context.other_card = nil
    j = j + (flags.calculated and 1 or #reps)
    return j
end

local function collapsing_loop(card, context, run_iteration, kind, i, n_cards)
    local reps = { 1 }
    local j = 1
    local tc_on = TC.enabled()
    local rec2, rec3, snap3, acc
    local collapsed = false

    while j <= #reps do
        local arm = nil
        if tc_on and #reps >= TC.THRESHOLD and not TC._recording then
            -- (nested-loop guard: if an outer loop is recording, never clobber)
            if j == 2 or j == 3 or (collapsed and j == #reps) then
                arm = {}
                TC._recording = arm
            end
        end

        local j_next = run_iteration(card, context, reps, j, i, n_cards)

        if arm then
            TC._recording = nil
            if j == 2 then
                rec2 = arm
            elseif j == 3 then
                rec3 = arm
                snap3 = TC.snapshot_world()
            elseif collapsed then
                -- honest final rep: verify against expectation
                if not TC.records_equal(rec3, arm) then
                    TC.stats.mismatches = TC.stats.mismatches + 1
                    TC.stats_total.mismatches = TC.stats_total.mismatches + 1
                    if ATLOG then ATLOG("COLLAPSE_MISMATCH", { kind = kind, n = #reps }) end
                end
            end
        end
        -- snapshot after rep 2 happens here (post-iteration, pre-advance)
        if arm and j == 2 then TC._snap2 = TC.snapshot_world() end

        if TC.debug and #reps > 1 then
            print(string.format('[TC] iter kind=%s j=%d j_next=%s reps=%d armed=%s rec_n=%s impure=%s',
                kind, j, tostring(j_next), #reps, tostring(arm ~= nil),
                arm and #arm or '-', arm and tostring(arm.impure) or '-'))
        end
        -- collapse decision: after rep 3 completed, before rep 4 runs
        if tc_on and not collapsed and j == 3 and j_next == 4 then
            local N = #reps
            if N >= TC.THRESHOLD and rec2 and rec3 then
                if N > TC.stats.max_run then TC.stats.max_run = N end
                local reason
                if rec2.impure or rec3.impure then
                    reason = 'impure'
                elseif not reps_plain(reps) then
                    reason = 'flagged_reps'
                elseif not TC.records_equal(rec2, rec3) then
                    reason = 'unstable'
                elseif not (TC._snap2 and snap3 and TC.snapshots_equal(TC._snap2, snap3)) then
                    reason = 'unstable'
                else
                    acc = TC.compose(rec3)
                    if not acc then reason = 'impure' end
                end
                if not reason then
                    local M = N - 4  -- collapse reps 4..N-1, run rep N honestly
                    if M >= 1 then
                        TC.apply_collapsed(acc, M, card)
                        TC.stats.runs = TC.stats.runs + 1
                        TC.stats.collapsed_reps = TC.stats.collapsed_reps + M
                        TC.stats_total.runs = TC.stats_total.runs + 1
                        TC.stats_total.collapsed_reps = TC.stats_total.collapsed_reps + M
                        collapsed = true
                        j_next = N  -- jump to the honest final rep
                    end
                elseif reason == 'impure' or reason == 'flagged_reps' then
                    TC.stats.impure = TC.stats.impure + 1
                    TC.stats.honest_reps = TC.stats.honest_reps + (N - 3)
                else
                    TC.stats.unstable = TC.stats.unstable + 1
                    TC.stats.honest_reps = TC.stats.honest_reps + (N - 3)
                end
            end
        end

        j = j_next
    end
    TC._snap2 = nil
end

function TC.install_hooks()
    if TC._installed then return end
    if not (SMODS and SMODS.score_card and SMODS.calculate_individual_effect
            and SMODS.calculate_end_of_round_effects and SMODS.Scoring_Parameters) then
        return
    end
    -- the loop shells are faithful copies of SMODS 1.0.0~BETA-1224a; refuse
    -- to install over a different version (honest loops, loud reason)
    local v = (SMODS.version or '')
    if not v:find('1224a', 1, true) then
        TC.disabled_reason = 'smods_version_drift:' .. tostring(v)
        print('[TC] trigger-collapse NOT installed: ' .. TC.disabled_reason)
        return
    end
    TC._installed = true

    -- default ON, persisted: materialize the setting so the Settings > Game
    -- toggle displays the real state (nil would render unchecked while
    -- behaving enabled)
    if G and G.SETTINGS and G.SETTINGS.trigger_collapse == nil then
        G.SETTINGS.trigger_collapse = true
    end

    -- recorder chokepoint: log only ops the applier actually ACTED on
    -- (gated/no-op calls return nil and must not enter the record)
    local cie = SMODS.calculate_individual_effect
    SMODS.calculate_individual_effect = function(effect, scored_card, key, amount, from_edition)
        local rec = TC._recording
        if rec and not TC.ACC_OF[key] then
            rec.impure = key
        end
        local ret = cie(effect, scored_card, key, amount, from_edition)
        if rec and TC.ACC_OF[key] and ret then
            -- message amounts are fresh tables every rep (reference compare
            -- would block every collapse); they carry no arithmetic — record
            -- shape only
            rec[#rec + 1] = { key = key, amount = key ~= 'message' and amount or nil }
        end
        return ret
    end

    function SMODS.score_card(card, context)
        collapsing_loop(card, context, run_iteration_scoring, 'scoring')
    end

    function SMODS.calculate_end_of_round_effects(context)
        local n = #context.cardarea.cards
        for i, card in ipairs(context.cardarea.cards) do
            collapsing_loop(card, context, run_iteration_eor, 'eor', i, n)
        end
    end
end

-- late installer: SMODS loads after this chunk; install on first frame
if Game and Game.update then
    local tc_gu = Game.update
    function Game:update(dt)
        if not TC._installed and not TC.disabled_reason then TC.install_hooks() end
        return tc_gu(self, dt)
    end
end

-- export for both the game (global) and the harness (return value)
TRIGGER_COLLAPSE = TC
return TC
