# P3 native TCP监听与接收

`include/diplay_tcp.h`提供核心线程上的非阻塞listen、accept、read、write、输出半关闭
和本地abort关闭，亦支持下面的非阻塞主动连接。这是一段TCP端点实现：尚无完整优雅关闭策略、
完整两后端合同，不能作为完整TCP合同验收。
原始[TCP JNI](tcp-jni.md)已接入，当前JNI ABI 6；native-only核心C ABI仍为1。
已有[优雅关闭原语](tcp-graceful-close.md)及[显式公共TCP主动连接适配](tcp-adapter.md)。
另有[公共监听原型](tcp-listener-adapter.md)，接受流复用相同的公共读写/关闭实现。
已有[显式会话适配](session-network-adapter.md)，包括原生reuseAddress及echo隔离。
现有生产后端选择未改变，新增C API及错误值是兼容的扩展。

监听绑定虚拟netif具体IPv6地址，支持动态端口；可以在链路未就绪和DAD前准备。
应用监听记录最多16个，PCB池还会独立限制分配；每个backlog为1–32，连接PCB总量32。
未领取连接属于listener，直到accept成功登记generation handle后才独立归应用所有。
accept报告真实本地/远端IPv6和端口，不暴露native指针，不使用内核scope索引。
注册失败不移出backlog，raw回调不递归调用端点注册表。

raw接收将pbuf所有权移交给stream，不立即tcp_recved，不预取到无限应用缓存。
每个链累计不超过TCP_WND（当前65535）；32个窗口的总量编译期限制在4MiB应用RX
负载预算内，接收回调另有累计计数检查。raw栈的固定pbuf池、heap、乱序/refused
状态仍由原有有界配置约束，不能从整个16MiB预算排除。listener队列和stream记录
为有界元数据，libc分配加入endpoint_allocated/peak以及既有统计。

read只复制请求范围，用pbuf_free_header归还已消费前缀，然后只对这些字节调用
tcp_recved。poll、计时器及普通接收不会恢复应用窗口。零容量读取返回0；非零读取
在无数据时返回WOULD_BLOCK。FIN后的缓存先读完，再PEER_CLOSED；RST后的已接管
缓存同样可读，之后PEER_RESET，不将reset伪装成EOF。PCB已由lwIP释放时不再调用它。

本地关闭先失效handle、去除回调再abort；此处关闭原语用于即时取消和资源回收，
仍由应用持有的连接适用；已进入TIME_WAIT的协议状态见下面的所有权说明。
不实现公共TcpOptions默认的优雅关闭语义。关闭listener会清理未领取的已连接子项及
SYN_RCVD半连接，已被应用领取的stream继续独立存在。未领取连接遇到RST立即移出
backlog并释放其记录/pbuf。全core销毁后断言应用TCP计数和接管字节归零。

宿主真实Ethernet帧测试：

- tcp_endpoint：握手、地址/端口、分段读取、零容量、FIN、RST、独立关闭和未领取数据。
- tcp_backpressure：填满65535字节窗口，核对实际ACK窗口0；注入时钟前进10秒，再读取
  并核对窗口开放、字节完整/顺序及后续WOULD_BLOCK。这是确定性虚拟时间，不是ARM性能数据。
- tcp_endpoint_limits：未就绪监听、16监听耗尽、32连接与额外SYN、类型误用、generation
  复用、SYN_RCVD关闭。资源耗尽后原32条连接仍可读取/关闭。
- tcp_endpoint_lifecycle：8轮新core，旧handle拒绝、未领取RST回收、RST前缓存、backlog
  释放与listener关闭后已领取stream独立存活；不是10k次长稳验收。

以上四项也在Android API28 x86_64独立native进程运行，不依赖root或USB。
完整Linux ASan/UBSan/pool guards及Windows套件均包含这些场景。
它们不是独立Linux内核peer对新TCP API的验证，也未覆盖全部窗口缩放/乱序矩阵、
真实iPhone/NCM/ARM及公共并发/deadline合同；G2/G3仍未通过。

