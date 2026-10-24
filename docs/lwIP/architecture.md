# 架构与实现约束

## 1. 现有代码事实

以下路径均相对于仓库根目录。

| 位置 | 当前职责 | 本方案影响 |
| --- | --- | --- |
| `shared/src/main/java/com/shilapi/xcertplay/network/CarPlayVpnService.kt` | 有线 TUN、IPv6 bridge、AirPlay TCP 监听、无线 attach、会话集合 | 抽出公共监听/会话宿主；VPN 服务仅持有 VPN 资源 |
| `shared/src/main/java/com/shilapi/xcertplay/network/Ipv6NcmBridge.kt` | Ethernet/IPv6 与 TUN 间搬运、MAC 学习及部分 NDP 报文适配 | VPN 后端继续使用；用户态不经过此桥 |
| `shared/src/main/java/com/shilapi/xcertplay/transport/NcmUsbBridge.kt` | USB NTB16 组装拆解、持久异步读请求、超时和关闭 | 保留 USB 所有权与请求完成机制，新增帧接口适配 |
| `shared/src/main/java/com/shilapi/xcertplay/transport/EthernetIpv6Codec.kt` | Ethernet/IPv6 编解码工具 | 可复用验证与测试样本；不能替代 lwIP 的 NDP |
| `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt` | VPN bind/attach、NCM 生命周期、iAP2 会话启动、端口通告 | 持有后端会话句柄，以实际 bound port 通告 |
| `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` | 有线权限、VPN 授权、启动门控 | 先选后端，再请求该后端所需权限 |
| `common/src/main/java/com/shilapi/xcertplay/VpnPreConsent.kt` | 已批准 ADB 下的 VPN 预授权 | 仅保留在原 VPN 路径，不作为用户态前置条件 |
| `shared/src/main/java/com/shilapi/xcertplay/network/TcpLiveness.kt` | 基于系统 Socket/FD 的 TCP 健康配置 | 转为后端能力；不能操作 lwIP 句柄对应的“假 FD” |
| `shared/build.gradle`、`shared/src/main/jni/Android.mk` | API 24、NDK 28.2.13676358、四 ABI、ndk-build | 新增 native 模块，沿用现有构建体系 |

有线路径当前通过 NCM 接收 Ethernet，IPv6 经 TUN 交给内核；取消 TUN 后不会自动存在系统可绑定的 link-local 地址。因此不能只删除 VPN 授权或把监听地址改为 `::`。

## 2. 通道审计清单

P0 必须确认每项是否出现在有线会话、建立方向、地址族、端口分配和协议通告字段。

| 文件（`shared/src/main/java/com/shilapi/xcertplay/` 下） | 审计内容 |
| --- | --- |
| `airplay/AirPlaySession.kt` | 主控制 TCP、事件 TCP、UDP keepalive、local/peer 地址传播 |
| `airplay/ScreenStream.kt` | 视频 TCP 动态监听及 accept 取消 |
| `airplay/AudioStream.kt` | UDP 音频 data/control 的端口、来源与回复 |
| `airplay/BufferedAudioStream.kt` | 缓冲音频 TCP、初始化时分配资源的时机 |
| `airplay/NtpClock.kt` | 双向 UDP 时间同步、单调时钟与发送时刻 |
| `airplay/IapTunnel.kt` | iAP tunnel TCP、双地址监听的目的和适用路径 |
| `airplay/VideoSettingsChannel.kt` | 设置 TCP 动态端口和发送串行化 |
| `media/MicrophoneUplink.kt` | UDP 上行地址、端口、打包与录音线程边界 |
| `network/CarPlayBonjour.kt` | 发现、公告、探测只在何种路径使用；JmDNS 对系统网卡的依赖 |
| `network/AirPlayPortSelector.kt`、`airplay/AirPlayTcpAccepted.kt` | 端口冲突重试、真实对端信息、内部连接判断 |

同时检索 `java.net`、`ParcelFileDescriptor.fromSocket`、`NetworkInterface`、`android.system.Os`，追踪有线可达调用链。ADB、无线发现、远程 MFi 等系统网络保持显式系统后端；不能机械地替换项目所有 Socket。

## 3. 模块边界

新增代码建议集中在 `shared/src/main/java/com/shilapi/xcertplay/network/backend/`：

| 文件/目录 | 责任 |
| --- | --- |
| `SessionNetwork.kt` | 会话网络入口、监听/连接/UDP 工厂、能力查询 |
| `NetworkEndpoint.kt` | 地址字节、端口、会话链路标识；提供受控诊断摘要 |
| `TcpStream.kt` | 流读写、半关闭、关闭、超时合同 |
| `TcpListener.kt` | accept、实际端口、取消 |
| `UdpEndpoint.kt` | 数据报发送接收、来源元数据、截断行为 |
| `BackendFailure.kt` | 结构化失败分类和是否允许启动回退 |
| `system/` | Java Socket 包装，不改变无线网络选择和现有 VPN scope |
| `lwip/` | JNI 会话、端点桥接、缓冲池句柄与 native 错误映射 |

