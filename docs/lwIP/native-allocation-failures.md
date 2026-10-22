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
| tcp_listen_allocation_failures | reuse开/关，各3点，瞬时/持续两种模式 | 重试成功则验证连接可用再关闭；失败则清零输出、释放端口/PCB/句柄并可重绑 |
| tcp_accept_allocation_failures | 最终ACK处理2点，瞬时/持续两种模式 | 无错误stream交付，原监听器可继续连接；关闭后半开/未交付资源归零并可重绑 |
| tcp_syn_allocation_failures | SYN输入5点，瞬时/持续 | 不发布错误stream；监听器恢复或明确核心失败，销毁及重建后资源归零 |
| tcp_timer_allocation_failures | 实际TCP handler重排分配，瞬时/持续 | 瞬时恢复；持续核心失败，旧端点错误和销毁/重建可验证 |
| tcp_connect_allocation_failures | 主动连接5点，瞬时/持续 | 成功则握手及双向数据；失败句柄清零，检查核心状态和资源回收/重建 |
| tcp_write_allocation_failures | 1字节2点、8192字节32点，瞬时/持续 | copied=0且原子回滚、无新数据输出；重试完整发送、缓冲区所有权及下一次写入序号正确 |
| tcp_receive_allocation_failures | 1/1440字节，顺序各2点、乱序各3点，瞬时/持续 | 原队列seed保留，缺口前不可读；重传后完整无重复、同stream继续接收，关闭资源回基线 |

位置计数是按调用场景和执行顺序统计，不能理解为不同源码分配语句数。每个场景
先在成功路径计数，再逐个令第k次分配返回NULL；冷创建首次calloc是单独定向检查。
calloc命中断言避免工具链绕过包装后假绿。未以清空整个池来掩盖某个失败分支的泄漏。

核心创建测试先验证冷calloc失败，完成一次正常初始化，再遍历热创建分配。失败
返回RESOURCE_LIMIT、无核心发布，netif_list/default清空；恢复核心还需完成DAD、
邻居学习、UDP绑定、发送及捕获帧核对，再销毁并检查heap及全部pool。

通过scripts/test_lwip_host.ps1或bash scripts/test_lwip_host.sh --sanitize运行；
也可单独执行frame_allocation_tests core_allocation_failures。测试使用GNU兼容
链接器，仅在已验证的MinGW/Linux宿主构建中声明结果。

当前61项宿主测试通过，包括ASan/UBSan、lwIP池检查和默认LSan构建。未把宿主
结果当作Android/JNI或真实NCM/iPhone证据。既有UDP分配详情见
[udp-large-datagram-gap.md](udp-large-datagram-gap.md)。

仍缺：冷启动内部周期定时器池初始化的逐点失败、其他libc分配点、JNI复制/异常、
全部TCP/重组/未知邻居排队及并发关闭路径、完整进程峰值和实际设备验证。全局
初始化保留的周期计时器属于热创建基线，不能把这些常驻项误记成已经逐点注入。
TCP定时器本地修正及vendor摘要见BACKPORTS.md，公共C/JNI ABI不变，生产后端未切换。

## TCP监听瞬时与持续不足

监听普通路径测得3处分配：监听记录calloc、普通PCB池、监听PCB池。每种reuse
设置先计数成功路径，再逐一注入一次失败，以及从第k次开始持续失败，共12个
场景位置。包装按真实分配器语义增加heap/pool错误计数，避免lwIP重试成功后的
计数修正下溢；不修改库的重试策略。

瞬时普通PCB失败由tcp_alloc重试恢复，因此允许返回一个完整可用监听器；不能把
“某次底层分配失败”误当成“整个监听操作必须失败”。当前两种reuse各有1例恢复
成功、2例RESOURCE_LIMIT；持续模式各3例RESOURCE_LIMIT。成功结果先实际握手、
accept、读取数据再关闭；失败结果必须handle/port清零。所有场景检查heap、活动/
监听PCB和全部MEMP pool回到基线，并重绑同一端口完成新连接。按需TCP定时器通过
受控poll退休后才比较基线，不将正常待退计时器误当作泄漏。

测试没有其他活动TCP连接，未覆盖tcp_alloc回收其他连接、accept/connect/发送分配、
冷定时器或并发取消；不把本子集扩大为完整TCP/R01验收。

## 接受连接最终ACK的分配失败

