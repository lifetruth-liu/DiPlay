# UDP按端点排队时间预算

原生C API新增`dp_udp_set_queue_max_age(core, handle, max_age_millis)`及
`dp_udp_get_expired_count(core, handle, &expired)`。默认max_age=0，保持控制类数据报
现有行为；非零值由通道显式指定，不硬编码媒体时长。现已接通原型token桥及
JNI ABI 7，见udp-jni.md；尚未接通公共SessionNetwork或实际媒体配置，不能据此
宣称R04或整通道已完成。

每个报文记录核心64位单调时钟时间，age>=max_age时过期；不使用32位栈时间计算
排队年龄。入队前、receive、queue stats及expired stats访问时清理队头过期包，
设置新预算也立即清理现有队列。清理最多遍历128项，不分配、不等待，归还数据块，
增加总dropped及独立expired计数。未调用/未收到数据期间可能仍保留队列，但内存
受原有上限约束，下一次消费绝不交付已经过期的数据。

额外时间戳和端点字段仍计入512KiB总预算，编译期重新计算数据块容量，不增加
固定预算。未改变现有公共结构体布局和C ABI版本；新增C函数是增量接口，JNI已升至7。
原始vendor源码及摘要不变。默认关闭策略不自动丢弃控制报文。

`frame_tests udp_queue_age`覆盖100ms到期边界、同队列旧/新报文、另一个默认控制
端点保留、128个零长度报文过期后满队列接纳新包、配置变更清理、32位时间回绕、
关闭后旧句柄/空统计指针和最后端点资源回收。宿主验证不证明Android JNI运行、
真实音频恢复、全局跨端点抢占公平性或全部R04消费暂停场景。
