-- no-lovely runtime stub (single source of truth; copied verbatim by both
-- nix/balatro-cryptid.nix and scripts/build.sh). The preflight
-- (src/preflight/core.lua) calls these lovely runtime functions; with no injector
-- they are inert: remove_var returns false (no persisted vars across the restart
-- this build never does), reload_patches is a no-op success (patches are baked).
return {
  repo = "https://github.com/ethangreen-dev/lovely-injector",
  version = "0.9.0",
  mod_dir = "Mods",
  remove_var = function() return false end,
  set_var = function() end,
  reload_patches = function() return true end,
  -- apply_patches(name, content): lovely applies registered patches to a file's
  -- content at load. On this baked build, file/Lua patches are already applied (the
  -- dump) and per-shader-file patches don't exist, so those pass through unchanged.
  -- The ONE thing lovely does at runtime that the dump can't capture is its GLSL_ES
  -- shader repairs (lovely/glsl_es_patches/repair{1,2,3}.toml, target
  -- "GLSL_ES_PATCHES.fs") -- applied to shaders built via SMODS's newShader wrapper
  -- when the renderer is OpenGL ES. Without them, SMODS shaders compile to garbage on
  -- mobile GPUs (Mali/Tensor) and wash the screen white. The repairs are ported here.
  apply_patches = function(name, content)
    if type(content) ~= 'string' then return content end
    if name ~= 'GLSL_ES_PATCHES.fs' then return content end
    local s = content
    -- int literals -> float (twice: adjacent literals share boundary chars)
    s = s:gsub('([^%w.])(%d+)([^%w.])', '%1%2.%3')
    s = s:gsub('([^%w.])(%d+)([^%w.])', '%1%2.%3')
    -- int type -> float in cast/array/decl contexts
    s = s:gsub('([%s({])int([%s([])', '%1 float%2')
    -- cleanups (undo false positives the above introduces)
    s = s:gsub('(__%w+__%s*[<>]%s*%d+)%.', '%1')            -- preprocessor compares
    s = s:gsub('([%d.]e%-?%d+)%.', '%1')                    -- scientific notation
    s = s:gsub('%[(%d+)%.%]', '[%1]')                       -- numeric array index
    s = s:gsub('%[([^%[%]]*[^%d.][^%[%]]*)%]', '[int(%1)]') -- non-numeric index
    s = s:gsub('(%d+%.%d+)f([^%w])', '%1%2')                -- decimal float suffix
    -- float/precision family -> highp (mediump overflows on Mali)
    s = s:gsub('(extern%s+)(number)', '%1highp %2')
    s = s:gsub('(uniform%s+)(number)', '%1highp %2')
    s = s:gsub('mediump(%s+)', 'highp%1')
    return s
  end,
}
