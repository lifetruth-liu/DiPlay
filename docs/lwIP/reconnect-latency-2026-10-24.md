# USERSPACE CarPlay USB 重连时延分析

日期：2026-10-24。测试对象：API 26 arm64 Android 主机与真实 iPhone，显式选择 `USERSPACE`。ADB 通过 Wi-Fi 连接，用于采集阶段日志；本次不是无 ADB 验收。

## 结论

本次详细测量中，控制面约 0.5 秒便完成 iAP2 身份识别、MFi 认证、订阅及首次 `StartCarPlaySession`。随后 iPhone 的 NCM bulk-OUT 端点约 20.75 秒仍未接受写入；链路开放后，应用 2.4 秒后再次发送 `StartCarPlaySession`，再过 1.195 秒 AirPlay 会话活跃。这个时序高度吻合旧 CarPlay 会话未正常结束时，iPhone 暂时对 NCM 返回 NAK 的已知行为；不是 lwIP 核心启动耗时。

重连还叠加了独立的约 10 秒本地等待：控制器请求 USB 配置重枚举，但没有观察到 USB detach/attach，超时后继续复用已提供的配置。该等待发生在本地网络附着之前。

## 详细测量

以第二次重连的“本地 NCM/network attached”为 `+0`：

| 相对时间 | 事件 | 与前一阶段的间隔 |
| ---: | --- | ---: |
| +0.000 s | USERSPACE NCM 网络附着 | — |
| +0.001 s | iAP2 控制循环开始 | 0.001 s |
| +0.110 s | iAP2 身份识别接受 | 0.109 s |
| +0.277 s | 首次 NCM bulk-OUT 未就绪 | 0.167 s |
| +0.405 s | MFi 认证接受 | 0.128 s |
| +0.423 s | 电源状态与订阅消息已发送 | 0.018 s |
| +0.504 s | 收到 CarPlay availability | 0.081 s |
| +0.512 s | 首次 `StartCarPlaySession` 已发送 | 0.008 s |
| +21.261 s | NCM bulk-OUT 首次成功写入；此前 56 帧未就绪 | 从首次未就绪起 20.984 s；从首次 `StartCarPlaySession` 起 20.749 s |
| +23.661 s | 再次 `StartCarPlaySession` 已发送 | 首次 NCM 成功写入后 2.400 s |
| +24.856 s | AirPlay 会话活跃 | 再次启动后 1.195 s |

从控制器本轮 attempt 开始到 AirPlay 活跃为 35.775 s：其中 USB transition 请求在 10.033 s 后超时；本地 NCM/network 在 attempt 开始后 10.919 s 附着；附着到首个 NCM 写成功为 21.261 s；NCM 首次写成功到 AirPlay 活跃为 3.595 s。

此前一次短时重连也看到相同趋势：NCM/network 附着至首个写成功 18.363 s，首个 NAK 至成功写入 17.990 s；成功写入至 AirPlay 活跃 3.291 s。该次记录显示就绪前有 46 个未就绪帧。两次 peer 等待分别约 20.75 s 与 18.00 s，均在源代码记录的 15–26 秒范围内。

## 时间线解释

1. 控制器先完成 iAP2 / MFi 启动流程。详细一轮中，身份识别、MFi 认证、订阅、CarPlay availability 与首次启动消息都在本地 NCM 附着后约 0.51 秒内完成，因此这些步骤没有消耗后续约 21 秒的主要等待。
2. iPhone 收到首次 `StartCarPlaySession` 后，仍约 20.75 秒不接受主机发出的 NCM bulk-OUT。代码在未就绪时每次最多等待 20 ms，然后返回 `NotReady` 并继续；累计 56 次未就绪，不是单个 USB 调用阻塞 20 秒。
3. NCM 首次写成功后，`WiredStartAgain` 的策略等待 `QUIET_MILLIS=2000`，并在控制循环中按最多约 500 ms 的周期检查是否应重发。实测第二次启动消息在 2.400 秒后发出；AirPlay 随后 1.195 秒活跃。数值符合该策略。实时日志捕获了第二次启动事件，因此此轮这一点已直接确认。
4. 本轮首次 USB transition 没有造成重枚举。控制器在固定 10 秒后再次检查，复用当前 USB 配置。状态选择基于 `deviceName` 和 `WiredSessionMemory`；相同主机端口可能复用设备节点名，因此旧会话记录可能让物理重连被识别为同一枚举。该路径解释额外 10 秒，但不解释 iPhone NCM 的约 21 秒 NAK。

