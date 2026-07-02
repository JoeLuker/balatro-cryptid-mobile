package systems.balatro.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** SaveIo's atomic write (docs/REVIEW-2026-07-01.md: the old writeText could tear the save on a
 *  mid-write process death). Verifies the stage-fsync-rename path replaces cleanly and leaves no
 *  .tmp behind; the rename itself being atomic is the OS's contract. */
class SaveIoTest {
    private fun tmpFile(): File = File.createTempFile("balatro_run", ".json").also {
        it.delete()             // start from "no save exists"
        it.deleteOnExit()
    }

    @Test fun writeCreatesReplacesAndCleansUpTmp() {
        val f = tmpFile()
        assertNull(SaveIo.read(f))
        SaveIo.write(f, "first")
        assertEquals("first", SaveIo.read(f))
        SaveIo.write(f, "second")                       // replace over an existing save
        assertEquals("second", SaveIo.read(f))
        assertFalse("stage file must not linger", File(f.parentFile, f.name + ".tmp").exists())
        SaveIo.delete(f)
        assertNull(SaveIo.read(f))
    }
}
