package com.weekd.miracastreceiver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.discovery.MdnsAdvertiser
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import android.widget.TextView

/**
 * 主 Activity - 等待投屏连接
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvConnectionCode: TextView
    private lateinit var tvStatus: TextView

    private var connectionCode: String = ""

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001

        /**
         * Wi-Fi Direct 需要的危险权限，必须运行时申请。
         *
         * 少了它 `WifiP2pManager.createGroup()` 会抛 SecurityException，Miracast 直接不可用
         * （AirPlay / DLNA 不受影响）。Android 13+ 用 NEARBY_WIFI_DEVICES 取代定位权限。
         */
        private val WIFI_DIRECT_PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                // Android 12+ 只申请 FINE 会被系统直接拒绝，必须两个一起申请
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initServices()
        initViews()
        requestWifiDirectPermissions()
        checkNetworkAndStart()
    }

    private fun requestWifiDirectPermissions() {
        val missing = WIFI_DIRECT_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Timber.i("Requesting Wi-Fi Direct permissions: $missing")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return

        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            // 权限是在服务启动之后才拿到的，重启一次服务让 Wi-Fi Direct 重新初始化
            Timber.i("Wi-Fi Direct permissions granted, restarting cast service")
            startCastService()
        } else {
            Timber.w("Wi-Fi Direct permissions denied — Miracast unavailable")
            tvStatus.text = "未授予定位权限，Windows 无线投屏不可用"
        }
    }

    private fun initServices() {
        deviceInfoProvider = DeviceInfoProvider(this)
        mdnsAdvertiser = MdnsAdvertiser(this)

        // 生成连接码
        connectionCode = NetworkUtils.generateConnectionCode()
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvConnectionCode = findViewById(R.id.tv_connection_code)
        tvStatus = findViewById(R.id.tv_status)

        // 设置设备名称
        val deviceName = deviceInfoProvider.getDeviceName()
        tvDeviceName.text = deviceName

        // 设置连接码
        tvConnectionCode.text = getString(R.string.connection_code, connectionCode)
    }

    private fun checkNetworkAndStart() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            tvStatus.text = "网络未连接"
            Timber.w("Network not available")
            return
        }

        if (!NetworkUtils.isWifiConnected(this)) {
            tvStatus.text = "未连接到 Wi-Fi"
            Timber.w("WiFi not connected")
        }

        // 获取 IP 地址
        val ipAddress = NetworkUtils.getLocalIpAddress()
        if (ipAddress != null) {
            tvDeviceIp.text = getString(R.string.device_ip, ipAddress)
            Timber.i("Local IP: $ipAddress")
        } else {
            tvDeviceIp.text = getString(R.string.device_ip, "获取中...")
        }

        // 启动服务
        startCastService()
        startAdvertising()

        // 更新状态
        updateStatus()
    }

    private fun startCastService() {
        val intent = Intent(this, CastReceiverService::class.java)
        startService(intent)
        Timber.i("Cast receiver service started")
    }

    private fun startAdvertising() {
        val deviceInfo = deviceInfoProvider.getDeviceInfo()
        val deviceName = deviceInfoProvider.getDeviceName()
        val macAddress = NetworkUtils.getMacAddress()

        // AirPlay is advertised by CastReceiverService/AirPlayReceiver only.
        // Do not register a second _airplay._tcp service here: duplicate AirPlay mDNS
        // records with different TXT capabilities can make iOS connect with the wrong
        // protocol path and never send the mirroring video SETUP.

        // 启动自定义 Miracast 服务广播（用于自定义 Android 发送端）
        mdnsAdvertiser.startAdvertising(
            serviceName = deviceName,
            port = 8080,
            deviceInfo = deviceInfo + ("code" to connectionCode)
        )

        Timber.i("Started AirPlay and Miracast advertising: $deviceName")
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            while (true) {
                if (mdnsAdvertiser.isAdvertising()) {
                    tvStatus.text = getString(R.string.waiting_connection)
                } else {
                    tvStatus.text = "服务启动中..."
                }
                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mdnsAdvertiser.stopAdvertising()
        Timber.i("MainActivity destroyed")
    }
}
