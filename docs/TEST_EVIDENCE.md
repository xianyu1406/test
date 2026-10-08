# 测试证据

测试记录日期：2026-10-08（用户时区 Asia/Shanghai）。以下仅记录实际结果；在本轮开发完成时更新APK与集成结果。

## 已实际执行

- `source scripts/env.sh && ./gradlew --no-daemon :core:test`：最新94项，失败0、错误0、跳过0。覆盖会话31、电话fake11、运动回放21、日程24、赶车3、备份4。
- 固定JDK/Gradle/SDK安装包通过TLS下载并校验官方摘要。四个平台适配器曾单独通过SDK36 Kotlin编译；这不等于整包构建或真机运行。
- GitHub仓库API只读检查 `gh api repos/xianyu1406/test`：代理返回Forbidden，尚无可核验的远端CI结果。

## 当前集成验证

完整命令：

```bash
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

该命令已经实际成功（BUILD SUCCESSFUL）。Android/Robolectric 3项通过，验证Room会话/任务/电话资格持久化、导入禁用电话且不注册过去闹钟、无效导入参数不会部分修改数据。app-debug.apk与instrumentation APK已生成。lint首次结果为0错误14警告，包含固定依赖版本、命名/注解、kapt建议、备用文案和数据迁移规则；已随后加入显式禁止系统备份/设备迁移规则，最终复查结果将在交付时追加。

API35 AOSP x86_64软件模拟器已尝试启动，宿主没有/dev/kvm；首次启动期间出现SystemServer/NetworkStack看门狗重启，不能记为模拟器安装或测试通过。正在独立尝试恢复；真实执行结果单列于最终记录。

## 未取得的证据

实体手机所有验收项尚未执行；没有拨打真实号码，没有发送短信或通知联系人。运动回放通过只说明算法/状态机对输入数据有预期响应。模拟器结果即使通过，也不能外推真实步行识别、真机锁屏可靠性、蜂窝通话送达、免提或对方接听。
