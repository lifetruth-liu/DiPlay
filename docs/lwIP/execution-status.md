# 实施记录

执行日期：2026-10-21 至 2026-10-24。工作分支：`codex/lwip-backend`。起点：`143f4674`。

## 当前交付

- **P0：**源码通道调用链、固定 lwIP 2.2.1 来源/摘要及设备基线缺口已记录；IPv4/mDNS
  脱敏抓包、VPN 禁用设备和无线基线仍缺失，P0/G0 的这些实测项未验收。2026-10-24 已在
  API 26 arm64 Android 手机上与真实 iPhone 完成短时 USERSPACE NCM/CarPlay 冒烟，见下方设备记录；
  这不替代参考抓包、地址/scope 分析或设备矩阵。
- **P1：**`SessionNetwork`、TCP/UDP 端点及系统适配、attachment 所有权和主控制/媒体
  通道注入已实现；共享合同测试覆盖系统与 lwip-jni。API 26 arm64 真机上的系统/lwIP
  端点合同及系统 IPv6 scope 仪器测试通过；iPhone 对端 scope、并发 USB 拔插、全进程峰值
  及全部故障矩阵仍未验收。
- **P2：**固定 lwIP 核心、IPv6 Ethernet/NDP/TCP/UDP 原型、NCM frame port、宿主帧测试、
  sanitizer 和四 ABI 构建已接入。宿主功能证据不代替 Linux 内核隔离网络基准或低配 ARM
  性能验收；真实 NCM/iPhone 短时会话已见下方记录，用户态后端仍不是默认路径。
- **P3：**TCP/UDP JNI 端点、有界队列与直接缓冲池、关闭屏障和结构化错误已实现；宿主
  分配/异常故障注入与 API 28 CheckJNI 自动化已有覆盖。DirectByteBuffer 的 HotSpot direct-memory/heap
  OOM 和 API 26 arm64 真机、API 28/29 AVD 的独立 Android service 进程 ART heap OOM 均验证映射到
  RESOURCE_LIMIT；完整 R01、全进程峰值和设备拔插仍未通过。
- **P4：**显式 USERSPACE 有线集成覆盖控制、事件、媒体、时钟、麦克风和 iAP 通道；fake
  peer、native SETUP、Activity/配对及 VPN/无线所有权自动化通过。API 26 arm64 真机上的 native
  通道/JNI instrumentation 也通过；另有一次真实 iPhone NCM 会话通过视频、音乐、导航、Siri
  双向语音和触控烟测。电话及其余设备矩阵未测，不能据此宣称 P4 最终设备验收完成。
- **P5：**后端偏好、受限 AUTO 回退、成功缓存、Advanced 实验选项及脱敏诊断已实现；AUTO
  仍走既有 VPN 默认路径，USERSPACE 需显式选择。
- **P6：**native CTest、Android 生命周期/端点回归、fuzz driver、配置成本与 release APK
  静态布局检查已接入。三个 seed-7 sanitizer campaign 均完成每目标 8 CPU 小时，报告/二进制摘要匹配，0 失败 batch、0 崩溃及 sanitizer 诊断；
  campaign 启动后仅新增宿主测试 harness，aggregate 源目录摘要变化已单独标注；
  API 26 arm64 真机 shared instrumentation 为 46/46 通过；Activity 重建仪器测试被 MIUI
  系统拒绝启动 Activity。性能、长稳和设备门槛仍未验收。
- **P7：**安装、隐私、兼容说明、问题模板和第三方声明已更新；发布候选、兼容设备报告和
  连续实验发布条件尚未满足。

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

- **G0/G1/G2 设备证据：**Xiaomi Mi 5s Plus（Android 8.0/API 26、arm64、MIUI
  V11.0.2.0.OBGCNXM）已与真实 iPhone 完成一次短时 USERSPACE NCM/CarPlay 会话，视频、音乐、导航、
  Siri 双向语音及触控有观测证据，详见文末设备记录。IPv4/mDNS 脱敏抓包、iPhone IPv6 scope、VPN
  禁用设备全会话、无线 A/B 基线、电话、设备并发及性能/长稳仍未验收。
- **Linux 宿主基准：**现有 Ubuntu 20.04 WSL1 缺少 `/dev/net/tun` 且拒绝 `unshare -n`；
  已另建临时 WSL2 副本并完成隔离 veth 600 秒 TCP/UDP 吞吐基准及 TAP 基本互通，
  详见下方 2026-10-24 记录。core 配置成本与 Linux 宿主网络基准现已有证据；它们仍不能替代
  低配 ARM A/B/A、真实 NCM/iPhone 或完整设备资源验收。
- **R01/G3：**现有宿主故障矩阵覆盖核心/周期定时器、TCP/UDP 多处分配、fake-JNI 异常与
  HotSpot DirectByteBuffer 分配边界的 direct-memory/heap OOM；API 26 arm64 真机和 API 28/29 AVD
  另有 ART heap OOM 子进程证据。完整分配位置、其他 libc 分配、全部并发关闭组合及其他设备/API ART
  覆盖仍未穷尽。
- **G6：**三项 native sanitizer fuzz 已各自达到 28,800 CPU 秒；8 小时设备会话、100 次
  USB 插拔/重连、低内存恢复、core stall、低配 ARM32/ARM64 峰值/性能和 release 运行预算
  仍未验收。宿主 10,000 次生命周期子项和 API 28 模拟器 100 次 JNI 生命周期不代表设备长稳。
- **G7：**尚未形成满足发布清单的候选提交/APK/兼容设备报告，也没有两次连续实验发布的
  G6 证据；真实设备证据不足以支持默认策略变更。

当前生产默认仍是既有 VPN 路径；USERSPACE 是显式实验选择。以下“运行证据”及后续记录
按时间保留历史结果；本节和文末最新快照反映当前状态，历史快照不可覆盖当前未完成门槛。

此前在原始 Ubuntu-20.04 WSL1 的宿主工具预检：Windows PATH 的 Python CMake 启动器不可用，
显式使用 CMake 3.15.3；WSL1 的 GCC 9.4.0 与 CMake 3.16.3 可构建，但缺少 `/dev/net/tun`，
`unshare -n true` 报 Permission denied。该限制仍适用于原始 WSL1 发行版；为执行 Linux 网络验收，
本轮从其导出副本并单独导入 WSL2，结果见下方最新记录。

较早快照曾记录没有可访问的 ADB 设备和基线报告；2026-10-23 已改用 SDK Platform Tools ADB
连接到下文所列 API 26 手机并完成 shared 仪器测试。该变化补上真实 ARM64 Android/JNI/ART
运行证据，但 G0/G1 的 iPhone、NCM、VPN 禁用及基线条件仍未通过；不能据此声称实际有线
CarPlay 已能脱离 VPN。生产默认仍是系统网络/原 VPN，USERSPACE 仍需显式选择；无线会话宿主已迁至普通服务。

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

### P3 按端点取消命令（2026-10-22）

基于96efb9bd。CoreCommandQueue新增队列身份绑定的Scope；取消记录首个原因，
原子取消该scope尚未提交的排队、Pending和执行中命令，拒绝后续入队。
其他scope和无scope命令继续执行。取消不占命令槽，满队列时仍可进行。
执行中任务被取消后，调用者可以退出；其预算保留到核心线程完成迟到结果清理。
已提交但未领取的成功结果不被取消覆盖。队列不维护scope注册表。
运行器提供独立scope创建/取消及executeCommand关联，不改变会话停止路径。

先加入占位取消实现，端点取消隔离测试真实red，等待线程仍存活，exit1：
TEMP/diplay-command-scope-red.log。实现后11项队列测试通过。
运行器未转发取消时再次red：endpoint cancellation must wake its pending caller；
diplay-command-scope-runner-red.log。接入后队列11项、运行器14项全部通过，
零失败/错误/跳过：diplay-command-scope-runner-green.log。

上述Scope只取消等待者，不关闭native端点。仍需关闭控制命令、类型化缓冲的
复制前准入、公共UDP/TCP和会话集成。实际NCM/iPhone/ARM与G2/G3门槛不变。

完整九项Android CI exit0，7m48s，250任务44执行：diplay-command-scope-ci.log。
JUnit共3316项（shared1732/common1578/home6），零失败、错误、跳过。
最终APK四ABI/16KiB/NX/许可证及源码摘要检查通过，SHA-256：
0d7a5c359ee1af807a02d37c56d4065daf73961bfa946173ecd2af9f07804cb6。
公开树检查通过。此批没有修改JNI/native，未重跑设备测试或宿主native套件；
不将历史设备结果记录为本批新证据，也未取得独立评审完成结果。

### P3 预留核心线程资源关闭配额（2026-10-22）

基于7e5a160a。CoreCommandQueue新增最多80个资源scope控制登记，覆盖OPEN、
排队关闭、执行释放阶段。创建时预留控制槽和有界关闭队列，关闭不占业务队列。
closeResource取消scope等待者并最多投递一次释放，返回表示首次投递而非释放完成。
释放优先于普通命令，仍共享每轮32次预算；迟到业务结果在释放前完成discard。
普通cancel仍只取消等待者，随后可以显式投递释放。结束后删除登记和回调。
全会话停止丢弃未执行回调，由原有上下文销毁屏障释放native资源；运行中回调
继续占槽直至返回。回调失败不重试，传给运行器触发整上下文停止。
Snapshot增加resourceScopes/closingResourceScopes，不当作native存活数。

资源限额占位实现真实red，未按限额拒绝登记，exit1：
TEMP/diplay-command-resource-red.log。实现后7项资源测试和11项队列回归通过。
运行器未转发关闭时red：diplay-command-resource-runner-red.log；接入后补测
核心线程执行、一次释放、会话继续运行和释放失败后的上下文销毁。

复核发现预分配早于配置校验。在128MiB测试JVM中，Int.MAX_VALUE资源限额的
期望IllegalArgumentException实际为OutOfMemoryError，真实red、exit1：
diplay-command-resource-config-red.log。将所有配置校验移至分配之前，最终资源8项、
队列11项、运行器16项共35项在同一128MiB JVM全部通过：
diplay-command-resource-final-green.log。运行器唤醒测试先等待核心到达长时间等待
边界，再投递关闭，不依赖启动竞争窗口。首轮完整CI通过后发生上述调整，其结果
不用于证明最终候选版本；以最终CI记录为准。

回调只允许捕获有界端点标识/元数据并非阻塞执行；通用接口不检查lambda负载大小。
此批尚未将公共UDP端点与native release接通，不宣称真实UDP端点关闭已验收。
复制前缓冲准入、公共UDP/TCP、会话集成及实际设备门槛仍未完成。

最终九项Android CI exit0，7m27s，250任务40执行：
diplay-command-resource-final-ci.log。JUnit共3326项（shared1742/common1578/home6），
零失败、错误、跳过。最终APK四ABI/16KiB/NX/许可证及源码摘要检查通过，SHA-256：
4e919976e61c4d932a4aa7c6f4218c361175b99786fe406ce7ebbe194a8987f3。
公开树及staged diff检查通过。此批JNI/native未修改，未重跑设备或native宿主套件；
没有取得独立评审完成结果，不记作独立评审通过。

### P3 复制前命令准备准入（2026-10-22）

基于5870ca90。callPrepared先预留命令数与声明字节，再在调用线程运行有界Java
prepare；PREPARING期间仍占额度，不持队列锁，不可访问native或执行阻塞I/O。
成功准备且未终止才发布核心操作；准备期间取消/超时不能提前归还仍使用中的额度。
资源关闭控制仍可执行，准备回调退出后拒绝发布已取消操作。普通call沿同一状态机。
ready队列预分配至命令上限；退休时先清空操作闭包，再归还额度。
准备阶段OOM映射RESOURCE_LIMIT，中断恢复标志并返回CANCELLED；首个终止原因保留。
运行器executePreparedCommand在准备之前检查运行状态/调用线程。

占位实现先执行prepare再入队，真实red：准备中snapshot.commands期望1实际0，
exit1：TEMP/diplay-command-preparation-red.log。实现后修正测试的调用线程：
pump线程不能同步发起阻塞调用，预算拒绝检查改由独立业务线程执行。
运行器占位入口过早执行prepare也真实red：未启动时准备次数期望0实际1，exit1：
diplay-command-preparation-runner-red.log。接入后准备8项、资源8项、队列11项、
运行器17项共44项通过：diplay-command-preparation-runner-green.log。
覆盖准备线程、核心线程、分配前数量/字节限额、准备期间端点关闭/全上下文停止、
deadline、两种中断、注入OOM及普通异常后预算恢复，不耗尽宿主物理内存。

通用组件仍依赖调用者准确申报并在prepare内创建拥有型缓冲，不分析闭包内存大小。
普通call预先捕获的负载不自动受复制前准入保护；公共UDP/TCP适配与native端点
尚未接通此入口，G2/G3和实际设备证据仍未完成。

完整九项Android CI exit0，7m9s，250任务44执行：
diplay-command-preparation-ci.log。JUnit共3335项（shared1751/common1578/home6），
零失败、错误、跳过。最终APK四ABI/16KiB/NX/许可证及源码摘要检查通过，SHA-256：
6902ca21661ee7514d7d9c3bb25394c74d780922205fc4facfd35321bbd869e4。
公开树及staged diff检查通过。此批JNI/native未修改，未重跑设备或native宿主套件；
未取得独立评审完成结果，不记作独立评审通过。

### P3 公共UDP合同适配与native贯通（2026-10-22）

基于c81cf197。新增LwipUdpEndpoint实现UdpEndpoint，显式绑定原型运行器；
LwipUdpCore定义核心线程非阻塞接口，NativePrototypeCore复用ABI2 JNI实现。
IPv6地址与虚拟netif一致，返回实际绑定端口/会话linkId；同方向并发拒绝，允许收发
并行。发送经准备准入后复制，接收使用私有缓冲和来源元数据，超时/取消不写用户
目标。关闭经预留资源队列一次释放token，失败绑定和迟到结果同样回收。
完整SessionNetwork/TCP和生产后端选择未接入，详细边界见[UDP适配](udp-adapter.md)。

新增callPreparedDeliver，使调用线程复制接收结果期间仍保留预算；finally退休，
交付异常也回收，已提交成功在并发close后仍交付。旧“先await再deliver”实现真实
red：diplay-udp-adapter-delivery-red.log；不是拿早先的队列测试代替这条新合同。
UDP占位工厂真实red BACKEND_INIT：diplay-udp-adapter-red.log。
原生核心尚未实现UDP接口时，实际Android适配路径red INTERNAL：
diplay-udp-adapter-native-red.log。接通后首轮设备7/7，13s：diplay-udp-adapter-native-green.log。
设备用例以有效NDP/IPv6/UDP帧，覆盖实际端口/来源、截断、空包、输出内容、关闭
等待者、native UDP回到echo监听器基线和同端口重绑。

复核未启动bind会短暂占资源scope，测试期望0实际1真实red：
diplay-udp-adapter-start-red.log；修为登记前检查运行状态。
绑定注入OOM原先暴露原始OOME并停止运行器，真实red：diplay-udp-adapter-oom-red.log。
适配边界及核心侧元数据构造映射RESOURCE_LIMIT，失败scope回收后运行器可继续绑定。
最终准备/交付10项、资源8项、队列11项、运行器17项、UDP8项共54项通过：
diplay-udp-adapter-final-green.log。未耗尽物理内存来测试OOM。

本批未改C/JNI ABI，未宣称全部65527字节边界、媒体过期策略或完整T01–T08。
完整TCP/SessionNetwork、会话集成和实际NCM/iPhone/ARM门槛仍待完成，G2/G3未通过。

最终九项Android CI exit0，7m42s，250任务44执行：diplay-udp-adapter-ci.log。
JUnit共3345项（shared1761/common1578/home6），零失败、错误、跳过。
最终Android connected 7/7，零失败、错误、跳过，15s：
diplay-udp-adapter-final-connected.log。任务自有API28 x86_64模拟器debug.checkjni=1，
测试进程5293日志确认Not late-enabling -Xcheck:jni (already on)。
AVD路径DeviceMonitor仍有bad sub-command告警，测试正常完成并核对XML。
最终APK四ABI/16KiB/NX/许可证及源码摘要检查通过，SHA-256：
05a7398d3ef919c7c4fef8072f62e842067bb2484e1159479e915258f758e90d。
公开树及staged diff检查通过。C代码未修改，未重跑native宿主sanitizer套件。
未取得独立评审完成结果，不记作独立评审通过。上述日志均位于任务主机TEMP目录。

### P3 native TCP监听/接收与窗口背压（2026-10-22）

基于c3262aea。新增diplay_tcp.h/tcp_endpoint.c，提供核心线程非阻塞listen/accept/
read及本地abort关闭；DP_PEER_CLOSED/RESET区分FIN和RST。真实IPv6及端口元数据
沿用generation注册表；未领取子连接归listener，领取后独立注册，释放不递归进入
注册表。接收保留有界pbuf，不在回调或poll中tcp_recved；read释放已消费前缀后才
恢复这些字节的窗口。libc元数据加入原有分配统计，core销毁检查TCP接管计数归零。
细节及尚未覆盖范围见[native TCP接收](native-tcp-receive.md)。

新增前三项真实帧测试在DP_INTERNAL占位实现上全部red，旧33项通过，exit8：
WSL /tmp/diplay-tcp-rx-red.log。实现后36项green，再补8轮生命周期与启动前监听，
最终Linux ASan/UBSan/pool guards、Windows MinGW各37/37。
日志WSL /tmp/diplay-tcp-rx-final-host.log、TEMP/diplay-tcp-rx-final-windows.log。
窗口用例填满65535字节后核对实际ACK窗口为0，注入时间前进10秒不自动恢复；
读取32768字节后窗口重新开放，最终65535字节完整且顺序一致。
另测16监听/32连接耗尽、原连接仍可用、未领取数据/RST回收、SYN_RCVD关闭、
FIN/RST前缓存及旧generation拒绝。Android API28 x86_64独立frame_tests四项PASS；
补充未就绪监听后重新运行tcp_endpoint_limits也PASS，不依赖root/NCM。
Android原生构建日志TEMP/diplay-tcp-rx-android-build.log。

生产候选九项Android CI exit0、40s、250任务17执行：diplay-tcp-rx-ci.log。
只扩展宿主测试的未就绪用例后，最终九项CI再次exit0、3s、250任务12执行：
diplay-tcp-rx-final-ci.log。JVM源码未修改，单元任务UP-TO-DATE，核对既有XML共3345项
（shared1761/common1578/home6），零失败、错误、跳过，不声称这3345项都重新执行。
JNI/UDP设备回归7/7，零失败、错误、跳过，10s：diplay-tcp-rx-connected.log。
任务自有模拟器debug.checkjni=1，进程4435日志确认already on；DeviceMonitor的AVD
路径告警未影响测试完成。四ABI均确认五项dp_tcp_*动态导出；APK四ABI/16KiB/NX/
许可证与原始源码摘要检查通过，SHA-256：
8c1c0d8455a07552ddac422a2cd2daf96cfd12ca3050b209913217b99cb467d4。

尚无TCP write/connect、优雅关闭/shutdownOutput、TCP JNI及公共TcpStream；本批
abort原语不代表TcpOptions默认关闭语义完成。未取得完整公共两后端合同、真机
NCM/iPhone/ARM或全部长稳/窗口矩阵证据，G2/G3仍未通过；整体目标保持未完成。
未取得独立评审完成结果，不记作独立评审通过。

### P3 native TCP短写与输出半关闭（2026-10-22）

基于7ca7e41c。新增dp_tcp_write与dp_tcp_shutdown_output，复制可接受前缀、按ACK
恢复发送空间。已复制后的输出暂不可用不再返回零字节WOULD_BLOCK，避免上层重复
提交；致命LINK_IO可以伴随非零copied，与失败可能已发送前缀的合同一致。
32个snd_buf总量编译期限制在2MiB发送负载预算内，未增加大块libc发送缓存。
半关闭只禁止发送，保持接收，已接受的关闭意图幂等；段池不足时由栈延后FIN。

补齐半关闭PCB所有权：ERR_CLSD表示正常结束，不作为reset。TIME_WAIT可能被计时器
或分配器释放而无err回调，core维护最多32条应用记录的有界链表，dp_leave在raw
操作返回后清除TIME_WAIT上的应用回调及记录引用，保留协议TIME_WAIT。应用接管的
pbuf和generation记录独立存活，读取/关闭不会访问已回收或复用给新连接的PCB。
core销毁同时检查记录链表归零；私有注册表的自定义TCP测试值不参与这条记录链。

write/half-close占位实现真实red：新增两项失败、旧37项通过，exit8：
WSL /tmp/diplay-tcp-write-red.log。实现后39项通过，再补暂不可用/致命输出和固定段池
耗尽测试。最终Linux ASan/UBSan/pool guards及Windows MinGW各41/41：
WSL /tmp/diplay-tcp-write-final-host.log、TEMP/diplay-tcp-write-final-windows.log。
40320字节测试覆盖32768短写、缓冲满、ACK恢复、独立复制和完整顺序；FIN测试覆盖
本地先关、远端先关、同时FIN及RST，重复FIN得到ACK，虚拟时间前进121秒后创建新
连接，再读取旧缓存/关闭旧句柄且新连接仍正常。固定TCP_SEG池耗尽验证延后FIN及
压力期间取消，不通过耗尽物理内存造故障。
Android API28 x86_64独立frame_tests的tcp_write/tcp_half_close/tcp_write_io/
tcp_shutdown_pressure全部PASS；构建日志TEMP/diplay-tcp-write-android-build.log。

九项Android CI exit0，13s，250任务17执行：diplay-tcp-write-ci.log。
JVM未修改，单元任务UP-TO-DATE；核对既有XML3345项（shared1761/common1578/home6）
零失败/错误/跳过，不记为全部重新执行。JNI/UDP设备回归7/7，17s、exit0：
diplay-tcp-write-connected.log，XML零失败/错误/跳过。自有模拟器debug.checkjni=1，
测试进程4092日志确认already on；既有AVD路径告警未影响执行。
四ABI均确认七项dp_tcp_*动态导出，APK四ABI/16KiB/NX/许可证与源摘要检查通过，
SHA-256：1999d3ee65f7a75e86f1a3029536c503fb684977f9466816b23258d627d6d347。

此批不等同于完整优雅close策略，仍缺主动connect、TCP JNI、公共TcpStream/
SessionNetwork及完整两后端契约；独立内核peer、实际NCM/iPhone/ARM与长稳门槛
仍未完成，G2/G3未通过。未取得独立评审完成结果，不记作独立评审通过。

### P3 native TCP主动连接（2026-10-22）

基于585be2be。新增connect begin/connect_status，候选与已连接端点共用generation
注册、32条记录/PCB上限和原有回调/接收/关闭。链路或DAD未就绪时返回WOULD_BLOCK、
无token；SYN入栈才发布候选，之后只查询同一token，握手完成后报告实际本地/远端
IPv6和端口。候选阶段write/shutdown_output不接受数据。正常紧接FIN仍保留已完成
握手事件，RST及协议重试耗尽报告PEER_RESET；公共deadline的TIMEOUT映射仍待适配。
SYN分配失败、注册失败、致命输出失败回滚PCB/元数据/注册，不隐藏未交付候选；
暂时输出不可用保留原候选重传。取消和全上下文销毁均回收候选。

三项占位用例真实red、旧41项通过，exit8：WSL /tmp/diplay-tcp-connect-red.log。
实现后44项green，再补固定段池分配失败、迟到SYN-ACK、恢复重传和协议超时。
最终Linux ASan/UBSan/pool guards、Windows MinGW各45/45，日志：
WSL /tmp/diplay-tcp-connect-final-host.log、TEMP/diplay-tcp-connect-final-windows.log。
SYN/SYN-ACK/RST真实帧覆盖双方收发、地址端口、未就绪/DAD、握手中取消、旧token、
重建、32候选耗尽、固定TCP_SEG池故障和致命输出即时回滚；不耗尽物理内存。
Android API28 x86_64独立frame_tests的tcp_connect/tcp_connect_cancel/
tcp_connect_limits/tcp_connect_retry全部PASS；构建日志TEMP/diplay-tcp-connect-android-build.log。

九项Android CI exit0，10s，250任务17执行：diplay-tcp-connect-ci.log。
JVM源码未修改，单元任务UP-TO-DATE；核对既有XML3345项（shared1761/common1578/home6）
零失败/错误/跳过，不记为全量重新执行。JNI/UDP回归7/7，17s、exit0：
diplay-tcp-connect-connected.log，XML零失败/错误/跳过。自有模拟器debug.checkjni=1，
测试进程3721确认already on，既有AVD路径告警未影响测试。四ABI均确认九项dp_tcp_*
导出；APK四ABI/16KiB/NX/许可证与源摘要检查通过，SHA-256：
c45c6c49084d1db1abb14c93b9b0a6fed128f970a3f5105099c4187cccef91b3。

仍缺TCP JNI、公共TcpStream/SessionNetwork、完整优雅关闭策略、独立内核peer对新
端点API的验证与完整两后端契约。实际NCM/iPhone/ARM、长稳及G2/G3仍未完成，整体
目标不标完成。未取得独立评审完成结果，不记作独立评审通过。

### P3 TCP JNI与ABI 3（2026-10-22）

基于a854e5a5。新增tcp_jni.c及prototype转发，九项TCP接口包括listen/accept/
connect/status/read/write/shutdown_output/两类close。新增方法使整体JNI ABI升级3，
Kotlin加载检查及native返回值同步；native-only核心C ABI仍为1。共享原有65527字节
复制区，helper改名copy_buffer，不增大prototype预算或JNI栈数组，不暴露裸指针。
数组形状、地址别名和范围在改变端点/消费前校验；Region调用后检查异常，发布新
handle失败用纯C回收；读写已发生后发布失败不重放。细节见[TCP JNI桥](tcp-jni.md)。

Android模拟器测试先缺tcpListen实现而red，UnsatisfiedLinkError，exit1、36s：
TEMP/diplay-tcp-jni-red.log。首次接通后设备8/9通过，剩余在失败context关闭候选时
期望0实际LINK_IO（-9），exit1：diplay-tcp-jni-green.log。原型已失败仍尝试输出RST
导致额外失败；修为失败context先停输出，核心已失败关闭同样禁止继续发送。
修复后模拟器9/9，12s：diplay-tcp-jni-fixed-connected.log。

新增Android用例覆盖被动/主动握手、实际地址/端口、偏移/零长度、溢出/负范围、
无效输出与地址别名不消费、FIN/RST、100000字节请求短写、复用scratch后重传内容、
错误线程/类型、重复close、失效handle、新context及失败context清理。
宿主prototype生命周期补转发接口关闭与重建，Linux ASan/UBSan/pool guards和Windows
最终各45/45：WSL /tmp/diplay-tcp-jni-host.log、TEMP/diplay-tcp-jni-windows.log。

