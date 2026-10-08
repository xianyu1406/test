# 当前交付状态

任务分支：`feat/zhundian-android`。本文件不记录会自引用变化的最终提交哈希；运行 `git rev-parse HEAD` 查看源码提交，APK构建提交另见 `artifacts/BUILD.txt`。

## 已完成

原生Android项目、完整Wrapper和固定工具链；日历/编辑/重复/赶车节点/备份界面；纯Kotlin三档状态机及真实Android平台适配；Room/DataStore持久化；串行队列与持久化一次呼叫资格；GitHub Actions构建定义。电话号码只存本机，不写入诊断日志。测试提醒强制禁用自动电话。

核心94项与Android/Robolectric3项测试通过；应用和instrumentation APK构建通过，lint0错误。API35软件模拟器出现启动看门狗循环，安装/启动/instrumentation证据正在另行获取。完整实际结果见 docs/TEST_EVIDENCE.md，不能把打包成功当作设备运行成功。

## 接续命令

```bash
cd /workspace/test
source scripts/env.sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# 仅已授权且系统启动完成的测试设备
adb shell getprop sys.boot_completed
./gradlew :app:connectedDebugAndroidTest
```

沿用已有检出，不创建额外worktree。独立代理已经完成分工；接续时从当前代码和最新构建日志定位失败，不重建项目。云工具链在 `/workspace/android-toolchain`，不是源码的一部分。

## 证据边界

真实设备步行、放置位置、锁屏、Doze、省电、音频振动、SIM/双SIM、免提及通话观察均需真机验收。所有自动电话测试使用fake gateway；自动化没有拨打真实号码。仓库可见性不变，不合并主分支，不创建公开Release。
