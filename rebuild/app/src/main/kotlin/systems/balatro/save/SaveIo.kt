package systems.balatro.save

import java.io.File

/**
 * The storage half of the run save/load (P4 SaveLoadThreadingModel). Plain synchronous file IO over
 * the RunSnapshot JSON — callers run it on Dispatchers.IO off the main thread. java.io.File works on
 * both Android (filesDir) and the JVM, so the on-disk round-trip is unit-testable off-device.
 */
object SaveIo {
    const val FILE_NAME = "balatro_run.json"

    /** Atomic write: stage to a sibling .tmp, fsync, then rename over the target — a process death
     *  mid-write can no longer tear the save (the old file stays intact until the swap). rename(2)
     *  is atomic on Android/Linux; the delete+retry fallback covers filesystems that refuse replace. */
    fun write(file: File, json: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "SaveIo: could not move ${tmp.name} over ${file.name}" }
        }
    }

    fun read(file: File): String? = if (file.exists()) file.readText() else null
    fun delete(file: File) { file.delete() }
}
