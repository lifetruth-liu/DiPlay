# USERSPACE USB CarPlay 断链与重连实测分析（2026-10-24）

## 结论

本次真机复现确认，“正在打开 CarPlay”期间的主要等待发生在 iPhone 的 NCM bulk-OUT 端点：DiPlay 已完成 iAP2 身份识别、MFi 认证、NCM 网络附着并发送首次 CarPlayStartSession，但 iPhone 暂时没有接收主机发出的 NCM 帧。该轮端点约 17.984 秒后首次接受写入，此前累计 51 次写入未就绪。它不是 lwIP 初始化阻塞，也不是一次持续 18 秒的 USB 调用。

等待窗口与代码中记录的旧 CarPlay 会话清理现象及 15–26 秒 NCM NAK 范围吻合。DiPlay 的 WiredStartAgain 在链路打开后等待约 2 秒，再发一次 StartCarPlaySession；本轮重发后约 1.09 秒 AirPlay session active。之前的分析记录为 56 帧、20.749 秒；本轮 51 帧、17.984 秒，数量不同但时间特征一致。

本轮用户看到的总等待还叠加了独立的 USB 配置重枚举等待：App 因本枚举已有会话记忆而请求配置切换，10 秒没有观察到 detach/attach，随后复用原配置。故“首次 Start 到 NCM 首次写成功”的约 18–21 秒，与其前面的约 10 秒 USB transition 是两段不同等待。

## 测试环境与操作

- Android Host：Xiaomi Mi 5s Plus，Android 8.0 / API 26 / arm64；DiPlay 0.2.21-hud-test。
- iPhone：iPhone14,7，iOS 27.0.1（由设备会话日志记录）。
- 实际传输：USB 有线、USERSPACE NCM/lwIP；Wi-Fi ADB 只用于远程操作和采日志。
- 19:15:27.355 在 DiPlay 点“通过 USB 连接”；先看到“正在打开 CarPlay…”，随后进入 CarPlay 地图。
- CarPlay 活跃时现场拔掉 iPhone USB 线；等待设备侧识别断开后再插回。重插后 App 再次进入 CarPlay 地图。

## 首次连接的精确时间线

时间为 Android logcat epoch 对应的本地时间（UTC+08:00）；阶段和帧数来自未过滤的原始 logcat。

| 本地时间 | 事件 | 说明 |
| --- | --- | --- |
| 19:15:27.355 | 点“通过 USB 连接” | 操作记录 |
| 19:15:29.966 | controller attempt 2 开始 | 有线启动 |
| 19:15:30.039 | MFi ready | 启动开销很短 |
| 19:15:30.068–30.084 | 配置 ID 6 已可用，但请求 USB transition 并等待重枚举 | 标记 previousSessionOnThisEnumeration=true |
| 19:15:40.112 | transition 等待 10,000 ms 超时 | 没有观察到 detach/attach |
| 19:15:40.155–40.174 | 复用配置描述符；选择目标配置 6 返回 false，before=6、detached=none、after=6 | 当前配置本来就是 6，随后继续打开数据通道 |
| 19:15:41.131–41.137 | AirPlay listener ready、NCM/USERSPACE 网络附着、进入 RunningControl | lwIP/listener 已启动 |
| 19:15:41.246 | iAP2 IdentificationAccepted | 控制面正常 |
| 19:15:41.561 | MFi authentication accepted | 认证正常 |
| 19:15:41.651 | 首次 carplay-start-session 发出 | 计时起点 |
| 19:15:41.676 | 首次 NCM bulk-OUT not ready | iPhone 尚未接收 NCM 写入 |
| 19:15:59.660 | NCM bulk-OUT 首次写入成功 | 此前 51 帧未就绪；首个 NAK 后 17.984 秒，首次 Start 后 18.009 秒 |
| 19:16:01.806 | 第二次 carplay-start-session 发出 | NCM ready 后 2.146 秒 |
| 19:16:02.896 | AirPlay session active | 第二次 Start 后 1.090 秒；首次 Start 后 21.245 秒 |

日志还显示 AirPlay TCP 在第二次 Start 后立即接入，随后 RTSP 配对/SETUP 完成。19:15:56.089 的截图仍显示“正在打开 CarPlay…”，19:16:20.291 的截图显示地图已运行；内部 active 事件发生在两张截图之间。

## 为什么会有约 20 秒 NAK

1. **iAP2 与网络服务很快就绪。** 从 NCM 网络附着、listener ready 到首次 Start 只过了约 0.52 秒；因此后面的等待不在 MFi、iAP2 或 lwIP 启动路径。
2. **USB 网络端点由 iPhone 决定何时开始接收。** NcmUsbBridge 在首次成功写入前把 bulkTransfer 的单次超时限制为 20 ms。未就绪时该次帧记为 NotReady 并丢弃，网络/控制线程继续运行；它不会在一个 USB 调用里卡住 18–21 秒。
3. **等待发生在对端状态上。** 本轮第一次 NAK 很快出现在 Start 之后，17.984 秒后同一 bulk-OUT 通道自动接受帧。WiredStartAgain 的源码注释也记录：旧 CarPlay 会话未完全结束时，iPhone 可能在 Start 后 15–26 秒持续 NAK NCM；iPhone 最初尝试 AirPlay 的网络包会因链路未开而丢失。
4. **链路打开后需要一次新启动请求。** 本轮既有实现等约 2 秒确认 NCM 已打开且尚无 AirPlay 连接，再发送一次 Start；AirPlay 随即 active。这个行为与实际时序吻合，现有证据不支持把 NAK 时间归因于 lwIP。
5. **UI 只呈现总的启动中状态。** 控制面已经 RunningControl，但 AirPlay session 尚未 active 时，页面继续显示“正在打开 CarPlay…”。目前用户看不到是在等 USB 枚举、iPhone NCM 端点，还是 AirPlay 握手，所以感受上像 App 一直没有进展。

