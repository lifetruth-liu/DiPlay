# USB 有线后端自动回退与恢复逻辑收敛：实现及测试方案

日期：2026-10-24。状态：A/P0、B/C 已实施并通过自动化验证；D/E 仍为方案，未执行本轮真机验证。

使用 writing-plans 方法整理；每阶段先补行为测试，再实现和验证。用户已授权实施 A/P0、B/C；D/E 尚未实施。现有未跟踪采集文件保留原样，本轮不提交。

## 目标与边界

1. P0：AUTO 优先 VPN，VPN 明确不可用时始终允许一次 lwIP 尝试，不依赖历史验证记录。
2. 删除“清除 lwIP 验证记录”按钮及失去用途的成功缓存链路。
3. 正确处理 USB 枚举、会话结束和异步回调，减少不必要的恢复等待。
4. 将配置恢复、旧会话结束、Start 重发调优拆成可独立验证的阶段。
5. 保留显式 VPN、显式 USERSPACE 的语义；不把 USB/MFi/协议错误和用户取消当成 VPN 不可用。

不承诺消除报告中的 18 秒端点未就绪。bulkTransfer 返回 <=0 只能直接证明写入未成功，NAK 和旧会话清理是需要进一步验证的解释。

## 当前证据

报告本轮：点击至 attempt 约 2.61 秒；配置 transition 等待约 10.03 秒；数据通道及首次 Start 约 1.54 秒；首次 Start 至首次 NCM 写成功约 18.01 秒；其后重发和 active 约 3.24 秒。删除一个阶段可能改变其他阶段，性能验收必须测总时间。

现有 AUTO 的阻塞不止选择器：

- CarPlayHostActivity.requestVpnConsent / maybeStartCarPlay 在 controller 之前要求 vpnReady；授权组件不可用进入 ADB 补授权。
- WiredBackendSelector 要求发布验证标记或版本/ABI 成功缓存才允许回退。
- CarPlayController 还要求 vpn.ncmReusable，防止将已关闭的 NCM 交给 lwIP。

## 阶段 A：P0 自动回退与移除验证记录

### 行为契约

| 设置/事件 | 结果 |
|---|---|
| AUTO，VPN 可用 | 使用 VPN，不启动 lwIP |
| AUTO，VPN 授权页面缺失、prepare/launch 因平台限制失败 | 记录 VPN_UNAVAILABLE，进入 lwIP，不等待 ADB 补授权 |
| AUTO，用户明确拒绝授权或取消连接 | 停止本轮，保持现有拒绝语义 |
| AUTO，VPN service 无法绑定或 establish 返回 null/明确平台不可用异常 | 同一连接 attempt 内最多回退一次 lwIP |
| AUTO，USB/MFi/协议错误、普通初始化错误 | 报告原错误，不切换后端 |
| AUTO，lwIP native 缺失或初始化失败 | 给出具体失败，结束本轮，不反复切换 |
| 显式 VPN | 只使用 VPN，保留已有授权/ADB兼容逻辑 |
| 显式 USERSPACE | 直接 lwIP，不启动 VPN 授权或 VPN ADB 流程 |

“始终尝试”表示取消成功记录门槛，不表示复用损坏 USB 或无视取消、拔线。所有归类为 VPN_UNAVAILABLE 的启动前失败必须保持 NCM 尚未移交，因此 ncmReusable=true。VPN 已接管、后续网络/USB故障不扩大归类为 VPN_UNAVAILABLE。

### 文件与改动

