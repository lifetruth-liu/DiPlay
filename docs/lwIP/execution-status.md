# 实施记录

执行日期：2026-10-21 至 2026-10-22。工作分支：`codex/lwip-backend`。起点：`143f4674`。

## 当前交付

- P2 原型增量：已有 generation token JNI、native 有界输出环、SecureRandom 熵注入，
  `LwipPrototype.nativeEcho` 在 core worker 创建真实 TCP/UDP echo netif；NCM 帧端口
  配置独立接收限额。API 28 x86_64 模拟器的 5 项 CheckJNI 仪器测试通过，含实际
  IPv6/NDP、TCP/UDP 帧回显和运行器线程集成；同一任务AVD上独立内核客户端通过
  TAP及veth的TCP/UDP含分片往返。生产端点 JNI、实际 iPhone/NCM 互通、
  ARM 性能及 G0/G1/G2 仍未验收；用户确认目前没有设备或报告。

- P0.1：完成源码通道清单，见 channel-inventory.md；抓包与设备验证尚缺。
- P0.2：保存构建环境与设备基线缺口，见 baseline.md；记录 lwIP 2.2.1 官方标签、
  commit 和 ZIP 的 SHA-256，见 ADR 001 / native UPSTREAM.md；P2 原型已固定引入源码。
- P1.1：新增 SessionNetwork、协议端点、TCP/UDP 合同、结构化错误与系统适配基础。
  系统 TCP 用 NIO 进行可取消的带 deadline 读写；UDP 用独立 DatagramSocket，避免
  Android 7 DatagramChannel socket adaptor 的 send/receive 共用阻塞锁。
  同方向重叠调用明确失败（RESOURCE_LIMIT）；不同方向允许并发。
  每次创建系统会话都生成独立的 link identity；重用连接名称不能复用旧端点。
  UDP 限时发送通过有界的 deadline 任务取消；发送超时会关闭该端点，不可继续复用。
- 新增 10 个回环/确定性合同测试：真实端口及冲突、双向 TCP、FIN/半关闭、
  关闭取消、UDP 空包/截断/来源、端点身份与地址所有权、deadline 校验、
  写入背压与恢复、同方向重叠拒绝、UDP 并发收发。
- 独立代码审查发现并修正锁等待越过 deadline、空 UDP 发送结果歧义及 Android 7
  channel adaptor 双向阻塞问题；复审没有发现此基础交付的剩余阻塞问题。
- P1.2 宿主拆分：`AirPlaySessionHost` 接管主控制监听、端口回退、会话集合和
  双栈 accept；普通 `CarPlaySessionService` 持有宿主，无线不再绑定 VPN 服务。
  有线 VPN 注入公共宿主，保留 TUN、scope 查找、应用 allowlist 和 NCM bridge。
  关闭使用独立的控制器 Owner 和宿主 Lease，旧清理不能关闭替换监听。
  服务销毁拒绝迟到 attach；控制器关闭取消待完成绑定、拒绝迟到 binder，
  服务意外断开先报告失败再清理控制器。原 VPN 服务中的无线入口已删除。
  原 TCP accept / Bonjour 双栈回归迁至普通服务；协议仍是原系统 Socket。
- P1.2 时钟通道迁移：`NtpClock` 使用 `SessionNetwork` / `UdpEndpoint`，
  端口取自端点实际绑定结果，保留原 NTP 计算；请求和回复串行发送，截断包不解析。
  注入后端由会话所有者关闭；默认系统后端由时钟对象关闭，并从主连接保留 IPv6 scope。
  绑定失败允许重试，关闭为终止态，关闭与绑定互斥。当前主会话仍显式走系统兼容入口，
  尚未完成全会话网络注入。
- P1.2 协议迁移：公共宿主现在按 attachment 创建并拥有 `SessionNetwork`，
  控制会话借用网络，全部主控制、事件、保活、媒体、NTP 与麦克风端点显式注入。
  主连接真实 peer/local 元数据来自端点，不使用回环代理。
  旧 Socket 构造通过系统适配器 `adopt` 保留兼容；协议业务不直接调用系统收发。
  监听端口回退同时清理已成功的另一地址族，iAP2/SETUP 使用实际端口。
  系统 TCP 保活及 abortive close、UDP 音乐缓冲均作为后端操作保留。
  录音线程只调用端点发送，不运行栈核心。
  `EndpointOwner` 隔离借用网络的通道资源，关闭后拒绝迟到的 bind/accept 发布；
  iAP 重连结束后移除旧连接引用。主控制初始化失败同样走统一关闭路径。
  独立审查修正 IPv4 首选时丢失次要 IPv6 scope：系统入口从完整原始地址集合
  校验唯一非零 scope，再去重；跨接口地址在替换旧 attachment 前拒绝。
  注入网络不读取系统接口索引。当前仍仅启用系统后端。

## 运行证据

日志在执行主机临时目录；JUnit XML / HTML 由 Gradle 写入各模块 build 目录。

