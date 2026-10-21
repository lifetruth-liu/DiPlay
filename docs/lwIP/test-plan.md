# 测试计划与证据要求

## 1. 原则与环境

测试分为纯 JVM、native 宿主、Android 集成、真实车机四层。模拟器不能证明 USB Host/iPhone 互通，宿主吞吐不能证明低配车机性能，握手成功不能证明音视频完整。

所有案例至少记录：测试编号、候选 commit、APK SHA-256、后端、Android/API、ABI、设备/固件、iPhone/iOS、USB 模式与速率、负载、命令、结果和报告路径。失败记录最后成功阶段、结构化错误、复现次数和日志时间段。

新增测试类建议：

| 测试位置 | 场景 |
| --- | --- |
| `shared/src/test/java/com/shilapi/xcertplay/network/backend/SessionNetworkContractTest.kt` | T01–T08，两后端共用契约；lwIP 通过宿主桥或对应设备测试运行，不能在 Robolectric 假装加载 Android so |
| 同目录 `BackendLifecycleTest.kt` | L01–L06，fake frame port/clock、确定性竞态 |
| 同目录 `WiredBackendSelectorTest.kt` | B01–B07，注入权限和故障结果 |
| 同目录 `BackendDiagnosticsTest.kt` | D01–D03，字段完整及隐私 |
| `shared/src/main/jni/lwip_backend/tests/` | N01–N09、R01–R04，真实栈和分配失败 |
| `shared/src/androidTest/java/com/shilapi/xcertplay/network/backend/` | JNI 契约、服务生命周期、native 加载和 CheckJNI |
| `common/src/test/java/com/shilapi/xcertplay/` | 设置持久化、分类、翻译、重连标记 |

纯 JVM 测试只负责接口消费者、选择策略和确定性模型；lwIP 本身的正确性必须经过真实 native 测试。两后端执行相同的测试向量，允许运行器不同。

## 2. 网络接口契约

| 编号 | 操作与注入 | 必须满足的断言 |
| --- | --- | --- |
| T01 | bind 0、连续创建监听、占用端口 | 返回真实端口；冲突明确报错或按策略重选；通告端口一致 |
| T02 | TCP 随机分块双向传输，含 1 字节及大缓冲 | 总字节、顺序和 SHA-256 一致；不依赖一次 read 等于一次 write |
| T03 | 对端 FIN、RST、本地取消、读超时 | EOF、连接重置、取消和超时可区分；无零字节忙循环 |
| T04 | accept/read/write/receive 阻塞中 close，重复 close | 等待者在 deadline 内退出；资源只释放一次 |
| T05 | 接收暂停 10 秒后恢复，发送端持续写 | 窗口/背压生效；内存不越预算；恢复后数据完整 |
| T06 | UDP 长度 0、1、典型媒体包、超接收缓冲、上限外发送 | 保留边界和来源；截断带原长度；非法超大报文失败 |
| T07 | 单读者+单写者并发、半关闭后继续读 | 不死锁；已发送字节在 FIN 前；对端响应可继续接收 |
| T08 | IPv6 同地址不同 session/link、非法端口和 deadline | 不跨会话；scope 不依赖内核接口；输入校验在进入 native 前执行 |

## 3. IPv6 与 TCP/IP 互通

使用固定二进制 Ethernet fixture 加 Linux 内核对端。pcap 只作为输入证据，断言来自报文解析和对端实际收到的数据；不能将“没有崩溃”当成互通成功。

| 编号 | 场景 | 断言 |
| --- | --- | --- |
| N01 | 邻居请求/通告、solicited-node multicast、MAC 学习 | 正确地址/MAC/校验和；无邻居时可按规则完成解析 |
| N02 | DAD 正常和冲突、手机重新出现及 MAC 变化 | 地址就绪/冲突可观察；旧邻居不永久污染重连 |
| N03 | TCP SYN/ACK、双向连接、FIN/RST、重复 SYN | 状态和回包正确；关闭后端口/控制块按上限回收 |
| N04 | 丢包 1%、5%；乱序 10%；重复包 1% | TCP 内容一致并出现合理重传；不无限堆积；UDP 损失可计数 |
| N05 | 接收零窗口、恢复窗口、发送缓存耗尽 | 不丢应用字节；无核心线程阻塞；窗口恢复可继续 |
| N06 | MTU 边界、Packet Too Big、扩展头、分片乱序/超时 | 支持集正常；不支持项按协议安全拒绝；重组内存/时间有界 |
| N07 | 错误校验和、截断包、非法长度、伪造 NDP、未知 EtherType | 不越界、不错误交付；明确丢弃计数；控制报文校验含 IPv6 必要字段 |
| N08 | 大量连接/半开连接、端口耗尽、组播洪泛 | 拒绝超限工作；合法已有会话仍可关闭；无日志洪泛 |
| N09 | 空闲、计时器超时、时钟向前推进、系统墙钟跳变 | 单调时钟驱动；合法静止会话存活；失败对端可被识别 |

