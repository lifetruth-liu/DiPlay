# 分阶段开发计划

> 执行方式：使用 `superpowers:executing-plans` 按任务推进。每项功能先建立失败测试，再实现最小行为并验证；不为文档或无行为变化编写镜像测试。本文中的新增文件、测试和脚本是交付要求，尚未存在时不可直接宣称命令可运行。

**目标：**逐步交付可回退、可测试、适合低配车机的 USB 用户态网络后端。

**架构：**先抽取会话网络接口并保持原路径，再验证 lwIP 原型、接入端点和全部媒体通道，最后引入选择策略与实验发布。

**技术栈：**Kotlin、JNI/C、lwIP、ndk-build、宿主 CMake/CTest、JUnit/Robolectric、Android 真机与 Linux 故障注入环境。

## 执行约定

- 每个任务作为一个可独立审查的提交单元；PR 按阶段或有独立证据的小任务拆分，不将未验证后端设为默认。
- 下列路径相对于仓库根目录。`backend/` 代表 `shared/src/main/java/com/shilapi/xcertplay/network/backend/`；`jni/` 代表 `shared/src/main/jni/lwip_backend/`。
- 测试类放在与生产包对应的 `shared/src/test/java/`；纯 native 测试放在 `jni/tests/`，Android 设备测试放在 `shared/src/androidTest/java/`。
- 每项记录红灯测试失败原因、实现后运行结果、回归范围；链接测试报告，不只贴“通过”。
- 阶段负责人为实施 PR 的作者，评审者核对退出门槛；真机报告注明测试人、设备和构建。没有实测证据就不勾选设备验收。
- 一个阶段未通过时先修复或缩小发布范围，不以增加重试次数掩盖问题。预算调整需提交测量证据及影响说明。

### 验证顺序补充（2026-10-23）

先完成 P1–P5 的功能实现，并通过宿主、JVM/Android 模拟器可自动化验证；真实车机、iPhone、VPN 禁用设备、低配 ARM、真实 NCM、设备性能和长稳测试集中放在功能完成后的最终验收阶段。缺少设备或报告时，记录对应验收项未覆盖，但不得因此暂停 P2–P5 的功能实现。最终验收通过前，lwIP 不得切为生产默认后端或扩大自动回退范围。

## P0：范围、证据与基线

**进入条件：**可构建现有项目。参考设备、低配车机及 VPN 禁用设备用于最终验收；暂时缺少设备不阻断前期实现和宿主测试。

### P0.1 建立通道和权限清单

