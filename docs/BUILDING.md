# 可复现构建与 APK

## 固定工具链

| 工具 | 版本 |
|---|---|
| JDK | Eclipse Temurin 21.0.9+10 |
| Gradle | 8.13（完整 Wrapper，分发包 SHA-256 固定） |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose 编译器插件 | 2.2.21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Android build-tools | 36.0.0 |
| Compose BOM | 2025.11.01 |
| Room / DataStore | 2.8.4 / 1.2.0 |
| Coroutines / Serialization | 1.10.2 / 1.9.0 |

版本写在项目 Gradle 文件。不要为了通过检查降低 targetSdk 或关闭 TLS / 下载校验。

## 当前云环境

在已有 `/workspace/test` 检出目录运行；每个云任务已隔离，不需要创建 Git worktree。

```bash
cd /workspace/test
python3 scripts/setup-cloud.py
source scripts/env.sh
./gradlew --version
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

安装脚本仅适用于 Linux x86_64，将 JDK、SDK 和缓存放在 `/workspace/android-toolchain`，校验发布方的下载摘要；可重复运行。需要 Python 3.12+、网络与有效代理 CA。下载来源包括 `github.com`（含其 release 资源域）、`raw.githubusercontent.com`、`dl.google.com`、`downloads.gradle.org`、`services.gradle.org`、`repo.maven.apache.org`、`plugins.gradle.org`。代理配置只写外部 Gradle 缓存，保留 TLS 校验，不写令牌。

云环境的 HOME 为只读时，ADB 仍可能需要 `$HOME/.android`。当前机器已将该工具目录链接到 `/workspace/android-toolchain/android-user`；新环境若没有这个链接，需由环境安装阶段创建空目录的链接或授予该目录写权限。不要覆盖已有 Android 用户目录，不要重定义 HOME。`scripts/env.sh` 为其他 Android 工具设置外部用户目录。

## 本地 Android Studio

安装 JDK 21、Android SDK Platform 36 和 Build-Tools 36.0.0，配置 `JAVA_HOME`、`ANDROID_HOME`，或通过 Android Studio 自动生成本机 `local.properties`。从项目目录执行同一条 Gradle 检查命令。不要提交 `local.properties`、签名密钥或密码。Windows 使用 `gradlew.bat`。

输出：

- 安装包：`app/build/outputs/apk/debug/app-debug.apk`
- instrumentation 包：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- 核心测试报告：`core/build/reports/tests/test/index.html`
- 安卓单元测试：`app/build/reports/tests/testDebugUnitTest/index.html`
- lint：`app/build/reports/lint-results-debug.html`

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cn.zhundian.app/.MainActivity
# 有已启动、已授权设备时才执行：
./gradlew :app:connectedDebugAndroidTest
```

首次侧载需允许安装未知来源。安装后在应用设置页按需授权通知、精确闹钟、全屏提醒与身体活动；联系人电话默认关闭，只有在实体手机上明确配置与确认后才能启用。模拟器测试与自动测试不应拨打真实号码。

## GitHub Actions

`.github/workflows/android.yml` 在任务分支推送、PR 和手动触发时运行核心测试、安卓单元测试、lint、debug APK 与 instrumentation APK 构建。每个第三方 Action 都固定到从其官方仓库获取的提交 SHA。工作流只需 `contents: read`，不创建 Release、不上传应用商店、不改变仓库可见性。

提交分支后，打开仓库 **Actions → Android build and checks → 对应运行 → Artifacts**，下载 `zhundian-debug-<SHA>`（APK 与构建身份）和 `android-reports-<SHA>`（测试与 lint 报告），默认保留 14 天。没有实际工作流运行链接和成功记录时，不能声称远端 CI 已通过。具体执行结果以 `TEST_EVIDENCE.md` 为准。

## 包身份与签名

包名 `cn.zhundian.app`，首版 `0.1.0` / versionCode `1`。工作流把完整构建 SHA、APK SHA-256 和 `apksigner verify --print-certs` 的证书信息写入 artifact 的 `BUILD.txt`。本地构建可用 `-PbuildSha="$(git rev-parse HEAD)"` 固定应用内构建标识。

当前使用本机自动生成的 debug 密钥，私钥不入库、不上传 artifacts。GitHub 临时 runner 的 debug 密钥可能每次变化：覆盖安装要求签名一致，否则需先备份数据再卸载旧版。后续持续分发应单独配置稳定签名，通过仓库 Secrets 注入，当前并未预设或声称已配置该密钥。

## 模拟器 smoke 检查（可选，但单独记录）

在支持虚拟化的机器优先使用硬件加速；无 `/dev/kvm` 时可尝试软件模拟，启动可能很慢。

```bash
sdkmanager 'emulator' 'system-images;android-35;default;x86_64'
printf 'no\n' | avdmanager create avd --name Zhundian_API_35 --package 'system-images;android-35;default;x86_64' --device pixel_2
"$ANDROID_HOME/emulator/emulator" -avd Zhundian_API_35 -no-window -no-audio -no-boot-anim -no-snapshot -accel off -gpu swiftshader
# 另一个终端确认系统就绪后才安装/测试：
adb shell getprop sys.boot_completed
./gradlew :app:connectedDebugAndroidTest
```

`adb devices` 出现序列号不代表系统已启动；必须确认 `sys.boot_completed=1`，并检查 instrumentation 的测试数量和结果。AOSP 镜像不含 Google Play Services，适合验证应用无 Google 服务依赖。模拟器的运动数据不能证明实际步行识别准确，也不能证明真机锁屏、Doze 或免提电话可靠。

手动运行 GitHub 工作流时，可勾选 `instrumentation`。额外任务在隔离的 Ubuntu runner 上检查 KVM，启动 API35 AOSP 模拟器并运行同一组原生调度和 Compose smoke 测试，上传 `native-api35-<SHA>` 报告与设备日志。默认不启用该任务。`scripts/run-device-tests.sh` 要求系统已经启动，明确撤销 CALL_PHONE，并在失败时仍保留日志；本地使用时先 `source scripts/env.sh`，再传入与 APK 一致的 `-PbuildSha=<SHA>`。已创建工作流不代表远端测试已执行，执行证据仍以 `TEST_EVIDENCE.md` 为准。
