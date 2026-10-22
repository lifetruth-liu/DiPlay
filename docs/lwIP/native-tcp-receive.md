# P3 native TCP监听与接收

`include/diplay_tcp.h`提供核心线程上的非阻塞listen、accept、read和本地abort关闭。
这是一段TCP端点实现：尚无write、主动connect、shutdownOutput、优雅关闭策略、
TCP JNI或公共TcpStream/SessionNetwork适配，不能作为完整TCP合同验收。
现有生产后端与Java/JNI ABI未改变；新增C API及错误值是兼容的扩展。

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
真实iPhone/NCM/ARM、公共并发/deadline合同及写入背压；G2/G3仍未通过。
