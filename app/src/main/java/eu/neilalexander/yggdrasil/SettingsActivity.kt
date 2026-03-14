package eu.neilalexander.yggdrasil

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.edit
import androidx.core.widget.doOnTextChanged
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {
    private lateinit var config: ConfigurationProxy
    private lateinit var inflater: LayoutInflater

    private lateinit var deviceNameEntry: EditText
    private lateinit var publicKeyLabel: TextView
    private lateinit var resetConfigurationRow: LinearLayoutCompat
    private lateinit var enableSocks5Proxy: Switch
    private lateinit var socks5HostEntry: EditText
    private lateinit var socks5PortEntry: EditText
    private lateinit var enableSocks5ProxyAuth: Switch
    private lateinit var socks5UsernameEntry: EditText
    private lateinit var socks5PasswordEntry: EditText

    private var publicKeyReset = false
    private var isBindingProxySettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        config = ConfigurationProxy(applicationContext)
        inflater = LayoutInflater.from(this)

        deviceNameEntry = findViewById(R.id.deviceNameEntry)
        publicKeyLabel = findViewById(R.id.publicKeyLabel)
        resetConfigurationRow = findViewById(R.id.resetConfigurationRow)
        enableSocks5Proxy = findViewById(R.id.enableSocks5Proxy)
        socks5HostEntry = findViewById(R.id.socks5HostEntry)
        socks5PortEntry = findViewById(R.id.socks5PortEntry)
        enableSocks5ProxyAuth = findViewById(R.id.enableSocks5ProxyAuth)
        socks5UsernameEntry = findViewById(R.id.socks5UsernameEntry)
        socks5PasswordEntry = findViewById(R.id.socks5PasswordEntry)

        deviceNameEntry.doOnTextChanged { text, _, _, _ ->
            config.updateJSON { cfg ->
                val nodeInfo = cfg.optJSONObject("NodeInfo")
                if (nodeInfo == null) {
                    cfg.put("NodeInfo", JSONObject("{}"))
                }
                cfg.getJSONObject("NodeInfo").put("name", text)
            }
        }

        deviceNameEntry.setOnKeyListener { _, keyCode, _ ->
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        }

        findViewById<View>(R.id.deviceNameTableRow).setOnKeyListener { _, keyCode, event ->
            Log.i("Key", keyCode.toString())
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    deviceNameEntry.requestFocus()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        resetConfigurationRow.setOnClickListener {
            val view = inflater.inflate(R.layout.dialog_resetconfig, null)
            val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            builder.setTitle(getString(R.string.settings_warning_title))
            builder.setView(view)
            builder.setPositiveButton(getString(R.string.settings_reset)) { dialog, _ ->
                config.resetJSON()
                updateView()
                dialog.dismiss()
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            builder.show()
        }

        findViewById<View>(R.id.resetKeysRow).setOnClickListener {
            config.resetKeys()
            publicKeyReset = true
            updateView()
        }

        findViewById<View>(R.id.setKeysRow).setOnClickListener {
            val view = inflater.inflate(R.layout.dialog_set_keys, null)
            val builder: AlertDialog.Builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.YggdrasilDialogs))
            val privateKey = view.findViewById<EditText>(R.id.private_key)
            builder.setTitle(getString(R.string.set_keys))
            builder.setView(view)
            builder.setPositiveButton(getString(R.string.save)) { dialog, _ ->
                config.setKeys(privateKey.text.toString())
                updateView()
                dialog.dismiss()
            }
            builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            builder.show()
        }

        enableSocks5Proxy.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingProxySettings) {
                return@setOnCheckedChangeListener
            }
            if (isChecked && !validateProxyEndpoint(showError = true)) {
                isBindingProxySettings = true
                enableSocks5Proxy.isChecked = false
                isBindingProxySettings = false
                updateSocks5AuthView(false)
                saveSocks5Preferences()
                return@setOnCheckedChangeListener
            }
            updateSocks5AuthView(isChecked)
            saveSocks5Preferences()
        }

        findViewById<View>(R.id.enableSocks5ProxyPanel).setOnClickListener {
            enableSocks5Proxy.toggle()
        }

        enableSocks5ProxyAuth.setOnCheckedChangeListener { _, _ ->
            if (isBindingProxySettings) {
                return@setOnCheckedChangeListener
            }
            updateSocks5CredentialsState()
            saveSocks5Preferences()
        }

        findViewById<View>(R.id.enableSocks5ProxyAuthPanel).setOnClickListener {
            if (enableSocks5ProxyAuth.isEnabled) {
                enableSocks5ProxyAuth.toggle()
            }
        }

        socks5HostEntry.doOnTextChanged { _, _, _, _ ->
            if (!isBindingProxySettings) {
                saveSocks5Preferences()
            }
        }
        socks5PortEntry.doOnTextChanged { _, _, _, _ ->
            if (!isBindingProxySettings) {
                saveSocks5Preferences()
            }
        }
        socks5UsernameEntry.doOnTextChanged { _, _, _, _ ->
            if (!isBindingProxySettings) {
                saveSocks5Preferences()
            }
        }
        socks5PasswordEntry.doOnTextChanged { _, _, _, _ ->
            if (!isBindingProxySettings) {
                saveSocks5Preferences()
            }
        }

        publicKeyLabel.setOnLongClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("public key", publicKeyLabel.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            true
        }

        updateView()
    }

    private fun updateView() {
        val json = config.getJSON()
        val nodeinfo = json.optJSONObject("NodeInfo")
        if (nodeinfo != null) {
            deviceNameEntry.setText(nodeinfo.getString("name"), TextView.BufferType.EDITABLE)
        } else {
            deviceNameEntry.setText("", TextView.BufferType.EDITABLE)
        }

        var key = json.optString("PrivateKey")
        if (key.isNotEmpty()) {
            key = key.substring(key.length / 2)
        }
        publicKeyLabel.text = key

        loadSocks5Preferences()
    }

    private fun loadSocks5Preferences() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(baseContext)
        isBindingProxySettings = true
        try {
            enableSocks5Proxy.isChecked = preferences.getBoolean(KEY_ENABLE_SOCKS5_PROXY, false)
            socks5HostEntry.setText(preferences.getString(KEY_SOCKS5_PROXY_HOST, "") ?: "", TextView.BufferType.EDITABLE)
            socks5PortEntry.setText(preferences.getString(KEY_SOCKS5_PROXY_PORT, "") ?: "", TextView.BufferType.EDITABLE)
            enableSocks5ProxyAuth.isChecked = preferences.getBoolean(KEY_ENABLE_SOCKS5_PROXY_AUTH, false)
            socks5UsernameEntry.setText(preferences.getString(KEY_SOCKS5_PROXY_USERNAME, "") ?: "", TextView.BufferType.EDITABLE)
            socks5PasswordEntry.setText(preferences.getString(KEY_SOCKS5_PROXY_PASSWORD, "") ?: "", TextView.BufferType.EDITABLE)
        } finally {
            isBindingProxySettings = false
        }
        updateSocks5AuthView(enableSocks5Proxy.isChecked)
    }

    private fun saveSocks5Preferences() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(baseContext)
        preferences.edit(commit = true) {
            putBoolean(KEY_ENABLE_SOCKS5_PROXY, enableSocks5Proxy.isChecked)
            putString(KEY_SOCKS5_PROXY_HOST, socks5HostEntry.text.toString().trim())
            putString(KEY_SOCKS5_PROXY_PORT, socks5PortEntry.text.toString().trim())
            putBoolean(KEY_ENABLE_SOCKS5_PROXY_AUTH, enableSocks5ProxyAuth.isChecked)
            putString(KEY_SOCKS5_PROXY_USERNAME, socks5UsernameEntry.text.toString())
            putString(KEY_SOCKS5_PROXY_PASSWORD, socks5PasswordEntry.text.toString())
        }
    }

    private fun validateProxyEndpoint(showError: Boolean): Boolean {
        val host = socks5HostEntry.text.toString().trim()
        val port = socks5PortEntry.text.toString().trim().toIntOrNull()
        if (host.isEmpty()) {
            if (showError) {
                Toast.makeText(this, getString(R.string.settings_socks5_error_host_required), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        if (port == null || port <= 0 || port > 65535) {
            if (showError) {
                Toast.makeText(this, getString(R.string.settings_socks5_error_port_invalid), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        return true
    }

    private fun updateSocks5AuthView(isProxyEnabled: Boolean = enableSocks5Proxy.isChecked) {
        socks5HostEntry.isEnabled = isProxyEnabled
        socks5PortEntry.isEnabled = isProxyEnabled
        enableSocks5ProxyAuth.isEnabled = isProxyEnabled
        findViewById<View>(R.id.enableSocks5ProxyAuthPanel).isEnabled = isProxyEnabled
        updateSocks5CredentialsState()
    }

    private fun updateSocks5CredentialsState() {
        val authEnabled = enableSocks5Proxy.isChecked && enableSocks5ProxyAuth.isChecked
        socks5UsernameEntry.isEnabled = authEnabled
        socks5PasswordEntry.isEnabled = authEnabled
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver, IntentFilter(PacketTunnelProvider.STATE_INTENT)
        )
        loadSocks5Preferences()
        (application as GlobalApplication).subscribe()
    }

    override fun onPause() {
        super.onPause()
        (application as GlobalApplication).unsubscribe()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }

    // To be able to get public key from running Yggdrasil we use this receiver, as we don't have this field in config
    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.hasExtra("pubkey") && !publicKeyReset) {
                val tree = intent.getStringExtra("pubkey")
                if (tree != null && tree != "null") {
                    publicKeyLabel.text = intent.getStringExtra("pubkey")
                }
            }
        }
    }
}
