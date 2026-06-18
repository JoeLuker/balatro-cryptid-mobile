-- IDLE_JOKER_PERF: two surgical per-frame hot-path reductions for joker areas.
--
-- Problem: 20 idle jokers cost ~10x more per frame than 2, because several O(N)
-- and O(N log N) paths run unconditionally every frame regardless of whether
-- anything changed.  This module patches two of the worst offenders:
--
--   1. align_cards sort skip
--      CardArea:align_cards runs a table.sort (O(N log N)) on every move() call
--      (i.e. every frame) for joker/title_2/play/shop/hand/consumeable areas.
--      The sort only changes relative card order when cards are emplaced, removed,
--      or drag-released.  Wobble animation (math.sin) shifts all cards equally and
--      never changes relative x-order.  We add a boolean _needs_sort flag on each
--      CardArea, set it on structural changes, and wrap align_cards to gate the
--      table.sort calls on it.
--
--   2. handle_card_limit / count_property cache
--      CardArea:handle_card_limit (called every frame via CardArea:update) runs
--      count_property twice — each is a full O(N) scan over self.cards — for every
--      joker and hand area, even when no cards have been added or removed.
--      We cache the results keyed on #self.cards and skip both scans when the count
--      hasn't changed (and TAROT_INTERRUPT isn't active, matching the existing gate).
--
-- NOTE: The third hot path (Card:update inner scan loops for Temperance, Joker
-- Stencil, Wheel of Fortune, Ectoplasm, Hex, Blueprint, Swashbuckler) requires
-- lovely-level code injection into the Card:update function body to skip only the
-- scan loops while preserving the flip/alert/SMODS-center-update logic that must
-- run every frame.  A monkey-patch wrapper cannot do this safely without either
-- corrupting the pre-loop value resets (Temperance: money=0, Stencil: x_mult=...).
-- That fix is tracked separately as a lovely patch on card.lua.
--
-- Kill switch: set G.SETTINGS.idle_joker_perf = false to revert to vanilla
-- behaviour on both paths simultaneously.  Default: on (nil treated as on).
--
-- Both patches install once on the first Game:update tick after SMODS and
-- CardArea/Card are fully loaded.

local IJP = {}
IJP._installed = false

-- ── helpers ─────────────────────────────────────────────────────────────────

local function enabled()
    return G and G.SETTINGS and G.SETTINGS.idle_joker_perf ~= false
end

-- ── 1. align_cards sort gate ─────────────────────────────────────────────────
--
-- We wrap CardArea:align_cards.  Before each table.sort inside the function, we
-- check self._needs_sort; if false we skip the sort.  After the sort runs we
-- clear the flag.  The flag is set by wrapping emplace, remove_card, and
-- Card:stop_drag (drag release).
--
-- All other parts of align_cards (position loops, rank-assignment) continue to
-- run every frame unchanged — that preserves the wobble animation.

local function install_sort_gate()
    local _align = CardArea.align_cards
    function CardArea:align_cards()
        if not enabled() then
            return _align(self)
        end

        -- initialise flag on first call if missing
        if self._needs_sort == nil then self._needs_sort = true end

        local needs = self._needs_sort
        -- always clear before the body so re-entrant calls from within don't
        -- double-sort; any structural change during the call will re-set it
        self._needs_sort = false

        -- If no sort needed AND no drag is active in this area, we can run a
        -- fast path: skip every table.sort by temporarily shadowing it locally.
        -- We still run the position-update loops so wobble animation is live.
        if not needs then
            -- check whether any card in this area is being dragged
            local drag_here = G.CONTROLLER and G.CONTROLLER.dragging.target and
                              G.CONTROLLER.dragging.target.area == self
            if not drag_here then
                -- run with table.sort neutralised for this area's call
                local real_sort = table.sort
                local skipped = 0
                table.sort = function(t, cmp)
                    -- only suppress sorts on self.cards; pass through any other
                    if t == self.cards then
                        skipped = skipped + 1
                    else
                        real_sort(t, cmp)
                    end
                end
                local ok, err = pcall(_align, self)
                table.sort = real_sort
                if not ok then error(err) end
                return
            end
            -- drag active in this area: fall through to full align (sets flag below)
            self._needs_sort = true  -- re-arm so sort fires this frame
        end

        -- full align (includes sort)
        _align(self)
        -- flag consumed by the sort; stays false until next structural change
    end

    -- mark dirty on emplace (card added)
    local _emplace = CardArea.emplace
    function CardArea:emplace(card, location, stay_flipped)
        self._needs_sort = true
        return _emplace(self, card, location, stay_flipped)
    end

    -- mark dirty on remove_card (card removed)
    local _remove_card = CardArea.remove_card
    function CardArea:remove_card(card, discarded_only)
        self._needs_sort = true
        return _remove_card(self, card, discarded_only)
    end

    -- mark dirty on explicit sort() call
    local _sort = CardArea.sort
    function CardArea:sort(method)
        self._needs_sort = true
        return _sort(self, method)
    end

    -- mark dirty on drag release: wrap Card:stop_drag
    if Card and Card.stop_drag then
        local _stop_drag = Card.stop_drag
        function Card:stop_drag()
            if self.area then self.area._needs_sort = true end
            return _stop_drag(self)
        end
    end