write最多复制当前tcp_sndbuf允许的前缀，采用TCP_WRITE_FLAG_COPY；调用返回后不再
借用原始payload。可写连接的0字节写入返回0，满缓冲或分配压力返回WOULD_BLOCK且未接收字节。
一旦复制成功，暂时输出失败不再改报可重试的零字节写入；协议栈拥有这些字节并
负责重传，避免上层重复提交。致命输出失败仍返回LINK_IO，此时copied可能非零，
与“失败写入可能已发送前缀”的公共合同一致。32个TCP_SND_BUF之和编译期限制在
2MiB发送负载预算内；固定池、heap和段元数据仍在原有总预算中，未新增大块TX缓存。

shutdown_output只关闭发送方向，记录成功意图后幂等；禁止后续write，仍可接收。
lwIP在TCP段池不足时可以延后FIN，接口不把它误报成尚未接受的重复关闭。
ERR_CLSD是本地半关闭完成后的正常结束，映射EOF而不是reset。RST仍独立报告RESET。

TIME_WAIT由协议栈在计时器/分配器中释放或复用，不保证err回调。每个core维护最多
32个应用stream记录的有界链表，在raw操作返回的dp_leave边界，去掉TIME_WAIT PCB
上的应用回调并清空应用记录的PCB引用；保留协议TIME_WAIT以正常确认重复FIN。
应用的接管pbuf和generation记录独立保留到读取/关闭，不能把已经退休的PCB留给
后续read/close。LAST_ACK完成由ERR_CLSD回调清空引用；销毁检查记录链表也归零。

新增用例：tcp_write以40320字节验证短写、满缓冲、ACK恢复、完整顺序和调用后修改
原数组；tcp_half_close覆盖本地先关、对端先关、同时FIN和RST，TIME_WAIT内重发FIN
仍得到ACK，注入121秒后创建新连接，再读旧缓存和关闭旧handle，不影响新连接。
tcp_write_io区分未就绪、已复制后的输出暂不可用与致命失败；tcp_shutdown_pressure
用固定TCP段池耗尽验证延后FIN、资源恢复，以及压力期间取消。均不耗尽物理内存。

主动连接由connect begin和connect_status两步组成。begin要求链路就绪且DAD已完成，
否则WOULD_BLOCK且handle为0，不保留候选。成功begin返回拥有型generation token，
表示SYN已入栈；并不表示握手成功。之后必须查询同一token，不能反复begin。
connect_status在握手中返回WOULD_BLOCK，成功后报告实际本地临时端口、真实远端
IPv6/端口。对端紧接着正常FIN不抹掉已完成事件，接管的数据仍可读取；RST或协议
重试耗尽报告PEER_RESET。后者不是公共deadline的TIMEOUT，deadline仍由后续适配层处理。

候选与已领取stream共用32个应用记录上限和固定TCP PCB池，使用同一列表/回调/
接收缓存/关闭路径。候选未完成时write和shutdown_output返回WOULD_BLOCK，不提前
排入应用数据。创建失败、SYN分配失败、致命输出错误都回滚注册和PCB，不发布token；
暂时输出不可用则保留同一候选，由TCP重传。close立即取消候选，全core销毁也会回收。
仅接受非零端口的IPv6单播目标，绑定虚拟netif并让栈分配本地临时端口。

tcp_connect验证就绪/DAD、握手、双方收发、实际地址端口和紧接的FIN；
tcp_connect_cancel验证拒绝RST、取消后的迟到SYN-ACK不完成新候选、generation及
上下文销毁。tcp_connect_limits覆盖参数、SYN固定池分配失败回滚、32候选容量和
致命输出回滚。tcp_connect_retry覆盖输出恢复后的原SYN重传及注入时间下的协议
握手超时。四项同时在Android x86_64原生进程执行，不是完整公共connect取消竞态验收。
