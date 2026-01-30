package com.example.wifidirecttest

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.wifidirecttest.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WiFi Direct 一键连接测试工具 - 主界面
 *
 * 核心功能：
 * 1. 一键自动发现并连接周围 WiFi Direct 设备（包括 Windows 设备）
 * 2. 仅通过发现搜索附近设备
 * 3. 手动点击设备列表中的设备进行连接
 * 4. 断开已有连接
 * 5. 实时日志输出
 *
 * 所有连接操作均通过程序代码（WifiP2pManager API）完成，
 * 不需要用户进入系统 WiFi Direct 设置页面手动操作。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiDirectManager: WiFiDirectManager
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wifiDirectManager = WiFiDirectManager(this)
        setupCallbacks()
        setupButtons()

        if (checkAndRequestPermissions()) {
            initWifiDirect()
        }
    }

    private fun setupCallbacks() {
        wifiDirectManager.onLog = { message ->
            runOnUiThread {
                appendLog(message)
            }
        }

        wifiDirectManager.onDevicesChanged = { devices ->
            runOnUiThread {
                updateDeviceList(devices)
            }
        }

        wifiDirectManager.onConnectionChanged = { connected, info ->
            runOnUiThread {
                if (connected && info != null) {
                    binding.tvConnectionInfo.visibility = android.view.View.VISIBLE
                    val role = if (info.isGroupOwner) "Group Owner (本机)" else "Client (本机)"
                    val ownerIp = info.groupOwnerAddress?.hostAddress ?: "N/A"
                    binding.tvConnectionInfo.text = "已连接 | 角色: $role | GO IP: $ownerIp"
                    binding.btnAutoConnect.isEnabled = false
                } else {
                    binding.tvConnectionInfo.visibility = android.view.View.GONE
                    binding.btnAutoConnect.isEnabled = true
                }
            }
        }

        wifiDirectManager.onStatusChanged = { status ->
            runOnUiThread {
                binding.tvStatus.text = status
            }
        }

        wifiDirectManager.onThisDeviceChanged = { device ->
            runOnUiThread {
                device?.let {
                    binding.tvDeviceInfo.text = "本机: ${it.deviceName} (${it.deviceAddress})"
                }
            }
        }
    }

    private fun setupButtons() {
        // 一键自动连接按钮
        binding.btnAutoConnect.setOnClickListener {
            appendLog("═══ 用户点击：一键自动连接 ═══")
            wifiDirectManager.autoDiscoverAndConnect()
        }

        // 仅发现设备
        binding.btnDiscover.setOnClickListener {
            if (wifiDirectManager.isDiscovering) {
                wifiDirectManager.stopDiscovery()
                binding.btnDiscover.text = getString(R.string.btn_start_discover)
            } else {
                wifiDirectManager.discoverPeers()
                binding.btnDiscover.text = getString(R.string.btn_stop_discover)
            }
        }

        // 断开连接
        binding.btnDisconnect.setOnClickListener {
            wifiDirectManager.disconnect()
        }

        // 清空日志
        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
            appendLog("日志已清空")
        }
    }

    private fun updateDeviceList(devices: List<WifiP2pDevice>) {
        if (devices.isEmpty()) {
            binding.tvDeviceList.text = getString(R.string.no_devices_found)
            return
        }

        val sb = StringBuilder()
        devices.forEachIndexed { index, device ->
            val statusStr = when (device.status) {
                WifiP2pDevice.AVAILABLE -> "可用"
                WifiP2pDevice.INVITED -> "已邀请"
                WifiP2pDevice.CONNECTED -> "已连接"
                WifiP2pDevice.FAILED -> "失败"
                WifiP2pDevice.UNAVAILABLE -> "不可用"
                else -> "未知"
            }
            sb.appendLine("[${index + 1}] ${device.deviceName}")
            sb.appendLine("    地址: ${device.deviceAddress}")
            sb.appendLine("    状态: $statusStr")
            sb.appendLine("    类型: ${device.primaryDeviceType}")
            if (index < devices.size - 1) sb.appendLine("──────────────────")
        }

        binding.tvDeviceList.text = sb.toString()

        // 设置点击设备列表区域时，连接第一个可用设备
        binding.tvDeviceList.setOnClickListener {
            val available = devices.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
            if (available != null) {
                appendLog("用户点击设备列表，连接: ${available.deviceName}")
                wifiDirectManager.connectToDevice(available)
            } else if (devices.isNotEmpty()) {
                appendLog("用户点击设备列表，连接: ${devices[0].deviceName}")
                wifiDirectManager.connectToDevice(devices[0])
            }
        }
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val logLine = "[$timestamp] $message\n"
        binding.tvLog.append(logLine)
        // 自动滚动到底部
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun initWifiDirect() {
        wifiDirectManager.initialize()
        wifiDirectManager.registerReceiver()
        appendLog("WiFi Direct 已就绪，可以开始操作")
    }

    // ───── 权限处理 ─────

    private fun checkAndRequestPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        // Android 13+ (API 33) 需要 NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        // 定位权限（Android 6.0 ~ 12 需要）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        return if (permissions.isNotEmpty()) {
            appendLog("正在请求必要权限: ${permissions.joinToString()}")
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            false
        } else {
            appendLog("所有必要权限已获取")
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                appendLog("权限已全部授予")
                initWifiDirect()
            } else {
                appendLog("[错误] 部分权限被拒绝，WiFi Direct 可能无法正常工作")
                Toast.makeText(this, "需要授予权限才能使用 WiFi Direct 功能", Toast.LENGTH_LONG).show()
                // 仍然尝试初始化
                initWifiDirect()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (wifiDirectManager.discoveredDevices.isEmpty()) {
            // 页面恢复时不自动搜索，等用户操作
        }
    }

    override fun onPause() {
        super.onPause()
        // 暂停时不停止搜索，保持后台工作能力
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiDirectManager.shutdown()
    }
}
