# 当前交付状态

任务分支：`feat/zhundian-android`。最终APK源代码提交为`f51650eadeb6b51c1264f69aa61ae59836317862`；之后提交为交付资料、设备测试脚本和可选云端测试配置。运行`git rev-parse HEAD`查看当前完整交付提交。没有合并主分支或发布Release。

## 已完成并有证据

原生Android日历/编辑/重复/赶车节点/备份；三档提醒纯Kotlin状态机；真实闹钟、传感器、音频振动、权限与本机拨号适配；Room/DataStore；串行会话队列和持久化一次电话资格；完整Gradle Wrapper和GitHub Actions。

- 核心94项 + Android/Robolectric5项通过，无失败或跳过。
- lint0错误13警告，APK及instrumentation APK构建通过。
- API28 AOSP软件模拟器实际安装/运行，4项instrumentation通过，无失败或跳过。
- 原生120秒稍后实测125.104秒，未宣称真机准点或Doze可靠。
- APK：`artifacts/zhundian-debug.apk`；身份/摘要/证书：`artifacts/BUILD.txt`。

## 剩余验证与真实阻塞

API35软件模拟器启动看门狗循环；现代系统后台/全屏路径需要API35 KVM或实体设备验证。API28通过不能替代这些证据。GitHub API被环境代理403阻断，远端CI结果未核验；已保存`api.github.com`网络配置草稿，需在环境设置保存并发布后重试。实体手机运动、锁屏、Doze、电话与免提均未验证，没有真实自动拨号。

## 接续

```bash
cd /workspace/test
source scripts/env.sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PbuildSha="$(git rev-parse HEAD)"
# 仅隔离测试设备；进程与ADB连接不保证跨云任务保留
export ANDROID_SERIAL=emulator-5556
adb shell getprop sys.boot_completed
scripts/run-device-tests.sh -PbuildSha="$(git rev-parse HEAD)"
# api.github.com访问恢复后，核验实际远端CI：
gh run list --repo xianyu1406/test --branch feat/zhundian-android
```

沿用现有检出，不创建额外worktree。工具链在`/workspace/android-toolchain`。从当前代码、`docs/TEST_EVIDENCE.md`与对应报告接续，不重做已完成实现；只为新改动或未解决问题重复必要检查。所有自动电话测试必须保持fake网关或明确禁用真实呼叫。
