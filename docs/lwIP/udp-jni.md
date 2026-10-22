# P3 UDP JNI桥

本批把native UDP连接到现有核心线程上下文，尚未实现公共Kotlin `UdpEndpoint`、
阻塞/deadline适配或会话选择。JNI ABI升级为2，Kotlin加载时要求版本恰为2；
native-only核心C ABI仍为1。旧JNI库与新Kotlin不允许混用。

所有UDP方法带上下文token和端点token。上下文查找验证创建线程/代数后，才访问
native核心或复制区；端点查找再次验证UDP类型和该核心注册表。Java不接收指针。
关闭上下文会经注册表回收仍打开的UDP资源，旧代数不能访问新上下文。

- udpBind使用调用者的两项LongArray返回端点token和实际端口。结果形状及端口先校验；
  Java发布结果出错时用native close撤销端点，不在异常挂起时继续访问Java对象。
- udpSend校验16字节目标地址、端口和payload范围，用减法检查offset/length避免溢出。
  仅通过GetByteArrayRegion复制，随后调用已有有界UDP实现；不保留Java数组地址。
- udpReceive在消费数据报前校验target/source/metadata形状，拒绝target与source引用
  同一数组。返回source地址和三项IntArray：[源端口，原始长度，复制长度]。
  零长度与WOULD_BLOCK保持区别；过小target报告截断并消费整个数据报。
- 每个JNI操作后检查异常，出错即停止后续JNI访问。不使用Critical数组、全局引用、
  native Java回调或线程attach。receive在消费后出现Java发布异常时不重放数据报。
- payload暂存使用上下文拥有的65527字节复制区。既无每次操作的native堆分配，
  也不增加约64KiB的JNI栈数组；连同原型帧队列/熵缓存的上下文总大小静态限制≤256KiB。
  复制区仅在当前JNI调用内借用，创建线程串行调用；不作为DirectByteBuffer租约暴露。

新增设备用例先在旧库因缺udpBind实现失败；桥接后通过，再以ABI2断言对旧返回1
建立失败证据，随后同步加载检查与native返回值。真实Ethernet帧经JNI输入UDP端点，
覆盖数组修改后的拥有型数据、来源、截断、空包、子范围发送及返回帧，校验错误数组
不会消费已排队包。还覆盖同一输出数组拒绝、offset溢出、负长度、错误线程、错误
类型token、重复关闭、槽位复用、上下文重建和旧上下文拒绝。

这些结果不是公共两后端合同、并发取消队列、DirectByteBuffer、OOM注入、实际
NCM/iPhone或ARM性能验收。后续核心命令桥必须在操作前推进时间和补充熵，并在正确
的核心线程调用；本原始JNI接口不自行产生阻塞读写语义。
