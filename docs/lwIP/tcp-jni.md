# P3 TCP JNI桥（当前ABI 6）

在既有context/UDP桥上新增TCP桥，整体JNI ABI升级到3，Kotlin加载器与native
abiVersion同步校验。后续新增[优雅关闭](tcp-graceful-close.md)将整体ABI升级4，
新增PCB keepalive配置后整体ABI升级5，后续新增绑定选项升级6，当前加载器与native均要求6。native-only核心C ABI仍为1。原始接口由核心创建线程串行调用，
不自行创建线程或提供阻塞/deadline语义；已有[类型化宿主核心接口](tcp-core.md)，
已有[显式公共TcpStream主动连接适配](tcp-adapter.md)、[监听适配](tcp-listener-adapter.md)
和[会话适配](session-network-adapter.md)，生产选择尚未接入。

| 方法 | 输出/语义 |
| --- | --- |
| tcpListen | LongArray(2)：listener token、实际端口 |
| tcpListenWithOptions | 相同结果，bind前设置reuseAddress；不允许重复活动监听 |
| tcpAccept | LongArray(1) stream token、两个独立ByteArray(16)地址、IntArray(2)端口 |
| tcpConnect | LongArray(1)候选token；成功表示SYN入栈，之后查询同一token |
| tcpConnectStatus | 握手完成后写两地址/两端口；等待或失败时不发布信息 |
| tcpRead/tcpWrite | offset/length子范围，IntArray(1)报告实际长度；WOULD_BLOCK为0 |
| tcpShutdownOutput | 非阻塞半关闭，不能据此假定对端已经收到FIN |
| tcpConfigureLiveness | 在对应PCB启用10s/3s/3次keepalive，不进入系统FD路径 |
| tcpClose/tcpListenerClose | 释放对应类型generation handle，原始重复关闭返回INVALID_ARGUMENT |
| tcpCloseGraceful | 消耗stream handle，把正常FIN关闭交给协议栈；不等待peer ACK |

所有输出数组形状、读写范围和地址别名在端点改变或数据消费前校验。local与remote
地址数组不能引用同一对象。范围用减法检查，防止offset+length溢出；拒绝负数和
越界，允许合法零长度。上下文generation与创建线程验证先于借用复制区。
只用Get/SetArrayRegion，不使用Critical数组、不保留Java数组地址、不分配全局引用、
不从native回调Java或attach线程。每次JNI访问后检查异常，异常挂起后停止后续访问。

共享既有65527字节context复制区，私有helper改名copy_buffer；UDP与TCP不可并行
调用它。较大的合法TCP范围会得到短读/短写，不因超过UDP最大数据报而拒绝TCP请求。
TCP写入随后由TCP_WRITE_FLAG_COPY接管；没有新增约64KiB的JNI栈数组或另一套大缓存。
prototype对象连同帧队列、熵和复制区仍受原有256KiB静态限制。

listen/accept/connect成功后若Java输出发布异常，关闭尚未成功交付的新handle，
cleanup只调用C，不在异常挂起时继续JNI。accept先发布元数据再发布token，失败时
回收新stream。读写已发生后发布异常不重放数据：read可能已经消费，write可能已
接受前缀。数量输出在native调用后发布，包括EOF/reset/WOULD_BLOCK的0和致命输出
错误时可能存在的非零前缀；校验阶段失败不改输出。调用方必须先解释返回状态。

已失败prototype上下文仍可关闭TCP资源；清理先停输出，避免RST输出被失败原型
拒绝后制造额外LINK_IO。核心本身已失败时的关闭同样禁止继续发送，保留原失败原因。
整体context销毁仍是最后所有权屏障，不从错误线程释放活对象。

Android用例先在缺tcpListen实现时真实失败，加入桥后验证ABI3、真实握手/收发/
FIN/RST、来源和临时端口、偏移/零长度、100000字节请求短写、scratch复用后重传
内容、无效输出不消费、地址别名、溢出/负范围、错误线程、错误类型、重复关闭、
槽位复用和新context拒绝旧token。首次全量模拟器回归还复现了失败context清理的
额外LINK_IO，修复后关闭成功。宿主prototype生命周期补上新转发接口的关闭/重建。

这些结果不包含Java输出异常的运行时注入、全部JNI边界/引用故障矩阵、公共TCP
并发/deadline契约或真实NCM/iPhone/ARM验收；不能将检查代码和正常CheckJNI通过
当作上述异常路径均已动态覆盖。G2/G3和完整业务集成仍未完成。
