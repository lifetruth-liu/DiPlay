# 新端点API与独立内核peer

`lwip_kernel_echo --endpoints`使用公开dp_tcp_*/dp_udp_*接口，不启动原始echo回调。
服务通过非阻塞pump执行listen/accept/read/write/close、UDP来源/长度回显，以及
主动connect/status/shutdown_output。内核对端`lwip_kernel_peer`仅使用系统IPv6
socket，不包含/链接lwIP、帧构造或新端点实现。该模式验证native API，不替代Kotlin
两后端参数化合同或真实NCM/iPhone/ARM验收。

## 测试模型

- 被动服务TCP7000、UDP6000；独立内核客户端校验TCP 3×64KiB及UDP 0/1/1200/4096/
  8192字节、内容与来源。慢消费模式暂停读取10秒，要求1MiB发送者仍受背压，随后
  完整有序恢复。
- 独立内核服务监听9000，读取并校验native主动连接的16字节challenge及FIN，再回送
  全部内容并半关闭。native继续读取回复与EOF，校验地址、固定服务端口及动态端口
  非零后释放handle。
- 控制UDP6002只属于测试harness。peer服务先通过pipe确认listen就绪，再发启动
  请求并等待READY；最多32次、每次250ms接收超时。DAD/邻居准备期间不能假定单次
  UDP已交付。重复请求幂等，不创建多个候选。双方均以退出码/验证标记确认成功。
- 应用缓存固定8×4096字节TCP、一个最大UDP缓冲及少量控制/主动连接元数据，实际
  98664字节，编译期限制128KiB。原传输/观测对象另受512KiB限制；pump只在实际
  状态/字节进度后立即重试，等待交给原有端口poll/timer，不在栈回调内阻塞I/O。

## 运行

普通Linux可先执行`bash scripts/test_lwip_host.sh --sanitize`构建与回归，再在具备
CAP_NET_ADMIN、network namespace及TAP/veth支持的环境执行：

```sh
sudo bash scripts/test_lwip_kernel.sh build/lwip-host-linux tap endpoints
sudo bash scripts/test_lwip_kernel.sh build/lwip-host-linux veth endpoints
```

本次WSL的unshare被拒绝且无/dev/net/tun，未将上述Linux执行入口标为通过。实际内核
互通在任务自有root API28 x86_64 AVD执行。仅一次性模拟器用于此测试；产品不需要
root或TUN。脚本先检查qemu/root，并通过lwip_namespace_exec创建新netns，验证与父
namespace不同后才配置接口。默认不带endpoints仍运行旧echo模式。

用Android NDK toolchain配置CMake，ABI x86_64/API24。以下为POSIX宿主示例，
ANDROID_NDK_HOME指向已安装NDK（本次28.2.13676358）；Windows需选择匹配NDK的
Ninja/MinGW生成器。构建并复制到同一设备测试目录，设置二进制可执行：

```sh
cmake -S shared/src/main/jni/lwip_backend -B build/lwip-android-kernel -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-24
cmake --build build/lwip-android-kernel --target lwip_kernel_echo lwip_kernel_peer lwip_namespace_exec lwip_kernel_offloads -j2
adb -s emulator-5580 root
adb -s emulator-5580 wait-for-device
adb -s emulator-5580 shell mkdir -p /data/local/tmp/diplay-endpoints
adb -s emulator-5580 push build/lwip-android-kernel/lwip_kernel_echo build/lwip-android-kernel/lwip_kernel_peer build/lwip-android-kernel/lwip_namespace_exec build/lwip-android-kernel/lwip_kernel_offloads scripts/test_lwip_android_kernel.sh /data/local/tmp/diplay-endpoints/
adb -s emulator-5580 shell chmod 755 /data/local/tmp/diplay-endpoints/lwip_kernel_echo /data/local/tmp/diplay-endpoints/lwip_kernel_peer /data/local/tmp/diplay-endpoints/lwip_namespace_exec /data/local/tmp/diplay-endpoints/lwip_kernel_offloads
adb -s emulator-5580 shell sh /data/local/tmp/diplay-endpoints/test_lwip_android_kernel.sh tap basic endpoints
adb -s emulator-5580 shell sh /data/local/tmp/diplay-endpoints/test_lwip_android_kernel.sh veth slow endpoints
```

脚本参数是`tap|veth basic|slow|faults-1|faults-5 echo|endpoints`。故障配置沿用原有
有界wire queue，故障场景仅做TCP，UDP不承诺可靠重传。独立主动连接和半关闭仍需
同时通过。退出时终止/等待本次工具，netns释放；不修改父namespace。

basic后续增加内核IPV6_HOPOPTS向量：普通UDP0/1/1200/4096/8192/65487/65488/65527，
HBH UDP相同前七组及最大65519，共16次往返。八字节HBH从IPv6 payload预算扣除。
测试工具需CAP_NET_RAW设置该socket选项，已在现有root隔离namespace harness中
运行；产品应用不需要该权限。peer不包含或链接lwIP/测试帧编解码器。

ASan/UBSan构建另用目录、配置时添加`-DDIPLAY_SANITIZERS=ON`，复制对应NDK的
libclang_rt.asan-x86_64-android.so并设置LD_LIBRARY_PATH，再运行同一脚本。例如：

```sh
LD_LIBRARY_PATH=/data/local/tmp/diplay-endpoints-asan ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 sh /data/local/tmp/diplay-endpoints-asan/test_lwip_android_kernel.sh tap basic endpoints
```

上行在设备shell内执行。ASan、UBSan及lwIP池边界/一致性检查均开启；Android这轮
明确关闭LeakSanitizer，不宣称有LSan泄漏扫描。Linux宿主50项仍以ASan/UBSan及
默认detect_leaks=1回归。协议完成后context销毁屏障清理未完成关闭项。

## 发现与证据

占位端点服务的红测为内核TCP Connection refused。veth阶段观察到requested=0且
内核停在accept，修正测试控制握手为显式ACK/有限重试后通过，未以延长固定sleep掩盖。

真实内核TCP timestamp触发vendor tcp_in.c:2003的有符号左移UB；增加高位timestamp
及全部split的真实解析器回归，在Linux UBSan同样先失败。修正移位前u32_t转换，
保持原始vendor清单，固定累计post-image并单独标识本地修正，见
[回补记录](../../shared/src/main/jni/lwip_backend/BACKPORTS.md)。

修复后ASan/UBSan：TAP basic、veth slow、TAP faults-1、veth faults-5和旧echo TAP
basic均通过。slow完整恢复1048576字节，零窗口样本169；faults-1计数
247/2/245/21/2，faults-5为259/12/247/13/3（admitted/drop/delay/reorder/duplicate）。
这些是功能证据，heap_peak仅是core统计，不能当作全进程PSS或设备性能预算。
具体日志、APK身份及完整CI见[实施记录](execution-status.md)。

仍需Kotlin两后端完整合同、更多资源/关闭/畸形包矩阵、fuzz、真实NCM/iPhone/ARM及
长稳。此次发现修复不代表上游安全审计或G2/G3通过。
