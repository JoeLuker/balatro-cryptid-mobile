package systems.balatro.audio

import android.content.Context
import android.media.MediaPlayer

/**
 * Looping background music (the bundled main theme, assets/sounds/music1.ogg). MediaPlayer fits a
 * single long, looping stream (SoundPool is for short SFX). Driven by the Activity lifecycle:
 * start() on resume, pause() on background, release() on destroy. Safe to call before/after init.
 */
object MusicManager {
    private const val TRACK = "music1.ogg"
    private const val VOLUME = 0.35f               // sits under the SFX

    private var player: MediaPlayer? = null
    private var prepared = false                   // onPrepared fired — start()/pause() are legal
    private var wantPlaying = false                // desired state; onPrepared honors it
    @Volatile var enabled = true                   // settings can flip this; pauses/resumes playback

    /** Begin (or resume) the loop. No-op if music is disabled or already playing. Calling while
     *  the player is still async-Preparing is safe: MediaPlayer.start() is ILLEGAL in that state
     *  (isPlaying=false doesn't mean startable), so we only record intent and let onPrepared act. */
    fun start(ctx: Context) {
        if (!enabled) return
        wantPlaying = true
        player?.let { if (prepared && !it.isPlaying) it.start(); return }
        try {
            val mp = MediaPlayer()
            ctx.assets.openFd("sounds/$TRACK").use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.isLooping = true
            mp.setVolume(VOLUME, VOLUME)
            // wantPlaying re-checked here: a lifecycle pause() during Preparing must NOT
            // start music in the background once preparation completes.
            mp.setOnPreparedListener { prepared = true; if (enabled && wantPlaying) it.start() }
            mp.prepareAsync()
            player = mp
        } catch (_: Throwable) { /* missing/compressed track → silent, never crash */ }
    }

    fun pause() {
        wantPlaying = false
        player?.let { if (prepared && it.isPlaying) it.pause() }
    }

    /** Flip music on/off at runtime (settings). */
    fun setEnabled(on: Boolean, ctx: Context) {
        enabled = on
        if (on) start(ctx) else pause()
    }

    fun release() { player?.release(); player = null; prepared = false; wantPlaying = false }
}
