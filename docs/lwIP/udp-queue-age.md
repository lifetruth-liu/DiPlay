# UDP按端点排队时间预算

原生C API新增`dp_udp_set_queue_max_age(core, handle, max_age_millis)`及
`dp_udp_get_expired_count(core, handle, &expired)`。默认max_age=0，保持控制类数据报
现有行为；非零值由通道显式指定，不硬编码媒体时长。现已接通原型token桥及
引入于JNI ABI 7（当前ABI 8），见udp-jni.md；显式SessionNetwork的UDP端点通过可选公共能力
`UdpQueueAgeControl`接通配置及统计。
配置由`CarPlayMediaEngine`按协商`framesPerPacket`和音频采样率传入，仅启用在RTP数据端口；
最大排队年龄为4个包时长，Opus按20ms包时长，缺失/无效协商值回退到20ms，因此缺省预算为80ms。
只有实现可选`UdpQueueAgeControl`的端点会应用非零预算；普通系统UDP维持原有默认行为，
RTCP控制端点也不配置限龄。宿主单元测试覆盖计算和数据/控制端口隔离，原生端到端队列
过期语义由`frame_tests udp_queue_age`及Android JNI instrumentation分别覆盖。真实媒体消费者暂停/恢复
和整通道验收仍未完成，不能据此宣称R04或设备验收已完成。

每个报文记录核心64位单调时钟时间，age>=max_age时过期；不使用32位栈时间计算
排队年龄。入队前、receive、queue stats及expired stats访问时清理队头过期包，
设置新预算也立即清理现有队列。清理最多遍历128项，不分配、不等待，归还数据块，
增加总dropped及独立expired计数。未调用/未收到数据期间可能仍保留队列，但内存
受原有上限约束，下一次原生出队会拒绝已经过期的数据。当前年龄检查止于原生
出队；已经交给命令结果的报文，以及解码器/媒体层后续缓冲，不在本接口的年龄
统计范围内。完整消费者暂停恢复仍需覆盖这些阶段，不能仅用本队列测试替代R04。

额外时间戳和端点字段仍计入512KiB总预算，编译期重新计算数据块容量，不增加
固定预算。未改变现有公共结构体布局和C ABI版本；新增C函数是增量接口，JNI已升至7。
原始vendor源码及摘要不变。默认关闭策略不自动丢弃控制报文。

`frame_tests udp_queue_age`覆盖100ms到期边界、同队列旧/新报文、另一个默认控制
端点保留、128个零长度报文过期后满队列接纳新包、配置变更清理、32位时间回绕、
关闭后旧句柄/空统计指针和最后端点资源回收。宿主验证不证明Android JNI运行、
真实音频恢复、全局跨端点抢占公平性或全部R04消费暂停场景。