| 执行 | 结果 | 证据 |
| --- | --- | --- |
| 原始完整 CI 九项任务 | exit 0，BUILD SUCCESSFUL，1m50s | `%TEMP%/diplay-lwip-baseline.log` |
| 首次 SessionNetworkContractTest | exit 1，缺少接口和系统实现，符合红灯预期 | `%TEMP%/diplay-lwip-contract-red.log` |
| 初始 6 项合同 | exit 0 | `%TEMP%/diplay-lwip-contract-green.log` |
| 增加并发与背压后的 8 项 | exit 0 | `%TEMP%/diplay-lwip-contract-expanded.log` |
| 重叠写入回归（旧实现） | exit 1；第二写入等待锁，无法在测试期限内返回 | `%TEMP%/diplay-lwip-lock-red.log` |
| 第一次修改后完整 CI | exit 1；1653 个 shared 测试有 1 个失败；既有 MicrophoneNoAudioRestartTest 在 600ms 内观察到 2 次重启而非 3 次 | `%TEMP%/diplay-lwip-ci.log` |
| 10 项合同 + 4 项既有麦克风测试 | exit 0，14 项通过，13s | `%TEMP%/diplay-lwip-review2-green.log` |
| 修正并发/UDP 后串行完整 CI | exit 0，BUILD SUCCESSFUL，8m02s | `%TEMP%/diplay-lwip-ci-final.log` |
| 同名会话隔离回归（旧实现） | exit 1，同名会话身份相同，符合红灯预期 | `%TEMP%/diplay-lwip-identity-red.log` |
| 修正会话身份后的完整 CI | exit 0，BUILD SUCCESSFUL，7m02s；shared 1655、common 1575、home 6 项，0 失败、0 跳过 | `%TEMP%/diplay-lwip-ci-identity.log` |
| 公开树凭据检查 | exit 0 | `python scripts/check_public_tree.py` |
| 新宿主 / 普通服务首次合同 | exit 1，缺少 Host/Service 类型；实现后 exit 0 | `%TEMP%/diplay-lwip-host-red.log` / `diplay-lwip-host-green.log` |
| 关闭后迟到 host attach | exit 1；修正终止态后通过 | `%TEMP%/diplay-lwip-host-integration2.log` |
| 控制器服务绑定合同首次运行 | 13 项中 2 项失败：关闭未取消等待、意外断开未清理 | `%TEMP%/diplay-lwip-service-controller-red.log` |
| 普通服务 / 无线启动 / Bonjour 回归 | exit 0，13 项通过，9s | `%TEMP%/diplay-lwip-service-controller-final.log` |
| 销毁 VPN 服务后迟到 binder | exit 1，仍调用 establish；增加销毁检查后通过 | `%TEMP%/diplay-lwip-vpn-destroyed-red.log` |
| 取消 attach 不应覆盖其他连接 | exit 1，2 项失败；Owner 取消标记及宿主替换前检查修正 | `%TEMP%/diplay-lwip-host-cancel-red.log` |
| 宿主 / 服务 / 原网络回归最终定向测试 | exit 0，35 项通过，13s | `%TEMP%/diplay-lwip-host-shared-complete.log` |
| 服务拆分完整 CI | exit 0，BUILD SUCCESSFUL，6m37s；shared 1667、common 1578、home 6 项，0 失败、0 跳过 | `%TEMP%/diplay-lwip-host-ci.log` |
| 时钟端点注入首次合同 | exit 1，缺少注入构造；实现后定向测试通过 | `%TEMP%/diplay-ntp-red.log` / `diplay-ntp-green.log` |
| 时钟关闭终止态 | exit 1，关闭后仍可创建端点；增加终止态后通过 | `%TEMP%/diplay-ntp-lifecycle-red.log` |
| 请求/回复重叠发送回归 | exit 1，6 项中 1 项失败；独立审查发现同方向重叠违反 UDP 合同 | `%TEMP%/diplay-ntp-overlap-red.log` |
| 网络所有者先取消时钟端点 | exit 1，8 项中 1 项失败，取消后接收忙循环；修正后 8 项全部通过 | `%TEMP%/diplay-ntp-owner-red.log` / `diplay-ntp-complete-green.log` |
| 时钟迁移最终完整 CI | exit 0，BUILD SUCCESSFUL，8m17s；shared 1675、common 1578、home 6 项，0 失败、0 跳过 | `%TEMP%/diplay-lwip-timing-final-ci.log` |
| 虚拟主控制 / 全媒体 SETUP 首次合同 | exit 1，缺少网络/端点构造入口；实现后通过 | `%TEMP%/diplay-protocol-red.log` / `diplay-protocol-first.log` |
| 宿主 / 系统端点回归 | 16 项中 1 项关闭后重绑失败；显式地址复用选项保留旧配置后定向通过，完整 CI 又暴露 FD 注销竞态，见后续确定性回归 | `%TEMP%/diplay-protocol-integration.log` / `diplay-protocol-integration2.log` |
| 控制初始化失败回归 | 4 项中 1 项失败，健康检测异常未关闭 stream；移入统一 try/finally 后通过 | `%TEMP%/diplay-protocol-init-red.log` |
| 首轮媒体回归 | 113 项中 7 项 BufferedAudioSessionTest 失败，旧 fixture 只提供 remoteSocketAddress；兼容入口恢复元数据读取后通过 | `%TEMP%/diplay-protocol-channels.log` / `diplay-protocol-channels2.log` |
| 关闭 / 麦克风虚拟发送 | 6 项中 2 项失败，关闭后重建端点及麦克风绕过网络；修正后通过 | `%TEMP%/diplay-protocol-microphone-owner-red.log` |
| 双栈 scope 首次合同 | exit 1，缺少完整地址集合的 scope 选择；实现后通过 | `%TEMP%/diplay-protocol-scope-red.log` |
| shared 扩展定向回归 | exit 0，25s，包括全协议注入、迟到 bind/accept、scope、网络合同、缓冲音频、仪表和麦克风 | `%TEMP%/diplay-protocol-final-focused.log` |
| common 定向回归 | exit 0，27s，Bonjour 双栈、仪表内容和普通服务控制器 | `%TEMP%/diplay-protocol-common-focused.log` |
| 协议迁移第一轮完整 CI | shared 1688 项中 3 项失败：关闭后端口未释放、VPN fixture 两 SDK 仍把端点强转 ServerSocket；发现失败后取消剩余构建 | `%TEMP%/diplay-lwip-protocol-ci.log` |
| selector 注册后关闭确定性回归 | 11 项中 1 项失败：暂停 accept worker 后 close 返回，但 OS 端口仍占用；同步关闭全部 selector 后通过 | `%TEMP%/diplay-protocol-selector-red.log` / `diplay-protocol-selector-green.log` |
| 批量关闭异常回归 | 13 项中 1 项失败：首个 close 异常使其余端点未关闭；聚合异常并继续关闭后通过 | `%TEMP%/diplay-protocol-close-errors-red.log` |
| 最终网络 / 宿主 / VPN / 注入回归 | exit 0，11s；含系统 Socket adopt 的元数据、选项、流适配、FIN、deadline 与所有权 | `%TEMP%/diplay-protocol-final-cancel-green.log` |
| 协议迁移最终完整 CI | exit 0，BUILD SUCCESSFUL，7m35s；shared 1691、common 1578、home 6 项，0 失败、0 跳过 | `%TEMP%/diplay-lwip-protocol-final-ci.log` |

服务拆分验证树基于 `20d2a2cf`，源代码随后冻结为 `c897763e`。生成的
`mobile/build/outputs/apk/debug/mobile-debug.apk` SHA-256：
`6065bd7699b81dbdf33d5b1d45d66e32936ba55f093554832368aa06567db33a`。
此 APK 仅有构建证据，没有设备安装或媒体性能证据。

时钟通道验证树基于 `c897763e`。独立审查发现的 scope 丢失及发送重叠已修正，
增量复审未发现新的实质问题。发现所有者先取消端点的忙循环后，取消了当时尚未
结束的 CI，先执行红灯回归再修正；最终完整 CI 对应冻结后的全部源码。
本批 `mobile-debug.apk` SHA-256：
`125dd2b52ba87ebabefc8ff2f1861e5a18edbd9f628e2e219d375cefe558ac1b`。
没有设备时仅验证系统 IPv6 回环和虚拟端点，不把 scope 传递修正视为真机验收。

协议迁移验证树基于 `b73a2c22`。健康检测初始化异常、迟到端点、双栈 scope、
NIO 同步关闭、批量关闭异常等回归均已修正；独立源码复审未发现遗留实质问题。
双栈服务测试用实际重绑端口证明 listener 释放，不把并发 accept 抛错作为关闭证据。
最后一轮完整 CI 对应冻结的生产源码及最终测试断言。
本批 `mobile-debug.apk` SHA-256：
`3204b10bfb8eb06bc23f5b330e353603670f7f07981e0f6bb6df6e5b75197f70`。
这仍是系统后端构建，不是 lwIP 可用性或性能验收。

完整 CI 命令与 AGENTS.md 相同，后续执行增加 `--stacktrace --max-workers=2`，
避免同时执行多组 Gradle 构建造成调度压力。首次 CI 失败没有通过改变既有测试断言掩盖。

## 尚未完成

日志副本保存在本工作树 `build/reports/lwip/`（忽略的构建产物，不作为源码依赖）。

P1.1 仍需补齐 RST、真实 IPv6 scope、初始化/关闭竞态与完整 T01–T08
两后端共用运行器等覆盖；IPv4/IPv6 数据报各自的最大长度边界也需补测。
公共宿主与普通服务已接入生产控制器；P1.2 把 AirPlay/媒体的系统 Socket
边界改为显式注入 SessionNetwork 的源码工作已完成，仍需补齐各变体的全通道契约
与设备基线比较，不能仅凭本批虚拟协商测试通过 G1。
P2 已新增固定版本 native 核心与宿主帧测试（见下节）；NCM 原型、JNI、native 端点联调、选择设置及 P3–P7 发布工作仍未交付。

后续宿主工具预检：Windows 的 Python cmake 包内真实二进制为 CMake 3.15.3，
PATH 上的启动器不能作为可用性证据；缓存 lwIP archive 再次核对 SHA-256 一致。
现有 Ubuntu-20.04 是 WSL 1，GCC 9.4.0 可运行，现已安装 Linux CMake 3.16.3，
`/dev/net/tun` 不存在，`unshare -n true` 报 Permission denied。
因此该环境暂不能提供计划所需的 Linux TAP/veth/netns 验收，不能把确定性帧测试
或 Windows 回环测试替代这项证据；可继续独立实现宿主 harness 与 Android 构建。

