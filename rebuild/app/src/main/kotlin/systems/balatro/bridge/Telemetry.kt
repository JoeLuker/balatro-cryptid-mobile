package systems.balatro.bridge

import android.content.Context
import android.util.Log
import android.view.Choreographer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Telemetry, built in from the ground up. The phone sends data out two ways the dev
 * machine can pull over adb with no server running:
 *   - logcat (tag BALATRO_TEL):  adb logcat -s BALATRO_TEL
 *   - a file (filesDir/telemetry.log):
 *       adb exec-out run-as systems.balatro.rebuild cat files/telemetry.log
 * Captures crashes (full stack), per-frame timing (rolling fps + worst frame, the
 * native analogue of the LÖVE PERF_SNAPSHOT), and arbitrary events. A phone-home POST
 * sink (tailnet, like telemetry-home.py) drops in behind `emit` later.
 */
object Telemetry {
    private const val TAG = "BALATRO_TEL"
    private var file: File? = null
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "telemetry.log")
        installCrashHandler()
        startFrameMonitor()
        event("BOOT", "pkg" to ctx.packageName, "ver" to "0.1")
    }

    fun event(kind: String, vararg fields: Pair<String, Any?>) {
        val sb = StringBuilder(ts.format(Date())).append(' ').append(kind)
        for ((k, v) in fields) sb.append(' ').append(k).append('=').append(v)
        emit(sb.toString())
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        try { file?.appendText(line + "\n") } catch (_: Throwable) {}
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            event("CRASH", "thread" to t.name, "error" to e.toString())
            try { file?.appendText(Log.getStackTraceString(e) + "\n") } catch (_: Throwable) {}
            Log.e(TAG, "CRASH", e)
            prev?.uncaughtException(t, e)   // still crash, but logged + filed first
        }
    }

    // ---- per-frame timing: rolling fps + worst-frame, reported every ~2s ----
    private var lastFrameNs = 0L
    private var frames = 0
    private var worstMs = 0.0
    private var windowStartNs = 0L

    private fun startFrameMonitor() {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (lastFrameNs != 0L) {
                    val dtMs = (now - lastFrameNs) / 1_000_000.0
                    frames++
                    if (dtMs > worstMs) worstMs = dtMs
                    if (now - windowStartNs > 2_000_000_000L) {
                        val fps = frames * 1e9 / (now - windowStartNs)
                        event("PERF", "fps" to "%.1f".format(fps), "worst_ms" to "%.1f".format(worstMs))
                        frames = 0; worstMs = 0.0; windowStartNs = now
                    }
                } else windowStartNs = now
                lastFrameNs = now
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }
}
