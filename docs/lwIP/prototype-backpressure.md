# P2 原型背压预检

本记录覆盖原生虚拟 netif echo 与独立内核客户端；不是生产 `LwipTcpStream`
合同、USB/NCM 或 G2/G6 验收。设备为任务专用 API28 x86_64 Android AVD，
Linux4.4.124+、WHPX，两核/2GiB；无 iPhone、车机或 VPN A/B/A 基线。

## 场景与判据

`lwip_kernel_peer interface --slow` 在连接前请求4KiB收发 socket 缓冲，
fork 的发送者持续写1MiB，父进程读取首个成功send的就绪标记后暂停读取十秒。
暂停结束时发送者必须尚未退出；恢复后按绝对流位置生成的字节序列逐字节验证，
并检查发送者正常完成。失败路径回收发送子进程，客户端未链接 lwIP。
核心测试工具在 owner command 边界采样拒收长度、当时剩余发送容量和零窗口，
只在结束后聚合输出；采样数不是持续时间或频率指标。

## 发现与修复

旧 echo 要求一次 `tcp_write` 整个pbuf。慢读后 lwIP 重试的合并pbuf曾达到34272字节，
超过固定32768字节 `TCP_SND_BUF`，即使发送缓存完全排空也永远返回ERR_MEM。
两次失败分别只恢复504184、557620字节；不是按固定位置损坏。

修复保持缓冲配置不变：每次最多复制剩余可发送部分，callback_arg保留消费偏移；
只有tcp_write成功才推进偏移并tcp_recved对应字节。部分完成返回ERR_MEM，让lwIP
继续持有完整pbuf；最终清除偏移并释放pbuf。不额外申请每连接状态对象。
EOF仍abort，是明确的P2 echo限制，不能替代生产半关闭实现。

确定性原生回归先发送27个乱序1440字节段，再补首段空洞，形成40320字节回调。
对端只ACK实际收到的连续前缀，推进timer，验证所有字节、顺序及输出校验和。
旧实现运行失败，新实现在Linux ASan/UBSan +lwIP guards和Windows均通过。

## 结果与边界

| 模式/场景 | 结果 | max refused | sndbuf at max refused | 零窗口采样 | lwIP heap peak |
| --- | --- | ---: | ---: | ---: | ---: |
| TAP basic | TCP三次64KiB及UDP空包/分片通过 | 24124 | 2856 | 0 | 36008 |
| veth basic | TCP三次64KiB及UDP空包/分片通过 | 1428 | 0 | 0 | 36008 |
| TAP slow | 十秒暂停及1MiB完整恢复通过 | 31416 | 0 | 153 | 36024 |
| veth slow | 十秒暂停及1MiB完整恢复通过 | 5524 | 0 | 161 | 36008 |

heap peak只覆盖lwIP heap，不包括静态池、工具TX环、临时复制、socket、栈或Java；
不能据此通过16MiB动态内存或24MiB PSS预算。未测低配ARM吞吐、core延迟、
十分钟稳定负载、生产端点关闭取消或完整会话。

Android镜像虽有tc，但不支持netem；本批没有把该项标为通过。
后续已补[确定性出口故障实验](prototype-wire-faults.md)，仍缺双向随机故障、
UDP损失及实际NCM/ARM数据。
本回归覆盖N05相关原型机制，不把它等同于T05生产合同全部满足。

运行日志、命令和后续完整CI见[实施记录](execution-status.md)。
