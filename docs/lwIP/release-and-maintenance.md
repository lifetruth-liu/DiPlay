# 发布、诊断与开源维护

## 1. 选择策略

后端偏好至少包括 `AUTO`、`VPN`、`USERSPACE`，持久化偏好与本次实际后端分离。AUTO 优先使用 VPN，仅在明确 VPN 不可用时尝试一次 lwIP；不依赖本机验证记录。

| 输入 | 行为 |
| --- | --- |
| 显式 USERSPACE | 检查 native 可用性、USB 权限，启动用户态；不调用 VPN prepare/establish、ADB/appops |
| 显式 VPN | 按原授权流程启动；失败保留原因，不静默切换 |
| AUTO，已授权 VPN 正常 | 初期沿用 VPN；不因“存在新后端”打断现有行为 |
| AUTO，VPN 授权组件不存在或系统明确禁止 establish | 启动边界尝试一次 lwIP；授权组件不可用时不等待 ADB 补授权 |
| AUTO，用户拒绝授权 | 视为用户选择；显示可选用户态路径，不自动重试授权或把拒绝写成设备故障 |
| AUTO，USB/MFi/iAP2/协议失败 | 不据此在后端间轮换；报告实际失败层 |
| USERSPACE native 缺失或初始化失败 | 清晰报告失败并结束本轮；不回到 VPN，不循环 |
| 正在运行时改变偏好 | 保存，下次连接生效并显示重连条；用户明确操作后才重连 |

每次启动总后端尝试数最多 2，每个后端最多 1 次；取消会终止全部后续尝试。切换前完整停止旧后端，不能两个消费者同时读取 NCM。USB 关闭后需要重新打开或重走设备配置时，按既有 USB 生命周期执行，不能复用已关闭对象。

运行中的故障不支持透明迁移 TCP 会话。按现有重连政策结束连接，在下一次启动选后端；不能声称已有媒体连接可无缝迁移。

不再记录版本/ABI 成功缓存，也不提供清除验证记录按钮。旧安装的孤立缓存键不读取、不影响选择；后端回退不修改持久化偏好。显式 VPN 保留原有授权及 ADB 兼容路径，用户拒绝/取消不触发自动回退。

USB 枚举按进程内 token 跟踪，不能只凭设备节点名沿用旧会话记忆。完整配置默认直接打开；同枚举旧 active 会话必须先有资源完全释放证据，否则拒绝重叠启动。只发过 Start 不等于 active。直接启动 watchdog 超时后，旧资源释放才能领取一次兼容 transition 恢复；预算跨 controller 保留，恢复失败停止自动重试。配置缺失、缺 Apple Ethernet 及请求拒绝的历史路径继续保留。

10秒上限用于必要的模式切换和一次失败恢复，不再用于正常主动重连。debug 启动参数 `wiredRecoveryExperiment=COMPATIBILITY` 仅用于对照；默认和 release 均使用 DEFAULT。旧 DIRECT/EARLY_REUSE 参数与2秒提前复用实现已移除，不增加正式设置。

NCM传输诊断只记录原始USB写返回值、长度、配置超时、实际耗时、收发计数、Ethernet/TCP包头类型和标准通知字段；不记录地址/载荷。启动期前4次写/收包、首次写成功/首次SYN及每秒最多一次后续采样，成功后的常规传输不持续输出日志；状态通知前8次后每秒最多一次。关闭汇总仅代表已记录完成的传输，不能当作在途USB事务的最终账本。负返回值不标为已证明的NAK。见[默认复用与诊断记录](wired-bc-default-and-diagnostics-2026-10-25.md)。

## 2. 设置与用户文档

实验阶段选项放 Advanced，标题使用既有 locale 规定的实验性后缀。选择行采用 `button()` 的 `Title · Value` 形式；说明“下次连接生效”，保存后调用 `markReconnectNeeded()`。

必须更新：

- `common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt` 的 section/category 映射，禁止普通 `section(...)` 导致跨分类重复。
- `AirPlayPersistence.kt` 的保存、默认、迁移与非法值回退。
- 默认及全部 `values-xx` 新字符串，使用 `settings_` 前缀。
- 设置分类/翻译/搜索/持久化测试；布局变化附紧凑和完整大字体截图，行对齐变化补阿拉伯语 RTL。
- `docs/INSTALL.md` 及维护中的翻译、设置说明、`docs/TESTING.md`、隐私说明、发行说明。

