# USB 恢复 D/E 真机与自动化记录（2026-10-25）

## 构建与范围

基于 `f014530d`，分支 `codex/wired-close-and-start-stages`。设备为 MI 5s Plus、Android 8/API26、arm64，iPhone 保持 USB 连接；使用 Wi-Fi ADB 覆盖安装并保留应用数据。实际后端为 USERSPACE/lwIP。APK SHA-256：`d8b96045cb91b888e0dd5b68b12f73f2bb2a5c1103b2df7204a2d294160e5e02`。

D 是 debug-only 的主动关闭候选：关闭当前 AirPlay 控制/事件/媒体连接时清除 reset-on-close，保留 NCM/iAP2，等待本地关闭命令与已产生输出完成，或整个候选的 750 ms 截止，随后销毁旧控制通道和网络。旧 lease 的释放与新 attachment 隔离。物理拔线、失败、VPN 不启用此等待。本地排空不是 FIN/ACK 或 iPhone 会话释放的证明。

E 保留生产的 2000 ms 静默、500 ms 轮询及一次重发；1000/500 ms 是 debug-only 候选。USB 模式、认证、等待网络、建立会话和 VPN 回退阶段都有九种语言文案。阶段依据当前枚举的链路/TCP 证据推进；实际 SYN 先于首次成功 USB 写入时可以进入建立会话阶段，不倒退。

## 自动化验证

完整 AGENTS.md 九项 Gradle 命令通过，附加 `--max-workers=1 --console=plain --stacktrace`：13m15s，250 项任务中 42 执行、208 UP-TO-DATE。shared 1895、common 1647，共 3542 项单测，零失败/错误；home 无测试源。三个应用的 lint 与 assemble 全部通过。公共树扫描、diff 空白检查和四 ABI native/APK 核验通过。

新增回归覆盖：关闭资源所有权及旧 lease；真实 TCP EOF；关闭过程中接受事件/IAP tunnel；核心忙时修改本地关闭标记；发送队列为空但 USB send 仍在执行；历史 NotReady 不能跳过本轮关闭屏障；暂停命令时钟下的单调截止；卡住的媒体回调、拔线与超时；旧 controller 回调不能清除新连接全局状态；真实有线 TCP 接入及旧 attachment 回调隔离；静默精确边界、早于 Start 的 TCP/active、默认和 debug/release 门槛、阶段不倒退和控制进度不重复计数。

本机 CI 日志：临时目录 `diplay-de-final-ci.log`，APK 核验 `diplay-de-native-report.json`；JUnit 与 HTML 在模块的 `build/test-results` 和 `build/reports/tests`。原始手机日志仅保留本机临时目录，不将手机身份或协议载荷写入本报告。

## 采集口径与异常

通过应用现有“断开连接”执行主动结束，再启动带实验参数的新 controller。D 的参数属于被关闭的会话，观察其后的重连；E 的静默参数属于新会话。配置恢复策略始终保持 COMPATIBILITY，未混入 DIRECT 或 2 秒 transition 提前复用。

记录 controller 启动到 active、首次 Start 到 NCM 首次写成功、网络可写到 Start 重发，以及重发到 active。controller 启动时长不包含菜单导航和 Activity 创建，不能冒充点击到 active。

覆盖安装后的首次 attempt 在约 60.3 秒失败，自动重试的第二次 attempt 在 22.771 秒进入 active。该安装启动单独记录，不归入主动重连样本；本轮未可靠保留第一次失败的完整异常，不能据此确定根因。后续线程快照主线程处于 Looper 等待，并已存在正常媒体线程，不支持“持续卡在打开 USB”的判断。

采集器曾因 logcat 的 `unexpected EOF` 丢失连续流，改用应用轮转日志与自动重开的过滤流。一次脚本只比较整秒时间，错误读到了同秒较早的旧 active，已改成毫秒边界；该条采集记录不作为性能样本，对应连接依据后续实际日志补记。旧版 Python 3.6 在并发启动带重定向的 logcat/ADB 子进程时还出现了管道句柄继承问题，导致 Activity 启动命令等待及采集暂停；改用内置 Python 3.12 并关闭句柄继承后完成补采。这不是 iPhone 物理拔线或已证明的连接失败。原始采集错误保留，不将其计成成功连接。

## 分组结果

最终构建共观察到 17 次成功的主动重连（attempt 3–19，包含探索、换组预热及补采），不是 17 次独立冷启动。以下四组各取 3 个明确归组的样本，均为 3/3 成功；其余 5 次预热/探索也保留在[脱敏样本](reconnect-latency-2026-10-25-de-samples.json)中。安装后的首次失败及恢复属于前述独立启动记录。

下表均为成功样本中位数，单位秒。样本太少，不估计可靠 P95；不得把分组先后运行当成随机交错 A/B。

| 被关闭会话的策略／新会话静默 | attempt | controller 到 active | 首次 Start 到网络可写 | 可写到重发 | 重发到 active | 可写到 active |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| LEGACY／2000 ms | 7–9 | 35.306 | 20.663 | 2.181 | 1.151 | 3.332 |
| LEGACY／1000 ms | 10–12 | 33.771 | 18.555 | 1.402 | 2.126 | 3.503 |
| LEGACY／500 ms | 13–15 | 31.659 | 16.285 | 0.863 | 2.553 | 3.341 |
| GRACEFUL／2000 ms | 17–19 | 35.862 | 20.586 | 2.243 | 1.030 | 3.273 |

D 的三次旧会话关闭均捕获到 `result=NOT_CONFIRMED localEgressOnly=true`，关闭总耗时分别 105、104、130 ms，worker 退出；没有触发 750 ms 截止。NOT_CONFIRMED 来自保守的本地证据判断，不能当成 FIN 未发送或已发送的证明。后续首次 Start 到可写中位数 20.586 秒，与兼容组的 20.663 秒相近，未观察到 D 消除主要等待。

E 的短窗口确实提前了重发，但本组样本中重发后的等待增加，可写到 active 三组仍约 3.3–3.5 秒。500 ms 组的总时间较低，同时其首次网络可写就提前了约 4 秒；不能将总时间差归因于静默缩短。因此不提升短静默为默认。

最后一次启动使用 `LEGACY`、`2000 ms`，attempt 19 在 01:05:12.135 记录 active。设备保留最终构建与原有后端偏好；没有卸载清数据或改动系统 VPN/权限设置。

## 发布限制

这些是单台设备的短时分组冒烟，不是每策略 30 轮交错 A/B、不同 iPhone/车机或长期可靠性验收。没有无 ADB 对照、物理拔插矩阵及一小时媒体/导航/Siri/触控长稳，本轮不能认证这些项目。

生产默认继续使用兼容关闭与 2000 ms 静默。没有证据证明旧 iPhone 会话清理是首次写等待的唯一根因，也不宣称 D 已消除该等待。D 的实验实现不作为正式默认策略发布。
