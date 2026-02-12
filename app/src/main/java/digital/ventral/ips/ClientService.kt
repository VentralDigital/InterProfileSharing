package digital.ventral.ips

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.google.gson.reflect.TypeToken
import java.net.Socket
import java.net.InetSocketAddress
import java.net.InetAddress
import kotlin.hashCode
import kotlinx.coroutines.*

class ClientService : BaseService() {

    private var lastCheckTime = 0L

    companion object {
        private const val LOGGING_TAG = "ClientService"
        private const val LAST_TIMESTAMP_KEY = "last_timestamp"
        private const val CHECK_THROTTLE = 1L * 1000 // 1 seconds in milliseconds
        private const val ACTION_DOWNLOAD_FILE = "digital.ventral.ips.action.DOWNLOAD_FILE"
        private const val ACTION_SHARE_FILE = "digital.ventral.ips.action.SHARE_FILE"
        private const val ACTION_DOWNLOAD_FILES = "digital.ventral.ips.action.DOWNLOAD_FILES"
        private const val ACTION_SHARE_FILES = "digital.ventral.ips.action.SHARE_FILES"
        private const val ACTION_COPY_TEXT = "digital.ventral.ips.action.COPY_TEXT"
        private const val ACTION_REMOTE_SHUTDOWN = "digital.ventral.ips.action.REMOTE_SHUTDOWN"
        private const val ACTION_DELETE_NOTIFICATION = "digital.ventral.ips.action.ACTION_DELETE_NOTIFICATION"
        private const val REMOTE_SHUTDOWN_NOTIFICATION_ID = 1
        private const val SHARING_INTERRUPTED_NOTIFICATION_ID = 2
        private const val EXTRA_FILE_URI = "digital.ventral.ips.extra.FILE_URI"
        private const val EXTRA_FILE_NAME = "digital.ventral.ips.extra.FILE_NAME"
        private const val EXTRA_FILE_SIZE = "digital.ventral.ips.extra.FILE_SIZE"
        private const val EXTRA_FILE_MIME = "digital.ventral.ips.extra.FILE_MIME"
        private const val EXTRA_FILE_URIS = "digital.ventral.ips.extra.FILE_URIS"
        private const val EXTRA_FILE_NAMES = "digital.ventral.ips.extra.FILE_NAMES"
        private const val EXTRA_FILE_SIZES = "digital.ventral.ips.extra.FILE_SIZES"
        private const val EXTRA_FILE_MIMES = "digital.ventral.ips.extra.FILE_MIMES"
        private const val EXTRA_TEXT = "digital.ventral.ips.extra.TEXT"

        /**
         * Clears shared item notifications and resets last polling timestamp.
         *
         * Should be called whenever something changes that may mean new shared
         * items might be available with an older timestamp. Eg. When the app
         * running on multiple ports and you want to switch between them while
         * items are being shared.
         */
        fun resetSharedItemPolling(ctx: Context) {
            NotificationManagerCompat.from(ctx).cancelAll()
            PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit()
                .putLong(LAST_TIMESTAMP_KEY, 0)
                .apply()
        }
    }

