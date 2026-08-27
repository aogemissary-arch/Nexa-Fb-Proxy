package com.nexafbproxy.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nexafbproxy.app.databinding.ActivityMainBinding
import java.util.ArrayList
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var secureStore: SecureStore

    private val io = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var pendingConfig: ProxyConfig? = null
    private var pendingPackages: List<String> = emptyList()
    private var passwordVisible = false

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingConfig?.let { actuallyStartVpn(it, pendingPackages) }
            } else {
                toast("Bạn chưa cấp quyền VPN.")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val stateTicker = object : Runnable {
        override fun run() {
            renderVpnState()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secureStore = SecureStore(this)
        loadSavedConfig()
        setupUi()
        requestNotificationPermissionIfNeeded()

        uiHandler.post(stateTicker)
    }

    private fun setupUi() {
        binding.tvTarget.text =
            "Facebook: ${FacebookProxyVpnService.FACEBOOK_PACKAGE}\n" +
            "Facebook Lite: ${FacebookProxyVpnService.FACEBOOK_LITE_PACKAGE}\n" +
            "Messenger: ${FacebookProxyVpnService.MESSENGER_PACKAGE}"

        binding.btnTogglePassword.setOnClickListener {
            passwordVisible = !passwordVisible
            binding.etPassword.inputType = if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            binding.etPassword.setSelection(binding.etPassword.text?.length ?: 0)
            binding.btnTogglePassword.text =
                if (passwordVisible) "ẨN MẬT KHẨU" else "HIỆN MẬT KHẨU"
        }

        binding.btnSave.setOnClickListener {
            val config = readConfig() ?: return@setOnClickListener
            secureStore.save(config)
            saveSelectedApps()
            toast("Đã lưu cấu hình.")
        }

        binding.btnTest.setOnClickListener {
            val config = readConfig() ?: return@setOnClickListener
            testProxy(config)
        }

        binding.btnStart.setOnClickListener {
            val config = readConfig() ?: return@setOnClickListener
            val selected = selectedPackages()

            if (selected.isEmpty()) {
                toast("Chọn ít nhất 1 app.")
                return@setOnClickListener
            }

            val installed = selected.filter { isPackageInstalled(it) }
            if (installed.isEmpty()) {
                toast("Không app nào trong lựa chọn hiện được cài trên máy.")
                return@setOnClickListener
            }

            secureStore.save(config)
            saveSelectedApps()
            pendingConfig = config
            pendingPackages = installed

            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                actuallyStartVpn(config, installed)
            }
        }

        binding.btnStop.setOnClickListener {
            val intent = Intent(this, FacebookProxyVpnService::class.java)
                .setAction(FacebookProxyVpnService.ACTION_STOP)
            startService(intent)
        }

        binding.btnOpenSelected.setOnClickListener {
            openFirstSelectedInstalledApp()
        }

        binding.btnClearCredentials.setOnClickListener {
            secureStore.clearCredentials()
            binding.etUsername.setText("")
            binding.etPassword.setText("")
            toast("Đã xóa username/password đã lưu.")
        }
    }

    private fun loadSavedConfig() {
        val config = secureStore.load(
            defaultHost = DEFAULT_HOST,
            defaultPort = DEFAULT_PORT
        )

        binding.etHost.setText(config.host)
        binding.etPort.setText(config.port.toString())
        binding.etUsername.setText(config.username)
        binding.etPassword.setText(config.password)

        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        binding.cbFacebook.isChecked = prefs.getBoolean(KEY_FB, true)
        binding.cbFacebookLite.isChecked = prefs.getBoolean(KEY_FB_LITE, false)
        binding.cbMessenger.isChecked = prefs.getBoolean(KEY_MESSENGER, true)
    }

    private fun saveSelectedApps() {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FB, binding.cbFacebook.isChecked)
            .putBoolean(KEY_FB_LITE, binding.cbFacebookLite.isChecked)
            .putBoolean(KEY_MESSENGER, binding.cbMessenger.isChecked)
            .apply()
    }

    private fun selectedPackages(): List<String> {
        val result = mutableListOf<String>()

        if (binding.cbFacebook.isChecked) {
            result += FacebookProxyVpnService.FACEBOOK_PACKAGE
        }
        if (binding.cbFacebookLite.isChecked) {
            result += FacebookProxyVpnService.FACEBOOK_LITE_PACKAGE
        }
        if (binding.cbMessenger.isChecked) {
            result += FacebookProxyVpnService.MESSENGER_PACKAGE
        }

        return result
    }

    private fun readConfig(): ProxyConfig? {
        val host = binding.etHost.text?.toString()?.trim().orEmpty()
        val port = binding.etPort.text?.toString()?.trim()?.toIntOrNull()
        val username = binding.etUsername.text?.toString().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (host.isBlank()) {
            binding.etHost.error = "Nhập Host/IP"
            return null
        }
        if (port == null || port !in 1..65535) {
            binding.etPort.error = "Port không hợp lệ"
            return null
        }
        if (username.isBlank()) {
            binding.etUsername.error = "Nhập username"
            return null
        }
        if (password.isBlank()) {
            binding.etPassword.error = "Nhập password"
            return null
        }

        return ProxyConfig(host, port, username, password)
    }

    private fun testProxy(config: ProxyConfig) {
        binding.btnTest.isEnabled = false
        binding.tvExitIp.text = "Đang kiểm tra SOCKS5…"

        io.execute {
            try {
                val result = Socks5Tester.test(config)
                runOnUiThread {
                    binding.tvExitIp.text =
                        "EXIT IP: ${result.exitIp}  •  ${result.latencyMs} ms"
                    binding.btnTest.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvExitIp.text = "LỖI: ${e.message}"
                    binding.btnTest.isEnabled = true
                }
            }
        }
    }

    private fun actuallyStartVpn(config: ProxyConfig, packages: List<String>) {
        val intent = Intent(this, FacebookProxyVpnService::class.java)
            .setAction(FacebookProxyVpnService.ACTION_START)
            .putExtra(FacebookProxyVpnService.EXTRA_HOST, config.host)
            .putExtra(FacebookProxyVpnService.EXTRA_PORT, config.port)
            .putExtra(FacebookProxyVpnService.EXTRA_USER, config.username)
            .putExtra(FacebookProxyVpnService.EXTRA_PASS, config.password)
            .putStringArrayListExtra(
                FacebookProxyVpnService.EXTRA_PACKAGES,
                ArrayList(packages)
            )

        ContextCompat.startForegroundService(this, intent)
    }

    private fun openFirstSelectedInstalledApp() {
        val launchIntent = selectedPackages()
            .firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }

        if (launchIntent == null) {
            toast("Không tìm thấy app đã chọn.")
        } else {
            startActivity(launchIntent)
        }
    }

    private fun renderVpnState() {
        val prefs = getSharedPreferences(
            FacebookProxyVpnService.STATE_PREFS,
            MODE_PRIVATE
        )

        val active = prefs.getBoolean(FacebookProxyVpnService.KEY_ACTIVE, false)
        val tunnelRunning = prefs.getBoolean(
            FacebookProxyVpnService.KEY_TUNNEL_RUNNING,
            false
        )
        val message = prefs.getString(
            FacebookProxyVpnService.KEY_MESSAGE,
            "Đã tắt"
        ).orEmpty()

        binding.tvVpnStatus.text = when {
            active && tunnelRunning -> "● ĐANG BẬT\n$message"
            active && !tunnelRunning -> "● KILL SWITCH\n$message"
            else -> "○ ĐÃ TẮT"
        }

        binding.btnStart.isEnabled = !(active && tunnelRunning)
        binding.btnStop.isEnabled = active
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(stateTicker)
        io.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_HOST = "79.127.168.43"
        private const val DEFAULT_PORT = 50101

        private const val UI_PREFS = "ui_prefs"
        private const val KEY_FB = "fb"
        private const val KEY_FB_LITE = "fb_lite"
        private const val KEY_MESSENGER = "messenger"
    }
}
