--[[
MIT License
Copyright (c) 2017 Robert Herlihy
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
]]

--I modified this A LOT. Needed to make it quicker if it is being saved to file every few seconds during a game
--[[
amulet: talisman detection
if v.is and v:is(Object) then
]]
local function STR_BIG(v)
	local arr, sign, num
	if Big and Big.is(v) then
		arr, sign, num = v:get_array(), v.sign, math.min(v.number, 1e308)
	elseif type(v) == "table" and v.__talisman then
		arr, sign, num = v.array, v.sign, v.val
	else
		return
	end

	local arrstr = {}
	for i, val in pairs(arr) do
		table.insert(arrstr, string.format("[%s]=%s,", i, val or 0))
	end
	return string.format("(Big and to_big({%s}, %s) or %s)", table.concat(arrstr), sign, num)
end
function STR_PACK(data, recursive)
	local ret_str = (recursive and "" or "return ").."{"
	
      for i, v in pairs(data) do
		local type_i, type_v = type(i), type(v)
        local bigi = STR_BIG(i)
        if bigi then
        	i = "["..bigi.."]"
        else
        	assert((type_i ~= "table"), "Data table cannot have an table as a key reference")
        	if type_i == "string" then
        		i = '['..string.format("%q",i)..']'
        	else
        		i = "["..i.."]"
        	end
        end
        local bigv = STR_BIG(v)
        if bigv then
        	v = bigv
        elseif type_v == "table" then
			if v.is and v:is(Object) then
				v = [["]].."MANUAL_REPLACE"..[["]]
			else
				v = STR_PACK(v, true)
			end
        else
          if type_v == "string" then v = string.format("%q", v) end
		  if type_v == "boolean" then v = v and "true" or "false" end
        end
		ret_str = ret_str..i.."="..v..","
      end

	  return ret_str.."}"
end

function STR_UNPACK(str)
  return assert(loadstring(str))()
end

function get_compressed(_file)
    local file_data = love.filesystem.getInfo(_file)
    if file_data ~= nil then
        local file_string = love.filesystem.read(_file)
        if file_string ~= '' then
            if string.sub(file_string, 1, 6) ~= 'return' then 
                local success = nil
                success, file_string = pcall(love.data.decompress, 'string', 'deflate', file_string)
                if not success then return nil end
            end
            return file_string
        end
    end
end

function compress_and_save(_file, _data)
    local save_string = type(_data) == 'table' and STR_PACK(_data) or _data
    save_string = love.data.compress('string', 'deflate', save_string, 1)
    love.filesystem.write(_file,save_string)
end
