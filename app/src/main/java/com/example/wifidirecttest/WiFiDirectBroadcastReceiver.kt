package com.example.wifidirecttest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build

/**
 * WiFi Direct 广播接收器
 * 监听系统发出的 WiFi P2P 状态变化事件，
 * 包括 P2P 状态变更、发现设备、连接状态变化等。
 */
class WiFiDirectBroadcastReceiver(
    private val wifiDirectManager: WiFiDirectManager,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            // WiFi P2P 状态变更（启用/禁用）
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                wifiDirectManager.onWifiP2pEnabled(enabled)
            }

            // 发现的设备列表变更
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                wifiDirectManager.log("[广播] 设备列表发生变化，正在获取最新列表…")
                wifiDirectManager.requestPeers()
            }

            // 连接状态变更
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                }

                if (networkInfo?.isConnected == true) {
                    wifiDirectManager.log("[广播] WiFi Direct 已连接，正在获取连接信息…")
                    wifiDirectManager.requestConnectionInfo()
                } else {
                    wifiDirectManager.log("[广播] WiFi Direct 连接已断开")
                    wifiDirectManager.onDisconnected()
                }
            }

            // 本机设备信息变更
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }

                device?.let {
                    wifiDirectManager.log("[广播] 本机设备信息: ${it.deviceName} (${it.deviceAddress})")
                    wifiDirectManager.onThisDeviceChanged?.invoke(it)
                }
            }
        }
    }
}