用户确认当前没有设备或报告；`adb devices -l` 也未发现设备。
G0/G1 的真机条件未通过，后续可推进独立接口和宿主实验，不能跳过阶段门槛或
声称实际有线 CarPlay 已能脱离 VPN。当前仍使用系统网络和原有线 VPN，
没有用户态启动开关；无线会话宿主已迁至普通服务。

## P2 原生核心与宿主 harness

本批基于 `6d306b5f`。lwIP 2.2.1 的 314 个源码/说明文件保持原样，固定归档、
commit、逐文件清单及清单摘要；自有配置、端口与 C ABI 独立维护。
新增 IPv6 Ethernet netif、DAD/NDP、TCP/UDP 原始栈、单线程/单 context 所有权、
显式 link ready、时钟/随机/有界发送回调、结构化失败及销毁清理。
NOT_READY 的未发出 DAD 不会把地址误标为可用；失败发送使 context 终止。
原型只有 C ABI，未提供生产 TCP/UDP 端点或 JNI，也未接入 NCM 或用户开关。

Windows GCC 8.1 / CMake 3.15.3：12 项确定性帧测试通过，exit 0，
`%TEMP%/diplay-native-windows-final.log`。
Linux GCC 9.4 / CMake 3.16.3：同 12 项以 ASan/UBSan 和 lwIP heap/pool
溢出及 sanity 检查通过，exit 0，`%TEMP%/diplay-native-asan-final.log`（最终重跑）。
覆盖 NDP、DAD/冲突、TCP 握手/数据、UDP 空包/回显/不可达、生命周期重复100次、
not-ready/failed 发送、畸形帧、乱序分片/重组销毁、重入/时钟逆行和错误线程。
未发送 DAD 的回归先失败再修复：`diplay-native-ready-red.log`。
ELF 检查器的非二次幂对齐/缺失 GNU_STACK 回归先失败，修正后5项通过：
`diplay-native-verifier-red.log` / `diplay-native-verifier-green.log`。
独立源码复审未发现明确实质问题；审查者未独立运行测试。

NDK 28.2.13676358 独立构建 exit 0，四个目标 ABI 的 ELF 均有16 KiB LOAD 对齐
和不可执行 GNU_STACK；数据+BSS 为1,474,540至1,488,440字节。
这不包含动态 TCP 缓冲、线程栈、USB/JNI队列等完整运行时成本。
日志 `%TEMP%/diplay-native-android.log`。Linux TAP/veth 工具和独立内核 socket
客户端已实现，但两种模式在本机 `unshare --net` 均 exit 1（Permission denied），
证据 `diplay-native-kernel-tap.log` / `diplay-native-kernel-veth.log`。
不能据宿主确定性测试通过 G2，也未验证 Android 实机加载或 ARM 性能。
构建与运行命令、原型边界见 native [README](../../shared/src/main/jni/lwip_backend/README.md)。

本批完整 Android CI 九项任务 exit 0，BUILD SUCCESSFUL，6m55s；shared1691、
common1578、home6项，零失败/错误/跳过（shared/home由Gradle增量复用）。
日志 `%TEMP%/diplay-lwip-native-ci.log`。最终APK四个库均不压缩，ELF LOAD及
ZIP数据偏移均16 KiB对齐，GNU_STACK不可执行；包含完整COPYING与132组上游通知。
APK内库数据+BSS范围1,472,472至1,490,700字节；此为静态检查，不是运行时预算验收。
`python scripts/check_lwip_native.py --apk mobile/build/outputs/apk/debug/mobile-debug.apk`
exit 0，证据 `%TEMP%/diplay-native-apk.log`。APK SHA-256：
`514a302ec98a940087acbf0865dc3e9ae14241ce143ff929843a83d56cd2c7df`。
CI新增独立Linux宿主sanitizer任务，以及Android构建后的APK检查；本地已执行对应命令，
尚未声称远程GitHub Actions运行通过。两份shell入口在Git中记录100755。
许可证资产重新生成摘要一致，公开树凭据检查与staged diff检查通过；未修改上游空白。

## P2.2 NCM 发送结果与帧端口

本批基于 `ba80fdf7`。`NcmUsbBridge.sendFrame()` 新增 Sent / NotReady /
Failed 结果；旧 `send()` 继续对 NotReady 静默返回、失败抛错，并保留参数校验异常。
Android bulkTransfer 返回负数或0只能说明没有报告完整发送，不能独自证明拔线。
短写不设置 link-open 时间或成功计数，USB异常和短写成为终止失败；USB返回后在
状态锁内重检关闭并发布结果。诊断回调位于状态锁外，避免阻断另一个线程关闭。
新增 `EthernetFramePort` / `NcmFramePort`，适配器接管 bridge 的独占所有权，
发送限制14–1514字节，接收继续复用原有 UsbRequest 异步读；不能同时交给VPN。
这是阻塞I/O适配层，必须由单独I/O worker调用；尚无原型core线程或队列，
不把接口注释视为已实现线程隔离。

发送结果与端口测试首次均因缺少类型/入口失败：
`%TEMP%/diplay-ncm-result-red.log` / `diplay-ncm-port-red.log`。
状态锁内诊断阻断关闭、输入校验被错误包装分别先失败：
`diplay-ncm-diagnostic-red.log` / `diplay-ncm-validation-red.log`；修正后
发送13项及原分帧/计数定向回归 exit 0：`diplay-ncm-final-focused.log`。
覆盖not-ready前后、0/-1、完整发送、短写、关闭、USB异常、传输中关闭、
MAC防御复制、帧长度限制与独占关闭。只读复审未发现新实质问题；审查者未跑测试。

P2.2 仍需实现 `LwipPrototype` 与有界RX/TX/core工作线程，接入native上下文、
实际NCM互通及性能预检；当前没有用户态后端启动入口。

首轮完整CI exit 1，6m41s：shared1700项通过，common1578项中既有
`SettingsBackupServerTest.severalIdleConnectionsDoNotHoldUpTheNextRequestEither`
在创建6个空闲Socket时Connection refused（第381行），未进入后续HTTP断言。
日志 `%TEMP%/diplay-ncm-ci.log`，失败XML副本
`build/reports/lwip/ncm-ci-backup-failure.xml`。单独该类30项重跑exit 0，10s：
`%TEMP%/diplay-ncm-backup-repro.log`。尚未稳定复现，未改该类测试或备份服务代码，
不能声称其根因已修复。该服务backlog为4，调度下连接突发是待证实的可能因素。

未改生产代码或测试断言的完整CI重跑 exit 0，BUILD SUCCESSFUL，5m37s：
shared1700、common1578、home6项，共3284项，最终XML确认零失败/错误/跳过。
日志 `%TEMP%/diplay-ncm-ci-final.log`。首轮失败仍为未定位的间歇问题，
不能用此重跑通过宣称已修复。APK检查再次 exit 0，
`%TEMP%/diplay-ncm-apk-final.log`；四ABI ELF/ZIP对齐和许可证检查均通过。
APK SHA-256：`246aed0c81332fb73ebc465f07fed8d9fd1d54e410da52eb33241cfdd0d51489`。
凭据检查、staged diff检查通过；本批没有设备或Linux内核互通证据。

## P2.2 有界运行器

本批基于 `22dac90c`。新增 `LwipPrototype` 和 pull 型 `LwipPrototypeCore`
接口；core工厂/准备/输入/定时器/输出拉取/销毁固定在一个线程，USB IN、OUT、
关闭分别运行独立worker。输入和输出按字节数及帧数双重限额，批次最多32帧，
事件与最近timer唤醒；没有满队列忙轮询。关闭使后续帧发布失效、清空排队引用，
使用总deadline join，超时保留正在执行的core所有权，不从其它线程强行free。
首因保留，额外错误最多4条。LISTENING不依赖USB接收首帧；LINK_ACTIVE只证明
整块USB传输成功。NotReady计数丢弃、不隐式重发旧UDP帧；协议负责重试。
未确认的startup输出交付队列后暂停core link，NotReady→Sent合并不吞掉中间暂停。

