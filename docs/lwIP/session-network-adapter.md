# P3 显式lwIP会话适配（JNI ABI 6）

`LwipSessionNetwork`实现公共SessionNetwork，组合已有TCP监听器、主动/接受流和UDP
适配。它是显式工厂入口，未接入生产后端选择或CarPlay通道；G2/G3仍未通过。

会话持有唯一linkId及runner的单调时钟。listen/connect/bindUdp先验证IPv6和linkId，
禁止把另一会话的端点交给本会话；端点仍携带实际地址/端口，不使用系统scope index。
每个端点及候选的generation、队列预算、方向排他和释放控制槽沿用已有实现。

start接管传入runner并等待启动；启动失败关闭runner、保留结构化错误。native工厂
先校验/防御复制MAC、IPv6和MTU，再构造runner，在其创建线程生成NativePrototypeCore。
该核心以echoServices=false准备，只补时钟/熵，不调用prepareEcho，避免占用原型的
TCP7000/UDP6000及额外PCB。原型nativeEcho和既有create默认仍启用echo服务。

close先禁止新建端点，再关闭runner。队列取消所有等待者和候选，context销毁屏障
清理所有已登记端点及协议关闭项；不从业务线程调用native释放。重复close可继续
之前的有界join，核心仍只由owner销毁一次。端点对象在会话关闭后再操作会收到取消，
随后单独close也不会再次访问已销毁context。

## reuseAddress

listen的reuseAddress已透传到原生绑定前的SOF_REUSEADDR。编译开启SO_REUSE，旧
dp_tcp_listen仍默认false，新增dp_tcp_listen_with_options支持显式0/1选项；旧原始
JNI方法也保留默认false。新增tcpListenWithOptions及prototype转发，整体JNI ABI
升级6，加载器同步要求6；native-only核心C ABI仍为1。

仍有已接受子连接时，原生用例验证旧/新监听均启用复用才允许重绑同一本地端口；
旧监听启用复用且进入TIME_WAIT后，新绑定按复用开关允许/拒绝。两个活动监听器
不能占用同一地址/端口，不提供SO_REUSEPORT。tcp_listen转换阶段的ERR_USE也映射
ADDRESS_IN_USE/公共ADDRESS_CONFLICT，失败的绑定PCB和元数据被回收。
同一选项由lwIP继承到子PCB；没有绕过协议栈的端口冲突检查。

新增JNI仍在创建线程调用，先验证port/backlog和结果数组形状，发布异常时用纯C
回收新listener，不在挂起Java异常后继续访问数组。现有绑定上限/内存预算不扩大。

## 证据及限制

JVM用例覆盖时钟/linkId、跨会话/IPv4拒绝、reuse选项、启动失败后的port关闭、
accept/connect/read/write/UDP send/receive六类并发等待取消、端点/候选归零和只销毁
一次上下文。原生复用用例用实际TCP帧及状态覆盖存活子连接、TIME_WAIT、重复活动
监听、复用后新旧连接分流和非法选项；既有UDP/原始TCP用例同时回归。
Android显式native会话可绑定原型echo端口，验证服务隔离、真实JNI选项、地址冲突及
会话关闭唤醒等待者；既有完整TCP/UDP帧路径继续回归。

SessionNetworkDeviceContractTest在Android上对系统socket和真实JNI会话运行相同的
四项参数化合同：临时监听端口分配/冲突/关闭重绑，跨link拒绝及非法/到期deadline，
会话关闭取消accept/UDP receive并拒绝新端点。lwIP使用断开的EthernetFramePort，
不伪造JNI或core，但这组用例没有网络peer。并发用例覆盖关闭与调用起始的竞争，
不声称精确证明每个等待者关闭前已进入原生阻塞状态。第四项UDP共同断言使用独立
夹具：系统侧IPv6内核socket，JNI侧确定性以太帧peer。接收覆盖0/1/1200字节、
短缓冲及零容量缓冲、源地址/端口、原始/复制长度、缓冲未写部分及截断后下一报文；
发送覆盖0/1/1200字节及65528字节拒绝。JNI发送到IPv6 multicast以避免依赖NDP，
捕获真实输出帧验证端口、IPv6地址、负载及UDP校验和；不将此夹具当作独立kernel互通。

独立kernel peer对原生端点API的验证见kernel-endpoint-peer.md。尚未完成全部两后端
参数化合同（尤其共同TCP背压/写阻塞取消、UDP分片/上限），所有分配/引用/竞态故障矩阵、
真实NCM/iPhone/ARM及性能长稳验收。此层完成接口组合，不代表全部
会话行为、业务集成或G2/G3已经验收。

TcpSessionDeviceContractTest补充两后端共同TCP字节/半关闭合同：65537字节固定种子
内容，1/32769/32767字节写调用、非零offset、1字节及随机容量read、完整字节及
SHA-256核对、shutdownOutput后继续读取响应、重复EOF。系统侧为IPv6内核socket
peer；JNI侧为有界Ethernet peer，真实握手后收集数据，收到FIN才分段回送并FIN。
回送为逐段确认，不测试丢包/重传、窗口压力或并发读写；不替代原生独立内核peer。
最终设备24/24通过；首轮JNI连接出现一次3秒deadline超时，诊断/复跑及最终版均通过，
尚未确认首次超时根因，不将这几次通过视为长稳证明。

后续共同TCP合同增加：静默peer下100ms read超时、目标缓冲保持不变、超时后读线程
与4097字节write/shutdownOutput并行并读完回显；本地close取消正在执行的read、重复
close及关闭后read拒绝。通过有界轮询线程栈确认后端read已进入再执行write/close，
peer收到FIN才回复，避免只用线程启动信号充当读操作已执行的证据。另对两个后端各
连续12次创建会话、握手和空半关闭。最终设备30/30，但诊断期间大数据用例的3秒握手
超时又出现一次；带有界wire轨迹的两轮随后通过，根因仍未定位，不作稳定性结论。

后续RST共同合同让peer收到首字节后发送真实重置，断言PEER_RESET及读取缓冲不变。
系统后端原来将Android NIO的普通IOException("Connection reset by peer")归为INTERNAL，
现按Android errno原因链或标准reset文本分类，同时保留本地关闭CANCELLED优先级；
未知错误仍为INTERNAL。JVM真实NIO/adopted socket也有远端SO_LINGER(0)回归。

本轮握手失败轨迹确认无TCP SYN输出，只有邻居发现等控制帧；旧wire peer只在启动
时一次注入NS，忽略运行时NDP请求。peer改为对目标为peerIp的NS发送带正确hop limit/
target/option/checksum的NA，并移除一次性预热注入。两轮诊断及最终32项中JNI握手
均通过；此修正补齐夹具真实协议缺口，不宣称有限复跑已证明所有历史超时根因或长稳。