end

-- ── 2. handle_card_limit / count_property cache ──────────────────────────────
--
-- Wrap CardArea:handle_card_limit.  When TAROT_INTERRUPT is not active and
-- #self.cards has not changed since the last call, reuse the cached slot counts
-- instead of re-scanning every card.  The cache is invalidated whenever
-- #self.cards changes (emplace/remove_card wrappers already mark _needs_sort;
-- we use a separate counter _limit_last_count here to keep the two concerns
-- independent).

local function install_limit_cache()
    -- We patch handle_card_limit directly rather than count_property so that
    -- count_property still works correctly for any other callers.
    local _handle = CardArea.handle_card_limit
    function CardArea:handle_card_limit()
        if not enabled() then
            return _handle(self)
        end

        -- Only the joker/hand areas hit the expensive path (SMODS.should_handle_limit).
        -- For all others the scan never ran anyway, so just delegate immediately.
        if not (SMODS and SMODS.should_handle_limit and SMODS.should_handle_limit(self)) then
            return _handle(self)
        end

        local n = #self.cards

        -- If TAROT_INTERRUPT is active the slot scan is already skipped upstream;
        -- the card_count line still needs to run so just delegate.
        if G.TAROT_INTERRUPT then
            return _handle(self)
        end

        -- Cache valid?
        if self._limit_last_count == n then
            -- Slot totals haven't changed; only recompute card_count (cheap).
            self.config.card_count = n + (self.config.card_limits.extra_slots_used or 0)
            -- Still need to run the draw-trigger logic; delegate the full function
            -- so we don't duplicate the DRAW_TO_HAND / SELECTING_HAND branches.
            -- To avoid re-running count_property we temporarily swap it out.
            -- count_property lives on the class (CardArea), not on instances, so
            -- we shadow it at the instance level; restore means setting back to nil
            -- so the class method is visible again via __index.
            local real_cp = CardArea.count_property
            self.count_property = function(_, _p)
                -- Return cached values rather than rescanning.
                if _p == 'card_limit' then
                    return self.config.card_limits.extra_slots or 0
                elseif _p == 'extra_slots_used' then
                    return self.config.card_limits.extra_slots_used or 0
                end
                -- Unknown property — fall through to real scan (safe fallback).
                return real_cp(self, _p)
            end
            local ok, err = pcall(_handle, self)
            self.count_property = nil   -- restore class-level method via __index
            if not ok then error(err) end
            return
        end

        -- Cache miss: run normally and record the new count.
        _handle(self)
        self._limit_last_count = n
    end
end

-- ── installation ─────────────────────────────────────────────────────────────

function IJP.install()
    if IJP._installed then return end
    if not (CardArea and Card) then return end

    install_sort_gate()
    install_limit_cache()

    IJP._installed = true
    print('[IJP] idle-joker-perf installed')
end

-- Late-install on first Game:update (CardArea and Card defined by then).
if Game and Game.update then
    local _gu = Game.update
    function Game:update(dt)
        if not IJP._installed then IJP.install() end
        return _gu(self, dt)
    end
end

IDLE_JOKER_PERF = IJP
return IJP
