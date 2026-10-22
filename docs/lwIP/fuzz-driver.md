# Native lwIP fuzz入口与CPU计量

`scripts/fuzz_lwip.sh`会用Linux Clang/libFuzzer为真实lwIP栈和DiPlay port构建一个
target，再执行 `scripts/lwip_fuzz_driver.py`。CMake通过 `DIPLAY_FUZZ=ON` 显式开启
SanitizerCoverage；同一次构建开启ASan、UBSan和lwIP pool guards。默认Android和普通
host构建不启用fuzzer编译或链接参数。

三个target分别覆盖：

- `ethernet`：对真实IPv6以太网帧输入模糊测试，含UDP、TCP SYN、ICMPv6 echo和短帧种子。
- `commands`：执行有界端点命令序列，覆盖UDP收发/统计/排队年龄、TCP读写/关闭、
  stale token、link状态、时钟推进及嵌入的以太网帧。
- `lifecycle`：在命令序列中反复销毁和重建core，验证旧token及资源回收。

每个输入都创建真实native core，固定接口、地址、时钟和随机源；输入最多触发128条
命令，单命令payload最多255字节。target在销毁后检查netif、堆和lwIP池计数，容许
TCP全局定时器至多一个延迟回收项。它们不调用Android JNI、USB或设备网络驱动。

每种target在首次运行时由 `scripts/generate_lwip_fuzz_seeds.py` 添加确定性种子；已有
corpus文件保持原样。libFuzzer随后把发现的输入继续写入corpus。建议每种target使用
独立输出目录：

```sh
bash scripts/fuzz_lwip.sh --target ethernet --runs 100 --seed 7 --output build/lwip-fuzz/ethernet
bash scripts/fuzz_lwip.sh --target commands --cpu-hours 8 --seed 7 --output build/lwip-fuzz/commands
bash scripts/fuzz_lwip.sh --target lifecycle --cpu-hours 8 --seed 7 --output build/lwip-fuzz/lifecycle
```

`--runs`只做短时冒烟，`--replay FILE`只执行指定输入；这两种模式都不计入CPU门槛。
`--cpu-hours`按Linux `wait4`返回的真实子进程user+system CPU时间累计，每个子进程以
最多10秒墙钟时间分批运行。只有子进程成功且累计CPU达到请求值，报告才会把
`cpu_budget_complete`设为true。墙钟时间、CI smoke或假子进程都不能代替CPU验收。

每次活动创建独立 `campaign-*` 目录，逐批原子写 `report.json`，记录实际binary和源码
SHA-256、种子、命令、每批CPU/墙钟/RSS、sanitizer选项、corpus摘要及失败日志。构建目录
另存Clang/CMake版本、配置/构建命令和日志。Git不可用时记录错误并保留源码摘要，不伪报
commit或clean状态。崩溃输入保存在该活动的 `crashes/`；脚本不会自动导入真实媒体或设备抓包。

CI会对三个真实target各运行100次种子smoke，并上传corpus、报告、构建元数据和日志。
这只证明入口、种子与短期运行可用，不满足测试计划要求的三类target各8 CPU小时、
总计至少24 CPU小时。该长时门槛仍未运行；真机NCM/iPhone及低配ARM验收也未覆盖。
完整目标与剩余门槛见 [test-plan.md](test-plan.md) 和
[execution-status.md](execution-status.md)。