Linux 故障注入要求 harness 暴露独立测试接口；`tc netem` 只施加于该接口，不修改开发机默认路由。每次保存规则、随机 seed 和计数，退出时清理测试接口。N04 不要求高损耗下仍满足无损性能门槛，重点验证恢复和资源边界。

## 4. 生命周期、USB 与资源故障

| 编号 | 注入 | 断言 |
| --- | --- | --- |
| L01 | NEW/PREPARING/LISTENING/LINK_ACTIVE 任一时刻 stop | 进入 STOPPED；资源归零；不继续发送业务回调 |
| L02 | 旧 generation 回调延迟至新会话启动 | 不能关闭或写入新会话，不复用失效 handle |
| L03 | 并发 stop、服务断开、Activity 重建 | 一次有效清理；UI 生命周期不误杀应存活会话 |
| L04 | accept 返回与 stop 同时发生 | 新交付连接被取消或归属旧会话，绝不漏入新会话 |
| L05 | core/USB 线程延迟和关闭超时 | 上报超时；不 free 活对象、不无限启动替代线程 |
| L06 | 10,000 次宿主创建/销毁、100 次设备重连 | live handles/pbuf/租约归零；线程/FD 无持续增长 |
| U01 | USB 无权限、授权取消、设备拔出 | 权限错误不变成 VPN 故障；取消后不继续启动 |
| U02 | NCM 未 ready、短写、读超时、请求完成与取消竞态 | 未 ready 不等同 detach；短写失败明确；不丢失已完成读、不双重 reap |
| U03 | NTB 分段/合并、非法长度、resync、USB 音频驱动占用 | 保持现有 USB 兼容行为，已有 NCM/USB 回归通过 |
| U04 | 播放中拔线、快速重插、手机重启 | 无 UAF/死锁；失败回调一次；新会话能独立启动 |
| R01 | 第 k 次 native 分配失败，逐点遍历 | 已分配资源清理，返回 RESOURCE_LIMIT，不崩溃 |
| R02 | pbuf/端点/命令队列/UDP 队列耗尽 | 有上限、可观测；TCP 背压、UDP 明确溢出策略 |
| R03 | JNI 异常、失效 handle、不同线程访问、引用积累 | 错误映射明确；CheckJNI 无违规；引用回收 |
| R04 | 解码/录音/消费者暂停和恢复 | core 不被阻塞；过期 UDP 不产生长时间延迟回放 |

## 5. CarPlay、选择与诊断

| 编号 | 场景 | 断言 |
| --- | --- | --- |
| C01 | 主控制、配对、认证、动态端口 | 协议应答与有效端点一致，原配对数据可用 |
| C02 | 视频连续/静止/恢复、分辨率和帧率变体 | 画面和触控正常，无空闲误断 |
| C03 | UDP 音乐、导航混音、音频控制 | 音频数据/control 都通过当前后端，连续无异常 |
| C04 | 缓冲音频、暂停/恢复、音频模式变化 | TCP 完整性、延迟和关闭符合原语义 |
| C05 | Siri、通话、麦克风上行 | 双向可听且时钟正确；不只检查录音成功 |
| C06 | 事件、HID/触控、iAP tunnel、视频设置 | 每个已支持通道逐项有证据，未协商项标未覆盖 |
| C07 | 无线切有线、有线切无线、重连 | 旧网络资源清理，地址/端口不沿用错误后端 |
| C08 | 真 VPN 禁用、无授权组件、无 ADB、无 root | 全功能用户态会话，不调用 prepare/establish/appops |
| B01 | 原默认、显式 VPN、显式用户态 | 偏好被尊重；用户态不请求 VPN |
| B02 | 自动模式 VPN prepare/establish 明确不可用 | 只在启动边界回退一次，记录原始原因 |
| B03 | 用户拒绝 VPN、取消连接或退出页面 | 不以故障名义绕过偏好，不循环授权、不继续已取消操作 |
| B04 | USB/MFi/协议失败 | 不错误触发网络后端轮询 |
| B05 | native 加载失败、ABI 不支持、初始化失败 | 错误可解释；显式用户态不悄悄切 VPN |
| B06 | 会话运行中修改偏好 | 下次生效并显示重连提示；不直接中断会话 |
| B07 | 升级/降级/更换固件、清除成功缓存 | 缓存按规则失效，真实失败不变成永久黑名单 |
| D01 | 正常/失败导出诊断 | 请求和有效后端、版本、阶段、峰值/计数齐全 |
| D02 | 导出带媒体、地址、密钥测试样本 | 不泄露媒体负载、凭据、配对标识和完整设备地址 |
| D03 | 报文洪泛、重复错误 | 日志限频且保留累计计数；诊断不阻塞 core |