测试先因缺少实现失败：`%TEMP%/diplay-prototype-red.log`。首轮7项通过后，
补充startup暂停/就绪合并用例，8项中2项运行失败再修正：
`diplay-prototype-readiness-red.log` / `diplay-prototype-readiness-green.log`。
只读审查发现await漏等迟到的USB-close worker，9项中1项先失败再修正：
`diplay-prototype-join-red.log` / `diplay-prototype-join-green.log`。
重复close超时的错误历史10项中1项先失败再修正：`diplay-prototype-history-red.log`。
最终11项包括长等待边界通过，exit 0：`diplay-prototype-boundary-green.log`。
增量复审未发现新的实质问题；审查者未运行测试。以上使用可控core/port，
不替代真实native/JNI/NCM互通证据。

详细线程、就绪、预算与未覆盖项见 [运行器说明](prototype-runner.md)。
尚未提供JNI工厂；还必须配置NCM接收限额并累计桥内部缓冲、native队列及Java队列，
不能把运行器自身限额当作已满足总512KiB或16MiB预算。未新增后端选择或改变默认路径。

本批完整Android CI exit 0，7m16s：`%TEMP%/diplay-prototype-ci.log`。
最终检查把可控core保留的借用输入改为复制，再执行完整九项CI，exit 0，1m13s
（未变任务增量复用）：`%TEMP%/diplay-prototype-final-ci.log`。
最终XML为shared1711、common1578、home6项，共3295项，零失败/错误/跳过。
APK四ABI、16KiB ELF/ZIP对齐与许可证检查再次exit 0：
`%TEMP%/diplay-prototype-apk-final.log`。APK SHA-256：
`17311e15503e20ca87b6593a258caa0094dc03a4043e59e1f363904cc90c82ad`。
公开树检查、staged diff检查通过；没有JNI加载、NCM或ARM性能验收证据。

后续验证环境预检：未找到docker/QEMU命令；现有WSL仍仅Ubuntu-20.04/WSL1。
Android emulator可执行文件存在，`-accel-check`报告WHPX可用，`-list-avds`为空，
SDK没有system-images。证据 `%TEMP%/diplay-prototype-emulator-preflight.log`。
尚未安装镜像/创建AVD/启动模拟器；后续可用独立AVD补JNI加载和CheckJNI证据，
不能替代实际iPhone/NCM或低配ARM性能门槛。

## P2.2 native 原型适配与 JNI

本批基于 `0147c982`。新增 `diplay_prototype.h` 与 native adapter：句柄是带标签的
单调 generation token，不向 Java 暴露指针；代际耗尽拒绝创建而不回绕。
上下文固定 TLS owner，错误线程不能访问或销毁。输出回调只复制到128槽、每槽1514字节的
环，不调用 Java；整体 adapter 配置分配小于256KiB。JNI 使用有界 region copy，
不保留 Java 引用、不创建 native 线程。Java 在 raw callback 外供给单调时钟与
SecureRandom 熵；耗尽终止且禁止继续输出。销毁仍只在 owner 线程执行并擦除熵。
`nativeEcho` 是显式 P2 工厂，配置防御复制，创建延迟到运行器 core worker；
没有接入用户设置、生产 SessionNetwork 选择或 AirPlay 启动路径。echo TCP7000 /
UDP6000 仅虚拟 netif，TCP EOF abort，不具备生产半关闭合同。R8 保留 JNI 名称。

NcmFramePort 接管时设置64KiB/256帧接收限额，运行器RX降为128KiB/128帧。
Ntb16Codec 在分配 datagram 前检查长度和累计限额，限制 NDP/entry 遍历并拒绝循环链；
原型丢弃并计数14–1514以外帧，限额超出映射RESOURCE_LIMIT，旧VPN默认不变。
一个有数据的 NTB 入队后先交消费方排空，再处理同一USB read中的后续块。
发现并先复现合法合并块被小队列误拒绝，再修复，保留 red/green 日志。
预算推导见 [运行器说明](prototype-runner.md)，不是 Android 内存实测验收。

测试先后证据（均执行主机 `%TEMP%`）：
- native API 缺失 red：`diplay-proto-native-red.log`；最终 Linux ASan/UBSan +
  lwIP heap/pool guards 18/18，exit0，`diplay-proto-native-final.log`；Windows
  MinGW 18/18，exit0，`diplay-proto-windows-final.log`。新增6例覆盖token、熵、
  环、时钟和销毁边界；其余12例继续覆盖真实IPv6/NDP/TCP/UDP帧。
- JNI Kotlin API 缺失 red：`diplay-proto-jni-red.log`。保留缺少JNI实现的APK
  `build/reports/lwip/proto-red-androidTest.apk`，在模拟器执行3例全部因缺失
  abiVersion native方法失败：`diplay-proto-jni-runtime-red.log`。新实现同3例
  全通过：`diplay-proto-jni-runtime-green.log`。
- 工厂缺失 red：`diplay-proto-factory-red.log`；真实运行器集成加入后4例全通过：
  `diplay-proto-factory-runtime.log`。
- NDP、UDP payload、TCP握手和payload JNI往返加入后，执行
  `./gradlew.bat :shared:connectedDebugAndroidTest --max-workers=2`，exit0，
  BUILD SUCCESSFUL，1m49s，5例零失败/错误/跳过：`diplay-proto-connected.log`。
  JUnit XML与逐测试logcat在 `shared/build/outputs/androidTest-results/connected/debug/`。
  环境为任务独立AVD `diplay-lwip-api28`，Android9/API28、x86_64、WHPX，
  serial emulator-5580；debug.checkjni=1，测试进程报告已开启-Xcheck:jni。
  logcat副本 `build/reports/lwip/proto-checkjni-logcat.txt`；AVD已关闭。
  ddmlib曾报告旧emulator console不支持getAvdPath命令，随后正常执行5例，非测试失败。
- NCM循环链运行red：`diplay-proto-ntb-cycle-red.log`；预算API编译red：
  `diplay-proto-rx-budget-red.log`；合并块运行red：`diplay-proto-rx-stream-red.log`。
  codec/framing/send/runner定向回归exit0：`diplay-proto-rx-stream-green.log`。
- native APK检查器Python单测5项通过；只读增量复审没有发现新的实质缺陷，
  审查者未跑Gradle。

上述模拟器和宿主证据不是系统内核TAP/veth互通、真实NCM或低配ARM性能证据。
G0/G1缺少参考设备与基线报告，G2吞吐/资源/稳定性门槛仍未通过。

仪器测试APK SHA-256（用于对应红绿运行）：
- 缺失JNI版本：`eb2f7b7c9ab303cdcd127e612a33a86861dfe1eb5578e6ab7902e5953f355f3e`。
- 最终5例版本：`962fbdaec68bd422e8b84a72043da1d857e724006e87305c6c019c999620b1cf`。

本批最终完整Android CI九项任务exit0，BUILD SUCCESSFUL，7m50s：
`%TEMP%/diplay-proto-jni-final-ci.log`。终态JUnit XML为shared1718、common1578、
samples/home6项，共3302项，零失败/错误/跳过（home任务增量复用）。
最终APK检查exit0，`%TEMP%/diplay-proto-apk-final.log`；四ABI库不压缩、
ELF LOAD及ZIP偏移16KiB对齐、栈不可执行，固定314文件源码与许可证检查通过。
APK SHA-256：`b030790b17625349f0abbac996f10af63d758b76224805378c8e311f7b01a3d7`。
公开树检查和staged diff检查通过；未修改上游源码。

