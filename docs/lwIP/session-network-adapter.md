# P3 显式lwIP会话适配（JNI ABI 6）

`LwipSessionNetwork`实现公共SessionNetwork，组合已有TCP监听器、主动/接受流和UDP
适配。它是显式工厂入口，未接入生产后端选择或CarPlay通道；G2/G3仍未通过。

会话持有唯一linkId及runner的单调时钟。listen/connect/bindUdp先验证IPv6和linkId，
禁止把另一会话的端点交给本会话；端点仍携带实际地址/端口，不使用系统scope index。
每个端点及候选的generation、队列预算、方向排他和释放控制槽沿用已有实现。

start接管传入runner并等待启动；启动失败关闭runner、保留结构化错误。native工厂
先校验/防御复制MAC、IPv6和MTU，再构造runner，在其创建线程生成NativePrototypeCore。
该核心以echoServices=false准备，只补时钟/熵，不调用prepareEcho，避免占用原型的
TCP7000/UDP7001及额外PCB。原型nativeEcho和既有create默认仍启用echo服务。

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

尚未完成全部两后端参数化合同、所有分配/引用/竞态故障矩阵、独立kernel peer对新
端点API的验证、真实NCM/iPhone/ARM及性能长稳验收。此层完成接口组合，不代表全部
会话行为、业务集成或G2/G3已经验收。