对 iPhone 内部会话清理的归因属于高可信解释，而不是 USB 抓包直接证明：本轮证据直接证明了 NAK→bulk-OUT 接收的时间窗；没有保存原始 USB/NCM pcap，也无法直接观察 iOS 内部会话状态。此前同日测量与本轮都落在 15–26 秒范围内，增加了该解释的可信度。

## 物理断链与重连观察

拔线操作发生在 CarPlay active 之后。随后 Wi-Fi ADB 连接也短暂掉线。最初的 USB 轮询脚本把 ADB 命令失败当成 USB Host 已 detach；原始文件现已核对出 19:17:25 的输出是 protocol fault，后续输出是 device not found。因此这些 DETACHED 行和操作记录中当时写的“detach confirmed”不能作为物理拔线的精确时间证据。

可确认的 App 侧事件是：拔线操作附近，旧 attempt 的 USBMUX READ 仍持续超时；19:17:59.596 首次记录 READ FAILED，19:17:59.658 状态进入 Failed，19:18:01.673 开始 teardown，19:18:01.728 teardown 完成，19:18:02.037 自动进入 attempt 3 / WaitingForIphone。因物理拔线时刻没有被可靠采到，无法精确计算 detach 到失败的时长。

19:18:30.975 ADB 恢复后截到“正在连接 iPhone…”。随后完整 dumpsys USB 于 19:22:47.890 观察到 Apple iPhone 重新枚举，设备节点为 /dev/bus/usb/001/006；19:23:16.957 的截图已显示 CarPlay 地图。重连成功已确认，attach 到 active 的精确耗时不作结论。

这仍表明物理断链后控制读没有立即失败，而是经历了多次约 10 秒 READ timeout 才结束旧 attempt；断线状态反馈值得单独改善，但当前证据不足以报告精确的物理拔线到检测耗时。
## 修改建议（本次未修改代码）

**P1：把等待阶段说清楚。** 将笼统的“正在打开 CarPlay…”拆成 USB 重枚举、iAP2/MFi、等待 iPhone 打开 NCM、AirPlay 握手等阶段。NCM 尚未 ready 时显示已等待时长或“iPhone 正在释放上一会话”，并保留取消/重试入口；不要把它伪装成 lwIP 正在启动。

**P1：减少无效的 10 秒配置等待。** 本轮目标配置已经是 6，transition 没有产生 detach，超时后仍用配置 6。建议核查 WiredConfigurationAction 的触发条件：当当前活动配置已是目标 CarPlay/NCM 配置且没有真实配置切换需求时，能否直接打开数据通道；只有需要切换模式的设备才等 detach/attach。需在不同 iPhone/iOS 与 Android USB Host 实现上验证后再定策略，避免跳过必要的配置切换。

**P2：让物理拔线立即取消本地会话。** 本次旧 attempt 在拔线操作附近持续报告 USBMUX READ timeout，直到 19:17:59.596 才失败；由于 ADB 同时掉线，物理 detach 的精确时间未采到，不能给出准确检测时长。建议将 Android USB_DEVICE_DETACHED 事件与当前 controller/UsbDevice 枚举关联，立即取消挂起读写、关闭会话资源并更新 UI，再进入等待重插状态。需要覆盖 detach 与 bulkTransfer/UsbRequest 并发的竞态。

**P2：保留现有 Start 重发时序，增加可观测性。** 当前“端点第一次接受写入 + 安静 2 秒 + 无 AirPlay connect 时重发一次”的逻辑本轮有效。建议保留，并把首次 Start、首次 NAK、首个成功写、重发、TCP connect、AirPlay active 这些时间点稳定写入诊断报告。不要通过缩短单次 20 ms 超时或每隔很短时间重复发送 Start 来试图消除对端等待；本轮证据显示等待来自 iPhone 端点状态。

## 证据边界

- 本轮一台 Android Host、一台 iPhone，且 USERSPACE/NCM；不能外推到所有型号、系统版本或 VPN 后端。
- 前一份 56 帧/20.749 秒与本轮 51 帧/17.984 秒都能复现长 NAK，但仍应补多轮重连与不同 iPhone/iOS 组合。
- 物理断开/重连阶段 Wi-Fi ADB 曾短暂报告 device not found；之后重新连接并保存了设备 logcat ring buffer、USB 快照和操作时间。完整原始文件在证据目录；由于 logcat 是环形缓冲区且无线 ADB 有短暂中断，不能把它描述为从头到尾零间隙的一条流。
- 本次没有改动任何源码，也没有运行 Gradle CI；工作只包含真机操作、日志/截图采集和分析文档。

## 原始证据

见 [采集说明与文件索引](reconnect-latency-2026-10-24-live/capture-notes.md) 及同目录中的完整原始 logcat、dumpsys 快照、ADB 操作记录、USB detach/attach 记录和界面截图。旧基线见 [此前的重连时延分析](reconnect-latency-2026-10-24.md)。