## P2.1 独立 Linux 内核互通（Android 任务 AVD）

本批基于 `e5371cbc`，补足原宿主环境缺少网络命名空间时的独立对端证据。
新增仅用于测试的 `lwip_namespace_exec`、`lwip_kernel_peer`、
`lwip_kernel_offloads` CLI；CMake 为 Linux/Android 生成工具，Android.mk 产品库不变。
客户端只链接 libc/kernel socket，不依赖 lwIP 或自制帧构造器。
设备侧脚本仅接受 disposable emulator/root，并在 helper unshare + 核对不同
netns 后才配置接口。脚本结束关闭 echo，接口随临时 namespace 回收。

环境：任务 AVD `diplay-lwip-api28`，serial emulator-5580，Android9/API28 x86_64，
Linux4.4.124+、WHPX；NDK28.2.13676358，Android API24 standalone工具，
CMake3.15.3/MinGW Makefiles。旧CMake给出版本警告，但构建目标成功。
测试中的root/TAP/veth权限仅用于内核对端，不是产品用户态网络的权限要求。

先观察 Android target 缺失：`%TEMP%/diplay-kernel-android-target-red.log`。
增加跨编译目标后，原 `/dev/net/tun` 在Android不存在导致真实运行exit1：
`diplay-kernel-android-tap-red.log`；测试工具仅在ENOENT时回退 `/dev/tun`。
初轮 TAP 又因等待 `/sys/class/net` 的接口超时，调查在同一新netns中
`ip link show dev dp-lwip0` 能看到接口，继承的sysfs却看不到：
`diplay-kernel-android-sysfs-probe.log`。两个测试脚本改为通过ip查询当前namespace。

veth 首轮 TCP connect timeout：`diplay-kernel-android-veth-first.log`。
保留抓包 `build/reports/lwip/kernel-veth-red.pcap`，tcpdump显示NDP有效但
TCP SYN cksum 0xfd32 incorrect（expected0x9a02等），证实AF_PACKET收到卸载前报文。
新CLI在两端隔离veth上关闭TSO/GSO/GRO/TX checksum并读取确认；未关闭lwIP校验。
修正后veth通过：`diplay-kernel-android-veth-green.log`。

格式化并重新跨编译全部工具后，最终依次执行tap、veth均exit0：
`%TEMP%/diplay-kernel-android-final.log`。每种模式独立内核对端验证：
- TCP三次连接，每次64KiB，累计内容、顺序和完整长度一致。
- UDP长度0、1、1200、4096、8192，内容及来源fe80::1/6000一致，含IPv6分片往返。
- 最终tap：rx271/tx226/not_ready0/heap_peak35880；veth：rx307/tx230/
  not_ready0/heap_peak34448。这些是本次echo计数，不是性能或总内存验收。

父namespace仍为net:[4026531963]，测试前后无dp-lwip0/dp-peer0，结束后无lwIP
测试进程：`diplay-kernel-android-cleanup.log`；任务AVD已关闭。
Linux宿主18项ASan/UBSan +heap/pool guards通过，exit0：
`diplay-kernel-host-final.log`，shell语法检查通过。
完整九项Android CI exit0，4s（增量复用）：`diplay-kernel-final-ci.log`。
APK四ABI/16KiB/许可证检查exit0：`diplay-kernel-apk-final.log`，APK仍为
`b030790b17625349f0abbac996f10af63d758b76224805378c8e311f7b01a3d7`。
只读复审未发现明确实质问题，审查者未执行测试。

可复现命令见 native README 的 Disposable Android emulator kernel peer。
WSL1原Linux shell入口仍因unshare权限不足未覆盖；本记录只证明Android Linux内核
TAP/veth功能互通。它没有经过Java/JNI运行器、USB/NCM或真实iPhone，不能替代
低配ARM预算、G0/G1真机回归或G2门槛。生产端点仍未启用。

## P2.2 慢消费者与原型 echo 停滞修复

本批基于 `c852ea79`。新增独立内核客户端 `--slow`，socket缓冲请求4KiB，
fork sender连续发送1MiB，首send后reader暂停十秒；确认writer未退出后恢复，
按绝对流位置逐字节校验并要求sender正常结束。设备脚本新增basic/slow场景，
核心工具在owner command边界采样拒收长度和零窗口，raw callback无逐包日志。

缺失选项先运行exit2：`%TEMP%/diplay-slow-peer-red.log`。
实际TAP慢读首轮运行exit1，恢复仅504184字节：`diplay-slow-tap-first.log`。
诊断重现仅557620字节，max_refused34272 > sndbuf32768，零窗口采样154：
`diplay-slow-tap-diagnostic.log`。旧echo整块write因超过固定总send-buffer无法恢复。
修复按剩余sndbuf消费，callback_arg记录已复制偏移；只有write成功才recved，
部分完成保留原pbuf给lwIP重试，全部完成才释放；EOF仍abort。未扩大任何栈缓冲配置。

新增确定性 `tcp_large_refused`：真实乱序27x1440再补首段，合并40320字节callback，
只ACK已观察的连续输出，最终内容和校验和完整。最终测试对旧实现运行失败：
`diplay-slow-native-final-red.log`；修复后Linux ASan/UBSan +pool/heap guards
19/19 exit0：`diplay-slow-native-final-rebuilt-green.log`；Windows19/19 exit0：
`diplay-slow-windows-final.log`。调试期间记录过缺少stdint头的编译失败、
把Nagle下未发送尾部当作已发送的测试假设错误，以及恢复旧文件mtime导致旧object
未重编译；均修正并保留原日志，最终记录明确确认fresh rebuild。

格式化并跨编译后，API28 x86_64任务AVD连续执行tap basic、veth basic、
tap slow、veth slow全部exit0：`diplay-slow-android-final.log`。两slow场景
均十秒背压后恢复完整1MiB，零窗口采样分别153/161，lwIP heap peak36024/36008。
仅是echo/内核功能预检，不是完整动态内存峰值。环境和完整数据见
[原型背压预检](prototype-backpressure.md)。Android tc不支持netem，
`diplay-fault-netem-preflight.log`，丢包/延迟注入未覆盖。

最终新版native库Android仪器测试5项零失败/错误/跳过，exit0，14s：
`diplay-slow-connected-final.log`。JUnit XML在shared/build/outputs/androidTest-results；
CheckJNI已开启，logcat副本build/reports/lwip/slow-checkjni-logcat.txt。
父namespace无测试接口/进程，任务AVD已关闭：`diplay-slow-cleanup.log`。
完整九项Android CI exit0，3s（增量复用）：`diplay-slow-final-ci.log`；
APK四ABI/16KiB/许可证检查exit0：`diplay-slow-apk-final.log`。
APK SHA-256：`54677a220cff6e9fa79ee1c7984212bc38cdea2c01282a7181b1de8adc918359`。
只读复审核对lwIP refused-data重试所有权和客户端验证，未发现新实质问题；未跑测试。
G0/G1/G2、生产端点合同、实际NCM/ARM预算仍未验收。

最终CI终态XML：shared1718、common1578、samples/home6，共3302项零失败/错误/跳过；公开树与staged diff检查通过。

## P2.1 有界出口故障注入

本批基于 `650b1920`，为不支持netem的任务AVD新增测试专用wire_faults调度队列。
固定256槽拥有帧副本，不扩容；满队列返回not-ready。faults-1/faults-5每100/20个
接纳Ethernet帧丢一个，另调度5ms延迟、每第10帧与可用排队前帧交换，
每100帧相位50复制一次。重复写共用槽，第二次完整write后才计数；短写/失败不出队。
head deadline和core timer控制poll，未到期不请求POLLOUT。CLI/脚本保留无注入默认；
fault配置退出时任一故障计数为零则失败，客户端/echo失败均传播。
仅CMake测试工具链接该模块，Android.mk产品库不变。