首轮完整CI在既有SettingsBackupServerTest准备六个空闲连接时出现Connection refused，
8m19s、exit1：TEMP/diplay-tcp-jni-ci.log。单独测试类通过；独立Java探针在backlog4、
未accept时复现第5次连接被拒绝而监听仍bound。仅修改测试准备：有限重试瞬时
ConnectException，失败也清理已建socket；生产服务和请求断言不变。修正后该类通过，
9s：diplay-tcp-jni-backup-fixed.log。

最终九项Android CI exit0，6m10s、250任务15执行：diplay-tcp-jni-final-ci.log。
本轮common单元任务重新执行；核对XML共3345项（shared1761/common1578/home6），
零失败/错误/跳过，UP-TO-DATE任务不记为重新执行。最终API28 x86_64模拟器JNI回归
9/9，13s、exit0：diplay-tcp-jni-final-connected.log，XML零失败/错误/跳过。
debug.checkjni=1，测试进程5876确认already on；既有AVD路径告警未影响测试。
四ABI均确认九项TCP JNI导出；APK四ABI/16KiB/NX/许可证与源摘要检查通过，SHA-256：
a3523b57a0149ba34b52a71af4e3a5f5f2694cc9e374b886d99bff998f7cbb49。
public tree及staged diff检查通过。未取得独立评审完成结果，不记作独立评审通过。

尚无Java输出发布异常的运行时注入，不把检查代码和正常CheckJNI视为全部异常
路径证据。TCP公共适配、完整SessionNetwork/优雅关闭/两后端契约、实际NCM/iPhone/
ARM与长稳仍未完成，G2/G3未通过。

### P3 TCP类型化宿主核心（2026-10-22）

基于a8b1caa5。新增LwipTcpCore，NativePrototypeCore实现listen/accept/connect/
status/read/write/shutdown_output/两类close，向宿主命令返回类型化端点信息及
CoreAttempt。connect候选与握手完成分开；Pending不阻塞核心；read缓冲耗尽后将
PEER_CLOSED映射EOF(-1)，PEER_RESET及写关闭映射结构化错误；write仍报告已接受
前缀而非公共全量写入。详见[TCP宿主核心接口](tcp-core.md)。

创建/接受成功但Kotlin结果构造失败时回收未交付token。普通调用补时钟/熵，TCP关闭
跳过supply，使失败context仍能释放资源；创建线程/context有效性检查仍保留。
不改变原始close的abort语义，不宣称公共默认优雅关闭、liveness或deadline完成。

新增模拟器集成用例在缺实现时ClassCastException真实red，exit1、58s：
TEMP/diplay-tcp-core-red.log。接通后10/10通过，exit0、12s：diplay-tcp-core-green.log。
通过完整IPv6/TCP帧验证空accept、未就绪connect、地址/端口、先读缓冲再EOF、
零长度读、输出关闭后的PEER_CLOSED、主动握手查询、100000字节请求短写32768、
发送缓冲满Pending、RST与时钟倒退后仍可关闭stream/listener。

最终九项Android CI exit0，7m12s、250任务46执行：diplay-tcp-core-ci.log；shared/
common单元任务本轮执行，home为UP-TO-DATE。核对XML共3345项（shared1761/common1578/
home6），零失败/错误/跳过；不将home旧结果记为重新执行。
最终API28 x86_64模拟器10/10，15s、exit0：diplay-tcp-core-final-connected.log，
XML零失败/错误/跳过；debug.checkjni=1，测试进程5137确认already on。
APK四ABI/16KiB/NX/许可证与源摘要检查通过，SHA-256：
8c17927a9a37f895a5c0e20df140c3267c91205982762d0271909de800dccf8b。
原生C未修改，未重跑宿主45项；不以旧结果当作本轮重新执行。public tree及staged
diff检查通过。未取得独立评审完成结果，不记作独立评审通过。

Kotlin结果分配失败与全部JNI异常路径尚无动态注入；公共TcpStream/TcpListener/
SessionNetwork、完整两后端契约、默认优雅关闭、实际NCM/iPhone/ARM及长稳仍待完成。
没有设备或基线报告，G2/G3不变，整体目标仍未完成。

### P3 TCP优雅关闭原语（2026-10-22）

基于61ba81ba。新增dp_tcp_close_graceful、prototype/JNI/类型化宿主转发。整体JNI
ABI升级4，native-only核心C ABI仍为1；既有abort接口不变。正常关闭先移除全部应用
回调，再交由tcp_close接管PCB；成功后不再访问PCB，立即失效handle/释放应用记录。
TF_CLOSEPEND、未ACK数据与TIME_WAIT继续受lwIP固定池限制，由协议ACK/超时或context
销毁释放。未读数据遵循lwIP的RST行为；候选取消与失败上下文走abort。详见
[TCP优雅关闭原语](tcp-graceful-close.md)，公共TcpStream策略尚未接入。

两项新宿主用例先以旧abort行为真实red，45/47通过、exit8：
WSL /tmp/diplay-tcp-graceful-red.log。实现后47/47通过；加入失败/取消路径成为48项。
首次补测误认为待握手取消不发RST，源码确认tcp_abort会发RST后纠正测试；另补
输出恢复用例，先遗漏setLinkReady(true)通知而失败，按既有宿主恢复流程补齐。
最终Linux ASan/UBSan/pool guards、Windows MinGW均48/48、exit0（9.48s/4.91s）：
WSL /tmp/diplay-tcp-graceful-final3-host.log、TEMP/diplay-tcp-graceful-final3-windows.log。
覆盖发送数据复制重传、FIN次序/段池压力/输出恢复、旧handle和PCB复用、未读数据RST、
候选取消、核心时钟/输出失败、FIN重试前销毁及重建后资源计数归零。

Android新增原始JNI用例先缺tcpCloseGraceful而UnsatisfiedLinkError真实red，exit1、
18s：diplay-tcp-graceful-jni-red.log；接通后11/11，16s、exit0：
diplay-tcp-graceful-connected.log。既有类型化核心用例也改用优雅关闭，含失败prototype。

最终九项Android CI exit0，7m32s、250任务47执行：diplay-tcp-graceful-ci.log。
shared/common单元任务重新执行，home为UP-TO-DATE；核对XML3345项（shared1761/
common1578/home6）零失败/错误/跳过，不将home旧结果记为本轮重新执行。
最终API28 x86_64模拟器11/11，13s、exit0：diplay-tcp-graceful-final-connected.log，
XML零失败/错误/跳过；debug.checkjni=1，测试进程6225确认already on。
四ABI均确认十项TCP JNI导出；APK四ABI/16KiB/NX/许可证与源摘要检查通过，SHA-256：
fe4df497334e1276fd1af29222d45b364400f5f55c181ad84acefa2113c0fbb2。
public tree及staged diff检查通过。未取得独立评审完成结果，不记作独立评审通过。

尚无tcp_close返回错误分支的运行时注入；公共流/监听器/SessionNetwork策略、全部
两后端契约、独立kernel peer对新端点接口的验证、真实NCM/iPhone/ARM及长稳仍缺。
不把关闭原语的已验证路径扩大为完整公共关闭合同；G2/G3及整体目标仍未完成。

### P3 公共TCP主动连接流（2026-10-22）

基于81e9c7fd。新增LwipTcpStream实现公共TcpStream，显式工厂经runner创建/等待
同一candidate，实际地址/端口及linkId传至公共端点。读写按方向排他，可一读一写；
整段write按最多32768字节的队列副本处理原生短写，保持同一绝对deadline。资源
scope先预留，未发布候选取消走abort，已发布流默认优雅关闭、resetOnClose走abort。
配置通过owner队列，关闭幂等且使用保留控制槽。详见[公共TCP流原型](tcp-adapter.md)。

读取目标只在提交后由调用线程写入；已消费读取的迟到结果不能交付时关闭流。
另补注入“消费后OOM”用例，确认出错后必须回收流，防止静默跳过字节。初修复发现
关闭的CANCELLED覆盖原错误，改为resource close携带原结构化原因，保留RESOURCE_LIMIT/
PEER_RESET；原有UDP默认取消行为不变。Pending就超时/中断则不关闭流，仍可继续读。

公共流最初9项以未实现方法真实red：TEMP/diplay-tcp-stream-red.log。接通后通过，
新增前缀写后超时不重放、中断及消费后分配失败用例。最后TCP12项，与UDP/命令队列
共49项定向测试通过：diplay-tcp-stream-verified-focused.log。消费后OOM先red：
diplay-tcp-stream-read-error-red.log；中间修复的错误覆盖失败也有保留日志
diplay-tcp-stream-final-focused.log，不把中间失败记为通过。

新增PCB keepalive配置及JNI/宿主接口，整体JNI ABI升至5，核心C ABI仍为1。该PCB
启用idle10000ms/interval3000ms/count3，不访问系统FD，不宣称TCP_USER_TIMEOUT。
原生探测用例先无实际配置而red，48/49通过、exit8：WSL /tmp/diplay-tcp-liveness-red.log。
实现后Linux ASan/UBSan/pool guards、Windows各49/49、exit0（4.93s/1.23s）：
WSL /tmp/diplay-tcp-stream-host.log、TEMP/diplay-tcp-stream-windows.log。真实探测帧
覆盖健康空闲连接反复ACK保持可用、停止回复后PEER_RESET，不以视频/应用空闲判失败。

Android公共流用例先缺tcpConfigureLiveness而NATIVE_UNAVAILABLE/UnsatisfiedLinkError
真实red，exit1、39s：diplay-tcp-stream-jni-red.log。接通后12/12、18s、exit0：
diplay-tcp-stream-connected.log。经公共connect/runner/JNI/lwIP走完整SYN/SYN-ACK、
实际端点、keepalive配置、偏移read/EOF、write及FIN关闭，资源scope归零。

最终九项Android CI exit0，7m24s、250任务46执行：diplay-tcp-stream-ci.log。
shared/common单元任务本轮执行，home为UP-TO-DATE；核对XML共3357项（shared1773/
common1578/home6）零失败/错误/跳过，不将home旧结果记作重新执行。最终API28
x86_64模拟器12/12、13s、exit0：diplay-tcp-stream-final-connected.log，XML零失败/
错误/跳过；debug.checkjni=1，测试进程7286确认already on。
补齐prototype转发后的最终宿主回归也各49/49、exit0（Linux5.01s/Windows1.25s）：
WSL /tmp/diplay-tcp-stream-final-host.log、TEMP/diplay-tcp-stream-final-windows.log。
四ABI均确认十一项TCP JNI导出；APK四ABI/16KiB/NX/许可证与源摘要检查通过，SHA-256：
336d587256ba2084206ca0c4cc46e97c18b3a6a5502a99a41587d1e317f80b04。
public tree及staged diff检查通过。未取得独立评审完成结果，不记作独立评审通过。

仍缺公共监听器、完整SessionNetwork、两后端参数化合同、全部JNI/分配/竞态故障矩阵、
独立kernel peer对新端点接口的验证、真实NCM/iPhone/ARM及长稳性能报告。只提供
显式原型主动流，不接入生产业务或改变后端选择；G2/G3与整体目标仍未完成。

### P3 公共TCP监听器（2026-10-22）

基于bddad5a9。新增LwipTcpListener，实现显式bind/accept，绑定具体本地IPv6或通配
地址，报告实际端口/地址和linkId。backlog为正，公共默认50按原生上限截到32。
新增只读tcpLocalAddress，NativePrototypeCore在owner线程返回防御复制；不改C/JNI，
整体JNI ABI仍5、核心C ABI仍1。详见[公共TCP监听原型](tcp-listener-adapter.md)。

每监听器最多一个accept，命令以监听器scope取消；先预留独立子流scope再调用native
accept，槽位耗尽不消费连接。成功/晚到/失败的token都由该scope持有；未发布子流
abort，已发布流默认优雅关闭。与主动连接抽取共用lease scope和发布逻辑，保持同一
读写/配置/关闭策略。关闭listener取消等待调用及未领取子项，已接受流仍独立工作。

最初10项用例以未实现方法真实red，exit1、15s：TEMP/diplay-tcp-listener-red.log。
实现后与既有流/UDP/命令队列59项通过，10s：diplay-tcp-listener-green.log。
补运行中accept关闭/端口复用用例；显式端口元数据校验先red，6s：
diplay-tcp-listener-port-red.log，修复后12项TCP监听器与其他定向测试共61项通过，
7s、exit0：diplay-tcp-listener-focused.log。覆盖资源槽预留、超时/中断/关闭竞态、
晚到子流回收、元数据/OOM失败、监听器关闭后子流可用及scope计数归零。

Android模拟器13/13，30s、exit0：diplay-tcp-listener-connected.log。新增公共bind/
accept用例经runner/JNI/lwIP走真实SYN/SYN-ACK/带数据ACK，验证动态端口/双方端点、
关闭listener后已接受流read/write、默认FIN及scope归零。其余TCP/UDP/JNI用例回归。

最终九项Android CI exit0，7m28s、250任务44执行：diplay-tcp-listener-ci.log。
shared/common单元任务重新执行，home为UP-TO-DATE；核对XML共3369项（shared1785/
common1578/home6）零失败/错误/跳过，不将home旧结果记作本轮执行。定向类计数61项。
最终API28 x86_64模拟器13/13，15s、exit0：diplay-tcp-listener-final-connected.log，
XML零失败/错误/跳过；debug.checkjni=1，测试进程8171确认already on。
APK四ABI/16KiB/NX/许可证与源摘要检查通过，SHA-256：
93bf0ccf3b7fe0081bcfbe7dcf19b8ffdac9fde423bbd3887a10f9a422ddf7a8。
C/JNI未修改，未重跑宿主49项，不把此前结果当作本轮重新执行。public tree及staged
diff检查通过。未取得独立评审完成结果，不记作独立评审通过。

仍缺完整SessionNetwork（包括reuseAddress策略及隔离原型echo服务）、两后端参数化
合同、全部分配/异常/竞态矩阵、独立kernel peer对新端点接口的验证、真实NCM/iPhone/
ARM及性能长稳。保持显式原型入口，未迁移生产业务；G2/G3及整体目标仍未完成。

### P3 显式lwIP会话与地址复用（2026-10-22）

基于04c4a5f9。新增LwipSessionNetwork实现公共会话接口，组合现有TCP/UDP适配，
校验IPv6与linkId、共享runner单调时钟。会话关闭取消accept/connect/read/write/
UDP send/receive及候选，依赖owner的context销毁屏障统一释放；重复close可继续
有界join。启动失败关闭已接管runner并保留错误。native工厂以echoServices=false
创建核心，不占用原型TCP7000/UDP7001；nativeEcho及旧create默认保留echo模式。
详见[显式会话原型](session-network-adapter.md)，生产后端选择/业务未迁移。

reuseAddress透传至新增原生绑定选项；SO_REUSE=1、bind前设置SOF_REUSEADDR，旧
C/JNI监听方法默认false不变。listen转换阶段ERR_USE修为ADDRESS_IN_USE，防止
重复活动监听误报资源上限。新增prototype/JNI方法，整体ABI6、核心C ABI1。
按既有审计要求增量检查TCP bind/listen/connect/继承/匹配及UDP条件分支；未启用
SO_REUSE_RXTOALL，UDP端点不设置复用标志。增量记录见upstream-audit.md，不宣称
完整安全审计通过。

原生复用用例先复用标志无效果而red，49/50通过、exit8：
WSL /tmp/diplay-session-reuse-red.log。实现后检查双方复用的存活子连接、旧监听已
复用的TIME_WAIT按新开关允许/拒绝、重复活动监听、非法选项及prototype转发。
另补复用后新旧连接分流，实际新连接收取自己的数据，旧流仍独立。最终Linux
ASan/UBSan/pool guards、Windows各50/50，exit0（4.74s/1.18s）：
WSL /tmp/diplay-session-final2-host.log、TEMP/diplay-session-final2-windows.log。

会话4项用例先未实现方法red，exit1、17s：TEMP/diplay-session-red.log。首次接通
发现旧位置时钟参数调用需同步，修正后会话/TCP/UDP/命令队列65项定向通过，
35s、exit0：diplay-session-focused-fixed.log。覆盖会话身份/时钟、复用参数、非法
端点、启动失败port关闭、六类等待取消及上下文只销毁一次。

Android显式会话先缺tcpListenWithOptions而NATIVE_UNAVAILABLE/UnsatisfiedLinkError
真实red，exit1、55s：diplay-session-jni-red.log。接通后14/14，15s、exit0：
diplay-session-connected.log。验证真实native会话可绑定原型echo端口、重复活动
监听为ADDRESS_CONFLICT、close取消accept/UDP等待；既有真实TCP/UDP帧用例回归。

最终九项Android CI exit0，7m37s、250任务46执行：diplay-session-ci.log。
shared/common单元任务本轮执行，home为UP-TO-DATE；核对XML共3373项（shared1789/
common1578/home6）零失败/错误/跳过，不将home旧结果记作本轮执行。定向类计数65项。
最终API28 x86_64模拟器14/14，18s、exit0：diplay-session-final-connected.log，
XML零失败/错误/跳过；debug.checkjni=1，测试进程9315确认already on。
四ABI均确认十二项TCP JNI导出；APK四ABI/16KiB/NX/许可证与源摘要检查通过，SHA-256：
45316a0e1ca9bbaf46386ded062100bd9c0ff2862da6b965346495c144873ef8。
public tree及staged diff检查通过。未取得独立评审完成结果，不记作独立评审通过。

仍缺完整两后端参数化合同、全部分配/引用/竞态故障矩阵、独立kernel peer对新端点
API验证、真实NCM/iPhone/ARM、性能长稳及生产通道/选择/回退集成。接口组合已接通，
不将已覆盖路径扩大为全部会话合同验收；安全审计、G2/G3及整体目标仍未完成。

### P3 新端点API的独立内核peer与timestamp修正（2026-10-22）

基于51aa57af。lwip_kernel_echo新增--endpoints模式，使用公开TCP/UDP端点API的
有界应用pump，不启动原始echo回调；独立kernel_peer不包含/链接lwIP或帧助手，
新增内核9000服务验证native主动连接、16字节challenge、输出半关闭、回复及EOF。
被动TCP/UDP及慢消费沿用原有独立内核客户端断言。应用缓存98664字节，编译限制
128KiB；原有传输/观测对象另限512KiB。见[内核peer验证](kernel-endpoint-peer.md)。

当前WSL root也被拒绝unshare且无/dev/net/tun，未绕过隔离或记作Linux入口通过。
使用任务自有API28 x86_64 root模拟器，脚本验证qemu/root、新旧namespace不同后
配置TAP/veth。占位服务先红测TCP Connection refused、exit1：
TEMP/diplay-endpoint-kernel-red.log。实现后TAP基本互通通过；veth显示内核stage=accept、
native requested=0，确认单次启动指令未交付：diplay-endpoint-kernel-veth-diagnostic.log。
控制协议改显式READY ACK/有限重试并幂等保留同一候选，veth及十秒暂停恢复通过。

Android ASan/UBSan真实内核输入进一步发现vendor tcp_in.c:2003 timestamp字节143
左移24的signed-int UB，exit1：diplay-endpoint-kernel-asan-tap.log。新增真实parser
高位值0x0102038f/0x80000000/0xffffffff、全部12字节split及SYN/ACK更新用例，在
Linux UBSan同样red，49/50、exit8：WSL /tmp/diplay-endpoint-timestamp-red.log。
修为移位前显式u32_t，不改字节序。原始vendor清单SHA保持7882cdb8...，314文件
身份不变；精确累计post-image b121300a...并由校验器单列local_fixes，不冒充上游
提交或CVE。BACKPORTS.md/UPSTREAM.md及审计记录同步，完整安全审计仍未通过。

修复后Android ASan/UBSan及lwIP池检查通过以下独立内核场景：

- TAP basic：TCP3×64KiB，UDP0/1/1200/4096/8192，主动连接及半关闭；
  diplay-endpoint-kernel-asan-tap-fixed.log。
- veth slow：十秒暂停发送者仍背压，1048576字节完整有序恢复，零窗口样本169；
  diplay-endpoint-kernel-asan-slow-fixed.log。
- TAP faults-1：admitted/drop/delay/reorder/duplicate=247/2/245/21/2；
  diplay-endpoint-kernel-asan-faults1.log。
- veth faults-5：259/12/247/13/3；diplay-endpoint-kernel-asan-faults5.log。
- 旧echo TAP basic兼容回归：diplay-endpoint-kernel-asan-legacy.log。

以上均exit0，独立peer符号检查无dp_core/dp_tcp/dp_udp/tcp_input/pbuf_alloc定义。
Android明确detect_leaks=0，不声称LeakSanitizer扫描。Linux宿主ASan/UBSan/pool guards
及默认LSan的50/50通过，5.50s：WSL /tmp/diplay-endpoint-timestamp-green.log；Windows
MinGW50/50通过，3.02s：TEMP/diplay-endpoint-timestamp-windows.log。

复查源码纠正此前会话记录的echo UDP端口：实际为6000，先前写7001有误。当前
会话文档及设备用例改为绑定TCP7000/UDP6000，确实检查两个原型服务端口均空闲。
此前TCP7000的隔离证据仍有效，不把旧UDP7001绑定结果当作UDP echo隔离直接证据。

修复后的完整九项Android CI exit0，12s、250任务19执行：diplay-endpoint-kernel-final-ci.log。
三模块单元任务均UP-TO-DATE；核对既有XML3373项（1789/1578/6）零失败/错误/跳过，
不记为重新执行。最终API28 x86_64 JNI14/14，16s、exit0：
diplay-endpoint-kernel-final-connected.log，XML零失败/错误/跳过；debug.checkjni=1，
测试进程11059确认already on。APK四ABI/16KiB/NX/许可证及更新源摘要检查通过，SHA-256：
ab5659a402e45e643b14c19a519372e6ff18caff2b9c27a3d722101e39726073。
父namespace仍net:[4026531963]，无dp-lwip0/dp-peer0或遗留工具进程；shell语法、public
tree及staged diff检查通过。未取得独立评审完成结果，不记作独立评审通过。

这次原生API内核互通不替代Kotlin两后端完整合同；更多资源/关闭/畸形包矩阵、fuzz、
真实NCM/iPhone/ARM、性能长稳及生产集成仍缺。安全审计、G2/G3和整体目标不标完成。

### 两后端 Android 本地契约首批

新增SessionNetworkDeviceContractTest，同一参数化测试分别创建真实系统socket后端
与LwipSessionNetwork.native；不替换JNI或core。覆盖临时监听端口、重复绑定冲突、
关闭重绑、跨link地址拒绝、非法/到期deadline、会话关闭取消accept/UDP receive、
重复关闭及关闭后新端点拒绝。断开EthernetFramePort不提供网络peer，这批结果仅为
本地契约；起始栅栏也不证明关闭时所有调用都已进入底层等待。共同流量/背压夹具
及完整T01–T08仍待补齐。

API28 x86_64模拟器参数化6项与既有JNI14项合计20/20，零失败/错误/跳过，Gradle
exit0：TEMP/diplay-device-contract-connected.log。未在本轮重新设置/确认CheckJNI，
不追加该项证据。完整九项Android CI exit0，12s、250任务14执行：
TEMP/diplay-device-contract-final-ci.log；三个单元任务均UP-TO-DATE，不记作重新执行。
public tree及diff检查通过。本轮只增加测试和覆盖记录，生产选择保持原状。

### 两后端共同 UDP 流量合同

SessionNetworkDeviceContractTest增加第四项参数化用例。系统侧用真实IPv6 UDP
socket peer，JNI侧用确定性Ethernet peer经过真实LwipSessionNetwork及JNI/core。
共同断言接收0/1/1200字节、完整/短/零容量缓冲、source及original/copied长度、
未写缓冲不变、截断后下一报文边界；发送0/1/1200字节并拒绝65528字节。JNI捕获
真实multicast输出帧，验证IPv6/端口、负载和UDP校验和，不依赖邻居缓存。

API28 x86_64设备22/22（公共8项、既有14项）零失败/错误/跳过，exit0、11s：
TEMP/diplay-udp-contract-final-connected.log。完整九项CI通过：
TEMP/diplay-udp-contract-final-ci.log；单元任务均UP-TO-DATE，不记重新执行。
public tree及diff检查通过。本轮仅测试/文档变化，不宣称CheckJNI新证据或独立
kernel peer验证；UDP分片/上限、共同TCP/背压合同、故障矩阵及真机门槛仍缺。

### 两后端共同 TCP 字节与半关闭合同

新增TcpSessionDeviceContractTest：相同断言分别经过系统IPv6 socket及真实JNI会话。
发送固定种子65537字节，分1/32769/32767写入并使用非零offset；shutdownOutput后
读取peer响应，覆盖1字节/随机容量read、完整字节序列及SHA-256、EOF重复读取。
JNI peer只通过EthernetFramePort收发合法TCP帧，队列各64帧、收集数据上限128KiB，
FIN后逐段确认回显，无fake core或直接原生端点调用。该夹具不证明丢包恢复、窗口
压力、同时读写或独立协议实现的互通；独立kernel证据仍参见kernel-endpoint-peer.md。

首次模拟器启动有运行但ADB离线，目标进程后来确认已退出/控制台端口关闭，才以
no-snapshot-load重新启动。测试代码编译通过；首次设备24项中JNI握手3秒超时，
其余23项通过：TEMP/diplay-tcp-contract-connected.log。增加peer异常传播诊断和
显式header位移括号后，diagnostic、repeat、最终版三次均24/24；不能确认哪项与
首次超时相关，不宣称已定位或修复其根因。最终版明确要求首个read容量为1。
最终TEMP/diplay-tcp-contract-final-connected.log，exit0、23s，XML零失败/错误/跳过。
九项完整CI exit0：TEMP/diplay-tcp-contract-final-ci.log；单元任务均UP-TO-DATE，
不记作重新执行。public tree及staged diff检查通过。本轮仅测试/文档，生产选择不变。
仍缺共同TCP并发/背压/异常关闭、故障矩阵及真机证据，G2/G3及整体目标保持未完成。

### 共同 TCP 超时恢复与读取取消

TcpSessionDeviceContractTest新增两个后端相同断言：静默读取100ms超时且缓冲不变，
随后并发读与write/shutdownOutput收回4097字节；close取消已进入后端的读取、重复
close、缓冲不变及关闭后拒绝。通过2秒有界线程栈观察确认read已进入再写入或关闭；
peer只在FIN后回复，因此测试的读取不会提前成功。另新增每后端12次重复创建会话/
握手/空半关闭的诊断回归。wire轨迹最多20条，连接失败时附带runner快照和peer异常。