- shared/src/main/java/com/shilapi/xcertplay/network/backend/WiredBackendSelector.kt：构造只接受 preference；删除 userspaceAllowedForAuto、userspaceValidatedForRelease、successCache 参数和 WiredBackendSuccessCache。保留 STACK_VERSION 用于诊断；begin 仍是 AUTO/VPN -> VPN、USERSPACE -> USERSPACE。afterFailure 条件为 AUTO + 已尝试 VPN + VPN_UNAVAILABLE + 未取消 + 尚未尝试 USERSPACE；最多两种后端各一次。
- common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt：拆分“VPN 可用”“系统 VPN 不可用”“用户拒绝/取消”三种结果，绑定当前启动 generation。AUTO 的不可用结果允许启动 controller，并传入本轮前置失败原因；不可伪造 vpnReady=true，不将持久化偏好改为 USERSPACE。晚到的授权/ADB结果不能覆盖当前回退会话。
- shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayRuntimeConfig.kt：移除成功缓存；增加本轮可选 VPN 前置不可用结果，以明确枚举而非任意字符串表示；该结果只在当前启动有效，不持久化。
- shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt：统一启动前与 attach 时的 VPN_UNAVAILABLE 回退入口。启动前已不可用时不再绑定 VPN service，但诊断保留 VPN 不可用 -> USERSPACE 的因果链；不重新做 USB 配置切换、iAP2配对或 MFi。移除缓存成功回调和专用去重标记，保留 session active 原有通知。
- shared/src/main/java/com/shilapi/xcertplay/network/CarPlayVpnService.kt：核对每个失败出口的分类与 NCM 所有权。establish 前未交接时返回可复用；接管后失败先关闭资源，不谎报可复用。不将所有 Exception 改成 VPN_UNAVAILABLE。
- common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt：删除成功缓存读写/清除 API 和键常量。旧安装的两个孤立 preference 键不再读取，可自然遗留；不增加为删除两个键而运行的迁移框架。
- common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt：删除按钮；选项仍在 Advanced 的 EXPERIMENTAL_NETWORK 卡片，使用现有 choice/button 样式及 markReconnectNeeded。
- common/src/main/res/values*/strings.xml：所有 locale 删除两条清除记录字符串；更新说明：自动优先 VPN，VPN 不可用时尝试 lwIP，更改在下次连接生效。标题保留每个 locale 既有实验性后缀。
- 同步维护 docs/lwIP 中对 AUTO 门槛的现行说明，实测历史报告保留原样。

### 单元与集成测试

先将 WiredBackendSelectorTest 中“无记录禁止回退”的旧断言改成失败测试，后实现新策略。覆盖 AUTO 无缓存首次安装即可回退、VPN 正常不回退、显式选择不回退、失败枚举全量参数化、begin 重复调用、重复失败回调、lwIP失败后不循环。

更新 WiredBackendPreferencePersistenceTest：默认/非法值回 AUTO、显式偏好往返、旧成功缓存键存在或不存在对策略无影响，后端偏好不被回退修改。

扩展 CarPlayControllerSessionServiceTest，新增 Activity 启动前置策略测试：

- AUTO 的 prepare/launch 无组件或 SecurityException 能到达 USERSPACE，VPN ADB调用次数为0。
- 用户拒绝、用户取消、系统不可用分别断言；拒绝不会自动回退。
- 前置不可用和 attach不可用走同一选择策略，各仅构造一个 lwIP network。
- establish null / SecurityException / IOException 明确归类并回退；非法地址、普通初始化失败不回退。
- VPN 成功时 lwIP构造次数为0；显式 USERSPACE时 VPN prepare/bind/ADB次数为0。
- NCM 在回退成功后不被 VPN失败清理关闭；终止时最终关闭一次。注入非可复用错误应终止，不能交接已关闭实例。
- close、detach、service晚到连接和晚到授权结果不能恢复旧 attempt。
- 诊断同时保存 fallbackReason=VPN_UNAVAILABLE 与最终 lwIP失败原因。

## 阶段 B：USB 生命周期与诊断，先不改变恢复时间

### 实现

