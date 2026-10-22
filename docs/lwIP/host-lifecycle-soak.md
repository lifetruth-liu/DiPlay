# 10,000次原生宿主生命周期

对应test-plan.md的L06宿主子项；不代表100次设备重连或完整L06通过。
`frame_tests lifecycle_soak`执行10,000轮，每轮新建、完成DAD/邻居学习、销毁核心，
交替覆盖空核心、UDP带排队seed及已发送reply、TCP带未读queued及未ACK的unacked。
一半相关轮次显式关闭端点，另一半让核心销毁回收活端点。每轮重复destroy核对幂等。

每次销毁后netif链表/default为空，lwIP heap和全部非timeout pool回初始基线。
全局TCP定时器回调参数为NULL，在最后PCB销毁后的下一tick才退休，因此timeout
pool可暂比初始多一个节点，逐轮检查不会增长。每1,000轮创建空核心推进时钟，
确认所有pool精确回基线；这些额外清扫核心不计入10,000个负载轮次。

每1,000轮采样Windows进程handle数，或Linux `/proc/self/fd`、`/proc/self/task`，
要求与预热后的初始数相同。Linux配合ASan/UBSan、pool guard和默认LSan验证，
没有使用实际USB、JNI、Java线程或设备。不能据此推断整会话的租约、线程、FD、
进程峰值、100次插拔或8小时音视频会话已经通过。

通过现有宿主脚本运行，全量测试包含该项：

```sh
bash scripts/test_lwip_host.sh --sanitize
```

Windows使用`scripts/test_lwip_host.ps1`。CTest为该项保留300秒上限，测试不等待
真实时钟，不用吞吐或墙钟耗时作为性能证明。结果和命令日志记录于execution-status.md。