来源：`WiredStartAgain.kt` 记录旧 CarPlay 会话中断后 NCM 延迟开放的行为；`NcmUsbBridge.kt` 定义未就绪的 20 ms 写超时及首个成功写入标记；`CarPlayController.kt` 编排 USB transition、控制会话及 NCM ready 后的重发。

## 脱敏事件日志摘录

以下是本次 Wi-Fi ADB 实时过滤器输出的事件标签和原始 epoch 时间戳。保留阶段顺序、相对耗时和未就绪计数；过滤器没有输出完整 PID/TID、USB 节点、序列号、MAC/IP/IPv6、控制器 UUID 或 iPhone `PHONE` 系统日志，因此这不是完整 `logcat` dump。

```text
# teardown 与后续重连
1792829263.989 phase=CONTROL teardown begin
1792829264.006 phase=CONTROL teardown CSM true 5ms
1792829264.012 phase=CONTROL teardown USBMUX true 5ms
1792829264.013 phase=CONTROL teardown VPN/NCM true 0ms
1792829264.074 phase=CONTROL teardown MFi true 0ms
1792829264.084 phase=CONTROL teardown end elapsedMs=94
1792829279.114 phase=IDLE attempt start
1792829279.139 phase=MFI stage=DiscoveringMfi
1792829279.152 phase=USB_DISCOVERY stage=MfiReady
1792829279.156 phase=USB_DISCOVERY stage=DiscoveringIphone
1792829279.163 USB permission pregranted
1792829279.165 USB permission granted
1792829279.167 phase=USB_DISCOVERY USB config action=request-transition
1792829279.168 phase=REENUMERATION USB transition requested
1792829279.173 phase=REENUMERATION stage=SelectingConfiguration
1792829279.178 phase=REENUMERATION stage=WaitingForReenumeration
1792829289.201 phase=REENUMERATION USB transition timeout 10000ms
1792829289.217 USB permission pregranted
1792829289.222 USB permission granted
1792829289.227 phase=REENUMERATION USB config action=reuse-descriptors
1792829289.233 phase=DATAPATHS stage=SelectingConfiguration
1792829289.239 phase=DATAPATHS stage=OpeningDataPaths
1792829289.242 phase=DATAPATHS USB config select ok=false
1792829289.287 USBMUX opened
1792829289.290 phase=CONTROL stage=Pairing
1792829289.301 saved pairing record used
1792829289.301 phase=CONTROL stage=ConnectingControl

# 本地网络附着与 iAP2 控制流程
1792829289.978 iAP2 CSM opened
1792829290.001 phase=CONTROL stage=AttachingNetwork
1792829290.033 local NCM/network attached
1792829290.034 iAP2 control loop start
1792829290.037 phase=CONTROL stage=RunningControl
1792829290.143 iAP2 identification accepted
1792829290.310 NCM bulk-OUT first NAK/not-ready
1792829290.438 MFi authentication accepted
1792829290.456 power/subscriptions sent
1792829290.537 CarPlay availability received
1792829290.538 CarPlay availability decoded
1792829290.545 FIRST StartCarPlaySession sent

# NCM ready、按策略重发、AirPlay active
1792829311.294 NCM bulk-OUT accepted a write
1792829311.294 phase=CONTROL NCM first successful write; priorNotReady=56
1792829313.694 SECOND StartCarPlaySession sent
1792829314.889 AirPlay session active
```

## 限制

- 测量基于两次短时重连，不代表重连循环、8 小时长稳或其他 iPhone/Android 组合。
- ADB 仍通过 Wi-Fi 使用；不满足“无 ADB”验收。
- 没有保存原始 iPhone 系统日志或原始网络报文；也未测 VPN 禁用基线、电话或完整设备矩阵。
- 本记录可证明阶段耗时和当次重发时序，不能证明所有 iPhone 型号/系统版本都具有相同 NAK 时长。
