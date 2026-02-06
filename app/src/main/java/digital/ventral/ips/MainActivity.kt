package digital.ventral.ips

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import digital.ventral.ips.ui.theme.InterProfileSharingTheme

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    /**
     * Invoke ServerService to share files between Android Profiles.
     *
     * - Called when picking files via "Share Files" button in the App
     * - Called when files are forwarded from another App (eg. Share image via Gallery)
     * - Called when contacts are forwarded from another App (as .vcf file)
     */
    private fun handleShareFiles(uris: List<Uri>) {
        uris.forEach { uri ->
            try {
                // In some cases the permission this activity received for handling a file for
                // sharing needs to be explicitly granted to other components of the App.
                grantUriPermission("digital.ventral.ips", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val serviceIntent = Intent(this, ServerService::class.java)
                serviceIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                serviceIntent.putExtra(ServerService.EXTRA_URI, uri)
                startForegroundService(serviceIntent)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting ServerService", e)
                showToast("Error: ${e.message}")
            }
        }
    }

    /**
     * Invoke ServerService to share plain text between Android Profiles.
     *
     * - Called when clipboard contents are shared via button in the App
     * - Called when text is forwarded from another App (eg. Share URL via Browser)
     */
    private fun handleShareText(text: String?) {
        try {
            val serviceIntent = Intent(this, ServerService::class.java)
            serviceIntent.putExtra(ServerService.EXTRA_TEXT, text)
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error starting ServerService", e)
            showToast("Error: ${e.message}")
        }
    }

    /**
     * Handle Intents, specifically other Apps sharing data with us.
     *
     * - Could be a single media file shared from some Messenger.
     * - Could be multiple images shared from a Gallery App.
     * - Could be a URL shared from an Internet Browser.
     *
     * If such an intent invoked the MainActivity, close it once done, nothing more to do here.
     */
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            // A single item has been shared with this App.
            Intent.ACTION_SEND -> {
                val type = intent.type
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                // Handle links or some other shorter texts.
                if (type == "text/plain" && sharedText != null) {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    sharedText?.let { handleShareText(it) }
                }
                // Handle single files.
                else {
                    val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    uri?.let { handleShareFiles(listOf(it)) }
                }
                finish()
            }
            // Multiple files have been shared with this App.
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM, Uri::class.java)
                uris?.let { handleShareFiles(it) }
                finish()
            }
        }
    }

    /**
     * Called when the "Share Files" Button is tapped.
     */
    private fun onShareFilesClick() {
        filePickerLauncher.launch(
            arrayOf("*/*") // Allow all file types.
        )
    }

    /**
     * Callback after File Picker finished.
     */
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            handleShareFiles(uris)
        } else {
            showToast(getString(R.string.message_files_none))
        }
    }

    /**
     * Called when the "Share Copied Text" Button is tapped.
     */
    private fun onShareClipboardClick() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clipData: ClipData? = clipboard.primaryClip
        if (clipData == null || clipData.itemCount == 0) {
            showToast(getString(R.string.message_clipboard_empty))
            return
        }

        // If we encounter a local media URI (content://) in the clipboard,
        // we share it like a file instead. Such non-text items may appear
        // in the clipboard when the user copied an image from a website.
        val fileUris = mutableListOf<Uri>()

        // Clipboard might hold multiple copied items at once. All of them
        // represent current clipboard contents, not a history of copied items.
        for (idx in 0 until clipData.itemCount) {
            val item = clipData.getItemAt(idx)
            val uri = item.uri

            // First, try to handle content:// URIs.
            if (uri != null && "content".equals(uri.scheme, ignoreCase = true)) {
                // Sanity Check: If we can't find out the file's size, we won't be able to share it.
                val fileSize = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) it.getLong(0) else -1L
                }
                if (fileSize != null) {
                    fileUris += uri
                    continue
                }
            }

            // Alternatively, fallback to attempt coercing clipboard item into text.
            val coercedText = try {
                item.coerceToText(this)?.toString()
            } catch (e: Exception) {
                null
            }
            if (!coercedText.isNullOrBlank()) {
                handleShareText(coercedText)
            }
            else {
                showToast(getString(R.string.message_clipboard_error))
            }
        }

        if (fileUris.isNotEmpty()) {
            handleShareFiles(fileUris)
        }
    }

    /**
     * When App is opened, check for Notification Permission.
     *
     * To prevent the ServerService from being killed in the background, it needs to run as a
     * ForegroundService with a "sticky" notification - which requires the permission.
     *
     * Posting Notifications is considered a Dangerous Permission, that's why we have to request
     * it during runtime in addition to mentioning it within the AndroidManifest.
     */
    private fun checkNotificationPermission() {
        when {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                // Permission is already granted.
            }
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                // Explain why the app needs this permission.
                showToast(getString(R.string.message_notification_required))
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                // Directly ask for the permission.
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Callback after Permission Request finished.
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Now that we were granted the notification permission, let the ClientService check
            // whether there's new items to create notifications for.
            startService(Intent(this, ClientService::class.java))
        }
        else {
            // Permission denied. Explain necessity.
            showToast(getString(R.string.message_notification_required))
        }
    }

    /**
     * Triggered when MainActivity UI comes into focus.
     *
     * This happens after onCreate(), when coming back from the SettingsActivity or simply when
     * the user comes back from looking at some other App. We use this opportunity to start the
     * ClientService (in case it was killed by Android to save battery)
     */
    override fun onResume() {
        super.onResume()
        startService(Intent(this, ClientService::class.java))
    }

    /**
     * Mostly boring UI stuff from this point onwards.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkNotificationPermission()
        enableEdgeToEdge()
        intent?.let { handleIntent(it) }

        setContent {
            InterProfileSharingTheme {
                Scaffold(
                    // Top Bar with Title and Settings link.
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(text = stringResource(id = R.string.main_topbar_title))
                            },
                            actions = {
                                IconButton(onClick = {
                                    // Start SettingsActivity when the Settings link is tapped.
                                    val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                                    startActivity(intent)
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = stringResource(id = R.string.main_topbar_settings)
                                    )
                                }
                            }
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    // Content below Top Bar.
                    ContentColumn(
                        modifier = Modifier.padding(innerPadding),
                        onShareFilesClick = { onShareFilesClick() },
                        onShareClipboardClick = { onShareClipboardClick() }
                    )
                }
            }
        }
    }

    /**
     * Helper function for displaying short messages at the bottom of the screen.
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Everything below builds the UI located below the title top bar.
 */
