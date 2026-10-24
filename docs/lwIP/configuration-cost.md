# lwIP 宿主配置成本报告

本报告用可重现的宿主工具检查固定 lwIP 配置的容量口径，并在创建全部 UDP 端点与
TCP listener 后读取 core 统计。工具为 `lwip_config_cost_report`，源码位于
`shared/src/main/jni/lwip_backend/tests/config_cost_report.c`，JSON 输出可直接保存为报告附件。

## 复现

从仓库根目录执行；Linux sanitizer 构建：

```sh
cmake -S shared/src/main/jni/lwip_backend -B build/lwip-config-linux \
  -DDIPLAY_SANITIZERS=ON -DDIPLAY_FUZZ=OFF -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build/lwip-config-linux --target lwip_config_cost_report -j2
build/lwip-config-linux/lwip_config_cost_report
```

Windows MinGW 构建可将 `build/lwip-config-linux` 换成独立构建目录并关闭 sanitizer：

```powershell
cmake -S shared/src/main/jni/lwip_backend -B build/lwip-config-windows `
  -G "MinGW Makefiles" -DDIPLAY_SANITIZERS=OFF -DDIPLAY_FUZZ=OFF
cmake --build build/lwip-config-windows --target lwip_config_cost_report -j2
build/lwip-config-windows/lwip_config_cost_report.exe
```

工具校验 `configured_bytes` 与固定组成之和相等，创建配置上限的 32 个 UDP endpoint 和
16 个 TCP listener，核对端点数/内存统计，并在关闭后确认动态分配回到启动基线。
配置字节计算为 `MEM_SIZE + sizeof(dp_core) + 帧工作区 + 元数据预留 + 所有 MEMP pool
容量 + 当前 endpoint 分配`。lwIP heap 与 MEMP pool 按整池容量计入；TCP 收发 pbuf
由这些池提供，不再重复加算逻辑队列限额。Android CI 的 Linux native job 在 CTest 后保存
`build/lwip-host-linux/config-cost-report.json` 并上传；本工作树已运行相同生成命令并校验 JSON，
这只验证本地流程，不代表 GitHub Actions 已执行。

## 本次宿主结果

2026-10-23，固定依赖 lwIP 2.2.1；两次均为 64 位宿主。Linux 使用 WSL1 Ubuntu 20.04、
GCC 9.4.0、CMake 3.16.3 和 ASan/UBSan；Windows 使用 MinGW-w64 GCC 8.1.0、CMake 3.15.3。

| 宿主 | MEMP 静态池容量 | 启动配置容量 | 32 UDP + 16 listener 后端点分配 | 测试状态配置容量 | 运行时 heap_used / peak |
| --- | ---: | ---: | ---: | ---: | ---: |
| Linux x86_64 | 490,208 B | 1,608,128 B | 528,256 B | 2,136,384 B | 528,256 / 528,256 B |
| Windows x86_64 | 434,488 B | 1,552,408 B | 528,256 B | 2,080,664 B | 528,256 / 528,256 B |

两种宿主当前测试状态都低于 16 MiB core 预算参考值。MEMP 池容量差异来自宿主编译器/ABI
布局，因此不能从一个宿主数值推导 Android ARM 数值。`heap_used` 是 lwIP heap 使用量加
endpoint 动态分配，不是进程 RSS/PSS；`configured_bytes` 是容量记账，不是实际驻留内存。

## 适用范围

这是 core 配置容量检查，不是完整后端或设备验收。它创建了 UDP 与 listener 上限，未建立
活动 TCP 流；虽然 TCP PCB、segment、pbuf 与 lwIP heap 容量均进入配置总数，动态 TCP stream
记录没有在此场景分配。工具也不覆盖 Kotlin/ART、DirectByteBuffer、runner/NCM 队列、线程栈、
USB、进程级 PSS、ARM ABI 或实际 iPhone 流量。G2 的 16 MiB 总后端资源、性能与关闭预检仍未
通过，必须在目标 ARM 设备补齐配置和完整会话分配证据。吞吐预检见 [宿主微基准](host-benchmark.md)。

## 与 16 MiB 初始预算的代码映射

`performance.md` 的 16 MiB 是后端专属受控内存上限，分类值是准入上限而非可以直接相加的独立
分配。以下对照用于检查实现没有放宽既定预算；它不是完整后端峰值或 G2 通过证明。

| 组成 | 当前源码上限或口径 | 预算计数方式与证据边界 |
| --- | --- | --- |
| lwIP heap、MEMP pools、core、帧工作区、元数据及 endpoint 分配 | 本报告 Linux 配置满 UDP 与 listener 后 2,136,384 B；Windows 为 2,080,664 B | `configured_bytes` 将固定 heap/pools 和报告场景已分配 endpoint 计一次；UDP pool 及其 endpoint 元数据在此数中。 |
| 活动 TCP | 32 个 PCB；应用接收累计上限 4 MiB、发送累计上限 2 MiB | 4/2 MiB 是逻辑缓冲准入上限，不是另行预分配的数组；pbuf、segment 和 lwIP heap 的容量已经列入 native core 配置。此报告没有活动 TCP stream，实际 TCP 分配峰值仍待测。 |
| UDP 接收 | 全局 512 KiB，包含 payload blocks、队列元数据和 UDP endpoint 记录 | `udp_endpoint.c` 的编译期断言约束总量；最大端点报告计入其运行时分配，不应再把 512 KiB 重复加到报告值。 |
| native prototype wrapper | `struct prototype` 含 128 个 1,514-byte TX frame、copy scratch 与熵缓冲；编译期限制 ≤ 256 KiB | 该 wrapper 不在本报告创建的 `dp_core` 中，完整原生 prototype 预算必须另外计入；见 [prototype runner](prototype-runner.md)。 |
| Kotlin runner frame queues | RX ≤ 128 KiB、TX ≤ 192 KiB，各 ≤ 128 帧 | 有界队列保存 frame 数组；这是原型 runner 的额外 Kotlin 分配，未进入 native 报告。 |
| DirectByteBuffer pool 与待处理命令 | 每 session direct pool ≤ 256 KiB；命令队列最多 128 项、保留 payload ≤ 256 KiB | 命令的 retained-byte 记账可能指向 pool 中同一 buffer，不能把两个限额机械相加；要按 buffer 实际归属统计一次。 |
| NCM 原型 I/O | receive queue ≤ 64 KiB、256 帧；read staging 为 32 KiB heap + 32 KiB direct buffer | `NcmFramePort` 在启动 I/O worker 前收紧 bridge 队列。NCM parser 的扩容缓冲和对象开销不在 native 报告中，仍需完整会话测峰值。 |

TCP receive pbuf、lwIP send data 与 native queue 的容量存在池化关系；命令队列是 payload 保留上限；NCM
帧被移交给 runner 后会释放 bridge queue slot。以上关系决定了容量上限不能直接求和，也不能因此排除
任何分配。完整实现需要以 buffer 所有权和池容量统一计数，涵盖 wrapper、Kotlin/ART、NCM、线程栈及
JNI 临时对象；Android ARM 的完整会话峰值和进程 PSS 仍须在目标设备测量。
