# 实施记录

执行日期：2026-10-21 至 2026-10-22。工作分支：`codex/lwip-backend`。起点：`143f4674`。

## 当前交付

- P0.1：完成源码通道清单，见 channel-inventory.md；抓包与设备验证尚缺。
- P0.2：保存构建环境与设备基线缺口，见 baseline.md；记录 lwIP 2.2.1 官方标签、
  commit 和已下载 ZIP 的 SHA-256，见 ADR 001 / native UPSTREAM.md；尚未引入源码。
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

完整 CI 命令与 AGENTS.md 相同，后续执行增加 `--stacktrace --max-workers=2`，
避免同时执行多组 Gradle 构建造成调度压力。首次 CI 失败没有通过改变既有测试断言掩盖。

## 尚未完成

日志副本保存在本工作树 `build/reports/lwip/`（忽略的构建产物，不作为源码依赖）。

P1.1 仍需补齐 RST、真实 IPv6 scope、初始化/关闭竞态与完整 T01–T08
两后端共用运行器等覆盖；IPv4/IPv6 数据报各自的最大长度边界也需补测。
公共宿主与普通服务已接入生产控制器；P1.2 仍需把 AirPlay/媒体的系统 Socket
边界改为显式注入 SessionNetwork、迁移健康检测并补齐协商与全通道契约。
P2–P7 的 native 栈、NCM 原型、JNI、全通道迁移、选择设置及发布均未交付。

用户确认当前没有设备或报告；`adb devices -l` 也未发现设备。
G0/G1 的真机条件未通过，后续可推进独立接口和宿主实验，不能跳过阶段门槛或
声称实际有线 CarPlay 已能脱离 VPN。当前仍使用系统网络和原有线 VPN，
没有用户态启动开关；无线会话宿主已迁至普通服务。