公共宿主建议新增 `network/AirPlaySessionHost.kt`，管理监听和会话集合；普通 Android 服务 `network/CarPlaySessionService.kt` 持有它，并延续现有控制器的生命周期要求。`CarPlayVpnService` 保留为 VPN 附件的所有者，不能成为无线和用户态必经的服务。服务绑定、通知和前后台行为须在 P1 逐项迁移，避免大规模改写控制器。

native 建议目录：

```text
shared/src/main/jni/lwip_backend/
  Android.mk                # 被现有 Android.mk 引入
  CMakeLists.txt            # 仅供宿主 harness/测试
  config/lwipopts.h         # 配置项及资源预算解释
  include/diplay_lwip.h     # 小型 C ABI、错误码、所有权合同
  src/                     # 核心循环、netif、端点、JNI 适配
  tests/                   # 不依赖 Android 的 native 测试
  third_party/lwip/         # 固定版本源码与原许可证
  UPSTREAM.md               # 版本、来源、校验、补丁及升级步骤
```

宿主目标不编译 JNI/Android 依赖；Android 与宿主共用网络核心和配置，平台差异用窄适配层承载。不要在原生协议栈中混入 CarPlay 加密、媒体解析或 USB descriptor 解析。

## 4. 接口合同

具体 Kotlin 签名在 P1 随契约测试冻结。接口必须满足以下语义，禁止通过继承 Java Socket 或全局 Socket 工厂隐藏实现差异。

| 操作 | 必须保证 |
| --- | --- |
| TCP listen/accept | `port=0` 返回实际端口；关闭唤醒 accept；只交付本会话连接 |
| TCP read | 正数表示数据，EOF 为 `-1`，不以零字节自旋；本地取消与对端 EOF 可区分 |
| TCP write | 公共流接口写完指定范围或抛错；底层短写由适配器处理；不保证对端已经消费 |
| TCP 并发 | 每条连接允许一个读者和一个写者；多写者由业务或接口串行化，禁止静默交错 |
| 超时 | 使用单调时钟和绝对 deadline；`0` 明确为不设超时；负值拒绝；关闭总能取消阻塞 |
| 半关闭 | `shutdownOutput` 在已接收发送数据后发送 FIN；本地完整关闭为有界操作 |
| UDP | 一个 send 对应一个数据报；接收返回实际来源和原始长度；超长报文报告截断，不伪装完整包 |
| 地址 | 使用协议端点，不依赖系统 scope index；发送到其他会话链路的端点失败 |
| 健康检测 | 内核保活配置与 lwIP 保活分别实现；不把合法静止画面误判为网络超时 |
| close | 幂等；释放后的句柄不能再被访问；旧回调只能释放自己的资源 |

早期可提供 `InputStream`/`OutputStream` 适配以复用协议解析，但端点地址、超时、取消和数据报元信息仍由新接口管理。业务不得获取或依赖 native 指针/FD。

## 5. lwIP 配置与协议策略

- 以 IPv6 Ethernet netif 接入：NCM 帧进入 `ethernet_input` 对应路径，由 lwIP 处理 ICMPv6、NDP、TCP/UDP。
- MAC、主机 link-local 和 iAP2 公告地址保持一致；静态地址配置、DAD 时序和地址冲突行为在 P2 以真实报文确认，不能为了加快启动无条件禁用 DAD。
- 接入收发必须处理 NCM 在 StartCarPlaySession 前尚未 ready 的现象；就绪判断区分“监听已准备”和“iPhone 已接收 USB 帧”，避免形成启动依赖环。
- MTU 来源按 USB/NCM 协商能力和现有值审计；IPv6 最小 MTU 要求不能被随意缩小。测试 ICMPv6 Packet Too Big 和大于 MTU 的流量。
- 扩展头、IPv6 重组、MLD 和组播过滤采用明确支持表；保留必要能力，拒绝非法包，重组限制数量、字节和超时。
- 首期关闭不使用的路由/NAT/PPP/DHCP 功能。IPv4 和 DNS 不因“可能有用”默认启用，也不在缺少证据时强制关闭。
- 若有线通道确实需要 mDNS，必须增加用户态 UDP 的公告/发现适配；JmDNS 不能直接绑定虚拟网卡。此依赖应在 P0 决定并计入 P4。
- 上游版本必须选择正式发布 tag，核对来源、维护状态、漏洞和许可证；记录 SHA 与校验值。本文不指定未经仓库验证的“最新版本”。

## 6. 线程、内存与背压

一个会话对应一个串行 lwIP 核心执行上下文。首选 raw API 和 `NO_SYS=1` 的显式核心循环，所有栈调用和超时处理均在此线程执行；若 P2 证明必须改用 tcpip_thread，需在 ADR 中说明，禁止两种模型混用。

USB IN、USB OUT 和业务读写通过有界队列与核心线程交换数据。USB 阻塞操作、Java 回调、文件写入、媒体解码、日志格式化不在核心回调中运行。核心循环由输入事件、命令或最近定时器 deadline 唤醒，不忙轮询。

