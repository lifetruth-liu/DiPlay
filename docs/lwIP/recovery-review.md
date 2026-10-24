# P6 资源、停滞与恢复代码审查

审查日期：2026-10-24。范围是当前 lwIP owner、队列/端点资源、低内存错误映射、UDP 实时包排队和已有宿主/Android 自动化证据。它不替代真实 USB/NCM、iPhone、低配 ARM 或长稳验收。

## 停滞与关闭所有权

- `LwipPrototype` 的 native core 只由 owner worker 创建、调用和销毁。USB 关闭使用独立 worker；core 调用卡住时，关闭等待有 2 秒上限。
- join 超时会让 runner 保持 `STOPPING`，不把活着的 native core 强制释放。旧 owner 退出后才销毁 core 并释放进程级 claim。代价是 owner 永不返回时，同进程后续 lwIP 创建会被资源上限拒绝；这是保护内存安全的故障收敛，不是自动恢复成功。
- 测试覆盖暂停中的 core call、重复关闭超时、关闭 worker 延迟加入、阻塞 USB writer 下的有限 drain，以及显式等待后 owner 恢复。选定的六组 JVM 类本轮 75/75 通过；详细列表见 `LwipPrototypeTest`、`CoreCommandQueueTest`、`CoreCommandResourceTest`、TCP/UDP endpoint tests。
- 这些用例使用受控 fake port/core。Android 调度器永久卡死、进程被系统回收，以及真实 USB 驱动不响应关闭仍未覆盖。

## 资源上限与背压

当前会话路径的主要硬上限为：

| 资源 | 上限 | 超限/拥塞行为 |
| --- | ---: | --- |
| core 命令 | 128 条、保留数据 256 KiB | 返回 `RESOURCE_LIMIT`；关闭和取消使用独立路径 |
| endpoint scopes | 80 | 与 32 TCP、16 listener、32 UDP 配置池对应；拒绝新资源 |
| DirectByteBuffer pool | 256 KiB | 有界申请，租约归还后复用；OOM 映射为 `RESOURCE_LIMIT` |
| Runner RX | 128 KiB、128 帧 | 丢弃并计数，不增长队列 |
| Runner TX | 192 KiB、128 帧 | 停止从 native owner 取更多帧，等待 USB writer 腾出容量 |
| Native TX ring | 128 × 1514 字节 | 固定容量；满时输出拒绝并计数 |
| Native lwIP heap | 1 MiB | 分配失败走结构化错误/协议丢弃路径；不扩容 |
| IPv6 重组 | 4 个上下文、最多 128 个 pbuf、30 秒 | 配额限制、淘汰/过期并回收 |

真实配置容量、宿主 heap peak 和全进程内存不是同一个指标。Android/ARM 的线程栈、USB/Java 对象、so 映射和进程 PSS 尚未纳入设备实测；因此上述逐项上限不能单独证明总计 16 MiB 动态内存或 24 MiB PSS 门槛。

## 低内存与系统恢复

- native 定向分配故障覆盖 core 冷/热创建、周期及 TCP 定时器、UDP/TCP endpoint、pbuf、重组队列和多处写入/接收路径；逐场景检查输出句柄、池基线和恢复后重试。该矩阵仍明示为完整 R01 的子集。
- Java DirectByteBuffer 的受控失败、HotSpot heap/direct-memory OOM 及 Android 独立 service 进程 ART heap OOM 有测试记录。它们只证明被覆盖分配边界的错误映射，不能承诺任意 OOM 后继续运行。
- 宿主 `lifecycle_soak` 执行 10,000 次 native core 创建/销毁并检查池和 FD/线程基线；API 28 instrumentation 另以 fake Ethernet port 执行 100 次 JNI session/endpoint 生命周期。真实 NCM 拔插、USB 读写挂起后重连和 8 小时会话仍未覆盖。
- Linux Release veth 600 秒 benchmark 和 TAP smoke 已通过，配置未因慢消费者而增大。慢消费者 10 秒暂停/恢复 1 MiB TCP echo 的预检也通过；这些不是低配 Android 性能或 CarPlay 媒体余量证明。

## RTP 队列年龄

lwIP UDP 端点有按端点配置的最大排队年龄和过期计数；默认值为 0。`CarPlayMediaEngine`现按协商包时长配置`AudioStream`的 RTP 数据端口，RTCP维持默认策略；系统后端缺少该可选能力时保持原行为。`docs/lwIP/udp-queue-age.md`说明了时间戳范围、固定队列预算和未覆盖的解码/媒体层缓冲。宿主测试验证预算计算和 RTP/RTCP 端口隔离，原生/JNI测试验证队列过期语义；设备上的真实消费者暂停/恢复仍需验证。

## 审查结论

代码级审查确认当前 owner 所有权、固定队列上限、慢消费者背压与已覆盖的低内存回收路径有对应实现和自动化证据；本次没有找到足以支持额外代码修复的未覆盖错误路径。审查不等于 P6/G6 通过：完整 R01、R04、整进程/低配 ARM Release 预算，以及真实设备和媒体验收仍是开放项。

本轮可复核的 JVM 运行命令：

```powershell
.\gradlew.bat :shared:testDebugUnitTest --tests '*LwipPrototypeTest' --tests '*CoreCommandQueueTest' --tests '*CoreCommandResourceTest' --tests '*LwipTcpStreamTest' --tests '*LwipTcpListenerTest' --tests '*LwipUdpEndpointTest' --max-workers=2
```

JUnit 汇总：75 tests、0 failures、0 errors、0 skipped。更广的 native、ART OOM、10,000 次宿主生命周期、600 秒 veth 和设备门槛分别见 [执行记录](execution-status.md)、[宿主生命周期](host-lifecycle-soak.md)、[性能预算](performance.md) 和 [UDP 队列年龄](udp-queue-age.md)。
