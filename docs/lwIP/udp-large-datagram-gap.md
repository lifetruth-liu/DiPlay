# UDP 最大负载边界：复现与修复

基于提交 `994298b3` 的工作区增加公共设备测试向量后，发现两后端发送上限不一致。
下面保留初始失败证据；发送及重组路径随后修复，最终验证见末节。G3与整体安全/
真机门槛仍未通过。

## 初始失败证据

自建 API28 x86_64 AVD，测试类 `SessionNetworkDeviceContractTest`。两后端共同向量：

| 方向 | 向量 | 结果 |
| --- | --- | --- |
| 接收 | 8192、65527 字节，完整缓冲 | 两后端通过，内容、来源与原始长度正确 |
| 接收 | 65527 字节，17 字节缓冲，随后另一个报文 | 两后端通过，截断及下一报文边界正确 |
| 发送 | 8192、65487 字节 | 两后端通过，JNI 输出重组后的负载与 UDP 校验和正确 |
| 发送 | 65488 字节 | 系统通过；JNI 返回 RESOURCE_LIMIT，测试失败 |
| 发送 | 65527 字节 | 系统通过；JNI 首轮 RESOURCE_LIMIT，边界诊断在前一向量失败而未再次执行此项 |
| 发送 | 65528 字节 | 原有参数拒绝；本轮 JNI 测试在更早向量失败，未重新覆盖此断言 |

JNI 接收夹具在 MTU1500 内生成 IPv6 分片，逆序交付、首片最后；同一数据报共用 ID。
输出解码器检查 IPv6 地址、MTU、分片 ID、偏移、非重叠、非末片八字节对齐、完整
覆盖及 UDP 长度/端口/负载/校验和。测试不调用原生端点 API 或替换真实 core。

日志位于本机 TEMP：`diplay-udp-fragments-connected.log`、
`diplay-udp-fragments-boundary.log`。后一轮36项中35通过、1失败，无错误/跳过，
Gradle exit1、47s。测试 APK SHA-256：
`b4fec304ee87252874a6dd9860a324cfca5b413acbb59bf998d8d7bba4640839`。

## 定位

`udp_endpoint.c` 的 `dp_udp_send` 先创建 payload pbuf，再由 lwIP 添加 UDP 和 IPv6
头。`pbuf.tot_len` 为16位。`pbuf_add_header_impl` 明确拒绝总长度回绕：

```
65487 + UDP(8) + IPv6(40) = 65535
65488 + UDP(8) + IPv6(40) = 65536
```

后者在 IPv6 层返回 ERR_BUF，当前映射为 DP_RESOURCE_LIMIT。它不是本次队列满或
提高 MEM_SIZE 可以解决的问题。入参65527是 IPv6 UDP 的协议负载上界；当前实现
尚不能证明能发送该范围内的每个长度。

## 后续约束

- 保留当前失败回归；不能删除 pbuf 的回绕保护、增加重试或盲目扩大堆来使测试变绿。
- 不能把公共上限直接改成65487并宣称原有边界已经实现。
- 若支持完整65527发送，需在单个完整 IPv6 pbuf 超过16位之前分片；每片仍须经过
  正常 IPv6 路由、邻居发现、MTU与有界输出路径，不能直接绕过 netif 发以太帧。
- 分片 ID 必须与同一源/目的地址的既有 lwIP 分片共享一致的生成规则，避免独立计数
  器冲突；新增上游源码改动需要单独记录来源、摘要与本地补丁身份。
- 先补原生边界回归和分配/部分输出失败检查，再跑真实 JNI 与独立内核 peer；所有权、
  空包、校验和、失败后的可用性及固定内存预算必须保持。

以上约束用于修复，不将扩大堆、关闭溢出保护或缩小公共上限当作解决方案。

## 修复与最终证据

发送侧复用既有分片器；大负载的IPv6基础头单独保存，共用同一个ID生成器及netif
输出，新路径传播部分输出失败。只对当前port的启用分片/禁用loopback配置启用；
其他配置维持原有行为。未增加堆、池或队列容量。

独立内核peer进一步触发顺序65488字节输入的重组断言，说明逆序夹具成功不够。
重组修复先移除各片元数据再拼接，避免中间总长度回绕；大负载的基础头保留于首片
headroom，IPv6输入分开使用基础头与负载。扩展头调整后超过65535的非法长度仍拒绝。
新声明仅供lwIP内部使用；旧重组入口无法返回单独头时安全丢弃大结果，公共C/JNI
ABI不变。补丁来源与三个精确post-image见原生BACKPORTS.md，未将其标为上游提交。

最终证据：

- 真实Android JNI36/36：TEMP/diplay-udp-large-reass-connected.log。
- Windows MinGW50/50；原生分片案例含32768/65487/65488/65527，顺序/乱序输入及
  第0/1/45片输出失败后的错误与资源清理：TEMP/diplay-udp-large-reass-final-host.log。
- Linux宿主ASan/UBSan及默认LSan50/50：/tmp/diplay-udp-large-reass-final-sanitize.log。
- Android ASan/UBSan独立内核peer的TAP/veth endpoints及旧echo均通过，UDP向量为
  0/1/1200/4096/8192/65487/65488/65527。TEMP/diplay-udp-large-kernel-tap-fixed.log、
  diplay-udp-large-kernel-veth.log、diplay-udp-large-kernel-legacy.log。
- Android明确detect_leaks=0，不宣称LSan扫描。首次顺序重组断言保留于
  TEMP/diplay-udp-large-kernel-tap.log。
- 完整九项CI、APK四ABI/16KiB/NX/源清单与许可证检查通过；整体执行记录见
  execution-status.md。真机、完整分配故障/扩展头矩阵及独立安全评审仍缺。
