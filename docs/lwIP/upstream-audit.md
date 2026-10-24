# lwIP 上游审计快照

日期：2026-10-22。候选：DiPlay `d58c2f15`，lwIP 2.2.1，
`77dcd25a72509eb83f72b033d219b1d40cd8eb95`。结论：发布ZIP签名验证通过；
安全审计**未通过**。TCP选项边界、IPv6空重组清理、NDP依赖链及PMTU最小值检查已回补；NDP定向覆盖和更完整协议验证尚未闭环。
独立内核peer的UBSan另发现TCP timestamp有符号移位，已做本地unsigned转换及高位/
split真实解析器回归，详见[新端点内核验证](kernel-endpoint-peer.md)和BACKPORTS.md。
该项不被归为上游提交或CVE，不改变完整安全审计未通过的结论。
不据未编译应用模块或既有绿色测试宣称该版本没有漏洞。

## 工件身份与签名

官方[发布目录](https://download.savannah.nongnu.org/releases/lwip/)及Git查询仍显示
2.2.1为所查询的最新正式标签。官方Git的annotated tag对象为
`009c2256469004009488b3385ba269461e8eb616`，对象正文没有PGP签名，不能称为签名tag。
ZIP与其detached signature是另一组工件：

| 工件 | SHA-256 / 身份 |
| --- | --- |
| lwip-2.2.1.zip | `7b622662dba2383d71f874f2e494b54ae948531559c17acbe75797966d646878` |
| lwip-2.2.1.zip.sig（310字节） | `e934e3c4d0fd4b6ac213db43d163707942184df6df8784c0f084ed5d95532a18` |
| 下载的公钥（981字节） | `e0f37a452e07e6dbbd6679b71653f652aab830817e9fcbf24cf8086d272c9ae1` |
| 签名及公钥指纹 | `746BEB8E56372EB695EA02E71D6B61C1B84299F5` |
| UID | Simon Goldschmidt，goldsimon@gmx.de |

公钥来自维护者[Savannah账号](https://savannah.nongnu.org/users/goldsimon)指向的
[公钥下载](https://savannah.nongnu.org/people/viewgpg.php?user_id=42414)。
在隔离GPG home导入后，对既有ZIP执行verify，exit0、GOODSIG及VALIDSIG，
签名时间2025-02-06。没有设置owner trust；GPG的TRUST_UNDEFINED保留，
身份关联依据是此次通过HTTPS取得的官方账号公钥，不是本机Web-of-Trust认证。
签名有效不代表代码已经通过安全评估。

复现时先独立核对完整指纹，再使用隔离目录；构建本身不联网、不要求GPG：

```sh
curl --fail -L -o lwip-2.2.1.zip https://download.savannah.nongnu.org/releases/lwip/lwip-2.2.1.zip
curl --fail -L -o lwip-2.2.1.zip.sig https://download.savannah.nongnu.org/releases/lwip/lwip-2.2.1.zip.sig
curl --fail -L -o goldsimon-key.asc 'https://savannah.nongnu.org/people/viewgpg.php?user_id=42414'
audit_keyring="$(mktemp -d)"
gpg --homedir "$audit_keyring" --show-keys --with-fingerprint goldsimon-key.asc
gpg --homedir "$audit_keyring" --import goldsimon-key.asc
gpg --homedir "$audit_keyring" --status-fd 1 --verify lwip-2.2.1.zip.sig lwip-2.2.1.zip
```

## 已公布问题的配置适用性

### SO_REUSE配置增量审查（2026-10-22）

显式会话适配为reuseAddress开启SO_REUSE=1。基于同一固定vendor及三项已列回补，
重新检查tcp.c的bind/listen/connect分支、tcp_in.c的监听匹配和子PCB继承、udp.c的
匹配/绑定分支，以及ip.h/opt.h的选项定义：

- TCP只有双方PCB设置SOF_REUSEADDR时才跳过活动地址绑定冲突；启用选项的新绑定
  可跳过TIME_WAIT列表，未移除旧TIME_WAIT。listen阶段仍拒绝同地址/端口的重复
  活动监听，connect保留重复连接元组检查。
- 子PCB继承SOF_REUSEADDR（SOF_INHERITED）。匹配优先具体本地地址，再回退ANY；
  新端点API仍绑定唯一虚拟netif及具体IPv6，未开放任意系统接口绑定。
- UDP端点不设置SOF_REUSEADDR，重复绑定仍拒绝；SO_REUSE_RXTOALL维持默认0，
  未开启广播/多播的额外多接收者复制路径。
- 新增tcp_reuse真实帧用例覆盖活动子连接、TIME_WAIT、重复监听和非法选项，既有
  UDP/TCP用例同时回归；Linux ASan/UBSan/pool guards及Windows最终各50/50。
  另验证复用后新旧连接分流。日志：WSL /tmp/diplay-session-final2-host.log、
  TEMP/diplay-session-final2-windows.log。

本次是配置相关路径的增量检查，不是完整CVE重检、模糊测试或安全审计通过。
输入匹配的全部地址/多播组合、资源失败及长期复用竞态仍需后续覆盖，首段安全
审计未通过的结论不变。未引入新的vendor应用模块或改变固定源码身份。

检查了Android.mk、CMake源清单和四ABI的真实stack archive，各30个object；
没有mqtt/snmp/smtp/lowpan/ppp/zepif成员。当前只编译core、core/ipv6及ethernet netif；
IPv4、DHCP、DNS、socket/netconn等配置关闭。下表只是这些问题的适用性判断，
不是完整CVE清单。未来引入模块、改变配置或编译清单必须重新审计。

| 问题/公开修复 | 当前判断 | 依据 |
| --- | --- | --- |
| CVE-2026-87121，MQTT接收越界 | 对应模块未链接；原vendor源码仍未修补 | [CISA公告JSON](https://github.com/cisagov/CSAF/blob/develop/csaf_files/OT/white/2026/icsa-26-265-01.json)，实际修复为[68b2c119](https://github.com/lwip-tcpip/lwip/commit/68b2c119)，不能只拣相邻的测试修改 |
| CVE-2026-8836，SNMPv3参数长度 | 对应模块未链接；原vendor源码仍未修补 | [原始CNA记录](https://cveawg.mitre.org/api/cve/CVE-2026-8836)及上游[0c957ec03054eb6c8205e9c9d1d05d90ada3898c](https://github.com/lwip-tcpip/lwip/commit/0c957ec03054eb6c8205e9c9d1d05d90ada3898c) |
| CVE-2026-91018，6LoWPAN释放问题 | lowpan6及zepif未链接；原vendor源码仍未修补 | [CISA公告JSON](https://github.com/cisagov/CSAF/blob/develop/csaf_files/OT/white/2026/icsa-26-265-02.json)，上游[f873b6295933e4149a2132adf3e9a2d2a676a5ec](https://github.com/lwip-tcpip/lwip/commit/f873b6295933e4149a2132adf3e9a2d2a676a5ec) |
| SMTP AUTH处理，614420f8 | 对应模块未链接；未把它无依据地归入上述CVE | 上游[614420f8](https://github.com/lwip-tcpip/lwip/commit/614420f8) |

## 启用路径上的维护修复：未闭环

从官方Git克隆并固定审计HEAD为
`d08f4773edd0182b7910fc8f046eed82ffcd67c9`（提交时间2026-09-01），
审查正式tag至该HEAD在当前core/ethernet源范围的16项变更。
这是固定时间快照，不跟踪master或自动改变产品依赖。

| 提交 | 判断与下一步 |
| --- | --- |
| [56b29f8bcfaefe2974dca67bde16cc7c391feaeb](https://github.com/lwip-tcpip/lwip/commit/56b29f8bcfaefe2974dca67bde16cc7c391feaeb) | IPv6重组清理空pbuf检查已精确回补。真实重组入口因pbuf配额不足留下空上下文，原版UBSan空指针复现；修复后定时清理及资源回收回归通过。该129-pbuf用例测试栈内部失败路径，不声称当前单帧入口可远程触发 |
| [3d896ba0a37ff3ce73270ca5e230707fe47f60e3](https://github.com/lwip-tcpip/lwip/commit/3d896ba0a37ff3ce73270ca5e230707fe47f60e3) | TCP选项读取边界检查已精确回补；原版ASan越界复现、截断MSS/未知选项及两缓冲区各分割点测试已通过。完整入口pbuf处理与直接解析器测试的范围见[补丁记录](../../shared/src/main/jni/lwip_backend/BACKPORTS.md) |
| [0a65e654943c7e91c8f1129513572e99072ef142](https://github.com/lwip-tcpip/lwip/commit/0a65e654943c7e91c8f1129513572e99072ef142)、db4e4c9d、[8e75a40acfea6b05ee643099e41f3b2e11ee464d](https://github.com/lwip-tcpip/lwip/commit/8e75a40acfea6b05ee643099e41f3b2e11ee464d)、7d1327e5 | 四项依赖链和本地游标边界检查已验证；2026-10-23 补上 NA/NS/RA/Redirect 真实入口约束测试及对应本地校验，详见[补丁记录](../../shared/src/main/jni/lwip_backend/BACKPORTS.md)。定向覆盖不代表完整 RFC 4861 或安全验收 |
| [d08f4773edd0182b7910fc8f046eed82ffcd67c9](https://github.com/lwip-tcpip/lwip/commit/d08f4773edd0182b7910fc8f046eed82ffcd67c9) | 已适配最小PMTU检查；保留原32位解码和65535钳制，不采用上游低16位mask。真实PTB引用实际发出的UDP包，极小/边界/超大值与后续UDP回包已覆盖；不是完整引用包身份验证或TCP大流量恢复验收 |
| 0f8331d47b21a24e7ed4542d42cffe4fab5e4dea | netif地址长度断言；本port固定6字节。当前受port约束，仍作为防御性回补候选 |
| 41a36098b39241d4a14d3c7f781ad91aad3ff3e7 | TCP timestamp大端修正；当前四个NDK ABI均为小端，不以此推断未来大端正确 |
| 0c3a93efe3f4276980114501d8fe442f95112c2b | timeout API改变；不是直接套用的兼容补丁。当前显式poll/next_timeout边界需保留合同 |
| 5d32779c1b70b604c1adde63bc7b1cf739090a24 | stats显示空指针检查；当前LWIP_STATS_DISPLAY=0 |
| 31d8988f、571c4625、e7ab7e07、4599f551、13d7ac33 | DHCP/ARP/AutoIP/IPv4相关；当前IPv4关闭。不是IPv6路径已修复的证据 |

回补前先建立失败测试；保留原始发布清单，逐文件记录原始/补后digest和上游commit，
更新完整性验证器，使其只允许精确列出的补丁，不接受任意vendor修改。
每个回补需要宿主sanitizer、四ABI、JNI及对应协议回归。本文没有声称上述漏洞
均已可利用或均有CVE，也没有把差异表当成完整渗透测试。

运行记录见[实施记录](execution-status.md)。当前阻止扩大集成的条件除真机门槛外，
还包括本表启用路径修复未闭环。

另有本地IPv6大UDP发送/重组修正，见[边界复现与修复](udp-large-datagram-gap.md)。
它不是上游回补/CVE结论：独立内核peer发现顺序大报文重组断言后，改为在16位负载
之外保存基础头并消除拼接的临时回绕。三个post-image及适用配置记录于BACKPORTS.md；
新增修改仍需独立安全评审及更完整扩展头/分配故障矩阵，不据当前回归宣称审计完成。

后续扩展头定向矩阵发现合法HBH重组后被误拒绝；已修正解析循环重启，并用首片
含完整UDP头的负例保持Fragment后非法HBH拒绝。当前覆盖40个长度/前置头/顺序
向量、4个非法HBH顺序向量及内核IPV6_HOPOPTS互通；没有覆盖所有未知选项动作、
路由头类型、畸形长度或分配故障，不将定向通过提升为完整安全结论。

TCP SYN分配故障注入又复现了timeout pool分配断言。新增本地TCP定时器可失败调度，
owner边界恢复或明确失败，精确post-image记录于BACKPORTS.md。SYN及实际handler
重排故障测试覆盖该分支；非TCP/冷启动定时器及完整审计仍未通过。此修正没有
上游提交身份或CVE认定，也不改变G0/G1和生产选择门槛。

### NDP 入口校验增量（2026-10-23）

针对 `nd6.c` 原有的 NA/NS/RA/Redirect 校验 TODO 增加本地防御：先检查完整 ND 选项链，
再允许其修改邻居/路由/目的缓存；补上 NA 的源地址与 Solicited 标志约束、DAD NS 约束，
以及 Redirect 的第一跳来源和目标地址约束。依据 [RFC 4861](https://www.rfc-editor.org/rfc/rfc4861.html)
相关规则建立真实 Ethernet/IPv6 帧用例。13 项定向 NDP/DAD CTest 全部通过；旧实现中
NA/NS/RA/Redirect 负例按预期触发断言失败。Linux sanitizer 与 Windows 宿主测试均为 73/73，
Android CI、四 ABI APK 与源码完整性检查通过；长时 fuzz 已在切换到本次源码后重跑，但尚未
达到预算。此增量不改变整体安全审计未通过、真机 G0/G1 未验证的结论。