先创建测试后因缺少wire_faults.c失败：`%TEMP%/diplay-wire-red.log`。
新增6例覆盖容量、绕环、输入复制、延迟deadline、丢弃、相邻重排、重复完成、
非法长度及时间溢出。最终Linux ASan/UBSan +lwIP guards25/25 exit0：
`diplay-wire-linux-verified.log`；Windows25/25 exit0：`diplay-wire-windows-final.log`。
Android shell语法检查通过。

API28 x86_64任务AVD上独立内核TCP三次64KiB，四种模式均exit0：
- TAP faults-1：236 admitted /2 drop /234 delayed /18 queued swaps /2 duplicates，
  `diplay-wire-tap-1-first.log`。
- TAP faults-5：260/13/247/10/3；veth faults-1：204/2/202/16/2；
  veth faults-5：246/12/234/7/2，`diplay-wire-other-first.log`。
以上是发送方向Ethernet帧确定性规则，不是随机损失或精确TCP数据段比例。
格式化重编后无故障TAP基本互通、veth十秒暂停1MiB恢复均exit0且全部注入计数0：
`diplay-wire-baseline-final.log`。

另保存veth faults-5抓包build/reports/lwip/wire-faults5.pcap；仅抓包见到的重复
TCP载荷与人工复制数量相同，不能用作栈重传证据（首次输出可能在线缆前已丢弃）。
新增固定1024项native输出重复提交观测，人工复制write不重复经过它；整个port的
队列/观测/熵/元数据编译期断言<=512KiB。最终veth faults-5再运行exit0，
记录10次重复native提交，而人工复制2次：`diplay-wire-retransmission-final.log`。
这是有界工作集估计，非完整TCP MIB；哈希碰撞/淘汰/分段变化可能影响。
规则、完整数据与范围见[出口故障实验](prototype-wire-faults.md)。

父namespace无遗留测试接口/进程，AVD已关闭：`diplay-wire-cleanup.log`。
完整九项Android CI exit0，3s（增量复用）：`diplay-wire-final-ci.log`。
APK四ABI/16KiB/许可证检查exit0：`diplay-wire-apk-final.log`；产品APK未变，SHA-256
`54677a220cff6e9fa79ee1c7984212bc38cdea2c01282a7181b1de8adc918359`。
公开树检查、staged diff检查通过。只读审查未发现实质问题，未运行测试。
UDP故障归因、双向随机故障、长时负载、实际NCM/ARM以及G0/G1/G2仍未验收。

## P0.2 发布签名与上游修复审计快照

本批基于 `d58c2f15`，只增加审计证据与明确待修复项，没有修改vendor/产品代码。
从官方下载目录取得310字节ZIP detached signature，SHA-256为
`e934e3c4d0fd4b6ac213db43d163707942184df6df8784c0f084ed5d95532a18`。
维护者Savannah账号指向的公钥指纹
`746BEB8E56372EB695EA02E71D6B61C1B84299F5`与签名一致。隔离GPG home验证既有
ZIP exit0，GOODSIG/VALIDSIG：`%TEMP%/diplay-lwip-signature-verify.log`。
owner trust保持undefined；未向用户GPG keyring导入，也未设置owner trust。
ZIP本体digest再次核对与固定值一致。Git annotated tag正文无签名，不称签名tag。
初次从NTFS home运行list-packets的权限警告后，正式verify改用WSL文件系统的隔离目录。

官方Git完整克隆至ignored build/lwip-upstream-audit，clone exit0：
`diplay-upstream-clone.log`；审计HEAD=d08f4773edd0182b7910fc8f046eed82ffcd67c9。
tag..HEAD当前core/ethernet源范围16项变更保存于
build/reports/lwip/upstream-core-changes.txt。发现TCP选项长度、IPv6重组清理、
NDP选项依赖链与PMTU最小值等启用路径修复，尚未建立全部回归或回补；
安全审计明确未通过。完整条目、来源及后续回补要求见[上游审计](upstream-audit.md)。

实际四ABI stack archive各30个object，无mqtt/snmp/smtp/lowpan/ppp/zepif成员，
对象清单build/reports/lwip/audit-objects-*.txt。相关CVE/SMTP修复已逐项说明配置适用性，
不把模块未链接写成原源码已修复，也不认为这是完整漏洞扫描。
公开CNA/CISA记录和官方commit作为来源；直接网页有403/抓取限制时保留官方Git/JSON
及实际下载证据，不据第三方文章宣称已修复或不受影响。

本批完整九项Android CI exit0，2s（增量复用）：`diplay-upstream-audit-ci.log`。
source清单与APK四ABI/16KiB/许可证检查exit0：
`diplay-upstream-source-verification.log` / `diplay-upstream-audit-apk.log`。
产品APK未变，SHA-256仍为
`54677a220cff6e9fa79ee1c7984212bc38cdea2c01282a7181b1de8adc918359`。
公开树检查通过。设备G0/G1/G2仍未通过；启用路径安全回补也成为明确待完成项。

只读复审核对16项变更、CNA记录及签名trust表述，未发现虚假闭环；审查者未重跑验证。staged diff检查通过。

### TCP选项边界回补（2026-10-22）

用户确认当前没有参考真机或报告；继续宿主工作，G0/G1不视为通过。
基于e586b0e9，精确回补上游3d896ba0a37ff3ce73270ca5e230707fe47f60e3的四行检查。
原始314文件清单及其digest不变，验证器先核对原始digest，再只接受精确补后digest。
完整源身份见native目录BACKPORTS.md；额外修改回补文件或其他文件均被拒绝。

新增直接编译真实tcp_in.c的测试，不添加产品测试入口。精确分配连续选项缓冲及
双缓冲各分割点，覆盖缺长度字节的MSS/未知选项及有效MSS。
原版ASan实测heap-buffer-overflow，tcp_get_next_optbyte从24字节分配末尾越界读一字节：
WSL /tmp/diplay-tcp-option-red.log。修复后Linux ASan/UBSan/pool guards和Windows
MinGW均26/26通过，完整性验证器Python测试7/7。
直接双缓冲测试不宣称覆盖全部入口pbuf操作；既有真实帧测试继续运行。

完整九项Android CI exit0，16s，250任务（17执行），日志TEMP/diplay-tcp-option-ci.log。
四ABI APK ELF/16KiB/NX/许可证检查通过，APK SHA-256为
8ccf584dc88209f7dd48b32921f29136286146943a1459a6af4a41f559400e48。
JNI connected测试5/5、无失败/错误/跳过，exit0，13s：diplay-tcp-option-connected.log。
DeviceMonitor查询AVD路径出现bad sub-command告警，随后设备测试正常完成，XML已核对。

重建Android独立内核工具后，任务自有API28 x86_64模拟器验证：
TAP basic TCP 3×64KiB及UDP空包/MTU/分片通过；veth faults-5 TCP 3×64KiB通过，
admitted263/drop13/delay250/reorder10/duplicate3，repeated_tcp_admissions10。
全部在独立网络命名空间，脚本exit0；日志diplay-tcp-option-kernel-{tap,veth}.log。
这些是模拟器内核证据，不是NCM/iPhone或ARM验收。
只读复核未发现测试/补丁/完整性逻辑实质问题；审查者未运行测试。
NDP/IPv6重组/PMTU回补继续开放，整体安全审计未通过，生产未选用lwIP后端。

### IPv6空重组清理回补（2026-10-22）

基于25cd4971，精确回补上游56b29f8bcfaefe2974dca67bde16cc7c391feaeb的IPv6部分；
IPv4关闭，未改动其vendor源码。原始manifest保持不变，补后digest进入严格白名单。
BACKPORTS.md记录原始/补后摘要及测试范围。

