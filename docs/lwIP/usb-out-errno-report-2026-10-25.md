# USB OUT 底层错误码与 20ms / 100ms 对照

代码清理状态：按用户要求已删除 NATIVE_20 / NATIVE_100 开关、原生 OUT 诊断路径和诊断库，恢复 Java 默认写入流程。本报告及数据描述清理前的实验 APK。

后续已完成 [重复 SET_CONFIGURATION 与 config 6 复用的 ABBA×3 对照](usb-configuration-abba-report-2026-10-25.md)：跳过调用仍有 down/up 和约 7 秒的 up→OUT；A 组配置调用全部返回 false，不能将其视为设备成功执行过重复配置。

## 结论

这次把 Java 的 `NotReady` 进一步定位到了同步 USB 系统调用的 **errno 110 / ETIMEDOUT**。正式对照 12 次连接中，首次成功之前共有 535 次失败写入，全部为该错误；加上原生路径核对的两次连接，共 641 次，仍无其他 errno。未观测到 EPIPE、ENODEV、EPROTO 等错误。

**100ms 没有消除长等待，也没有观察到总体提速。** Start→首次 OUT 成功的中位数：20ms 为 17.134 秒，100ms 为 17.456 秒；controller→active 中位数分别为 21.568 秒和 21.870 秒。100ms 最长仍等待 22.096 秒才首次成功。相邻配对三组更快、三组更慢，不能把小幅中位数差值解释为确定的性能差异。

可以排除本轮失败主要表现为 STALL 的说法，也不支持“把 20ms 放宽到 100ms 就能解决”的假设。但仍不能证明线上持续 NAK，或确认 iPhone 正在清理旧会话。ETIMEDOUT 只说明同步传输在期限内未完成、主机控制器没有向该调用报告其他错误；设备端点状态、主机事务调度等更深层原因仍需事务级证据。

默认保留 Java 20ms、LEGACY。诊断参数不持久化；结束时已恢复默认并成功连接。

## 当前设备能获取什么

- MI 5s Plus，Android 8.0 / API 26，Linux 3.18.71，user 构建，`ro.debuggable=0`；实际后端 USERSPACE。
- ADB shell 为 uid 2000，没有可用的 `su` / `strace` 命令；`run-as` 应用可用。
- `dmesg` 的 `/dev/kmsg`、usbmon 路径不可读；未取得 USB 包或 URB 轨迹。
- ftrace 可以列出部分 xHCI / DWC3 事件，但 xHCI 事件启用文件不可写；`current_tracer=nop`、`tracing_on=0`，相关事件也未启用。没有更改 root、SELinux 或跟踪配置。
- 起初 USB Host 列表为空；用户重新准备连接后已枚举到 iPhone，随后才安装和采样。没有把无设备阶段算入超时实验。

## 如何取得 errno

