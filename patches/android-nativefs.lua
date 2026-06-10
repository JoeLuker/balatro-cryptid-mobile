--[[
Android-compatible nativefs wrapper for Balatro mods

On Android, we can't use FFI-based file operations for the save directory.
This wrapper redirects operations to love.filesystem when on Android.

This file replaces the top-level nativefs.lua in the game's save directory.
The original FFI-based nativefs is preserved in nativefs/nativefs.lua for
non-Android platforms.
]]--

local IS_ANDROID = love.system.getOS() == 'Android'

if IS_ANDROID then
    local nativefs = {}
    local workingDirectory = love.filesystem.getSaveDirectory()

    -- Core read function with container support
    function nativefs.read(containerOrName, nameOrSize, sizeOrNil)
        local container, name, size
        if sizeOrNil then
            container, name, size = containerOrName, nameOrSize, sizeOrNil
        elseif not nameOrSize then
            container, name, size = 'string', containerOrName, 'all'
        else
            if type(nameOrSize) == 'number' or nameOrSize == 'all' then
                container, name, size = 'string', containerOrName, nameOrSize
            else
                container, name, size = containerOrName, nameOrSize, 'all'
            end
        end

        local content, readSize = love.filesystem.read(name)
        if not content then
            return nil, "Could not read file: " .. name
        end

        if size ~= 'all' and type(size) == 'number' then
            content = content:sub(1, size)
        end

        if container == 'data' then
            return love.filesystem.newFileData(content, name), #content
        end
        return content, #content
    end

    -- Load and compile Lua file
    function nativefs.load(name)
        local chunk, err = love.filesystem.read(name)
        if not chunk then return nil, err end
        local fn, load_err = load(chunk, name)
        if not fn then return nil, load_err end
        return fn
    end

    -- love.filesystem.write/append fail silently (false return, no error)
    -- when the parent directory doesn't exist in the save dir — PhysFS does
    -- not create it, and on this device createDirectory does not recurse
    -- either. Real nativefs (io.open against an existing on-disk mod dir)
    -- never hit this, so callers like SMODS save_mod_config don't guard for
    -- it: every mod config write was silently lost. Create each path segment
    -- before any write.
    local function ensure_parent_dirs(name)
        local acc
        for seg in name:gmatch("([^/]+)/") do
            acc = acc and (acc .. "/" .. seg) or seg
            love.filesystem.createDirectory(acc)
        end
    end

    -- Write file
    function nativefs.write(name, data, size)
        if type(data) ~= 'string' then
            data = data:getString()
        end
        if size and size ~= 'all' then
            data = data:sub(1, size)
        end
        ensure_parent_dirs(name)
        return love.filesystem.write(name, data)
    end

    -- Append to file
    function nativefs.append(name, data, size)
        if type(data) ~= 'string' then
            data = data:getString()
        end
        if size and size ~= 'all' then
            data = data:sub(1, size)
        end
        ensure_parent_dirs(name)
        return love.filesystem.append(name, data)
    end

    -- Line iterator
    function nativefs.lines(name)
        return love.filesystem.lines(name)
    end

    -- Working directory (simulated)
    function nativefs.getWorkingDirectory()
        return workingDirectory
    end

    function nativefs.setWorkingDirectory(path)
        workingDirectory = path
        return true
    end

    -- Directory operations
    function nativefs.getDirectoryItems(dir)
        return love.filesystem.getDirectoryItems(dir)
    end

    function nativefs.getDirectoryItemsInfo(path, filtertype)
        local items = {}
        local files = love.filesystem.getDirectoryItems(path)
        for i = 1, #files do
            local filepath = path .. '/' .. files[i]
            local info = love.filesystem.getInfo(filepath, filtertype)
            if info then
                info.name = files[i]
                table.insert(items, info)
            end
        end
        return items
    end

    function nativefs.getInfo(path, filtertype)
        return love.filesystem.getInfo(path, filtertype)
    end

    function nativefs.createDirectory(path)
        return love.filesystem.createDirectory(path)
    end

    function nativefs.remove(name)
        return love.filesystem.remove(name)
    end

    function nativefs.getDriveList()
        return { '/' }
    end

    -- File object implementation
    local File = {
        getBuffer = function(self) return self._bufferMode, self._bufferSize end,
        getFilename = function(self) return self._name end,
        getMode = function(self) return self._mode end,
        isOpen = function(self) return self._mode ~= 'c' end,
    }

    function File:open(mode)
        if self._mode ~= 'c' then return false, "File " .. self._name .. " is already open" end
        if mode == 'r' then
            self._content = love.filesystem.read(self._name)
            if not self._content then
                return false, "Could not open " .. self._name
            end
            self._pos = 0
        end
        self._mode = mode
        return true
    end

    function File:close()
        if self._mode == 'c' then return false, "File is not open" end
        self._content = nil
        self._mode = 'c'
        return true
    end

    function File:setBuffer(mode, size)
        self._bufferMode = mode or 'none'
        self._bufferSize = size or 0
        return true
    end

    function File:getSize()
        if self._content then return #self._content end
        local info = love.filesystem.getInfo(self._name)
        return info and info.size or 0
    end

    function File:read(containerOrBytes, bytes)
        if self._mode ~= 'r' then return nil, 0 end

        local container = bytes ~= nil and containerOrBytes or 'string'
        bytes = not bytes and containerOrBytes or 'all'
        bytes = bytes == 'all' and (#self._content - self._pos) or math.min(#self._content - self._pos, bytes)

        if bytes <= 0 then
            return container == 'string' and '' or love.data.newFileData('', self._name), 0
        end

        local data = self._content:sub(self._pos + 1, self._pos + bytes)
        self._pos = self._pos + bytes

        if container == 'data' then
            return love.filesystem.newFileData(data, self._name), #data
        end
        return data, #data
    end

    function File:lines()
        if self._mode ~= 'r' then error("File is not opened for reading") end
        local content = self._content
        local pos = 1
        return function()
            if pos > #content then return nil end
            local lineEnd = content:find('\n', pos) or #content + 1
            local line = content:sub(pos, lineEnd - 1):gsub('\r$', '')
            pos = lineEnd + 1
            return line
        end
    end

    function File:write(data, size)
        if self._mode ~= 'w' and self._mode ~= 'a' then
            return false, "File " .. self._name .. " not opened for writing"
        end
        if type(data) ~= 'string' then data = data:getString() end
        if size and size ~= 'all' then data = data:sub(1, size) end

        if self._mode == 'a' then
            return love.filesystem.append(self._name, data)
        end
        return love.filesystem.write(self._name, data)
    end

    function File:seek(pos) self._pos = pos; return true end
    function File:tell() return self._pos or 0 end
    function File:flush() return true end
    function File:isEOF() return not self._content or self._pos >= #self._content end
    function File:release() if self._mode ~= 'c' then self:close() end end
    function File:type() return 'File' end
    function File:typeOf(t) return t == 'File' end

    File.__index = File

    function nativefs.newFile(name)
        return setmetatable({
            _name = name,
            _mode = 'c',
            _content = nil,
            _pos = 0,
            _bufferSize = 0,
            _bufferMode = 'none'
        }, File)
    end

    function nativefs.newFileData(filepath)
        local content, size = love.filesystem.read(filepath)
        if not content then return nil, "Could not read " .. filepath end
        return love.filesystem.newFileData(content, filepath), #content
    end

    -- Mount/unmount are no-ops on Android
    function nativefs.mount(archive, mountPoint, appendToPath) return true end
    function nativefs.unmount(archive) return true end

    return nativefs
else
    -- On other platforms, use the original FFI-based nativefs
    return require('nativefs.nativefs')
end