新reassembly_tests.c编译真实ip6_frag.c，在core线程执行真实重组入口：129个pool
pbuf超过128配额，入口释放被拒绝链并留下空重组上下文。原版在timer清理时UBSan
报告ip6_frag.c157空pbuf成员访问；纠正测试回调/销毁参数类型后重新运行，仍为预期
red、exit1。补后连续八轮超额拒绝及31次timer tick清理，各轮pbuf/context池计数归零。
这是内部配额失败路径，不声称当前每帧一个pbuf的入口可远程触发，也不称为分配失败注入。

Linux ASan/UBSan/pool guards和Windows MinGW均27/27通过；Python完整性测试7/7。
完整九项Android CI exit0，1m17s，250任务17执行，TEMP/diplay-reassembly-ci.log。
四ABI APK检查通过（ELF/ZIP 16KiB、NX、许可证与源码摘要）；APK SHA-256为
d01f85b9a1b13776e1236e80bf9224299ca4425dd17ec248e22e59359fc4849e。
JNI connected 5/5、无失败/错误/跳过，19s、exit0；日志diplay-reassembly-connected.log。
AVD路径查询同样有DeviceMonitor bad sub-command告警，测试XML已核对。

重建Android内核工具，任务自有API28 x86_64模拟器隔离netns的TAP basic通过：
TCP 3×64KiB，UDP空包/MTU/分片，exit0；diplay-reassembly-kernel-tap.log。
rx265/tx229/not_ready0，heap_peak36008。该结果不替代NCM/iPhone和ARM验收。
只读复核未发现实质问题并核对上游diff及digest；审查者未运行测试。
公开树和diff检查通过。NDP/PMTU仍开放，整体安全审计与真机门槛仍未通过。

### NDP依赖链与选项边界（2026-10-22）

基于962bc1ca。nd6.c回补0a65e654/db4e4c9d/8e75a40a/7d1327e5四项上游依赖链；
脚本核对原文件等于固定tag，官方tag..7d对该文件只有这四项变更，保留CRLF。
新增真实Ethernet/IPv6 NS回归：未知选项在前、Source LL在后，原版NA目的MAC取到
未知选项的a5字节，独立重跑red exit1；补后目的MAC正确。前置零长度和后续截断
Source LL用例均无NA。日志WSL /tmp/diplay-ndp-red.log。

复核提示上游查找函数16位offset存在回绕风险。独立内存边界测试确认：近65535末尾
的选项长度超过剩余字节时，原链返回起始位置之前的无关选项，ctest red exit1。
随后本地增加推进前剩余长度检查，返回NULL。该防御性检查不是上游提交内容，摘要
及来源在BACKPORTS.md分别记录。直接真实helper还验证八字节选项仅复制可用的六字节，
即使hwaddr_len为八也不越界。未生成网络攻击流量，不声称远程可利用性或分配CVE。

最终Linux ASan/UBSan/pool guards与Windows MinGW各29/29；Python完整性7/7。
完整九项Android CI exit0、8s，250任务17执行：TEMP/diplay-ndp-final-ci.log。
四ABI/ELF与ZIP 16KiB/NX/许可证检查通过；最终APK SHA-256：
d46bab59d7677067c79de36d935a5cedfcecd0d4726a05f232dbfd19790dab5b。
JNI connected 5/5，零失败/错误/跳过，exit0、9s：diplay-ndp-final-connected.log。
早期不含本地guard的CI/JNI/内核结果不用于证明最终版本。
最终Android独立内核工具重建后，任务自有API28 x86_64模拟器veth basic在独立netns
通过TCP 3×64KiB、UDP空包/MTU/分片，exit0：diplay-ndp-final-kernel-veth.log。
rx317/tx212/not_ready0、heap_peak36008；该结果不是NCM/iPhone或ARM验收。

只读审查提出offset问题后其审查回合未正常完成，不记作审查通过；本地完成了上述
red/green验证。NA/Redirect/RA完整入口定向覆盖仍待补齐，PMTU仍未回补，安全审计
继续未通过。真机G0/G1与ARM门槛不变；生产未启用lwIP后端。

### PMTU最小值与后续发包（2026-10-22）

基于3661abd5，适配上游d08f4773最小MTU检查，保留原32位读取和65535钳制；
不采用低16位mask，避免把较大值按65536取模。累计nd6.c原始/最终digest及本地差异
记录在BACKPORTS.md，原始发布manifest不变。

真实帧测试先从UDP echo取得实际发出的IPv6头，再作为ICMP6 Packet Too Big引用头。
逐项检查1/0/1279/1280/1400/65535/65536/UINT32_MAX更新后的destination cache MTU，
并验证后续UDP echo。原版缓存接受过小值，独立ctest red exit1；修复后通过。
日志WSL /tmp/diplay-pmtu-red.log、diplay-pmtu-final-host.log。
该用例不声称覆盖全部引用包匹配或TCP大流量恢复。

Linux ASan/UBSan/pool guards和Windows MinGW各30/30；完整性Python测试7/7。
Windows第一次编译因新增lwIP头的htonl宏影响Windows SDK声明失败，核对头文件后在
测试文件定义LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS，复测30/30；产品配置未改。
完整九项Android CI exit0、8s，250任务17执行，TEMP/diplay-pmtu-ci.log。
JNI connected 5/5、零失败/错误/跳过，12s、exit0：diplay-pmtu-connected.log。
四ABI APK ELF/ZIP 16KiB、NX、许可证/源码检查通过，APK SHA-256：
15de03f85542e51c0cf2cd874615fba1fbfbddadf1b0205e32e092b71d69b38e。

重建Android内核工具后，任务自有API28 x86_64模拟器独立netns的TAP basic通过TCP
3×64KiB、UDP空包/MTU/分片，exit0：diplay-pmtu-kernel-tap.log。
rx271/tx226/not_ready0、heap_peak36008；不是NCM/iPhone或ARM验收。
只读复核回合未完成，不记作通过；实现diff和测试结果已由主代理核对。
NDP其余定向入口、完整端点/会话集成及真机验收继续待完成，整体方案尚未完成。

### P3私有端点句柄注册表（2026-10-22）

基于dfc31d9c，新增endpoint_registry.{h,c}及独立测试；CMake/Android.mk均编译。
80槽固定容量嵌入dp_core，进程级单调generation和类型/槽号组成正值token，不编码
指针。耗尽拒绝，代数不回绕；成功注册转移所有权，remove先失效再释放，clear幂等。
core销毁在剩余PCB清理前clear。内存由现有sizeof(core)预算统计覆盖。
详细合同和未覆盖项见[端点注册表](endpoint-registry.md)。

测试先因缺实现编译失败，再以占位实现运行至预期注册断言red、exit1。实现表后通过；
补充真实core销毁用例再次red，确认释放回调未执行，接入clear后green。
覆盖正确查找、已占用的不同表同槽/同类型隔离、类型错误、旧句柄、槽复用、满80项、
失败输出清零、逐项/批量释放一次、清空后代数不重用及真实上下文销毁释放。
不把计数回调等同于真实TCP/UDP PCB并发关闭验证；后续端点实现仍需覆盖。

Linux ASan/UBSan/pool guards、Windows MinGW各31/31。补充跨占用表隔离后Linux
定向用例及Android x86_64独立native测试重新通过，Windows完整31项重跑通过。
日志WSL /tmp/diplay-endpoint-registry-host.log及TEMP/diplay-endpoint-registry-windows-final.log。
完整九项Android CI exit0、27s，250任务17执行：diplay-endpoint-registry-ci.log。
JNI connected 5/5，零失败/错误/跳过，17s、exit0：diplay-endpoint-registry-connected.log。
Android native endpoint_registry_tests在任务自有API28模拟器exit0，不依赖root。
APK四ABI/16KiB/NX/许可证与源码检查通过，SHA-256：
454658946ef961c8fea0bbe1d1efacca65901236756c7224fa163e7dc12dcdcd。

