package digital.ventral.ips

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile

object FileUtils {
    internal fun getFileName(context: Context, uri: Uri): String? {
        // A) Try ContentResolver's OpenableColumns
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0)
            }
        }
        // B) Try DocumentFile
        DocumentFile.fromSingleUri(context, uri)?.name?.let { return it }
        // C) Try last segment in URI
        if (uri.lastPathSegment != null) { return uri.lastPathSegment }
        // D) Fallback: Make it up and guess extension
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(getMimeType(context, uri)).orEmpty()
        return "share_${System.currentTimeMillis()}${if (ext.isNotBlank()) ".$ext" else ""}"
    }

    internal fun getFileSize(context: Context, uri: Uri): Long {
        // A) Try ContentResolver's OpenableColumns
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0)
            }
        }
        // B) Try AssetFileDescriptor
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { assetFileDescriptor ->
                if (assetFileDescriptor.length >= 0) return assetFileDescriptor.length
                return assetFileDescriptor.parcelFileDescriptor.statSize
            }
        } catch (_: Exception) {}
        // C) Unknown size.
        return -1L
    }

    internal fun getMimeType(context: Context, uri: Uri): String? {
        return context.contentResolver.getType(uri)
    }

    internal fun getMimeType(fileName: String): String {
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"
    }

    internal fun cacheFile(context: Context, uri: Uri): Uri {
        val outFile = File(context.cacheDir, getFileName(context, uri) ?: "${System.currentTimeMillis()}.bin")

        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Failed to cache file")

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    }

    internal fun clearCache(context: Context) {
        try {
            context.cacheDir.deleteRecursively()
            context.cacheDir.mkdirs()
        } catch (e: Exception) {
            android.util.Log.w("FileUtils", "Failed to clear cache", e)
        }
    }
}

