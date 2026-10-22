# P3 TCP宿主核心接口

`LwipTcpCore`把[原始TCP桥](tcp-jni.md)的数组和状态码转换为宿主命令可以调用的
非阻塞接口，`NativePrototypeCore`同时实现UDP/TCP核心接口。全部调用仍在创建线程，
不自行等待、启动线程或回调Java；已有[显式公共TCP主动连接适配](tcp-adapter.md)与
[监听适配](tcp-listener-adapter.md)，tcpLocalAddress向绑定工厂提供防御复制的本地IPv6，
生产SessionNetwork与后端选择尚未接入。

| 原始结果 | 宿主接口语义 |
| --- | --- |
| listen成功 | Listener(token, actual port) |
| accept成功 | Accepted(token, Peer)，含实际双方IPv6和端口 |
| connect成功 | Ready(candidate token)，不是握手完成 |
| connectStatus成功 | Ready(Peer)，之后可传给公共端点适配层 |
| WOULD_BLOCK | Pending，由runner在实际进度后重试；不在核心内轮询 |
| read成功 | Ready(实际长度)，允许短读和零长度 |
| read的PEER_CLOSED | Ready(-1)，缓冲数据先读完再出现EOF |
| write成功 | Ready(已接受前缀长度)，不是整段写入承诺 |
| write/shutdown的PEER_CLOSED | BackendFailure.PEER_CLOSED |
| PEER_RESET | BackendFailure.PEER_RESET |
| 资源上限错误(-2) | BackendFailure.RESOURCE_LIMIT |
| 致命输出错误 | BackendFailure.USB_IO |

参数范围在JNI前用减法校验，避免offset+length溢出。每次调用的地址、端口、计数数组
不跨命令保留；返回的Peer地址数组由调用方拥有。读写计数必须在请求和65527字节
复制区范围内，Pending和EOF不得同时报告消费。写入错误可能已经接受前缀，调用方
不能将失败当作零进度重发；公共全量write的循环、deadline和取消由显式适配实现。

创建listener、接受stream或创建candidate后，若Kotlin元数据构造/返回包装失败，
调用原始close回收尚未交付的token；OOM由既有nativeCall转换为RESOURCE_LIMIT。
这些回收分支尚无Kotlin分配故障的动态注入，不将正常用例视为其全部运行时证据。

普通操作先补充单调时间及熵；TCP abort/优雅关闭/listener close只检查创建线程和context存活，不调用
supply。原型或时间已经失败时，重复supply会阻止释放资源；原始C桥负责失败后的
停输出清理。context close仍是最终所有权屏障。tcpClose为abort；新增tcpCloseGraceful
把关闭交给协议栈，详见[优雅关闭原语](tcp-graceful-close.md)。公共TcpStream的
幂等/configure/deadline/liveness策略已由显式适配实现，完整两后端合同尚未验收。

Android模拟器用例通过完整IPv6/TCP帧驱动实际JNI和lwIP，覆盖未就绪connect、空
accept、双向地址/端口、读取缓冲后EOF、零长度读、关闭输出后的错误、主动候选与
握手查询、100000字节请求短写32768、发送队列满、RST及失败context释放。
这不替代公共并发/取消/deadline合同、真实NCM/iPhone、ARM和性能门槛；G2/G3仍未通过。