用户说明要明确：用户态后端仅用于 DiPlay 的 USB 网络；其他应用流量不经过它；USB 权限及 iPhone 的信任/CarPlay 确认仍必要；不保证克服硬件或固件所有限制。实验后端故障时可手动选择原 VPN 路径，系统确实禁用 VPN 的设备不能承诺这个回退一定可用。

## 3. 诊断合同

`BackendDiagnostics.report()` 当前输出固定字段；未提供的可选数值统一写作 `unavailable`：

```text
wired.backend.requested / effective / attempt / generation
wired.backend.stage / failureCode / fallbackReason
wired.stack.version / abi / nativeLoadResult
wired.link.mtu / ncmTxSent / txNotReady / txShortWrite / rxResync / rxRejected
wired.queue.rxHighWaterBytes / rxHighWaterFrames / txHighWaterBytes / txHighWaterFrames
wired.queue.commandHighWater / commandHighWaterBytes
wired.memory.currentBytes / peakBytes / liveHandles
wired.udp.dropped / txSent
```

测试冻结了字段名，并验证非法值和可能包含敏感内容的 label 会被省略、旧 generation 的迟到更新不会覆盖当前快照。报告不会写入设备地址、异常文本或媒体载荷。当前尚未输出 config hash、core work/timer latency、TCP 连接/重传/零窗口、UDP 接收/截断细分以及 shutdown 时长/未释放资源数；这些是后续诊断能力，不应描述成现有日志承诺。报告由调用方与 app/API/ABI、候选版本和 APK 摘要一起保存。日志保持聚合，逐包跟踪默认关闭。

不得默认导出音视频内容、配对材料、令牌、MFi 私密材料、手机名称和完整 MAC/IP。开发抓包必须用户主动开启，私有保存、有限大小/时间，分享前脱敏；公开 issue 优先使用脱敏报告与构造的最小报文样本。无远程遥测也应能完成本地排障。

## 4. 故障排查顺序

1. USB 枚举/权限：确认数据口、线缆、授权和设备配置；未进入 NCM 不调 TCP 参数。
2. iAP2/MFi：查看认证和 StartCarPlaySession；不因这些失败回退网络后端。
3. NCM：检查 not-ready、短写、读完成和 resync，确认 USB 层仍活跃。
4. IPv6：核对地址、MAC、DAD/NDP 和 MTU；用户态地址不需要出现在系统网卡列表。
5. 监听/端口：核对实际端口与 iAP2/协议通告，判断 SYN 是否到达。
6. 协议与媒体：逐通道检查 accept、协商、收发、时钟和业务消费水位。
7. 性能与关闭：区分 USB 等待、core stall、消费者慢、队列满、资源超限和旧回调。

修改前保存最小复现；修复后运行对应测试编号及原失败 corpus，再执行必要回归。不能将调大队列、无限重试或关闭校验作为通用修复。

## 5. 依赖与贡献规范

lwIP 作为固定源码依赖随公开树构建。保留上游许可证、版权和出处，更新 `docs/THIRD_PARTY_NOTICES.md`；核对选中 tag 的实际许可证及项目整合要求，不能只凭项目首页判断全部文件。

`UPSTREAM.md` 必须记录正式 tag、完整提交 SHA、源码校验、来源 URL、配置差异、补丁及构建工具版本。优先将适配放在项目目录；必须改动上游时，每个补丁注明原因、测试和上游跟踪链接，避免无法升级的深度 fork。

新增贡献须包含：改变的行为、涉及后端、测试编号、复现步骤、资源/性能影响和文档更新。网络报文修复包含最小脱敏 fixture；JNI/生命周期修复包含竞态或分配失败测试。文档中的通道和接口语义变更与代码同 PR，不能只更新中文说明而遗漏代码注释中的所有权合同。

主要接口和 native 所有权注释应使用清晰英语便于国际贡献者；本目录中文方案保留为完整设计入口，后续英文版本需链接同一测试编号，避免两个互相冲突的规范。

