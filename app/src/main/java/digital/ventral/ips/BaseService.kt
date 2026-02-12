package digital.ventral.ips

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.net.Socket

/**
 * Base Class holding things needed by both Server and Client Services.
 */
abstract class BaseService : Service() {
    internal var TAG = "BaseService"
    internal val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        internal const val DEFAULT_PORT = 2411
        internal const val CHANNEL_ID = "ServiceChannel"

        internal val gson = Gson()
    }

    data class SharedItem(
        val type: String,
        val timestamp: Long,
        // Fields for type FILE
        val uri: String? = null,
        val name: String? = null,
        val size: Long? = null,
        val mimeType: String? = null,
        // Fields for type TEXT
        val text: String? = null
    ) {
        companion object {
            const val TYPE_FILE = "FILE"
            const val TYPE_TEXT = "TEXT"
        }
    }

    data class ClientRequest(
        val action: String,
        // Fields for type SHARES_SINCE
        val timestamp: Long? = null,
        // Fields for type FETCH_FILE
        val uri: String? = null
    ) {
        companion object {
            const val ACTION_SHARES_SINCE = "SHARES_SINCE"
            const val ACTION_FETCH_FILE = "FETCH_FILE"
            const val ACTION_STOP_SHARING = "STOP_SHARING"
        }
    }

    fun onCreate(tag: String) {
        super.onCreate()

        // Adding ANDROID_ID (unique per Device+User+App) to logging TAG to be able to tell apart
        // logs of this App running within different Android User Profiles.
        val androidId: String = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        TAG = "${tag}[${androidId.take(2)}]"

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        try {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notifications_channel_name),
                // Silent notifications. Not only likely to be less annoying, but also prevents
                // Android's NotifAttentionHelper from muting us if we're being noisy.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notifications_channel_description)
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error creating notification channel", e)
            stopSelf()
        }
    }

    /**
     * A custom TCP Port can be configured within the SettingsActivity.
     */
    internal fun getPort(): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val port = prefs.getString("port", DEFAULT_PORT.toString())?.toInt() ?: DEFAULT_PORT
        return port
    }

    /**
     * Whether encryption is currently turned on in the settings.
     */
    internal fun useEncryption(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean("encryption", false)
    }

    internal fun hasNotificationPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    internal fun isPortAvailable(): Boolean {
        return try {
            val socket = ServerSocket()
            val loopbackAddress = InetAddress.getLoopbackAddress()
            socket.bind(InetSocketAddress(loopbackAddress, getPort()))
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sends a STOP_SHARING request telling the remote ServerService, presumably running in another
     * User Profile, to shut down and free the port.
     *
     * Should rightfully be part of ClientService but ended up being needed in ServerService, well.
     */
    fun sendStopSharing() {
        try {
            Socket().use { socket ->
                val loopbackAddress = InetAddress.getLoopbackAddress()
                socket.connect(InetSocketAddress(loopbackAddress, getPort()), 2000) // 2 second timeout

                var outputStream = socket.getOutputStream()
                if (useEncryption()) {
                    outputStream = EncryptionUtils.encryptStream(applicationContext, outputStream)
                }

                val writer = outputStream.bufferedWriter()
                val request = ClientRequest(action = ClientRequest.ACTION_STOP_SHARING)
                writer.write(gson.toJson(request))
                writer.newLine()
                writer.flush()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error sending stop sharing request", e)
        }
    }

    internal fun hasActiveNotifications(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        return nm.activeNotifications.isNotEmpty()
    }

    internal fun clearActiveNotifications() {
        getSystemService(NotificationManager::class.java).cancelAll()
    }

    override fun onBind(intent: Intent): IBinder? = null
}