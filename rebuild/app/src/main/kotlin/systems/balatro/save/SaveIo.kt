package systems.balatro.save

import java.io.File

/**
 * The storage half of the run save/load (P4 SaveLoadThreadingModel). Plain synchronous file IO over
 * the RunSnapshot JSON — callers run it on Dispatchers.IO off the main thread. java.io.File works on
 * both Android (filesDir) and the JVM, so the on-disk round-trip is unit-testable off-device.
 */
object SaveIo {
    const val FILE_NAME = "balatro_run.json"
    fun write(file: File, json: String) = file.writeText(json)
    fun read(file: File): String? = if (file.exists()) file.readText() else null
    fun delete(file: File) { file.delete() }
}
