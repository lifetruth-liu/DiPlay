# P3 TCP JNI桥（当前ABI 8）

在既有context/UDP桥上新增TCP桥，整体JNI ABI升级到3，Kotlin加载器与native
abiVersion同步校验。后续新增[优雅关闭](tcp-graceful-close.md)将整体ABI升级4，
新增PCB keepalive配置后整体ABI升级5，后续新增绑定选项升级6，UDP排队年龄接口升级7，
DirectByteBuffer读写接口升级8；当前加载器与native均要求8。native-only核心C ABI仍为1。原始接口由核心创建线程串行调用，
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
| tcpReadDirect/tcpWriteDirect | 使用DirectByteBuffer在JNI边界传输字节；仍报告实际短读/短写长度 |

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

ABI 8的公共TCP适配使用session-owned有界DirectByteBuffer池，单session容量上限256 KiB。
command未从core队列退休前，超时或取消不能归还buffer；context关闭后，活跃租约在退休时释放，
池中空闲buffer则立即回收。数组复制接口继续保留用于原型/兼容路径。

Android用例先在缺tcpListen实现时真实失败，加入桥后验证ABI3、真实握手/收发/
FIN/RST、来源和临时端口、偏移/零长度、100000字节请求短写、scratch复用后重传
内容、无效输出不消费、地址别名、溢出/负范围、错误线程、错误类型、重复关闭、
槽位复用和新context拒绝旧token。首次全量模拟器回归还复现了失败context清理的
额外LINK_IO，修复后关闭成功。宿主prototype生命周期补上新转发接口的关闭/重建。

fake-JNI夹具没有制造真实JVM数组分配OOM。引用与线程管理按当前JNI边界审计：桥接不保存
jobject、不调用数组Elements接口、不创建全局引用，也没有native到Java回调或native线程调用
JNI；输入对象引用仅在同步native方法期间借用，返回的统计数组交还VM管理。因此当前没有
需手动释放的局部/全局引用，也不需要AttachCurrentThread/DetachCurrentThread。若以后加入
Java回调、异步引用或Elements租约，必须新增明确所有权与线程生命周期测试。Android CheckJNI
正常路径、fake-JNI挂起异常及数组操作失败测试均已通过；它们不替代实际Java OOM注入、公共
TCP并发/deadline契约或真实NCM/iPhone/ARM验收。G2/G3和完整业务集成仍未完成。

宿主 `jni_output_exception_cleanup` 使用仅供测试的JNI函数表，在
`SetLongArrayRegion` 发布结果时注入挂起异常，覆盖TCP普通监听和带选项监听；验证
native返回 `DP_INTERNAL`、未交付listener已关闭、端点计数回零，清除注入异常后可
再次监听并正常关闭。该夹具还令 `GetByteArrayRegion` 在 `tcpConnect` 地址和 `tcpWrite`
payload 复制时挂起异常，验证异常原样保留、连接未创建或活动连接未输出新帧；清除
异常后，活动连接仍可成功写入。另在 `tcpRead` 的 `SetByteArrayRegion` 注入发布异常，
验证读取到的字节已经消费、计数数组未发布，重试返回 `WOULD_BLOCK` 而不重复数据，
异常挂起期间没有后续JNI访问。它直接编译实际JNI桥源文件，但使用fake JNI表，不是
JVM或CheckJNI证据；实际Java复制OOM、引用/线程故障矩阵仍待覆盖。

宿主回归还覆盖原型 JNI `create`、`supply`、`input`、`takeOutput` 和 `stats` 在入口已有
挂起异常时不再调用数组或对象 JNI，并在 `GetArrayLength` 本身抛出异常后停止访问。用例
通过fake JNI记录异常挂起期间的调用数；不替代真实JVM异常或CheckJNI验证。
另对 `stats` 的 `NewLongArray` 注入分配异常，验证其原样返回null、保持异常挂起且不再访问
JNI；清除故障后再次创建并发布统计数组成功。该注入不是真实JVM OOM。
