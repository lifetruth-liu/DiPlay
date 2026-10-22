# Native fuzz驱动与CPU计量

`scripts/lwip_fuzz_driver.py`是Linux libFuzzer活动的报告驱动。它尚未提供三个
native target或fuzz_lwip.sh构建入口；这些仍按实施计划继续，不把此组件交付当作
完整fuzz工具或24 CPU小时验收通过。

命令要求target、output，以及三种互斥模式之一：cpu-hours、runs、replay。
seed必须1..2147483647，预算必须正且有限。cpu-hours模式反复运行10秒墙钟上限
批次，每批通过wait4记录实际子进程user+system CPU；累计达到请求值且子进程
成功才设置cpu_budget_complete。runs是冒烟、replay只执行一个文件，两者始终
不授予CPU预算通过。崩溃/非零退出使驱动非零退出，保留日志和crashes目录。

每次活动创建唯一campaign目录，逐批原子保存report.json，保留命令、seed、CPU/
墙钟用量、RSS、binary SHA-256、实际source SHA-256、平台和sanitizer选项。
可访问Git时附commit及dirty；源码归档或WSL无法解析Windows worktree时明确记录
Git不可用，仍保存实际源码和二进制摘要。共享corpus保留并记录逐文件摘要；默认
不导入真实媒体或抓包。

单元测试覆盖参数、重放命令、seed、失败/不足时不通过预算、实际Linux子进程CPU
计量和失败活动报告。测试中的假子进程只验证驱动报告，不是lwIP fuzz证据。
CI已接入这些测试；三个真实target、coverage插桩、有效协议种子、短时冒烟和完整
24 CPU小时活动仍未完成。实施步骤见
../superpowers/plans/2026-10-23-lwip-native-fuzz.md。
