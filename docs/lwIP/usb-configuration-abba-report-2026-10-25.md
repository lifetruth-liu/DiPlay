# 重复 SET_CONFIGURATION 与 config 6 复用对照

代码清理状态：按用户要求已删除 A/B 全部配置实验开关、额外配置读请求与实验日志，恢复原有配置选择流程。本报告及数据作为历史证据保留，以下实现说明描述的是采样当时的 APK。

## 结论

本轮没有观察到跳过配置调用能消除 NCM down/up 或首次 OUT 长等待。ABBA×3 共 12 次，A/B 各 6 次，全部成功进入 active。Start→首个完整 OUT 成功的中位数为 A 16.900 秒、B 17.412 秒；controller→active 为 22.638、22.631 秒。B 没有稳定改善，不能把总时间中位数相差 7ms 解释为提速。

两组都捕获到 5/6 次 down、6/6 次 up；up→OUT 中位数为 7.125、7.040 秒。**跳过 SET_CONFIGURATION 后，该状态变化和 up 后继续超时仍然存在。**

一个关键限制是：A 的六次 `UsbDeviceConnection.setConfiguration()` 全部返回 false，前后 GET_CONFIGURATION 均为 6。B 则确认已是 6，完全省略这次调用。因此本轮验证的是“重复尝试配置的现有路径”与“省略该失败调用”，没有构造出“设备成功执行重复配置”这一条件，也没有 USB 事务轨迹证明请求抵达 iPhone。不能据此认定成功的重复配置无副作用。

正式组首次成功前共有 508 次 OUT 失败（A 245、B 263），全部 errno 110 / ETIMEDOUT。没有其他 errno，12 次原生路径均记录到一次完整写成功。

## 实现与控制变量

- `UsbConfigurationExperiment`：DEFAULT、REPEAT、SKIP_IF_CURRENT，仅 debug Intent 启用，不持久化；Activity 和 USB host 都有 debug 门控。
- `UsbConfigurationSelection.selectForExperiment`：A/B 都执行并记录前后 GET_CONFIGURATION；B 仅在明确读得目标配置时跳过 SET_CONFIGURATION。未知或不同配置继续使用现有选择、分离驱动、重开流程。DEFAULT 直接委托原流程，不增加读请求。
- 配置日志记录单调时间、读取值、决定、SET 返回布尔值和调用耗时；NCM claim / SET_INTERFACE 日志记录起止、接口、重试次数和耗时。既有通知和传输日志提供 controller 的单调 elapsedMs。
- 所有正式样本前后配置都是 6，A 正好一次配置调用，B 零次；NCM 数据接口仍为 4/1，所有 SET_INTERFACE 均第一次成功。没有加入 alt=0、NTB 参数协商、packet filter 或新的就绪门控。
- 同一 APK、MI 5s Plus / Android 8 / API26、同一 iPhone，保持 USB 物理连接；实际后端 USERSPACE。统一 native 20ms、LEGACY、默认关闭、2000ms Start 静默；无 PROBE、无模式切换。

APK 构建与安装 SHA256 均为 `a59ac5aa356545bd2066bcaf31df51937f9f5a0a0f54bb9e40d21d0ef6a9ba43`，通过 `adb install -r` 覆盖安装并保留数据。

## 采样方法和异常

正式序列为 `(A B B A) × 3`，A=REPEAT、B=SKIP_IF_CURRENT。正常 active 后采集器等待 3 秒，再打开设置并按 XML 中真实的“断开连接”按钮操作；UI 和 ADB 另有开销。实际上一轮 active→teardown begin 为 6.654–8.457 秒。

确认 teardown end、executorTerminated=true，且没有资源 completed=false 后，force-stop 清除已断开的旧 Activity，再固定等待 1 秒启动新 Activity。实测 teardown end→新启动命令为 1.695–1.907 秒。每轮都是新进程 / controller；这避免 onNewIntent 保留旧 controller 参数，但也使本轮不同于先前保持进程的重连采样。controller 的 attempt 每次重置为 1，因此数据用 sample_id 区分轮次。不能把本轮与之前构建的组中位数直接当成单变量对照。

第一条安装后种子连接成功，32.529 秒进入 active。首次正式轮在启动连接之前，采集器因应用文件日志没有 teardown end 而停下；logcat 证明实际关闭在 153ms 内完成，资源全部 completed=true，并有 shutdown complete clean=true。没有因此产生一个失败的正式连接。原因是断开后文件日志未保留完整后半段，采集器改用过滤的 logcat 确认关闭，保留异常记录，再做第二条种子连接（18.946 秒）后开始完整正式序列。

共保留 15 条成功连接：2 条种子、12 条正式、1 条恢复；正式采集错误为 0，所有模式、配置、后端和策略核对通过。原始连接日志、停止时 logcat、采集器和 CI 日志仅保留本机临时目录；仓库样本不包含手机序列号、ADB 公钥、地址和协议载荷。

## 正式结果

下表各阶段独立取中位数，不能相加充当总时间；down→up 仅包含实际捕获到两种通知的 5 条样本。通知缺失不证明设备没有发生该变化。

