# USB OUT errno 与启动超时对照

用户已授权设备能力检查和单变量超时实验。沿用当前工作区的启动探测实现。

- [x] 检查 shell、usbmon、dmesg、ftrace 权限：无可用主机事务采集权限，run-as 可用。
- [x] 测试先行：debug 门控、超时范围、首次成功后恢复正常写入、errno 解码。
- [x] 增加独立 JNI 同步 USBDEVFS_BULK 诊断，立即保存 errno；保持 Android 8 的 critical-array 写入方式，不重试 syscall，不改错误分类。
- [x] 同一个 APK 对照 Java 20ms 与 native 20ms，确认可观测性路径可用。
- [x] native 20ms/100ms ABBA 重复 3 组，每组策略固定 LEGACY；各 6 次主动重连，断开返回后等 1 秒。安装首次连接单列。
- [x] 完整 AGENTS CI、四 ABI 核验、安装后实验，采集阶段时间、errno、失败次数、写耗时。
- [x] 保存脱敏数据与报告，恢复默认 Java 20ms、LEGACY。原始日志仅保留临时目录。

实现仅改变首次成功之前的可选写入路径。实验参数不持久化、不添加设置项。发布构建忽略入口参数。长超时上限 100ms，调用方更短的超时优先。errno 只说明内核系统调用结果，不能直接证明线上的 NAK 或 iPhone 内部状态。