G2实际NCM与ARM预检缺口仍存在，不扩大业务迁移或生产后端选择。这只是P3的基础
组件，尚无公共lwIP SessionNetwork或完整端点JNI操作，G3未通过。公开树检查通过。

### P3 native UDP端点（2026-10-22）

基于25d43648，新增diplay_endpoints.h及udp_endpoint.c，CMake/Android.mk编译。
实现虚拟IPv6/netif bind、动态端口、发送、拥有型数据报接收、队列统计和关闭，
使用已有generation注册表。接收返回来源/原始长度/复制长度；空队列WOULD_BLOCK
与成功空包分开。API均为核心线程非阻塞操作，尚未暴露端点JNI或Kotlin实现。
合同和范围见[native UDP端点](native-udp-endpoints.md)。

首次测试缺符号链接失败；加入占位实现后运行至预期bind断言red、exit1，再实现。
真实帧覆盖动态/冲突端口、准备期bind、双向零长度、来源、截断、UDP端口和校验和、
端点记录及字节上限、带序号的记录回绕/块复用、32个UDP PCB耗尽、排队关闭/新core
零资源。32KiB用例乱序输入IPv6分片，完整接收后发送并重组核对内容与UDP校验和。

补测实际输出由ready转not-ready时，初版错误返回LINK_IO，red复现后改为WOULD_BLOCK；
真正输出失败仍沿core failure返回LINK_IO。恢复ready后继续接收，未隐藏重发。
对照performance.md发现初版仅端点限额不满足所有UDP累计512KiB，32端点总量测试red。
改为共享1KiB块池，扣除32端点记录和块索引后计算块数，编译期断言总缓存分配≤512KiB。
raw接收无分配，首次成功bind分配共享池，最后端点关闭释放；失败路径归还所有权。
额外libc对象加入heap_used/configured_bytes；heap_peak为各分配域峰值之和，属保守上界。
此段早期实现从未作为最终候选提交，旧绿色结果不用于证明最终资源预算。

最终Linux ASan/UBSan/pool guards及Windows MinGW各33/33；补充运行时512KiB分配
断言后，Linux定向两用例和Windows完整套件重跑通过。
日志WSL /tmp/diplay-udp-endpoint-budget-host.log，TEMP/diplay-udp-endpoint-budget-windows-final.log。
完整九项Android CI exit0、8s，250任务17执行：diplay-udp-endpoint-budget-ci.log。
JNI兼容回归5/5，零失败/错误/跳过，10s、exit0：diplay-udp-endpoint-budget-connected.log。
最终Android x86_64独立frame_tests的udp_endpoint及udp_endpoint_fragments均exit0；
构建日志diplay-udp-endpoint-budget-android-build.log，不依赖root。
四ABI/16KiB/NX/许可证和源摘要检查通过，最终APK SHA-256：
5672fadb21dae002ab3eee409883e35571a3d6a297213fc0c3317c16a49d4222。

未宣称全部65527字节边界、公共阻塞/deadline合同、按媒体通道的过期丢弃或实际
NCM/iPhone/ARM已验收。仍需JNI命令桥、公共适配、TCP及会话集成，G2/G3未通过。
公开树检查通过；本批未取得独立审查完成结果，不记作独立评审通过。

### P3 UDP JNI桥与ABI 2（2026-10-22）

基于84dbb2f2。新增udp_jni.c及C私有prototype_internal.h，prototype上下文添加
65527字节复制区及UDP token转发API；不把dp_core指针传给Java。上下文创建线程和
generation验证后才能借用复制区；每次调用仅使用Region复制，不保留Java数组地址。
总prototype对象含复制区静态限制≤256KiB，原有预算测试仍通过。
接口形状、异常路径和未完成范围见[UDP JNI桥](udp-jni.md)。

新设备测试在未实现JNI时真实red：udpBind UnsatisfiedLinkError，原有5项通过、
新增1项失败，exit1：TEMP/diplay-udp-jni-red-runtime.log。实现后6项green。
随后新增ABI2断言，旧返回1再次red（expected2/actual1），exit1：diplay-udp-jni-abi-red.log。
同步Kotlin加载检查与JNI abiVersion返回2；native-only核心C ABI仍为1。

设备回归真实帧UDP接收/发送、源地址/端口、原始和复制长度、空包、子范围发送、
溢出offset/负长度、无效数组不消费包、target/source同对象拒绝、错误线程、类型
token误用、重复关闭、槽位复用和上下文重建。Java输入帧随后清零不改变接管数据。
新增宿主UDP上下文生命周期覆盖带未关闭端点销毁、旧token拒绝和上下文失败后释放。

最终Linux ASan/UBSan/pool guards、Windows MinGW各33/33；
WSL /tmp/diplay-udp-jni-host-final.log、TEMP/diplay-udp-jni-windows-final.log。
完整九项Android CI exit0，8m30s，250任务52执行：diplay-udp-jni-ci.log。
最终ABI2 connected测试6/6，零失败/错误/跳过，13s、exit0：diplay-udp-jni-final-connected.log。
任务自有API28 x86_64模拟器debug.checkjni=1，测试进程5539日志确认
Not late-enabling -Xcheck:jni (already on)。AVD路径DeviceMonitor仍有bad sub-command
告警，测试正常完成并核对XML。没有以此宣称OOM异常注入或全部JNI边界验收已完成。

最终APK四ABI/ELF与ZIP 16KiB/NX/许可证与原始源码+精确补丁检查通过，SHA-256：
9aeaab072b322d6471fe1f3053bc1e68d05983d2ee6ad109cd74013894291afe。
现有native方法R8 keep规则覆盖新方法。公开树及diff检查通过。
仍缺公共UdpEndpoint、核心命令/完成队列、deadline/取消、TCP和会话选择；G2/G3及
实际NCM/iPhone/ARM设备门槛不变，整体目标尚未完成。

### P3 核心命令队列与运行器接入（2026-10-22）

基于222cf024。新增内部CoreCommandQueue，默认128条/256KiB声明保留字节，
包括执行中和已完成未领取的命令。支持绝对单调时钟deadline、线程中断和停止取消；
取消后的迟到结果在核心线程清理，清理前继续占预算。Pending只随实际进展重试，
每轮最多32次尝试。接入现有运行器线程，native时钟与命令时钟共享起点。
详细合同与限制见[核心命令队列](core-command-queue.md)。

队列缺失实现的运行时red记录于TEMP/diplay-core-command-red-runtime.log；
运行器未接入时命令超时的red记录于diplay-core-command-runner-red.log。
接入后8项队列测试及运行器回归通过，覆盖保留预算、超时、取消、迟到清理、
输入唤醒和核心线程归属。定向green日志diplay-core-command-runner-green.log。

完整九项Android CI exit0，8m25s，250任务46执行：diplay-core-command-ci.log。
JUnit共3312项（shared1728/common1578/home6），零失败、错误、跳过。
Android connected 6/6，零失败、错误、跳过，17s：diplay-core-command-connected.log；
其中真实native核心stats通过运行器命令调用。任务自有API28 x86_64模拟器
debug.checkjni=1。四ABI/16KiB/NX/许可证和源码摘要检查通过；APK SHA-256：
26041f09f2bd6a9ded0d3d2ab4e2c5bdce7a78a6e5d22efaa15021bde1d894b4。
公开树检查通过。本批未取得独立审查完成结果，不记作独立评审通过。

本通用队列依赖类型化调用者准确声明保留字节，不证明复制前内存准入。
公共UdpEndpoint、端点级取消、TCP、会话选择及实际设备门槛仍待完成。
