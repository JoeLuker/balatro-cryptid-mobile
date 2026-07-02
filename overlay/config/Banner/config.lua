-- Mobile default: no right-click on touch, so route plain taps in the
-- collection to Banner's toggle (the mod's own left_click mode). Persisted
-- per-profile after first save; this is only the shipped default.
return {
	disabled_keys = {},
	left_click = true,
	limit_poker_hand_scoring = false,
}
