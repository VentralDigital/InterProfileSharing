package digital.ventral.ips

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

object FileUtils {
    internal fun getFileName(context: Context, uri: Uri): String? {
        var name = uri.lastPathSegment
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        return name
    }

    internal fun getFileSize(context: Context, uri: Uri): Long {
        // A) Try OpenableColumns.SIZE
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
}

