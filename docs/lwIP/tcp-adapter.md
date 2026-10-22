# P3 公共TCP流适配（当前JNI ABI 6）

`LwipTcpStream`实现现有TcpStream合同，显式connect工厂接入LwipPrototype命令队列与
LwipTcpCore。已有[公共TCP监听原型](tcp-listener-adapter.md)，通过同一发布逻辑交付
接受流；已有[显式会话适配](session-network-adapter.md)，生产后端选择和业务通道尚未接入。
所有原生操作和资源释放仍在核心线程，业务调用方只等待有界命令，不直接操作PCB。

connect先预留resource scope，再在同一命令中创建至多一个候选并查询其握手状态。
未就绪时没有候选，握手等待时复用原token；保持整个调用的绝对deadline。完成后校验
实际双方IPv6/端口，将调用方linkId保留到NetworkEndpoint；地址对调用方防御复制。
候选超时、取消、元数据错误或交付失败均通过保留的控制槽回收，未发布候选使用abort。

每流允许一读一写并发，同方向重叠返回RESOURCE_LIMIT。shutdownOutput占用写方向，
不能插入正在进行的整段write中。范围用减法校验，避免整数溢出；空read返回0，peer
FIN在缓冲读完后返回-1，reset传播PEER_RESET。读取使用至多32768字节的队列副本，
只有结果成功提交后才在调用线程复制到目标子范围；超时/取消不修改目标。

如果native已经消费字节，结果却因超时/取消被丢弃，流会关闭，避免下一次read静默
跳过这些字节。native读取、结果构造或交付出错也保守关闭流；尚未执行/仍Pending
就超时或中断时保持流可用。关闭可携带原错误作为scope取消原因，防止RESOURCE_LIMIT/
PEER_RESET被清理操作覆盖成CANCELLED。队列保留运行中副本的预算直到核心真正退出。

write循环完成整个范围，或抛出错误；失败可能已经接受前缀，绝不自动重发未知前缀。
每条命令只复制至多32768字节，先取得队列预算再分配；原生短写后用同一绝对deadline
继续剩余范围，WOULD_BLOCK等真实进度再重试。大于256KiB的请求可分批完成，不需要
一次预留整个请求。与同步流写入一样，调用方必须在write返回前保持源范围不变；
只保证已入队那一块的所有权，不提供整个大请求在方法入口的原子快照。
完成/出错/取消后移除命令副本，不在命令预算之外另挂发送缓存。

close幂等并立即取消等待者，通过保留控制槽异步执行非阻塞释放，不需要普通命令槽。
已发布流默认调用优雅关闭原语，resetOnClose=true使用abort；configure通过owner队列
保存同一份策略。全runner停止依赖context销毁屏障清理，不从业务线程释放活PCB。
正常关闭不等待peer ACK，协议仍可在固定池内重传数据/FIN，详见[关闭原语](tcp-graceful-close.md)。

configureLiveness调用新原生接口，给该PCB启用10秒idle、3秒interval、3次keepalive。
健康空闲peer的ACK会恢复idle，不用视频/应用数据空闲作为断线依据；不访问系统FD。
不声称实现Linux TCP_USER_TIMEOUT，诊断明确采用协议重传deadline。整体JNI ABI升级5，
加载器与native同步要求5；native-only核心C ABI仍为1。

宿主fake core测试校验owner线程、实际地址、防御复制、300000字节整段短写与块上限、
单读单写、等待副本、超时/中断、候选取消、FIN/EOF、reset策略、资源归零、前缀后超时
不重放、已消费读取出错/迟到结果关闭，以及分配故障不终止runner。
原生用例用真实探测帧验证健康空闲连接持续存活、停止回应探测后复位。Android模拟器
从公共connect驱动真实SYN/SYN-ACK、JNI配置、偏移读/EOF、write和FIN关闭。

尚未完成完整两后端参数化合同、全部分配/异常/竞态矩阵、
独立kernel peer对新端点接口的验证、真实NCM/iPhone/ARM、性能和长稳验收。
这些测试不能替代G2/G3，也没有改变现有生产后端选择。
