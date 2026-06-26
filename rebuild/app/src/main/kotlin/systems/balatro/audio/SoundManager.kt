package systems.balatro.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Lightweight SFX playback over the bundled vanilla sounds (assets/sounds, extracted from
 * Balatro.love). SoundPool fits short, low-latency, overlapping one-shots. Music/ambient are not
 * handled here. [init] is called once from MainActivity; play() is a no-op until then (and in unit
 * tests, where the pool is never created), so the game model stays decoupled from Android audio.
 */
object SoundManager {
    /** The curated SFX names (file = "$name.ogg"). */
    private val NAMES = listOf(
        "button", "cancel", "highlight1", "card1", "cardSlide1", "chips1", "chips2", "multhit1",
        "coin1", "coin3", "tarot1", "whoosh1", "gong", "timpani", "generic1", "paper1", "glass1",
    )

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    @Volatile var enabled = true            // global mute toggle (settings can flip this later)

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        NAMES.forEach { n ->
            try {
                ctx.assets.openFd("sounds/$n.ogg").use { ids[n] = p.load(it, 1) }
            } catch (_: Throwable) { /* a missing/compressed asset just means that cue is silent */ }
        }
        pool = p
    }

    /** Play a one-shot SFX by name. [rate] varies pitch (e.g. rising chip ticks). Safe before init. */
    fun play(name: String, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled) return
        val id = ids[name] ?: return
        pool?.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun release() { pool?.release(); pool = null; ids.clear() }
}
