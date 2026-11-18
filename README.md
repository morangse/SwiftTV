# SwiftTV

SwiftTV 是为 Android TV 设计的原生直播播放器，兼容 Android 6.0（API 23）及以上。用遥控器选台、查看节目单和收藏，也可以用手机在同一局域网管理频道表。

[下载 0.3.1 安装包](https://github.com/morangse/SwiftTV/releases/tag/v0.3.1)

## 开始使用

1. 安装 Release 中的 APK，打开 SwiftTV 的「控制中心」。
2. 输入你有权使用的 M3U 订阅地址、导入本地文件，或用手机扫码添加。公开版需要先添加频道表。
3. 确认键打开频道画廊，上下键换台，右键或菜单键查看线路和收藏。也可点击播放画面打开频道画廊。

支持兼容的 HTTP/HTTPS HLS、MPEG-TS 和 HTTP-FLV 线路、XMLTV 节目单、备用线路、脱敏诊断和开机恢复上次频道。「开机自动看直播」默认开启，可在控制中心关闭。实际开机与待机恢复取决于电视系统权限和固件。详见 [设备与隐私](docs/DEVICE.md)。

河南移动订阅快捷入口应用户操作从上游下载频道表供预览。线路仅适用于部分河南移动网络。参见 [上游说明](https://github.com/Elykia093/HA-CMCC-IPTV)。

## 从源码构建

需要 JDK 17 和 Android SDK Platform 36。将 SDK 路径设为 ANDROID_HOME，或使用 Android Studio 创建本机 SDK 配置。

Windows PowerShell：

    .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

macOS / Linux 使用 ./gradlew 执行相同任务。调试 APK 位于 app/build/outputs/apk/debug/。安装正式包的设备需用同签名的发布包覆盖升级。构建独立测试包见 [构建与验证](docs/BUILD.md)。

## 许可

SwiftTV 代码使用 [Apache-2.0](LICENSE)。组件、应用图标和第三方频道资料见 [NOTICE](NOTICE)。[M3U 样例](samples/example.m3u) 演示频道表格式，地址为占位示例。

SwiftTV is a native Android TV player for user-provided M3U playlists. It supports remote navigation, XMLTV guides, favorites, and source failover.
