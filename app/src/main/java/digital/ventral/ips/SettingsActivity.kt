package digital.ventral.ips

import android.os.Bundle
import android.widget.Toast
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            // Handle "Home" (Back) link by closing settings, going back to MainActivity.
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            val hasKey = EncryptionUtils.hasEncryptionKey(requireContext())

            // Get references to preferences
            val encryptionToggle: SwitchPreferenceCompat? = findPreference("encryption")
            val passwordPreference: EditTextPreference? = findPreference("password")
            val portPreference: EditTextPreference? = findPreference("port")

            // Handle password field.
            passwordPreference?.summary = if (hasKey) getString(R.string.settings_password_set)
              else getString(R.string.settings_password_not_set)
            passwordPreference?.setOnPreferenceChangeListener { preference, newValue ->
                val password = newValue as String
                if (password.length >= 8) {
                    if (EncryptionUtils.updateEncryptionKey(requireContext(), password)) {
                        encryptionToggle?.isChecked = true
                        encryptionToggle?.isEnabled = true
                        passwordPreference.summary = getString(R.string.settings_password_set)
                        // We don't want the password to actually be stored, always return false.
                        false
                    } else {
                        showToast(getString(R.string.message_key_generation_failed))
                        false
                    }
                } else {
                    showToast(getString(R.string.message_password_invalid))
                    false
                }
            }

            // Handle encryption toggle
            encryptionToggle?.isEnabled = hasKey
            encryptionToggle?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    // Don't allow enabling encryption without a key being set
                    if (!hasKey) {
                        showToast(getString(R.string.message_set_password_first))
                        false
                    } else {
                        true
                    }
                }
                true
            }

            // Validate Server TCP Port setting.
            portPreference?.setOnPreferenceChangeListener { preference, newValue ->
                val portString = newValue as String
                try {
                    val port = portString.toInt()
                    if (port in 1024..65535) {
                        // We want to reset both Client and Server services to
                        // make sure the new port is used immediately.
                        val ctx = requireContext()
                        ClientService.resetSharedItemPolling(ctx)
                        if (ServerService.isRunning) {
                            ctx.startService(Intent(ctx, ServerService::class.java).apply {
                                action = "STOP_SERVICE"
                            })
                        }
                        true
                    } else {
                        showToast(getString(R.string.message_port_invalid))
                        false
                    }
                } catch (e: NumberFormatException) {
                    showToast(getString(R.string.message_port_invalid))
                    false
                }
            }
        }

        /**
         * Helper function for displaying short messages at the bottom of the screen.
         */
        private fun showToast(message: String) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
}


