# WiFi Direct 一键连接测试工具

Android 应用，用于测试 Windows 设备是否可以通过程序代码一键完成 WiFi 直连（WiFi Direct）操作。

## 功能

- **一键自动连接**：自动发现周围 WiFi Direct 设备并连接，无需用户进入系统设置手动操作
- **设备发现**：搜索并列出附近所有支持 WiFi Direct 的设备
- **手动连接**：点击设备列表中的设备发起连接
- **实时日志**：完整的操作日志输出，便于调试和分析

## 技术栈

- Android Studio
- Kotlin
- WifiP2pManager API
- ViewBinding

## 权限要求

- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE`
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`（Android 6.0+）
- `NEARBY_WIFI_DEVICES`（Android 13+）

## 使用方式

1. 在 Android Studio 中打开项目
2. 连接 Android 设备（需支持 WiFi Direct）
3. 编译运行
4. 确保目标 Windows 设备已开启 WiFi Direct
5. 点击「一键自动连接」按钮
