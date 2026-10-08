# 实际测试与交付证据

日期：2026-10-08（Asia/Shanghai）。APK对应源代码提交：`f51650eadeb6b51c1264f69aa61ae59836317862`。之后的提交只补充交付资料、设备测试脚本和可选云端模拟器工作流。

## 自动测试与构建

实际执行：

```bash
source scripts/env.sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# 最终源代码补充了恢复/实例隔离回归后执行受影响的检查：
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PbuildSha=f51650eadeb6b51c1264f69aa61ae59836317862
```

| 检查 | 实际结果 |
|---|---|
| 核心JVM测试 | 94通过，0失败/错误/跳过 |
| Android/Robolectric（API35模拟Android运行库） | 5通过，0失败/错误/跳过 |
| lintDebug | 0错误，13警告 |
| assembleDebug | 成功，生成可签名校验的APK |
| assembleDebugAndroidTest | 成功，生成instrumentation APK |

核心测试包括会话31、fake电话网关11、运动回放21、日程24、赶车3、备份4。Android单元测试覆盖Room恢复任务/计数/电话资格、导入关闭电话且不补过去闹钟、无效导入不部分修改数据、提交不确定后继续本地提醒、旧通知不能中止另一实例。

lint警告为固定依赖存在更新、Compose命名/注解建议、kapt改用KSP建议及备用未使用文案；未关闭lint，也未把错误改成警告。系统备份/设备迁移警告已经通过显式排除规则修复。Robolectric不是实体设备或系统模拟器运行证据。

安装工具链的TLS和发布方摘要校验均保留。首次编译发现的Kotlin跨模块智能转换、测试API用法和集成问题已修复并重跑相应检查。

## 系统模拟器：已实际执行

运行环境：**AOSP Android 9 / API28 / x86_64**，无Google Play Services。宿主无`/dev/kvm`，使用软件模拟。应用保持`minSdk26 / targetSdk36 / compileSdk36`，未为测试降低targetSdk。

```bash
source scripts/env.sh
export ANDROID_SERIAL=emulator-5556
adb shell getprop sys.boot_completed  # 实际返回1后才继续
scripts/run-device-tests.sh -PbuildSha=f51650eadeb6b51c1264f69aa61ae59836317862
```

实际安装应用和instrumentation APK，测试前撤销`CALL_PHONE`。4项instrumentation全部通过，0失败/错误/跳过；runner XML记录347.821秒：

1. 真实`AlarmManager.setAlarmClock`注册、收到广播、创建持久化会话、紧急停止保存为`ABORTED`。
2. 中档任务点击1、2后重建Activity：网格、目标3和未完成状态保留。
3. 普通档注册120秒稍后，并实际重新进入响铃。设备单调时钟测得**125104毫秒，即125.104秒**，相对设定晚约5.104秒。未修改时钟或缩短应用计时；不能据此承诺120.000秒精度。
4. Compose日历、编辑和设置/权限页面导航可用。

所有电话测试通过fake网关；设备测试没有真实拨号、短信或联系人通知。模拟器运动数据不证明真实步行识别准确。

测试框架清理安装后，又直接安装了交付APK；设备内`base.apk`的SHA-256与交付文件完全一致。软件模拟器一次冷启动遇到System UI无响应叠层，等待系统恢复后实际看到完整日历页面，保留了该诊断截图，未把叠层图当成功界面。实际应用截图为[API28日历](evidence/calendar-api28.png)，它是原生运行截图，不是界面示意图。

报告：`app/build/reports/androidTests/connected/debug/index.html`，XML在`app/build/outputs/androidTest-results/connected/debug/`。交付复制件位于`artifacts/reports/instrumentation/`；完整设备日志在`artifacts/device/`，搜索`ZhundianEvidence`可找到实测稍后间隔。

## APK与签名

- 文件：`artifacts/zhundian-debug.apk`；标准Gradle输出仍在`app/build/outputs/apk/debug/app-debug.apk`。
- 包名：`cn.zhundian.app`；版本：`0.1.0 (1)`。
- APK SHA-256：`08cb5c422d6130320cf6ecf15f2e09bf70ab9cb5b3e8a71955c1cb8267545956`。
- debug证书SHA-256：`9b33b214ec284bba1b4357ce5da27b84bfe34c6b9c070cb26301fb33f0b26d05`。
- `apksigner verify --print-certs`通过；打包清单确认没有`INTERNET`权限。未提交或输出签名私钥。

