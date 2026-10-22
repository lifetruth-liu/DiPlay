# P3 端点句柄基础

本批提供 native 私有注册表，不是完整 SessionNetwork 或 JNI 端点实现。
实际 NCM/ARM 的 G2 证据仍缺失，生产选择及业务迁移不扩大。

`src/endpoint_registry.{h,c}` 的固定80槽与当前32 TCP PCB、16 listener和32 UDP PCB
配置总量对应。表嵌入每个 `dp_core`，由现有 `sizeof(*core)` 资源预算覆盖，不按
句柄数量另行分配表项。实际 PCB 和端点缓冲预算仍需在端点实现时分别约束。

正值64位token由固定tag、进程级单调32位generation、端点类型和槽号组成。
不编码指针；仅作为身份校验，不能当作认证秘密。每次注册消耗新generation，
跨表及上下文重建不会复用；generation耗尽返回RESOURCE_LIMIT，禁止回绕。
查找同时核对tag、类型、槽范围和完整token，不将旧句柄解析为新资源。

成功注册转移资源所有权；失败仍由调用方释放。删除先使槽失效，再调用一次release。
批量clear可重复，core销毁在移除剩余全局PCB前调用clear。release须非阻塞且不能
重入注册表；未来端点释放函数必须注销自己的raw回调/PCB并释放缓冲。
注册表是核心线程私有组件，本身不提供锁或阻塞；未来JNI命令必须通过核心线程
入口调用，不直接从业务线程访问。公开端点close的幂等语义由端点层实施；私有remove
对重复/陈旧token返回INVALID_ARGUMENT，避免误将重复操作应用于复用槽。

宿主 `endpoint_registry_tests` 覆盖正确查找、正值token、跨表/类型拒绝、槽复用后的
旧token拒绝、80槽耗尽及输出清零、逐项与批量释放一次、重复clear、清空后token不重用。
另用真实dp_core创建/execute/销毁验证资源释放回调。首次缺实现，随后占位实现产生
预期断言失败；增加核心销毁用例后再次red，补上clear后通过。

这些测试证明表及所有权回调行为，不替代真实TCP/UDP PCB回调竞态、读写取消、
半关闭、DirectByteBuffer租约、JNI异常及跨线程命令队列测试。generation耗尽分支
未通过数十亿次实际分配实测；当前依据代码中的显式无回绕检查，后续可增加限定测试入口。
