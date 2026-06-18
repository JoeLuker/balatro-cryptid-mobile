function love.conf(t)
    t.window.width = 480
    t.window.height = 760
    t.window.visible = true   -- a GL context is needed even headless (run under xvfb)
    t.window.resizable = false
    t.modules.audio = false
    t.modules.sound = false
end