新增 `docs/lwIP/channel-inventory.md`；检查 [架构清单](architecture.md#2-通道审计清单) 中全部文件及有线可达调用链。

- [x] 对每个监听/连接/发送调用记录：协议用途、方向、地址族、bind 地址、动态/固定端口、通告位置、关闭所有者。源码清单见[通道审计](channel-inventory.md)。
- [x] 标记系统 scope、文件描述符、回环地址判断、JmDNS、TCP keepalive 等内核依赖。依赖与例外路径见[通道审计](channel-inventory.md#2026-10-23-静态网络调用复核)。
- [x] 记录 iAP2 StartCarPlaySession 与 NCM ready 的源码时序，确认 lwIP 虚拟监听启动不会等待尚未发生的 USB 可写事件。运行器回归验证启动与 USB ready 解耦，见[运行器记录](execution-status.md#p22-有界运行器)。
- [ ] 明确 IPv4/mDNS 是否为有线必要条件；以代码路径加至少一次脱敏抓包支撑结论。
- [ ] 在真实 VPN 禁用设备区分“没有授权 UI”与“establish 被系统拒绝”，记录预期选择路径。

**验证：**清单中每项均能定位到代码和测试用例编号，未发现的协商变体列为设备覆盖缺口，不臆测兼容。

### P0.2 固定测量方法与上游版本

新增 `docs/lwIP/baseline.md`、`docs/lwIP/adr-001-stack-selection.md` 和 native `UPSTREAM.md`。

- [ ] 记录候选提交、APK 校验、Android/ABI、USB 速率、iOS、分辨率、帧率、媒体码率及温控状态。
- [ ] 执行现有 VPN 和无线基线，采集吞吐、延迟、CPU、PSS/native 内存、GC、线程与 FD。
- [x] 选择 lwIP 正式 tag，记录 SHA、下载来源、校验、许可证、已知问题与最小功能配置。详情见 [ADR 001](adr-001-stack-selection.md) 与 [UPSTREAM.md](../../shared/src/main/jni/lwip_backend/UPSTREAM.md)。
- [x] 确认上游依赖不需要闭源下载步骤，源码构建符合现有公开树检查。
- [x] 对照性能计划冻结原型预算；允许更严格，不允许没有证据地放宽。代码上限和计数边界见[配置成本报告](configuration-cost.md#与-16-mib-初始预算的代码映射)，原始门槛见[性能预算](performance.md)。

**退出门槛 G0（实现准备）：**通道清单完整；现有 CI 有运行记录；版本和最小协议集已明确。参考设备基线移至最终验收；缺少设备不阻断 P1–P5 实现，也不能据此宣称设备验收或扩大默认启用。

## P1：网络接口与旧路径迁移

### P1.1 定义合同与系统适配

新增 `backend/SessionNetwork.kt`、`NetworkEndpoint.kt`、`TcpStream.kt`、`TcpListener.kt`、`UdpEndpoint.kt`、`BackendFailure.kt`；新增 `backend/system/` 下的对应实现。

测试新增 `network/backend/SessionNetworkContractTest.kt`，系统后端测试可使用回环网络。

- [x] 先编写 T01–T08 契约场景，证明现有直接 Socket 类型无法满足统一注入和关闭合同。
- [x] 实现系统包装，保持原 TCP noDelay、keepalive、端口冲突和地址选择行为。
- [x] 用 fake monotonic clock 测 deadline；不以长时间 sleep 检验超时。
- [x] 测 read/accept/receive 阻塞中 close、短写、并发读写、EOF、半关闭、UDP 截断。
- [x] 为地址/日志增加独立测试，不将 `InetAddress` 的系统接口索引当作会话身份。

### P1.2 拆分公共宿主并迁移协议使用点

新增 `network/AirPlaySessionHost.kt`、`network/CarPlaySessionService.kt`，修改 `CarPlayVpnService.kt`、`CarPlayController.kt`、`shared/src/main/AndroidManifest.xml` 及通道清单内业务文件。

- [x] 先测试非 VPN 会话宿主无需 VPN 授权和 VPN 服务绑定，关闭会话不误关新 generation。
- [x] 将监听/会话集合迁入公共宿主；VPN service 仅建立和撤销 TUN/bridge。
- [x] 向全部有线会话对象显式注入 `SessionNetwork`，无线仍使用系统后端。
- [x] 将 `TcpLiveness` 的能力转为后端操作，保持原空闲检测语义。
- [x] 保持后台存活、Activity 重建和停止顺序；测试服务意外断开时的资源清理。
- [x] 验证端口冲突重选后，iAP2/协议应答使用实际监听端口。

**验证：**T01–T08、L01–L04、C01–C04；原 `AirPlayPortSelectorTest`、`AirPlayDualListenerTest`、`AirPlayTcpAcceptTest` 和音视频相关回归；完整 CI。

**退出门槛 G1（接口迁移）：**仅系统后端启用；迁移前后自动化回归无未解释变化；公共宿主不再要求 VPN。VPN/无线真机冒烟移至最终验收，不阻断后续功能实现。

## P2：lwIP 原型与宿主可行性

### P2.1 固定依赖与宿主 harness

新增 `jni/Android.mk`、`CMakeLists.txt`、`config/lwipopts.h`、`include/diplay_lwip.h`、`src/core.c`、`src/netif.c`、`tests/`、`third_party/lwip/`、`UPSTREAM.md`；修改现有 `shared/src/main/jni/Android.mk`。

- [x] 先编写使用真实 Ethernet 帧的 NDP、TCP echo、UDP echo 测试，初始缺少实现时失败。
- [x] 建立确定性时间和帧注入 harness；同时提供 Linux TAP/veth 集成模式，与 Linux 内核互通，避免只有 lwIP 自测。
- [x] 核心实现采用单线程 raw API，注入 clock/wake/frame-output；Android JNI 不参与宿主测试。
- [x] 配置 IPv6 地址、MAC、MTU、NDP/DAD、TCP/UDP，按 P0 证据启用必要扩展功能。
- [x] 添加 `scripts/test_lwip_host.sh`：配置、构建、CTest，任一失败非零退出，输出编译器和依赖版本。
- [x] 四 ABI 构建，并检查最终 APK 中 native 库可加载及页面对齐要求。

### P2.2 接入 NCM 原型并测量

新增 `backend/lwip/LwipPrototype.kt` 与 `backend/NcmFramePort.kt`；修改 `NcmUsbBridge.kt` 的发送结果适配，保留原 API 调用者行为。

- [x] 先测试 send 的 sent/not-ready/failed 映射、短写和模拟拔线异常；不将未发送帧计为发送成功。宿主回归见 [NCM 发送结果与帧端口记录](execution-status.md#p22-ncm-发送结果与帧端口)。
- [x] 接入现有异步读请求，核心线程只投递 TX，不直接阻塞在 USB bulkTransfer。分线程与有界队列回归见 [运行器记录](execution-status.md#p22-有界运行器)。
- [ ] **最终设备验收：**验证无 VPN、无 ADB、无 root 条件下的 NDP、双向 TCP/UDP；真实 iPhone 使用已建立 CarPlay USB 模式触发报文，不能要求修改 iPhone 安装测试程序。此项在 P1–P5 功能实现后执行。
- [x] 吞吐微基准使用 Linux 对端；真实 iPhone 验证链路和协议时序，两种证据分开记录。Linux 隔离 veth 600 秒与 TAP 冒烟已完成；真实 NCM/CarPlay 短时会话见[设备记录](execution-status.md#api-26-arm64-真实-iphone-userspace-usb-carplay-烟测-2026-10-24)，但缺少支撑 IPv4/mDNS 结论的脱敏抓包。
- [x] 注入丢包/延迟和消费暂停，确认 TCP 重传、窗口收缩和内存有界。原型范围证据见[出口故障实验](prototype-wire-faults.md)和[背压预检](prototype-backpressure.md)；这些结果不替代 NCM 或设备内存预算验收。
- [x] 添加可复现的宿主 core 配置成本报告；其范围和结果见[配置成本报告](configuration-cost.md)。
- [x] 完成 Linux 隔离网络吞吐宿主性能预检：在临时 WSL2 副本中通过 600 秒 veth/netns TCP/UDP 基准及 TAP 基本互通，报告见[执行记录](execution-status.md#p2-linux-veth-宿主吞吐微基准-2026-10-24)。该宿主结果不替代低配 ARM、真实 NCM 或完整设备资源预检。

**验证：**N01–N09、U01–U04、原型性能预检；宿主 sanitizer；四 ABI 构建。

**退出门槛 G2（宿主原型）：**真实 Ethernet harness 的 NDP、TCP/UDP 和故障回归通过；宿主配置成本在预算内；没有已知启动时序死锁。实际 NCM + IPv6、低配 ARM 性能及完整设备资源门槛移至最终验收。若宿主回归或预算不通过，先修复再继续；设备证据缺失本身不阻断 P3–P5 功能实现。

## P3：JNI 端点与资源安全

新增 `backend/lwip/LwipSessionNetwork.kt`、`LwipTcpStream.kt`、`LwipTcpListener.kt`、`LwipUdpEndpoint.kt`、`LwipNative.kt`；native 按职责拆为 `src/endpoints.c`、`src/buffers.c`、`src/jni_bridge.c`。

- [x] 将 T01–T08 作为参数化合同同时运行系统和 lwIP 后端。
- [x] 先加入失效 handle、重复关闭、ABA 复用和取消竞态测试，再实现 generation + handle 注册表；禁止向 Kotlin 暴露裸指针。
- [x] 实现有界命令/接收/发送队列和 pbuf/DirectByteBuffer 所有权；错误路径与成功路径都核对计数归零。
- [x] TCP 应用消费进度驱动背压；测试暂停 10 秒后恢复仍保持字节完整和顺序。
- [x] 实现 UDP 原始长度、截断和来源元数据，测试零长度数据报。
- [x] JNI 检查异常、局部/全局引用释放和线程 attach/detach；不得在 Java 异常挂起时继续调用 JNI。
- [x] 实现关闭状态机、有限 join、native context 销毁屏障及结构化错误映射。
- [x] 使用配置限制测试 OOM、端点耗尽、pbuf 分配失败，不能依赖耗尽宿主物理内存模拟。

**验证：**先运行 T01–T08、L01–L06、R01–R04、sanitizer/CheckJNI；真机并发关闭和拔插留至最终验收。

**退出门槛 G3（自动化）：**契约两后端通过；已覆盖的失败路径无泄漏/越界/UAF；无阻塞核心线程；资源上限及释放证据齐全。真机并发关闭和拔插属于最终验收，不阻断 P4/P5 实现。

## P4：完整 CarPlay 集成

修改通道清单内所有有线可达网络调用，`CarPlayController.kt` 仅通过后端句柄取得实际地址/端口；必要时新增 `network/UserSpaceBonjour.kt`，其是否需要由 G0 决定。

- [x] 为主控制、事件、视频、UDP 音频、缓冲音频、时钟、麦克风、iAP tunnel、视频设置逐项建立 fake network 协商测试。
- [x] 接入显式 USERSPACE 有线启动路径：NCM → `NcmFramePort` → `LwipSessionNetwork` → 普通会话服务；默认 AUTO 仍选择 VPN，用户态路径不绑定 VPN service。宿主 owner 测试覆盖替换与过期解绑。
- [x] 逐通道接入 lwIP，保证协议通告端口与实际 bound port 一致，业务加密与媒体格式不变。API 28 模拟器 JNI 集成测试覆盖 AirPlay SETUP 返回值及 native listener/bind；真实 peer 视频、媒体、导航及 Siri 短时收发烟测见[设备记录](execution-status.md#api-26-arm64-真实-iphone-userspace-usb-carplay-烟测-2026-10-24)，完整通道验收仍未完成。
- [x] 验证 peer 地址传播、来源校验和内部连接判断，不用回环地址代替 iPhone 身份。系统与 native peer contract、非 loopback AirPlay metadata、UDP source 与端点归属测试通过；真实 NCM/iPhone 地址仍留最终设备验收。
- [x] 用户态连接的健康检测通过 lwIP 能力实施，不进入系统 FD 配置路径。JNI TCP peer-health instrumentation 通过，用户态路径调用 native TCP health control。
- [ ] **最终设备验收：**在 VPN 禁用设备验证全会话；逐项检查音乐、导航混音、Siri、通话、触控及已支持附加通道。API 26 arm64 手机已完成 USERSPACE NCM 下的视频、音乐、导航、Siri 双向语音和触控短时烟测；通话及其余设备/通道矩阵仍待验收，详见[设备记录](execution-status.md#api-26-arm64-真实-iphone-userspace-usb-carplay-烟测-2026-10-24)。
- [x] 静态搜索加调用链评审核对 iPhone 会话数据面的系统 Socket 无漏出；明确标注无线/ADB 路径，并审查远程 MFi HTTP 等独立出站依赖的路由所有者。源码结果与例外见 [通道清单](channel-inventory.md#2026-10-23-静态网络调用复核)。
- [x] JVM 回归覆盖 VPN 授权/作用域、无线 service attach 与断开清理；新增配对数据保存后重新创建 host 并读回的回归测试。
- [x] 在 API 28 x86_64 Android 模拟器上实际重建 `CarPlayHostActivity`，验证 Activity 实例更换并重读 AirPlay identity 与 wired backend 偏好；证据见 [执行记录](execution-status.md#p4-carplayhostactivity-实际重建-2026-10-23)。
- [x] 执行 VPN/无线/配对/断线自动化组合回归：Robolectric 覆盖无线→用户态→无线 attachment 替换、配对数据保留、过期 owner 不影响新 attachment、断开释放端口/网络，并断言该路径不调用 VPN；另运行 VPN scope 与配对持久化用例。真实 VPN/无线链路和会话验收仍按设备门槛记录，详见[执行记录](execution-status.md#p4-无线用户态切换与配对断开组合回归-2026-10-23)。

**验证：**先运行 C01–C08、U01–U04 和完整 CI；全功能真机记录移至最终验收。

**退出门槛 G4（功能实现）：**通过（2026-10-23）：所有有线通道的自动化协商与宿主/API 28 模拟器验证通过，显式用户态路径不准备或调用 VPN；没有未测通道的兼容声明。真实 iPhone/NCM 全会话与媒体验收留到最终验收。

## P5：选择策略、设置与诊断

新增 `backend/WiredBackendSelector.kt`、`backend/BackendDiagnostics.kt`；修改 `CarPlayHostActivity.kt`、`CarPlayController.kt`、`AirPlayPersistence.kt`、`DiPlayActivity.kt` 和所有 locale 资源。

- [x] 先写 B01–B07 选择状态测试，再实现每次启动确定一个后端；AUTO 默认 VPN，显式选项固定后端。
- [x] 显式用户态不请求 VPN/ADB；显式 VPN 不静默改为用户态，并有授权准备测试。
- [x] 自动模式只在明确 VPN 不可用时回退一次，不依赖成功缓存；用户拒绝、取消及 USB/MFi/协议故障不回退。
- [x] 持久化用户偏好；A/P0 已移除 USERSPACE 成功缓存与清除按钮。
- [x] 设置位于 Advanced，使用实验性标题后缀、`Title · Value`、完整翻译及 `markReconnectNeeded()`；不立即切换运行会话。
- [x] 诊断记录请求/实际后端、阶段、失败码、回退原因、版本/ABI、MTU、队列和内存高水位、NCM/lwIP 计数；固定字段不含地址或载荷。
- [x] 注入 native 库加载失败并与 AUTO 的 VPN 不可用回退组合验证；native 失败会停止在第二次尝试并给出可操作状态文案。

**验证：**B01–B07、D01–D03、设置 IA/翻译/持久化测试；紧凑/完整大字体截图，行对齐变化补阿拉伯语 RTL 截图；完整 CI。最终运行记录见 `execution-status.md`。

**退出门槛 G5：**策略与权限分离；每次启动尝试有上限；活动切换和取消不会残留旧后端；诊断可定位故障层。

## P6：性能、长稳与故障加固

新增 `scripts/benchmark_lwip.sh`、`scripts/fuzz_lwip.sh`、native fuzz targets；在 CI 增加 native 测试和 artifact 上传，保留现有 Android 完整检查。完善 `docs/lwIP/baseline.md` 和候选验证报告。

- [ ] 执行测试计划全部故障场景，修复后将最小复现样本加入回归语料。
- [ ] 在 P1–P5 功能实现和自动化回归完成后，集中执行最终设备验收：真实 NCM/iPhone、VPN 禁用/已有 VPN、最低 API、低配 ARM、全媒体通道、设备并发关闭/拔插和长稳矩阵；API 26 arm64/iPhone 已有一次短时 USERSPACE CarPlay 烟测，但不替代这些矩阵，详见[设备记录](execution-status.md#api-26-arm64-真实-iphone-userspace-usb-carplay-烟测-2026-10-24)。
- [ ] 在低配 ARM32 和 ARM64 执行同配置 A/B/A 性能基准及峰值压力。
- [x] 执行 10,000 次宿主资源生命周期测试；原生宿主定向证据见[压力测试记录](host-lifecycle-soak.md)，本轮完整 Release 与 sanitizer CTest 亦通过。
- [ ] 执行 8 小时设备会话及 100 次真实 USB 拔插/重连；Java/JNI/USB 整会话与设备长稳仍待验证。
- [ ] 执行 VPN 禁用设备、最低 API 设备、已有其他 VPN 设备和无线回归矩阵。
- [x] 跑至少 24 CPU 小时 native fuzz 活动，分别覆盖帧输入、端点命令和生命周期序列；保存 corpus 与失败 seed。三项目标各累计至少 28,800 CPU 秒，seed 7，报告及二进制摘要匹配、0 失败 batch、0 崩溃产物；详见[执行记录](execution-status.md#p6-seed-7-sanitizer-fuzz-24-cpu-小时门槛完成-2026-10-23)。
- [x] 完成代码级 core stall、资源上限、低内存错误映射、恢复与媒体排队审查；保留固定队列预算和未覆盖边界，详见[资源恢复审查](recovery-review.md)。该勾选只表示代码/自动化审查完成，不代表设备门槛通过。
- [x] 配置实时 RTP UDP 队列年龄预算：四个协商包时长（Opus/缺省包时长20ms），仅RTP数据端口；宿主测试验证数据/RTCP隔离，原生过期语义由host与JNI测试覆盖，见[UDP 队列年龄](udp-queue-age.md)。
- [ ] 在真实媒体消费者暂停/恢复时确认过期 RTP 不会造成恢复后播放陈旧音频；需真实 NCM/iPhone 媒体会话，设备门槛未覆盖。
- [ ] 确认 release 构建性能，sanitizer 构建结果不能替代发布构建预算。

**退出门槛 G6：**测试与性能计划的硬门槛全部通过；未覆盖项明确限制实验范围；无未解决崩溃、死锁、UAF、失控资源增长和功能缺失。

## P7：实验发布与长期维护

- [ ] 按发布清单整理候选提交、APK 校验、测试报告、兼容设备和限制。
- [x] 更新安装、隐私、设置、测试及第三方声明，说明用户态不接管其他应用流量。
- [x] 提供兼容性问题模板和可选脱敏诊断；不加入默认远程遥测依赖。
- [x] 保留实验选项；A/P0 中 AUTO 先使用 VPN，在明确 `VPN_UNAVAILABLE` 后尝试一次 lwIP，不依赖历史记录。用户拒绝或取消不回退；完整 G0–G6 设备验收仍单独跟踪。
- [ ] 真实设备证据足够后单独提交默认策略变更，重新运行选择与权限测试；不随普通优化隐式扩大。
- [ ] 至少两次连续实验发布没有新增阻塞性问题且 G6 重跑通过后，才评估结束实验；该条件不是自动批准。
- [ ] 每次 lwIP 升级重跑协议、JNI、fuzz、四 ABI 与低配性能，更新补丁记录。

**退出门槛 G7：**仓库能从源码复现构建和测试；用户能选择旧路径；兼容声明与设备报告一致；维护人能定位版本并回退。
