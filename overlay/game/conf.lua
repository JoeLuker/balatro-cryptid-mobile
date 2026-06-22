-- Android love.conf — OWNED file (was a heredoc in scripts/build.sh).
-- Window dims must be 0 for Android fullscreen. _RELEASE_MODE=false enables
-- Balatro's built-in debug mode (what makes DebugPlus's tools/console reachable);
-- this authoritative copy overrides the desktop dump's conf.lua.
_RELEASE_MODE = false
_DEMO = false

function love.conf(t)
    t.console = not _RELEASE_MODE
    t.title = 'Balatro'
    t.window.width = 0
    t.window.height = 0
    t.window.minwidth = 100
    t.window.minheight = 100
end
