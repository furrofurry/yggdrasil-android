package eu.neilalexander.yggdrasil

import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import eu.neilalexander.yggdrasil.YggStateReceiver.Companion.YGG_STATE_INTENT
import mobile.Yggdrasil
import org.json.JSONArray
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


private const val TAG = "PacketTunnelProvider"
const val SERVICE_NOTIFICATION_ID = 1000

private data class Socks5ProxyConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?
)

open class PacketTunnelProvider: VpnService() {
    companion object {
        const val STATE_INTENT = "eu.neilalexander.yggdrasil.PacketTunnelProvider.STATE_MESSAGE"

        const val ACTION_START = "eu.neilalexander.yggdrasil.PacketTunnelProvider.START"
        const val ACTION_STOP = "eu.neilalexander.yggdrasil.PacketTunnelProvider.STOP"
        const val ACTION_TOGGLE = "eu.neilalexander.yggdrasil.PacketTunnelProvider.TOGGLE"
        const val ACTION_CONNECT = "eu.neilalexander.yggdrasil.PacketTunnelProvider.CONNECT"

        const val EXTRA_PROXY_MESSAGE = "proxy_message"
    }

    private var yggdrasil = Yggdrasil()
    private var started = AtomicBoolean()

    private lateinit var config: ConfigurationProxy

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var updateThread: Thread? = null