Android 8 的 [JNI bulk_request](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/jni/android_hardware_UsbDeviceConnection.cpp) 使用 `GetPrimitiveArrayCritical`，调用 [libusbhost 的 usb_device_bulk_transfer](https://android.googlesource.com/platform/system/core/+/android-8.0.0_r1/libusbhost/usbhost.c)，最终执行 `ioctl(fd, USBDEVFS_BULK, ...)`。返回 Java 的整数不携带 errno。

新增 `NcmOutExperiment` 与 `usb_out_diagnostic.c`：仅 debug Intent 可选择 `NATIVE_20` / `NATIVE_100`。沿用 critical-array 与同步 ioctl，立即保存 errno，不重试 syscall，不改变 Sent / NotReady / Failed 分类。只替换首次完整写成功之前的 OUT 路径，之后恢复 Java 写入。调用方给出更短的超时仍优先。

为防止关闭连接时 fd 被复用，在 bridge 状态锁内复制 fd，实际 ioctl 在锁外执行，finally 关闭副本；接收线程不会被整个写调用锁住。库加载和 fd 复制在写调用计时之前完成。记录的 duration 包括 JNI 调用与副本释放，并非 USB 总线级时间。错误计数覆盖所有原生启动写入，日志仅采样首次几次、新错误种类、每秒及首次成功，不记录地址或载荷。

错误含义依据 [Linux USB Error codes](https://www.kernel.org/doc/html/latest/driver-api/usb/error-codes.html)。这不是通过 Java `-1` 猜 errno，也不是通过耗时推断 STALL/NAK。

## 对照方法

同一个 APK，安装 SHA256 与构建一致：`a812d36d47f2683898d3fd78547108aaf44bea8903823afb7f16caa67c839d6c`。

1. 安装首次连接单列：attempt 1，24.954 秒。
2. 实现路径核对：Java 20ms、native 20ms、native 20ms、Java 20ms。四次均成功，Start→OUT 为 16.553、20.888、17.877、19.190 秒。复现了同类长等待，但小样本不证明两个实现统计等价，不纳入超时组。
3. 正式实验：A=native 20ms，B=native 100ms，ABBA 重复三组，各六次。唯一配置差异为启动写超时。
4. 最后恢复 DEFAULT / LEGACY：attempt 18，20.481 秒进入 active。

所有轮次均核对实验参数和 LEGACY 策略；同一 USERSPACE 后端、默认关闭策略、2 秒 Start 静默窗口、协议栈恢复方式、USB 配置复用方式保持一致。UI 断开命令返回后固定等 1 秒，ADB 命令开销另计。未触发模式切换，未使用 PROBE。计时为 controller→active，不代表点击→首帧。

## 正式实验结果

各阶段独立取中位数，不能相加当作总时长。

| 指标 | 20ms | 100ms |
| --- | ---: | ---: |
| 成功次数 | 6/6 | 6/6 |
| controller→首次 Start | 1.117 秒 | 1.124 秒 |
| Start→首次 OUT 成功 | **17.134 秒** | **17.456 秒** |
| Start→OUT 范围 | 11.077–21.556 秒 | 10.886–22.096 秒 |
| OUT→DAD preferred | 1.994 秒 | 1.981 秒 |
| OUT→重发 Start | 2.225 秒 | 2.170 秒 |
| TCP→active | 0.878 秒 | 0.866 秒 |
| controller→active | **21.568 秒** | **21.870 秒** |
| 总耗时范围 | 15.890–25.773 秒 | 15.404–26.319 秒 |
| 失败写入总数 / ETIMEDOUT 数 | 263 / 263 | 272 / 272 |
| 首次成功前失败次数中位数 | 44.5 | 46.5 |
| 首次成功时累计写调用耗时中位数 | 0.785 秒 | 4.545 秒 |
| 每轮单次最大写耗时的中位数 | 24ms | 101ms |

累计写耗时包含 Start 前调用及首次成功调用，不能直接从 Start→OUT 中相减得到严格空闲时间。100ms 累计调用时间约为 20ms 的 5.8 倍，这是调用占用/等待时间，不是 CPU 时间或功耗测量。

相邻两轮构成六组 AB/BA 配对，100ms−20ms 的 Start→OUT 差值为 −8.411 至 +9.424 秒，中位数 +1.056 秒；总耗时差值为 −8.346 至 +8.868 秒，中位数 +0.863 秒。两项都是三组改善、三组变慢。样本波动远大于两组总体中位数约 0.3 秒的差异。

所有 DAD preferred 均晚于实际 OUT 成功，长超时没有绕过本地地址验证。18 次采样均成功，采集错误为 0；正式组之外的六次不计入上述组统计。

## 如何理解剩余等待

1. 失败已经具体到同步 USB 传输超时，不能再把本轮 Java 负返回值笼统视为未知种类，也不应按 STALL 去盲目 clear-halt。
2. 将单次等待放宽五倍仍持续超时，说明至少 20→100ms 这个范围不能解决主要瓶颈；更长、异步持续排队等方案未测试，不能一概排除。
3. 长等待之后仍有约 2 秒的 DAD / Start 重发窗口和约 0.9 秒的会话握手，这些本地阶段与此前报告一致，但不是主要十几秒来源。
4. 旧会话释放、iPhone 数据通道启用、主机 USB 调度仍是待区分的候选。下一步应优先取得可用主机的 USB 事务轨迹或硬件分析仪证据，并以正常结束会话、物理重插或另一主机做受控对照；本轮未执行这些新实验。

## 验证与证据

新增测试先观察缺失接口导致的编译 RED，之后覆盖 debug 门控、非法参数回退、超时上限、errno 与有符号返回值解码、首次成功后恢复 Java、并发关闭时 fd 副本释放及拒绝晚到成功、dup 失败传播。独立审查发现的 fd 竞态已修正并复查。

完整 CI 前两轮有既有热点测试的调度依赖失败：它要求工作线程在 50ms 预算内进入 action，而 FutureTask 在运行前被超时取消也符合产品行为。仅调整该测试的断言，保留 abort 与总时间上限检查，没有改热点产品代码。首轮还发生 Lint/Kotlin 分析器内部异常；最终冻结代码后完整重跑通过，没有禁用检查。

最终执行 AGENTS.md 的全部九项任务，3m44s 成功：250 tasks，45 executed / 205 up-to-date。shared 1912、common 1646，共 3558 项单测无失败/错误；home 无测试源，三个应用 lint / assemble 全部通过。现有 lwIP 四 ABI 校验通过，新诊断库也核对包含四 ABI；真机成功加载并产生 errno。没有声称运行 Linux sanitizer 或新的 Windows native host suite。

脱敏逐轮数据见 [usb-out-errno-samples-2026-10-25.json](usb-out-errno-samples-2026-10-25.json)。原始日志、设备能力快照、采集/分析脚本、CI 日志保留于本机 `%TEMP%/diplay-usb-out-20261025/`，未将 USB 序列号、ADB 公钥或协议载荷提交到仓库。未卸载、清数据或改变后端偏好；恢复默认连接后未继续主动重连。

## 正式实验逐轮数据

| attempt | timeout | Start to OUT (s) | OUT to DAD (s) | total (s) | ETIMEDOUT count |
| --- | --- | ---: | ---: | ---: | ---: |
| 6 | 20ms | 21.556 | 1.989 | 25.773 | 54 |
| 7 | 100ms | 15.778 | 1.991 | 19.995 | 39 |
| 8 | 100ms | 22.096 | 1.767 | 26.319 | 63 |
| 9 | 20ms | 16.268 | 1.998 | 20.553 | 41 |
| 10 | 20ms | 11.077 | 1.993 | 15.890 | 27 |
| 11 | 100ms | 20.501 | 1.988 | 24.758 | 51 |
| 12 | 100ms | 17.591 | 1.979 | 22.174 | 46 |
| 13 | 20ms | 18.000 | 1.995 | 22.582 | 48 |
| 14 | 20ms | 19.297 | 1.995 | 23.750 | 54 |
| 15 | 100ms | 10.886 | 1.982 | 15.404 | 26 |
| 16 | 100ms | 17.321 | 1.925 | 21.565 | 47 |
| 17 | 20ms | 14.800 | 1.982 | 19.432 | 39 |
