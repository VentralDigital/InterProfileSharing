package digital.ventral.ips

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*

class ServerService : BaseService() {

    private var serverSocket: ServerSocket? = null
    // List of items that are currently shared by the server.
    private var sharingList = mutableListOf<SharedItem>()

    companion object {
        private const val LOGGING_TAG = "ServerService"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_URI = "digital.ventral.ips.extra.URI"
        const val EXTRA_TEXT = "digital.ventral.ips.extra.TEXT"

        // Allows other components of the application to check whether the
        // ServerService is running in the current profile. (eg. if this
        // profile is sharing something, don't show notifications for it)
        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate(LOGGING_TAG)
        isRunning = true
        ensurePortAvailable()
        startServer()
    }

    override fun onDestroy() {
        stopServer()
        isRunning = false
        sharingList.clear()
        FileUtils.clearCache(applicationContext)
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * If the configured port isn't available, it's most likely used by another ServerService
     * instance running within another User Profile. Tell it to stop sharing to free the port.
     */
    private fun ensurePortAvailable() {
        runBlocking(Dispatchers.IO) {
            if (!isPortAvailable()) {
                runBlocking {
                    sendStopSharing()
                    delay(500) // Give it some time to shut down.
                }
            }
        }
    }

    /**
     * We're serving shared items via a socket on the local loopback interface (127.0.0.1).
     *
     * This is sufficient since we only intend to share with other instances of this App within
     * other User Profiles. While this doesn't prevent other, potentially malicious, Apps from
     * connecting to us, it should at least prevent connections from an external network.
     *
     * We'll optionally use encryption to protect malicious local applications from interacting
     * with the server or intercepting inter-profile communications.
     */
    private fun startServer() {
        try {
            val port = getPort()
            val loopbackAddress = InetAddress.getLoopbackAddress()
            serverSocket = ServerSocket(port, 0, loopbackAddress).also { server ->
                android.util.Log.d(TAG, "Server started on port $port")

                // Start accepting connections in a coroutine.
                serviceScope.launch {
                    ServerMonitor.set(applicationContext)
                    while (isActive) {
                        try {
                            val client = server.accept()
                            handleClient(client)
                        } catch (e: Exception) {
                            // Log error only if it's not due to normal socket closure.
                            if (e !is java.net.SocketException || serverSocket != null) {
                                android.util.Log.e(TAG, "Error accepting client", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error starting server", e)
            ServerMonitor.clear(applicationContext)
            stopSelf()
        }
    }

    private fun stopServer() {
        try {
            serverSocket?.close()
            serverSocket = null
            serviceScope.cancel() // Cancel all coroutines.
            android.util.Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error stopping server", e)
        }
    }

    /**
     * Called after Server accepted a connection.
     *
     * Could've used an HTTP Server here, but that seemed overkill. Instead we're using a simple
     * Client-Server pattern where the ClientRequest is a JSON encoded object containing the
     * desired action.
     *
     * For simple message framing we use newlines at the end of each JSON encoded object. The
     * exception to this is when we're transferring raw file data, as the binary data could contain
     * newlines we instead rely on the fact that the client will have knowledge of the file size.
     *
     * Available actions are:
     *  - SHARES_SINCE
     *    Server returns a JSON encoded list of currently shared items which are newer than the
     *    specified timestamp. Used by the Client to check for newly shared items.
     *  - FETCH_FILE
     *    Server returns the raw data of the file located at the specified uri. No newline framing
     *    but Client should be aware of file size as it is part of SHARES_SINCE response.
     *  - STOP_SHARING
     *    Causes the ServerService to shutdown, making the port available to use for another User
     *    Profile desiring to share items.
     *
     */
    private fun handleClient(client: Socket) {
        serviceScope.launch {
            try {
                var inputStream = client.getInputStream()
                var outputStream = client.getOutputStream()
                if (useEncryption()) {
                    outputStream = EncryptionUtils.encryptStream(applicationContext, outputStream)
                    inputStream = EncryptionUtils.decryptStream(applicationContext, inputStream)
                }
                val reader = inputStream.bufferedReader()
                val writer = outputStream.bufferedWriter()

                val requestJson = reader.readLine()
                val request = gson.fromJson(requestJson, ClientRequest::class.java)

                when (request?.action) {

                    ClientRequest.ACTION_SHARES_SINCE -> {
                        val timestamp = request.timestamp ?: 0
                        val shares = sharingList.filter { it.timestamp > timestamp }
                        val response = gson.toJson(shares)
                        writer.write(response)
                        writer.newLine()
                        writer.flush()
                    }

                    ClientRequest.ACTION_FETCH_FILE -> {
                        val requestedUri = request.uri
                        if (requestedUri != null) {
                            // Find the shared item, to make sure the specified uri is actually being shared.
                            val sharedItem = sharingList.find {
                                it.type == SharedItem.TYPE_FILE && it.uri == requestedUri
                            }
                            if (sharedItem != null) {
                                val uri = Uri.parse(sharedItem.uri)
                                val contentResolver = applicationContext.contentResolver
                                contentResolver.openInputStream(uri)?.use { contentStream ->
                                    contentStream.buffered().copyTo(outputStream)
                                    outputStream.flush()
                                } ?: throw Exception("Could not open input stream for URI")
                            }
                        }
                    }

                    ClientRequest.ACTION_STOP_SHARING -> {
                        android.util.Log.d(TAG, "Stop sharing requested via TCP")
                        ServerMonitor.clear(applicationContext)
                        client.close()
                        stopSelf()
                    }

                    else -> {
                        android.util.Log.w(TAG, "Unknown action: ${request?.action}")
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error handling client", e)
            } finally {
                try {
                    client.close()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error closing client socket", e)
                }
            }
        }
    }

    /**
     * Invoked via MainActivity for each new item to share.
     *
     * Called with EXTRA_URI
     * - when picking files via "Share Files" button in the App's MainActivity
     * - when files are forwarded from another App (eg. Share image via Gallery)
     * Called with EXTRA_TEXT
     * - when clipboard contents are shared via button in the App's MainActivity
     * - when text is forwarded from another App (eg. Share URL via Browser)
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Handle taps on the notification's "Stop Sharing" button.
            if (intent?.action == "STOP_SERVICE") {
                ServerMonitor.clear(applicationContext)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            // We must call startForeground within 5 seconds of service start.
            // Uses a lightweight placeholder notification which is replaced further down.
            startForeground(NOTIFICATION_ID, createPlaceholderNotification())

            // All other intents should be requests to share more items.
            val uri = intent?.getParcelableExtra<Uri>(EXTRA_URI, Uri::class.java)
            val text = intent?.getStringExtra(EXTRA_TEXT)
            when {
                uri != null -> {
                    addFileItem(uri)
                }
                text != null -> {
                    addTextItem(text)
                }
                else -> {
                    android.util.Log.w(TAG, "No URI or text found in intent")
                }
            }

            // Replaces placeholder notification.
            startForeground(NOTIFICATION_ID, createNotification())

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error in onStartCommand", e)
        }
        return START_STICKY
    }

    /**
     * Adds a FILE to the list of currently shared items.
     */
    private fun addFileItem(uri: Uri) {
        try {
            // Remove existing item with same uri to avoid duplicates.
            sharingList.removeAll {
                it.type == SharedItem.TYPE_FILE && it.uri == uri.toString()
            }
            val sharedItem = SharedItem(
                type = SharedItem.TYPE_FILE,
                uri = uri.toString(),
                name = FileUtils.getFileName(this, uri),
                size = FileUtils.getFileSize(this, uri),
                mimeType = FileUtils.getMimeType(this, uri),
                timestamp = System.currentTimeMillis()
            )
            sharingList.add(sharedItem)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error adding FILE item", e)
        }
    }

    /**
     * Adds a TEXT to the list of currently shared items.
     */
    private fun addTextItem(text: String) {
        // Remove existing item with same text to avoid duplicates.
        sharingList.removeAll {
            it.type == SharedItem.TYPE_TEXT && it.text == text
        }
        val sharedItem = SharedItem(
            type = SharedItem.TYPE_TEXT,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        sharingList.add(sharedItem)
    }

    /**
     * Lightweight placeholder notification to ensure startForeground() is called within time limit.
     *
     * This reduces the chance of ForegroundServiceDidNotStartInTimeException to be thrown.
     */
    private fun createPlaceholderNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notifications_server_title))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * To prevent Android from killing our Server while sharing to conserve battery, we run it as
     * a "Foreground Service" which requires a notification being displayed while its running.
     *
     * This notification will inform the user that items are currently being actively shared with
     * other profiles, and gives him control to stop sharing them via the "Stop Sharing" button
     * below the notification.
     */
    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notifications_server_title))
            .setContentText(getNotificationText())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notifications_server_action_stop), stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Generates an informative text about what is being shared depending on the current item list.
     */
    private fun getNotificationText(): String {
        return when {
            // Case: Not sharing anything (This shouldn't happen).
            sharingList.isEmpty() -> {
                getString(R.string.notifications_server_description_ready)
            }
            sharingList.size == 1 -> {
                val item = sharingList[0]
                when (item.type) {
                    // Case: Sharing one Text item.
                    SharedItem.TYPE_TEXT -> {
                        // NotificationCompat handles truncation of long text.
                        getString(R.string.notifications_server_description_text, item.text)
                    }
                    // Case: Sharing one File item.
                    SharedItem.TYPE_FILE -> {
                        val size = item.size?.let {
                            android.text.format.Formatter.formatShortFileSize(this, it)
                        } ?: getString(R.string.notifications_share_file_size_unknown)
                        getString(R.string.notifications_server_description_file, item.name, size)
                    }
                    else -> getString(R.string.notifications_server_description_unknown)
                }
            }
            // Case: Sharing multiple Text items.
            sharingList.all { it.type == SharedItem.TYPE_TEXT } -> {
                val lastText = sharingList.last().text
                getString(R.string.notifications_server_description_texts, lastText)
            }
            // Case: Sharing multiple items (All Files, or mixed).
            else -> {
                val totalSize = sharingList
                    .mapNotNull { it.size }
                    .sum()
                val sizeStr = android.text.format.Formatter.formatShortFileSize(this, totalSize)
                getString(R.string.notifications_server_description_mixed, sizeStr)
            }
        }
    }

}
