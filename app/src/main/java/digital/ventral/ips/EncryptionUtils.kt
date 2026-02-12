package digital.ventral.ips

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.crypto.tink.subtle.AesGcmJce
import java.security.MessageDigest
import java.security.SecureRandom
import java.io.*
import java.nio.*

object EncryptionUtils {
    private const val DOMAIN_SEPARATOR = "|digital.ventral.ips|SharedPassword"
    private const val PREFS_NAME = "encryption_prefs"
    private const val KEY_DERIVED_KEY = "derived_key"
    private const val TAG = "EncryptionUtils"
    private const val HEADER_SIZE_LENGTH = 4
    private const val HEADER_IV_LENGTH = 12
    private const val HEADER_SIZE = HEADER_SIZE_LENGTH + HEADER_IV_LENGTH
    private const val BODY_SIZE_LIMIT = 100*1024*1024

    /**
     * Derives an AES key from the provided password using a deterministic approach.
     *
     * Uses SHA-256 double hashing with domain separator appended to the first hash.
     */
    private fun deriveKey(password: String): ByteArray {
        val hash1 = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        val hash2 = MessageDigest.getInstance("SHA-256").digest(hash1 + DOMAIN_SEPARATOR.toByteArray())
        return hash2
    }

    /**
     * Gets or creates encrypted shared preferences instance.
     */
    private fun getEncryptedPrefs(context: Context) = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Error creating EncryptedSharedPreferences", e)
        throw e
    }

    /**
     * Updates the encryption key based on the password and stores it securely.
     */
    fun updateEncryptionKey(context: Context, newPassword: String): Boolean {
        return try {
            val encryptedPrefs = getEncryptedPrefs(context)
            val key = deriveKey(newPassword)
            encryptedPrefs.edit()
                .putString(KEY_DERIVED_KEY, Base64.encodeToString(key, Base64.NO_WRAP))
                .apply()
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error updating encryption key", e)
            false
        }
    }

    fun hasEncryptionKey(context: Context): Boolean {
        return try {
            val encryptedPrefs = getEncryptedPrefs(context)
            val keyStr = encryptedPrefs.getString(KEY_DERIVED_KEY, null)
            return keyStr != null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error updating encryption key", e)
            false
        }
    }

    /**
     * Retrieves the stored encryption key from encrypted storage.
     */
    fun getStoredKey(context: Context): ByteArray {
        val encryptedPrefs = getEncryptedPrefs(context)
        val keyStr = encryptedPrefs.getString(KEY_DERIVED_KEY, null)
            ?: throw IllegalStateException("No encryption key found")
        return Base64.decode(keyStr, Base64.NO_WRAP)
    }

    /**
     * Wraps the given output stream such that every time write() is called on the returned
     * encrypting output stream, we encrypt the data before passing it on.
     */
    fun encryptStream(context: Context, stream: OutputStream): OutputStream {
        return EncryptingOutputStream(stream, getStoredKey(context))
    }

    /**
     * Wraps the given input stream such that every time read() is called on the returned
     * decrypting input stream, we decrypt the data coming in before returning it.
     */
    fun decryptStream(context: Context, stream: InputStream): InputStream {
        return DecryptingInputStream(stream, getStoredKey(context))
    }

    /**
     * Encrypts stream data before passing it on to the given output.
     *
     * The passed cleartext is encrypted and its ciphertext becomes the message body, while
     * the ciphertext size and the AES initialization vector become part of the header.
     *
     * With every write the contents streamed will be in the form [HEADER][BODY], or rather
     *   [[SIZE][IV]][CIPHERTEXT]  which we'll call a message.
     */
    private class EncryptingOutputStream(out: OutputStream, private val key: ByteArray) : FilterOutputStream(out) {
        private val aead = AesGcmJce(key)
        override fun write(data: ByteArray, off: Int, len: Int) {
            val cleartext = data.copyOfRange(off, off + len)
            // Encrypt.
            val iv = ByteArray(HEADER_IV_LENGTH).apply { SecureRandom().nextBytes(this) } // random initialization vector
            val ciphertext = aead.encrypt(cleartext, iv) // auth tag is appended at end of ciphertext
            // Send ciphertext as message body, ciphertext size and iv as message header.
            val message = ByteArrayOutputStream()
            message.write(ByteBuffer.allocate(HEADER_SIZE_LENGTH).order(ByteOrder.BIG_ENDIAN).putInt(ciphertext.size).array())
            message.write(iv)
            message.write(ciphertext)
            out.write(message.toByteArray())
        }
    }

    /**
     * Decrypts received data before passing it back to the reader.
     *
     * There's no guarantee that we actually receive a single, full encrypted message with
     * each chunk of data we read from the input stream. It could be any of these variations:
     *
     *  [HEAD
     *  [HEAD]
     *  [HEAD][BO
     *  [HEAD][BODY]
     *  [HEAD][BODY][HE
     *  ... etc
     *
     * This is why decrypting the stream requires more careful handling, and buffers.
     */
    private class DecryptingInputStream(input: InputStream, private val key: ByteArray) : FilterInputStream(input) {
        private val aead = AesGcmJce(key)
        private var headerBytesRead = 0
        private var headerBuffer = ByteArray(HEADER_SIZE)
        private var expectedBodySize = -1
        private var iv: ByteArray? = null
        private var bodyBytesRead = 0
        private var bodyBuffer: ByteArray? = null
        private var cleartext: ByteArray? = null
        private var cleartextRead = 0

        override fun read(data: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            // According to this function's description we MUST return at least one byte. So we'll
            // have to keep blocking until we've read enough to decrypt.
            while (true) {
                // Read some more header if it's still incomplete.
                if (headerBytesRead < HEADER_SIZE) {
                    val count = super.read(headerBuffer, headerBytesRead, HEADER_SIZE - headerBytesRead)
                    if (count <= 0) return -1
                    headerBytesRead += count
                    // Header now complete? Parse it.
                    if (headerBytesRead == HEADER_SIZE) {
                        expectedBodySize = ByteBuffer.wrap(headerBuffer.copyOfRange(0, HEADER_SIZE_LENGTH)).order(ByteOrder.BIG_ENDIAN).int
                        // If App we're talking to is not configured for encryption, we'll end up
                        // interpreting JSON encoded rubbish as a valid header here. Abort early.
                        if (expectedBodySize <= 0 || expectedBodySize > BODY_SIZE_LIMIT) return -1
                        bodyBuffer = ByteArray(expectedBodySize)
                        iv = headerBuffer.copyOfRange(HEADER_SIZE_LENGTH, HEADER_SIZE_LENGTH + HEADER_IV_LENGTH)
                    }
                }
                // Read some more body if it's still incomplete.
                if (bodyBytesRead < expectedBodySize) {
                    val count = super.read(bodyBuffer, bodyBytesRead, expectedBodySize - bodyBytesRead)
                    if (count <= 0) return -1
                    bodyBytesRead += count
                    // Body now complete? Decrypt.
                    if (bodyBytesRead == expectedBodySize) {
                        try {
                            cleartext = aead.decrypt(bodyBuffer, iv)
                        } catch (e: Exception) {
                            return -1
                        }
                    }
                }
                // We have the cleartext, but it hasn't been fully read by the caller yet.
                if (cleartext != null) {
                    val remaining = cleartext!!.size - cleartextRead
                    val count = minOf(len, remaining)
                    System.arraycopy(cleartext!!, cleartextRead, data, off, count)
                    cleartextRead += count
                    // Cleartext now completely read? Reset.
                    if (cleartextRead == cleartext!!.size) {
                        headerBytesRead = 0
                        expectedBodySize = -1
                        bodyBytesRead = 0
                        bodyBuffer = null
                        cleartext = null
                        cleartextRead = 0
                    }
                    return count
                }
            }
        }
    }
}