- IphoneUsbHost.kt 增加匹配 iPhone 的 detach 解析和注册；CarPlayController 注册并对称关闭 handle。
- 将 WiredSessionMemory.kt 从单一 deviceName 改为进程内的受同步保护记录：枚举 token、deviceName、Start时间、是否active、停止原因、transition结果。记录不持久化；节点名不是唯一生命周期凭据。
- 枚举 token 由进程内 USB 生命周期跟踪维护，独立于单个 controller 的 receiver 生命周期。应用启动时对当前设备建立新 token；重复 attach 不分配新 token；确认 detach 后同名 reattach 必须新 token。controller未存活时也要跟踪 detach，避免关闭到重建间隙污染记忆。
- token另配 controller attempt generation；所有 permission、open、transition、service回调携带捕获的 token/generation，落地前复核。失效回调若带资源，立即关闭它拥有的资源，不能仅 return 泄漏。
- REENUMERATION 中预期 detach：使旧 USB实例和请求失效，继续等待新 attach，不当成普通会话失败；物理连接阶段 detach：即时取消本地 owner、读写和watchdog，异步释放，显示断开/等待重插。主线程不等待 USB或线程join。
- 若未收到广播，通过已有发现轮询确认当前设备已不存在后做同样失效处理；不能把单次 bulk timeout当detach。
- 会话结束原因区分主动重连、启动失败、物理断开、异常关闭；START_SENT和ACTIVE不能混为一谈。
- 日志使用单调时钟，保留 attempt/token/backend/transition原因及结果、Start/NotReady/首个成功写/第二次Start/TCP/active/断开/关闭时间。统一诊断脱敏，不扩大原始设备标识暴露。

### 测试

扩展 CarPlayControllerUsbServiceTest：receiver注册和释放，包括启动中途失败；无关设备detach忽略；当前设备detach取消一次；预期重枚举detach不误失败；重复广播幂等；同名重新插入生成新token；晚到旧detach不得关闭新会话（结合token、事件顺序和当前设备清单验证，不能假设广播携带自定义token）。

新增 WiredUsbLifecycleTest / WiredSessionMemoryTest：controller之间持续跟踪、注册间隙、漏广播后的清单校验、旧回调资源释放、并发close/读写/service完成。测试 fake clock和可控executor，不依赖实际sleep。

本阶段验收：注入detach后一次主线程调度内状态失效；不等待10秒READ超时才响应；旧资源最终释放、无后台线程或owner泄漏。真机统计系统广播到应用取消的时间，暂定P95<500ms；物理拔线到系统广播单独测量，不混算。

## 阶段 C：收敛配置 transition，保持历史兼容路径

### 第一批可默认启用的规则

1. 完整 CarPlay配置 + 新枚举/没有需要恢复的旧active会话：直接openDataPaths。
2. 完整配置 + 仅Start发出但从未active：不只凭这个标记强制transition。
3. 完整配置 + 同一有效枚举中的active会话被主动结束：第一版保留一次旧恢复请求，覆盖#335。
4. 缺少完整CarPlay配置、仅USBMUX/NCM而无Apple Ethernet：保留现有mode request与拒绝后降级路径，覆盖#634/#509/#455。
5. 同一恢复周期限制transition次数；拒绝、有无detach/新attach、超时仍原配置分别记结果，不能仅记录“请求已发送”。

修改 WiredConfigurationAction.kt 接受明确恢复上下文，避免不断叠加布尔参数；纯策略只返回动作和原因，USB操作在controller执行。watchReenumeration保留generation保护和当前超时上限；设备已离开总线时不能打开旧UsbDevice。

### 第二批性能实验，达到门槛后再默认启用

对完整配置且可复用的“旧active会话主动重连”比较：A现有一次请求+10秒上限；B直接打开；C发送请求后无detach且配置未变时提前复用。三组单独测试，不和关闭顺序/Start窗口同时调整。

B/C作为测试构建中的内部策略，不增加正式设置项。C首轮候选等待为2秒，仅是实验参数，不是已知安全阈值；必须覆盖延迟detach，旧generation一旦detach立即取消，防止旧接口仍运行。没有验证收益前不把2秒写成通用默认。

直接打开失败后的恢复：走现有startup watchdog完成失败与本地释放，再由上层一次受限恢复attempt请求mode transition；携带当前恢复周期的预算，active或真实新枚举后重置。不能在旧attempt还活着时并行transition，也不能无限“直接打开 -> transition -> 直接打开”。

### 测试

WiredConfigurationActionTest补齐上述五条规则；START_SENT/ACTIVE/停止原因、旧新token组合参数化。CarPlayControllerUsbServiceTest覆盖提前复用、10秒兼容等待、延迟detach、旧计时器、请求拒绝、配置缺失、恢复预算耗尽、用户取消。保留历史问题场景行为测试，不只删除原断言。

性能决策看总点击到active和失败率；减少transition等待但让NCM等待增加不算收益。某设备首次确定transition无重枚举收益，也不能据此永久跨版本禁用必要配置切换。

