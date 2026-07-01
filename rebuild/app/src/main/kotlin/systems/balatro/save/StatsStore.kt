package systems.balatro.save

import android.content.Context

/**
 * Lifetime (cross-run) stats, persisted in the shared "balatro" prefs alongside the audio settings.
 * Recorded once per finished run (win or loss); read for the launcher's Stats screen.
 */
object StatsStore {
    data class Stats(
        val games: Int = 0, val wins: Int = 0, val bestAnte: Int = 0,
        val totalHands: Int = 0, val bestScore: Long = 0L,
    ) {
        val winRate: Int get() = if (games == 0) 0 else (wins * 100) / games
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("balatro", Context.MODE_PRIVATE)

    fun read(ctx: Context): Stats = prefs(ctx).let {
        Stats(
            games = it.getInt("st_games", 0), wins = it.getInt("st_wins", 0),
            bestAnte = it.getInt("st_bestAnte", 0), totalHands = it.getInt("st_hands", 0),
            bestScore = it.getLong("st_bestScore", 0L),
        )
    }

    /** Fold one finished run into the lifetime totals. */
    fun record(ctx: Context, won: Boolean, ante: Int, hands: Int, bestScore: Long) {
        val p = prefs(ctx)
        p.edit()
            .putInt("st_games", p.getInt("st_games", 0) + 1)
            .putInt("st_wins", p.getInt("st_wins", 0) + if (won) 1 else 0)
            .putInt("st_bestAnte", maxOf(p.getInt("st_bestAnte", 0), ante))
            .putInt("st_hands", p.getInt("st_hands", 0) + hands)
            .putLong("st_bestScore", maxOf(p.getLong("st_bestScore", 0L), bestScore))
            .apply()
    }
}
