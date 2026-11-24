# 构建与验证

- 环境为 JDK 17、Android SDK Platform 36 和 Build Tools 36。
- 在 macOS / Linux 运行 ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug。在 Windows 将 ./gradlew 改为 .\gradlew.bat。
- 手机端安装器是 `installer/` 下的独立 Gradle 工程，需先 `cd installer`，再运行 `.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease`。也可以从仓库根目录运行 `.\scripts\build-installer.ps1`，详见 `installer/README.md`。
- 调试时可传入 -PisolatedUiCheck=true 构建独立应用 ID。
- scripts/controlled_server.py 使用 FFmpeg 生成原创受控 HLS 测试视频，可验证播放、切台和故障切换。
- 正式发布需要维护者保管签名资料。签名目录默认为 .signing/，也可通过 -Pswifttv.signingDir=<目录> 指向已有签名。密钥只在本机构建时读取。