## 6. 设备与版本矩阵

| 维度 | 最低要求 |
| --- | --- |
| Android | API 24/25 实机覆盖 link-local 与 USB 旧行为；API 26–28；至少一种 API 29+ 车机；当前项目目标版本的权限/服务测试 |
| ABI | armv7 低配与 arm64 真机；x86/x86_64 编译、加载及模拟器契约测试 |
| 固件 | 正常 VPN、无授权 UI、真正禁止 VPN、另有 VPN 正在运行；分别记录，不能互相替代 |
| iPhone | 至少两代硬件与两个 iOS 主版本；包含项目仍声称支持的最低版本，缺失时限制声明 |
| USB | 数据线/不同数据口；High-Speed 及实际遇到的低速/Full-Speed 环境；异常线缆只要求安全失败，不保证媒体峰值 |
| 音视频 | 当前支持的主要编码与缓冲音频模式；最低和常用显示配置，含高负载档 |
| 业务 | 音乐、导航、Siri、通话、触控、静止画面、前后台及屏幕关闭/唤醒策略 |
| 用户界面 | 紧凑/完整、大字体；行对齐变化覆盖 RTL；设置搜索、持久化、重连条 |

已有 VPN 场景还须确认其他应用流量/路由未被用户态后端改变。车机测试停车进行，录音/通话以受控测试内容验证。

## 7. 运行入口与 CI

### 当前已有的仓库检查

每个实现 PR 和最终交付均运行，与 `.github/workflows/android.yml` 保持一致：

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --stacktrace
```

Windows PowerShell 使用 `./gradlew.bat`，参数不变并放同一行。SDK 找不到时设置 `ANDROID_HOME` 或 `local.properties`，不得提交本机 SDK 路径。

### P2/P6 必须交付的新增入口

以下脚本现在属于计划产物，开发阶段实现后方可运行：

```sh
# P2：Linux/macOS 宿主核心测试；失败非零退出
bash scripts/test_lwip_host.sh --build-dir build/lwip-host --sanitizers address,undefined
# P3：设备 JNI 契约；需要兼容且已连接的 Android 测试设备
./gradlew :shared:connectedDebugAndroidTest
# P6：release-like 宿主基准，写入结构化指标及环境元数据
bash scripts/benchmark_lwip.sh --output build/lwip-benchmark
# P6：三类 target 各 8 CPU 小时，总计至少 24 CPU 小时
bash scripts/fuzz_lwip.sh --target ethernet --cpu-hours 8 --output build/lwip-fuzz/ethernet
bash scripts/fuzz_lwip.sh --target commands --cpu-hours 8 --output build/lwip-fuzz/commands
bash scripts/fuzz_lwip.sh --target lifecycle --cpu-hours 8 --output build/lwip-fuzz/lifecycle
```

脚本必须提供 `--help`、固定随机 seed 的重放参数、构建版本和非零失败状态。仅存在不被测试发现的源码不能算作已接入 CI。

- PR：现有 Android 检查 + native 单元/契约 + ASan/UBSan + 四 ABI 构建；设备测试单独注明运行结果。
- 定时或人工任务：fuzz、协议故障矩阵、100 次重连和 8 小时长稳；不要求每个 PR 跑满 24 CPU 小时。
- 发布候选：累计满足完整 fuzz 门槛，重跑失败 corpus、低配性能和完整设备矩阵。
- CI 上传：JUnit/CTest、sanitizer 日志、corpus/seed、性能 JSON、构建元数据；抓包和原始媒体默认不上传公共 CI。

## 8. 长稳与报告模板

8 小时脚本/人工流程每小时包含音乐、动态/静止画面、导航提示、Siri、受控通话、暂停/恢复；每分钟记录资源摘要。100 次设备循环交替正常停止、播放中拔线、快速重插，记录成功率和失败层，不能只挑成功循环。

```text
候选 commit / APK SHA-256：
测试编号 / 时间 / 测试人：
设备、固件、API、ABI、USB 速率：
iPhone / iOS / 媒体配置：
请求后端 / 有效后端 / 回退原因：
操作与故障注入参数 / 随机 seed：
预期结果 / 实际结果 / 通过或失败或未覆盖：
CPU、PSS、native 内存、队列峰值、延迟分位数：
线程/FD/handle/pbuf/缓冲租约的前后值：
复现次数 / 总次数 / 错误分类：
日志、报告、pcap（访问受限）的路径与校验：
限制与未覆盖项：
```

阻塞发布：崩溃、死锁、越界/UAF、资源失控、关闭超界且影响重连、任一宣称支持的通道不可用、自动策略违背用户偏好、真实 VPN 禁用设备不可用。已知低速 USB 物理带宽不足须说明支持边界，不能用调大缓存将其标为通过。