## 阶段 D：主动结束旧会话，独立验证18秒窗口

先审计AirPlaySessionHost、service lease与网络close的实际执行顺序、usbmux channel.close是否发送可观测关闭，明确哪些行为能够让对端收到，而非只完成本地close。

仅主动停止/主动重连且USB仍在时，实验顺序为：标记会话停止且阻止新任务 -> 关闭当前AirPlay会话/媒体及其TCP连接（此时保留NCM与iAP2）-> 在有发送完成证据或有界窗口结束后关闭iAP2/USBMUX -> 释放NCM/MFi。必须拆分AirPlay lease关闭与网络销毁，不能把service.detach提前就认为对端收到了FIN。

该顺序只是候选；必须先确认网络close/命令提交的语义。若socket.close本身不保证FIN排出，则增加有界的发送完成机制或保留为研究结论；不凭空添加未确认的CarPlay停止消息、不添加固定sleep模拟“正常结束”。物理拔线和崩溃走立即abort，不等待对端。

测试记录调用顺序、未响应的硬截止、USB途中拔掉、重入close、新attempt隔离、旧lease无法detach新lease。真机主动重连至少30轮，单独比较首次Start到首次成功写的分布。若18秒窗口没有改善，不合并纯推测的关闭顺序改动。

## 阶段 E：Start重发与UI

默认保留WiredStartAgain的2秒静默、500ms轮询及最多一次主动重发。保留已active或链路打开后已见TCP尝试时不重发，以及watchdog只允许延长一次。

前面阶段稳定后，单独比较静默2秒/1秒/500ms，若降低成功率或重复会话增加则保留2秒。先不为节省最多500ms引入额外调度线程。

UI显示可验证阶段：切换USB模式、身份认证、等待iPhone USB网络、建立CarPlay会话、VPN不可用正在尝试用户态。不能把“旧会话释放中”当已证明事实。若首次写之前已经观察到TCP尝试，则状态按实际事件推进，不让标签倒退。设置改动仍只标记下次重连，由用户选择立即重连。

测试WiredStartAgainTest、WiredStartWatchdogTest、Iap2WiredControlClientTest的边界：开链先于Start/晚于Start、临界时间、active、TCP尝试、重复availability、第二次Start只延长一次、close后计时器无效。UI测试CarPlayStageTextTest与所有locale翻译覆盖。

## 测试执行与发布门槛

### 1. 每阶段局部验证

先新增失败行为测试，运行对应测试类确认失败原因，再实现并重跑。策略使用fake clock，controller使用Robolectric暂停Looper和可控executor。所有新增测试验证外部行为/资源归属，不写镜像实现的测试。

示例命令（Windows PowerShell）：

```powershell
.\gradlew.bat :shared:testDebugUnitTest --tests '*WiredBackendSelectorTest' --tests '*WiredConfigurationActionTest' --tests '*WiredStartAgainTest' --tests '*WiredStartWatchdogTest'
.\gradlew.bat :common:testDebugUnitTest --tests '*WiredBackendPreferencePersistenceTest' --tests '*CarPlayControllerSessionServiceTest' --tests '*CarPlayControllerUsbServiceTest' --tests '*WiredBackendSettingsScreenshotsTest'
```

新增类后加入相应局部命令；要求全部退出0，报告内无失败。

### 2. 设置截图与本地化

更新WiredBackendSettingsScreenshotsTest，预置旧缓存键时也不显示清除按钮。产出320dp紧凑、960dp完整布局、fontScale=1.5截图；包含中文、长文案语言和阿拉伯RTL；增加高密度配置。检查选择行值/箭头对齐、同高、无裁切、无新增字面量间距或px尺寸。人工打开截图确认，不能只判断文件生成成功。

运行SettingsTranslationsTest、SettingsLayoutPolicyTest、AdaptiveSettingsUiTest。类别未移动仍保留Advanced；所有本地化标题实验性后缀符合AGENTS.md。

