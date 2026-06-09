-- Reserve Shim
--
-- Sticky Fingers' touch "Pull" drag-target (for saving a consumable from a pack
-- instead of using it — e.g. Cryptid Code cards) calls G.FUNCS.can_reserve_card
-- and G.FUNCS.reserve_card. Those are defined by the Pokermon mod, not the base
-- game. Rather than ship all ~130MB of Pokermon for two functions, this mod
-- extracts exactly those two, verbatim, from Pokermon's pokeui.lua
-- (InertSteak/Pokermon). They depend only on base-game globals, so they work
-- standalone. Sticky Fingers does not call reserve_card_to_joker_slot, so it is
-- intentionally omitted.

G.FUNCS.can_reserve_card = function(e)
    if #G.consumeables.cards < G.consumeables.config.card_limit then
        e.config.colour = G.ARGS.LOC_COLOURS.pink
        e.config.button = 'reserve_card'
    else
        e.config.colour = G.C.UI.BACKGROUND_INACTIVE
        e.config.button = nil
    end
end

G.FUNCS.reserve_card = function(e) -- only works for consumeables
    local c1 = e.config.ref_table
    G.E_MANAGER:add_event(Event({
        trigger = 'after',
        delay = 0.1,
        func = function()
            c1.area:remove_card(c1)
            c1:add_to_deck()
            if c1.children.price then c1.children.price:remove() end
            c1.children.price = nil
            if c1.children.buy_button then c1.children.buy_button:remove() end
            c1.children.buy_button = nil
            remove_nils(c1.children)
            G.consumeables:emplace(c1)
            G.GAME.pack_choices = G.GAME.pack_choices - 1
            if G.GAME.pack_choices <= 0 then
                G.FUNCS.end_consumeable(nil, delay_fac)
            end
            return true
        end
    }))
end
