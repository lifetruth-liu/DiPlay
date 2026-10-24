# P3 公共TCP监听适配

`LwipTcpListener`实现现有TcpListener，显式bind工厂经runner调用原生listen/accept。
复用[公共TCP流](tcp-adapter.md)的读写、deadline、配置和关闭实现。已有
[显式SessionNetwork](session-network-adapter.md)，生产后端选择仍未接入。当前JNI
ABI为8，核心C ABI仍为1；reuseAddress由新绑定选项在原生bind前设置。

bind仅接受虚拟netif的具体IPv6或全零通配地址，向调用方报告具体地址和实际端口，
保留linkId。动态端口必须非零，显式端口必须与返回值一致；不静默改绑其他端口。
backlog为正数，公共默认50按原生固定上限截到32；该提示不意味着无界连接队列。
监听器在native bind前预留resource scope，失败/元数据错误/交付失败均回收资源。

每监听器最多一个未完成accept，重叠调用返回RESOURCE_LIMIT。每次accept先预留
子流的独立resource scope，再提交以监听器scope为取消范围的命令；槽位耗尽时
不能调用native accept或消费待领取连接。原生返回stream token后立即登记到子流
lease，再校验双方地址/端口，最后交付公共TcpStream。接受/主动连接共用同一套
发布、配置和释放策略，避免出现两种不同的流生命周期。

等待accept的命令只在实际进度后重试，保持同一绝对deadline。超时/中断/监听器关闭
取消当前调用、回收未发布子流scope，监听器在超时/中断后仍可用。如果native操作
在取消后才返回新stream，预留的owner仍在，晚到结果被丢弃并在核心线程abort，
不会因业务线程已经返回而漏掉新句柄。无效子流元数据和构造错误也走未发布abort。

close幂等，经保留控制槽关闭原始listener，取消其等待调用并由C层清理未领取子项。
已成功领取的stream有独立scope，关闭listener不关闭这些stream。已提交成功的
accept可以在并发close后交付；尚未提交的结果按取消路径回收。全runner停止仍依赖
context销毁屏障，不从业务线程释放活PCB。

12项宿主用例验证通配绑定、实际端口/地址、backlog限制、显式端口校验、同向排他、
等待及运行中accept的关闭/超时/中断、晚到子流回收、监听端口复用、槽位耗尽时不
消费连接、元数据/分配失败、监听器关闭后已接受流读写、各scope及句柄归零。
与原有TCP流/UDP/命令队列一起做定向回归。Android模拟器以完整SYN/SYN-ACK/ACK及
数据帧驱动公共bind/accept，验证动态端口和真实peer地址、关闭listener后子流继续
读写、默认FIN及scope归零。

尚未完成全部两后端参数化合同、全部
分配/异常/竞态矩阵、独立kernel peer对新端点接口的验证、真实NCM/iPhone/ARM及性能
长稳验收。不能将上述原型路径扩大为G2/G3或完整业务迁移通过。
