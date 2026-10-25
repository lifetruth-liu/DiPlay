# NCM 启动暂停与 DAD 对照计划

本轮按用户批准的第1、2项执行，不改变20ms启动写超时、2秒Start重发等待、默认USB复用或单次恢复。

1. 先写失败测试：ICMPv6头解析与DAD探测识别；实验仅debug生效；探测重试只接受无源地址的NS、遇成功/失败/取消停止、有次数/时长上限；USB失败期间地址不能提前完成DAD。
2. 新增头部诊断（ICMPv6类型/代码、是否DAD），核心线程记录原生地址状态变化、暂停原因、恢复调度、队列等待与探测计数。观察只读原生状态，不推进协议计时；日志有界、不输出地址或载荷。
3. A保持现有恢复；B通过一个debug启动参数选择：第一份DAD NS在USB线程每100ms有界重试，核心保持暂停，首次成功才恢复DAD；不重试TCP或任意载荷，不提前标记preferred。默认/release仍A。两组同APK，唯一变量为启动恢复策略。
4. 单测RED/GREEN，完整AGENTS.md九项CI和native检查。覆盖安装当前设备，同APK交错A/B各10轮（若设备或失败需提前终止，明确记录），固定断开间隔、后端和其他参数。保存脱敏逐轮数据，报告controller到Start、Start到首次OUT、DAD完成、Start重发、TCP、active及成功率。安装首次连接单列，不混入主动重连。
5. 结果不利时保留A默认；区分本地循环因果、探测采样误差与尚未知的USB失败原因。不以Java负返回值证明NAK/STALL。

实现位置：NcmFrameHeader/NcmTransferDiagnostics、LwipPrototype/NativePrototypeCore及原生stats、CarPlayRuntimeConfig/Controller/HostActivity。通过已有attempt诊断回调串联时钟；采集脚本和原始日志保留临时目录。
