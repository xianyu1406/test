# Android 平台能力与限制

本文件说明采用的 API、明确降级和仍需实体设备验证的行为。代码存在、状态机测试通过、系统接受注册、真实设备送达是不同证据层级。没有真机数据时不得宣称锁屏可靠、步行识别准确或免提成功。

## 调度、前台运行与锁屏

| 路径 | 实现选择与边界 |
|---|---|
| 准点闹钟 | `AlarmManager.setAlarmClock`，每个实例独立 `PendingIntent` URI。Android 12+ 检查 `canScheduleExactAlarms()`；没有授权则只保存安排并给设置入口，不能写“提醒已成功调度”。 |
| 普通稍后 | 120 秒后另设用户可见 alarm clock。未用系统不精确 repeating，也未依赖 `setExactAndAllowWhileIdle` 的短间隔保证。Doze 中实际间隔待真机测试。 |
| 活动会话 | 声明 `systemExempted` FGS 类型及权限，限已获精确闹钟授权、正在运行闹钟会话的用途。Android 14+ 按运行时资格启动；资格不足/被撤销需提示并进入故障降级。未伪装成健康、电话、无声媒体或短服务。 |
| 细粒度计时 | 前台会话使用单调时钟和有超时的部分唤醒锁。唤醒锁不等于绕过 Doze；系统冻结、采集断流、服务终止仍需恢复。 |
| 全屏提醒 | 请求高重要性闹钟通知与全屏 Intent。Android 14+ 检查全屏能力；没有全屏授权降级为系统允许的通知，不强行承诺自动弹页。Android 13+ 通知权限需用户授权。 |
| 恢复 | 监听开机、应用更新、时间/时区变化和精确闹钟授权变化，重新打开应用也重建未来提醒。已过期实例不批量补响、不补拨。 |
| 首次解锁前 | 首版未实现 Direct Boot。Room 使用凭据保护存储，重启恢复范围明确为**首次解锁后**。没有 `LOCKED_BOOT_COMPLETED` 或在未解锁时读取普通 Room 的路径。 |
| 强制停止/关机 | 普通应用无法保证强制停止、关机、没电后仍执行；再次打开后重建可恢复的未来调度。厂商省电可能影响服务和通知。 |
| 多实例 | 统一队列与各自持久化状态，只允许一个当前声音/电话会话；一个节点结束不清除后续节点。队列延迟和聚合提示仍需设备并发验收。 |

`SCHEDULE_EXACT_ALARM` 可由用户撤销；精确闹钟资格也关系到当前 `systemExempted` 服务选择。必须以使用时检查为准，设置页的一次快照不是永久授权。未采用设备管理员、无障碍、root、默认拨号器或阻止卸载/关机。

## 声音、运动和电话

- 闹钟流铃声渐强、振动和可选系统中文 TTS 由真实平台适配器执行；TTS 缺语言/引擎时保留铃声。音量上限受系统与勿扰实际行为约束，不声称绕过所有机型策略。音量键仅在提醒界面可识别的情况下做普通稍后；不拦截电源键。
- 全传感器模式使用 `TYPE_STEP_DETECTOR`/`TYPE_STEP_COUNTER`、加速度、重力/旋转向量及陀螺仪。Android 10+ 的身体活动权限按需申请。硬件缺失、权限丢失、注册失败或断流均为“暂时无法验证”，不是“不走路”。
- 较弱传感器方式需明确选择且标明能力；没有计步硬件不能假装通过完整多传感器验证。慢走、扶着手机、不同口袋、桌上静止、摇晃和车辆振动都可能影响基线，必须做真机校准。手机运动证据也不能证明人的清醒程度。
- 本机普通电话采用 `TelecomManager.placeCall(tel:, extras)`，检查 `CALL_PHONE`、`READ_PHONE_STATE`、本机电话能力、启用的蜂窝线路、默认线路与已有通话。不要求读取整本通讯录。`CALL_PHONE` 不被当作 `phoneCall` FGS 的资格。
- 无 SIM、双 SIM 默认线路不明确、权限撤销、已有通话或状态不可确定时，保留唯一请求机会并显示原因；提供 `ACTION_DIAL` 手动入口。“拨号界面已打开”不等于电话送达。平台拒绝实际提交后不自动循环重拨。
- `EXTRA_START_CALL_WITH_SPEAKERPHONE` 只是免提路由请求。普通应用不能保证接通、免提、锁屏自动呼叫，也不能把本地 TTS 注入普通蜂窝通话。`TelephonyCallback`/旧版 `PhoneStateListener` 的 `OFF_HOOK` 包含拨号过程，接通状态标记未知。
- 已领取并持久化资格、但提交前进程死亡，可能漏拨；本实现选择避免重复拨号，保留“提交不确定”和本地提醒。已向系统提交的电话不能承诺由紧急停止撤回。

