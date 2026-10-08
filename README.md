# 准点

原生 Android 离线日历与分档提醒，面向容易忘事、拖延或半醒关闹钟的人。Kotlin、Compose Material 3、Room、DataStore；无需账号、Firebase、Google Play Services 或联网权限。

## 已实现的工作流

- 月/周日历、今日和日期详情；单次或每周多星期安排；重要标记与提醒强度独立。
- 明确时区、跨天时间、下三次实际提醒预览；修改本次、修改之后、跳过、删除和启用切换。
- 赶车模板从用户输入倒推起床、准备出门、最晚出门、到站四个独立节点，均可修改；不推测检票时间。
- 普通档：停止当前提醒或120秒稍后；中档：10数字任务后完成三个独立运动窗口；强档：20数字任务、持续运动复查、可选一次联系人本机电话兜底。
- 活动会话由前台服务、Room持久化与串行协调器管理。紧急停止记为中止，不记为验证成功；独立后续提醒保留。
- 真实 SensorManager、AlarmManager.setAlarmClock、闹钟音频/振动、可选中文TTS、TelecomManager.placeCall；无硬件、权限或数据时明确降级。
- 系统文件选择器导入/导出版本化 JSON，导入前预览，导入不会恢复电话授权或补触发过期提醒。

步行规则是**待真机校准的基线**。自动测试和模拟器不能证明真实步行准确、锁屏可靠或电话免提有效。当前实际结果见 [测试证据](docs/TEST_EVIDENCE.md)，接续工作见 [STATUS](STATUS.md)。

## 构建与安装

固定 JDK 21.0.9+10、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21；SDK `min26 / compile36 / target36`。完整 Gradle Wrapper 已提交。

在当前 Linux x86_64 云环境：

```bash
cd /workspace/test
python3 scripts/setup-cloud.py
source scripts/env.sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cn.zhundian.app/.MainActivity
```

已有 Android Studio 的电脑安装上述 JDK/SDK 后可直接执行同一 Gradle 命令。Windows 使用 `gradlew.bat`。详细环境、路径与模拟器命令见 [BUILDING](docs/BUILDING.md)。

APK 默认位置：`app/build/outputs/apk/debug/app-debug.apk`。包名 `cn.zhundian.app`，版本 `0.1.0 (1)`。构建提交、APK摘要和证书在交付目录 `artifacts/BUILD.txt`，不要把证书摘要当私钥。debug更新安装需要签名一致；GitHub临时runner的debug密钥可能变化，不能直接覆盖旧安装。私钥与密码不入库。

## 安装后配置

从“设置”查看精确闹钟、通知、锁屏全屏提醒和身体活动权限，按需授权后运行10秒测试提醒。测试提醒自动电话始终关闭。保存后明确显示“安排已保存”及成功调度数量；保存数据库不等于系统已接受闹钟。

联系人电话默认关闭。仅在实体手机明确填写普通联系人、确认用途、启用并授予所需权限后使用；无需读取通讯录。自动电话还需可用SIM/默认线路/无正在进行的通话。免提只是请求，提交电话不表示接通，更不表示已清醒。条件不足时等待手动拨打并继续本地提醒。紧急停止不能保证撤回已经提交给系统的电话。

不能识别可靠的电源键来源，因此不把系统熄屏、切换应用当成完成或稍后。普通档在本应用提醒界面按音量键可以稍后，也始终保留屏幕与通知操作。应用不绕过强制停止、关机、无电或厂商限制。首版未实现 Direct Boot：开机首次解锁前不支持提醒恢复。

## 云端构建和源码结构

GitHub Actions 在任务分支推送、PR 或手动触发时执行单元测试、lint和APK构建，上传APK及报告为工作流 artifacts；不发布 Release、不修改仓库可见性。下载：仓库 **Actions → Android build and checks → 对应运行 → Artifacts**。是否已在远端运行以测试证据为准。

- `core/`：可注入时间的纯Kotlin日程、会话、舒尔特、运动回放、电话资格与备份模型及测试。
- `app/.../runtime/`：串行协调器、前台服务、系统广播与通知。
- `app/.../platform/`：真实闹钟、传感器、音频、权限和本机电话接口。
- `app/.../data/`：Room快照/实例/记录与DataStore设置；`ui/`：中文Compose界面。

规则与决策：[产品规格](docs/PRODUCT_SPEC.md)；系统降级与真机验收表：[平台限制](docs/PLATFORM_LIMITATIONS.md)；运动算法：[基线说明](docs/MOTION_BASELINE.md)。