升级流程：核对上游变更/安全修复 → 更新固定版本和补丁 → 宿主协议/契约 → sanitizer/fuzz corpus → 四 ABI → Android 完整 CI → VPN/无线回归 → ARM32/ARM64 性能与设备长稳。即便接口未变化，TCP/NDP 定时与内存行为变化仍需设备验证。

## 6. 发布门槛

- [ ] G0–G6 证据完整，所有声称支持的设备/通道有报告。
- [ ] 真 VPN 禁用设备的无 VPN/无 ADB 全功能验证通过。
- [ ] API 24/25 与低配 ARM32、ARM64 覆盖通过，四 ABI 构建/加载通过。
- [ ] 8 小时混合负载、100 次重连及 24 CPU 小时 fuzz 门槛通过。
- [ ] 性能/内存/关闭预算通过；缺少证据的配置不列为支持。
- [ ] 用户偏好、有限回退、取消和运行中设置变更行为通过。
- [ ] 原 VPN 和无线回归通过，用户态不改变其他应用路由。
- [ ] 许可证、安装、隐私、设置、诊断和问题模板同步。
- [ ] 完整 CI 有候选提交上的新记录，不能引用旧提交的绿灯。
- [ ] 已列出已知限制、回退办法、候选 APK 校验与维护联系人/仓库 issue 入口。

## 7. 回退与扩大范围

初期保持实验选择，不引入运行时远程开关依赖。发现 native 崩溃、死锁、资源失控或重要通道故障时，暂停扩大自动启用，发布修复或恢复原默认策略；用户显式偏好与迁移行为必须有测试。

回退以已验证发布版本或独立策略提交为单位；不要只替换 so 而保留不兼容的 Kotlin/JNI ABI。持久化使用稳定枚举，未知值回到安全默认，不损坏配对资料。

两次连续实验版本满足 G6 且无新增阻塞性问题后，提出单独的默认策略变更评审，附兼容设备数量、固件范围、失败分布和性能报告。不能把“用户未报告”当作已测成功，也不能用 ARM64 手机通过代替低配车机。

### USB D/E 内部实验（2026-10-25）

生产保持兼容关闭及 2000 ms Start 静默窗口，500 ms 轮询和一次重发限制不变。没有新增用户设置或持久化开关。
仅 debuggable 应用接受启动 Intent 的 `wiredCloseExperiment=GRACEFUL`（默认 `LEGACY`）及
Long `wiredStartQuietMillis=2000/1000/500`；其他值回退默认，controller 再次检查 debug 标志。
实验值用于新 controller：已有会话必须先主动断开再启动。D 的实验值控制当前会话怎样结束，收益测量属于其后的重连，不能按下一轮的参数误分组。

D 仅对 USERSPACE、有效枚举、已 active 的主动结束启用；其他失败、拔线、VPN 路径不等待候选屏障。
网络保持到会话关闭/本地发送排空或 750 ms 截止，之后正常销毁；超时不认证资源完全释放。
本地排空不代表 FIN/ACK 完成，不得依据该日志认定 iPhone 已释放旧会话。正式默认变更须遵守独立 A/B 门槛和设备矩阵。
详见 [D/E 语义审计](wired-de-execution-plan-2026-10-25.md) 和 [设备记录](reconnect-latency-2026-10-25-de-report.md)。

### NCM 启动独立探测对照（2026-10-25）

历史实验中，debug启动Intent曾接受`lwipStartupPolicy=PROBE`；该开关及探测实现已按用户要求删除。采样结果保留在[对照报告](ncm-startup-probe-report-2026-10-25.md)，不能据此认定当前代码仍提供实验入口。

### 连接实验清理（2026-10-25）

已撤除配置 A/B、原生 OUT 20/100ms、PROBE、GRACEFUL 关闭、短 Start 静默窗口和兼容恢复对照的启动参数及候选分支，保留原有配置选择、Java20 启动写入、默认 lwIP 链路恢复、2000ms Start 静默与资源释放屏障。临时 iPhone syslog 抓取和逐包实验日志一并清理；保留通用诊断和历史报告。此前 D/E 与 PROBE 章节描述的是历史实验，不是现有启动选项。