    override fun onCreate() {
        super.onCreate(LOGGING_TAG)

        registerReceiver(
            systemEventsReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_USER_UNLOCKED)
                addAction(Intent.ACTION_USER_FOREGROUND)
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(systemEventsReceiver)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error unregistering receiver", e)
        }
        serviceScope.cancel() // Cancel all coroutines.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIncomingIntent(it) }
        return START_STICKY
    }

    /**
     * Callback reacting to registered system event broadcasts.
     *
     * Assumes the Service is running when events are being broadcast, which might not be the case
     * as Android may decide to stop this background service to save battery. Events declared in the
     * Manifest are more powerful as they don't need to be registered by a still running service.
     * This function only handles events which cannot be registered for by Manifest, the others are
     * handled by the ClientServiceStarter.
     *
     * We attempt to receive any events that may indicate that a switch to another User Profile has
     * happened and we should check if there's new items being shared.
     *
     * - ACTION_USER_PRESENT
     *   User is present after device wakes up (e.g when the keyguard is gone).
     * - ACTION_USER_UNLOCKED
     *   Sent when the credential-encrypted private storage has become unlocked for the target user.
     * - ACTION_USER_FOREGROUND
     *   After a user switch is complete, if the switch caused the process's user to be brought to
     *   the foreground.
     */
    private val systemEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_USER_UNLOCKED,
                Intent.ACTION_USER_FOREGROUND -> {
                    android.util.Log.d(TAG, "System event received: ${intent.action}")
                    throttledCheck()
                }
            }
        }
    }

    /**
     * Checks for new shared items ins a throttled manner.
     *
     * It's possible that multiple checks are triggered due to multiple events that we react to
     * being emitted all at once. It's unlikely that new items appear less than a second after the
     * last check, so we ignore those checks here.
     */
    private fun throttledCheck() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCheckTime >= CHECK_THROTTLE) {
            lastCheckTime = currentTime
            android.util.Log.d(TAG, "Scheduling immediate check")
            serviceScope.launch {
                try {
                    immediateCheck()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error during immediate check", e)
                }
            }
        } else {
            android.util.Log.d(TAG, "Skipping check - too soon since last check")
        }
    }

    /**
     * Checks for new shared items and handles them by creating notifications.
     */
    private fun immediateCheck() {
        // After checking for new items, all we do is display notifications for them. No point
        // checking for new items if we don't have permission to do that.
        if (!hasNotificationPermission()) {
            android.util.Log.d(TAG, "Skipping check - no notification permission")
            return
        }

        // We don't want sharing notifications to appear from the client if the ServerService is
        // running. Wouldn't make sense to offer it to the User sharing it.
        if (ServerService.isRunning) {
            android.util.Log.d(TAG, "Skipping check - ServerService is running in current profile")
            return
        }

        // If it appears that the ServerService has been killed recently, take this opportunity
        // to warn the user that profiles must be allowed to keep running in the background.
        if (ServerMonitor.wasKilled(applicationContext)) {
            createSharingInterruptionWarningNotification()
            ServerMonitor.clear(applicationContext)
        }

        // We store the timestamp of the latest item we received so we can use it as a filter for
        // the SHARES_SINCE request, filtering out shares we've already seen.
        val lastTimestamp = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getLong(LAST_TIMESTAMP_KEY, 0)

        val port = getPort()
        try {
            val socket = Socket()
            // Let's see if a ServerService is available to fetch items from.
            try {
                val loopbackAddress = InetAddress.getLoopbackAddress()
                socket.bind(InetSocketAddress(loopbackAddress, 0)) // 0 = any available port
                socket.connect(InetSocketAddress(loopbackAddress, port), 1000) // 1 second timeout
            }
            catch (e: Exception) {
                android.util.Log.d(TAG, "Apparently, currently no Server running at $port")
                return
            }
            // Successfully connected to Server, now fetch list of new shares.
            socket.use { s ->
                var inputStream = socket.getInputStream()
                var outputStream = socket.getOutputStream()
                if (useEncryption()) {
                    outputStream = EncryptionUtils.encryptStream(applicationContext, outputStream)
                    inputStream = EncryptionUtils.decryptStream(applicationContext, inputStream)
                }
                val reader = inputStream.bufferedReader()
                val writer = outputStream.bufferedWriter()

                val request = ClientRequest(
                    action = ClientRequest.ACTION_SHARES_SINCE,
                    timestamp = lastTimestamp
                )
                writer.write(gson.toJson(request))
                writer.newLine()
                writer.flush()

                val response = reader.readLine()
                val typeToken = object : TypeToken<List<SharedItem>>() {}.type
                val shares: List<SharedItem> = gson.fromJson(response, typeToken)

                if (shares.isNotEmpty()) {
                    // Make sure we store the most recent item's timestamp.
                    val newTimestamp = shares.maxOf { it.timestamp }
                    PreferenceManager.getDefaultSharedPreferences(applicationContext)
                        .edit()
                        .putLong(LAST_TIMESTAMP_KEY, newTimestamp)
                        .apply()

                    // Split shares into individual and grouped items.
                    val (individualItems, groupedItems) = groupSharesByType(shares)
                    individualItems.forEach { item ->
                        // Ensure a unique notification Id based on the item data.
                        val notificationId = "${item.text}${item.uri}".hashCode()
                        createShareNotification(item, notificationId)
                    }
                    groupedItems.forEach { (mimeType, items) ->
                        val notificationId = "${items.map { it.uri }}".hashCode()
                        createGroupShareNotification(mimeType, items, notificationId)
                    }
                }
                // Offer the User to shut down the Server in another Profile,
                // when we've already dealt with all of its shared items.
                else if (!hasActiveNotifications()) {
                    createRemoteShutdownNotification()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to request SHARES_SINCE", e)
        }
    }

    /**
     * Creates a notification to shut down the remote Server.
     *
     * The notification allows a user to shut down a Server running in another Profile without
     * having to manually switch back to it.
     */
    private fun createRemoteShutdownNotification() {
        val remoteShutdownIntent = Intent(this, ClientService::class.java).apply {
            action = ACTION_REMOTE_SHUTDOWN
        }
        val remoteShutdownPendingIntent = PendingIntent.getService(
            this,
            0,
            remoteShutdownIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.notifications_server_title))
            .setContentText(getString(R.string.notifications_share_remote_shutdown))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notifications_server_action_stop), remoteShutdownPendingIntent)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(REMOTE_SHUTDOWN_NOTIFICATION_ID, notification)
    }

    /**
     * Creates a notification when sharing was unexpectedly interrupted.
     *
     * Warns the user to allow a profile to keep running in the background when
     * we suspect that sharing was interrupted due to ServerService having been
     * killed.
     */
    private fun createSharingInterruptionWarningNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notifications_server_title))
            .setContentText(getString(R.string.notifications_share_interrupted))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(SHARING_INTERRUPTED_NOTIFICATION_ID, notification)
    }

    /**
     * Group small FILE items by Mime Type.
     *
     * Reduces the amount of notifications when many files at once are being shared.
     */
    private fun groupSharesByType(shares: List<SharedItem>): Pair<List<SharedItem>, Map<String, List<SharedItem>>> {
        val unGroupableItems = shares.filter { share ->
            share.type == SharedItem.TYPE_TEXT ||     // Text items
                    share.size == null ||             // Unknown size
                    share.size > 300 * 1024 * 1024 || // Items > 300MB
                    share.mimeType == null            // Unknown type
        }
        val remainingByMimeType = shares
            .filterNot { it in unGroupableItems }
            .groupBy { it.mimeType!! }
        val groupableButSingle = remainingByMimeType
            .filter { (_, items) -> items.size == 1 }
            .flatMap { (_, items) -> items }
        val groupedItems = remainingByMimeType
            .filter { (_, items) -> items.size > 1 }
        return Pair(unGroupableItems + groupableButSingle, groupedItems)
    }

    /**
     * Creates a notification for a newly shared item.
     *
     * The notification provides basic information on the shared item and action buttons allowing
     * the user to actually do something with these items. Each of the buttons triggers a pending
     * intent which we'll deal with in the handleIncomingIntent() function.
     */
    private fun createShareNotification(item: SharedItem, notificationId: Int) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setDeleteIntent(createDeleteNotificationIntent())
            .apply {
                when (item.type) {
                    SharedItem.TYPE_FILE -> {
                        val size = item.size?.let {
                            android.text.format.Formatter.formatShortFileSize(applicationContext, it)
                        } ?: getString(R.string.notifications_share_file_size_unknown)
                        setContentTitle(getString(R.string.notifications_share_file_title))
                        setContentText("${item.name} ($size)")
                        addAction(
                            android.R.drawable.ic_menu_save,
                            getString(R.string.notifications_share_file_action_download),
                            createDownloadFileIntent(item)
                        )
                        addAction(
                            android.R.drawable.ic_menu_share,
                            getString(R.string.notifications_share_file_action_share),
                            createShareFileIntent(item)
                        )
                    }
                    SharedItem.TYPE_TEXT -> {
                        setContentTitle(getString(R.string.notifications_share_text_title))
                        setContentText(item.text)
                        setStyle(NotificationCompat.BigTextStyle().bigText(item.text))
                        item.text?.let { text ->
                            addAction(
                                android.R.drawable.ic_menu_save,
                                getString(R.string.notifications_share_text_action_copy),
                                createCopyTextIntent(text)
                            )
                            addAction(
                                android.R.drawable.ic_menu_share,
                                getString(R.string.notifications_share_text_action_share),
                                createShareTextIntent(text)
                            )
                        }
                    }
                }
            }
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Creates a notification for a group of newly shared items.
     */
    private fun createGroupShareNotification(mimeType: String, items: List<SharedItem>, notificationId: Int) {
        val totalSize = items.sumOf { it.size ?: 0 }
        val sizeStr = android.text.format.Formatter.formatShortFileSize(applicationContext, totalSize)
        val fileCount = items.size

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notifications_share_files_title, mimeType))
            .setContentText(getString(R.string.notifications_share_files_description, "$fileCount", sizeStr))
            .setDeleteIntent(createDeleteNotificationIntent())
            .addAction(
                android.R.drawable.ic_menu_save,
                getString(R.string.notifications_share_files_action_download),
                createGroupDownloadIntent(items)
            )
            .addAction(
                android.R.drawable.ic_menu_share,
                getString(R.string.notifications_share_files_action_share),
                createGroupShareIntent(items)
            )
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Pending Intent for "Add to Downloads" button of shared FILE item notification.
     */
    private fun createDownloadFileIntent(item: SharedItem): PendingIntent {
        val intent = Intent(this, ClientService::class.java).apply {
            action = ACTION_DOWNLOAD_FILE
            putExtra(EXTRA_FILE_URI, item.uri)
            putExtra(EXTRA_FILE_NAME, item.name)
            putExtra(EXTRA_FILE_SIZE, item.size)
            putExtra(EXTRA_FILE_MIME, item.mimeType)
        }
        return PendingIntent.getService(
            this,
            item.uri.hashCode(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Pending Intent for "Download and Share" button of shared FILE item notification.
     */
    private fun createShareFileIntent(item: SharedItem): PendingIntent {
        val intent = Intent(this, ClientService::class.java).apply {
            action = ACTION_SHARE_FILE
            putExtra(EXTRA_FILE_URI, item.uri)
            putExtra(EXTRA_FILE_NAME, item.name)
            putExtra(EXTRA_FILE_SIZE, item.size)
            putExtra(EXTRA_FILE_MIME, item.mimeType)
        }
        return PendingIntent.getService(
            this,
            item.uri.hashCode() + 1, // Offset to avoid collision with download intent.
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Pending Intent for "Copy to Clipboard" button of shared TEXT item notification.
     */
    private fun createCopyTextIntent(text: String): PendingIntent {
        val intent = Intent(this, ClientService::class.java).apply {
            action = ACTION_COPY_TEXT
            putExtra(EXTRA_TEXT, text)
        }
        return PendingIntent.getService(
            this,
            text.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Pending Intent for "Share.." button of shared TEXT item notification.
     *
     * Compared to the other Intents, this one actually doesn't need to re-enter this Service.
     * If the Share button for a TEXT item is tapped on we can launch the sharing chooser directly.
     */
    private fun createShareTextIntent(text: String): PendingIntent {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooserIntent = Intent.createChooser(shareIntent, getString(R.string.notifications_share_text_action_share_choose)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            text.hashCode() + 1, // Offset to avoid collision with copy intent.
            chooserIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Pending Intent for "Add all to Downloads" button for group notification of shared FILE items.
     */
    private fun createGroupDownloadIntent(items: List<SharedItem>): PendingIntent {
        val intent = Intent(this, ClientService::class.java).apply {
            action = ACTION_DOWNLOAD_FILES
            putExtra(EXTRA_FILE_URIS, items.mapNotNull { it.uri }.toTypedArray())
            putExtra(EXTRA_FILE_NAMES, items.mapNotNull { it.name }.toTypedArray())
            putExtra(EXTRA_FILE_SIZES, items.mapNotNull { it.size }.toLongArray())
            putExtra(EXTRA_FILE_MIMES, items.mapNotNull { it.mimeType }.toTypedArray())
        }
        return PendingIntent.getService(
            this,
            items.hashCode(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Pending Intent for "Download and Share all" button for group notification of shared FILE items.
     */
    private fun createGroupShareIntent(items: List<SharedItem>): PendingIntent {
        val intent = Intent(this, ClientService::class.java).apply {
            action = ACTION_SHARE_FILES
            putExtra(EXTRA_FILE_URIS, items.mapNotNull { it.uri }.toTypedArray())
            putExtra(EXTRA_FILE_NAMES, items.mapNotNull { it.name }.toTypedArray())
            putExtra(EXTRA_FILE_SIZES, items.mapNotNull { it.size }.toLongArray())
            putExtra(EXTRA_FILE_MIMES, items.mapNotNull { it.mimeType }.toTypedArray())
        }
        return PendingIntent.getService(
            this,
            items.hashCode() + 1, // Offset to avoid collision with group download intent.
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Pending Intent to react to shared-item notifications having been deleted (swiped away).
     */
    private fun createDeleteNotificationIntent(): PendingIntent {
        val intent = Intent(this, ClientService::class.java).apply {
            action = ACTION_DELETE_NOTIFICATION
        }
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Called via onStartCommand(), handles Intents passed as Service Start parameter.
     */
    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            // User tapped on "Add to Downloads" or "Download and Share" button of FILE item
            // notification. Both actions first fetch the file into the Downloads folder.
            ACTION_DOWNLOAD_FILE,
            ACTION_SHARE_FILE -> {
                val uri = intent.getStringExtra(EXTRA_FILE_URI)
                val name = intent.getStringExtra(EXTRA_FILE_NAME)
                val size = intent.getLongExtra(EXTRA_FILE_SIZE, -1)
                val mime = intent.getStringExtra(EXTRA_FILE_MIME)
                if (uri != null && name != null && size > 0) {
                    serviceScope.launch {
                        val share = intent.action == ACTION_SHARE_FILE
                        handleDownloadAction(uri, name, size, mime, share)
                    }
                }
            }
            // User tapped on "Add all to Downloads" or "Download and Share all" button of grouped
            // FILE items notification. Both actions first fetch files into the Downloads folder.
            ACTION_DOWNLOAD_FILES,
            ACTION_SHARE_FILES -> {
                val uris = intent.getStringArrayExtra(EXTRA_FILE_URIS)
                val names = intent.getStringArrayExtra(EXTRA_FILE_NAMES)
                val sizes = intent.getLongArrayExtra(EXTRA_FILE_SIZES)
                val mimes = intent.getStringArrayExtra(EXTRA_FILE_MIMES)
                if (uris != null && names != null && sizes != null && mimes != null) {
                    serviceScope.launch {
                        val share = intent.action == ACTION_SHARE_FILES
                        handleGroupDownloadAction(uris, names, sizes, mimes, share)
                    }
                }
            }
            // User tapped on "Copy to Clipboard" button of TEXT item notification.
            ACTION_COPY_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (text != null) handleCopyTextAction(text)
            }
            // User tapped on "Stop Sharing" button to shutdown a Server running in another Profile.
            ACTION_REMOTE_SHUTDOWN -> {
                runBlocking(Dispatchers.IO) {
                    if (!isPortAvailable()) {
                        runBlocking {
                            sendStopSharing()
                            // Remove the notification.
                            val notificationManager = getSystemService(NotificationManager::class.java)
                            notificationManager.cancel(REMOTE_SHUTDOWN_NOTIFICATION_ID)
                        }
                    }
                }
            }
            // User deleted (swiped away) a shared-item notification.
            ACTION_DELETE_NOTIFICATION -> {
                // If that was the last notification, offer the User to remotely shut down sharing.
                if (!hasActiveNotifications()) {
                    createRemoteShutdownNotification()
                }
            }
            else -> {
                // Service was started unrelated to notification actions. Use this as an opportunity
                // to check if we missed any shared items. Maybe the service was down.
                throttledCheck()
            }
        }
    }

    /**
     * Callback for "Copy to Clipboard" button of TEXT item notifications.
     */
    private fun handleCopyTextAction(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.notifications_share_text_action_share_clip), text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.message_share_text_copy_success), Toast.LENGTH_SHORT).show()
    }

    /**
     * Callback for "Add to Downloads" and "Download and Share" buttons of FILE item notifications.
     */
    private suspend fun handleDownloadAction(
        uri: String,
        name: String,
        size: Long,
        mimeType: String?,
        share: Boolean
    ) {
        try {
            // Create notification to indicate download is in progress.
            val notificationId = "${null}${uri}".hashCode() // Replaces existing notification.
            val notificationManager = getSystemService(NotificationManager::class.java)
            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setAutoCancel(true)
                .setOngoing(true)
                .setContentTitle(if (share) getString(R.string.notifications_share_files_download_share, name)
                    else getString(R.string.notifications_share_files_download, name))
                .setProgress(0, 0, true)
            notificationManager.notify(notificationId, notificationBuilder.build())

            // Fetch file from ServerService and store it to Downloads folder.
            val contentUri = fetchFileToDownloads(uri, name, size, mimeType)
            if (contentUri == null) {
                notificationBuilder
                    .setContentTitle(if (share) getString(R.string.notifications_share_files_download_fail_share)
                        else getString(R.string.notifications_share_files_download_fail))
                    .setContentText(getString(R.string.notifications_share_files_download_fail_description, name))
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                notificationManager.notify(notificationId, notificationBuilder.build())
                return
            }

            // If we're not sharing, open the downloaded file when tapping on the notification.
            if (!share) {
                val openFileIntent = Intent().apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(contentUri, mimeType ?: FileUtils.getMimeType(name))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    name.hashCode(),
                    openFileIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                notificationBuilder
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(getString(R.string.notifications_share_files_download_complete))
                    .setContentText(getString(R.string.notifications_share_files_download_complete_description, name))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                notificationManager.notify(notificationId, notificationBuilder.build())
            }
            // Replace download progress notification with notification that triggers the sharing
            // intent when tapped on. Unfortunately we can't trigger the sharing directly because
            // doing that from a background service would be blocked by Android.
            else {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = mimeType ?: FileUtils.getMimeType(name)
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooserIntent = Intent.createChooser(shareIntent, getString(R.string.notifications_share_files_download_share_choose, name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    name.hashCode(),
                    chooserIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val shareNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setAutoCancel(true)
                    .setContentTitle(getString(R.string.notifications_share_files_download_share_choose_title, name))
                    .setContentText(getString(R.string.notifications_share_files_download_share_choose_description))
                    .setContentIntent(pendingIntent)
                    .build()
                notificationManager.cancel(notificationId)
                notificationManager.notify(notificationId + 1, shareNotification)
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error handling ${if (share) "share" else "download"}", e)
        }
    }

    /**
     * Callback for "Add all to Downloads" and "Download and Share all" buttons of grouped FILE item
     * notifications.
     */
    private suspend fun handleGroupDownloadAction(
        uris: Array<String>,
        names: Array<String>,
        sizes: LongArray,
        mimeTypes: Array<String>,
        share: Boolean
    ) {
        try {
            // Create notification to indicate downloads are in progress.
            val totalSize = sizes.sum()
            val notificationId = "${uris.map { it }}".hashCode() // Replaces existing notification.
            val notificationManager = getSystemService(NotificationManager::class.java)
            val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setAutoCancel(true)
                .setOngoing(true)
                .setContentText("0% of ${android.text.format.Formatter.formatShortFileSize(this, totalSize)}")
                .setProgress(100, 0, false)
            notificationManager.notify(notificationId, notificationBuilder.build())

            // Fetch files from ServerService and store it to Downloads folder.
            var downloadedSize = 0L
            val contentUris = uris.indices.mapNotNull { i ->
                val uri = fetchFileToDownloads(uris[i], names[i], sizes[i], mimeTypes[i])
                // Updates progress bar based on the size of each downloaded file.
                downloadedSize += sizes[i]
                val progress = ((downloadedSize.toFloat() / totalSize) * 100).toInt()
                notificationBuilder
                    .setContentText("$progress% of ${android.text.format.Formatter.formatShortFileSize(this, totalSize)}")
                    .setProgress(100, progress, false)
                notificationManager.notify(notificationId, notificationBuilder.build())
                uri
            }
            if (contentUris.size != uris.size) {
                notificationBuilder
                    .setContentTitle(if (share) getString(R.string.notifications_share_files_download_fail_share)
                        else getString(R.string.notifications_share_files_download_fail))
                    .setContentText(getString(R.string.notifications_share_files_download_fail_description_partial))
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                notificationManager.notify(notificationId, notificationBuilder.build())
                return
            }

            // If not sharing, open the downloads folder when tapping on the notification.
            if (!share) {
                val openDownloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    uris.hashCode(),
                    openDownloadsIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                notificationBuilder
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(getString(R.string.notifications_share_files_download_complete))
                    .setContentText(getString(R.string.notifications_share_files_download_complete_description_multiple, "${uris.size}"))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                notificationManager.notify(notificationId, notificationBuilder.build())
            }
            // Replace download progress notification with notification that triggers the sharing
            // intent when tapped on. Unfortunately we can't trigger the sharing directly because
            // doing that from a background service would be blocked by Android.
            else {
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND_MULTIPLE
                    type = mimeTypes.first()
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(contentUris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooserIntent = Intent.createChooser(shareIntent, getString(R.string.notifications_share_files_download_share_choose_multiple))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    uris.hashCode(),
                    chooserIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val shareNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setAutoCancel(true)
                    .setContentTitle(getString(R.string.notifications_share_files_download_share_choose_title_multiple, "${contentUris.size}"))
                    .setContentText(getString(R.string.notifications_share_files_download_share_choose_description))
                    .setContentIntent(pendingIntent)
                    .build()
                notificationManager.cancel(notificationId)
                notificationManager.notify(notificationId + 1, shareNotification)
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error handling ${if (share) "share" else "download"}", e)
        }
    }

    /**
     * Makes a FETCH_FILE request to the ServerService to get a file's raw contents.
     */
    private suspend fun fetchFileToDownloads(
        sourceUri: String,
        name: String,
        size: Long,
        mimeType: String?
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType ?: FileUtils.getMimeType(name))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val destinationUri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext null

            var success = false
            Socket().use { socket ->
                val loopbackAddress = InetAddress.getLoopbackAddress()
                socket.connect(InetSocketAddress(loopbackAddress, getPort()))
                val request = ClientRequest(
                    action = ClientRequest.ACTION_FETCH_FILE,
                    uri = sourceUri
                )
                var outputStream = socket.getOutputStream()
                if (useEncryption()) {
                    outputStream = EncryptionUtils.encryptStream(applicationContext, outputStream)
                }
                val writer = outputStream.bufferedWriter()
                writer.write(gson.toJson(request))
                writer.newLine()
                writer.flush()

                // Normally we could determine the end of a ServerService response with a newline
                // but since the binary data of the file could contain a newline character we can't
                // rely on that. We know the size of the file we're receiving though, so we count
                // bytes until we have all of them.
                contentResolver.openOutputStream(destinationUri)?.use { destinationStream ->
                    var inputStream = socket.getInputStream()
                    if (useEncryption()) {
                        inputStream = EncryptionUtils.decryptStream(applicationContext, inputStream)
                    }
                    var bytesRead = 0L
                    val buffer = ByteArray(8192)
                    while (bytesRead < size) {
                        val remaining = size - bytesRead
                        if (remaining <= 0L) break
                        val read = inputStream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        destinationStream.write(buffer, 0, read)
                        bytesRead += read
                    }
                    success = (bytesRead == size)
                }
            }

            if (success) {
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(destinationUri, contentValues, null, null)
                destinationUri
            } else {
                contentResolver.delete(destinationUri, null, null)
                null
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error saving file to Downloads", e)
            null
        }
    }
}