先正常完成SYN/SYN-ACK，再对最终ACK处理逐点注入，当前测得输入pbuf与接受记录
calloc两点。瞬时及持续模式均验证：无故障控制能接受并读取数据；输入分配失败
不处理该ACK，连接保持半开；接受记录失败不交付stream。dp_tcp_accept无连接时
须WOULD_BLOCK且输出handle清零，不能把异步接受失败当成一个成功的空stream。

在关闭原监听器之前，用另一个源端口完成握手、accept和数据读取，证明原监听器
未被破坏。随后关闭监听器、退休按需TCP定时器，检查heap、活动/监听PCB和全部
MEMP pool回到基线；再次同端口创建监听器并收发。测试只针对原生核心，不据此
推断JNI运行器在输入OOM后继续运行；SYN接收、周期定时器及并发取消等仍待覆盖。

## SYN与TCP定时器重排

新增SYN逐点瞬时/持续失败矩阵，正常路径测得5处分配。瞬时定时器分配失败曾触发
上游断言；现TCP仅在定时器插入成功后置active，owner边界重试，持续无法调度时
返回核心RESOURCE_LIMIT并走销毁屏障。SYN后续分配失败也可能先回滚PCB，此时
无需保留TCP定时器，不能要求每个持续失败场景都使核心失败。

另取实际已排队TCP定时器的handler，移除旧节点后在owner内重放，定向覆盖重排
分配失败：瞬时失败恢复，持续失败必须使核心失败；检查旧端点错误、销毁、重建
及heap/全部pool回基线。此重放只证明分配分支，不证明真实到期时间精度。
冷启动及非TCP周期定时器仍保留原断言路径，完整R01尚未通过。

## 主动连接分配失败

正常主动连接测得5处分配，分别逐点瞬时及持续注入。瞬时2例恢复成功、3例明确
RESOURCE_LIMIT；持续5例RESOURCE_LIMIT，其中1例核心失败。成功结果必须完成
真实帧握手、接收reply及发送hi；无首个SYN时受控推进时间以允许重传。失败输出
handle必须清零。非核心失败先退休TCP timer并检查heap/活动PCB/监听PCB/全部pool
回到调用前基线，再在同核心重新连接并收发；所有场景销毁后检查全局基线，且另
创建核心、连接并收发后再次检查。无其他活动连接，不覆盖回收其他PCB及全部
写入/重组/未知邻居/并发路径，也没有新增Android/JNI或真机结论。

## TCP写入分配失败与原子回滚

1字节正常写入测得2点、8192字节测得32点，分别逐点瞬时和持续注入，共68个
失败位置。当前全部返回WOULD_BLOCK且copied=0，符合现有TCP写入背压接口；未将
它记作test-plan.md中要求RESOURCE_LIMIT的完整R01通过，也未修改该验收要求。
每次失败在重试前核对heap/全部pool回到写入前基线，且未输出新TCP数据。解除注入
后完整重试，成功即覆盖调用方source，最终逐帧核对校验和、序号和内容，确保已复制
数据不再借用调用方内存。全部ACK后同stream再写1字节，验证没有重复接受前缀。
关闭stream和listener、退休按需timer后检查heap/活动PCB/监听PCB/全部pool基线，
再重绑同端口完成连接和接收。此场景是空发送队列的首写，不包含已有unsent队列
拼接、所有发送窗口状态、输出分配失败及取消竞态；完整R01/R02仍未通过。

## TCP顺序及乱序输入分配失败

先在已接受stream排入seed，再对1或1440字节的新数据帧输入注入；顺序每种长度
正常2点，乱序每种长度3点，各遍历瞬时/持续故障，共20个位置。每组只有输入
pbuf分配失败返回RESOURCE_LIMIT；后续协议分配失败可能被栈处理为丢弃/待重传并
返回OK，未据此宣称完整R01的错误映射验收通过。

任何结果均先确认核心健康且原seed可完整读取。乱序数据在缺口补齐前不可读，
WOULD_BLOCK不修改目标缓冲区。解除注入后重传同帧，再填gap，核对完整内容与
无重复交付；随后同stream发送next并接收，证明连接继续可用。关闭stream和
listener、退休timer后heap/活动PCB/监听PCB/全部pool回初始基线，重绑同端口再
完成连接和接收。没有覆盖所有重叠/多段乱序/FIN/重组/回调拒绝及并发关闭状态；
也不把宿主核心可恢复扩大为JNI runner在输入OOM后会保留原会话。