| 缓冲 | 所有权与释放时机 |
| --- | --- |
| NCM 接收帧 | 初版沿用现有拥有型 ByteArray；交给 native 时允许一次受控复制 |
| native 输入 pbuf | 成功转交协议栈后由协议栈管理；失败立即释放 |
| TCP 接收数据 | 仅在业务已消费或有预算的缓冲安全接管后更新接收窗口；保留未消费数据时遵守 raw API 所有权 |
| TCP 发送数据 | 初版使用栈内复制；禁止在重传结束前释放无复制发送缓冲 |
| UDP 接收 | 拥有型数据报队列有上限；溢出计数，实时媒体优先丢过期包 |
| DirectByteBuffer | 池化；租约明确；Java 关闭后 native 不得继续访问；不依赖 GC finalizer 做正常回收 |

TCP 缓冲满时施加窗口背压，不能任意丢弃已交付到应用流的字节。UDP 按通道限制排队时间，控制类报文溢出必须计数并上报。NCM TX 控制报文可优先于大批媒体数据，但单条 TCP 流字节顺序不得改变，不能通过无限优先级饿死其他流。

现有 `NcmUsbBridge.send()` 在链路尚未 ready 时可能丢帧并返回；不能假装成“已上网线”。P2 必须提供内部发送结果适配，区分 sent/not-ready/failed，记录未发送计数；对 TCP/NDP 依赖协议重试，对 UDP 不隐式重发过期媒体。

## 7. 生命周期与错误边界

状态：`NEW → PREPARING → LISTENING → LINK_ACTIVE → STOPPING → STOPPED`；任意运行阶段失败进入 `FAILED`，随后统一清理至 `STOPPED`。LISTENING 只表示虚拟监听端口已就绪，不代表完整 CarPlay 已可用。

控制器统一持有 NCM 资源所有权。VPN bridge 现有关闭 NCM 的行为必须纳入适配，保证一次有效关闭，避免两个后端竞争读同一条链路。

关闭顺序：

1. 标记 generation 失效，拒绝新命令与新 accept。
2. 唤醒业务等待者，结束媒体上行/会话读写。
3. 在核心线程注销回调并关闭/中止端点，清理待处理命令和定时器。
4. 取消 USB 请求、停止帧投递，按所有权关闭 NCM。
5. join 工作线程并归还缓冲；回调完全不可达后销毁 native context。

每一步有 deadline，不能在核心线程 join 自己；超时仍需保留安全的所有权，禁止强制 free 仍被线程使用的对象。不能结束的线程记录为失败并阻止复用该 context，不能悄悄成功或无限创建替代线程。

错误至少区分：VPN_UNAVAILABLE、VPN_DENIED、NATIVE_UNAVAILABLE、BACKEND_INIT、USB_DETACHED、USB_IO、ADDRESS_CONFLICT、RESOURCE_LIMIT、PEER_CLOSED、PROTOCOL、CANCELLED、INTERNAL。日志不通过字符串匹配决定重试。

## 8. 选型依据

- lwIP 提供 IPv6/TCP/UDP 和轻量 raw API，许可证及线程规则参见 [官方概览](https://www.nongnu.org/lwip/) 与 [raw API 文档](https://www.nongnu.org/lwip/2_0_x/raw_api.html)。实际配置以固定版本源码与文档为准。
- 不自行维护 TCP 拥塞控制、重传与乱序算法。
- 不默认使用回环代理：它会增加一层端口/地址映射、数据拷贝和取消传播，尤其不利于 UDP 与虚拟 peer 地址的语义。
- 不承诺优于内核性能；性能门槛以 [性能计划](performance.md) 的可重复测量为准。

## 9. 主要风险及处理

| 风险 | 尽早验证 | 处理与停止条件 |
| --- | --- | --- |
| iPhone 固件使用未审计通道/IPv4/mDNS | P0 通道审计与不同 iOS 抓包 | 补齐协议集和测试后继续；不以单个版本成功声明普遍兼容 |
| USB ready 与监听就绪互相等待 | P2 重放启动时序、未 ready 注入 | 分离 LISTENING 与 LINK_ACTIVE；时序不明确不接入生产选择 |
| JNI/native 内存安全 | P2/P3 sanitizer、CheckJNI、失效句柄测试 | 先修复再做性能优化；UAF/越界为发布阻塞 |
| 视频挤压实时音频/控制 | P2/P6 混合负载、队列延迟 | 限制批处理和队列，预留调度；不满足低配预算保持实验 |
| 资源关闭与 USB 复用冲突 | P1/P3 生命周期和 generation 测试 | 明确唯一所有者，旧回调隔离；不安全对象禁止复用 |
| 部分厂商禁用 USB Host 或杀后台 | P0/P6 真机矩阵 | 清晰说明边界，沿用已验证生命周期策略；不宣传用户态能绕过全部限制 |
| 网络栈升级导致隐性性能/协议回归 | P7 固定版本、变更记录和全矩阵 | 升级独立 PR，可回退匹配的 Kotlin/native 版本 |
