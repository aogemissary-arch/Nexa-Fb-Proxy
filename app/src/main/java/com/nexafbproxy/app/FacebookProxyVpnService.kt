package com.nexafbproxy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.wgtunnel.hevtunnel.HevTunnelConfig
import com.wgtunnel.hevtunnel.TProxyService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FacebookProxyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Đang kết nối SOCKS5…")
                )

                val config = ProxyConfig(
                    host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
                    port = intent.getIntExtra(EXTRA_PORT, 0),
                    username = intent.getStringExtra(EXTRA_USER).orEmpty(),
                    password = intent.getStringExtra(EXTRA_PASS).orEmpty()
                )

                val selectedPackages =
                    intent.getStringArrayListExtra(EXTRA_PACKAGES)?.toList().orEmpty()

                worker.execute { startTunnel(config, selectedPackages) }
            }
        }

        return START_STICKY
    }

    private fun startTunnel(config: ProxyConfig, requestedPackages: List<String>) {
        if (config.host.isBlank() || config.port !in 1..65535) {
            failClosed("Host/port proxy không hợp lệ.")
            return
        }

        val installedPackages = requestedPackages
            .distinct()
            .filter { isPackageInstalled(it) }

        if (installedPackages.isEmpty()) {
            failClosed("Không tìm thấy app đã chọn trên máy.")
            return
        }

        if (TProxyService.TProxyIsRunning()) {
            val label = packageLabel(installedPackages)
            updateState(
                active = true,
                tunnelRunning = true,
                message = "$label → SOCKS5 ${config.host}:${config.port}"
            )
            updateNotification("$label → SOCKS5 ${config.host}:${config.port}")
            return
        }

        stopping.set(false)

        try {
            val builder = Builder()
                .setSession("NEXA Facebook + Messenger SOCKS5")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .setBlocking(false)

            installedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }

            vpnInterface?.close()
            vpnInterface = builder.establish()

            val tun = vpnInterface
            if (tun == null) {
                failClosed("Android không tạo được VPN interface.")
                return
            }

            val label = packageLabel(installedPackages)

            updateState(
                active = true,
                tunnelRunning = false,
                message = "VPN đã bật; đang khởi động SOCKS5"
            )
            updateNotification("Kill Switch đang giữ $label…")

            val hevConfig = HevTunnelConfig(
                mtu = TUN_MTU,
                ipv4 = TUN_IPV4,
                ipv6 = TUN_IPV6_PLACEHOLDER,
                address = config.host,
                port = config.port,
                username = config.username,
                password = config.password
            )

            val configFile: File = TProxyService.createHevTunnelConfig(
                hevConfig,
                cacheDir
            )

            val started = TProxyService.TProxyStartService(
                configFile.absolutePath,
                tun.fd
            )

            if (!started) {
                failClosed("SOCKS5 tunnel không khởi động được.")
                return
            }

            updateState(
                active = true,
                tunnelRunning = true,
                message = "$label → SOCKS5 ${config.host}:${config.port}"
            )
            updateNotification("$label → SOCKS5 ${config.host}:${config.port}")

            monitorTunnel(config, label)

        } catch (e: Exception) {
            failClosed(e.message ?: "Lỗi VPN không xác định.")
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun packageLabel(packages: List<String>): String {
        val names = packages.mapNotNull {
            when (it) {
                FACEBOOK_PACKAGE -> "Facebook"
                FACEBOOK_LITE_PACKAGE -> "Facebook Lite"
                MESSENGER_PACKAGE -> "Messenger"
                else -> null
            }
        }
        return names.joinToString(" + ").ifBlank { "App đã chọn" }
    }

    private fun monitorTunnel(config: ProxyConfig, label: String) {
        while (!stopping.get()) {
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                return
            }

            if (stopping.get()) return

            val running = try {
                TProxyService.TProxyIsRunning()
            } catch (_: Throwable) {
                false
            }

            if (!running) {
                updateState(
                    active = vpnInterface != null,
                    tunnelRunning = false,
                    message = "KILL SWITCH: SOCKS5 mất kết nối"
                )
                updateNotification("KILL SWITCH — $label đang bị chặn")
                return
            }

            updateState(
                active = true,
                tunnelRunning = true,
                message = "$label → SOCKS5 ${config.host}:${config.port}"
            )
        }
    }

    private fun failClosed(message: String) {
        updateState(
            active = vpnInterface != null,
            tunnelRunning = false,
            message = "KILL SWITCH: $message"
        )
        updateNotification("KILL SWITCH — app đã chọn đang bị chặn")
    }

    private fun stopEverything() {
        if (!stopping.compareAndSet(false, true)) return

        try {
            if (TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService()
            }
        } catch (_: Throwable) {
        }

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        updateState(active = false, tunnelRunning = false, message = "Đã tắt")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEverything()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Facebook + Messenger Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Trạng thái VPN SOCKS5 cho Facebook và Messenger"
                setShowBadge(false)
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("NEXA FB Proxy")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateState(active: Boolean, tunnelRunning: Boolean, message: String) {
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .putBoolean(KEY_TUNNEL_RUNNING, tunnelRunning)
            .putString(KEY_MESSAGE, message)
            .apply()
    }

    companion object {
        const val ACTION_START = "com.nexafbproxy.app.START"
        const val ACTION_STOP = "com.nexafbproxy.app.STOP"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "username"
        const val EXTRA_PASS = "password"
        const val EXTRA_PACKAGES = "packages"

        const val STATE_PREFS = "vpn_state"
        const val KEY_ACTIVE = "active"
        const val KEY_TUNNEL_RUNNING = "tunnel_running"
        const val KEY_MESSAGE = "message"

        const val FACEBOOK_PACKAGE = "com.facebook.katana"
        const val FACEBOOK_LITE_PACKAGE = "com.facebook.lite"
        const val MESSENGER_PACKAGE = "com.facebook.orca"

        private const val TUN_MTU = 1500
        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6_PLACEHOLDER = "fc00::1"

        private const val CHANNEL_ID = "nexa_fb_proxy_channel"
        private const val NOTIFICATION_ID = 9101
    }
}