@Composable
fun ContentColumn(modifier: Modifier = Modifier, onShareFilesClick: () -> Unit, onShareClipboardClick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Explanatory text.
        Text(
            text = stringResource(R.string.main_text_1),
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.main_text_2),
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.main_text_3),
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        // Large Share Files / Copied Text Buttons below explanatory text.
        ShareButtonsLayout(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f),
            onShareFilesClick = onShareFilesClick,
            onShareClipboardClick = onShareClipboardClick
        )
    }
}

@Composable
fun ShareButtonsLayout(modifier: Modifier = Modifier, onShareFilesClick: () -> Unit, onShareClipboardClick: () -> Unit) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        if (this@BoxWithConstraints.maxHeight >= this@BoxWithConstraints.maxWidth) {
            Column(modifier) {
                ShareButtons(
                    modifier = Modifier.weight(1f),
                    onShareFilesClick = onShareFilesClick,
                    onShareClipboardClick = onShareClipboardClick
                )
            }
        } else {
            Row(modifier) {
                ShareButtons(
                    modifier = Modifier.weight(1f),
                    onShareFilesClick = onShareFilesClick,
                    onShareClipboardClick = onShareClipboardClick
                )
            }
        }
    }
}

@Composable
fun ShareButtons(modifier: Modifier = Modifier, onShareFilesClick: () -> Unit, onShareClipboardClick: () -> Unit) {
    LargeButton(
        title = stringResource(R.string.main_button_share_files_title),
        description = stringResource(R.string.main_button_share_files_description),
        onClick = onShareFilesClick,
        modifier = modifier
    )
    LargeButton(
        title = stringResource(R.string.main_button_share_text_title),
        description = stringResource(R.string.main_button_share_text_description),
        onClick = onShareClipboardClick,
        modifier = modifier
    )
}

@Composable
fun LargeButton(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1
            )
            Text(
                text = description,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}