| 指标 | A：REPEAT | B：SKIP_IF_CURRENT |
| --- | ---: | ---: |
| 成功 | 6/6 | 6/6 |
| SET_CONFIGURATION 调用 / 返回 true | 6 / 0 | 0 / 不适用 |
| down 通知 / up 通知 | 5/6 / 6/6 | 5/6 / 6/6 |
| controller→Start | 1.894s | 1.807s |
| Start→OUT | **16.900s** | **17.412s** |
| Start→OUT 范围 | 7.349–19.357s | 13.474–19.397s |
| Start→up | 9.846s | 9.912s |
| down→up | 7.809s | 7.297s |
| up→OUT | **7.125s** | **7.040s** |
| up→OUT 范围 | 6.384–8.044s | 6.356–7.612s |
| OUT→DAD preferred | 1.994s | 1.981s |
| controller→active | **22.638s** | **22.631s** |
| controller→active 范围 | 12.982–26.364s | 19.029–24.844s |

A 的失败配置调用耗时 2.528–3.959ms，中位数约 3.164ms。这是调用耗时，不等于设备执行请求的时间。B 确实减少了这次调用，但没有观察到秒级稳定收益。

相邻两轮形成六组 AB/BA 配对。B−A 的 Start→OUT 差值为 −5.031、+1.051、+1.102、+11.076、+0.040、−0.004 秒，中位数 +0.546 秒，2 组更快、4 组更慢。总时间差值中位数 −0.087 秒，3 组更快、3 组更慢；范围 −4.590 至 +10.675 秒。样本波动显著，不能以此证明小幅收益或损失。

| sample_id | 组 | Start→OUT(s) | Start→down(s) | Start→up(s) | up→OUT(s) | total(s) |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 3 | A | 18.505 | 5.336 | 12.121 | 6.384 | 23.619 |
| 4 | B | 13.474 | 未捕获 | 5.862 | 7.612 | 19.029 |
| 5 | B | 18.449 | 3.142 | 10.949 | 7.500 | 23.846 |
| 6 | A | 17.398 | 2.560 | 10.369 | 7.029 | 22.820 |
| 7 | A | 14.351 | −1.502 | 6.307 | 8.044 | 19.846 |
| 8 | B | 15.453 | 1.065 | 8.874 | 6.579 | 20.524 |
| 9 | B | 18.425 | 4.766 | 12.063 | 6.362 | 23.657 |
| 10 | A | 7.349 | 未捕获 | −0.604 | 7.953 | 12.982 |
| 11 | A | 19.357 | 4.376 | 12.186 | 7.171 | 26.364 |
| 12 | B | 19.397 | 5.745 | 13.041 | 6.356 | 24.844 |
| 13 | B | 16.398 | 1.567 | 8.863 | 7.535 | 21.605 |
| 14 | A | 16.402 | 2.542 | 9.323 | 7.079 | 22.456 |

负数表示通知早于首次 Start。正式组 12 次 availability 都为 true；B 全部在 Start 后 55–121ms 捕获到 SYN。A 有一条仅在 9.768 秒后捕获到首个 SYN，另外五条在 58–140ms 内。这些都不能代替 OUT 可写证据。

首个 OUT 时间取完整写成功后 `NCM bulk-out ready` 的诊断时间，包含返回和记录日志的开销，不是总线 ACK 时间。约 7 秒的 up→OUT 同样是通知观测到成功观测的差值，不能直接当作 iPhone 内部定时器常量。

## 对下一步的影响

本轮降低了“省略现有重复配置尝试就能解决长等待”的优先级。跳过请求仍有 down/up 和 up 后 ETIMEDOUT，重复调用不是出现该现象的必要条件。两组共同的 SET_INTERFACE、iPhone 数据端点恢复以及主机事务处理仍需区分。

下一步宜先补齐 GET_INTERFACE 的实际 alternate setting 和完整控制请求观测，再单独测试接口恢复顺序；不能将 alt=0→1、NTB 协商和通知门控一起加入。特别是网络 up 后仍超时，本轮继续不支持直接用通知作为 USB OUT 已就绪的充分条件。

## 验证与恢复

策略 / 接口缺失导致的编译 RED 已观察，随后五项新测试通过：debug 门控、A 重复调用、B 仅跳过当前配置、DEFAULT 无额外读请求、失败配置保留驱动分离和重开。既有配置测试及 Activity debug/release 传参检查通过。独立审查没有发现功能阻塞，指出的新 Intent 不能重建现有 controller 问题已在采样方法中处理。

运行 AGENTS.md 完整九项 Gradle CI 命令，附加 `--max-workers=2 --continue --console=plain`：8m37s 成功，250 tasks，50 executed / 200 up-to-date。shared 1917、common 1646，共 3563 项单测，零失败 / 错误；home 无测试源，三个应用 lint / assemble 通过。四 ABI lwIP/APK 校验通过，真机安装成功且 SHA256 与构建一致。未把实验策略提升为生产默认。

最后恢复 DEFAULT 配置策略、Java20、LEGACY，sample 15 在本地时间 **15:45:39.840** 记录 active，controller→active 为 19.781 秒。恢复后未继续主动重连。

脱敏数据：[usb-configuration-abba-samples-2026-10-25.json](usb-configuration-abba-samples-2026-10-25.json)。本机原始证据目录：`%TEMP%/diplay-config-abba-20261025/`；CI 为 `%TEMP%/diplay-config-ci.log`。此前 OUT 错误码实验见 [usb-out-errno-report-2026-10-25.md](usb-out-errno-report-2026-10-25.md)。
