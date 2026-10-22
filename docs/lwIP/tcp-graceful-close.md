# P3 TCP优雅关闭原语（JNI ABI 4）

新增`dp_tcp_close_graceful`、prototype转发、`tcpCloseGraceful` JNI及类型化核心方法。
整体JNI ABI升至4，Kotlin加载检查同步；native-only核心C ABI仍为1。既有tcpClose
保留本地abort语义，用于取消、resetOnClose及未交付资源回滚。公共TcpStream尚未接入。

健康且已连接的stream先清除arg/recv/err/sent/poll回调，再调用lwIP tcp_close。
成功后PCB可能已经释放，也可能处于FIN_WAIT/LAST_ACK或TF_CLOSEPEND；应用不得再
访问该PCB。清空stream中的PCB指针，失效generation handle，释放应用元数据和
接收pbuf。协议栈继续拥有已复制的发送数据、重传和FIN，应用close不等待peer ACK。
这也避免旧handle或回调在PCB释放/复用后访问新连接。

FIN段池耗尽时，lwIP可返回成功并设置TF_CLOSEPEND，由定时器重试。PCB仍在固定
MEMP_TCP_PCB/TCP_SEG池内，不新增无界closing列表；没有应用回调、句柄或外部分配
记录遗留。协议ACK/超时回收协议资源，context销毁屏障也会清理所有active/TIME_WAIT/
bound PCB，包括已无应用handle的关闭项。成功不等于peer已收到全部字节或FIN。

存在尚未读取的数据时，tcp_close按lwIP语义发送RST并回收；不声称这一情况可以
无损丢弃数据后仍优雅关闭。未完成握手的candidate按取消路径abort。已复位/已无PCB
的stream只回收应用记录。失败core停输出后abort；失败prototype也走停输出abort，
不因再次补时钟/熵阻止清理。健康但输出暂时不可用时保留协议重传。

除无效handle/类型/线程等调用拒绝外，关闭消耗handle，包括致命输出失败。核心已有
失败或关闭时发生输出失败，返回原结构化错误，但资源已回收或交给协议栈；不可因
返回错误重放close或继续使用旧token。tcp_close若返回错误，按其API约定PCB仍有效，
实现以abort收尾并报告INTERNAL。此罕见分支尚无运行时故障注入。

新增宿主用例覆盖：有未ACK发送数据的关闭及复制内容重传、对端先FIN、未读数据RST、
旧handle失效、TIME_WAIT重复FIN与过期、新PCB/handle复用、固定TCP_SEG耗尽后恢复、
FIN尚未重试即销毁上下文、待握手候选取消、核心时钟倒退、致命输出及重建后计数归零。
另覆盖输出暂时不可用后的FIN重试；恢复时按实际宿主流程通知setLinkReady(true)。
Android模拟器验证JNI的FIN交付、无正常RST、旧token拒绝、FIN/ACK后上下文销毁；
类型化核心回归也使用该关闭接口，包括失败prototype释放。

尚未完成公共TcpStream的幂等关闭/并发取消/configure策略、两后端关闭合同、独立
kernel peer对端点接口的验证、全部资源失败路径及真实NCM/iPhone/ARM验收。已有门槛
保持未通过，不把原语实现视为G2/G3或完整业务迁移完成。