## 隐私与备份

默认不联网、不全天采集、不保存原始传感器流，日志不应输出完整号码。JSON 备份可能包含地点和备注，应由用户选择存放位置。Android 自动系统备份已关闭，应用仅通过明确的系统文件选择操作迁移安排；不迁移活动会话、已领取的呼叫请求或自动开启联系人兜底。

## 验证边界

核心 JUnit 测试使用注入时钟/回放数据及 fake 电话接口，不能验证真实传感器、电话网络或免提。模拟器即便通过安装、启动和闹钟 smoke，也不构成真机锁屏/Doze/通话证明。当前云环境的模拟器与 KVM 能力、实际执行结果统一记录在 [TEST_EVIDENCE.md](TEST_EVIDENCE.md)，未运行的项目必须单列。

官方网页入口已尝试获取，当前环境部分 Android 文档请求返回 HTTP 403；不把未成功取得网页正文写成“已在线核实”。构建所用 SDK API 和实际编译/lint 结果作为可检验的实现证据，不能代替厂商设备测试。

## 真机验收记录模板

每轮填写：机型 `____`；Android/厂商版本 `____`；应用构建 SHA `____`；安装签名 `____`；提醒参数 `____`；检测模式/手机位置 `____`；测试日期 `____`。录屏、系统通知/闹钟截图和脱敏日志放在私有证据位置，完整号码不得进入仓库。

| 场景 | 需要观察的事实 | 当前结果 | 证据位置 |
|---|---|---|---|
| 自然走路/慢走 | 新片段持续时间、步数、窗口归属；不宣称准确率 | 待真机 | 待填写 |
| 手持/裤袋/外套口袋/扶着手机 | 各位置分别记录；陀螺仪不需大幅转动 | 待真机 | 待填写 |
| 翻身/拿起/零碎摇晃/剧烈摇晃 | 不应直接刷新无运动期限 | 待真机 | 待填写 |
| 静止刷牙/穿衣/桌面 | 未识别运动与数据故障分开；静止准备仅一次 | 待真机 | 待填写 |
| 车辆振动 | 记录误判和可解释拒绝原因 | 待真机 | 待填写 |
| 息屏与锁屏 | 普通、中档、强档通知/声音/期限；拒绝全屏后的通知降级 | 待真机 | 待填写 |
| 切换应用/旋转/返回 | 数字任务不重置，离开界面不完成，不因切换自动稍后 | 待真机 | 待填写 |
| Doze/厂商省电 | 实际闹钟与 120 秒稍后间隔；传感器断流进入故障 | 待真机 | 待填写 |
| 重启与首次解锁 | 解锁前不承诺；解锁后只重建未来提醒，旧会话不补拨 | 待真机 | 待填写 |
| 权限拒绝/中途撤销 | 通知、精确闹钟、身体活动、电话分别验证 | 待真机 | 待填写 |
| 音量/振动/TTS/勿扰 | 渐强、任务音量、静音限时、TTS 不可用仍有铃声 | 待真机 | 待填写 |
| 多个节点同到点 | 独立状态、单个声音/电话、完成起床不取消出门 | 待真机 | 待填写 |
| 无 SIM/双 SIM 未选默认/已有通话 | 不消耗未提交机会；手动拨号提示真实 | 待真机 | 待填写 |
| 明确启用后的真实电话与免提 | 只向知情同意联系人明确发起；记录 API 提交、接通/免提分别证据 | 待真机，自动测试不拨号 | 待填写 |
| 紧急停止与崩溃恢复 | 提交前停止可阻止电话；已领取请求不重拨；恢复需明确操作 | 待真机 | 待填写 |
| JSON 恢复 | 先预览、联系人关闭、过期提醒不补触发 | 待真机 | 待填写 |

相关官方依据：

- [精确闹钟](https://developer.android.com/develop/background-work/services/alarms)、[Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)、[Direct Boot](https://developer.android.com/privacy-and-security/direct-boot)
- [前台服务类型与资格](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [传感器概览](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)、[运动传感器](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)
- [时间敏感通知](https://developer.android.com/develop/ui/views/notifications/time-sensitive)、[Android 14 行为变化](https://developer.android.com/about/versions/14/behavior-changes-14)
- [TelecomManager.placeCall](https://developer.android.com/reference/android/telecom/TelecomManager#placeCall(android.net.Uri,android.os.Bundle))
