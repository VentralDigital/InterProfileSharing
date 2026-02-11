package digital.ventral.ips

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Provides UX improvement: Warning user if sharing was killed.
 *
 * The most commonly reported issue with the app appears to be users thinking
 * that it is not working because they did not configure the profile, from
 * which they wanted to share from, to be allowed to keep running in the
 * background.
 *
 * This helper stores a timestamp whenever the ServerService was started, and
 * clears it whenever the service was stopped by the user. But if the service
 * is not running and the timestamp is still set it's likely that it was
 * killed due to not being allowed to keep running in the background.
 *
 * The 5 minute time window intends to reduce false positives and unnecessary
 * notification spam. (If the service was killed longer than 5 minutes ago,
 * don't report anything to the user).
 */
object ServerMonitor {
    private const val KEY = "ServerMonitor_ServerServiceStartTimeStamp"
    private const val WINDOW_MS = 5L * 60L * 1000L

    fun get(ctx: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(ctx).getLong(KEY, 0L)

    fun set(ctx: Context) {
        val p = PreferenceManager.getDefaultSharedPreferences(ctx)
        p.edit().putLong(KEY, System.currentTimeMillis()).apply()
    }

    fun clear(ctx: Context) =
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putLong(KEY, 0L).apply()

    fun wasKilled(ctx: Context): Boolean {
        val ts = get(ctx)
        return ts != 0L && (System.currentTimeMillis() - ts) <= WINDOW_MS
    }
}
