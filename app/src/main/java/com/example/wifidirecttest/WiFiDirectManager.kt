package com.example.wifidirecttest

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * WiFi Direct 核心管理器
 * 封装了发现设备、自动连接、断开连接等功能。
 * 通过程序代码自动完成 WiFi 直连操作，无需用户手动操作系统设置页面。
 */
class WiFiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WiFiDirectManager"
    }

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: WiFiDirectBroadcastReceiver? = null

    // 已发现的设备列表
    val discoveredDevices = mutableListOf<WifiP2pDevice>()

    // 当前连接状态
    var isConnected = false
        private set
    var isDiscovering = false
        private set
    var connectionInfo: WifiP2pInfo? = null
        private set
    var connectedDevice: WifiP2pDevice? = null
        private set

    // 回调
    var onLog: ((String) -> Unit)? = null
    var onDevicesChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    var onConnectionChanged: ((Boolean, WifiP2pInfo?) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
    var onThisDeviceChanged: ((WifiP2pDevice?) -> Unit)? = null

    // 自动连接模式：发现设备后是否自动连接第一个可用设备
    var autoConnectOnDiscovery = false

    fun initialize() {
        log("正在初始化 WiFi Direct...")
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            log("[错误] 此设备不支持 WiFi Direct")
            return
        }
        channel = manager!!.initialize(context, Looper.getMainLooper()) {
            log("[警告] WiFi Direct Channel 已断开")
        }
        log("WiFi Direct 初始化成功")
    }

    fun registerReceiver() {
        receiver = WiFiDirectBroadcastReceiver(this, manager!!, channel!!)
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)
        log("广播接收器已注册")
    }

    fun unregisterReceiver() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
                log("广播接收器已注销")
            } catch (e: Exception) {
                Log.w(TAG, "Unregister receiver error: ${e.message}")
            }
        }
        receiver = null
    }

    /**
     * 开始发现周围的 WiFi Direct 设备
     */
    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        val mgr = manager ?: run { log("[错误] Manager 未初始化"); return }
        val ch = channel ?: run { log("[错误] Channel 未初始化"); return }

        log("开始搜索周围 WiFi Direct 设备...")
        onStatusChanged?.invoke("状态：正在搜索设备…")

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isDiscovering = true
                log("设备搜索已启动，等待系统回调发现结果…")
            }

            override fun onFailure(reason: Int) {
                isDiscovering = false
                val reasonStr = failureReasonToString(reason)
                log("[错误] 设备搜索失败: $reasonStr")
                onStatusChanged?.invoke("状态：搜索失败 ($reasonStr)")
            }
        })
    }

    /**
     * 停止发现设备
     */
    fun stopDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return

        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isDiscovering = false
                log("已停止搜索")
                onStatusChanged?.invoke("状态：空闲")
            }

            override fun onFailure(reason: Int) {
                log("[警告] 停止搜索失败: ${failureReasonToString(reason)}")
            }
        })
    }

    /**
     * 更新发现的设备列表（由 BroadcastReceiver 调用）
     */
    @SuppressLint("MissingPermission")
    fun requestPeers() {
        val mgr = manager ?: return
        val ch = channel ?: return

        mgr.requestPeers(ch) { peerList ->
            val devices = peerList.deviceList.toList()
            discoveredDevices.clear()
            discoveredDevices.addAll(devices)

            if (devices.isEmpty()) {
                log("设备列表已更新：未发现设备")
            } else {
                log("发现 ${devices.size} 个设备：")
                devices.forEachIndexed { index, device ->
                    val statusStr = deviceStatusToString(device.status)
                    log("  [${index + 1}] ${device.deviceName} (${device.deviceAddress}) - $statusStr")
                }
            }

            onDevicesChanged?.invoke(devices)

            // 自动连接模式
            if (autoConnectOnDiscovery && !isConnected && devices.isNotEmpty()) {
                log("自动连接模式：尝试连接第一个可用设备...")
                val target = devices.firstOrNull {
                    it.status == WifiP2pDevice.AVAILABLE
                } ?: devices.first()
                connectToDevice(target)
                autoConnectOnDiscovery = false
            }
        }
    }

    /**
     * 连接到指定设备 — 核心的程序自动连接方法
     * 通过 WifiP2pManager.connect() 直接发起连接请求，
     * 不需要用户在系统设置中手动点击设备。
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WifiP2pDevice) {
        val mgr = manager ?: run { log("[错误] Manager 未初始化"); return }
        val ch = channel ?: run { log("[错误] Channel 未初始化"); return }

        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log("正在连接到: ${device.deviceName}")
        log("  设备地址: ${device.deviceAddress}")
        log("  设备类型: ${device.primaryDeviceType}")
        log("  设备状态: ${deviceStatusToString(device.status)}")
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        onStatusChanged?.invoke("状态：正在连接 ${device.deviceName}…")

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // 使用 PBC (Push Button Configuration) 方式，无需 PIN 码
            wps.setup = WpsInfo.PBC
            // 尝试让本设备作为 Group Owner (可选)
            groupOwnerIntent = 0
        }

        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("连接请求已发送，等待对方接受…")
                log("（注意：Windows 设备可能会弹出配对提示）")
                connectedDevice = device
            }

            override fun onFailure(reason: Int) {
                val reasonStr = failureReasonToString(reason)
                log("[错误] 连接请求发送失败: $reasonStr")
                onStatusChanged?.invoke("状态：连接失败 ($reasonStr)")
            }
        })
    }

    /**
     * 一键自动连接：发现设备并自动连接
     * 这是主要的一键操作方法。
     */
    fun autoDiscoverAndConnect() {
        log("═══════════════════════════════")
        log("启动一键自动连接流程")
        log("═══════════════════════════════")

        if (isConnected) {
            log("当前已有连接，先断开…")
            disconnect()
        }

        autoConnectOnDiscovery = true
        discoverPeers()
    }

    /**
     * 更新连接状态（由 BroadcastReceiver 调用）
     */
    fun requestConnectionInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return

        mgr.requestConnectionInfo(ch) { info ->
            connectionInfo = info
            if (info?.groupFormed == true) {
                isConnected = true
                log("═══════════════════════════════")
                log("WiFi Direct 连接已建立！")
                log("  Group Owner: ${if (info.isGroupOwner) "本机" else "对方"}")
                log("  Group Owner IP: ${info.groupOwnerAddress?.hostAddress}")
                log("═══════════════════════════════")
                onStatusChanged?.invoke("状态：已连接")
                onConnectionChanged?.invoke(true, info)
            } else {
                isConnected = false
                log("连接信息更新：未建立 Group")
                onConnectionChanged?.invoke(false, null)
            }
        }
    }

    /**
     * 断开当前连接
     */
    fun disconnect() {
        val mgr = manager ?: return
        val ch = channel ?: return

        log("正在断开 WiFi Direct 连接…")
        onStatusChanged?.invoke("状态：正在断开…")

        // 先尝试移除 Group
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isConnected = false
                connectedDevice = null
                connectionInfo = null
                log("已成功断开连接")
                onStatusChanged?.invoke("状态：已断开")
                onConnectionChanged?.invoke(false, null)
            }

            override fun onFailure(reason: Int) {
                log("[警告] 断开连接失败: ${failureReasonToString(reason)}")
                // 即使失败也尝试取消连接
                cancelConnect()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun cancelConnect() {
        val mgr = manager ?: return
        val ch = channel ?: return

        mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isConnected = false
                log("连接已取消")
                onStatusChanged?.invoke("状态：已断开")
            }

            override fun onFailure(reason: Int) {
                log("[警告] 取消连接失败: ${failureReasonToString(reason)}")
                onStatusChanged?.invoke("状态：断开异常")
            }
        })
    }

    fun log(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }

    fun onWifiP2pEnabled(enabled: Boolean) {
        if (enabled) {
            log("WiFi Direct 功能已启用")
        } else {
            log("[警告] WiFi Direct 功能未启用，请打开 WiFi")
            onStatusChanged?.invoke("状态：WiFi Direct 未启用")
        }
    }

    fun onDisconnected() {
        isConnected = false
        connectedDevice = null
        connectionInfo = null
        onStatusChanged?.invoke("状态：已断开")
        onConnectionChanged?.invoke(false, null)
    }

    fun shutdown() {
        log("正在关闭 WiFi Direct Manager...")
        if (isDiscovering) {
            stopDiscovery()
        }
        if (isConnected) {
            disconnect()
        }
        unregisterReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            channel?.close()
        }
        channel = null
        manager = null
        log("WiFi Direct Manager 已关闭")
    }

    private fun failureReasonToString(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P不支持"
        WifiP2pManager.ERROR -> "内部错误"
        WifiP2pManager.BUSY -> "系统繁忙"
        else -> "未知错误($reason)"
    }

    private fun deviceStatusToString(status: Int): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "可用"
        WifiP2pDevice.INVITED -> "已邀请"
        WifiP2pDevice.CONNECTED -> "已连接"
        WifiP2pDevice.FAILED -> "失败"
        WifiP2pDevice.UNAVAILABLE -> "不可用"
        else -> "未知($status)"
    }
}
