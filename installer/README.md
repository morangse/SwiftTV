# SwiftTV 安装助手

一个 Android 手机端的小工具：在同一个局域网内发现电视，通过 ADB 把内置的 SwiftTV 安装包推到电视上并安装。

- 应用包名：`app.swifttv.installer`
- 版本：1.0.0（versionCode 1）
- 最低手机系统：Android 8.0（API 26）
- 内置安装包：`app/src/main/assets/swifttv-0.3.2.apk`（与 SwiftTV 0.3.2 公开发行包一致）
- 图标：复用 SwiftTV 品牌图，已生成各密度启动图标与 Android 13 单色图标

## 目录与构建产物

- `app/src/main/assets/swifttv-0.3.2.apk`：内置的 SwiftTV 安装包
- `app/src/main/java/app/swifttv/installer/`：应用源码
- `app/build/outputs/apk/release/app-release.apk`：构建后的 release APK（不入库）
- `app/build/outputs/apk/debug/app-debug.apk`：构建后的 debug APK（不入库）

本目录是**独立的 Gradle 工程**，不在仓库根 `settings.gradle.kts` 的 `:app` 模块内；请先 `cd installer` 再使用本目录的 Gradle wrapper。

## 功能

1. **局域网发现**
   - 默认扫描本机所在 IPv4 网段的 5555 端口，32 并发、220 ms 超时，自动跳过本机地址。
   - 网段大于 /24（例如 /16）时自动收窄到本机 /24，避免扫描几千台设备。
   - mDNS 发现 `_adb-tls-connect._tcp`、`_adb-tls-pairing._tcp`、`_adb._tcp`，覆盖 Android 11+ 无线调试和部分电视盒子。

2. **设备识别**
   - 读取制造商、型号、品牌、Android 版本、SDK、`leanback` / `television` 特征。
   - 识别电视上是否已安装 `app.swifttv.player` 及已装版本。
   - 对小米、Redmi、海信、TCL/雷鸟、索尼、创维/酷开、长虹、华为/荣耀、飞利浦、Google TV 等品牌显示中文名称。
   - 非电视设备也可尝试安装，界面会给出提示。

3. **连接与授权**
   - 手动输入 IP + 端口连接。
   - 首次连接遇到电视“允许 USB 调试”弹窗时，最多自动重试 3 次，每次等待 45 秒。
   - 错误分类：未授权、超时、端口拒绝、不可达、协议不匹配、未知，并给出对应中文提示。

4. **Android 11+ 无线调试配对**
   - 支持输入配对端口 + 6 位配对码，使用 Kadb 的 SPAKE2 配对实现。
   - 配对成功后自动连接；优先使用 mDNS 发现的连接端口，也可手动填写无线调试主界面上的端口。
   - 额外打包 Conscrypt，Android 8/8.1 等没有 TLS 1.3 的手机也能完成配对。

5. **安装**
   - 默认 `adb install -r` 覆盖安装。
   - 版本降级自动追加 `-d` 重试。
   - 签名不一致时弹出确认框，可卸载旧版后重装（会丢失电视上的收藏和设置）。
   - 安装前检查电视 SDK 是否低于 APK 的 minSdk，避免无意义的失败。
   - 安装成功后可自动通过 `monkey` 或 `am start` 打开 SwiftTV。

6. **安装包管理**
   - 默认使用内置 SwiftTV 0.3.2。
   - 可通过系统文件选择器更换本地 APK；会读取包名、版本、图标和大小，并提示所选包是否不是 SwiftTV。

## 使用步骤

### 电视端

1. 手机和电视连接同一个 Wi‑Fi，不要使用访客网络，确认路由器没有开启 AP 隔离。
2. 电视打开开发者选项：设置 → 关于 → 连续点击“版本号”7 次。
3. 在开发者选项中打开“USB 调试”。
4. 首次连接时电视会弹出“允许 USB 调试吗？”，点“允许”，建议勾选“始终允许”。

品牌差异：

