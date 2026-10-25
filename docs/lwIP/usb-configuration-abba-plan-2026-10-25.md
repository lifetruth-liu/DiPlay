# 重复 SET_CONFIGURATION 单变量实验

目标：区分重复配置请求是否造成 NCM down/up 以及首次 OUT 的长等待。用户已确认设计并授权执行 ABBA×3。

同一 debug APK：A=REPEAT，B=SKIP_IF_CURRENT。两组在打开 USBMUX 配置前都读取 GET_CONFIGURATION；B 仅在读得目标配置时省略 SET_CONFIGURATION，其他路径沿用现有驱动分离和重开逻辑。DEFAULT 保持原流程且不增加读请求。实验参数经 debug 门控，不持久化。

- [x] 添加策略、跳过条件、未知/不同配置回退、驱动分离、release 门控测试，观察 RED。
- [x] UsbConfigurationSelection 封装实验读写日志；IphoneUsbHost 接入；Activity/runtime/controller 传参；NcmUsbBridge 记录 claim 和 SET_INTERFACE 起止及单调时间。
- [x] 定向测试 GREEN，完成 AGENTS.md 全部 CI，校验 APK，安装且保留数据。
- [x] 种子连接单列；ABBA×3 共 12 次，每次 active 后固定等待 3 秒，主动断开，确认 teardown end 后再等待 1 秒启动。
- [x] 固定 native20、LEGACY、默认关闭、2000ms 静默；记录配置请求、通知、OUT、DAD、重发和 active。异常/非 config6/重枚举单列，无静默回退。
- [x] 汇总各组和相邻配对的时间、down/up 发生情况、up→OUT，以及失败样本；恢复 DEFAULT/Java20/LEGACY。

若 B 跳过请求后失败，保留失败并停止自动采集，先调查再继续。12 次不是独立冷启动，不据此认证其他设备。原始日志只存本机临时目录，仓库仅写脱敏样本。

执行结果：完整 CI、12 次交错样本和默认恢复均完成；详见 [实验报告](usb-configuration-abba-report-2026-10-25.md)。关闭完成信号改从 logcat 采集，原始采集停止单列保留。