### 3. 必须的CI命令

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --stacktrace
```

Gradle找不到SDK时设置ANDROID_HOME或local.properties。遵循.github/workflows/android.yml继续运行：

```powershell
python scripts/check_public_tree.py
python scripts/check_lwip_native.py --apk mobile/build/outputs/apk/debug/mobile-debug.apk
```

不修改native栈时不扩展无关benchmark；仍要求既有CI中的native/sanitizer/fuzz job通过。修改native关闭/FIN语义时才补对应host/sanitizer测试并执行相关脚本。

### 4. 真机矩阵

- 已复现Android 8/API26 arm64 + 当前iPhone作为主基线；补实际车机和较新Android；四ABI由打包检查覆盖，有对应设备/模拟器时补native加载测试，不能用模拟器替代USB真机。
- 补至少一组不同iPhone/iOS；可获得时覆盖历史上需要mode transition或拒绝请求的组合，未获得要明确发布验证缺口。
- 三种后端选择分别测：冷插、主动重连、失败重试、强停重开、拔插、锁屏、授权未授予/拒绝、系统授权页面缺失、VPN service绑定失败、establish失败、native加载失败。
- 验证VPN“明确不可用”的注入与实际ROM场景。不能假定另一个VPN运行就必定establish失败；记录真实结果。
- 每项核心成功路径至少10轮；主要重连场景每策略至少30轮，A/B交错并保持拔插/间隔一致。30轮P95只能作初步对比，不能声称覆盖长期可靠性。
- 正常连接后检查画面、媒体/导航音频、触控、Siri、HUD/仪表，连续运行至少1小时并做30轮拔插，观察线程、USB请求、内存/文件描述符是否累积。发布长稳要求沿用项目既有G0–G6，不把1小时冒充完整长稳验收。
- Wi-Fi ADB用于日志与故障注入；另做无ADB连接验收。主机ADB掉线单独记录，不能当iPhone detach。

### 5. 量化验收

- P0：无验证记录的AUTO在每个明确VPN_UNAVAILABLE测试中进入一次lwIP；VPN正常不切换；用户取消/拒绝不回退；显式偏好始终遵守；NCM无双重持有和泄漏。
- 记录所有样本，失败计入分母；同时报告成功率、成功样本中位数/P95、失败原因、超时数。失败样本不能从性能结论中隐去。
- 配置优化：不应transition的分支在单元测试中等待0；真机总连接中位数/P95优于基线且成功率未观察到下降。预设性能目标为主复现场景总中位数至少减少5秒；不达标保留生命周期修复，性能策略不升默认。
- 关闭优化：独立降低首次Start到首个写成功时间且无新失败，否则不合并候选行为。
- Start窗口优化：最多收益约1.5秒，若无稳定收益则维持2秒。
- 未得到实机数据时，只能声明实现/自动化验证通过，不能宣称连接已提速或旧会话根因已证实。

## 交付与回退顺序

按独立提交/PR单元交付：A自动回退及缓存删除 -> B生命周期与诊断 -> C默认安全规则 -> C性能实验结论/选择策略 -> D关闭候选及实测结论 -> E状态提示和经验证的Start窗口。

A和B可独立落地；C/D/E行为优化各有独立回退点。回退配置或关闭策略时保留P0自动回退和生命周期修复。不增加面向用户的实验开关集合；实验策略仅测试构建内部注入，选定后删除无用分支。

实现交付附：代码diff、局部与完整CI结果、设置截图、测试矩阵、原始时间线与统计、未覆盖设备清单。历史采集报告不因新策略而被改写。

## A/P0 实施结果（2026-10-24）

已取消 AUTO 的发布验证/版本/ABI 成功记录门槛，移除成功缓存链路、清除按钮和九种语言的两条清除文案。AUTO 仍先选 VPN；明确不可用且 NCM 可复用、连接未取消时，只回退一次 lwIP。启动前授权组件缺失/受平台限制时，AUTO 跳过 VPN ADB 补授权及 VPN 绑定，经同一个选择策略进入 lwIP；拒绝授权不回退。显式 VPN 的授权/ADB 兼容路径保留，显式 USERSPACE 不请求 VPN。

前置失败绑定 controller 启动 generation，新一轮重连会重新准备 VPN。授权请求使用独立的 consent generation：同一个授权请求可以跨越“无线切有线”的 controller 重建；更新的请求、用户态/无线切换、关闭连接或更新的回退结果会阻止旧回调生效。

回归测试先复现无记录禁止回退、AUTO 误走 ADB、旧失败沿用和旧无线 controller 导致授权结果丢弃，再实施修复。选择器全失败枚举、持久化旧键、服务绑定/NCM 保留、授权拒绝/迟到结果、重连重新准备和实际通告端口均有覆盖。新增 IOException 保留 NCM、非法配置不归类 VPN_UNAVAILABLE 的服务测试。

完整 AGENTS.md 九项 Gradle 命令加 `--max-workers=2 --console=plain --stacktrace` 成功，耗时 8m10s；shared 1830、common 1612 项测试零失败/错误，home 测试任务无测试源；三应用 lint 与 assemble 通过。首次默认并发检查出现音频缓冲和热点 ADB 两项计时失败，二者单独复跑及最终完整重跑均通过，未修改其实现或放宽断言。

`check_public_tree.py`、`check_lwip_native.py --apk mobile/build/outputs/apk/debug/mobile-debug.apk` 和 `git diff --check` 通过。APK SHA-256 为 `b3d53b8ab191bf011401ebfabd331b6a2ca78e5aa62568f5e86b16c80945f9ad`。设置截图位于 `common/build/reports/lwip-settings-ui/`，已检查大字体紧凑中文/日文、高密度中文、完整英文/俄文及阿拉伯 RTL；旧缓存存在时没有清除按钮。

独立代码复核的两个生命周期问题已修复并复核通过。本轮未安装到真机、未复测连接时延或执行设备兼容矩阵；B–E 的 USB 配置、生命周期、关闭顺序及 Start 时序保持原样。

## B/C 实施结果（2026-10-24）

### B：枚举、资源与异步回调

- `IphoneUsbLifecycle` 在进程启动 provider 中安装，独立于 controller 持有一份 USB attach/detach receiver；已发现设备存在时每 1 秒校验 Android 清单，覆盖 controller 重建间隙及漏广播。controller 单独注册的授权/attach/detach receiver 和失效订阅随其关闭，部分注册失败也对称释放。进程监听初始化失败会回收已注册 receiver，并保留原始异常及清理异常。
- 枚举 token 不持久化，不读取手机序列号。重复 attach 不产生新 token；确认消失后同名设备是新 token。清单已更新但 detach/attach 广播排队稍后送达时，按事件对换 token；单独的迟到 detach 不关闭仍在清单中的新设备。
- Android 公开的节点名/VID/PID 无法区分“旧 detach 后又来一条伪重复 attach”与真正的快速拔插。遇到该事件对时保守换 token；这个公开 API 限制需要在目标固件验证，不声称可识别任意乱序/伪造广播。
- controller 的 permission、ADB、transition、open、watchdog 和资源发布同时检查当前枚举及 attempt generation。USB 授权 PendingIntent 带进程内唯一 request epoch，旧请求的授权/拒绝不会落入新请求；轮询仍以 Android 当前权限和设备为准。已到 DATAPATHS/CONTROL 的迟到权限结果直接忽略。
- ADB 的 attempted/pending key 包含捕获的 token 和 attempt generation，失效时只清理旧 token 的 key；未执行的旧任务先复核有效期，不继续发起授权。这避免同名重插时被旧的 pending key 永久挡住。watchdog 的当前性检查、失败标记和失败发布与 close/active 共用锁，取消或 active 获胜后不会留下孤立的直接路径失败标记。
- 预期重枚举 detach 只失效旧请求，继续等待新枚举；其他 detach 同步取消 owner/watchdog，后续 USB/MFi/service 关闭在后台执行。晚到打开成功的 USB pipe 立即由回调关闭，不进入 NCM；mux、CSM 和本地 NCM 的发布有关闭竞争检查。
- `WiredSessionMemory` 分开记录 Start、active、结束原因和 transition 结果。失效 token 不能被晚到写入复活；仅保留不含设备身份的 opaque token 撤销标记至进程结束。日志增加 token、generation 和单调 elapsedMs，记录 Start/重发、TCP 接入、active、detach 和关闭结果；不更改 NCM NotReady/DAD 行为。

### C：默认策略、一次恢复与实验入口

| 上下文 | 默认动作 |
| --- | --- |
| 完整配置，新枚举／没有旧 active 会话 | 直接打开 |
| 只发送过 Start，未 active | 直接打开，不因此强制 transition |
| 同一枚举中已 active，随后主动重连 | 保留一次兼容 transition，等待上限 10 秒 |
| 缺配置／缺 Apple Ethernet | 保留历史 mode request、次数上限及拒绝后可用配置降级 |
| 直接路径启动 watchdog 失败 | 旧资源关闭且 worker 退出后，下一轮最多领取一次恢复请求 |
| 恢复轮失败或预算耗尽 | 结束自动重试，提示拔插 iPhone；不循环直接打开和 transition |

恢复预算在 controller 重建之间保留，成功 active 或真实新枚举开始新的周期。关闭失败或 worker 未退出不标记 released，不能领取恢复。新 controller 的原有本轮 transition 上限继续生效，防止一次启动随反复枚举无限切换模式。

实验策略没有新增设置或持久化开关：仅 debuggable 应用接受启动 Intent 的 `wiredRecoveryExperiment` 字符串，值为 `COMPATIBILITY`、`DIRECT` 或 `EARLY_REUSE`；也可在 debug 测试中注入 `CarPlayRuntimeConfig.wiredRecoveryExperiment`。非 debuggable 应用一律使用 COMPATIBILITY。DIRECT/EARLY_REUSE 仅改变同枚举旧 active 主动重连；缺配置和缺 Apple Ethernet 仍走兼容路径。

EARLY_REUSE 取 2 秒作为实验候选。结构指纹在 vendor request 前保存，包含配置编号、接口编号/alternate/class/subclass/protocol 及端点地址/attributes/maxPacket/interval。2 秒时必须仍是同一枚举、完整配置且指纹相同；即使配置编号未变，只要接口/端点改变也退回 10 秒窗口。提前复用后发生延迟 detach，立即失效旧 attempt。

### 自动化与待验收项

使用暂停 Looper、可控 executor、USB 清单和平台边界替身，覆盖快速同名拔插、重复/迟到/无关广播、漏广播、controller 间隙、旧授权/ADB pending key、旧打开资源、提前复用后的延迟 detach、完整/变化描述、release 失败/worker 未终止、恢复领取并发、watchdog 与取消/active 竞争、预算耗尽以及 debug/release 实验隔离。蓝牙与 dashboard 的会话级测试使用无线 fixture，继续验证原有断言，避免伪造未建立的 USB 枚举。无线故障注入沿用旧的双参数入口，由内部故障方法承接新恢复状态。

独立规范/代码复核发现的快速同名拔插、同编号描述变化、旧 ADB pending key 和 watchdog 竞争问题均已复现、修复并复核通过。完整检查中的无线故障注入兼容问题通过保留原入口修复；dashboard 测试的真实一秒仪表轮询曾产生 5 次写入而非预期的 1 次，已在显式控制可见性的 fixture 中隔离该回调，保留原断言，不改产品 dashboard 行为。

最终 AGENTS.md 九项 Gradle 命令加 `--max-workers=1 --console=plain --stacktrace` 成功：7m02s，250 个任务中 15 执行、235 UP-TO-DATE。JUnit 报告 shared 1874、common 1633 项，合计 3507 项，零失败/错误；home 无测试源。三个应用的 lint 与 assemble 全部通过。`check_public_tree.py`、`git diff --check` 及四 ABI native/APK 核验通过；最终 APK 与核验报告的 SHA-256 一致，为 `fdbcb8a9166c9ebc5806e114450c864e0fd24f161d907bed233480e48e333a97`。本机日志位于临时目录 `diplay-bc-final-ci.log`、`diplay-bc-final-targeted.log`、`diplay-bc-native-report.json`，JUnit/HTML 报告位于各模块 `build/test-results` 和 `build/reports/tests`。

真机 API26/车机/不同 iPhone 的成功率、时延分布和广播到取消的 P95 尚未验收；2 秒/DIRECT 不升为正式默认。D 的关闭顺序和 E 的 Start 静默/轮询调优未实施。
