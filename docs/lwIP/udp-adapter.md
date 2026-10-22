# P3 UDP公共合同适配

`LwipUdpEndpoint`实现现有`UdpEndpoint`，通过显式`LwipPrototype`绑定；尚未提供
完整`LwipSessionNetwork`，也不改变生产后端选择。`LwipUdpCore`是仅限核心线程的
非阻塞接口，`NativePrototypeCore`将其转发给既有UDP JNI（当前整体ABI 5），不新增C接口。

bind在登记资源前检查运行状态和调用线程；IPv6本地地址只能是虚拟netif实际地址
或全零通配地址。结果报告实际地址、实际分配端口和调用者的会话linkId，不使用
内核IPv6接口索引。端口冲突映射ADDRESS_CONFLICT，OOM映射RESOURCE_LIMIT。
原型echo监听器仍存在，统计中必须扣清其基线，不能把它误报为泄漏。

每个端点持有一个资源scope和generation token。token只在核心线程发布和消费；
关闭先取消等待者，再通过预留控制队列释放native端点，最多释放一次。绑定失败、
迟到绑定结果和构造失败同样请求释放。全会话停止时由上下文销毁屏障回收剩余
端点。close返回表示已请求关闭，不等待对端，也不从业务线程直接释放native对象。

每个端点允许一个发送者和一个接收者并行，同方向重叠立即RESOURCE_LIMIT。
发送在prepare准入后复制payload和目标IPv6地址，原数组在等待期间不再被核心
读取。目标必须属于相同linkId，端口非零；不接受IPv4。WOULD_BLOCK等待实际进展，
没有对已成功提交数据报的隐藏重发。原型端点支持长度0，不以0字节判断失败。

接收在prepare中分配容量不超过65527的私有数组、源地址和元数据；native不写
调用者目标数组。元数据核对来源端口、原始长度和复制长度，成功结果在调用线程
复制到目标数组。`callPreparedDeliver`在delivery结束后才归还配额，避免核心已经
提交而调用线程尚未完成复制时提前释放预算。delivery不持队列锁，不可阻塞或进行
native/I/O操作；已提交成功可跨越并发close交付，仍未提交的超时/取消不写目标。
delivery失败也在finally退休命令。普通准备入口使用同一交付状态机。

发送声明payload+16字节，接收声明capacity+44字节（source、三个int、复制后的
端点地址），绑定命令声明64字节。固定对象元数据由128命令/80资源登记限额约束；
这不是整个Java堆、GC驻留或16MiB总预算的测量证明。接收结果在交付期间仍占预算，
目标数组及交付后的ReceivedDatagram归调用者拥有。
`receiveBufferSize`返回0，表示没有可配置OS缓冲，不创建内核socket。

单元测试覆盖真实地址/端口、独立发送副本、截断、空包、零容量、同方向并发拒绝、
同时收发、关闭等待、超时复用、输入验证、失败绑定回收、未启动入口和注入OOM。
队列另测delivery持有预算、跨关闭交付和delivery异常后的回收。
Android测试从公共UDP合同经运行器/JNI到真实lwIP，以有效IPv6/NDP/UDP帧收发，
核对来源与截断、零包、实际输出端口/内容、关闭唤醒、native数量回到echo基线，
以及同端口重新绑定。此处“公共合同”不表示已有完整SessionNetwork工厂可供业务选用。

未验收：全部65527字节边界、媒体过期丢弃策略、完整两后端参数化T01–T08、TCP、
完整会话、实际NCM/iPhone/ARM和关闭耗时预算。G2/G3没有因此通过。