签名仅适用于当前环境；覆盖安装需要相同证书。云端临时debug签名可能变化，不能假定可直接覆盖此APK。完整构建身份见`artifacts/BUILD.txt`。

## GitHub Actions与Release：已实际执行

用户后续明确要求将APK放入Release。已发布[v0.1.0调试预览版](https://github.com/xianyu1406/test/releases/tag/v0.1.0)，仓库保持原可见性，未合并主分支。

- 首次可读取的远端运行`37754169015`失败在`setup-java`：Temurin完整版本标识应为`21.0.9+10.0.LTS`。修复匹配字符串后，本地`actionlint 1.7.12`、YAML与shell检查通过。
- 实际执行`gh workflow run android.yml --repo xianyu1406/test --ref feat/zhundian-android -f publish_release=true -f instrumentation=false`。
- [运行37757411241](https://github.com/xianyu1406/test/actions/runs/37757411241)已完成，`debug`与`release`两个job均为`success`；测试、lint、APK构建、报告上传及Release发布步骤均通过。可选API35系统模拟器job明确为`skipped`，不计为通过。
- 标签与构建提交：`ab9d7156cb63aa9ed0c5e11784cc6f14d1ee135c`。该提交仅修改CI和说明，应用功能源码与之前本地验证版本相同；BuildConfig中的构建SHA及debug签名不同。
- 附件`zhundian-debug.apk`为20,035,970字节，SHA-256为`971c309bdd3e50c27e9db1794bd20100ef49a0a867105a56e40f855a84a6e059`。
- Release debug证书SHA-256为`ac383ef4539d027b33bb274d4364c4192f05a65fcee34a252a330cca02dada2e`。附件`BUILD.txt`同时保存摘要和证书。
- 已实际通过公开下载链接下载APK及BUILD.txt，摘要与GitHub资产`digest`一致；`apksigner verify --print-certs`通过，`aapt dump badging`确认包名、版本及SDK为`cn.zhundian.app / 0.1.0(1) / min26 / target36`。
- 已在隔离API28软件模拟器卸载原测试签名版本、安装Release附件并撤销`CALL_PHONE`。冷启动的`am start -W`首次等待超时，UI dump两次返回空根节点；稍后确认Activity处于resumed状态，截图实际显示日历首页。[实际Release运行截图](evidence/release-v0.1.0-api28.png)。这是额外安装/可见界面检查，未宣称UI dump或重新执行4项instrumentation通过，也没有真实拨号。

工作流默认push/PR只构建。只有指定仓库任务分支的手动`publish_release=true`才可发布；发布job具有单独的`contents:write`权限，下载同一次构建的artifact并校验摘要和提交，拒绝覆盖已发布的v0.1.0。下载副本与校验记录在`artifacts/release-v0.1.0/`。这是云端重新构建的附件，不是原本地APK的逐字节复制，不能直接假定可覆盖原本地安装。

## 未通过或未执行的边界

- **API35系统模拟器未完成验证**：软件模拟首次启动与重试出现`SystemServer/NetworkStack`看门狗重启；没有应用测试通过记录。日志保存在`artifacts/reports/emulator-api35-*.log`。这是模拟器启动问题，不是已证实的应用崩溃。没有宣称忽略的硬件超时参数已生效。
- 当前云环境直接向`uploads.github.com`上传遇到代理403/凭据401；通过仓库内GitHub Actions的原生令牌完成了用户授权的发布，没有要求用户提供新令牌。
- 工作流额外提供手动选择的API35/KVM instrumentation任务；其存在不构成远端运行证据。
- **实体手机全部未验证**：真实步行/摇晃/静止和持机位置、锁屏/Doze/厂商省电、重启/权限撤销、实际音频振动、SIM/双SIM、电话送达/观察/免提。Android14+前台服务与全屏权限需额外现代设备验证。验收模板见`PLATFORM_LIMITATIONS.md`。