    private var parcel: ParcelFileDescriptor? = null
    private var readerStream: FileInputStream? = null
    private var writerStream: FileOutputStream? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        config = ConfigurationProxy(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d(TAG, "Intent is null")
            return START_NOT_STICKY
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val enabled = preferences.getBoolean(PREF_KEY_ENABLED, false)
        return when (intent.action ?: ACTION_STOP) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping...")
                stop(); START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                Log.d(TAG, "Connecting...")
                if (started.get()) {
                    connect()
                } else {
                    start()
                }
                START_STICKY
            }
            ACTION_TOGGLE -> {
                Log.d(TAG, "Toggling...")
                if (started.get()) {
                    stop(); START_NOT_STICKY
                } else {
                    start(); START_STICKY
                }
            }
            else -> {
                if (!enabled) {
                    Log.d(TAG, "Service is disabled")
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Starting...")
                start(); START_STICKY
            }
        }
    }

    private fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        try {
            startInternal()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start VPN tunnel", t)
            stop()
        }
    }

    private fun startInternal() {

        val notification = createServiceNotification(this, State.Enabled)
        startForeground(SERVICE_NOTIFICATION_ID, notification)

        // Acquire multicast lock
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("Yggdrasil").apply {
            setReferenceCounted(false)
            acquire()
        }

        Log.d(TAG, config.getJSON().toString())
        val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
        val socks5ProxyConfig = getSocks5ProxyConfig(preferences)

        yggdrasil.startJSON(config.getJSONByteArray())

        var proxyMessage: String? = null
        val proxyModeEnabled = if (preferences.getBoolean(KEY_ENABLE_SOCKS5_PROXY, false)) {
            if (socks5ProxyConfig == null) {
                proxyMessage = getString(R.string.proxy_error_invalid_configuration)
                false
            } else {
                val enabled = enableSocks5ProxyOnTunnel(socks5ProxyConfig)
                if (!enabled) {
                    proxyMessage = getString(R.string.proxy_error_unsupported)
                }
                enabled
            }
        } else {
            false
        }

        val address = yggdrasil.addressString
        val builder = Builder()
            .addAddress(address, 7)
            .addRoute("200::", 7)
            // We do this to trick the DNS-resolver into thinking that we have "regular" IPv6,
            // and therefore we need to resolve AAAA DNS-records.
            // See: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#1935
            // and: https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/dns/net/getaddrinfo.c#365
            // If we don't do this the DNS-resolver just doesn't do DNS-requests with record type AAAA,
            // and we can't use DNS with Yggdrasil addresses.
            .addRoute("2000::", 128)
            .allowFamily(OsConstants.AF_INET)
            .allowBypass()
            .setBlocking(true)
            .setMtu(yggdrasil.mtu.toInt())
            .setSession("Yggdrasil")

        if (proxyModeEnabled) {
            builder.allowFamily(OsConstants.AF_INET6)
        }
        // On Android API 29+ apps can opt-in/out to using metered networks.
        // If we don't set metered status of VPN it is considered as metered.
        // If we set it to false, then it will inherit this status from underlying network.
        // See: https://developer.android.com/reference/android/net/VpnService.Builder#setMetered(boolean)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val serverString = preferences.getString(KEY_DNS_SERVERS, "")
        if (serverString!!.isNotEmpty()) {
            val servers = serverString.split(",")
            if (servers.isNotEmpty()) {
                servers.forEach {
                    Log.i(TAG, "Using DNS server $it")
                    builder.addDnsServer(it)
                }
            }
        }
        if (preferences.getBoolean(KEY_ENABLE_CHROME_FIX, false)) {
            builder.addRoute("2001:4860:4860::8888", 128)
        }

        if (proxyModeEnabled) {
            builder.addRoute("0.0.0.0", 0)
            try {
                builder.addRoute("::", 0)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to add IPv6 default route for SOCKS5 proxy mode", e)
            }
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disallow app package from VPN", e)
            }
        }

        parcel = builder.establish()
        val parcel = parcel
        if (parcel == null || !parcel.fileDescriptor.valid()) {
            stop()
            return
        }

        readerStream = FileInputStream(parcel.fileDescriptor)
        writerStream = FileOutputStream(parcel.fileDescriptor)

        readerThread = thread {
            reader()
        }
        writerThread = thread {
            writer()
        }
        updateThread = thread {
            updater()
        }

        var intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_ENABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        if (!proxyMessage.isNullOrBlank()) {
            intent = Intent(STATE_INTENT)
            intent.putExtra("type", "proxy")
            intent.putExtra(EXTRA_PROXY_MESSAGE, proxyMessage)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    private fun getSocks5ProxyConfig(preferences: android.content.SharedPreferences): Socks5ProxyConfig? {
        if (!preferences.getBoolean(KEY_ENABLE_SOCKS5_PROXY, false)) {
            return null
        }
        val host = preferences.getString(KEY_SOCKS5_PROXY_HOST, "")?.trim().orEmpty()
        val port = preferences.getString(KEY_SOCKS5_PROXY_PORT, "")?.trim()?.toIntOrNull()
        if (host.isEmpty() || port == null || port <= 0 || port > 65535) {
            Log.w(TAG, "SOCKS5 proxy is enabled but host/port is invalid, skipping proxy mode")
            return null
        }

        val normalisedHost = normaliseProxyHost(host) ?: return null
        val useAuth = preferences.getBoolean(KEY_ENABLE_SOCKS5_PROXY_AUTH, false)
        val username = preferences.getString(KEY_SOCKS5_PROXY_USERNAME, "")?.takeIf { useAuth && it.isNotEmpty() }
        val password = preferences.getString(KEY_SOCKS5_PROXY_PASSWORD, "")?.takeIf { useAuth && it.isNotEmpty() }
        return Socks5ProxyConfig(normalisedHost, port, username, password)
    }

    private fun normaliseProxyHost(host: String): String? {
        val stripped = host.removePrefix("[").removeSuffix("]")
        return try {
            val parsed = InetAddress.getByName(stripped)
            if (parsed is Inet6Address) {
                parsed.hostAddress
            } else {
                stripped
            }
        } catch (e: Exception) {
            Log.w(TAG, "SOCKS5 proxy host is not a valid IP address: $host", e)
            null
        }
    }

    private fun enableSocks5ProxyOnTunnel(proxy: Socks5ProxyConfig): Boolean {
        val candidates = listOf(
            listOf("setSocks5Proxy", String::class.java, Int::class.javaPrimitiveType!!, String::class.java, String::class.java),
            listOf("setSocksProxy", String::class.java, Int::class.javaPrimitiveType!!, String::class.java, String::class.java),
            listOf("configureSocks5Proxy", String::class.java, Int::class.javaPrimitiveType!!, String::class.java, String::class.java),
            listOf("setSocks5Proxy", String::class.java, Int::class.javaPrimitiveType!!),
            listOf("setSocksProxy", String::class.java, Int::class.javaPrimitiveType!!),
            listOf("configureSocks5Proxy", String::class.java, Int::class.javaPrimitiveType!!)
        )

        for (candidate in candidates) {
            val name = candidate[0] as String
            val params = candidate.drop(1).toTypedArray() as Array<Class<*>>
            try {
                val method: Method = yggdrasil.javaClass.getMethod(name, *params)
                when (params.size) {
                    2 -> method.invoke(yggdrasil, proxy.host, proxy.port)
                    4 -> method.invoke(yggdrasil, proxy.host, proxy.port, proxy.username ?: "", proxy.password ?: "")
                }
                Log.i(TAG, "SOCKS5 proxy mode enabled via mobile binding method $name")
                return true
            } catch (_: NoSuchMethodException) {
                // Try next method signature
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to enable SOCKS5 proxy mode via method $name", t)
                return false
            }
        }

        Log.w(TAG, "SOCKS5 proxy settings provided but mobile binding does not support proxy mode")
        return false
    }

    private fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }

        yggdrasil.stop()

        readerStream?.let {
            it.close()
            readerStream = null
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
        parcel?.let {
            it.close()
            parcel = null
        }

        readerThread?.let {
            it.interrupt()
            readerThread = null
        }
        writerThread?.let {
            it.interrupt()
            writerThread = null
        }
        updateThread?.let {
            it.interrupt()
            updateThread = null
        }

        var intent = Intent(STATE_INTENT)
        intent.putExtra("type", "state")
        intent.putExtra("started", false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        intent = Intent(YGG_STATE_INTENT)
        intent.putExtra("state", STATE_DISABLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        stopForeground(true)
        stopSelf()
        multicastLock?.release()
    }

    private fun connect() {
        if (!started.get()) {
            return
        }
        yggdrasil.retryPeersNow()
    }

    private fun updater() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
            return
        }
        var lastStateUpdate = System.currentTimeMillis()
        updates@ while (started.get()) {
            val treeJSON = yggdrasil.treeJSON
            if ((application as  GlobalApplication).needUiUpdates()) {
                val intent = Intent(STATE_INTENT)
                intent.putExtra("type", "state")
                intent.putExtra("started", true)
                intent.putExtra("ip", yggdrasil.addressString)
                intent.putExtra("subnet", yggdrasil.subnetString)
                intent.putExtra("pubkey", yggdrasil.publicKeyString)
                intent.putExtra("peers", yggdrasil.peersJSON)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
            val curTime = System.currentTimeMillis()
            if (lastStateUpdate + 10000 < curTime) {
                val intent = Intent(YGG_STATE_INTENT)
                var state = STATE_ENABLED
                if (yggdrasil.routingEntries > 0) {
                    state = STATE_CONNECTED
                }
                if (treeJSON != null && treeJSON != "null") {
                    val treeState = JSONArray(treeJSON)
                    val count = treeState.length()
                    if (count > 1)
                        state = STATE_CONNECTED
                }
                intent.putExtra("state", state)
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                lastStateUpdate = curTime
            }

            if (Thread.currentThread().isInterrupted) {
                break@updates
            }
            if (sleep()) return
        }
    }

    private fun sleep(): Boolean {
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            return true
        }
        return false
    }

    private fun writer() {
        val buf = ByteArray(65535)
        writes@ while (started.get()) {
            val writerStream = writerStream
            val writerThread = writerThread
            if (writerThread == null || writerStream == null) {
                Log.i(TAG, "Write thread or stream is null")
                break@writes
            }
            if (Thread.currentThread().isInterrupted || !writerStream.fd.valid()) {
                Log.i(TAG, "Write thread interrupted or file descriptor is invalid")
                break@writes
            }
            try {
                val len = yggdrasil.recvBuffer(buf)
                if (len > 0) {
                    writerStream.write(buf, 0, len.toInt())
                }
            } catch (e: Exception) {
                Log.i(TAG, "Error in write: $e")
                if (e.toString().contains("ENOBUFS")) {
                    //TODO Check this by some error code
                    //More info about this: https://github.com/AdguardTeam/AdguardForAndroid/issues/724
                    continue
                }
                break@writes
            }
        }
        writerStream?.let {
            it.close()
            writerStream = null
        }
    }

    private fun reader() {
        val b = ByteArray(65535)
        reads@ while (started.get()) {
            val readerStream = readerStream
            val readerThread = readerThread
            if (readerThread == null || readerStream == null) {
                Log.i(TAG, "Read thread or stream is null")
                break@reads
            }
            if (Thread.currentThread().isInterrupted ||!readerStream.fd.valid()) {
                Log.i(TAG, "Read thread interrupted or file descriptor is invalid")
                break@reads
            }
            try {
                val n = readerStream.read(b)
                yggdrasil.sendBuffer(b, n.toLong())
            } catch (e: Exception) {
                Log.i(TAG, "Error in sendBuffer: $e")
                break@reads
            }
        }
        readerStream?.let {
            it.close()
            readerStream = null
        }
    }
}
