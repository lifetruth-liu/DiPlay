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

## P0：范围、证据与基线

**进入条件：**可构建现有项目，有至少一台能运行 VPN 的参考设备；开始准备低配车机及 VPN 禁用设备。

### P0.1 建立通道和权限清单

新增 `docs/lwIP/channel-inventory.md`；检查 [架构清单](architecture.md#2-通道审计清单) 中全部文件及有线可达调用链。

- [ ] 对每个监听/连接/发送调用记录：协议用途、方向、地址族、bind 地址、动态/固定端口、通告位置、关闭所有者。
- [ ] 标记系统 scope、文件描述符、回环地址判断、JmDNS、TCP keepalive 等内核依赖。
- [ ] 记录 iAP2 StartCarPlaySession 与 NCM ready 的时序，确认 lwIP 虚拟监听启动不会等待尚未发生的 USB 可写事件。
- [ ] 明确 IPv4/mDNS 是否为有线必要条件；以代码路径加至少一次脱敏抓包支撑结论。
- [ ] 在真实 VPN 禁用设备区分“没有授权 UI”与“establish 被系统拒绝”，记录预期选择路径。

**验证：**清单中每项均能定位到代码和测试用例编号，未发现的协商变体列为设备覆盖缺口，不臆测兼容。

### P0.2 固定测量方法与上游版本

新增 `docs/lwIP/baseline.md`、`docs/lwIP/adr-001-stack-selection.md` 和 native `UPSTREAM.md`。

- [ ] 记录候选提交、APK 校验、Android/ABI、USB 速率、iOS、分辨率、帧率、媒体码率及温控状态。
- [ ] 执行现有 VPN 和无线基线，采集吞吐、延迟、CPU、PSS/native 内存、GC、线程与 FD。
- [ ] 选择 lwIP 正式 tag，记录 SHA、下载来源、校验、许可证、已知问题与最小功能配置。
- [ ] 确认上游依赖不需要闭源下载步骤，源码构建符合现有公开树检查。
- [ ] 对照性能计划冻结原型预算；允许更严格，不允许没有证据地放宽。

**退出门槛 G0：**通道清单完整；现有 CI 有运行记录；至少参考设备基线可复现；版本和最小协议集已明确。缺少目标低配设备时可继续宿主开发，但不能通过 G6 或扩大默认启用。

## P1：网络接口与旧路径迁移

### P1.1 定义合同与系统适配

新增 `backend/SessionNetwork.kt`、`NetworkEndpoint.kt`、`TcpStream.kt`、`TcpListener.kt`、`UdpEndpoint.kt`、`BackendFailure.kt`；新增 `backend/system/` 下的对应实现。

测试新增 `network/backend/SessionNetworkContractTest.kt`，系统后端测试可使用回环网络。

- [ ] 先编写 T01–T08 契约场景，证明现有直接 Socket 类型无法满足统一注入和关闭合同。
- [ ] 实现系统包装，保持原 TCP noDelay、keepalive、端口冲突和地址选择行为。
- [ ] 用 fake monotonic clock 测 deadline；不以长时间 sleep 检验超时。
- [ ] 测 read/accept/receive 阻塞中 close、短写、并发读写、EOF、半关闭、UDP 截断。
- [ ] 为地址/日志增加独立测试，不将 `InetAddress` 的系统接口索引当作会话身份。

### P1.2 拆分公共宿主并迁移协议使用点

新增 `network/AirPlaySessionHost.kt`、`network/CarPlaySessionService.kt`，修改 `CarPlayVpnService.kt`、`CarPlayController.kt`、`shared/src/main/AndroidManifest.xml` 及通道清单内业务文件。

- [ ] 先测试非 VPN 会话宿主无需 VPN 授权和 VPN 服务绑定，关闭会话不误关新 generation。
- [ ] 将监听/会话集合迁入公共宿主；VPN service 仅建立和撤销 TUN/bridge。
- [ ] 向全部有线会话对象显式注入 `SessionNetwork`，无线仍使用系统后端。
- [ ] 将 `TcpLiveness` 的能力转为后端操作，保持原空闲检测语义。
- [ ] 保持后台存活、Activity 重建和停止顺序；测试服务意外断开时的资源清理。
- [ ] 验证端口冲突重选后，iAP2/协议应答使用实际监听端口。

**验证：**T01–T08、L01–L04、C01–C04；原 `AirPlayPortSelectorTest`、`AirPlayDualListenerTest`、`AirPlayTcpAcceptTest` 和音视频相关回归；完整 CI。

**退出门槛 G1：**仅系统后端启用，VPN/无线真机冒烟通过；迁移前后基线无解释不清的变化；公共宿主不再要求 VPN。

## P2：lwIP 原型与可行性门槛

### P2.1 固定依赖与宿主 harness

新增 `jni/Android.mk`、`CMakeLists.txt`、`config/lwipopts.h`、`include/diplay_lwip.h`、`src/core.c`、`src/netif.c`、`tests/`、`third_party/lwip/`、`UPSTREAM.md`；修改现有 `shared/src/main/jni/Android.mk`。

- [ ] 先编写使用真实 Ethernet 帧的 NDP、TCP echo、UDP echo 测试，初始缺少实现时失败。
- [ ] 建立确定性时间和帧注入 harness；同时提供 Linux TAP/veth 集成模式，与 Linux 内核互通，避免只有 lwIP 自测。
- [ ] 核心实现采用单线程 raw API，注入 clock/wake/frame-output；Android JNI 不参与宿主测试。
- [ ] 配置 IPv6 地址、MAC、MTU、NDP/DAD、TCP/UDP，按 P0 证据启用必要扩展功能。
- [ ] 添加 `scripts/test_lwip_host.sh`：配置、构建、CTest，任一失败非零退出，输出编译器和依赖版本。
- [ ] 四 ABI 构建，并检查最终 APK 中 native 库可加载及页面对齐要求。

### P2.2 接入 NCM 原型并测量

新增 `backend/lwip/LwipPrototype.kt` 与 `backend/NcmFramePort.kt`；修改 `NcmUsbBridge.kt` 的发送结果适配，保留原 API 调用者行为。

- [ ] 先测试 send 的 sent/not-ready/failed 映射、短写和拔线；不将未发送帧计为发送成功。
- [ ] 接入现有异步读请求，核心线程只投递 TX，不直接阻塞在 USB bulkTransfer。
- [ ] 验证无 VPN、无 ADB、无 root 条件下的 NDP、双向 TCP/UDP；真实 iPhone 使用已建立 CarPlay USB 模式触发报文，不能要求修改 iPhone 安装测试程序。
- [ ] 吞吐微基准使用 Linux 对端；真实 iPhone 验证链路和协议时序，两种证据分开记录。
- [ ] 注入丢包/延迟和消费暂停，确认 TCP 重传、窗口收缩和内存有界。
- [ ] 完成性能预检与配置成本报告，确定是否继续完整集成。

**验证：**N01–N09、U01–U04、原型性能预检；宿主 sanitizer；四 ABI 构建。

**退出门槛 G2：**实际 NCM + IPv6 可互通；无启动时序死锁；低配设备或同级 ARM 基准达到原型预算。只有桌面 echo 成功不能通过 G2。若不通过，先定位 USB、栈参数或 JNI 成本，停止扩大业务迁移。

## P3：JNI 端点与资源安全

新增 `backend/lwip/LwipSessionNetwork.kt`、`LwipTcpStream.kt`、`LwipTcpListener.kt`、`LwipUdpEndpoint.kt`、`LwipNative.kt`；native 按职责拆为 `src/endpoints.c`、`src/buffers.c`、`src/jni_bridge.c`。

- [x] 将 T01–T08 作为参数化合同同时运行系统和 lwIP 后端。
- [ ] 先加入失效 handle、重复关闭、ABA 复用和取消竞态测试，再实现 generation + handle 注册表；禁止向 Kotlin 暴露裸指针。
- [ ] 实现有界命令/接收/发送队列和 pbuf/DirectByteBuffer 所有权；错误路径与成功路径都核对计数归零。
- [x] TCP 应用消费进度驱动背压；测试暂停 10 秒后恢复仍保持字节完整和顺序。
- [x] 实现 UDP 原始长度、截断和来源元数据，测试零长度数据报。
- [ ] JNI 检查异常、局部/全局引用释放和线程 attach/detach；不得在 Java 异常挂起时继续调用 JNI。
- [ ] 实现关闭状态机、有限 join、native context 销毁屏障及结构化错误映射。
- [ ] 使用配置限制测试 OOM、端点耗尽、pbuf 分配失败，不能依赖耗尽宿主物理内存模拟。

**验证：**T01–T08、L01–L06、R01–R04、sanitizer/CheckJNI；真机并发关闭和拔插。

**退出门槛 G3：**契约两后端通过；已覆盖的所有失败路径无泄漏/越界/UAF；无阻塞核心线程；资源上限及释放证据齐全。

## P4：完整 CarPlay 集成

修改通道清单内所有有线可达网络调用，`CarPlayController.kt` 仅通过后端句柄取得实际地址/端口；必要时新增 `network/UserSpaceBonjour.kt`，其是否需要由 G0 决定。

- [ ] 为主控制、事件、视频、UDP 音频、缓冲音频、时钟、麦克风、iAP tunnel、视频设置逐项建立 fake network 协商测试。
- [ ] 逐通道接入 lwIP，保证协议通告端口与实际 bound port 一致，业务加密与媒体格式不变。
- [ ] 验证真实 peer 地址传播、来源校验和内部连接判断，不用回环地址代替 iPhone 身份。
- [ ] 用户态连接的健康检测通过 lwIP 能力实施，不进入系统 FD 配置路径。
- [ ] 在 VPN 禁用设备验证全会话；逐项检查音乐、导航混音、Siri、通话、触控及已支持附加通道。
- [x] 静态搜索加调用链评审核对 iPhone 会话数据面的系统 Socket 无漏出；明确标注无线/ADB 路径，并审查远程 MFi HTTP 等独立出站依赖的路由所有者。源码结果与例外见 [通道清单](channel-inventory.md#2026-10-23-静态网络调用复核)。
- [ ] 复测 VPN、无线、配对存储、断线和活动重建。

**验证：**C01–C08、U01–U04、完整 CI；全功能真机记录。

**退出门槛 G4：**全部有线通道通过；实验开关下用户态无任何 VPN 调用；没有“握手通过但通道未测”的兼容声明。

## P5：选择策略、设置与诊断

新增 `backend/WiredBackendSelector.kt`、`backend/BackendDiagnostics.kt`；修改 `CarPlayHostActivity.kt`、`CarPlayController.kt`、`AirPlayPersistence.kt`、`DiPlayActivity.kt` 和所有 locale 资源。

- [ ] 先写 B01–B07 选择状态测试，再实现每次启动确定一个后端。
- [ ] 初期保留原默认；显式用户态不请求 VPN/ADB；显式 VPN 不静默改为用户态。
- [ ] 自动模式识别明确 VPN 不可用，在启动边界至多回退一次；用户拒绝授权不自动等同于设备故障。
- [ ] 保存用户偏好与本次有效后端，成功缓存带版本/ABI 边界，可清除，不形成永久错误封禁。
- [ ] 设置按仓库要求放 Advanced，使用实验性标题后缀、`Title · Value`、完整翻译及 `markReconnectNeeded()`；运行会话不立即切换。
- [ ] 增加请求/实际后端、失败阶段、栈版本、资源峰值及丢包计数；不导出密钥、媒体和完整设备地址。
- [ ] 测 native 库不存在/加载失败与 VPN 不可用的组合，界面给出可理解失败原因，不循环弹授权。

**验证：**B01–B07、D01–D03、设置 IA/翻译/持久化测试；紧凑/完整大字体截图，行对齐变化补阿拉伯语 RTL 截图；完整 CI。

**退出门槛 G5：**策略与权限分离；每次启动尝试有上限；活动切换和取消不会残留旧后端；诊断可定位故障层。

## P6：性能、长稳与故障加固

新增 `scripts/benchmark_lwip.sh`、`scripts/fuzz_lwip.sh`、native fuzz targets；在 CI 增加 native 测试和 artifact 上传，保留现有 Android 完整检查。完善 `docs/lwIP/baseline.md` 和候选验证报告。

- [ ] 执行测试计划全部故障场景，修复后将最小复现样本加入回归语料。
- [ ] 在低配 ARM32 和 ARM64 执行同配置 A/B/A 性能基准及峰值压力。
- [ ] 执行 8 小时会话、100 次插拔/重连、10,000 次宿主资源生命周期测试。
  - 原生宿主10,000次子项已有[定向压力测试](host-lifecycle-soak.md)；Java/JNI/USB整会话及设备长稳仍待验证，父项保持未完成。
- [ ] 执行 VPN 禁用设备、最低 API 设备、已有其他 VPN 设备和无线回归矩阵。
- [ ] 跑至少 24 CPU 小时 native fuzz 活动，分别覆盖帧输入、端点命令和生命周期序列；保存 corpus 与失败 seed。
- [ ] 审查 core stall、资源上限、低内存和系统恢复，不以媒体缓冲变大掩盖网络排队延迟。
- [ ] 确认 release 构建性能，sanitizer 构建结果不能替代发布构建预算。

**退出门槛 G6：**测试与性能计划的硬门槛全部通过；未覆盖项明确限制实验范围；无未解决崩溃、死锁、UAF、失控资源增长和功能缺失。

## P7：实验发布与长期维护

- [ ] 按发布清单整理候选提交、APK 校验、测试报告、兼容设备和限制。
- [ ] 更新安装、隐私、设置、测试及第三方声明，说明用户态不接管其他应用流量。
- [ ] 提供兼容性问题模板和可选脱敏诊断；不加入默认远程遥测依赖。
- [ ] 首批发布保持显式实验选项；仅在已验证设备扩大自动回退范围。
- [ ] 真实设备证据足够后单独提交默认策略变更，重新运行选择与权限测试；不随普通优化隐式扩大。
- [ ] 至少两次连续实验发布没有新增阻塞性问题且 G6 重跑通过后，才评估结束实验；该条件不是自动批准。
- [ ] 每次 lwIP 升级重跑协议、JNI、fuzz、四 ABI 与低配性能，更新补丁记录。

**退出门槛 G7：**仓库能从源码复现构建和测试；用户能选择旧路径；兼容声明与设备报告一致；维护人能定位版本并回退。
