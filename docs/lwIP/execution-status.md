# 实施记录

执行日期：2026-10-21 至 2026-10-22。工作分支：`codex/lwip-backend`。起点：`143f4674`。

## 当前交付

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
