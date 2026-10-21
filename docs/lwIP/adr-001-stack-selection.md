# ADR 001：固定 lwIP 2.2.1 作为宿主原型候选

日期：2026-10-21。状态：原型选型已记录，G0/G2 未通过。

## 决定

沿用方案的 lwIP 单线程 raw API / NO_SYS 模型，选择正式版本 2.2.1，
不跟踪 master。官方 [发布公告](https://lists.nongnu.org/archive/html/lwip-users/2025-02/msg00001.html)
确认其 tag 为 `STABLE-2_2_1_RELEASE`。
已直接查询官方 Git 标签，并下载官方发布 ZIP 核对文件和许可证；固定值见
`shared/src/main/jni/lwip_backend/UPSTREAM.md`。此记录不表示已经编译原型。

与自行编写 TCP 相比，lwIP 提供可审计的协议实现；与 loopback 代理相比，raw API
可以保留真实协议端点、UDP 来源及消费驱动的 TCP 接收窗口。
成本是必须自行实现有界核心调度、JNI 生命周期、缓冲所有权及取消，不能依赖 GC。
没有实测证据支持优于内核的性能声明。

## 配置边界

宿主原型从 Ethernet、IPv6、ICMPv6/NDP/DAD、TCP/UDP、显式单调计时开始。
首期不启用 NAT、PPP、DHCP 或互联网转发。IPv4、mDNS、MLD 和分片支持的最终
选择仍受通道抓包门槛约束；源码审计不能代替设备证据，不在此无依据地删去双栈能力。
Android 继续沿用 ndk-build / API24 / 四 ABI；宿主独立 CMake，不链接 JNI。

资源限制和原型退出条件逐项沿用 performance.md：总受控动态内存 16 MiB、
TCP 32、UDP 32、listener 16、输入/输出帧队列各 512 KiB、命令 1024，
同时保留关闭命令配额。不能以放宽这些预算替代 ARM 测量。

## 未解决条件

尚未完成 CVE/上游修复审计、配置编译、Ethernet fixture、Linux 互通、四 ABI
加载、sanitizer、NCM 原型和低配 ARM 性能验证。ZIP 校验只用于固定已取得的工件，
本次未执行 GPG 签名验证。后续依赖引入需保留上游 COPYING，记录本地补丁和漏洞审计。
若原型失败，保留证据并停止扩大生产迁移，不将候选版本设为默认后端。
