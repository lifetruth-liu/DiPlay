# 有线通道审计

审计基线：`143f4674`，2026-10-21。下表来自源码调用链，**不是设备抓包结论**。
路径相对于 `shared/src/main/java/com/shilapi/xcertplay/`。

| 通道 / 位置 | 方向、地址和端口 | 通告、所有者及内核依赖 | 验证 |
| --- | --- | --- | --- |
| 主控制 `network/CarPlayVpnService.kt` | iPhone → TCP listener；有线 scoped link-local IPv6；首选 config.port，冲突重选 | `CarPlayController.runStack` 用 `boundPort()` 写入 `Iap2WiredCarPlayEndpoint`；service 关闭；ServerSocket、NetworkInterface、TUN scope | T01、C01 |
| 事件 `airplay/AirPlaySession.kt` | iPhone → TCP；`::`、临时端口 | SETUP `eventPort`；session 关闭；系统 Socket 与加密流 | C02、T04 |
| 低功耗保活 `airplay/AirPlaySession.kt` | iPhone → UDP；`::`、临时端口 | SETUP `keepAlivePort`，仅协商需要时开启；session 关闭 | C02、T06 |
| 视频 `airplay/ScreenStream.kt` | iPhone → TCP；`::`、临时端口 | stream SETUP `dataPort`；ScreenStream 关闭 listener/accepted socket | C03、T04 |
| 音频 `airplay/AudioStream.kt` | iPhone → UDP data/control；`::`、两个临时端口 | stream SETUP `dataPort` / `controlPort`；AudioStream 关闭 | C04、T06 |
| 缓冲音频 `airplay/BufferedAudioStream.kt` | iPhone → TCP；wildcard、构造时分配临时端口 | stream SETUP `dataPort`；BufferedAudioStream 关闭；需迁移提前分配语义 | C04、T05 |
| 时间 `airplay/NtpClock.kt` | 双向 UDP；`::`、临时端口；远端 timingPort | SETUP `timingPort`；NtpClock 关闭；InetSocketAddress 来源和单调时间 | C05、T06 |
| 麦克风 `media/MicrophoneUplink.kt` | Android → UDP；向 config.host/config.port 发送 | 对端协商的媒体配置；MicrophoneUplink 关闭；录音线程不得运行栈核心 | C05、T06 |
| iAP 数据流 `airplay/IapTunnel.kt` | iPhone → TCP；IPv4 wildcard 或传入 IPv6，另尝试同端口另一地址族 | stream SETUP `dataPort`；IapTunnel 关闭；双栈兼容不能凭假设删去 | C06、T01 |
| 视频设置 `airplay/VideoSettingsChannel.kt` | iPhone → TCP；临时端口 | stream SETUP `dataPort`；channel 关闭；发送需串行化 | C07、T07 |
| 发现 `network/CarPlayBonjour.kt` | 无线 mDNS、TCP probe；系统网络地址 | 控制器无线启动分支创建 JmDNS；不在当前 `runStack` 有线启动调用链；CarPlayBonjour 关闭 | C08、无线回归 |

主连接 `AirPlaySession.peerAddress` 从真实 Socket 传播到 timing、媒体和控制通道；
`AirPlayTcpAccepted.isInternalAirPlayPeer` 根据 peer/local 判断内部无线探测。
这些身份必须保留为协议端点，不能以 loopback 代理地址替代。
`TcpLiveness` 对主连接用 ParcelFileDescriptor / android.system.Os 配置保活；
只有系统后端可以调用它，虚拟端点必须自行实现相同能力。

## 启动与关闭

`CarPlayController.runStack` 打开 iAP2 CSM 后调用 `attachVpn`，后者建立 TUN、启动
IPv6 bridge 并绑定 AirPlay listener，然后通告真实端口和 link-local 地址。
`NcmUsbBridge.send` 注释和分支说明 StartCarPlaySession 前 bulk OUT 可 NAK/超时；
因此 LISTENING 必须可以在 USB 可写前完成。现有 send 对未 ready 返回 Unit，
后续适配必须区分未发送与已发送，短写保留失败语义。
目前 bridge 接管 NCM，controller 在 attach 成功后放弃本地关闭所有权；抽取宿主时
必须显式交接，避免用户态与 VPN 两个 reader 消费同一 NCM。

## 未覆盖证据

源码表明有线启动不依赖 JmDNS，但不能证明所有 iOS 协商都不使用 mDNS 或 IPv4。
尚无脱敏 pcap、VPN 禁用设备、参考设备性能基线；G0 未通过。
首期不能据此删除 IPv4/双栈兼容或对外声明用户态可用。
后续需将 pcap 摘要、设备/iOS 与测试编号关联，原始媒体和密钥不入库。
