# 原生分配失败定向矩阵

这是[test-plan.md](test-plan.md)中R01的宿主测试子集，不表示完整R01或G3通过。
frame_allocation_tests通过链接器--wrap拦截分配调用，产品不包含注入开关。
普通池使用memp_malloc，溢出检查模式使用memp_malloc_fn，libc部分使用calloc。

| 场景 | 已覆盖的失败位置 | 恢复/资源断言 |
| --- | --- | --- |
| core_allocation_failures冷创建 | 首次进程创建的上下文calloc：1次 | 输出从非零值清空；全局占用释放，随后可创建并收发UDP |
| 同场景热创建 | lwIP全局初始化后的完整创建调用，当前测得1点 | netif链表/default为空，heap/全部MEMP pool回基线，随后创建和收发UDP |
| udp_bind_allocation_failures | 初次池3点、已有池2点 | 失败输出清空，固定端口可重绑，原端点已有报文与收发保留 |
| udp_allocation_failures | 1200字节1点、65487/65488/65527各93点 | 错误明确、无完整失败报文、资源回基线，解除注入可完整发送 |

位置计数是按调用场景和执行顺序统计，不能理解为不同源码分配语句数。每个场景
先在成功路径计数，再逐个令第k次分配返回NULL；冷创建首次calloc是单独定向检查。
calloc命中断言避免工具链绕过包装后假绿。未以清空整个池来掩盖某个失败分支的泄漏。

核心创建测试先验证冷calloc失败，完成一次正常初始化，再遍历热创建分配。失败
返回RESOURCE_LIMIT、无核心发布，netif_list/default清空；恢复核心还需完成DAD、
邻居学习、UDP绑定、发送及捕获帧核对，再销毁并检查heap及全部pool。

通过scripts/test_lwip_host.ps1或bash scripts/test_lwip_host.sh --sanitize运行；
也可单独执行frame_allocation_tests core_allocation_failures。测试使用GNU兼容
链接器，仅在已验证的MinGW/Linux宿主构建中声明结果。

当前54项宿主测试通过，包括ASan/UBSan、lwIP池检查和默认LSan构建。未把宿主
结果当作Android/JNI或真实NCM/iPhone证据。既有UDP分配详情见
[udp-large-datagram-gap.md](udp-large-datagram-gap.md)。

仍缺：冷启动内部周期定时器池初始化的逐点失败、其他libc分配点、JNI复制/异常、
全部TCP/重组/未知邻居排队及并发关闭路径、完整进程峰值和实际设备验证。全局
初始化保留的周期计时器属于热创建基线，不能把这些常驻项误记成已经逐点注入。
产品代码、vendor摘要与公共C/JNI ABI不变，生产后端未切换。