- 小米/Redmi：设置 → 关于 → 产品型号连点 7 次；部分机型还要打开“网络调试”。
- 海信：设置 → 关于 → 版本号连点；开发者选项 → USB 调试。
- TCL/雷鸟：设置 → 系统 → 关于 → 版本号连点；开发者选项 → USB 调试。
- 索尼/Google TV：设置 → 系统 → 关于 → 版本号连点；Android 11+ 可用“无线调试”。
- 创维/酷开、长虹、华为智慧屏、荣耀智慧屏：设置 → 关于本机 → 版本号连点，再打开开发者选项。

### 手机端

1. 安装构建产物 `app/build/outputs/apk/release/app-release.apk`（或 debug 版）。
2. 打开应用，等待自动扫描；也可以手动输入电视 IP 和端口。
3. 点击设备行的“连接”，在电视上确认授权。
4. 连接成功后点“安装 SwiftTV”。
5. 如果电视已装签名不同的旧版，按提示选择“卸载后重装”。

### Android 11+ 电视没有 5555 直连

1. 电视：开发者选项 → 无线调试 → 打开“使用配对码配对设备”。
2. 电视屏幕会显示配对端口和 6 位配对码。
3. 手机应用点“无线调试配对”，填入 IP、配对端口、配对码；连接端口可留空，应用会优先使用 mDNS 发现的端口，否则尝试 5555。
4. 若连接端口不是 5555，请填写无线调试主界面显示的“IP 地址和端口”中的端口。

## 构建

环境要求：

- JDK 17（`C:\Program Files\Java\jdk-17`）用于 Gradle 构建和默认单元测试
- Android SDK，compileSdk 36，build-tools 36.0.0
- Gradle wrapper 8.14
- 可选：JDK 21+ 用于运行 Kadb 身份生成测试；在 JDK 17 下该测试会自动跳过

在仓库根目录执行：

```powershell
cd installer
.\gradlew.bat :app:assembleRelease --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

也可以在仓库根目录直接运行 `.\scripts\build-installer.ps1`，它会构建 debug/release 并把 APK 复制到 `output/delivery/`。

产物：

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/apk/debug/app-debug.apk`

release 当前使用 debug 签名，目的是让构建出的 APK 可以直接侧载；若要上架或长期分发，应换成独立 release keystore。

## 验证记录

- `:app:assembleDebug`：通过
- `:app:assembleRelease`：通过，`apksigner` 验证 v2 签名通过，`zipalign` 验证通过
- `:app:testDebugUnitTest`：10 个 JVM 单元测试；JDK 17 下 9 个通过、`KadbIdentityTest` 因 Kadb 的 Java 21 class 版本自动跳过，JDK 21+ 下 10 个全部通过
  - `SubnetTest` 5 个：/24 网段、/16 与 /8 收窄、非法地址、IP 转换
  - `BrandHintsTest` 4 个：品牌识别与电视特征
  - `KadbIdentityTest` 1 个：Kadb 生成并持久化 ADB 私钥/证书
- 运行时依赖核查：release dex 中包含 `com.flyfishxu.kadb`、`com.flyfish233.crypto.spake2`、`org.conscrypt.OpenSSLProvider`、BouncyCastle
- 内置 APK 校验：与 SwiftTV 0.3.2 公开发行包的 SHA-256 完全一致
- 未在真实电视上做端到端安装测试（当前环境没有可连接的电视）；首次使用请按上面的电视端步骤操作

## 已知限制

- 只扫描 5555 端口的电视需要电视已开启网络 ADB；Android 11+ 的随机端口依赖 mDNS 或手动填写。
- 部分电视只支持 USB ADB，不开放网络调试，需要先用电脑执行 `adb tcpip 5555`，或使用电视自带的“网络调试”开关。
- 少数厂商 ROM 会限制后台网络或 mDNS；此时用“手动连接”输入 IP 和端口。
- 部分老电视的 `pm install` 行为与 AOSP 不同，安装失败时应用会显示原始错误，可复制运行日志排查。
- Android 17 起 Google 将收紧本地网络权限；当前 targetSdk 36 不受影响，后续升级 targetSdk 时需要适配 `ACCESS_LOCAL_NETWORK`。
