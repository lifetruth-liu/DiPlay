# P3 native UDP端点

当前为 `diplay_endpoints.h` 的核心线程C API，已添加[UDP JNI桥](udp-jni.md)，尚未接入公共Kotlin
`UdpEndpoint`。生产会话选择不变，G2/G3不据这批宿主结果通过。

- bind在虚拟IPv6地址上分配真实UDP PCB，绑定本会话netif；port=0返回栈实际端口，
  冲突返回ADDRESS_IN_USE。监听准备不依赖链路ready。
- 每个端点以注册表token标识，不将资源指针作为Java句柄。API经dp_enter/leave
  执行线程/重入/时钟边界；内部raw回调只复制、计数和释放pbuf，不等待业务线程。
- 每端点64KiB有效负载和128个描述符限额构成拥有型接收队列。零长度包也占一个描述符。
  各端点共享固定1KiB块池，数据块、块索引和32端点记录的总分配不超过512KiB，
  由每ABI编译期断言约束。新包超过端点限额或全局池上限时丢弃并计数；当前没有按媒体通道实现过期包优先策略。
  接管成功后立即释放输入pbuf，不保留栈借用地址或帧。
- receive返回16字节来源地址、来源端口、原始长度和复制长度。过小甚至零容量缓冲
  消费一个完整数据报并明确报告截断；空队列返回WOULD_BLOCK，区别于成功空包。
- send在调用期间复制到栈pbuf，成功仅表示栈/有界帧输出接管，不保证USB或对端送达。
  链路尚未ready或实际输出转为not-ready时返回WOULD_BLOCK；不隐藏重发。大小校验上限是IPv6 UDP的65527，
  实际分配/栈pbuf长度限制仍可能返回资源错误。已验证到32KiB，不宣称整个上限实测。
- udp_new受当前32个UDP PCB池限制，端点表亦有上限。每个接收队列仅分配一次固定
  大小记录对象；共享池在首次成功bind时分配，在最后端点释放时回收，raw接收不分配。
  32端点耗尽及资源预算测试通过。额外libc对象内存加入heap_used、
  configured_bytes，heap_peak为栈峰值与端点峰值之和（保守上界，非同时峰值采样）。
- close移除raw接收回调、PCB和队列；core销毁经注册表释放所有端点。旧token不能
  访问复用槽或新core。私有重复close返回INVALID_ARGUMENT；Kotlin适配通过资源scope和
  一次消费的token实现幂等关闭，见[UDP适配](udp-adapter.md)。

真实帧测试 `udp_endpoint` 覆盖准备期绑定、动态/冲突端口、空队列/双向空包、来源、
截断、常规发送、端点和全局队列上限、带序号的记录环回绕和共享块复用、32端点耗尽、带排队数据的整体
销毁和新core零资源。`udp_endpoint_fragments` 覆盖32KiB乱序IPv6分片输入、完整接收、
分片输出重组、UDP端口、字节内容与校验和。两项也在API28 x86_64模拟器原生运行。

上层已有有界核心命令、复制前准入、公共UDP接口及deadline/取消映射；仍需TCP端点、
完整SessionNetwork与会话级接入。
仍需真实NCM/iPhone、ARM、全部公共合同和会话级测试，不能把本C API测试当作已完成
CarPlay集成或公共两后端契约。