首次冷启动仅wait-for-device就启动Gradle过早：ActivityManager明确报测试包not
encryption aware，instrumentation进程未创建；取消失效调用也结束了同一启动树内的
模拟器。确认设备消失后单独启动新实例，并确认dumpsys user为RUNNING_UNLOCKED，
才重新运行。原始日志TEMP/diplay-tcp-errors-connected.log无测试结果，不计通过。
重新执行时新增合同通过，但旧大数据用例JNI握手再次3秒超时（27/28通过）：
TEMP/diplay-tcp-errors-final-connected.log。带轨迹diagnostic为28/28，加入12次重复
创建的最终handshake为30/30，exit0、24s，XML零失败/错误/跳过：
TEMP/diplay-tcp-errors-handshake.log。没有得到超时失败时的wire轨迹，根因仍未确认。

完整九项CI exit0：TEMP/diplay-tcp-errors-final-ci.log；三模块单元任务均UP-TO-DATE，
不记重新执行。public tree及staged diff检查通过。仅测试及文档变化，未更改生产行为。
背压、RST、写阻塞取消、故障矩阵及真机门槛仍缺，G2/G3及完整目标保持未完成。

### 共同 RST 合同及系统后端错误分类修复

新增peerResetIsDistinctFromEofTimeoutAndLocalCancellation，两后端peer收到首字节
后真实reset，读取必须为PEER_RESET且缓冲不变。系统peer使用SO_LINGER(0)，JNI
peer发合法RST/ACK。首轮red明确系统expected PEER_RESET but INTERNAL，同时两个
JNI连接超时：TEMP/diplay-reset-red.log。初版按Android errno原因链/SocketException
分类仍失败，追加cause诊断确认Android NIO抛无cause的普通IOException("Connection
reset by peer")：TEMP/diplay-reset-cause.log。因此补充该精确标准文本，保留绑定冲突
分类及本地关闭CANCELLED优先级，未知错误仍INTERNAL，原因链检查最多16层。
JVM真实NIO及adopted socket的SO_LINGER(0)回归通过：TEMP/diplay-reset-jvm.log。

本轮首次得到握手失败wire轨迹：无TCP SYN输出，只有IPv6控制帧，runner无故障。
测试peer原来只注入一次启动NS且忽略运行时NS；改为应答目标peerIp的实际NDP请求，
发送正确NA，移除启动一次注入。随后green/cause两轮JNI均无握手失败（系统RST尚红），
最终完整32/32、零失败/错误/跳过，exit0、25s：TEMP/diplay-reset-final-connected.log。
这补齐了确定的夹具缺口；有限重跑不证明所有历史超时均由此引起或满足长稳。

设备为自建API28 x86_64 AVD diplay-lwip-api28，无真实USB/iPhone；启动前确认用户
RUNNING_UNLOCKED。测试APK SHA-256：
d65be6d2fa4bba2b74b2a43033827c44e30774c39ee09934643a627259a1aba7。
完整九项CI exit0、7m33s、250任务43执行：TEMP/diplay-reset-final-ci.log。
最终XML shared1790/common1578/home6共3374项零失败/错误/跳过；任务执行情况按CI
日志记录，不将UP-TO-DATE任务记重新执行。public tree及staged diff检查通过。
本轮仅显式系统适配器错误分类及测试夹具变化，不切换生产后端；背压/写阻塞取消、
故障矩阵、真机及安全/性能验收仍缺，G2/G3和完整目标保持未完成。

### 共同写阻塞取消与同向并发拒绝

新增closeCancelsBackpressuredWriterAndRejectsOverlappingWrite，两后端执行同一
断言：重复使用32KiB源缓冲，最多1024次write。系统peer设置4096接收缓冲并暂停
读取；JNI peer通告零窗口、不消耗输入数据。五秒观察期内必须出现完成计数至少
500ms不变且writer在后端write内、future未完成；随后重叠write必须RESOURCE_LIMIT，
close两次后writer三秒内CANCELLED，源缓冲不变。夹具保持有界，系统peer清理时关闭
已接受socket并释放暂停门闩。无需生产代码修改。

自建API28 x86_64 AVD，确认RUNNING_UNLOCKED；本轮34/34，零失败/错误/跳过，
exit0、41s：TEMP/diplay-write-connected.log。测试APK SHA-256：
6efa913028e5ac45267968553b424b44523d77a6b47e8b7c86c0b43473d46306。
完整九项CI exit0、8s、250任务14执行：TEMP/diplay-write-final-ci.log。三模块单元
任务UP-TO-DATE，不记重新执行。public tree及staged diff检查通过。
此次覆盖T04的写取消及短期背压观察；十秒暂停后完整恢复及内存预算尚未验证，不
标T05完成。故障矩阵、真实NCM/iPhone/ARM及安全/性能门槛仍缺，整体目标继续。

### 共同十秒暂停与完整恢复

新增tenSecondReceivePauseBackpressuresThenRecoversAllBytes。系统peer暂停read，
JNI peer通告零窗口；发送者复用32KiB缓冲共256块，首次write完成后才计十秒暂停。
暂停末完成计数至少500ms不变且writer未完成，再恢复读取/发送正窗口ACK。全部
8MiB写完并shutdownOutput后，peer以固定40字节返回实际接收长度和增量SHA-256，
与发送者核对后EOF；不在夹具保留8MiB负载。JNI暂停及恢复过程每50ms抽样已有
snapshot，检查命令128项/256KiB、RX128帧/128KiB、TX128帧/192KiB边界与无runner
故障；响应读取时也检查。未测系统内核缓冲或原生/进程完整峰值，不标整个T05完成。

首轮36/36，添加首次write起点门闩后36/36，恢复阶段也抽样的最终36/36，均exit0。
最终TEMP/diplay-recovery-budget-connected.log，47s，XML零失败/错误/跳过；自建
API28 x86_64 AVD、RUNNING_UNLOCKED，无真实USB/iPhone。测试APK SHA-256：
c09ed14b3eaf2ae6d99c7eb46e062c8f13e921ce7fcd7cd152b4bc66f45b979b。
完整九项CI exit0、12s、250任务14执行：TEMP/diplay-recovery-final-ci.log，三模块
单元任务UP-TO-DATE，不记重新执行。public tree及staged diff检查通过。
本轮仅测试夹具/文档变化，生产后端不变；完整内存峰值、故障矩阵、真实NCM/iPhone/
ARM与安全/性能门槛仍缺，整体目标和G2/G3保持未完成。

### UDP大负载发送与有序重组修复

994298b3之后扩展公共向量，先复现JNI65488字节发送RESOURCE_LIMIT、系统可发65527；
36项中35通过1失败，TEMP/diplay-udp-fragments-boundary.log。原因是完整IPv6头加
UDP负载超过16位pbuf长度。没有降低65527输入上限、扩大预算或移除溢出保护。

本地补丁使大负载的基础IPv6头与payload分开，复用现有分片循环、ID生成器与netif
输出，仅对当前启用分片且禁用loopback的port配置启用。新大报文路径传播首片和
部分输出错误。内部helper新增但公共C ABI1/JNI ABI6不变。

首轮JNI36/36后，扩大独立kernel UDP向量才发现顺序65488字节重组时pbuf头恢复
断言：TEMP/diplay-udp-large-kernel-tap.log。接收侧改为先剥各片元数据再拼接，消除
临时16位回绕；大结果的基础头保留在首片headroom，IPv6输入分别消费头与payload。
扩展调整后超过65535仍拒绝；断言关闭时仍执行所有头部移除操作。

三个vendor文件保留CRLF，精确post-image及适用边界记录于原生BACKPORTS.md；原始
314文件发布清单和manifest摘要不变。新本地补丁身份与三个官方回补身份分开，
没有伪称上游提交或安全评审完成。校验器增加两个独立local post-image及累计frag
摘要约束，仍拒绝任意vendor修改。

最终验证：

- Windows MinGW原生50/50，0.93s：TEMP/diplay-udp-large-reass-final-host.log。
  UDP案例含32768/65487/65488/65527，顺序/乱序输入；第0/1/45片输出失败后正确
  错误、UDP PCB/pbuf归零。早期测试误将无echo夹具PCB基线设为1、并把失败核心
  close结果期待OK，已依据源码与实际基线纠正，没有修改生产清理语义。
- Linux ASan/UBSan/默认LSan50/50，5.48s：/tmp/diplay-udp-large-reass-final-sanitize.log。
- JNI最终36/36，51s，零失败/错误/跳过：TEMP/diplay-udp-large-reass-connected.log。
  API28 x86_64自建AVD，无真实USB/iPhone。测试APK SHA-256：
  392d66453c426c61795a32d21b8006dfe773b16ebe875c61d443fce9c89e419b。
- Android ASan/UBSan独立内核peer：TAP endpoints、veth endpoints及旧echo TAP均通过，
  UDP0/1/1200/4096/8192/65487/65488/65527和既有TCP/主动连接。日志TEMP/
  diplay-udp-large-kernel-tap-fixed.log、diplay-udp-large-kernel-veth.log、
  diplay-udp-large-kernel-legacy.log。Android明确detect_leaks=0，不宣称LSan扫描。
- 关闭断言的附加MinGW变体UDP回归通过：TEMP/diplay-udp-large-noassert-variant.log；
  使用-DLWIP_NOASSERT=1 -Wno-error=unused-parameter，因已有TCP参数仅被断言引用。
  首次原始-Werror构建因此失败，不宣称原始无断言配置警告全通过。
- 完整九项CI exit0、3s、250任务15执行：TEMP/diplay-udp-large-final-ci.log，三个
  单元任务UP-TO-DATE，不记重新执行。APK四ABI/16KiB/NX/许可证/源摘要检查通过，
  mobile APK SHA-256：f4153b749415108f751dbe85de94d84f59ee4a92939963af4945f5c178378689。

父namespace仍net:[4026531963]，dp-lwip0/dp-peer0不存在；public tree及staged diff
检查通过。完整分配/扩展头故障矩阵、独立安全评审、进程峰值、实际NCM/iPhone/ARM
及性能/长稳仍缺，生产选择未切换，G2/G3与整体目标保持未完成。

### 扩展头重组定向矩阵与解析循环修复

新增原生udp_extension_fragments场景，内部40个向量：一/两个前置扩展头、HBH/
Destination Options及串联、顺序/逆序分片，覆盖普通长度、独立基础头边界、最大
合法payload及扩展后总长度超限；接受/丢弃后继续接收正常报文且pbuf归零。

红测TEMP/diplay-extension-red.log：extensions=1 first=HBH ordered=0 payload=65479
被误拒绝。重组后既然回到基础头，合法前置HBH仍是第一个，不能再走中途HBH拒绝。
增加解析循环continue后合法矩阵通过；进一步负例发现单纯continue会放过真正
Fragment→HBH顺序。最初8/9字节分片负例因UDP头跨pbuf被拒绝，不能证明顺序检查；
改为16/1确保完整UDP头在首片，TEMP/diplay-extension-negative-red.log明确红测。
最终在Fragment分支进入重组前显式拒绝next=HBH，同时保留atomic拒绝。新增4个
atomic/重组、顺序/逆序负例。没有将该中间修复引入的问题描述成基线已知CVE。

本地ip6.c累计SHA从2c9d7c76fd3d5c990d2967cd2ae8f9e240054e115892085e974c0ce8f191ecd6
更新为38bf7badfed81fdd58543f18c344966214aaa4235fed1d8d72a0f34019d8ce90；CRLF与
原始manifest不变，精确post-image验证器及BACKPORTS.md同步。其他vendor文件未变，
公共C ABI1/JNI ABI6、资源预算与生产选择不变。

验证：

- Windows原生51/51，1.17s：TEMP/diplay-extension-final-host.log。
- Linux ASan/UBSan及默认LSan51/51，5.27s：/tmp/diplay-extension-sanitize.log。
- JNI36/36，49s、零失败/错误/跳过：TEMP/diplay-extension-connected.log。API28
  x86_64自建AVD、RUNNING_UNLOCKED，无实际USB/iPhone；测试APK SHA-256：
  b15b55ebe4274322237e9b60443734bf48ec0e8465c97a85ac487819c2c4e829。
- 独立内核peer使用IPV6_HOPOPTS，普通UDP8组及HBH8组共16次往返；TAP/veth端点
  及旧echo通过：TEMP/diplay-extension-kernel-tap.log、diplay-extension-kernel-veth.log、
  diplay-extension-kernel-echo.log。HBH最大payload65519，保留8字节扩展头预算。
  Android工具ASan/UBSan启用、detect_leaks=0，不宣称LSan扫描。
- 无断言附加变体通过该场景：TEMP/diplay-extension-noassert.log，沿用
  -DLWIP_NOASSERT=1 -Wno-error=unused-parameter，不宣称原始无断言-Werror全通过。
- 完整九项CI exit0、3s、250任务15执行：TEMP/diplay-extension-final-ci.log；单元
  任务均UP-TO-DATE，不记重新执行。APK四ABI/16KiB/NX/许可/源摘要检查通过，mobile
  SHA-256：d6ad6eda16b327980ea3de672d872f9be4f76bfd627a495fa7a6f2146fc5c56c。

public tree及staged diff检查通过。未知选项动作、更多路由/畸形长度、分配故障矩阵、
独立安全评审、进程峰值、实际NCM/iPhone/ARM和性能长稳仍缺，G2/G3和整体目标未完成。

### UDP发送逐点分配失败回归

新增CMake测试专用frame_allocation_tests，使用GNU兼容链接器--wrap包装lwIP
mem_malloc/memp_malloc；溢出检查构建使用memp_malloc_fn，保留file/line参数。
产品Android.mk不包含测试目标/注入开关，生产及vendor代码、ABI均未修改。

成功路径动态计数后逐一使第k次heap/pool分配返回NULL。已解析邻居/可写输出下，
1200字节1点，65487/65488/65527各93点，共280个失败位置。MinGW普通及Linux池
检查构建计数一致。每个失败须RESOURCE_LIMIT、无完整末片输出、核心failure仍OK，
heap及全部MEMP pool使用量回到发送前基线；随后同一端点可完整重发，捕获帧重组
核对地址、端口、非重叠/边界、负载与校验和，资源再次回到基线。

初次sanitizer链接失败暴露memp_malloc在MEMP_OVERFLOW_CHECK下是memp_malloc_fn
宏入口，原包装未覆盖；修正两种构建入口后通过，没有把缺失注入视为通过。

最终Windows52/52、1.20s：TEMP/diplay-udp-allocation-final-host.log，单项0.19s。
Linux ASan/UBSan/池检查及默认LSan52/52、9.09s，注入单项3.92s：
/tmp/diplay-udp-allocation-final-sanitize.log。两个CTest LastTest.log均打印1/93/93/93。
完整九项Android CI exit0、15s、250任务12执行：TEMP/diplay-udp-allocation-final-ci.log。
三个单元任务均UP-TO-DATE，不记重新执行。本轮未重新运行JNI/设备用例；仅宿主
测试/文档变化，不借用历史设备结果声称本轮覆盖。public tree及staged diff检查通过。

仅R01的UDP发送子集，未遍历上下文/绑定libc分配、JNI异常、未知邻居排队及全部
TCP/重组分配路径；并发、独立安全评审、完整进程峰值、真机及性能长稳仍缺。
生产后端未切换，G2/G3与整体目标保持未完成。

### UDP绑定逐点分配失败与已有队列保留

测试专用frame_allocation_tests扩展calloc链接包装，无产品钩子。首次绑定创建
接收池路径动态计数3点，已有池路径2点；基线调用断言实际calloc命中2/1次，不能
因工具链跳过包装而只测PCB分配。每个点依次返回NULL后须RESOURCE_LIMIT，预置
非零的handle/actual_port清零，heap、UDP PCB和全部MEMP pool使用量回到原基线。

同一核心的固定47000端口必须可立即重绑、发送并关闭。已有池路径在注入前通过
真实帧预排入原端点数据，失败后核对内容、来源和长度，原端点继续可发送；新端点
关闭不误释放仍共享的池。普通MinGW与Linux sanitizer均确认3/2点，既有UDP发送
280点注入同时回归。测试/构建/文档以外无代码修改，vendor摘要及ABI不变。

最终Windows53/53、1.31s，单项0.02s：TEMP/diplay-bind-allocation-queue-host.log。
Linux ASan/UBSan/池检查及默认LSan53/53、9.33s，单项0.08s：
/tmp/diplay-bind-allocation-queue-sanitize.log。完整九项CI通过，日志：
TEMP/diplay-bind-allocation-queue-ci.log；三个单元任务UP-TO-DATE，不记重新执行。
public tree及staged diff检查通过。本轮未重新运行JNI/设备测试，不将宿主矩阵当作
Android libc、JNI或实际NCM/iPhone证据。上下文分配、更多TCP/接收/并发故障、独立
安全评审、进程峰值及真机/长稳仍缺，R01与G2/G3及整体目标均未标完成。

### 上下文calloc失败、全局所有权与重建

新增测试专用core_allocation_failures。进程首次core calloc注入NULL，预置非零
输出须清空并返回RESOURCE_LIMIT；随后正常创建成功，证明全局claimed未残留。
完成一次初始化及销毁后，动态测量热创建的分配路径，当前普通/池检查构建均为1点。
热创建失败同样不发布核心；netif_list/default为空，heap及所有MEMP pool回到基线。
每轮还需重新创建、DAD/邻居准备、UDP绑定及捕获帧核对，再销毁验证资源。

热创建基线保留了全局周期计时器，不把它们误记为逐点注入。冷启动内部定时器池
尚未覆盖；本轮不声称完整上下文创建/R01失败矩阵。只增加测试、CMake场景和文档，
未修改生产代码、vendor摘要、ABI或选择策略。

Windows54/54、1.29s，单项0.02s：TEMP/diplay-core-allocation-final-host.log。
Linux ASan/UBSan/池检查及默认LSan54/54、9.27s，单项0.06s：
/tmp/diplay-core-allocation-final-sanitize.log。两边LastTest.log均确认冷calloc1次、
热创建1点。完整九项CI exit0、2s、250任务12执行：TEMP/diplay-core-allocation-final-ci.log；
三个单元任务UP-TO-DATE，不记重新执行。public tree及staged diff检查通过。
本轮未启动模拟器或重跑JNI，不借用历史设备结果扩大当前验证范围。
完整故障/安全矩阵、进程峰值、实际NCM/iPhone/ARM及性能长稳仍缺，整体目标继续。

### TCP监听分配失败、重试与端口回滚

新增测试专用tcp_listen_allocation_failures，reuse开/关各测得3处分配：监听记录
calloc、普通PCB、监听PCB。分别逐点瞬时失败及从第k次持续失败，共12个场景位置。
原测试假定任何单次失败都返回RESOURCE_LIMIT，红测记录在TEMP/
diplay-listen-allocation-diagnostic.log：reuse=0 fail_at=2 calls=4 status=OK。
核对tcp_alloc确认其会回收/重试PCB分配；该恢复成功不是产品缺陷，未改库行为。

最终瞬时模式每种reuse均1例恢复成功、2例RESOURCE_LIMIT；持续模式各3例明确
RESOURCE_LIMIT。成功句柄实际握手/accept/读数据后关闭，失败输出清零；每项核对
heap、活动/监听PCB及所有MEMP pool回基线，再同端口重绑并连接。按需TCP计时器
通过受控500ms poll退休后比较。包装增加真实heap/pool错误计数，配合库重试成功
后的统计修正，避免测试注入制造错误计数下溢。产品源、vendor摘要与ABI不变。

Windows55/55、1.25s，单项0.02s：TEMP/diplay-listen-allocation-final-host.log。
Linux ASan/UBSan/池检查及默认LSan55/55、9.16s，单项0.13s：
/tmp/diplay-listen-allocation-final-sanitize.log。两边LastTest.log打印四组一致结果。
完整九项CI exit0、日志TEMP/diplay-listen-allocation-final-ci.log；三个单元任务
UP-TO-DATE，不记重新执行。public tree及staged diff检查通过；本轮未运行新JNI/
设备测试。没有其他活动连接时的监听矩阵，不代表PCB回收其他连接、全部accept/
connect/发送/冷定时器及并发故障；独立安全评审、峰值和真机/长稳仍缺，目标继续。

### TCP接受最终ACK分配失败与原监听器保留

新增tcp_accept_allocation_failures。SYN/SYN-ACK正常完成后，动态测得最终ACK处理
输入pbuf与接受记录calloc两处分配，分别注入瞬时/持续失败。两模式的正常控制
各交付1个可读stream；两处分配失败各造成1次输入RESOURCE_LIMIT和1次未交付接受，
dp_tcp_accept均WOULD_BLOCK、输出清零，无错误句柄。输入OOM保留的半开连接最终
由监听器关闭回收，未把正常待清理协议状态误判为立即泄漏。

关闭前，原监听器须接受不同源端口的新客户端并读取数据；关闭并通过500ms受控
poll退休按需TCP计时器后，heap、活动/监听PCB及全部MEMP pool回到原基线；再同
端口重绑、握手、accept和读数据。产品、vendor、ABI及选择策略均未改变。

Windows56/56、1.34s，单项0.02s：TEMP/diplay-accept-allocation-final-host.log。
Linux ASan/UBSan/池检查及默认LSan56/56、9.46s，单项0.09s：
/tmp/diplay-accept-allocation-final-sanitize.log。完整九项CI通过：
TEMP/diplay-accept-allocation-final-ci.log；三模块单元任务UP-TO-DATE，不记重新执行。
public tree及staged diff检查通过。本轮仅宿主测试和文档，无新JNI/设备证据；SYN/
定时器分配、其他TCP/接收/并发故障、独立安全审查、峰值与真机/长稳仍缺，目标继续。

### TCP定时器分配失败修复

SYN逐点注入在第3处分配复现timeout pool耗尽断言：TEMP/
diplay-syn-allocation-red.log。新增可失败的TCP定时器插入，成功后才置active；
owner边界重试，持续失败报告核心RESOURCE_LIMIT并通过现有销毁屏障清理。
TCP回调重排使用同一路径，其他timeout调用仍保留原行为，未改变池预算或ABI。
SYN正常路径5点，分别瞬时/持续注入；另重放实际TCP timer handler定向覆盖
瞬时恢复与持续核心失败，均检查销毁、重建及heap/所有pool基线。重放不证明时序。

最终Windows58/58、2.81s：TEMP/diplay-timer-final-host.log；Linux ASan/UBSan、
池检查及默认LSan58/58、9.85s：/tmp/diplay-timer-final-sanitize.log。
LWIP_NOASSERT变体新增两例通过：TEMP/diplay-timer-noassert-final.log；该变体
保留既有-Wno-error=unused-parameter配置，不声称无警告严格构建。
九项CI通过，12s、17执行/233缓存：TEMP/diplay-timer-final-ci.log。
实际Android JNI36/36通过，49s：TEMP/diplay-timer-final-jni.log。
四ABI APK及源码精确摘要校验、public tree检查通过，APK SHA-256：
af4ff3fc3175cfb3cc63a766a70f26242fbcd329ad71c782fc07f468155425e9。

本轮未重跑内核互通，不把既有内核结果算作当前修复的新证据。冷启动/非TCP定时器、
其他分配/并发路径、峰值、独立安全审查和实际NCM/iPhone/ARM仍待覆盖；用户确认
目前没有参考设备或报告，G0/G1未通过，生产选择保持原状，整体目标继续。

### TCP主动连接分配失败矩阵

新增tcp_connect_allocation_failures：正常5点，瞬时恢复2/拒绝3，持续拒绝5，
其中持续1例核心RESOURCE_LIMIT。所有失败输出handle清零；成功必须完成SYN/
SYN-ACK/ACK、接收reply、发送hi。非核心失败在同核心重连前检查heap/活动PCB/
监听PCB/全部pool基线；所有场景销毁、全局基线检查，再新建核心和双向收发。
测试直接通过，没有为制造红测修改正确产品行为，本轮产品和vendor均无变化。

Windows59/59、1.25s：TEMP/diplay-connect-allocation-final-host.log；Linux
ASan/UBSan/池检查及默认LSan59/59、10.00s：
/tmp/diplay-connect-allocation-final-sanitize.log。九项CI exit0、3s，12执行/
238缓存：TEMP/diplay-connect-allocation-ci.log；三个单元任务UP-TO-DATE，
不记重新执行。public tree和diff检查通过。本轮未启动设备或重跑JNI/内核。
没有其他活动连接，仍缺全部TCP写入/接收、未知邻居/重组、冷定时器、并发关闭、
独立安全审查和实际设备等；不将本子集视为完整R01，整体目标继续。

### TCP首写分配失败的回滚与重试

新增tcp_write_allocation_failures，1字节2点、8192字节32点，各做瞬时/持续注入，
共68个失败位置。全部WOULD_BLOCK/copied=0；重试前heap/全部pool归还，且没有
输出新数据。解除故障后完整重试，覆盖source，逐帧校验数据及校验和；ACK完成后
同stream再写1字节检查正确序号，关闭后资源回基线并重绑端口、重新连接接收。
用例直接通过，未改产品行为、vendor或ABI。该背压结果不等同test-plan.md要求的
RESOURCE_LIMIT，不声称完整R01通过；已有unsent队列/所有窗口和并发路径仍缺。

Windows60/60、1.30s：TEMP/diplay-write-allocation-final-host.log；Linux
ASan/UBSan/池检查及默认LSan60/60、10.88s：
/tmp/diplay-write-allocation-final-sanitize.log。两个平台打印相同的4组分配计数。
九项CI exit0、3s，12执行/238缓存：TEMP/diplay-write-allocation-ci.log；三个
单元任务UP-TO-DATE，不记新执行。public tree及diff检查通过。本轮未启动设备，
没有新增JNI或内核证据；真机、安全审查、完整故障矩阵和峰值仍待覆盖，目标继续。

### TCP接收分配失败、原队列保留与乱序恢复

新增tcp_receive_allocation_failures：1/1440字节，顺序各2点、乱序各3点，各做
瞬时/持续注入，共20个失败位置。每组1例输入RESOURCE_LIMIT，后续协议分配
失败可能返回OK并等待重传；未把这些结果算作完整R01错误映射通过。测试先排入
seed，故障后仍能读出；乱序缺口前必须WOULD_BLOCK且不改缓冲区。解除故障后
重传同帧并补gap，完整有序无重复，再接收next。关闭、timer退休后heap/全部pool
回基线，重绑同端口完成连接和接收。用例直接通过，无产品、vendor或ABI修改。

Windows61/61、1.33s：TEMP/diplay-receive-allocation-host.log；Linux ASan/UBSan/
池检查及默认LSan61/61、11.01s：/tmp/diplay-receive-allocation-sanitize.log。
九项CI exit0、3s，12执行/238缓存：TEMP/diplay-receive-allocation-ci.log；三个
单元任务UP-TO-DATE，不记新执行。public tree及diff检查通过。本轮无新JNI/内核/
设备证据；多段重叠乱序、FIN、回调拒绝、重组及并发关闭等仍缺，不宣称完整
R01/R02或真机/安全门槛通过，整体目标继续。

### 10,000次原生宿主创建/销毁

新增lifecycle_soak执行10,000个负载轮次：空核心、UDP带排队数据、TCP带未读数据/
未确认发送交替；显式端点关闭与核心销毁回收交替，每轮重复destroy。初版严格要求
全部pool立即回基线，红测TEMP/diplay-lifecycle-soak-diagnostic.log定位cycle=2、
SYS_TIMEOUT多1。核对TCP timer无应用参数且最后PCB销毁后下一tick退休，改为
逐轮允许最多一个全局timeout节点，其他pool精确归零；每1,000轮空核心推进时间
后全部pool精确归零。没有改产品行为或vendor，详见host-lifecycle-soak.md。

Windows62/62、1.46s，soak0.23s，10次采样process_handles均99：
TEMP/diplay-lifecycle-soak-final-host.log。Linux ASan/UBSan/池检查及默认LSan
62/62、43.69s，soak33.18s，10次采样fds均6/threads均1：
/tmp/diplay-lifecycle-soak-final-sanitize.log。九项CI exit0、3s，12执行/238缓存：
TEMP/diplay-lifecycle-soak-ci.log；单元任务UP-TO-DATE，不记新执行。public tree
及diff检查通过。未启动设备、无新JNI或内核证据；该子项不代表整会话租约/线程/
FD、100次设备重连或8小时音视频长稳，完整L06和整体目标保持未完成。

### 原生UDP按端点排队年龄预算

新增dp_udp_set_queue_max_age及dp_udp_get_expired_count增量C接口，默认关闭。
报文记录64位核心单调时间，达到预算后在入队/读取/统计/修改预算时丢弃并归还
blocks，总dropped及独立expired递增。timestamp/端点字段仍计入512KiB总预算，
不扩大内存上限，公共结构体/C ABI版本/JNI ABI及vendor摘要不变。

先加入测试与声明，TEMP/diplay-udp-age-red.log记录缺少实现的链接失败。实现后
首次测试误用250ms步进的advance检查100ms边界，导致fresh也过期；核对助手后
改为精确f.now推进。最终udp_queue_age覆盖边界、不同端点默认保留、满队列先
清理、零长包、预算切换、32位栈时钟回绕、失效句柄和关闭资源。详见udp-queue-age.md。

Windows63/63、1.50s：TEMP/diplay-udp-age-final-host.log；Linux ASan/UBSan/
池检查及默认LSan63/63、47.19s：/tmp/diplay-udp-age-final-sanitize.log。
九项CI exit0、7s，17执行/233缓存：TEMP/diplay-udp-age-ci.log；单元任务
UP-TO-DATE，不记新执行。四ABI APK及源码校验通过：TEMP/diplay-udp-age-apk.log；
APK SHA-256 0a4dd2dfb1d9bc1bd5a55fb70a556f2cea74097f9acb1f22db0143044e018559。
public tree及diff检查通过。没有新设备/JNI运行/内核证据；策略尚未接通JNI、
公共API及媒体通道配置，完整R04、真机及整体目标仍未完成。

### UDP排队年龄的原型token桥与JNI ABI 7

新增原型dp_proto_udp_set_queue_max_age/expired_count，保持上下文token代数、
owner线程及核心错误边界；新增JNI udpSetQueueMaxAge/udpExpiredCount，年龄
Long参数限0..UINT32_MAX，统计LongArray必须1项，失败不发布Java输出。整体JNI
ABI升7并同步加载器/设备断言；C ABI仍1、公共C结构体及vendor摘要不变。

先加原型测试与声明，TEMP/diplay-udp-age-bridge-red.log记录缺失实现链接失败；
随后实现token桥和JNI。宿主63/63、1.78s：TEMP/diplay-udp-age-bridge-host.log；
Linux ASan/UBSan/池检查及默认LSan63/63、54.91s：
/tmp/diplay-udp-age-bridge-sanitize.log。Android API28 x86_64完整JNI36/36通过，
81s：TEMP/diplay-udp-age-bridge-jni.log。最后保留原跨线程close断言并追加年龄
配置跨线程拒绝后，定向重跑1/1、17s：TEMP/diplay-udp-age-bridge-jni-final.log。
真实JNI覆盖旧包过期/新包可读、越界配置不影响原预算、数组形状、线程和旧token；
debug.checkjni属性为空，不将本次记录成已显式启用CheckJNI的验收。

九项CI通过、8m31s、47执行/203缓存：TEMP/diplay-udp-age-bridge-ci.log；shared
1790及common1578项重新执行，零失败/错误；home6项为UP-TO-DATE，合计XML3374。
四ABI APK/源码校验：TEMP/diplay-udp-age-bridge-apk-final.log；APK SHA-256：
7c5dd5a16375a4275c944a7fcaca806876c49ff2bd1e11f7b7724cc122f2e763。
public tree及diff检查通过。仅运行自建AVD，无新NCM/iPhone/ARM或内核证据。
仍需接通类型化公共接口和媒体通道配置；生产选择不变，完整R04和整体目标继续。

### UDP排队年龄的公共可选能力

新增UdpQueueAgeControl，显式lwIP UdpEndpoint实现setReceiveMaxAge及expiredDatagrams；
调用在端点scope中排队，只有owner访问native token。LwipUdpCore和NativePrototypeCore
接通JNI ABI7，System端点不声明支持；默认关闭策略不变，没有启用媒体通道。
范围校验、OOM映射和负计数协议校验与关闭取消均保留；接收等待不阻塞配置命令。
本轮没有修改C/JNI源码、ABI或vendor，未重复原生宿主/sanitizer验证。

先加测试，TEMP/diplay-udp-age-api-red.log记录未实现接口编译失败。实现后新增
3项适配测试覆盖范围/关闭、等待接收时配置、OOM/非法统计及恢复。定向15/15：
TEMP/diplay-udp-age-api-final-tests.log。真实Android API28 x86_64 JNI36/36，
69s：TEMP/diplay-udp-age-api-jni.log；公共SessionNetwork能力可调用，实际UDP
端点入队旧包后推进单调时钟，过期计数1、新包可读；关闭后统计查询CANCELLED。

九项CI通过、7m11s、43执行/207缓存：TEMP/diplay-udp-age-api-ci.log。shared1793、
common1578重新执行零失败/错误，home6为UP-TO-DATE，XML总3377。四ABI APK/源码
校验：TEMP/diplay-udp-age-api-apk.log；APK SHA-256：
e2264833b46149a1d893acd98c3c3acc56035791b47a00f18923bd82ac4e3fc2。
public tree及diff检查通过，测试用AVD已关闭。无参考NCM/iPhone/ARM设备证据。
年龄检查只覆盖原生出队，已进入命令结果和媒体层的数据仍待完整暂停恢复验证；
实际媒体策略、G0/G1与整体方案保持未完成，生产选择不变。

### Native fuzz报告驱动与CPU计量组件

按既有三target计划开始实现P6，新增lwip_fuzz_driver.py及CI中的驱动合同测试。
互斥cpu-hours/runs/replay，记录固定seed、逐批命令、失败退出、日志/corpus摘要、
binary/source SHA-256和可用Git身份；CPU用Linux wait4实测user+system，墙钟
仅用于10秒分批。失败/短活动/runs/replay不会获得CPU预算通过标记。每活动独立
报告目录，逐批原子写JSON，保留故障产物。详见fuzz-driver.md和实施计划。

红测TEMP/diplay-fuzz-driver-red.log为缺少实现。Windows测试先发现测试断言
写死POSIX路径，修正为平台Path；Linux端到端报告测试发现Windows管理的worktree
不能由Linux Git解析，改为明确Git不可用并始终记录实际source/binary摘要，不假报
commit或clean。Linux最终7/7，0.428s：/tmp/diplay-fuzz-driver-final-tests.log；
Windows7项，5通过/2个Linux专用跳过，0.041s：TEMP/diplay-fuzz-driver-final-tests.log。
真实子进程测试验证CPU及失败报告，假子进程不是lwIP fuzz流量。

宿主Clang安装初遇APT snap hook及旧索引404；仅临时APT配置清除该安装hook，
刷新索引后安装Clang10成功，未改仓库产品或系统长期APT配置。九项CI exit0、5s、
12执行/238缓存：TEMP/diplay-fuzz-driver-ci.log；单元任务UP-TO-DATE，不记新执行。
四ABI APK/源码检查通过：TEMP/diplay-fuzz-driver-apk.log，APK摘要仍
e2264833b46149a1d893acd98c3c3acc56035791b47a00f18923bd82ac4e3fc2。
public tree及diff检查通过。该阶段没有设备/native栈运行证据；真实三target、插桩、
有效种子、fuzz_lwip.sh和实际活动仍待实现，累计24 CPU小时尚未开始计入，目标继续。

### Native lwIP fuzz targets与种子smoke（2026-10-23）

完成三类Clang/libFuzzer target：Ethernet帧、端点命令和core生命周期。新增可选
`DIPLAY_FUZZ` CMake配置，只在Linux Clang且ASan/UBSan开启时构建；正常Android与普通
host配置仍关闭coverage插桩。target使用真实lwIP stack/port，每次输入检查core销毁、
netif/默认路由、堆和memp池基线。可复现种子包含IPv6 UDP、TCP SYN、ICMPv6 echo、
有效TCP/UDP命令、嵌套帧、stale token和重复重建序列。

`scripts/fuzz_lwip.sh`已完成Clang构建、种子初始化、smoke/CPU预算/replay入口；报告
包含真实子进程wait4 CPU时间和墙钟/RSS、binary/source摘要、compiler/CMake版本、构建
命令、sanitizer选项、corpus摘要和故障目录。Git在WSL挂载的Windows worktree中不可用，
报告明确记下这一点，并保留实际source SHA-256。CI现对每类target运行100次种子smoke，
上传report、日志、corpus和构建元数据。

WSL Ubuntu 20.04 / Clang 10 / CMake 3.16三target各100次均exit 0：
`build/lwip-fuzz-smoke/ethernet/campaign-20261022T172026.866933Z/report.json`，CPU
2.20秒；`build/lwip-fuzz-smoke/commands/campaign-20261022T172108.646217Z/report.json`，
CPU 2.58秒；`build/lwip-fuzz-smoke/lifecycle/campaign-20261022T172108.761897Z/report.json`，
CPU 5.48秒。三份batch日志均有100次完成且无ASan/UBSan错误。生命周期代表种子单文件
replay也exit 0，实际执行1个输入：
`build/lwip-fuzz-smoke/lifecycle/campaign-20261022T172253.393946Z/report.json`，CPU
0.14秒。smoke与replay报告的 `cpu_budget_complete` 均为false，不计入长时验收。

该证据只覆盖本机x86_64短时native fuzz。测试计划要求的三个target各8 CPU小时、合计
24 CPU小时仍未完成；用户确认目前没有参考设备或脱敏基线报告，G0/G1真机验证也仍缺失。
Android完整CI、public-tree/APK核验和最终diff检查在本实现后继续执行；生产后端选择保持不变。

### Release-like Linux TCP/UDP 宿主基准

新增 `scripts/benchmark_lwip.sh`，默认构建 Release、关闭 sanitizer，校验 native 源码，
并要求 Linux root、隔离 network namespace 与网络接口权限。它在隔离 veth/TAP peer 上
运行 lwIP TCP/UDP echo，默认测量600秒；核对回显字节和零UDP丢包，记录吞吐、RTT分位数、
环境、源码/二进制摘要、lwIP计数和客户端CPU/RSS。`report.json`明确标记这不是设备验收，
且不评估相对真实媒体峰值的带宽余量。`workflow_dispatch`新增默认关闭的可选任务并上传
报告和构建日志；PR流程只运行快速脚本/报告合同测试，不承担10分钟基准。

Linux `test_lwip*.py` 18/18通过（含本机IPv6 loopback echo指标与报告门槛测试）；Windows
18项通过、9项Linux专属跳过。Release `lwip_kernel_echo`与`lwip_kernel_offloads`成功构建。
ASan/UBSan/池保护宿主CTest 63/63通过，其中包含10,000轮生命周期soak。要求的完整Android
检查成功：250个Gradle任务，12执行、238缓存命中；public-tree及APK/native源核验通过。
workflow YAML、shell语法检查通过。

本机WSL `unshare --net`预检返回无权限，故未创建隔离peer，没有实际lwIP TCP/UDP吞吐值，
也没有达到10分钟宿主基准门槛。loopback测试只验证测量代码和报告完整性，不代表lwIP栈性能。
用户确认当前无可用参考设备、抓包或VPN/无线基线；G0/G1、真实媒体余量、长时设备矩阵和
累计24 CPU小时fuzz仍待补齐，整体方案与生产后端选择均保持未完成/不变。

### P4 有线通道的静态网络调用复核

对 `shared/src/main/java` 搜索 `java.net` Socket/Datagram API、HTTP connection、VPN 建立及
Socket FD 操作，并查看每个命中点的生产调用者。AirPlay 有线主控制、事件、视频、音频、
缓冲音频、iAP、视频设置、NTP 和麦克风均通过 SessionNetwork 端点；底层系统 TCP/UDP
集中在 SystemSessionNetwork，TcpLiveness 也只从该适配器进入。无线 Bonjour probe、ADB、
仅测试使用的旧 Socket 构造/ServerSocket overload已在通道清单标注。

复核还发现显式配置的 RemoteMfiAuthenticationClient 使用未绑定 SessionNetwork 的
HttpURLConnection 访问外部认证服务，由 Android 系统网络栈路由。它不是 iPhone 数据通道，
但属于独立系统网络出站；已记录其调用边界，避免把数据面复核扩大成全进程无系统网络的结论。
源码搜索未见其他有线 CarPlay 数据通道直接使用系统 Socket。静态审查项完成；是否迁移远程
认证依赖仍是产品范围决策。该审计没有设备抓包或全通道协商回归，不据此标记 G4 或任一设备
门槛完成。

### P2 安全回补：NDP 真实入口约束

在固定 lwIP `nd6.c` 上补齐原有 NA/NS/RA/Redirect 校验 TODO。完整 ND 选项链必须非零且
不越过报文边界，检查发生在改写邻居/路由/目的缓存之前；新增 NA Solicited/源地址约束、
DAD NS 目标与 Source LL 约束，以及 Redirect 第一跳来源和目标地址约束。对应 10 个新真实
Ethernet/IPv6 CTest 加既有 NDP、选项和 DAD 测试，共 13/13 通过；旧实现下相关负例能复现
缓存/路由改变或错误响应。Linux ASan/UBSan/池保护 CTest 73/73、Windows MinGW CTest 73/73
均通过；仓库要求的 Gradle 单测、三模块 lint 和 assemble 命令成功，四 ABI APK ELF/ZIP
16 KiB 对齐、许可证及固定源码检查通过。APK SHA-256：
`cbdc909f80f105dfd1580baaebb8470aa21cfdb6e99009d612b886675fbfb312`。公开树检查通过；
脚本单测 25 项中 16 项通过、9 项按平台跳过。三类长时 fuzz 原先构建于本次 NDP 修改之前，
已要求保存旧报告并在当前源码上重建重跑。完整 RFC/安全评审、三个 target 各 8 CPU 小时、
隔离网络下 10 分钟基准，以及 G0/G1 真机和 G2 实际 NCM 门槛仍未完成。

### P3 API 28 双后端 Android 契约验证（2026-10-23）

在任务 AVD `diplay-lwip-api28`（Android 9 / API 28、x86_64，`debug.checkjni=1`）运行
`./gradlew :shared:connectedDebugAndroidTest --stacktrace`，结果 36/36 通过、0 失败、0 跳过：
`SessionNetworkDeviceContractTest` 8 项、参数化 `TcpSessionDeviceContractTest` 14 项、
`NativePrototypeInstrumentationTest` 14 项。覆盖系统与 `lwip-jni` 的 UDP 来源/截断/边界、端口冲突、
关闭取消、TCP 半关闭/重置/超时、10 秒接收暂停后的背压恢复，以及原生 JNI 与 runner 生命周期。
原生 AVD 控制台的旧 ddmlib `getAvdPath` 子命令告警未影响设备测试，Gradle 任务 exit 0。
这是模拟器证据，不覆盖物理设备、iPhone/NCM、VPN/无线基线或低配 ARM 性能；G0/G1/G2 仍未通过。

### P3 端点级关闭合同加固（2026-10-23）

扩展参数化 `SessionNetworkDeviceContractTest`：两个后端分别验证只关闭 listener 能取消已进入
accept 的调用，只关闭 UDP endpoint 能取消已进入 receive 的调用；调用返回 `CANCELLED`，重复
关闭安全，原会话仍可绑定新端点。通过观察工作线程进入对应后端栈帧后再关闭，避免把线程启动
信号当作阻塞已发生。T02 的共同 TCP 合同固定保留 1 字节与 32769 字节边界，其余写入
采用固定种子的随机分段。T08 增加 TCP connect 与 UDP send 对远端 port 0 的预校验断言。

API 28 x86_64 AVD、`debug.checkjni=1` 上复跑 `./gradlew :shared:connectedDebugAndroidTest
--stacktrace`，40/40 通过、0 失败、0 跳过：SessionNetworkDeviceContractTest 12 项、
TcpSessionDeviceContractTest 14 项、NativePrototypeInstrumentationTest 14 项。会话关闭原有
并发测试仍只同步到调用启动；新增端点关闭测试提供了单独的确切进入等待证据。这仍是模拟器
验证，不代表真机/NCM/ARM 或全套 G3 失败路径完成。AGENTS.md 要求的完整 Gradle 检查亦成功：
250 个任务中 14 个执行、236 个缓存命中。

### P3 原型TCP echo的libc分配失败补测（2026-10-23）

新增宿主定向注入，覆盖 `prototype_echo.c` 在TCP接收回调里暂存待回显数据的libc `malloc`。
分配失败时确认没有回显payload；解除故障后重传相同TCP序号，逐字节验证完整回显，证明
`ERR_MEM`路径保留了输入段。该用例只覆盖此原型回调的单个分配点，不代表所有libc分配、
JNI复制/异常或完整R01/G3通过。Linux ASan/UBSan/LSan CTest与Windows MinGW CTest均74/74
通过；AGENTS.md规定的Gradle单测、三模块lint和assemble命令成功，250个任务中30个执行、
220个缓存命中。测试注入只存在于宿主测试目标，公共ABI和生产后端未变更。

### P2 ND6 邻居队列分配失败记账修复（2026-10-23）

对未解析邻居的 UDP 输出连续注入 `MEMP_ND6_QUEUE` 分配失败，复现 `nd6_queue_size`
在 `memp_malloc` 返回 NULL 时仍增长的错误：实际没有队列项，逻辑容量却被逐步耗尽，
后续正常发送会被当成队列已满而失败。修复为仅在节点分配成功后递增，包括淘汰旧项后的
重试分支。新测试遍历16次失败注入，每次确认 core 健康且 heap/pbuf/全部 MEMP pool
回到基线；解除注入、收到有效邻居通告后，核对原 UDP 报文确实完整发出。旧实现下该
恢复断言可复现失败。源码摘要固定于 `BACKPORTS.md` 和 `scripts/check_lwip_native.py`。

本次 Linux ASan/UBSan/LSan 与池保护 CTest 75/75、Windows MinGW CTest 75/75；
`AGENTS.md` 完整 Gradle 单测、三模块 lint 及 assemble 命令成功，250 个任务中17个执行、
233个缓存命中。该修复只关闭 ND6 队列记账这一条分配失败缺口。JNI OOM、其他 TCP/重组
队列和并发关闭路径、累计24 CPU小时 fuzz、隔离10分钟宿主基准、实际 NCM/ARM 与 G0/G1
真机门槛仍未完成；用户已确认目前没有可用设备或报告，G0/G1 继续标记未验收，生产后端不变。

### P2 IPv6 重组配额淘汰回归（2026-10-23）

新增宿主帧测试填满 `IP_REASS_MAX_PBUFS` 后送入另一数据报的首片，确认 `IP_REASS_FREE_OLDEST`
策略淘汰较早的不完整上下文、保留新上下文；继续输入后，新 UDP 数据报完整交付，pbuf 与
重组上下文计数归零，端点关闭后 core 仍健康。该测试刻画现有有界淘汰行为，没有改变生产栈。
全量编译时发现该用例只属于分配测试目标，因此将定义与分发入口一并限制到
`DP_WRAP_ALLOCATION`，修复了普通宿主目标的 `-Werror=unused-function`。

Linux ASan/UBSan/LSan 与池保护 CTest 77/77 通过；Windows MinGW 从生成的 CTest 清单原生执行
77/77 条测试命令通过。该覆盖只验证宿主侧重组配额回收，不替代真实设备 NCM/媒体压力、累计
24 CPU 小时 fuzz、隔离10分钟宿主基准或 G0/G1 真机验收；用户确认目前无可用设备或报告，
这些门槛继续标记未验收。

### P2 IPv6 重组上下文池淘汰回归（2026-10-23）

新增帧级测试先占满 `MEMP_NUM_REASSDATA`，再输入第五个不完整数据报。它验证上下文池耗尽时
lwIP 淘汰最旧分片集并复用槽位：被淘汰数据报的尾片不会与旧首片拼接；新数据报完整交付；
重新输入旧数据报首片后，先前留下的尾片只与这个新上下文组合。最后检查全部 UDP 负载、
重组上下文与 pbuf 计数归零。它覆盖固定池耗尽的淘汰路径，不等同于对重组分配器逐点注入，
也没有修改生产栈。

Linux ASan/UBSan/LSan 与池保护全量 CTest 77/77 通过；Windows MinGW 原生执行生成清单中的
77/77 条测试命令通过。lwIP vendor verifier 与 `git diff --check` 通过。R01 重组分配失败、JNI
异常/复制失败和并发关闭仍待覆盖；设备、NCM/媒体压力、隔离10分钟基准与累计24 CPU小时 fuzz
仍未验收。

### P2 IPv6 重组上下文分配失败恢复回归（2026-10-23）

新增一次性链接器故障注入，令存在一个未完成数据报时，新上下文的首次
`MEMP_IP6_REASSDATA` 分配失败。lwIP 淘汰最旧上下文并重试后，新数据报可完整交付；被淘汰数据报
留下的尾片不会拼接旧首片，重新输入首片后才能独立完成。测试核对拒绝计数、重组上下文及 pbuf
归零、UDP endpoint 关闭和 core 健康。该用例覆盖分配失败后的淘汰重试，不改变生产栈，也不代表
重组分配器所有失败位置或完整 R01 通过。

Linux ASan/UBSan/LSan 与池保护全量 CTest 78/78 通过；Windows MinGW 原生执行生成清单中的
78/78 条测试命令通过。lwIP vendor verifier 与 `git diff --check` 通过。JNI复制/异常、更多TCP
分配点及并发关闭仍待覆盖；用户确认目前无真机或基线报告，因此 G0/G1、实际NCM/媒体压力、隔离
10分钟基准和累计24 CPU小时 fuzz 继续未验收。

### P2 冷启动周期定时器分配失败恢复（2026-10-23）

新增三个独立冷启动用例，分别令 ND6、IPv6重组和 MLD6 周期定时器的
`MEMP_SYS_TIMEOUT` 插入失败。旧实现会在 `sys_timeout` 中断言终止；修复增加可返回错误的
`sys_timeouts_init_try` 与 `lwip_init_checked`。定时器初始化现在幂等，任一插入失败会撤销
周期定时器节点并让本次 `dp_core_create` 返回 `DP_RESOURCE_LIMIT`；模块初始化只运行一次，
后续创建补齐周期定时器并成功完成DAD。失败用例逐项检查netif、heap和全部MEMP池归零。

Linux ASan/UBSan/LSan与池保护全量CTest 81/81通过；vendor精确摘要校验通过。
AGENTS.md规定的Gradle单测、三模块lint与assemble命令通过，250个任务中17个执行、233个
UP-TO-DATE。API 28 x86_64 AVD、`debug.checkjni=1`的`:shared:connectedDebugAndroidTest`也通过，
40/40项；出现的旧ddmlib `getAvdPath`控制台告警未影响任务。最终APK的四ABI ELF与打包检查通过。
Windows MinGW全量CTest也通过81/81，使用可用的Python 3.6 CMake 3.15.3启动器绕开PATH中
Python 3.7 CMake启动器的退出码1问题。ABI不变，生产后端未切换。运行期间其他timeout
调用和完整R01仍待覆盖；JNI分配异常、并发边界、累计24 CPU小时fuzz、隔离10分钟基准、G0/G1
真机及G2实际NCM验收仍未完成，当前用户确认没有设备或报告。

### P2 运行期周期定时器重排分配失败恢复（2026-10-23）

新增瞬时和持续 `MEMP_SYS_TIMEOUT` 故障注入，均在真实周期回调到期后触发。旧路径在重排
失败时复现 `sys_timeout` 断言并终止；现在回调使用可失败插入，并记录待修复状态。瞬时不足由
core owner 边界幂等补回缺失定时器，核心继续工作；持续不足回滚整组周期定时器、将核心标为
`DP_RESOURCE_LIMIT`，再销毁、重建并验证非定时器池回到基线。该测试覆盖周期重排，不代表其他
one-shot timeout 或完整 R01 通过。

Linux ASan/UBSan/LSan 与 lwIP pool 检查全量 CTest 83/83 通过（50.42s，日志：
`build/reports/lwip/diplay-lwip-runtime-timers-asan.log`）；Windows MinGW 全量 CTest 83/83
通过（日志：`build/reports/lwip/diplay-lwip-runtime-timers-windows.log`）。AGENTS.md 九项
Gradle 单测、三模块 lint 与 assemble 通过，250 个任务中 17 个执行、233 个缓存；lwIP
vendor 精确摘要和 `git diff --check` 通过。API 28 x86_64 AVD、`debug.checkjni=1` 下重新运行
`:shared:connectedDebugAndroidTest`，40/40 通过，48s。新增 vendor 摘要记录于 `BACKPORTS.md`
和 `scripts/check_lwip_native.py`。真机设备/基线、实际 NCM、JNI OOM、其他 one-shot timeout、
完整 R01、24 CPU 小时 fuzz 和隔离性能门槛仍待验收。

### P2 通用 one-shot timeout 分配失败转为核心资源错误（2026-10-23）

新增定向测试令 `dp_core_execute` 注册通用 `sys_timeout()` 时 `MEMP_SYS_TIMEOUT` 分配失败。旧路径
在 `timeouts.c` 断言并终止进程；现在 void API 记录失败，core owner 边界消费该状态并返回
`DP_RESOURCE_LIMIT`，超时节点没有进入队列。测试销毁失败 core、检查超时池占用不变，再创建新 core，
成功注册并取消一个 one-shot timeout，确认错误状态没有泄漏到后续会话。源码检索和 CMake core/backend
编译清单未发现当前默认生产路径的其他 one-shot 注册点；未来接入者仍须审查新调用的失败语义。

按 TDD 先观察旧实现触发断言，再验证新测试通过。Windows MinGW CTest 84/84；Linux
ASan/UBSan/LSan 与 lwIP pool 检查 CTest 84/84（51.70s，日志：
`build/reports/lwip/diplay-lwip-one-shot-asan.log`）。AGENTS.md 完整 Gradle 命令通过，250 个任务中
17 个执行、233 个缓存。API 28 x86_64 AVD、`debug.checkjni=1` 下 `:shared:connectedDebugAndroidTest`
40/40 通过（49s）；ddmlib 的 `getAvdPath` 子命令告警仍未影响运行。lwIP vendor hash/APK 核验在更新
精确摘要后完成。JNI OOM 注入、完整 R01/G3、物理设备/NCM、24 CPU 小时 fuzz 和隔离性能门槛仍未完成。

### P3 JNI 输出异常后的端点清理宿主回归（2026-10-23）

新增 `jni_output_exception_cleanup`，直接编译TCP/UDP JNI桥并以测试专用fake JNI函数表
令 `SetLongArrayRegion` 发布结果时挂起异常。用例覆盖UDP绑定、TCP普通监听、带选项监听、
主动连接和 accept 结果数组写入失败；逐项确认桥返回 `DP_INTERNAL`、未交付端点已关闭，
TCP listener 保持可用，UDP PCB、TCP listener 与活动连接计数符合预期。清除异常后再次
握手、accept，并正常关闭资源。Windows MinGW CTest 85/85通过；Linux ASan/UBSan/LSan
与lwIP pool保护 CTest 85/85通过（53.31s）。

这不是JVM、Android CheckJNI或实际Java OOM注入；引用/线程故障、
完整R01/G3和真机门槛仍待覆盖。生产JNI代码、ABI和后端选择未改变。

### P3 JNI 输入数组读取异常宿主回归（2026-10-23）

扩展同一测试专用fake-JNI夹具，在 `GetByteArrayRegion` 的 TCP connect 地址、UDP send
目的地址与payload、TCP write payload 四个读取点注入挂起异常。用例检查桥返回
`DP_INTERNAL` 且保留异常；connect 不创建活动端点，UDP 端点保持有效且不产生输出帧，
TCP stream 保持活动且不产生写入帧。清除异常后，UDP/TCP 端点可关闭，TCP stream 可继续
成功写入。Windows MinGW 下单项 `jni_output_exception_cleanup` 通过；Linux ASan/UBSan/LSan
与 lwIP pool 检查全量85项复跑通过（53.70s，日志：`build/lwip-host-linux/ctest.log`）；
Windows MinGW全量CTest复跑也通过85/85（2.48s，日志：`build/lwip-host-windows/ctest-jni-exceptions.log`）。
AGENTS.md完整Gradle单测、三模块lint与assemble命令再次通过，250个任务中12个执行、238个缓存命中。

这仅验证JNI桥对模拟的数组复制异常作出正确处理，不启动JVM，也不制造真正的Java数组
分配OOM；引用、线程异常及 Android 运行时注入仍待覆盖。生产JNI代码、ABI与后端选择未变。

### P3 JNI 数据输出异常与消费语义宿主回归（2026-10-23）

fake-JNI夹具继续覆盖 `udpReceive` 的target、source、metadata三个发布点，以及 `tcpRead`
payload发布点。每处挂起异常后，桥返回 `DP_INTERNAL` 且不再访问后续JNI数组；由于native
接收已消费数据，清除异常后的重试明确返回 `DP_WOULD_BLOCK`，不重放UDP数据报或TCP字节。
TCP活动stream和UDP端点保持健康，成功控制路径核对UDP来源地址、端口与负载。

按TDD先观察夹具未注入写数组异常时测试错误返回 `DP_OK`，随后增加 `SetByteArrayRegion` /
`SetIntArrayRegion`故障注入与“异常挂起期间JNI调用”计数。Windows MinGW完整CTest 85/85通过
（3.55s，`build/lwip-host-windows/ctest-jni-output-exceptions.log`）；Linux ASan/UBSan/LSan及
lwIP pool检查85/85通过（55.65s，`build/lwip-host-linux/ctest.log`）。AGENTS.md Gradle单测、
三模块lint与assemble也通过，250个任务中12个执行、238个缓存命中。

这些结果是宿主fake-JNI异常模拟，不是真正的JVM OOM或Android CheckJNI注入；实际Java OOM、
引用/线程故障、全进程资源峰值及设备门槛仍未覆盖。

### P3 JNI 入口挂起异常与数组长度异常回归（2026-10-23）

修正原型JNI数组桥在进入时已有挂起异常时仍调用 `GetArrayLength` 的问题；`create`、
`supply`、`input`、`takeOutput` 和 `stats` 现在会在触碰JNI数组前检查异常，`GetArrayLength`
之后也检查异常再继续。fake-JNI新增两类注入：入口预先挂起异常，以及 `GetArrayLength`
自身触发异常；覆盖熵复制、帧输入、输出读取和统计入口，并断言异常期间不发生后续JNI
访问。测试先在旧实现失败，再验证修复通过。

Windows MinGW CTest 85/85通过（2.16s）；Linux ASan/UBSan/LSan及lwIP pool保护CTest
85/85通过（53.45s）。AGENTS.md完整Gradle单测、三模块lint与assemble通过，250个任务中
17个执行、233个缓存命中；四ABI native库随 `:mobile:assembleDebug` 构建成功。JNI ABI与
生产后端不变。API 28 x86_64 AVD、`debug.checkjni=1` 下重新运行
`:shared:connectedDebugAndroidTest`，40/40通过（49s）；ddmlib旧版 `getAvdPath` 控制台警告
未影响结果。宿主测试使用fake JNI，不是实际JVM OOM或Android CheckJNI异常注入；引用/线程
故障、完整R01/G3及真机门槛仍待验收。

### P2 宿主报告解析 Windows worktree Git 来源（2026-10-23）

修复 WSL 中报告生成器无法解析 Windows 管理的 worktree `.git` 指针的问题。共享 Git
上下文辅助模块只在检测到可映射且存在的 Windows worktree gitdir 时，设置 WSL 挂载路径及
worktree 环境，并启用与 Windows checkout 一致的 `core.autocrlf`；普通仓库沿用原有 Git
行为。fuzz 和 benchmark 报告均使用该解析逻辑，并把辅助模块纳入源码摘要。

Python 宿主测试在 Windows 运行20项、9项因平台条件跳过；WSL运行20项且无跳过。WSL fuzz
smoke 运行成功，报告正确记录当前 Windows worktree 的 commit 与 dirty 状态。
修复前已启动的长时 fuzz campaign 不会自动重写报告，其 commit/dirty 仍未知，但保留源码摘要、
二进制摘要和运行数据；后续新 campaign 使用修复后的元数据。此项只改善来源记录，不增加 fuzz
CPU 预算或硬件验收证据。

### P4 fake network 全通道协商与 API 28 CheckJNI 回归（2026-10-23）

`ProtocolNetworkInjectionTest` 逐项验证主控制、事件、视频、计时、UDP 音频及控制、缓冲音频、
iAP tunnel、视频设置的端口都来自注入网络；另验证麦克风使用同一网络和真实 peer 元数据，
并在会话结束后释放各端点。当前 API 28 x86_64 AVD（`debug.checkjni=1`）执行
`./gradlew.bat :shared:connectedDebugAndroidTest --stacktrace`，40/40 通过、0 失败、0 跳过；
Gradle 报告位于 `shared/build/reports/androidTests/connected/debug/index.html`。

这是 fake network 协商和模拟器 JNI 运行证据，不证明 lwIP 全会话集成、iPhone/NCM、VPN 禁用
真机或低配 ARM 性能；对应 G1/G2/G4/G6 设备门槛仍未验收。

### P1.1 系统 UDP IPv4 最大负载边界（2026-10-23）

新增真实 IPv4 loopback 用例：65,507 字节 UDP payload 可完整发送、接收且内容一致；65,508
字节在调用系统 socket 前明确抛出 `IllegalArgumentException`。旧实现把 IPv4 超限数据交给
操作系统，复现为平台相关的 `BackendFailure`/`SocketException`。系统后端现在按目标地址族
分别执行 IPv4 65,507 与 IPv6 65,527 字节上限检查。`SessionNetworkContractTest` 15/15 通过，
包含新边界用例；定向 shared 单测和 `SystemAddressScopeTest` 命令均成功。

此项只证明宿主系统 socket 回环边界，不替代 Android 设备、lwIP JNI 或完整 UDP 故障矩阵；
新增确定性连接初始化竞态用例：单调时钟暂停在 socket 创建前，关闭会话后再放行连接，
要求返回 `CANCELLED`；资源注册入口会同步关闭迟到创建的 socket。该用例通过，但只覆盖
关闭先于资源注册这一分支。真实 IPv6 scope 及其他初始化阶段的关闭竞态仍是 P1.1 未完成项。

AGENTS.md 完整 Gradle 检查首次运行有一个预加载压力用例未在3秒内达到目标队列水位；该用例
隔离重跑通过，随后完整命令再次通过（250个任务中19个执行、231个缓存命中）。

### P1.1 会话关闭先于连接 socket 注册的竞态回归（2026-10-23）

新增 `SessionNetworkContractTest.sessionCloseWinsBeforeConnectRegistersItsSocket`：可控单调时钟
将连接暂停在创建 socket 之前，测试关闭会话后放行连接，确认迟到创建的 socket 在 `own()`
注册边界被同步关闭，调用以 `CANCELLED` 结束。该确定性用例通过；它只覆盖关闭先于资源注册
这一分支，不代表 listen/bind/connect 全部初始化交错或真实 IPv6 scope 已验收。合同类16/16
通过；AGENTS.md 完整 Gradle 检查成功，250个任务中15个执行、235个缓存命中。

### P3 JNI 统计数组分配异常宿主回归（2026-10-23）

扩展fake-JNI数组对象工厂，令 `stats` 的 `NewLongArray` 返回null并挂起分配异常；验证桥保留
异常、不再调用其他JNI函数，清除故障后可重新创建并填充11项统计数组。ASan首次检查发现测试
夹具为统计结果只分配了4个long；扩大夹具容量后，Windows MinGW与Linux ASan/UBSan/LSan配置
下的 `jni_output_exception_cleanup` 定向测试均1/1通过。该测试模拟JNI对象分配异常，不是真实
JVM OOM；完整Windows CTest与sanitizer套件结果见前文对应执行记录。

P3 配置受限分配失败项也已有证据：[原生定向矩阵](native-allocation-failures.md)使用链接器
分配拦截逐点注入 core、TCP/UDP、pbuf 与定时器分配失败，端点注册表/容量用例覆盖耗尽，
并在恢复/销毁后核对 heap、PCB、pbuf 与 MEMP pool 基线。该计划子项现标记完成；矩阵文档明确
它仍只是 R01 子集，尚不能据此判定 G3 或完整错误路径矩阵通过。

### P3 有界 DirectByteBuffer 租约与 ABI 8（2026-10-23）

TCP/UDP公共端点改用session-owned DirectByteBuffer池，单session容量上限256 KiB；新增
`tcpReadDirect`、`tcpWriteDirect`、`udpSendDirect`和`udpReceiveDirect` JNI入口，整体JNI ABI
升至8。池按容量桶复用并跟踪分配、活动租约和峰值；池关闭时立即回收空闲buffer，仍被命令
引用的buffer则等待租约归还。`CoreCommandQueue`增加退休回调，调用方超时或取消不会提前归还
仍在core线程执行的buffer，命令实际完成或丢弃后才释放租约。

`DirectBufferPoolTest`覆盖容量限制、池关闭与活动租约、重复释放；TCP/UDP适配测试覆盖等待中的
写入保持租约、取消/超时期间仍保持租约、核心退休后归零，以及失败写入和endpoint关闭后分配
计数回到基线。更新这些最终计数断言后，定向运行`DirectBufferPoolTest`、`LwipTcpStreamTest`
和`LwipUdpEndpointTest`成功。AGENTS.md规定的完整Gradle单测、mobile/home/maphost lint及
assemble命令成功，250项任务中45项执行、205项为UP-TO-DATE。

JNI宿主测试经DirectByteBuffer覆盖TCP读写、UDP发送接收与空数据报；Windows MinGW的
`jni_exception_tests`定向测试通过，Linux ASan/UBSan/LSan与lwIP池检查CTest 85/85通过。API 28
x86_64 AVD、`debug.checkjni=1`下`:shared:connectedDebugAndroidTest` 40/40通过，覆盖实际JNI
direct-buffer路径；完整Gradle构建也产出四ABI native库和Debug APK。

另补充TCP `tcpReadDirect`和UDP `udpReceiveDirect`在native已消费数据、随后向metadata数组发布
结果时发生JNI异常的回归：返回`DP_INTERNAL`，异常挂起期间不再调用JNI，direct目标保持原值，
清除异常后重试返回`DP_WOULD_BLOCK`，证明已消费载荷不会被错误重放。Windows MinGW完整CTest
复跑85/85通过。此项仍使用fake-JNI，不是实际JVM异常注入或Android CheckJNI错误注入。

fake-JNI还覆盖已关闭UDP/TCP listener句柄的二次关闭、TCP错误类型关闭，以及stale句柄上的
UDP direct receive和TCP accept：均映射为`DP_INVALID_ARGUMENT`，并保留输出数组/direct目标原值。
Windows MinGW定向测试和完整CTest均通过（85/85）；JNI跨线程访问、真实引用积累/回收及JVM
CheckJNI异常注入的覆盖当时仍待补。

随后在 API 28 x86_64 AVD、`debug.checkjni=1` 下新增并运行
`repeatedStatsCallsKeepJniReferencesBoundedUnderCheckJni`，循环调用 stats JNI 10,000 次并校验
每个返回数组；完整 `:shared:connectedDebugAndroidTest` 41/41 通过、0 跳过，包含既有跨线程
错误映射及 100 次 native 重建测试。测试期间 logcat 无 `JNI DETECTED ERROR`；出现的
`app_process` RegisterNatives 慢路径警告来自测试进程启动。源码复核未发现显式全局 JNI 引用或
线程 attach/detach 调用，数组引用在每次 native 返回时交还 JVM。由此补齐了 R03 的可复现线程
与引用 churn 子项；仍未注入真实 JVM OOM，且其他 R01/G3 失败路径、USB拔插和真机门槛未完成，
不据此标记 G3。

以上完成P3“有界命令/接收/发送队列与pbuf/DirectByteBuffer所有权计数”的宿主和模拟器子项，
不代表P3退出门槛或G3通过。真机并发关闭、USB拔插期间的native访问、实际iPhone/NCM、真实JVM
OOM与完整JNI故障矩阵仍未覆盖；用户确认当前没有参考设备或现成报告。

### P1.1 系统端点发布与会话关闭竞态、真实 IPv6 scope（2026-10-23）

增加可控端点发布屏障，先以红灯复现 listener 和 UDP socket 已成功 bind、session close 完成后，
旧实现仍成功返回已关闭 endpoint 的行为。`publish()` 现在在端点初始化后、交给调用方前与
session close 使用同一把锁线性化；若 session 已关闭或 resource 已停止，则以 `CANCELLED`
结束并关闭该 resource。listener、UDP bind、TCP connect、adopt 和 accept 新建 stream 共用此发布边界。
扩展回归分别覆盖 listener bind、UDP bind、主动 connect 和 accept 后的关闭交错，并保留
既有“关闭先于 connect socket 注册”用例。

另新增真实系统 link-local IPv6 测试：从当前宿主枚举具有非零 scope ID 的活动接口，在该地址上
完成 TCP listener/connect/accept 双向 payload，以及 UDP send/receive 与来源 endpoint 核对；
同时检查 session `linkId` 未与 OS scope index 混用。Windows 宿主 `SessionNetworkContractTest`
21/21 通过、0 失败、0 跳过；本地 XML 报告位于
`shared/build/test-results/testDebugUnitTest/TEST-com.shilapi.xcertplay.network.backend.SessionNetworkContractTest.xml`。

该测试证明当前宿主系统栈对真实 link-local scope 的 TCP/UDP 回环及关闭发布顺序，不证明 Android
VPN scope、NCM/iPhone 有线链路、G1/G2 真机门槛或完整故障矩阵；生产仍只选择系统后端。

随后 API 28 x86_64 AVD 上运行 `:shared:connectedDebugAndroidTest`，41/41 通过、0 跳过；
报告保存在 `shared/build/outputs/androidTest-results/connected/debug/`。这是模拟器仪器测试，
不替代 VPN 禁用设备、真实 CarPlay/NCM 或 G1/G2 真机证据。

继续按模拟器真实接口补测 IPv6 scope 后，新加 `SystemSessionNetworkIpv6ScopeTest`，在活动
link-local 地址上验证 Android TCP/UDP 回环；定向仪器测试 1/1 通过。该轮完整仪器运行先暴露
系统 TCP 读与本地 close 的竞态：Android 偶尔将本地关闭呈现为 `read() == -1`，旧实现把它当
正常 EOF 返回。读就绪后重新检查 endpoint 状态；修复后的系统 TCP 合同类 14/14 通过，新增
IPv6 scope 测试 1/1 通过。修复后完整 42 项运行有一次 native 原型 TCP 帧在现有 2 秒测试期限
内未出现；同一测试单独运行 1/1 通过，随后再次完整运行 API 28 仪器集 42/42 通过、0 跳过
（57 秒）。因此记录一次间歇性 native 测试超时和后续全绿复跑，不扩大为已解决的 native
产品缺陷。按本仓库 `AGENTS.md` 重跑的完整 Gradle CI 也成功，250 项任务中 41 项执行、209 项
为 `UP-TO-DATE`。

### P2/P3 当前源码 ASan/UBSan 宿主回归（2026-10-23）

在 WSL Ubuntu 20.04 / GCC 9.4.0 / CMake 3.16.3 下，以当前工作树重新配置并构建
`DIPLAY_SANITIZERS=ON`，再运行全部 CTest：85/85 通过，51.05 秒。记录与构建产物位于
`build/lwip-review-linux/`，CTest 明细为 `build/lwip-review-linux/ctest.log`。
本轮覆盖 cyclic timer 重排失败后的恢复与持续失败清理、one-shot timeout 分配失败、
ND6 队列分配失败后计数和资源恢复，以及现有 TCP/UDP、JNI、生命周期回归。
Linux kernel TAP/veth 未在这次命令中运行；不把宿主 sanitizer 结果当作 NCM、低配 ARM
或 G2 设备验收。

### P6 当前 native 源码三目标 fuzz 重跑（2026-10-23，进行中）

复核发现先前三项长跑的构建早于最近的 ND6 修复；旧 report、corpus 和日志已保留，
但旧时长不计入当前源码的 24 CPU 小时门槛。当前源码快照摘要为
`ebdcc9972cdeccc08a046a11c623f0756d33f262c9f898868d322e8b419e8125`，
`nd6.c` SHA-256 为 `26b48a9dcec3bb662f610dcdd0ec8c115f317dfa9a8a0b1898b8ca4edb43b047`。
截至本记录，三个独立 seed-7 campaign 均已按当前源码重建并运行；表中 CPU 时间来自各自
report 的 wait4 user+system 计数，未达到预算，不代表 fuzz 验收通过。

| Target | CPU / 28,800 秒 | 批次 | 失败批次 / 崩溃产物 | Binary SHA-256 | 报告 |
| --- | ---: | ---: | ---: | --- | --- |
| commands | 13,080 秒 | 1,255 | 0 / 0 | `1ca18cedd0f426fcd24a6e9f34129fcff50438d8816f11afdd6829bdc6e52c18` | `build/lwip-fuzz-full/commands-current/campaign-20261023T001544.794153Z/report.json` |
| lifecycle | 13,292 秒 | 1,251 | 0 / 0 | `39c509cc8a06f1587282ec0d6e1bf05bee1a92e21c7517889856cb9df4d41479` | `build/lwip-fuzz-full/lifecycle-nd6-current/campaign-20261023T001410.004684Z/report.json` |
| ethernet | 13,202 秒 | 1,307 | 0 / 0 | `90b0205db69ec070c82aa453b799de45df5ef23d2859851556852d13bfff6a92` | `build/lwip-fuzz-full/ethernet-nd6-patched-current/campaign-20261023T001351.362315Z/report.json` |

三个 report 的源码摘要一致，构建时均核对了 `nd6.c` 当前 SHA；本次状态复核时三个 campaign
仍在运行，均未达到 28,800 CPU 秒。之后 CMake 注册项、成本报告工具和普通回归测试源码发生变化，
只影响测试/构建元数据；复核确认没有修改 campaign 编译的生产 native 源，活动二进制 SHA 保持不变。
当前整棵工作树摘要因测试工具变化而不同于 campaign 起始摘要 `ebdcc997…`，不能把摘要变化误读为
活动二进制已按新源码重建。旧 campaign 因主动更换过期二进制
以 SIGINT 正常结束，退出码 130、已有批次无非零退出且无崩溃产物；该退出不视作 fuzz 缺陷，
但旧二进制的 CPU 时间不能替代当前源码预算。

### P6 对齐宿主 sanitizer 命令入口（2026-10-23）

`test-plan.md` 给出的 `--sanitizers address,undefined` 原先会被 `test_lwip_host.sh` 当成未知参数拒绝。
保留现有 `--sanitize` 兼容入口，并增加文档指定的 sanitizer 列表参数：
`address,undefined` 映射到当前 CMake 的 ASan/UBSan 开关；其他值明确报错。
新增入口回归先因 `Unknown option: --sanitizers` 失败，修复后 Python 脚本合同 23/23 通过，
包含 fake CMake/CTest 验证 sanitizer 参数与环境变量实际传递。

随后按计划中的精确命令 `bash scripts/test_lwip_host.sh --build-dir build/lwip-host-linux
--sanitizers address,undefined` 从当前源码重配并运行，Linux ASan/UBSan/LSan + lwIP pool
CTest 88/88 通过（55.47 秒）。Windows MinGW 8.1 / CMake 3.15.3 当前源码 CTest 也为
88/88 通过（2.19 秒）。根目录 `AGENTS.md` 要求的 Gradle 单测、三模块 lint 和 assemble
成功：250 项任务中 12 项执行、238 项 `UP-TO-DATE`。这补齐的是计划命令可复现性与宿主跨平台回归，
不提供 Linux kernel 吞吐、NCM/iPhone 或 G2 设备证据。

### P2 runner worker-allocation shutdown fallback 与完整 CI（2026-10-23）

通过可注入 `workerFactory` 覆盖启动 worker 分配失败、USB close worker 分配失败、inline USB close
失败以及由 core worker 发起停止时的关闭顺序。启动失败映射为 `BACKEND_INIT`，端口仍被关闭；清理
worker 无法创建时回退同步关闭，并把关闭错误保留为 suppressed failure；core worker 发起回退时先
销毁 core，再关闭端口；`awaitStopped` 只有在线程与 inline close 均结束后才发布 `STOPPED`。
`LwipPrototypeTest` 20 项测试通过。

完整仓库 CI 按根目录 `AGENTS.md` 命令运行并成功：`BUILD SUCCESSFUL in 8m 6s`，250 个任务中
18 项执行、232 项为 `UP-TO-DATE`。随后在 API 28 x86_64 AVD 上运行
`:shared:connectedDebugAndroidTest`，42/42 通过、0 跳过。验证过程中还调整了两个测试时序前提：50 ms deadline 用例不再
要求新 worker 必须在截止前获得调度；CoreCommandQueue 测试在启动 pump 前等待 `hasReady()`，而非
只观察包含 PREPARING 阶段的 live command 数。对应定向回归及最终完整 CI 均通过。一次 Robolectric
teardown 的 2 秒关闭断言在重负载时曾超时；单项重跑通过，最终完整 CI 也通过。

当前 runner 仍为 P2 原型，生产后端继续选择系统网络栈。由于没有参考设备，真机关闭/拔插、
USB/iPhone 交互及内存、吞吐门槛仍未覆盖；native fuzz 三目标也尚未达到各自 28,800 CPU 秒。

### P3 DirectByteBuffer 分配 OOM 的受控恢复回归（2026-10-23）

为 `DirectBufferPool` 的 direct allocator 增加默认保持 `ByteBuffer.allocateDirect` 的依赖入口，
测试注入 allocator 抛出的 `OutOfMemoryError`，不实际耗尽 JVM 堆或直接内存。先运行新增回归，
确认因 allocator 注入入口缺失而编译失败；实现后 `:shared:testDebugUnitTest --tests
com.shilapi.xcertplay.network.backend.lwip.DirectBufferPoolTest` 成功。测试确认异常映射为
`RESOURCE_LIMIT`、保留原始 cause、失败时分配与租约计数均为0，并可在撤销故障后重试得到 direct
buffer、归还租约。该证据只覆盖 DirectBufferPool 的受控分配失败路径，不等同于实际 JVM OOM，
也不完成 R01/G3 或 JNI 故障矩阵。

按仓库 `AGENTS.md` 执行的完整 Gradle CI 首轮只有
`CarPlayControllerUsbServiceTest.aMissingIphoneIsReportedWithWhatAndroidListsOnce` 的关闭等待
断言失败（2,047 ms，门槛 2,000 ms）；定向重跑整个测试类通过，随后不改生产代码重跑完整命令，
`BUILD SUCCESSFUL in 7m 46s`，250 个任务中16项执行、234项为 `UP-TO-DATE`。因此记录为一次
未复现的 teardown 超时，不把它归结为已确认根因或 lwIP 回归。

之后在同一 API 28 AVD 上复跑 `:shared:connectedDebugAndroidTest`，确认设备属性
`debug.checkjni=1`、SDK 28，42/42 通过、0 跳过。前两次启动时 SDK adb 与 PATH 中的 adb
server 版本不一致，模拟器短暂显示 offline，Gradle 因无在线设备而退出；执行
`adb reconnect offline` 后设备恢复。两次启动失败均未运行测试用例。

### P2 冷启动定时器持续分配失败后重试（2026-10-23）

补充 `core_timer_persistent_allocation_failure`：令冷启动 `MEMP_SYS_TIMEOUT` 持续失败，连续两次
创建均须返回 `DP_RESOURCE_LIMIT`、保持输出 core 为空，并核对 netif、heap 与全部 MEMP pool
仍在基线；撤销注入后再次创建、补齐幂等周期定时器并完成 DAD。现有实现通过
`lwip_init_checked()` / `sys_timeouts_init_try()` 将状态显式传回 core 创建边界，不重复运行模块
初始化。三个单点瞬时失败用例与新增持续失败重试用例均通过；当前源码 Linux
ASan/UBSan/LSan + lwIP pool 检查完整 CTest 为 86/86（52.04 秒），Windows MinGW 完整 CTest
也为 86/86（2.83 秒）。按根目录 `AGENTS.md` 重跑 Gradle 单测、三模块 lint 和 assemble
成功：250 个任务中12项执行、238项 `UP-TO-DATE`。该验证覆盖宿主分配注入，不替代设备内存压力、
真实 USB/NCM 或 G3 其他故障路径。

### P2.2 宿主 core 配置成本报告（2026-10-23）

新增 `lwip_config_cost_report`，从 MEMP pool 配置与 core 统计核算启动容量，并将 UDP 与
TCP listener 分别创建至配置上限，再检查 endpoint 分配和清理回基线。Linux x86_64
ASan/UBSan 构建报告启动配置容量 1,608,128 B、32 UDP + 16 listener 后
2,136,384 B；Windows x86_64 MinGW 对应 1,552,408 B 和 2,080,664 B。两者当前被测配置
均低于 16 MiB core 参考线；MEMP 数值因宿主 ABI 不同而有差异。

该报告计入全量 lwIP heap/pool 容量、core/帧/元数据预留和当前 endpoint 动态分配；场景未建立
活动 TCP stream，且不测 Kotlin/ART、runner/NCM/USB 队列、线程栈、进程 PSS 或 Android ARM。
因此只完成宿主 core 配置成本子项，不能作为完整后端 16 MiB 或 G2 验收。Linux 吞吐微基准
因当前 WSL1 不允许 `unshare --net`（`Permission denied`，且缺少 `/dev/net/tun`）未运行；
G2 性能预检仍未完成。详细口径和复现方式见[配置成本报告](configuration-cost.md)。

新增 `config_cost_report` CTest 后，Linux ASan/UBSan/LSan + lwIP pool guards 和 Windows
MinGW 完整 CTest 均 88/88 通过；根目录 `AGENTS.md` 要求的完整 Gradle 单测、三模块 lint、
三模块 assemble 命令成功：BUILD SUCCESSFUL，250 项任务中 12 项执行、238 项 UP-TO-DATE。
再以仓库 CI 入口 `bash scripts/test_lwip_host.sh --sanitize` 实跑，88/88 通过，
耗时 53.56 秒；日志为 `build/lwip-host-linux/ctest.log`。
`scripts/check_lwip_native.py` 通过。机器可读结果保存在
`build/reports/lwip/config-cost-linux-x86_64-20261023.json` 与
`build/reports/lwip/config-cost-windows-x86_64-20261023.json`。按宿主 CI 工作流运行的 Python
检查也通过：native source checker 7/7、fuzz/benchmark contracts 22/22；这些单元测试不替代
实际隔离网络吞吐基准。`.github/workflows/android.yml` 已增加配置成本 JSON 采集和 artifact 上传；
本地按同一路径生成的 `config-cost-report.json` 可解析且报告预算状态为 true，尚未运行远程 GitHub Actions。

随后再次执行 `bash scripts/test_lwip_host.sh --sanitize`：当前工作树 CTest 88/88 通过，54.82 秒，
包括 `lifecycle_soak`、定时器持续分配失败重试及 TCP unsent 追加失败矩阵；日志更新在
`build/lwip-host-linux/ctest.log`。脚本明确说明本次没有请求 kernel interoperability；当前 WSL1
缺少 `/dev/net/tun` 且拒绝 `unshare -n`，所以隔离 Linux 吞吐微基准仍无法在这台主机执行。

另将现有 native core、prototype wrapper、runner、DirectByteBuffer/命令队列和 NCM 原型的源码上限
逐项映射到 `performance.md`，并区分固定容量、逻辑保留上限及重叠 buffer 记账，见
[`configuration-cost.md`](configuration-cost.md#与-16-mib-初始预算的代码映射)。据此冻结的仍是原有
16 MiB、吞吐与延迟等初始门槛，没有放宽；这完成 P0.2 的预算对照子项，不等同于完整 backend 峰值、
ARM 低配性能或 G2 验收。

### P3 TCP 已有 unsent 队列追加的分配失败回归（2026-10-23）

扩展 `tcp_write_allocation_failures` 之外的空队列覆盖：先写入 8192 字节，使初始拥塞窗口
留下未发送前缀，再向同一 stream 追加 8192 字节，并逐点注入每次 native 分配失败。
瞬时及持续注入各覆盖 31 个位置。全部失败返回 `WOULD_BLOCK`、`copied=0`，此前未发送前缀
和 heap/MEMP 占用保持完整；撤销故障后重试，逐字节验证前缀和追加数据顺序正确、无重复，
关闭后资源回到基线。Linux ASan/UBSan/LSan + lwIP pool CTest 87/87 通过（52.64 秒），
Windows MinGW CTest 87/87 通过（2.10 秒）。按根目录 `AGENTS.md` 重跑完整 Gradle 单测、
lint 和 assemble 成功：250 个任务中 12 项执行、238 项 `UP-TO-DATE`。

这是 R01 的 TCP unsent 拼接宿主子集，不覆盖全部拥塞窗口、输出分配及并发取消路径，
也不代表 R01/G3 或真实 NCM/iPhone 验收通过。

### P6 N08 IPv6 未加入组播洪泛宿主子项（2026-10-23）

新增 `ipv6_unjoined_multicast_flood`：向 4,096 个不同的 link-local IPv6 multicast group
发送校验有效的 UDP 帧，确认未加入的组播流量被丢弃，不产生输出、UDP 接收队列、pbuf、heap
或 MEMP pool 增长；随后同一 UDP endpoint 仍可接收合法单播并正常关闭，资源计数回到创建前基线。
lwIP `LWIP_PLATFORM_DIAG` 在此构建配置中为 no-op，因此没有逐包诊断输出。Windows MinGW
完整 CTest 89/89 通过（2.84 秒）；Linux ASan/UBSan/LSan 与 lwIP pool guards 完整 CTest
89/89 通过（54.98 秒）。按根目录 `AGENTS.md` 重跑 Gradle 单测、三模块 lint 和 assemble
成功：250 项任务中 12 项执行、238 项 `UP-TO-DATE`。

该用例只覆盖 N08 的未加入 IPv6 组播输入子集；端口/端点上限和 TCP 半开连接由既有定向用例覆盖，
完整 N08、实际网卡洪泛、设备日志限频及 G2/G6 真机门槛仍未完成。

### P3 原型 wrapper 上下文分配失败回归（2026-10-23）

新增 `prototype_context_allocation_failure`，对原型 wrapper 分配其固定上下文的首次 libc `calloc` 注入失败，验证错误映射为 `DP_RESOURCE_LIMIT`、输出句柄清零、netif/heap/MEMP 资源保持基线；解除注入后创建与销毁成功。MinGW 首次访问 C TLS 时运行时会先申请 TLS 存储，因此用例先预热此路径，确保故障命中目标上下文分配而不是运行时 TLS 初始化。

Linux ASan/UBSan/LSan 与 lwIP pool guards 完整 CTest 90/90 通过（57.96 秒），Windows MinGW 完整 CTest 90/90 通过（2.65 秒）。根目录 `AGENTS.md` 要求的 Gradle 单测、mobile/home/maphost lint 与 assemble 命令成功：250 个任务中 12 项执行、238 项 `UP-TO-DATE`；`scripts/check_lwip_native.py` 和 `git diff --check` 均通过。该用例只补充 R01 的一个宿主分配位置，不代表完整 R01/G3、真实设备或 NCM 验收通过。

### P3 IPv6 重组上下文分配失败且无可淘汰项（2026-10-23）

新增 reassembly_context_allocation_failure_without_victim，验证没有已有重组上下文可回收时，
MEMP_IP6_REASSDATA 首次分配和 lwIP 回收重试均失败：首片被丢弃，重组上下文、pbuf 与 heap
不增长，核心保持健康；撤销持续注入后，使用同一数据报标识重传并完整收包。用例先按预期暴露
测试注入器只支持一次性失败的问题，随后让其复用已有 persistent 标志表达池分配持续失败，
既有一次性失败测试保持原语义。

Linux ASan/UBSan/LSan 与 lwIP pool guards 完整 CTest 91/91 通过（54.75 秒），
Windows MinGW 完整 CTest 91/91 通过（8.08 秒）。按根目录 AGENTS.md 执行的完整
Gradle 单测、mobile/home/maphost lint 与 assemble 成功：250 个任务中 12 项执行、
238 项 UP-TO-DATE。当前源码三项 sanitizer fuzz campaign 仍在运行：commands 8,974 秒、
lifecycle 9,084 秒、ethernet 9,086 秒，均为 0 失败批次、0 崩溃产物，尚未达到每项
28,800 CPU 秒。完整 R01 和设备验收仍未完成。

### P4 显式用户态有线宿主接入（2026-10-23）

`CarPlayController` 现在按每次启动的 `WiredBackend` 走现有 VPN 路径或显式 USERSPACE 路径；后者把 NCM 所有权交给 `NcmFramePort`、lwIP 网络和 `CarPlaySessionService`，并用实际 listener 端口构建 iAP2 通告。`AUTO` 当前仍映射至 VPN，直到有发布验证证据；显式用户态分支不调用 VPN service。服务 owner 测试覆盖第二个用户态 attachment 替换第一个、旧 owner 无法关闭新网络，以及最终 owner detach 会关闭网络；selector 测试覆盖 VPN 默认与显式用户态选择。

定向 `CarPlaySessionServiceTest` 和 `WiredBackendSelectorTest` 通过。该接入还未完成 P4 全通道端到端宿主验证、实际 NCM/peer 地址审计以及 VPN/无线及 Activity 重建回归；P4/G4 与真机验收仍保持未完成。P5 已补齐持久化偏好、版本/ABI 成功缓存、受限 AUTO 回退、Advanced 设置和诊断报告，见下节。

### P5 后端选择、设置与诊断（2026-10-23）

`AUTO` 保留 VPN 首选路径；只有版本/ABI 匹配的 USERSPACE 成功缓存存在时，明确的 `VPN_UNAVAILABLE` 才允许本次连接尝试一次 USERSPACE。用户拒绝、取消、USB、MFi、协议和一般初始化故障均不跨后端回退。显式 USERSPACE 不调用 VPN 服务；显式 VPN 不改变后端。偏好与成功缓存持久化，缓存可从 Advanced 设置清除。

诊断快照随本次有线尝试更新，并进入现有诊断报告。它只保留受限枚举、白名单版本/ABI、MTU、失败阶段和聚合计数；NCM 短写/未就绪/重同步、lwIP 丢包/发送、命令与帧队列高水位、直接缓冲峰值及资源 scope 数可见，不保存地址、媒体、密钥、异常原文或载荷。native 加载失败有独立失败码和可操作状态提示，不会触发下一轮回退。

定向共享模块与 common 测试通过：selector 回退/不回退组合、native 加载错误分类、诊断报告固定字段和隐私过滤、显式 USERSPACE 不准备 VPN、设置持久化/IA/翻译，以及紧凑日文、完整英文、紧凑阿拉伯 RTL 的大字体截图。最新完整根目录 Gradle CI、宿主 sanitizer CTest 和 Python 脚本回归已通过，详见下方最终复核记录。缺少真实设备与抓包，实际 NCM/iPhone、peer 地址和完整有线会话验收未覆盖；P4/G4 和最终设备门槛不变。

### P5 当前工作树最终回归（2026-10-23）

根目录 `AGENTS.md` 指定的完整命令再次通过：shared/common/home 单测、mobile/home/maphost lint 与三个 debug assemble 均成功（250 个任务中 17 项执行、233 项 `UP-TO-DATE`）。本轮 `bash scripts/test_lwip_host.sh --sanitize` 的 Linux ASan/UBSan/LSan + lwIP pool CTest 为 91/91；`python3 -m unittest discover -s scripts/tests -v` 在 WSL Linux 下 30/30 通过，`scripts/check_lwip_native.py` 与 `git diff --check` 通过。

第一次完整 Gradle 尝试在三个 fuzz worker 并发时有一个既有 buffered-audio socket 测试未能在 3 秒内填到等待阈值；该测试单独重跑通过，降低后台 campaign 优先级后再次运行完整 CI 通过，期间没有改动该测试或生产音频代码。记录为负载相关的单次超时，不视为 lwIP 回归。

当前三项 seed-7 sanitizer campaign 仍在运行：commands 13,080/28,800 CPU 秒、lifecycle 13,292/28,800、ethernet 13,202/28,800，均 0 失败批次、0 崩溃产物，尚未达到计划预算。工作树没有可用的 iPhone/ADB 参考设备或基线报告；Linux namespace 吞吐测试也受 WSL1 能力限制。因此 24 CPU 小时 fuzz、P0/G0/G1 设备证据、P2 隔离吞吐与低配 ARM、P4/G4 全通道，以及 P6/G6 和 P7 仍保持未完成。

### P5 最终验证补记（2026-10-23）

修复 Android 仪器测试中的模拟时钟绑定：测试原先把尾随 lambda 传给了 `ensureNativeLoaded`，实际仍读取系统时钟，导致 ND6/DAD 场景推进时间后未触发预期定时器；改为显式传入 `clockMillis` 后，完整 `:shared:connectedDebugAndroidTest` 通过，42/42。测试期间临时加入的 native 调试日志已全部移除。

最终复核结果：根目录 `AGENTS.md` 指定的 Gradle 单测、mobile/home/maphost lint 与 assemble 命令成功（250 项任务，14 项执行、236 项 UP-TO-DATE）；`bash scripts/test_lwip_host.sh --sanitize` 的 Linux ASan/UBSan/LSan + lwIP pool CTest 91/91 通过；WSL Python `scripts/tests` 30/30 通过；`git diff --check` 通过。

旧的三项 seed-7 sanitizer fuzz campaign 未达到预算，且报告构建源码 SHA `ebdcc997…` 与当前工作树完整源码摘要 `3a1461b3…` 不同，因此不能算作当前完整源码树的 24 CPU 小时验收。停止旧任务时 commands 为 15,723.2 CPU 秒（1,505 批）、lifecycle 为 15,565.0（1,463 批）、ethernet 为 15,605.1（1,544 批）；均为 0 个失败 batch、0 个 crash。三项均因换用已修正的 campaign 入口而通过 SIGINT 有序停止，报告 return code 130 表示 KeyboardInterrupt，预算未完成；旧记录仅作历史证据。没有可用 iPhone/ADB 设备或脱敏基线报告；WSL1 也缺少 TUN 并拒绝 network namespace，因此真机 NCM/iPhone、P0/G0/G1 设备证据及 P2 隔离吞吐门槛仍未覆盖，P4/G4 全通道和后续设备门槛继续保持未完成。

### P6 fuzz 入口预检回归与当前源码 campaign 重启（2026-10-23）

在重新启动长时 campaign 时，真实执行 `fuzz_lwip.sh` 暴露参数预检缺陷：`python -c` 通过 `runpy.run_path` 加载驱动，但 Python 没有把驱动所在的 `scripts/` 目录加入模块路径，`lwip_git_context` 导入以 `ModuleNotFoundError` 失败，构建尚未开始。新增回归直接提取并执行 shell 入口使用的预检语句；先确认它按上述原因失败，再修复为显式加入驱动同目录。

修复后 Windows 定向 fuzz-driver 单测 12 项中 10 项通过、2 项因 `wait4` 仅支持 Linux 而跳过；WSL Python 全套 31/31 通过；`bash -n scripts/fuzz_lwip.sh`、`--help` 及真实 commands 1-run smoke 均通过。Smoke 报告为 `build/lwip-fuzz-entry-smoke/campaign-20261023T052349.963964Z/report.json`，正常退出但标记 `mode=smoke`、`cpu_budget_complete=false`；它不计入长时 CPU 门槛。

三项新的 8 CPU 小时 seed-7 sanitizer campaign 已从修复后源码独立构建启动。它们的报告源码 SHA 和独立重算的当前源码 SHA 一致：`6ec4b8a7fdbbc8e9aae3e0478e287b22b5445f21a7f4495cac7e7fbf9a4df234`。最新收到的快照为 commands 10.4 CPU 秒/1 批、lifecycle 164.5/16 批、ethernet 163.3/16 批，均 0 个失败批次、0 个 crash，尚未接近 28,800 秒预算；各 worker 正持续运行。此处 SHA 覆盖固定 native backend 源树和 fuzz 构建入口文件，不包含 Kotlin/Android 层，因此仍需单独依照对应计划门槛验收那些层。

### N08 半开 TCP 池耗尽边界（2026-10-23）

新增宿主帧级用例 `tcp_half_open_capacity`，将单个 listener 的 SYN_RCVD 项填满 32 个 PCB，验证第 33 个 SYN 被拒绝、关闭 listener 后 PCB 与内存计数归零且端口可复用。新增 `tcp_half_open_pool_exhaustion` 覆盖两个 listener 共享 PCB 池的情形：一个半开连接占用小 listener 的 backlog，另一个 listener 占用剩余 31 个 PCB；此时其 backlog 尚未满但 PCB 池已耗尽，测试捕获到 accept 回调曾对 lwIP 以 `ERR_MEM` 报告的空 PCB 调用 `tcp_abort(NULL)`。回调现对空 PCB 安全返回，释放容量后下一次 SYN 可接受，全部资源归还且端口可重绑。测试先验证了旧代码会触发空指针 abort 计数，修复后该用例及 `tcp_endpoint_limits`、`tcp_half_open_capacity`、`tcp_endpoint_lifecycle` 均通过。

该覆盖完成 N08 的半开 backlog/PCB 池子场景。既有 `tcp_endpoint_limits` 通过真实握手填满 32 个已建立连接的 PCB 上限，额外 SYN 不会产生可接受连接，随后关闭全部已有流并检查资源与端口恢复；`ipv6_unjoined_multicast_flood` 验证宿主输入路径对 4,096 个未加入组播组的处理。完整临时端口区间耗尽、实际网卡洪泛及设备日志限频仍未完成。修复后 Linux ASan/UBSan/LSan CTest 93/93 通过，根目录 `AGENTS.md` 完整 Gradle CI 也通过（250 项任务，17 项执行、233 项 `UP-TO-DATE`）；API 28 x86_64 AVD 上 `:shared:connectedDebugAndroidTest` 43/43 通过，包含修复后的四 ABI native 构建。模拟器集成套件不制造跨 listener 的 PCB 耗尽输入，精确故障路径由上述宿主回归覆盖。

最新 seed-7 sanitizer campaign 快照：commands 2,007.2/28,800 CPU 秒（194 批）、lifecycle 1,975.3/28,800（190 批）、ethernet 2,123.5/28,800（206 批），均 0 个失败批次、0 个崩溃产物，进程仍运行。这些 campaign 在本次 TCP 回调修复前构建，报告 native 源码 SHA `6ec4b8a7…`，当前修复后摘要为 `37b734d4…`，故这些时长不计入最终源码的 24 CPU 小时门槛。设备/网络基线门槛也仍未完成。

### P4 native SessionNetwork 上的 AirPlay 通道协商（2026-10-23）

新增 `LwipAirPlayChannelsInstrumentationTest`，在 API 28 Android 模拟器上通过实际 JNI lwIP core 和 `LwipSessionNetwork.native` 执行 AirPlay base SETUP，以及包含屏幕、UDP 音频、缓冲音频、iAP 和视频设置的 stream SETUP。测试检查响应端口有效且各 TCP/UDP 协议端口不冲突，并在 session 关闭后确认 native resource scopes 回到零。定向 `:shared:connectedDebugAndroidTest` 通过，1/1。

此用例的控制连接和 EthernetFramePort 是确定性模拟对象，没有真实 NCM、iPhone peer 收发或解密媒体流量；它补上 native 端点实际绑定/释放的模拟器证据。结合现有 peer/source 与 lwIP health instrumentation 后，方案中的 G4 自动化宿主/模拟器门槛通过；真实设备上的地址与媒体验收，以及 VPN/无线/Activity 重建回归仍未覆盖。随后完整 `:shared:connectedDebugAndroidTest` 在该模拟器通过 43/43；根目录 `AGENTS.md` 指定的单测、三模块 lint 与 assemble 命令也通过，250 项任务中 14 项执行、236 项 `UP-TO-DATE`。本次记录基于 dirty worktree、HEAD `5d2538bb021b331f600370183254a24cb6dc12b3`，测试 APK `shared/build/outputs/apk/androidTest/debug/shared-debug-androidTest.apk` SHA-256 为 `b8392c076846697c9cc3d7df700c7df9d28cc8810f600c0b2a6c552291326860`；设备为 `diplay-lwip-api28` AVD，API 28、x86_64。JUnit XML 在 `shared/build/outputs/androidTest-results/connected/debug/TEST-diplay-lwip-api28(AVD) - 9-_shared-.xml`。`git diff --check` 通过。

### P4 配对存储的会话重建回归（2026-10-23）

新增 `AirPlayPairingPersistenceTest`，验证一次新配对通过 `PairingStore` 保存到持久化层后，host 重建可重新读取同一 Ed25519 公钥；恢复过程不会再次触发保存回调，且输入/读取数组的防御性拷贝保持有效。旧实现的 RED 结果确认恢复每条已保存记录都会再次调用 `onSave`；新增 `PairingStore.restore` 只更新内存而不重复写回，正常新配对仍经 `save` 持久化。该测试覆盖 JVM 持久化与内存 host 重建，不替代 Activity 实际重建、VPN/无线切换或真机配对会话验收。

修改后的 `:common:testDebugUnitTest --tests com.shilapi.xcertplay.AirPlayPairingPersistenceTest` 定向回归通过。随后根目录 `AGENTS.md` 九项 Gradle 命令全部成功：250 个任务中47项执行、203项 `UP-TO-DATE`；API 28 x86_64 AVD 上 `:shared:connectedDebugAndroidTest` 43/43 通过。模拟器的 ddmlib `getAvdPath` 子命令告警未影响测试运行。`python scripts/check_lwip_native.py` 与 `git diff --check` 通过；原生宿主 sanitizer CTest 仍为前述 93/93，本轮未改 C/JNI 源码。当前 seed-7 三目标 fuzz campaign 仍在运行，源码摘要与已构建目标匹配，尚未达到每目标 28,800 CPU 秒。缺少物理设备与基线报告，G0/G1 真机、实际 NCM/iPhone、低配 ARM 和最终设备矩阵仍未验收。

### P2 宿主脚本测试的 Windows / POSIX 分流（2026-10-23）

新增宿主 harness CLI 用例在 Windows Python 3.6 下暴露两项测试夹具兼容问题：该解释器尚不支持 `subprocess.run(capture_output=...)`，且 Windows Python 不应尝试用 WSL launcher 执行 POSIX 临时路径。夹具改用 `stdout`/`stderr=PIPE` 和显式 UTF-8 错误替换，并只在 POSIX 环境运行。Windows Python 3.6 脚本套件 31 项运行成功、10 项按平台跳过；WSL Linux 完整脚本套件 31/31 通过。此修正仅限测试夹具，没有改变宿主脚本或产品实现。

### P4 CarPlayHostActivity 实际重建（2026-10-23）

新增 `mobile` 应用级 Android instrumentation runner 与 `CarPlayHostActivityRecreationInstrumentationTest`。测试在 API 28、x86_64 `diplay-lwip-api28` AVD 上启动真实 Activity，调用 `ActivityScenario.recreate()`，确认实例已替换、Activity 未结束，并重新载入持久化的 AirPlay 公钥及 `USERSPACE` wired backend 偏好。`MfiTarget.USB_CH341` 使测试不依赖未打包的本地 MFi 凭证或远程服务；模拟器未连接 USB accessory 或 iPhone，测试只验证 Activity 与偏好重建，不代表 CarPlay 会话连续性。

验证命令 `ANDROID_SERIAL=emulator-5554 .\gradlew.bat :mobile:connectedDebugAndroidTest` 成功，1/1 通过；JUnit XML 为 `mobile/build/outputs/androidTest-results/connected/debug/TEST-diplay-lwip-api28(AVD) - 9-_mobile-.xml`。第一次夹具使用未配置服务器的 REMOTE MFi target，启动路径按预期拒绝该无效配置；改为 USB_CH341 后测试通过。API 28 上撤销麦克风权限会 force-stop instrumented package，因此测试只授予 RECORD_AUDIO，不在 runner 尚未返回结果时撤权。VPN/无线/配对/断线组合回归、真实附件及真机网络验收仍未覆盖。

此次修改后，根目录 `AGENTS.md` 指定的完整九项 Gradle 命令通过：BUILD SUCCESSFUL，250 个任务中 19 项执行、231 项 `UP-TO-DATE`；`git diff --check` 通过（仅有仓库既有 CRLF 转换提示）。Activity instrumentation 的独立结果为 API 28 x86_64、1/1 通过。VPN/无线/配对/断线组合及物理设备门槛仍未覆盖。

### 当前源码 fuzz campaign 快照（2026-10-23）

三项 seed-7 sanitizer campaign 仍在独立进程运行，当前报告与 native 输入源码 SHA `37b734d420b4db4a8af452ca8eb062de4465e9b94deca3b303e259fce1de1d13` 匹配，报告记录的构建二进制 SHA 也各自与实际文件匹配：commands 2,613.6/28,800 CPU 秒（262 批），lifecycle 2,629.2/28,800（255 批），ethernet 2,378.2/28,800（250 批）；均 0 个失败批次、0 个崩溃产物。24 CPU 小时总预算尚未完成。

### P4 无线/用户态切换与配对断开组合回归（2026-10-23）

在 `CarPlaySessionServiceTest` 增加无线→用户态→无线的实际 host attachment 顺序：断言旧 listener 端口释放、用户态 attachment 使用自己的端口、PairingStore 中的配对数据跨切换保留、撤销旧用户态 owner 不会关闭新的无线 attachment，最终 detach 会释放端口和用户态网络；同时确认该无线/用户态路径没有触发 VPN scope。用户态网络使用可跟踪关闭的系统 socket 代理，以隔离验证 service/host 所有权；native lwIP 通道绑定由前述 API 28 instrumentation 单独覆盖。

验证 `:shared:testDebugUnitTest --tests com.shilapi.xcertplay.network.CarPlaySessionServiceTest --tests com.shilapi.xcertplay.network.CarPlayVpnScopeTest` 成功：组合类在 SDK 25/33 共 6 次运行通过，VPN scope 类在 SDK 28/33 共 14 次运行通过。`:common:testDebugUnitTest --tests com.shilapi.xcertplay.AirPlayPairingPersistenceTest` 也通过（1/1），验证持久化配对在 host 重建后恢复。上述是自动化边界证据，不代表物理 VPN、无线或 iPhone 会话验收。

### P4 无线/用户态切换回归及本轮最终复核（2026-10-23）

首次按根目录 `AGENTS.md` 重跑完整 Gradle CI 时，common 1587 项中有 1 项失败：
`DashboardContentDeliveryTest.aPausedSelectionWaitsForResumeWithoutGrantingMapZoom` 在暂停后观察到 5 次 event 写入而非 1 次。单项与整个测试类重跑通过。追查确认该类直接驱动 controller 的集群 UI 状态，但 `CarPlayController` 启动的全局 `BydClusterMapPause` ticker 仍会每秒回调当前 controller；恰好在此处介入时会重新发送 `showUI`/keyframe，再与待选内容写入竞态。测试现在在该用例中隔离全局 ticker 回调，保留原有暂停、恢复与 map zoom 断言；定向用例通过。没有改生产 UI 行为或放宽断言。

修复后按 `AGENTS.md` 的完整九项 Gradle 命令通过：`BUILD SUCCESSFUL in 7m 43s`，250 项任务中 15 项执行、235 项 `UP-TO-DATE`。`python scripts/check_lwip_native.py` 和 `git diff --check` 通过；后者仅输出仓库既有 CRLF 转换提示。尝试运行 `:shared:connectedDebugAndroidTest` 时，APK 构建成功，但测试启动失败：SDK 内 ADB 41 与 PATH 中 ADB 32 互相重启 server，Gradle 最终报告 `No connected devices`。该次不计为 instrumentation 通过；此前 API 28 x86_64 AVD 的 43/43 结果仍见上文。当前仍无真实 iPhone/NCM 设备或脱敏无线/VPN 基线报告。

本轮 CI 期间临时将三项 campaign 降至 nice +19，完成后恢复 nice +10；campaign 均持续运行、来源与构建二进制摘要匹配。恢复优先级后的快照为：commands 4,344.4 CPU 秒 / 427 batches，lifecycle 4,330.7 / 414，ethernet 4,365.1 / 466；均 0 个失败 batch、0 个崩溃产物。源码 SHA 为 `37b734d420b4db4a8af452ca8eb062de4465e9b94deca3b303e259fce1de1d13`；三项目标 binary SHA 分别为 `aaeedef34e3ae706c7e13d01f0637ed4d65d8f0f33cabaef21fba3509725fbd1`、`d4831a21816e9fb688e444379bc28ad3b17a49e503c5a50290b2b9c5aa15bdac`、`7ffd52ee2419581830099509431022d067b984a787d6508f2b0d59e9eabb3e70`。每项 28,800 CPU 秒预算仍未完成。

### P6 release 变体本机构建预检（2026-10-23）

为覆盖未运行 sanitizer 的 Android 发布变体，使用本机 Android debug keystore 作为仅限本地验证的签名输入，执行 `:mobile:assembleRelease`，结果 `BUILD SUCCESSFUL in 3m 25s`，129 项任务中 55 项执行、74 项 `UP-TO-DATE`。该 APK 不是使用项目发布密钥签名的候选包。

`python scripts/check_lwip_native.py --apk mobile/build/outputs/apk/release/mobile-release.apk` 通过，APK SHA-256 为 `920c5fcb78cf7a940079327ce337701a410b558cbc4fa268b0b24b1af239a5fb`，文件大小 19,442,826 bytes。arm64-v8a、armeabi-v7a、x86、x86_64 四 ABI 的 native LOAD 段和 APK ZIP 数据偏移均满足 16 KiB 对齐，库未压缩；静态 data+BSS 范围 1,473,720–1,491,128 bytes。该数据只验证 release 变体与包内布局，不能代替设备上的 release 吞吐、延迟、CPU 或运行时内存预算，P6 发布构建性能门槛仍未通过。

在 WSL Ubuntu 20.04 上运行 `python3 -m unittest discover -s scripts/tests -v`，31/31 通过。当前主机未安装 Docker，WSL 仍为 1，P2 Linux 隔离网络基准没有可用的替代执行环境。

### P2 冷启动周期定时器恢复选项 A 复核（2026-10-23）

按用户选择核对的选项 A 在当前工作树已有实现：`lwip_init_checked()` 把周期定时器初始化结果返回给 backend；初始化幂等查重；失败撤销本轮周期项并让 core create 返回 `DP_RESOURCE_LIMIT`、保持输出为空；全局模块初始化标记保留，后续 create 只重试定时器补齐。用已有 Windows MinGW 宿主构建中的 `frame_allocation_tests.exe` 直接运行三个单点插入失败场景和持续失败两次后解除故障的场景，四项均以退出码 0 / `PASS` 结束。此前完整 Windows、Linux sanitizer CTest 结果与原生矩阵见上方记录；本次定向执行没有重建宿主二进制。

同轮补记 P0.1 静态调用链结论：[通道清单](channel-inventory.md#2026-10-23-mdns--ipv4-有线调用链复核)指出 wired `runStack` 用 scoped IPv6 和实际 AirPlay 端口构造 `CarPlayStartSession`，当前 `CarPlayBonjour` 只在 wireless 分支启动；`IapTunnel` 仍保留 IPv4/IPv6 双监听。用户确认目前没有参考设备或报告，因此 IPv4/mDNS 脱敏抓包门槛保持未完成，静态审计没有被提升为协议或设备结论。

### P7 安装/隐私说明与兼容性问题模板（2026-10-23）

更新英文安装、兼容性、测试、隐私说明及繁体中文安装、设置和隐私说明，注明 USERSPACE 是显式实验选项、Auto 仍使用既有 VPN 路径、用户态不建立 VPN/TUN、不改系统默认路由且不承载其他应用流量；也说明真机兼容性尚未建立。新增公开兼容性问题表单，收集版本/APK 摘要、车机/iPhone 环境、请求与实际后端、VPN 状态、失败阶段和复现步骤；诊断报告为可选上传，并明确禁止上传原始抓包、媒体、凭据和配对资料。第三方声明更正为当前可由用户显式选择的 lwIP 后端，并保留上游版本、提交、许可证和 APK NOTICE 来源。

诊断字段与 `BackendDiagnosticStore.report()` 的固定枚举、版本/ABI 标记及聚合计数核对；报告不输出地址、异常原文或封包内容，分享仍由用户主动执行。未加入自动上传或远程遥测依赖。根目录 `AGENTS.md` 的完整 Gradle CI 命令通过（BUILD SUCCESSFUL；250 项任务中 12 项执行、238 项 `UP-TO-DATE`）；`git diff --check` 及两个 GitHub issue 表单 YAML 解析通过。以上是源码、文档与宿主构建证据，不是物理设备兼容性验证；真实 iPhone/NCM、VPN 状态及固件门槛仍未覆盖。

同一时点三项 seed-7 sanitizer campaign 继续运行，源码 SHA `37b734d420b4db4a8af452ca8eb062de4465e9b94deca3b303e259fce1de1d13` 与三个目标二进制摘要均匹配各自报告及当前文件：commands 5,805.6/28,800 CPU 秒（566 批）、lifecycle 5,796.4/28,800（552 批）、ethernet 5,839.9/28,800（611 批）；全部 0 个失败批次、0 个崩溃产物。24 CPU 小时预算尚未完成。

### P6/P7 当前工作树 Release APK 复核（2026-10-23）

首次直接执行 `:mobile:assembleRelease` 因缺少项目发布 keystore 在签名校验阶段失败，四 ABI native 编译已完成。随后使用本机 Android debug keystore（仅供本地预检）重跑，`BUILD SUCCESSFUL`，129 个任务中 10 项执行、119 项 `UP-TO-DATE`。`python scripts/check_lwip_native.py --apk mobile/build/outputs/apk/release/mobile-release.apk` 通过；APK SHA-256 `920c5fcb78cf7a940079327ce337701a410b558cbc4fa268b0b24b1af239a5fb`，大小 19,442,826 bytes。arm64-v8a、armeabi-v7a、x86、x86_64 均未压缩，ELF LOAD 对齐和 ZIP 数据偏移为 16 KiB；data+BSS 为 1,473,720–1,491,128 bytes。此检查只证明当前打包产物与布局，不证明 Android 真机加载、USB 互通或性能。当前 Git 树仍有 116 个修改/未跟踪路径且没有候选提交；使用本地 debug signer，兼容设备清单也尚无实测条目，因此 P7 候选发布整理项仍未完成。首次无 keystore 的失败不是源码构建失败。此 Release 预检时查询 API 28 AVD，PATH 中 ADB 32 与 Android SDK ADB 41 互相重启 server，模拟器当时显示 offline；后续使用 SDK ADB 41 已恢复模拟器，并完成下方 Debug instrumentation，本轮没有 Release instrumentation 结果。

### P7 显式实验选项与本机回退范围（2026-10-23）

源码复核确认 `userspaceValidatedForRelease` 在 controller 中保持 `false`，全局发布验证通过前 AUTO 不会仅凭发布标记启用 USERSPACE；只有显式 USERSPACE 后端的会话达到 `SESSION_ACTIVE` 才保存本机成功缓存。该缓存按应用安装持久化，并受 lwIP 版本及 ABI 限制；AUTO 仍先试 VPN，仅当 VPN 明确不可用、缓存匹配且本次没有先前尝试时才切换一次。用户拒绝、取消、USB/MFi/协议/native 初始化错误及显式偏好均不触发跨后端回退。

验证 `:shared:testDebugUnitTest --tests com.shilapi.xcertplay.network.backend.WiredBackendSelectorTest :common:testDebugUnitTest --tests com.shilapi.xcertplay.WiredBackendPreferencePersistenceTest` 成功（BUILD SUCCESSFUL，53 个任务中 2 项执行、51 项 `UP-TO-DATE`）。随后重跑根目录 `AGENTS.md` 的完整九项 Gradle 命令，成功（BUILD SUCCESSFUL in 8m 4s，250 项任务中 14 项执行、236 项 `UP-TO-DATE`）；计划相关文件的 `git diff --check` 通过。显式实验/逐安装缓存门槛因此标记完成；全局发布资格、发布签名和兼容设备清单仍未完成。

完整 CI 结束后的 fuzz 快照：commands 6,597.3/28,800 CPU 秒（640 批）、lifecycle 6,644.0/28,800（631 批）、ethernet 6,592.8/28,800（689 批）；均为 0 失败批次、0 崩溃产物，三项 driver 仍运行且源码/构建二进制摘要与报告匹配。每目标 8 CPU 小时的 fuzz 门槛仍未达到。

### P3/P4 API 28 x86_64 模拟器 native 与 Activity 重建复跑（2026-10-23）

SDK ADB 41 恢复一致运行后，`ANDROID_SERIAL=emulator-5554 .\gradlew.bat :shared:connectedDebugAndroidTest :mobile:connectedDebugAndroidTest` 成功（BUILD SUCCESSFUL in 1m 13s，149 个任务中 11 项执行、138 项 `UP-TO-DATE`）。API 28 `diplay-lwip-api28` x86_64 AVD 的 shared native/JNI instrumentation 为 43/43，mobile Activity 重建为 1/1；对应 JUnit XML 均为 0 failures、0 errors、0 skipped。ddmlib 的 emulator-console `getAvdPath` 命令警告未影响结果。测试未连接 USB accessory 或 iPhone，不代表真实 NCM/CarPlay 会话、Release variant 运行或真机兼容验收。

同一 API 28 AVD 上安装当前本地 debug-signed Release APK 后，用 `monkey -p com.shihab.diplay 1` 启动主 Activity，ADB 返回应用 PID `29892`，`dumpsys activity` 确认 `com.shilapi.xcertplay.DiPlayActivity` 为 resumed activity。该项只证明 Release APK 可安装并启动；没有 USB/NCM、CarPlay 会话或显式 USERSPACE 选择，因此不证明 release native 栈已加载或链路可用。

### P2 选项 A 当前源码双平台宿主回归（2026-10-23）

用全新隔离构建目录重新编译当前源码，Windows MinGW 完整 CTest 93/93 通过；WSL Linux ASan/UBSan/LSan 与 lwIP pool guards 完整 CTest 93/93 通过。两组都包含 `core_timer_allocation_failure_1..3`、`core_timer_persistent_allocation_failure`、运行期周期定时器瞬时/持续失败恢复及其他原生回归。日志分别保存在 `build/lwip-host-windows-timer-a-verify/Testing/Temporary/LastTest.log` 和 `build/lwip-host-linux-timer-a-verify/Testing/Temporary/LastTest.log`。这验证选项 A 当前实现的宿主构建与故障场景，不构成 Android 真机、NCM 或端到端 CarPlay 证据。

本轮从该 Windows MinGW 构建目录新鲜复跑完整 CTest，93/93 通过（2.64 秒）；N08 相关的 4 个用例定向复跑也为 4/4。根目录 `AGENTS.md` 完整 Gradle 命令成功（250 项任务中 12 项执行、238 项 `UP-TO-DATE`，3 秒）；`git diff --check` 无错误。结果仅验证宿主与当前 Android 构建检查，不替代设备门槛。

Windows 首次按默认 PATH 运行脚本时，解析到 `C:\Program Files\Python37\Scripts\cmake.exe`，该入口 `--version` 即以退出码 1 结束，构建尚未开始。排查确认同机 Python36 下 CMake/CTest 3.15.3 和 MinGW GCC 8.1 可正常工作；显式传入 `-Cmake 'C:\Users\lifetruth\AppData\Local\Programs\Python\Python36\Scripts\cmake.exe'` 后完整测试通过。仓库脚本无需修改。

### P6 native runner 重复启停资源回收（2026-10-23）

新增 `repeatedNativeRunnerSessionsReleaseWorkersQueuesAndCore` Android instrumentation 回归，在 API 28 x86_64 AVD 上重复启动并关闭 100 个实际 native lwIP core / `LwipPrototype` runner。每轮验证 native TCP/UDP 端口绑定、runner 停止状态、worker 回到基线、frame port 关闭一次，并检查 RX/TX 队列、命令队列、协程 scope 及 DirectByteBuffer 池的活动资源计数全部归零。测试使用确定性 fake EthernetFramePort，因此覆盖 JNI/core 与 runner 的反复资源生命周期，不覆盖真实 USB accessory 拔插、NCM 设备或 iPhone 会话。

`ANDROID_SERIAL=emulator-5554 .\\gradlew.bat :shared:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.shilapi.xcertplay.network.backend.lwip.NativePrototypeInstrumentationTest#repeatedNativeRunnerSessionsReleaseWorkersQueuesAndCore'` 定向测试通过，JUnit 为 1/1、0 failures、0 errors、0 skipped，测试耗时 0.651 秒。随后完整 `:shared:connectedDebugAndroidTest :mobile:connectedDebugAndroidTest` 通过：shared 44/44、mobile Activity 重建 1/1，均无失败/错误/跳过，BUILD SUCCESSFUL in 1m 6s。根目录 `AGENTS.md` 完整 Gradle CI 命令也通过（BUILD SUCCESSFUL in 1m 28s；250 项任务中 14 项执行、236 项 `UP-TO-DATE`）。完整仪器结果分别为 `shared/build/outputs/androidTest-results/connected/debug/TEST-diplay-lwip-api28(AVD) - 9-_shared-.xml` 和 `mobile/build/outputs/androidTest-results/connected/debug/TEST-diplay-lwip-api28(AVD) - 9-_mobile-.xml`。真机 USB 断连恢复仍需要实际设备证据。

### 当前 seed-7 sanitizer campaign 快照（2026-10-23）

当前三项独立 seed-7 sanitizer fuzz campaign 仍在运行，最新报告快照均无失败批次或 crash artifact：commands 9,538.5/28,800 CPU 秒（917 批），lifecycle 9,547.3/28,800（903 批），ethernet 9,538.0/28,800（977 批）。三项报告源码 SHA `37b734d4…` 和各自二进制 SHA 均与当前输入源/实际目标文件匹配；每目标 8 CPU 小时预算尚未完成，设备、USB 与媒体验收也仍未覆盖。

### P6 重复 SessionNetwork 端点生命周期（2026-10-23）

新增 `LwipSessionLifecycleInstrumentationTest.repeatedNativeSessionEndpointLifecyclesReleaseScopesAndWorkers`，每轮创建实际 JNI lwIP session，绑定一个 TCP listener 和 UDP endpoint，发送一条 UDP 数据以走过 DirectByteBuffer 命令路径，再关闭两个端点及 session；共重复 100 轮。逐轮检查实际分配端口、两个 native resource scope 释放、DirectByteBuffer 租约归零、frame port 仅关闭一次及 lwIP worker 数恢复到基线。Android API 28 x86_64 模拟器定向测试通过（1/1，0.648 秒）；最终完整 shared/mobile 仪器套件通过（45/45 与 1/1，均无失败、错误或跳过），随后根目录 `AGENTS.md` Gradle CI 也通过（BUILD SUCCESSFUL in 45s；250 项任务中 14 项执行、236 项 `UP-TO-DATE`）。JUnit 文件为 `shared/build/outputs/androidTest-results/connected/debug/TEST-diplay-lwip-api28(AVD) - 9-_shared-.xml`。此项使用 fake EthernetFramePort，验证 JNI endpoint/session 反复分配与清理，不覆盖真实 USB 拔插、NCM/iPhone 会话或设备长稳矩阵。

一次完整仪器运行中，既有 `SessionNetworkDeviceContractTest.datagramPayloadSourceTruncationAndBoundaries[lwip-jni]` 的 UDP receive 在 3 秒 deadline 超时；没有改动该路径。随后单独运行其参数化合同类 12/12 通过，再次完整 shared/mobile 仪器运行 45/45 与 1/1 通过（BUILD SUCCESSFUL in 1m 6s），最新完整 Gradle CI 也通过（BUILD SUCCESSFUL in 45s）。这是一次未复现的超时，现有证据不足以确定根因；不将它归因于代码缺陷或环境负载，也不通过放宽 deadline 掩盖。对应最新成功 JUnit 与 CI 是最终本地验证依据。

### N08 已建立连接容量覆盖澄清（2026-10-23）

复核宿主 `tcp_endpoint_limits` 后，更新测试矩阵以反映已有覆盖：该用例通过真实 TCP 握手将 32 个已建立连接填满配置的 PCB 上限，验证额外 SYN 不会成为可接受连接、所有已有连接均能关闭，并在 teardown 后确认资源归零及监听端口可重绑。它覆盖了受限连接容量下的既有会话保留与关闭；`tcp_half_open_capacity` 和 `tcp_half_open_pool_exhaustion` 另行验证半开容量。此证据不等于填满整个临时端口号区间，配置的 32 个活动 TCP PCB / 16 个 listener 上限会先于该区间耗尽触发；完整区间耗尽、实际网卡输入洪泛和设备日志限频仍未覆盖。此前 Linux sanitizer 与 MinGW CTest 93/93 包含这些连接容量用例；本轮 Windows MinGW 对 `tcp_endpoint_limits`、半开容量/池耗尽和未加入组播洪泛定向复跑，4/4 通过（0.26 秒）。本次仅修正文档覆盖说明，没有修改 native 源码。

### N08 NCM 日志洪泛边界补齐（2026-10-23）

审阅 IPv6 NCM 转发日志后发现，入站/出站包摘要已有每 bridge 16/24 条预算，但邻居通告补齐 target-link-layer option 的提示会随每个匹配报文重复输出。新增 `Ipv6NcmBridgeLogGate`，使用原子一次门控限制为每 bridge 一条；`Ipv6NcmBridgeLogGateTest.neighborAdvertisementRewriteLogIsAllowedOncePerBridge` 对同一 gate 连续请求 10,001 次，验证只有第一次获准。先运行测试确认缺少门控时编译失败，接入门控后定向测试通过。

根目录 `AGENTS.md` 指定的完整 Gradle CI 第二次运行通过：BUILD SUCCESSFUL，250 个任务中 13 项执行、237 项 `UP-TO-DATE`。第一次运行只有一个无关的 `CarPlayControllerUsbServiceTest` teardown 在 2 秒 deadline 内未完成；单项复跑通过，第二次完整 CI 也通过，未更改或放宽该测试。设备日志实测、物理网卡洪泛和真机 NCM 仍无可用设备/基线证据，因此本次完成源码日志上限及其单元回归，不把硬件验收标记为通过。

完整 CI 结束后、三项 driver 恢复 nice +10 的 seed-7 sanitizer campaign 快照：commands 10,847.3/28,800 CPU 秒（1,038 批），lifecycle 10,856.5/28,800（1,024 批），ethernet 10,837.9/28,800（1,110 批）。全部仍在运行，0 个失败批次、0 个崩溃产物；报告源码 SHA `37b734d420b4db4a8af452ca8eb062de4465e9b94deca3b303e259fce1de1d13` 匹配当前 native 输入源码，各目标二进制摘要也与报告一致。每个目标 28,800 CPU 秒的门槛尚未完成。

### P3 graceful TCP close 错误清理回归（2026-10-23）

新增宿主链接器 wrapper，只在 `frame_allocation_tests` 中令一次 `tcp_close()` 返回 `ERR_MEM`，覆盖 `dp_tcp_close_graceful` 的错误分支。测试确认接口返回 `DP_INTERNAL`、仍由应用持有的 PCB 被 abort、stream handle 立即失效、监听器保持可用，并且 PCB/heap 回到仅保留监听器的基线；关闭监听器后 heap 与 TCP PCB pool 全部归零。该用例模拟 raw API 错误返回，不等同于真实分配器故障；lwIP 在 FIN 段池压力下返回成功并延迟 FIN 的路径仍由既有压力测试覆盖。

Windows MinGW 完整重建后的 CTest 94/94 通过；WSL Linux ASan/UBSan/LSan 与 lwIP pool guards 的完整 CTest 94/94 通过（55.07 秒）。新增覆盖记入 [原生分配失败定向矩阵](native-allocation-failures.md)，不代表完整 R01/G3。当前三项 seed-7 campaign 快照：commands 11,590.5/28,800 CPU 秒（1,108 批），lifecycle 11,592.1（1,093 批），ethernet 11,569.7（1,181 批）；均无失败批次或崩溃产物。宿主测试期间只改动 `CMakeLists.txt` 和 `tests/frame_tests.c`，未改 production core/backend/fuzzer 源码。为诚实保留 campaign 报告原始 aggregate source SHA `37b734d4…`，它现在不同于目录重算 SHA `6926a1d2…`；各实际 fuzz 二进制 SHA 仍与原报告匹配。该差异仅反映未链接进 fuzz target 的宿主测试 harness 更新，不能记作新 aggregate source SHA 的整段 fuzz 验收。

根目录 `AGENTS.md` 完整 Gradle CI 九项命令随后成功：BUILD SUCCESSFUL，250 个任务中 12 项执行、238 项 `UP-TO-DATE`。`git diff --check` 与 lwIP vendor 摘要校验通过。

### P3 DirectByteBuffer 真实 direct-memory OOM 子进程回归（2026-10-23）

`DirectBufferPoolTest` 新增隔离 JVM 子进程，设 `-XX:MaxDirectMemorySize=8k` 后请求 256 KiB direct buffer，实际触发 `ByteBuffer.allocateDirect` 的 `OutOfMemoryError`。子进程确认异常映射为 `RESOURCE_LIMIT`、cause 保留为 `OutOfMemoryError`，且池未登记分配或租约；OOM 限制只作用于子进程，不耗尽 Gradle 测试 JVM。原受控 allocator 用例仍验证移除瞬时故障后可重试。该结果证明 HotSpot direct-memory 限额路径，不证明 Android ART 或 JVM heap OOM 行为。

新用例定向测试及完整 `DirectBufferPoolTest` 均通过。随后按根目录 `AGENTS.md` 执行完整 Gradle CI，`BUILD SUCCESSFUL in 2m 2s`，250 项任务中 15 项执行、235 项 `UP-TO-DATE`。`python scripts/check_lwip_native.py` 和本轮相关文件的 `git diff --check` 通过；前者确认 vendor/backport 摘要，后者只有既有 CRLF 转换提示。

CI 结束时三项 seed-7 sanitizer campaign 仍运行且无失败或崩溃产物：commands 12,240.7/28,800 CPU 秒（1,169 批），lifecycle 12,255.3/28,800（1,155 批），ethernet 12,171.2/28,800（1,240 批）。报告所用 native 输入 SHA 为 `37b734d4…`，当前 native 目录 aggregate SHA 为 `6926a1d2…`；差异仍只来自此前的 CMake/宿主测试 harness 变更，campaign binary 摘要各自与报告一致。本次新改的是 Kotlin 测试和文档，没有把本轮时长算作新的 aggregate SHA campaign 验收。完整 R01/G3 与设备门槛仍未完成。

### P3 UDP Ethernet 接收入口分配失败恢复（2026-10-23）

新增 `udp_receive_input_allocation_failure`，对合法 IPv6 UDP Ethernet frame 的 `dp_core_input()` 首次 RX pbuf 分配连续注入两次失败。每次都要求返回 `DP_RESOURCE_LIMIT`，随后直接检查 `dp_udp_receive()` 返回 `DP_WOULD_BLOCK`、没有数据报交付，并断言 heap、pbuf、全部 MEMP pool 保持调用前基线；解除注入后重放同一 frame，核对源地址、源端口及完整 payload，随后验证资源回基线。此用例补足现有 UDP bind、发送、TCP 输入与 receive 矩阵之间的入口覆盖，不代表完整 R01。

Windows MinGW 完整 CTest 95/95 通过；WSL Linux ASan/UBSan/LSan 与 lwIP pool guards 完整 CTest 95/95 通过（54.18 秒）。根目录 `AGENTS.md` 的完整九项 Gradle CI 命令通过（BUILD SUCCESSFUL；250 项任务中 12 项执行、238 项 `UP-TO-DATE`）；`python3 -m unittest discover -s scripts/tests -v` 31/31 通过，`python scripts/check_lwip_native.py` 与本轮相关文件的 `git diff --check` 通过。无 Android 设备或脱敏抓包/VPN 无线基线可用于本轮物理验收。

新增的 harness 及 CTest 注册分别改动 `tests/frame_tests.c` 和 `CMakeLists.txt`，没有改 production core/backend/fuzzer 输入。campaign watcher 确认 aggregate source SHA 因这两个宿主测试文件从 `6926a1d2…` 先变为 `4b243986…`，随后加入 UDP RX 无数据断言后变为 `2acd9e35…`；正在运行的 campaign 报告仍针对 `37b734d4…` 源快照，实际 fuzz binary 摘要匹配原报告。本轮不把这些 campaign 时长记作新 aggregate SHA 的验收；R01/G3 与全真机门槛仍未完成。

### P3 HotSpot heap OOM 子进程回归（2026-10-23）

`DirectBufferPoolTest.realHeapExhaustionDuringAllocationMapsToResourceLimitInIsolatedJvm` 使用 `-Xmx32m` 子进程，在注入的 direct allocator 回调内实际申请 64 MiB Java 数组，触发 HotSpot `OutOfMemoryError`；direct-memory 上限设为 1 MiB，因此失败来自 JVM heap。父测试进程不承受该堆压力。验证池将 allocator 抛出的 OOM 映射为 `RESOURCE_LIMIT`、保留 cause，且分配/租约计数为零。此项证明 HotSpot heap OOM 在该分配边界的映射，不覆盖 Android ART，也不声称任意 JVM heap OOM 都能恢复，更不表示完整 R01。

`:shared:testDebugUnitTest --tests com.shilapi.xcertplay.network.backend.lwip.DirectBufferPoolTest` 定向测试通过（包含真实 direct-memory 与 heap OOM 两个隔离子进程场景）。随后按根目录 `AGENTS.md` 重跑完整九项 Gradle CI，`BUILD SUCCESSFUL in 1m 29s`，250 项任务中 15 项执行、235 项 `UP-TO-DATE`。

### P3 Android ART heap OOM 子进程 instrumentation（2026-10-23）

新增 AndroidTest service 并置于独立 `:direct_buffer_oom` 进程；服务内尝试分配 2 GiB heap 数组，捕获 API 28 ART 实际抛出的 `OutOfMemoryError`。它经过真实 `DirectBufferPool.acquire()` allocator 边界后映射为 `RESOURCE_LIMIT`，cause 保留为 `OutOfMemoryError`，池的 allocated/leased bytes 与 leased count 均为 0。测试校验 service PID 与 instrumentation PID 不同，避免 OOM 影响测试 runner。

API 28 x86_64 AVD、`debug.checkjni=1` 下定向 instrumentation 通过：1/1，0 failures、0 errors、0 skipped，JUnit 用时 0.563 秒。随后 `:shared:connectedDebugAndroidTest` 全套 46/46 通过，均无失败/错误/跳过；单测结果位于 `shared/build/outputs/androidTest-results/connected/debug/TEST-diplay-lwip-api28(AVD) - 9-_shared-.xml`。仓库 `AGENTS.md` 完整 Gradle CI 命令成功：250 个任务中 14 项执行、236 项 `UP-TO-DATE`；`scripts/check_lwip_native.py` 与本轮 `git diff --check` 通过。这是模拟器上的 ART 与分配器边界证据；不代表实体车机/其他 API ART 行为、任意 OOM 恢复或完整 R01。真实 NCM/iPhone 与长稳门槛不变。

### P2/P3 宿主与 Android 回归最新快照（2026-10-23）

选项 A 的冷启动周期定时器检查在当前源码快照下再次通过：Windows MinGW CTest 95/95（`scripts/test_lwip_host.ps1 -Cmake 'C:\Users\lifetruth\AppData\Local\Programs\Python\Python36\Lib\site-packages\cmake\data\bin\cmake.exe'`），WSL Ubuntu 20.04 ASan/UBSan/LSan 与 lwIP pool guards CTest 95/95（`scripts/test_lwip_host.sh --sanitize`）。后者总时长 54.61 秒；冷初始化三处逐点失败、持续失败不发布 core、解除后重试等定时器用例均通过。Windows 默认 PATH 的 Python37 CMake 启动器仍以退出码 1 结束，因此显式指定 Python36 包内的 CMake 3.15.3 可执行文件；宿主测试脚本和 PATH 未改动。

API 28 x86_64 AVD、`debug.checkjni=1` 下，新增独立 service 进程 ART heap OOM 测试通过 1/1，`:shared:connectedDebugAndroidTest` 全套 46/46 无失败/错误/跳过；根目录 `AGENTS.md` 的完整 Gradle CI 九项命令再次 BUILD SUCCESSFUL。以上证明当前宿主/模拟器测试快照，不替代实体设备、真实 NCM/iPhone、其他 API ART 或完整 R01/G3。

### P6 seed-7 sanitizer campaign 最新快照（2026-10-23）

三个 8 CPU 小时 campaign 仍在运行：commands 14,209.4/28,800 CPU 秒（1,354 批），lifecycle 14,204.9/28,800（1,338 批），ethernet 14,097.8/28,800（1,428 批）；目前均为 0 失败批次、0 崩溃产物。campaign 报告与 fuzz 二进制仍对应源码 SHA `37b734d420b4db4a8af452ca8eb062de4465e9b94deca3b303e259fce1de1d13`；当前聚合源码 SHA `2acd9e35a68fa5d2d3ec67ca45d1f579515a5d9cb5e15b4f50b209b103823968` 的差异来自 campaign 启动后新增的 CMake/宿主测试 harness。当前二进制摘要匹配旧报告，但这段运行时间不计作新聚合源码快照的完整 fuzz 验收；三个 28,800 CPU 秒预算仍未完成。

### P6 seed-7 campaign fuzz 目标二进制等价性复核（2026-10-23）

针对前述 aggregate source SHA 不同，复核了 campaign 启动时间（UTC 06:05）之后的本地文件变更：lwIP backend 中仅 `CMakeLists.txt`（本地 18:05）和 `tests/frame_tests.c`（18:11）晚于启动时间。CMake 差异只增加/调整宿主测试和分配故障注入 target，`DIPLAY_FUZZ` 的 fuzz target 定义及链接关系未改；`frame_tests.c` 不参与 fuzz target。其余 backend 文件早于 campaign 启动。逐目标重算当前构建文件 SHA-256 与活动报告记录完全相同：commands `aaeedef34e3ae706c7e13d01f0637ed4d65d8f0f33cabaef21fba3509725fbd1`，lifecycle `d4831a21816e9fb688e444379bc28ad3b17a49e503c5a50290b2b9c5aa15bdac`，ethernet `7ffd52ee2419581830099509431022d067b984a787d6508f2b0d59e9eabb3e70`。因此这些运行时长可计入对应**完全相同的 fuzz 二进制**的预算；报告中的 aggregate source SHA 仍只代表启动时目录快照，不改写为当前目录摘要。

复核快照：commands 14,316.11/28,800 CPU 秒、lifecycle 14,322.08/28,800、ethernet 14,293.94/28,800；三项均仍运行，0 失败批次、0 crash artifact。每项 8 CPU 小时门槛仍未达到，campaign 结束后仍需检查最终报告、失败 corpus 重放和退出码。

### P6 工具脚本与方案首页复核（2026-10-23）

在 Windows 和 WSL1（内核 `4.4.0-26100-Microsoft`）分别运行 `python -m unittest discover -s scripts/tests -v`：Windows 31 项中 21 项通过、10 项因需要 Linux/POSIX 能力而按预期跳过；WSL1 上 31/31 全部通过。`python scripts/check_lwip_native.py` 通过，核对 314 个固定 lwIP 文件、上游 2.2.1、backport 与本地修补摘要。`git diff --check` 对本次文档变更通过。

将方案首页的状态修正为：有线通道代码集成与自动化证据已完成，真实 NCM/iPhone 会话、设备性能基线、长稳与最终发布资格仍未完成；USERSPACE 继续是显式实验选项。该文案与 P4/G4 实现状态及设备验收边界保持一致。脚本宿主单测和文档一致性复核不构成真实设备、Linux 隔离网络性能或 24 CPU 小时 fuzz 验收。

### P3 API 29 Android ART heap OOM instrumentation（2026-10-23）

在 API 29 Google APIs x86_64 AVD、`debug.checkjni=1` 下重跑独立 `:direct_buffer_oom` service 进程测试，定向 JUnit 1/1、0 failures、0 errors、0 skipped，用时 0.405 秒。服务 PID 与 instrumentation PID 不同，ART heap `OutOfMemoryError` 映射为 `RESOURCE_LIMIT`，分配/租约计数为零。

首轮完整 shared instrumentation 46 项中有 45 项通过，唯一失败是 `publicTcpStreamTraversesRunnerHandshakeReadWriteAndGracefulClose` 在等待 SYN 帧时触发 2 秒 `TCP frame timeout`。单独重跑该用例通过 1/1；随后完整套件重跑 46/46、0 failures/errors/skips，39.17 秒。最终 JUnit XML 为 `shared/build/outputs/androidTest-results/connected/debug/TEST-diplay-lwip-api29(AVD) - 10-_shared-.xml`。没有修改生产代码或放宽 deadline；这次未复现的超时原因未确定，保留为一次性观察，不据此归因于代码或环境。API 28 与 29 的模拟器证据不代表实体车机、其他 API 版本或任意 OOM 条件的恢复能力。

### P6 seed-7 sanitizer fuzz 24 CPU 小时门槛完成（2026-10-23）

三项目标分别累计至少 28,800 子进程 CPU 秒并自然退出，均 `returncode=0`、
`cpu_budget_complete=true`；所有 batch 返回码为 0，crash artifact 数为 0。逐份读取
最终报告并扫描全部 batch 日志，没有 ASan、UBSan 或 LSan 错误标记。结果如下：

| Target | 实测 CPU 秒 / 门槛 | batches | 二进制 SHA-256 | 报告 SHA-256 |
| --- | ---: | ---: | --- | --- |
| commands | 28,806.03125 / 28,800 | 2,724 | `aaeedef34e3ae706c7e13d01f0637ed4d65d8f0f33cabaef21fba3509725fbd1` | `ddcea86d444da7701c4ede6e68802b6d31e07759a6e9cab0d736717229f40556` |
| lifecycle | 28,809.265625 / 28,800 | 2,710 | `d4831a21816e9fb688e444379bc28ad3b17a49e503c5a50290b2b9c5aa15bdac` | `25829cce6ef906584d7832276d54185320245c5cc43adb5c88c91c95aa2f822e` |
| ethernet | 28,802.75 / 28,800 | 2,848 | `7ffd52ee2419581830099509431022d067b984a787d6508f2b0d59e9eabb3e70` | 9db58dfaa347b7cfac06ca1f07f2e601df67905c9ae22e0e0cefcaf9fba48c13 |

报告和 batch 日志位于忽略的 `build/lwip-fuzz-final/{commands,lifecycle,ethernet}/`。
复核时三份报告中的 binary SHA 与当前 fuzz executable 完全一致。报告记录的 native 输入
aggregate SHA 为 `37b734d420b4db4a8af452ca8eb062de4465e9b94deca3b303e259fce1de1d13`；
当前目录 aggregate SHA 是 `2acd9e35a68fa5d2d3ec67ca45d1f579515a5d9cb5e15b4f50b209b103823968`。
二者差异来自 campaign 启动后变更的宿主测试 harness/CTest 注册，未链接进这三个 fuzz
目标；此结论仅对这三个已核对的同一二进制输入成立，不将 fuzz 时长外推到新 harness。

campaign 结束后的再次验证：Windows MinGW CTest 95/95、WSL1 ASan/UBSan/LSan 与 lwIP pool guards CTest 95/95；Windows Python 脚本套件 31 项运行（10 项按平台跳过），WSL1 脚本套件 31/31；因此 P6 中三项目标各 8 CPU 小时、总计 24 CPU 小时的 sanitizer fuzz 子门槛通过。根目录 AGENTS.md 指定的完整 Gradle CI 九项命令随后通过（BUILD SUCCESSFUL；250 项任务中 12 项执行、238 项 UP-TO-DATE）；`python scripts/check_lwip_native.py` 与 `git diff --check` 也通过。
G6 的设备 8 小时会话、USB 插拔/重连、低内存恢复、低配 ARM 性能/内存和 release 设备预算，
以及真实 NCM/iPhone、G0/G1 设备证据和 Linux 隔离网络基准仍未覆盖；USERSPACE 继续保持显式实验选项。

### API 26 实体 ARM64 Android 仪器测试（2026-10-23）

新增可用设备 `Xiaomi Mi 5s Plus`（device `natrium`，Android 8.0.0 / API 26，arm64-v8a，
MIUI `V11.0.2.0.OBGCNXM`，Qualcomm MSM8996pro）。设备通过 ADB TCP 连接。PATH 上的 `D:\adb\adb.exe`
是 ADB 1.0.32；Android SDK 的 Platform Tools ADB 为 1.0.41 / 37.0.1。首轮 Gradle 运行时新旧
client 重启 server 后丢失 TCP transport；改用 SDK ADB 重新连接后运行测试，后续不再发生该问题。

命令：`ANDROID_SERIAL=<connected-device> .\gradlew.bat :shared:connectedDebugAndroidTest :mobile:connectedDebugAndroidTest --max-workers=2`。
`shared` 在真实 API 26 ART/arm64 上 **46/46 通过**，0 failure/error/skipped：包含 native/JNI
原型 16 项、TCP/JNI 14 项、系统与 lwIP endpoint contract 12 项、Android IPv6 scope 1 项、
独立进程 ART heap OOM 1 项、100 轮 JNI session 生命周期 1 项及 AirPlay channel 协商 1 项。
JUnit XML：`shared/build/outputs/androidTest-results/connected/debug/TEST-MI 5s Plus - 8.0.0-_shared-.xml`。
Debug app APK SHA-256：`4b54c6d1e7c5ce2860ff8b8381798bb2df527423456e8b3c146023b05ceee0b1`；
shared instrumentation APK SHA-256：`3a92678e118ab032f1ff219c926c9a8a3fdea7f7561b66e458262b83fc2bf47e`。

同一命令中的 `mobile` Activity recreation 用例 **1 项失败**，因此 Gradle 整体退出码为 1。
设备 system log 明确记录 MIUI `ExtraActivityManagerService: MIUILOG- Permission Denied Activity`
并拒绝测试启动 `CarPlayHostActivity`；没有应用崩溃记录。失败 JUnit XML 为
`mobile/build/outputs/androidTest-results/connected/debug/TEST-MI 5s Plus - 8.0.0-_mobile-.xml`，
MIUI 拒绝记录的脱敏副本位于忽略构建目录 `build/reports/lwip/android-api26-miui-activity-denial.txt`。
该结果是设备 ROM 的 Activity 启动限制下未通过，不能记为 app 重建通过，也没有据此改动或
放宽测试。

lwIP instrumentation 的 `EthernetFramePort` 使用 fake port；这次只证明实体 API 26 ARM64 上
Android/JNI/ART 和端点代码运行，不证明真实 USB/NCM/iPhone 媒体互通、VPN 禁用、无线基线或
低配车机性能。Mi 5s Plus 是手机，不能代替低配 ARM 车机，也不能通过 G2/G6 性能门槛。

### API 26 当前 shared instrumentation 快照（2026-10-24）

在当前工作树上用 SDK Platform Tools ADB 重跑 `:shared:connectedDebugAndroidTest --max-workers=2`，
Mi 5s Plus API 26 arm64 **46/46 通过**，0 failures、0 errors、0 skipped，BUILD SUCCESSFUL in 1m08s。
JUnit XML 根节点计数为 `tests=46 failures=0 errors=0 skipped=0`，文件：
`shared/build/outputs/androidTest-results/connected/debug/TEST-MI 5s Plus - 8.0.0-_shared-.xml`。
这刷新了当前源码下 Android/JNI/ART 与端点自动化证据；所有 lwIP frame test 仍使用 fake
`EthernetFramePort`，不替代真实 NCM/iPhone 或媒体会话。MIUI 对 `mobile` ActivityScenario 启动的拒绝仍单独记录，
本次只运行 shared 仪器任务，没有把该 mobile 用例计作通过。

### P2 Linux veth 宿主吞吐微基准（2026-10-24）

原 Ubuntu-20.04 WSL1 仍无 TUN 且无法创建 netns。未转换或覆盖该发行版；将其导出并以独立名称
`DiPlay-Netns-20261024-002023` 导入临时 WSL2，内核为 `6.6.87.2-microsoft-standard-WSL2`，
`/dev/net/tun` 存在且 `unshare -n true` 成功。隔离副本中用 GCC 9.4.0、CMake 3.16.3 和 Release
（关闭 sanitizer）构建内核 echo peer。以 root 执行：

```sh
bash scripts/benchmark_lwip.sh --output build/lwip-benchmark-netns-20261024-fullrange
```

脚本在独立 network namespace 配置 veth，不改父 network namespace。另运行
`bash scripts/test_lwip_kernel.sh build/lwip-benchmark-netns-20261024-fullrange/build tap echo`，
TAP IPv6 TCP/UDP 基本互通通过：TCP 3×64 KiB、UDP 空包/MTU/分片输入，0 丢包、not-ready 0、
lwIP heap peak 36,008 bytes。

在同一 Linux 隔离副本中运行 `python3 -m unittest discover -s scripts/tests -v`，脚本单测
31/31 通过；`python scripts/check_lwip_native.py` 与 `git diff --check` 也通过。

当前源码快照的 veth 报告状态 `complete`，请求/实际时长为 600/603.33 秒，
`duration_gate_passed=true`，内核命令 exit 0。TCP 16 KiB echo 300.03 秒、58,021 个完整往返，
单向载荷吞吐 3.02 MiB/s，RTT p50/p95/p99/max 为 0.898/43.532/44.726/48.458 ms。UDP
64/1200/4096/8192 字节阶段各 75 秒，分别收到 846,202/807,689/711,718/566,262 个数据报，
全部 0 丢包；单向载荷吞吐为 0.69/12.32/37.07/58.99 MiB/s。core 计数为 RX 7,996,301、
TX 7,882,905、not-ready 0、lwIP heap peak 18,720 bytes。报告另记录客户端 CPU 185.26 秒、
峰值 RSS 56,408 KiB。

结构化报告、peer 报告、构建与 kernel 日志位于忽略构建目录
`build/lwip-benchmark-netns-20261024-fullrange/`。本轮 native 输入 SHA-256 为
`153f58680f12c2e6e5eb9ba1c8bdb62da1c6d702903b8e8e53d2717f1603d660`，benchmark binary SHA-256 为
`808a941755bc6c7e5dfba7aee763127bff2d4f513d4d0a8225a97d4da63879b5`；工作树仍有改动，HEAD
`5d2538bb` 只是基点。该结果完成 Linux 宿主吞吐预检，但报告将 `device_performance_acceptance`
及 `media_headroom_gate_evaluable` 保持为 false；不能推导 Android/NCM/iPhone、车机 CPU/PSS、
媒体余量或低配 ARM 性能通过。

### P6 N08 临时 TCP 端口耗尽与恢复（2026-10-24）

新增独立 host target `tcp_full_ephemeral_port_exhaustion`，使用默认动态端口范围
49152–65534（16,383 个端口），只将该测试栈的 `MEMP_NUM_TCP_PCB` 扩至 16,384。测试逐一
绑定并检查全部端口唯一，额外绑定确认返回 `ERR_BUF`；关闭一个已绑定 PCB 后，同一探测 PCB
重新绑定并复用释放端口。Android 生产栈及其它 host target 仍使用默认 32 个 TCP PCB。

WSL2 Release 新用例 **1/1 通过**，耗时 4.19 秒；完整 Release CTest 套件 **96/96 通过**，
耗时 5.32 秒。原 Ubuntu-20.04 WSL1 中 ASan/UBSan/LSan 完整 CTest 套件 **96/96 通过**，
含全区间新用例，耗时 93.46 秒。WSL2 sanitizer 运行时对不同
测试二进制均出现随机进程启动 SIGSEGV（12 次独立重复中各有复现），相同二进制在 WSL1
重复运行稳定；因此 WSL2 sanitizer 异常按运行时环境问题记录，不作为代码通过证据。测试证明
默认端口分配器完整耗尽和恢复逻辑；应用端 32 PCB 池会更早触发资源上限。实际网卡洪泛与设备
日志采样仍需真机，P6 全部故障场景及设备验收因此仍未关闭。

### API 26 shared instrumentation 尝试（2026-10-24）

在 Android native 四 ABI 构建和完整 Gradle CI 通过后，使用 SDK Platform Tools 37.0.1、
`ANDROID_SERIAL=192.168.0.103:5555` 重跑 `:shared:connectedDebugAndroidTest --max-workers=2`。
UTP 在安装 `shared-debug-androidTest.apk` 时收到 `INSTALL_FAILED_USER_RESTRICTED`，0 项测试启动；
设备 logcat 明确记录 MIUI `MIUILOG- INSTALL_FAILED_USER_RESTRICTED` 和
`Install canceled by user`。`dumpsys device_policy` 未显示 device-policy 安装限制；本轮没有改动
设备安装安全设置。该运行不能计为 instrumentation 通过，也没有应用测试崩溃证据；先前 API 26
shared 46/46 结果仍单独保留，不外推为本轮执行。

### P6 R04 RTP UDP 队列年龄预算接入（2026-10-24）

`CarPlayMediaEngine`按协商的`framesPerPacket`和音频采样率计算4个RTP包时长并传给`AudioStream`；
Opus和缺失/无效包时长采用20ms，因此默认预算80ms。仅RTP数据端点在实现`UdpQueueAgeControl`时
收到非零预算；RTCP保持默认0，普通系统UDP后端没有该可选能力时保持原行为。若端点配置预算失败，
关闭该数据端点并中止音频监听设置，避免以未限龄状态继续运行。

新增`AudioQueueAgeBudgetTest`覆盖1024帧/44.1kHz取整到93ms、960帧/48kHz与Opus按80ms，及无效/缺省
参数回退；fake `SessionNetwork`断言RTP数据端点收到93ms、RTCP未配置。该测试2/2通过，
`CarPlayAudioStreamIsolationTest` 2/2通过；shared JVM报告共1825项、0失败/错误/跳过，common
JVM报告1588项、0失败/错误/跳过。

按仓库`AGENTS.md`运行完整Android CI：
`:shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug`
`:home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug`，
`BUILD SUCCESSFUL in 9m 25s`，250项任务（44 executed，206 up-to-date）。
`python scripts/check_lwip_native.py` exit 0，`git diff --check` exit 0（Git提示现有文件的
LF/CRLF自动转换，不是空白错误）。

自动化验证覆盖了预算计算、端口隔离及原生/JNI队列过期语义；真实媒体消费者暂停/恢复、
陈旧音频恢复体验、NCM/iPhone整通道、VPN/无线基线和设备性能仍未覆盖。当前无可用参考车机、
iPhone或测试报告，故R04设备验收和G0/G1/G2/G6设备门槛保持未完成。

### P6 TCP 输出半关闭 FIN 分配失败补充与当前验证快照（2026-10-24）

新增 `tcp_shutdown_fin_allocation_failure` 宿主用例，在 `tcp_shutdown_output()` 入队 FIN 时注入一次
通用分配失败。验证调用保留关闭意图、后续写入被拒绝、`tcp_slowtmr` 恢复后发送 FIN，且端点销毁后
heap 与全部 MEMP pool 回到基线。该覆盖补充 P3/P6 的资源失败矩阵，不表示完整 R01 已完成。

Ubuntu-20.04 WSL1 使用 `bash scripts/test_lwip_host.sh --build-dir build/lwip-host-linux-wsl1 --sanitizers address,undefined`
重新配置并执行完整 ASan/UBSan/LSan 与 lwIP pool guards CTest：97/97 通过，97.05 秒；其中端口耗尽项
48.23 秒。WSL2 Release 完整 CTest 97/97 通过，5.85 秒；新 FIN 用例通过。首次与 Gradle 并行的 WSL1
执行中端口耗尽项达到60秒测试时限；Gradle 结束后的单项复测为47.85秒，随后完整套件通过，因此没有
放宽该测试时限或改变生产代码。

根目录 `AGENTS.md` 完整九项 Gradle CI 再次通过：`BUILD SUCCESSFUL in 9m 21s`，250项任务中54项执行、
196项 `UP-TO-DATE`。当时的 ADB 未授权记录已由下方同日设备烟测更新。自动化宿主结果与这次短时会话
仍不替代参考抓包、VPN 禁用与无线 A/B 基线、电话通道、并发拔插、性能/长稳及完整设备矩阵验收。

### API 26 arm64 真实 iPhone USERSPACE USB CarPlay 烟测（2026-10-24）

在 Xiaomi Mi 5s Plus（Android 8.0/API 26、arm64、MIUI V11.0.2.0）安装测试包
`0.2.21-hud-test`。Advanced 中显式选择 `USERSPACE`，强制停止并重启后设置仍显示为用户态；
通过 Wi-Fi ADB 继续采样，Android USB Host 枚举到 Apple iPhone 的 NCM 控制/数据接口。iPhone 解锁并
信任主机后，DiPlay 获取 USB 设备访问权并启动 CarPlay。设备设置记录
`wired_backend_preference=USERSPACE`、`wired_backend_success_version=lwip-2.2.1-diplay.1`、
`wired_backend_success_abi=arm64-v8a`；这份成功缓存仅在用户态 AirPlay session active 后写入。
会话期间 `CarPlaySessionService` 运行，`CarPlayVpnService` 未运行，系统网络报告只有普通 Wi-Fi
`NOT_VPN` 网络。本次通过 Wi-Fi ADB 操作设置并采样日志，因此不计作“无 ADB”验收。

观察到 AirPlay 控制反馈收到 HTTP 200，CarPlay 视频持续收发并解码；稳定片段约 29–30 fps、恢复数为 0、
解码 late 为 0。导航负载期间观测约 22–28 fps、最高采样约 7.8 Mbps，恢复数仍为 0；这只是手机上的
短时烟测，不是性能门槛或车机基准。Android 屏幕触控得到响应，视频统计记录到触控事件，发送峰值
7–24 ms、触控至帧响应约 14–60 ms。

媒体音频用户确认可听；媒体 RTP 以 48 kHz LPCM 播放，采样中 dropped、underruns、writeErrors 均为 0。
Siri 问时测试建立 24 kHz 单声道 VOICE_RECOGNITION 上行，捕获并发送 251 个编码帧，读取/发送错误为 0；
assistant 回传 PCM 也已播放，丢包、欠载和写错误均为 0。地图语音提示通过 8 kHz 导航音频通道播放，
采样中丢包、欠载和写错误均为 0；iAP NowPlayingUpdate 消息持续到达。未触发测试电话（用户选择跳过）。

该证据证明这台设备上的显式用户态 NCM 核心 CarPlay 视频、控制和已测音频路径可工作，不代表 G0/G1
参考网络抓包或 P4 全通道验收。仍未覆盖：IPv4/mDNS 与 iPhone IPv6 scope 脱敏 pcap、通话与其余附加
通道、其他 VPN 共存及无线 A/B、并发停止/拔线/重插、8 小时会话、100 次重连、低配车机性能和完整故障矩阵。

同日两次真实 USB 断开/重连的阶段耗时、原因分析和脱敏 logcat 事件摘录见
[USERSPACE 重连时延分析](reconnect-latency-2026-10-24.md)。该短时测量不替代 G0/G1/G6 的长稳、循环重连或设备矩阵验收。
