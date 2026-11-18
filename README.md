<p align="center">
  <img src="app/src/main/res/drawable-nodpi/brand_icon_legacy.png" width="96" alt="SwiftTV 应用图标">
</p>

<h1 align="center">SwiftTV</h1>

<p align="center"><strong>为电视遥控器设计的 Android TV 直播播放器</strong></p>

<p align="center">
  <a href="https://github.com/morangse/SwiftTV/releases/download/v0.3.1/SwiftTV-0.3.1.apk"><strong>下载 APK</strong></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/morangse/SwiftTV/releases/tag/v0.3.1">v0.3.1 发行说明</a>
  &nbsp;·&nbsp;
  <a href="#安装与导入">快速上手</a>
</p>

<p align="center">Android 6.0+（API 23）&nbsp; · &nbsp;M3U / XMLTV&nbsp; · &nbsp;Apache-2.0</p>

<p align="center">
  <img src="docs/screenshots/channel-gallery.jpg" width="900" alt="SwiftTV 播放演示视频时打开的透明频道画廊">
</p>

<p align="center"><sub>播放时按确认键或点按画面，打开底部频道画廊。</sub></p>

## 功能

| 功能 | 使用方式 |
| :--- | :--- |
| **频道画廊与收藏** | 按分组浏览频道，支持按央视编号排列、识别 M3U 附带的台标和收藏常看频道。方向键先移动焦点，按确认后再换台。 |
| **原生播放与备用线路** | 基于 Media3 ExoPlayer 播放 HTTP/HTTPS HLS、MPEG-TS 和 HTTP-FLV。同一频道可有多条线路，播放异常时按策略恢复或尝试备用线路，也能手动切换。 |
| **频道表与节目单** | 支持 M3U 网址、本地文件和局域网扫码导入。保存前可预览，远程订阅刷新失败时保留旧表。配置 XMLTV（.xml / .xml.gz）后可查看当前和下一档节目。 |
| **开机恢复** | 默认开启开机自动看直播，并记住上次播放的频道。可在「控制中心」关闭。实际开机和待机恢复取决于设备固件与系统权限。 |
| **大屏显示** | 播放画面全屏，画廊和菜单使用半透明面板。低配设备可在「视觉与毛玻璃效果」切换「极速纯色」。 |
| **线路检测与诊断** | 支持对单个频道或当前分类的线路测速，查看首帧和上次播放结果。通过手机扫码导出脱敏 JSON，方便复现播放问题。 |

| 线路与收藏 | 控制中心 |
| :---: | :---: |
| [<img src="docs/screenshots/channel-details.jpg" width="430" alt="频道详情中的收藏、节目、线路与首帧结果">](docs/screenshots/channel-details.jpg) | [<img src="docs/screenshots/control-center.jpg" width="430" alt="在控制中心管理播放画面、开机和 M3U 订阅">](docs/screenshots/control-center.jpg) |
| 可手动切源、重试或查看检测结果。 | 管理订阅、节目单和开机恢复。 |

<sub>截图采自 MuMu 模拟器中的 SwiftTV 0.3.1。频道、线路与节目名称为演示数据。演示视频由 Earth Science and Remote Sensing Unit, NASA Johnson Space Center 提供。<a href="https://svs.gsfc.nasa.gov/30180/">素材出处</a></sub>

## 安装与导入

1. 在电视上安装 [SwiftTV-0.3.1.apk](https://github.com/morangse/SwiftTV/releases/download/v0.3.1/SwiftTV-0.3.1.apk)。可将 APK 复制到 U 盘，再使用电视的文件管理器打开。校验文件见 [SHA256SUMS.txt](https://github.com/morangse/SwiftTV/releases/download/v0.3.1/SHA256SUMS.txt)。
2. 打开 SwiftTV 的「控制中心」，添加你有权使用的 M3U 频道表。可手动输入订阅地址、选择电视上的本地文件，或选择「手机扫码管理台」，让同一 Wi-Fi 下的手机扫码导入。
3. 在预览中确认频道，保存后选台播放。若频道表附带有效节目单，SwiftTV 会读取它。也可以在控制中心填写独立的 XMLTV 地址。

**公开版使用用户自行添加的频道表。** [M3U 格式样例](samples/example.m3u)可帮助你整理订阅，示例地址仅用于说明格式。

## 遥控器操作

| 当前界面 | 操作 | 效果 |
| :--- | :--- | :--- |
| 全屏播放 | <kbd>↑</kbd> / <kbd>↓</kbd> 或频道加减键 | 换台 |
| 全屏播放 | <kbd>OK</kbd>，或点按画面 | 打开频道画廊 |
| 全屏播放 | <kbd>→</kbd> 或菜单键 | 打开线路与收藏 |
| 全屏播放 | 数字键 | 按画廊中的频道序号换台 |
| 频道画廊 | 方向键，随后按 <kbd>OK</kbd> | 选择分组或频道，确认后播放 |
| 任意菜单 | 返回键 | 返回上一级或关闭菜单 |

## 设备与排错

Android TV 6.0（API 23）是最低系统版本，具体画质、HEVC 和 4K 播放取决于电视解码能力与源格式。需要检查时，在「控制中心 → 本机硬件解码能力」查看当前设备的检测结果。

如果某个频道无法播放，可从「线路与收藏」查看失败原因并尝试其他线路。电视无法打开应用文件选择器时，可以从同一局域网的手机扫码导入。反馈问题时可附电视型号、系统版本、复现步骤与脱敏诊断。 [设备与隐私](docs/DEVICE.md) · [提交问题](https://github.com/morangse/SwiftTV/issues)

## 从源码构建

使用 Kotlin、Android 原生 Views、SurfaceView 和 Media3。构建需要 JDK 17、Android SDK Platform 36 和 Android Build Tools 36。将 SDK 路径设为 ANDROID_HOME，或使用 Android Studio 生成本机配置。

    ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

在 Windows 使用 <code>.\gradlew.bat</code> 运行相同任务。构建后的调试 APK 位于 <code>app/build/outputs/apk/debug/</code>。签名和独立测试包参数见 [构建与验证](docs/BUILD.md)。

SwiftTV 使用 [Apache-2.0](LICENSE) 许可证。依赖、图标和第三方素材的来源见 [NOTICE](NOTICE)。
