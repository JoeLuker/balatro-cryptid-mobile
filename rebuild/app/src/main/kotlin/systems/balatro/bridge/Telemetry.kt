package systems.balatro.bridge

import android.content.Context
import android.util.Log
import android.view.Choreographer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Telemetry, built in from the ground up — the phone PUSHES data to the dev machine.
 * Every event is one line `epoch session KIND k=v ...` (the format the tailnet receiver
 * scripts/telemetry-home.py ingests, shared with the LÖVE build's phone.log) and lands
 * three ways:
 *   - phone-home POST -> http://100.87.221.109:8753/ingest  (no adb needed, from anywhere
 *     on the tailnet; batched off-thread, 60s backoff when offline, bounded queue)
 *   - logcat (tag BALATRO_TEL):  adb logcat -s BALATRO_TEL
 *   - a file (filesDir/telemetry.log):  adb exec-out run-as systems.balatro.rebuild cat files/telemetry.log
 * Captures crashes (full stack, with a synchronous best-effort POST so they escape even as
 * the process dies), per-frame timing (rolling fps + worst frame), and arbitrary events.
 */
object Telemetry {
    private const val TAG = "BALATRO_TEL"
    private const val HOME_URL = "http://100.87.221.109:8753/ingest"
    private const val MAX_PENDING = 2000
    private var file: File? = null
    private var session: String = "r0"

    private val pending = ArrayDeque<String>()   // guarded by its own monitor

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "telemetry.log")
        session = "r" + (System.currentTimeMillis() / 1000L).toString(16)   // marks a rebuild session
        installCrashHandler()
        startFrameMonitor()
        startPhoneHome()
        event("BOOT", "pkg" to ctx.packageName, "ver" to "0.1")
    }

    fun event(kind: String, vararg fields: Pair<String, Any?>) {
        val sb = StringBuilder()
            .append(System.currentTimeMillis() / 1000L).append(' ')   // epoch seconds (receiver parts[0])
            .append(session).append(' ')                              // session/profile (parts[1])
            .append(kind)                                             // event name (parts[2])
        for ((k, v) in fields) sb.append(' ').append(k).append('=').append(v)
        emit(sb.toString())
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        try { file?.appendText(line + "\n") } catch (_: Throwable) {}
        synchronized(pending) {
            pending.addLast(line)
            while (pending.size > MAX_PENDING) pending.removeFirst()   // bound; file + phone.log keep everything
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val line = crashLine(t, e)
            try { file?.appendText(Log.getStackTraceString(e) + "\n") } catch (_: Throwable) {}
            Log.e(TAG, "CRASH", e)
            post(listOf(line))                 // synchronous best-effort: crashes phone home before we die
            prev?.uncaughtException(t, e)       // still crash, but logged + filed + sent first
        }
    }

    private fun crashLine(t: Thread, e: Throwable): String {
        val line = "${System.currentTimeMillis() / 1000L} $session CRASH thread=${t.name} error=${e.toString().replace(' ', '_')}"
        try { file?.appendText(line + "\n") } catch (_: Throwable) {}
        return line
    }

    // ---- phone-home: batch-POST pending lines to the tailnet receiver, off the main
    //      thread. Offline costs one 3s timeout then a 60s backoff; the queue is bounded
    //      so an offline session can't grow without limit (matches the LÖVE sink). ----
    private fun startPhoneHome() {
        Thread {
            var backoffUntil = 0L
            while (true) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                if (System.currentTimeMillis() < backoffUntil) continue
                val batch: List<String> = synchronized(pending) {
                    if (pending.isEmpty()) emptyList() else ArrayList(pending).also { pending.clear() }
                }
                if (batch.isEmpty()) continue
                if (!post(batch)) {
                    backoffUntil = System.currentTimeMillis() + 60_000
                    synchronized(pending) {                       // re-queue (front), keep the bound
                        for (i in batch.indices.reversed()) pending.addFirst(batch[i])
                        while (pending.size > MAX_PENDING) pending.removeFirst()
                    }
                }
            }
        }.apply { isDaemon = true; name = "balatro-telemetry-home" }.start()
    }

    private fun post(lines: List<String>): Boolean = try {
        val body = lines.joinToString("\n", postfix = "\n").toByteArray()
        val conn = (URL(HOME_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 3000; readTimeout = 3000
            setRequestProperty("Content-Type", "text/plain")
        }
        conn.outputStream.use { it.write(body) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    } catch (_: Throwable) { false }

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
