# HANDOFF — Poke6 墨水屏刷新链路调查交接

> 生成时间：2026-09-25 · 面向下一个会话/接力的 LLM 或工程师
> **先读本文，再按需查 `ref.md`（5684 行完整日志）与 `WAVEFORM_TO_DISPLAY.md`（15 KB 提炼版）**
> **★ 2026-09-25 第二会话已执行 §6.1~§6.5 并推翻 3 条旧结论 —— 见 §4.6、§6、`ref.md` §9.3.24**
>
> **★★★ 2026-09-29 第六会话（最新）：主症状的根因已定位，并已用内核 patch 消除 —— 见 §13。**
> **§0 / §4.1 / §4.6 / §12 中"供电偶发成功窗口"「提高重试频率」「86% reset 来自 scrollingRefreshMode」
> 等论断已被实测推翻；务必先读 §13，再读其余章节。**
>
> **★★★ 2026-10-08 第七次实测：第二层根因也解决了（`wait all_lut_free` 超时 500ms→2000ms，v7G），
> 六个档位全部零 reset、A2/DU4 解锁、`update_err` 归零 —— 见 §13.10。**
>
> **★★★ 2026-11-06 第九会话（本轮）：设备「卡开机 → 落 recovery」的根因在
> `/onyxconfig` 里的 MMKV，与 boot/内核/BCB **无关**。`eac_app_pkg_set` 从
> **1507 B 缩到 10 B（只剩 `com.onyx`）**，且 `com.onyx` 的 theme/fallback 三键被删
> ⇒ 启动遍历唯一成员时无配置可用 ⇒ 卡死。恢复出厂擦 `/data` 但**不擦 `/onyxconfig`**，
> 所以修不好。配套工具：`tools/einkimg/`。**先读 §14。**

---

## 0. 30 秒速览

| 项 | 内容 |
|---|---|
| **项目** | BOOX Poke6 墨水屏刷新机制逆向 + launcher 刷新档位改造 |
| **设备** | 故障机 `6C7F0E64`（Poke6，TPS6518x 供电故障）；正常机 `6C1BF7D9` |
| **仓库** | `E-Ink-Launcher`，分支 `v0.x`，root = `C:\Users\root\Documents\eink\E-Ink-Launcher` |
| **当前提交** | `4effadb`（已推送）|
| **设备当前档位** | prefs `launcherRefreshMode=2` ⇒ `SCOPE_VALUES[2]=1` = **DU**（⚠️ 见 §3.2 索引语义）|
| **设备当前内核** | v6-A2 patched（`#79 Mon Mar 16 18:22:03`），刷在 `boot_b`，槽位 `_b` |
| **工作区根** | `C:\Users\root\Documents\eink`（`ref.md` 在此，**不在 git 仓库内**）|

**一句话现状**：黑屏问题已解决（v6 内核 patch 有效）；A2 档在故障机必然 reset（已知并规避）；
**§6.1~§6.5 已执行完毕**：双刷机制 A 被**证伪**（因果方向反了）、§6 的 17 个 dt 属性**全部不存在**、
**全屏 GC16 与 A2 走同一条 reset 路径**（⇒ 整屏清残影与避免 reset 物理不可兼得）。
**§9.3.25（第三会话）**：「重叠」在 **scope / EAC / A2 三维度实测全部无效**；**「降低操作频率」同日也被否证** ——
**§9.3.29⑧（第五会话）找到原因**：**86% 的 reset 来自全屏 `update[1]`（130 次中 112 次，83 次是 A2 全屏）**，
**不经 scope 通道**；来源 = **`scrollingRefreshMode=2` 滚动特判**（API 改不动，只能走 UI）。
**详见 §12 交接章节。**
**仅剩 1 个未解项**：重启的真正触发源（`bootreason=reboot`，已排除崩溃/watchdog，需常驻 events log）。

> ⚠️ **2026-09-29 第六会话修正（务必先读 §13）**：黑屏之外的主症状（刷新停滞 / 重置风暴 / 卡顿 / 日志风暴）
> 根因已定位并**已修复**。`tps6518x` 的 DISPLAY regulator enable 回调
> （Image 偏移 `0x5F4198`）判定"电源 OK"用的是 **`regmap_read(reg 0x0F) == 0xFA` 精确相等**
> （`0x5F4374` / `0x5F47EC` 两处 `cmp w8,#0xfa`），而故障机该寄存器
> **684 / 684 次回读恒为 `0xBA`**（与 `0xFA` 只差 bit6）⇒ **判据结构性不可满足**
> ⇒ `regulator_enable(DISPLAY)` **恒返回 `-ETIMEDOUT`** ⇒
> 每次 powerup 走 1.6 s 失败重试 + `regmap_write(reg 1, 0)` **反复拉低电源轨**
> ⇒ 波形中止（LUT 卡在 `frame_cur` 1~2）⇒ `wait all_lut_free` 超时 ⇒ reset 风暴。
>
> **已刷入 v7A 内核**（只改这一处判据，其余 v6-A2 patch 不动）。实测（受控负载剖面 ×6 `TestWaveform(2)`）：
> **reset 1→0、卡住的 LUT 3→0、`Reg PowerGood`/`Retry`/`Unable to enable DISPLAY`/`epdc power error`
> /`wait all_lut_free timeout` 全部归零**；dmesg 由上万行降到 319 行。
> §12.1 的「来源 = `scrollingRefreshMode=2`」与「提高重试频率」两条均已被实验否证（详见 §13.4）。
> **回滚**：`dd if=/sdcard/boot_patched_a2_v6.img of=/dev/block/by-name/boot_b bs=4M` + 重启。

---

## 1. 环境与工具约定

### 1.1 可用/不可用工具

| 可用 | 不可用 |
|---|---|
| `cargo` `git` `node` `npm` `python`(3.12) `rustc` | `docker` `go` `make` `python3` `rg` |

- **shell = PowerShell**（须用 5.1 兼容语法）
- `adb.exe` 在工作区根：`C:\Users\root\Documents\eink\adb.exe`
- 设备有 **Magisk root**（`su -c` 可用，`id` → `uid=0 context=u:r:magisk:s0`）

### 1.2 ★ 用户明确规则（务必遵守）

| # | 规则 |
|---|---|
| 1 | **不要本地编译** —— gradle 因证书吊销检查失败（`CRYPT_E_NO_REVOCATION_CHECK`）。改静态自检 + 推 CI |
| 2 | **灰度表不用管** —— 不做目视灰度验证 |
| 3 | **per-app 重启丢失 = 接受**（scope 是运行时状态）|
| 4 | **`PerAppRefreshHelper.java` 保留**（不删）|
| 5 | 术语：「全局刷新」= **全屏刷新**（`update[1]`），**不是** launcher 设置项 |

### 1.3 ★★ 工具踩坑（本会话反复被绊，务必先读）

| 坑 | 现象 | 正确做法 |
|---|---|---|
| **脚本内 `dmesg` 失败** | 脚本里 `dmesg > f` 得空文件或 `klogctl: Permission denied`（同命令 `su -c "dmesg"` 直连正常）| **先 `adb shell su -c "dmesg > /data/local/tmp/x.txt"` 导出，再让脚本只读该文件** |
| **`echo > file` 被 PowerShell 截获** | `su -c "echo xxx > file"` 时 `>` 在宿主机执行，脚本内容损坏 | **用 `write_file` 写好脚本再 `adb push`** |
| **脚本内 `find`/`grep` 无输出** | SELinux domain 差异 | 同上：导出到文件再处理，或逐条 `adb shell su -c "..."` |
| **CRLF 污染** | 推到设备的 `.sh` 带 `\r` 执行报错 | `sed -i "s/\r$//" <file>`（或用 `tr -d '\r'`）|
| **`input swipe` 权限** | 非 root 注入报 `INJECT_EVENTS permission` | 必须 `su -c "input ..."` |
| **PowerShell 引号嵌套** | 复杂 `su -c "..."` 里的引号被展开 | 写成 `.sh` 文件 push 过去 |
| **adb 会掉线** | `device not found` / `unknown host service` | `adb reconnect` 或等几秒重试 |
| ★★ **掉线后可能连上【别的设备】** | 2026-09-25 实测：Poke6 断开后，adb 上出现 `182QGFZD225UX`（**MEIZU 18s**），`device not found` 之后所有命令会打到那台机器上 | **每次重连后先确认**：`adb devices -l` + `getprop ro.product.model` 必须是 **Poke6**；**所有命令显式带 `-s 6C7F0E64`** |
| **session temp 目录会变** | `$env:TEMP\eink_diag` 路径失效 | 用绝对路径 `C:\Users\root\AppData\Local\Temp\eink_diag` |
| **长实验被 PowerShell timeout 打断** | 脚本中途 `Terminated`，**末尾的还原步骤可能未执行** → 设备留下非默认状态（如 `cut_frame_num` 非 0）| 用 `su -c nohup sh /data/local/tmp/x.sh > /data/local/tmp/x.log 2>&1 &` 后台跑，另起命令看日志；**事后必须核对设备状态** |
| **shell 变量包裹整条命令** | `CMD="CLASSPATH=… app_process …"; $CMD` → `CLASSPATH=…: inaccessible or not found` | 不要把 `VAR=val cmd` 塞进变量再展开；写全或用 `env`。⚠️ **已连着踩 3 次**（§9.3.25⑪ → §9.3.27⑤ → 本轮）—— **脚本里每处都写完整命令**，别为省字用变量 |
| ★★ **读抓取文件前先核对时间戳** | 2026-09-29 曾把 `/data/local/tmp/live_all.log`（**09-28 01:50 就停了**）当成"刚才的重启证据"分析，结论全错 | **先比对文件 mtime 与设备当前时间**（`ls -la` + `date`）；不一致即说明抓取已中断，需重新部署 |
| ★★ **A/B 对照必须控制变量** | dither 对比时用「翻页后截图」，内容与设置同时变 ⇒ 得出「PNG 大 2.4 倍」的错误结论 | 同一页面**只改待测变量**；本轮正解是「不翻页、各连拍 2 张比字节数」|
| ★★ **`repaintEverything(V)` 前需有内容变化** | 屏幕内容无变化时调 `repaintEverything` 可能**无事可做**（实测多组 `dF=0`、零 `update_to_display`）⇒ 误判"该值无效" | 每组**先翻页改变内容**再测；判据用 `logcat \| grep -c 'update_mode = 1'` |
| **PowerShell 内联 shell 语法** | `for f in …; do …; done` / `\$f` → `syntax error: unexpected 'do'`、`\$f` 被本地展开成 `C:\sys\…` | 整脚本 `push` 执行；或改用 PowerShell `foreach` 逐条调 adb |
| ★ **跨时间窗口对比（本轮踩坑）** | 把「A 时刻的快翻」与「B 时刻的 idle」相比 ⇒ 得出「降低操作频率有效」的**错误结论**（实际是**硬件状态变了**，不是节奏变了）| 对照组必须**在同一时间窗口内交替进行**（顺序打乱）；任何跨时刻得出的结论，都要做**反向顺序复验**（本轮 `setUpdListSize` 与「降低操作频率」两条结论都是这样被推翻的）|
| ★★ **adb 起不来：`cannot open …\Temp\adb.log: Permission denied`** | 2026-10-09 实测：会话沙箱把**派生进程的写操作限制在工作区内**，而 `%LOCALAPPDATA%\Temp` 在工作区**之外** ⇒ adb（客户端与守护进程）无法在那里创建日志文件，报 `failed to start daemon`。⚠️ **别去修 ACL**：`icacls`/`Get-Acl` 看 `%TEMP%` 与 `adb.log` 都是 `root:(F)`、无拒绝项，pwsh/cmd 也能在 `%TEMP%` 里建文件 —— 这属于「**工作区之外的写**」这一**预期内**的拒绝，不是 ACL 故障（ACL 诊断技能也把这类归为"应解释、不必修"）| **把 TEMP/TMP 指到工作区内目录再调 adb**：<br>`$env:TEMP='C:\Users\root\Documents\eink\.adbtmp'; $env:TMP=$env:TEMP; .\adb.exe devices -l`<br>⚠️ 每次 `pwsh` 调用都是新进程 ⇒ **每条** adb 命令都要带上。同因，其它往 `%TEMP%` 写的工具（gradle 等）也可能中招 |

### 1.4 ★ Git 推送

```powershell
cd C:\Users\root\Documents\eink\E-Ink-Launcher
git push origin v0.x        # 代理可用时（当前 127.0.0.1:7890 在运行）
# 若代理挂掉、直连可用时：git -c http.proxy= -c https.proxy= push origin v0.x
```

- ✅ **`origin` URL 已是干净的**（2026-10-08 复核：`https://github.com/237389749/E-Ink-Launcher.git`，**无内嵌 token**）。
  鉴权走 `credential.helper = manager`（GCM）。工作区根那个 `ghp_….txt` 是 **0 字节空文件**，不用管。
- 网络状态会变：曾出现"直连通、代理不通"，也出现"代理通、直连不通"。**先测再推**：
  ```powershell
  Test-NetConnection github.com -Port 443 -InformationLevel Quiet
  Test-NetConnection 127.0.0.1 -Port 7890 -InformationLevel Quiet
  ```
- ★★ **2026-10-08 实测：报 `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`
  时，先怀疑【运行环境】而不是凭据**。当时代理 7890 已断、直连 TCP 通，但
  `git push` 与 Git 自带 `curl` 都在 **TLS 握手阶段**就失败、**GCM 根本不弹窗**
  （因为根本没走到 401 认证挑战）。把执行环境的文件沙箱放宽到 full-access 后，**同一条命令直接成功**
  ⇒ 这是**沙箱拿不到用户的加密凭据库**，不是 token/账号问题。
  **不要把这种现象误判成"没配凭据"去反复折腾 `credential.helper`。**
  判断顺序：① `Test-NetConnection` 看 TCP；② 报错在 TLS 层还是 401；
  ③ 若在 TLS 层且 curl 同样报错 ⇒ 是环境/沙箱，换环境即可。
- 推送成功后独立核验（不依赖 `git status` 的缓存）：
  ```powershell
  git -c http.proxy= -c https.proxy= ls-remote origin refs/heads/v0.x   # 应与 git rev-parse HEAD 相同
  ```

---

## 2. 文档地图

| 文档 | 位置 | 内容 | 何时读 |
|---|---|---|---|
| **HANDOFF.md** | 仓库根 | 本文（交接总纲）| **首先** |
| **WAVEFORM_TO_DISPLAY.md** | 仓库根 | 从波形到显示完整链路（15 KB，已提炼 + 校验）| 要理解波形/灰阶/帧数机制 |
| **ref.md** | 工作区根（**仓库外**）| 项目完整日志 5684 行，§9.3 是核心 | 要查具体推导过程/历史结论 |
| **WBF_FRAMES.md** | 仓库根 | 8×14 帧数矩阵（较旧，已被 WAVEFORM_TO_DISPLAY 覆盖）| 仅供参考 |
| **boot-patches/README.md** | 仓库根 | 早期内核 patch 包说明 | 要刷 v3/v4 老版本 |
| **BOOT-MODE-POSTMORTEM.md** | 仓库根 | 四种启动模式实测、两个根因、检查清单 | 动 boot/recovery/OTA 前**必读** |
| **RECOVERY-EDL.md** | 仓库根 | 变砖后只写 2 个分区（misc + recovery_b） | 设备卡 logo / 无 USB 时 |
| **DEBLOAT-AND-BOOTMODES.md** | 仓库根 | 预装清理 + ABL/recovery 静态分析 | 要清预装 / 理解 fastboot 能力 |

**ref.md 阅读提示**：§9.3 开头有「导读框」，列明 4 条最优结论 + **已作废的中间论断**。
  顺序为 `9.3.1 → 9.3.21 → 9.3.23`（21 与 23 已在末尾）。

---

## 3. 当前设备状态（2026-09-25 02:3x 采集）

### 3.1 身份

```
model   = Poke6
serial  = 6C7F0E64
slot    = _b
kernel  = 4.19.157-perf-g5b9f6edaa05e-dirty #79 SMP PREEMPT Mon Mar 16 18:22:03 CST 2026
uptime  = ~32700 s（约 9 小时，未重启）
```

### 3.2 配置

| 项 | 值 |
|---|---|
| `launcherRefreshMode` | **2** → 新表 index 2 = **DU(1)**（2026-09-25 实测确认生效值）|
| `EAC` 逻辑值 | ★ **不再由 launcher 定死**（2026-10-09 移除 `applyFixedEac`，见 §5.1）；设备上残留的 top app/fallback `updateMode=3` 建议跑一次 `official 0` 恢复出厂值 |
| scope（运行时） | 由 launcher `apply()` 设置 |
| 内核 | **v7H**（2026-10-08 刷入；修复两层根因，见 §13）|

> ⚠️ **★ 索引语义（务必按这个读）**：
> ```
> SCOPE_VALUES = {-1, -1, 1, 2, 4}          ← 2026-10-08 起 5 档（DU4 已移除）
> MODE_NAMES   = {None, NORMAL, DU, GC16, A2}
>                              ↑index2      ↑index3
> ```
> ⇒ **index 2 = DU，index 3 = GC16**（§0 速览表旧版「index 2 = GC16」是**错的**，已更正）。
> ⇒ 旧表 index 4 = A2（**不变**）、index 5 = DU4（**已移除**，仅可能影响"当年选过 DU4"的存档）。
> 实测证据（2026-09-25 第二会话）：`refresh_mode.log` 末次切档为 `apply: globalScope DU -> ui=1`，
> 且 `logcat | grep update_to_display` 显示 `waveform_mode = 1`（= DU）⇒ **实际生效 = DU**。
> **判断实际生效档位请用 `fastModeIndex` + 实测波形（`update_to_display` 日志），不要只看 prefs。**

### 3.3 健康指标（20 s 窗口）

| 指标 | 值 | 说明 |
|---|---|---|
| `reset cause` | **0** | 空闲时不触发 |
| `epdc power error` | **9** | ★ **硬件故障持续** |
| `TPS6518x` | **12** | ★ 同上 |
| `all_lut_free timeout` | 0 | 空闲时无 |
| `waveform_desc is NULL` | **0** | v6 patch 有效抑制 |
| **`update_err`** | **1** | ★ **恒为 1**（上次失败未恢复的粘滞位）|
| `frame[a:b:c]` | `32738:32737:32738` | a≠c ⇒ 有活动 |

> ⚠️ **上表是 2026-09-25 的旧读数，已被 §13/§13.10 的修复取代。**
> **2026-10-08 刷入 v7H 后的实测**（含 13.5 min 混合负载 soak）：
> `reset cause 0` / `wait all_lut_free timeout 0` / `wait lut_free timeout 0` /
> `epdc power error 0` / `waveform_desc is NULL 0` / `cant get free 0` / `Unable to enable 0` /
> **`update_err = 0`**（历史上"恒为 1"的粘滞位，现在归零）。
> ⚠️ 但 **`Reg PowerGood` 仍恒为 `0xBA`** —— 硬件 PG 位确实不置起，只是内核 patch 后
> 不再让它决定成败（见 §13.6 边界说明）。

### 3.4 内核与镜像

| 项 | 值 |
|---|---|
| 当前内核 | ★ **v7H**（2026-10-08 刷入 boot_b）：v6-A2 全部 9 处 patch + `0x5F43B0` 判成功（保留 `Reg PowerGood` 读数）+ `wait all_lut_free` 恢复 stock 5000 ms。见 §13 / §13.10 |
| 仓库镜像 | `boot_patched_a2_v6.img` md5 `c62600dd4bb3a1abcba0a0c0caaf75c6` |
| | `boot_patched_du_v6.img` md5 `43a4e312aa97e0fa6febd96f7ff00e87` |
| | `boot_v7G.img` `96e2aec0b67a7c23b4d5ec3d4b52eb9c` / `boot_v7H.img` `39ec0cb43213803d9ac4b79e7c987fc7` |
| 设备备份 | `/sdcard/boot_b_pre_v5.img`、`boot_b_pre_v6.img`、`boot_b_du_v6.img`、`boot_patched_a2_v6.img`、`boot_v7A/v7F/v7G/v7H.img` |

**回滚方式**：`dd if=/sdcard/<备份>.img of=/dev/block/by-name/boot_b bs=4M` + 重启

### 3.5 设备端工具（`/data/local/tmp/`）

| 工具 | 作用 |
|---|---|
| `sscope.dex` | `io.onyx.SetScope <UI值>` — 设全局 scope |
| `cscope.dex` | `io.onyx.ClearScope` — 清 scope |
| `tw.dex` | `io.onyx.TestWaveform <值> [轮数]` — `repaintEverything(值)` 受控触发 |
| `checkmode.dex` | `io.onyx.CheckMode` — 读 `appScopeRefreshMode` + `fastModeIndex` |
| `dth.dex` / `ldu.dex` / `ha.dex` | 主题 dump / 写 app config / 等（详见 ref §6）|
| `test_mode.sh` | 改 prefs + kill launcher（快速切档）|
| `full_matrix.sh` / `mode_matrix.sh` / `lum_probe.sh` / `verify_modes.sh` | 全模式矩阵测试 |

**调用范例**：
```bash
adb shell su -c "CLASSPATH=/data/local/tmp/sscope.dex app_process /system/bin io.onyx.SetScope 2"
adb shell su -c "CLASSPATH=/data/local/tmp/tw.dex app_process /system/bin io.onyx.TestWaveform 2 1"
adb shell su -c "CLASSPATH=/data/local/tmp/checkmode.dex app_process /system/bin io.onyx.CheckMode"
```

---

## 4. 已确立的结论（可直接引用）

### 4.1 硬件根因（未变）

```
TPS6518x 供电芯片故障
  → power good 位永不置起（Reg PowerGood 回读恒 [0xf] 0xBA，无变化）
  → 每次 powerup 需重试 2 次，每次 ~1.6s i2c 超时（err = 0xffffff92 = -ETIMEDOUT）
  → 波形无法完整执行 / 刷新被拖慢
```

**这是硬件故障，软件只能缓解，不能根治。**

### 4.2 波形库（Poke6 专属，已三重验证）

| sg 槽 | 标准名 | bpp | 帧数 | state 覆盖 | 驱动率 | **同色态驱动** | 可区分灰阶 |
|---|---|---|---|---|---|---|---|
| `[0]` | INIT | — | 113 | 193/256 | 0.743 | **0.750** | 3 |
| `[1]` | DU | 1bpp | **22** | 42/256 | 0.026 | 0.023 | 5 |
| `[2]` | GC16 | 4bpp | **38** | 243/256 | 0.219 | 0.229 | **16** |
| `[3]` | GC16_FAST | 4bpp | 38 | 244/256 | 0.221 | 0.240 | 16 |
| `[4]` | A2（名）| — | 38 | 244/256 | 0.221 | 0.240 | 16 |
| `[5]` | GL16 | 4bpp | 38 | 244/256 | 0.221 | 0.240 | 16 |
| `[6]` | GL16_FAST（名）| 4bpp | **5** | 18/256 | 0.004 | 0.009 | 3 |
| `[7]` | DU4 | 2bpp | **24** | 84/256 | 0.056 | 0.062 | 7 |

- **`mode3 == mode4 == mode5` 逐字节相同**（厂商把 GC16 复制进三列）
- **`mode8`–`modeB` 缺失** ⇒ **Poke6 无 REAGL 族** ⇒ "高灰阶 + 不闪"**物理不可得**
- 帧数受**温度**影响：0°C 时 GC16 = 131 帧；24~38°C 恒定 38

### 4.3 模式 → 槽 → update（设备实测，6/6 复现）

| UI 值 | 名称 | → sg 槽 | frame_total | `update` | 故障机 |
|---|---|---|---|---|---|
| **1** | DU | `[1]` | **22** | `[0]` 局部 | ✅ |
| **2** | GC16 | `[2]` | **38** | `[0]` 局部 | ✅ |
| **3** | GC4 | `[2]` | 38 | `[0]` | ✅ |
| **4** | **A2** | **`[6]`** | **5** | **`[1]` 全屏** | ❌ **reset 循环** |
| 6 | REAGL | `[2]` | 38 | `[0]` | ✅（等价 GC16）|
| **8** | DU4 | **`[2]` 回落** | 38 | `[0]` | ⚠️ 等价 GC16 |
| 9 | REAGL_PLUS | `[2]` | 38 | `[0]` | ✅（等价 GC16）|

### 4.4 ★ 分界线：`update[0]` 局部 vs `update[1]` 全屏

- **不是**帧数，**不是** flags 的 `FULL(32)` 位（实测 `98` 带 FULL 仍是局部）
- 由 **native SurfaceFlinger 依 waveform mode 判定**；kernel 仅透传
- **只有 A2 族（mode 6 槽）走全屏**
- 全屏在故障机必然 `wait all_lut_free` 超时 → reset 循环

### 4.5 残影机制

| 波形 | 同色态驱动（闪烁度）| 清残影能力 |
|---|---|---|
| INIT | 0.750 | 最强（仅初始化）|
| **GC16 族** | **0.229~0.240** | ✅ **唯一** |
| DU4 | 0.062 | ⚠️ 弱 |
| DU | 0.023 | ❌ 差分 |
| A2 | 0.009 | ❌ 差分 |

**规律**：`bpp ↑ → 覆盖 ↑ → 同色态驱动 ↑ → 越闪、帧数越多`（E Ink 物理必然）

**关键补充**：即使选 GC16，`FULL` 位不生效 ⇒ **只清"变化区域"残影**。
**整屏清残影只有 `repaintEverything()`**，而 launcher **仅在切档时调一次**，日常无周期/手动入口。

### 4.6 ★ 两个未解现象（都源于硬件）

> ⚠️ **本节 2026-09-25 第二会话【重大修正】** —— 现象 A 的原机制推断已被**证伪**，
> 详见 §4.6.A 与 `ref.md` §9.3.24①。

#### 现象 A：刷新"停滞 → 突增"（用户称"一次性刷新两次/双刷"）

**实测数据**（20 ms 采样 `frame[]`）：
```
单次翻页:  0~200ms +36帧 → 停 2.0s → 2240ms +109帧
不操作:    0~380ms +38帧 → 停 2.0s → 2380ms +36帧
停 launcher: 仍有同样模式（已 force-stop 验证）
```
**已排除**：launcher、刷新模式、用户操作 —— **全部无关**（这三条仍然成立）。

**★ 原机制 A（「双刷 = powerup 重试周期」）—— 【已证伪】**：

| 证据 | 数据 | 结论 |
|---|---|---|
| 时序方向 | `Reg Enable` 全部出现在 frame 推进段**开始之后**（lag +0.01 ~ +10.43s） | ❌ 不是「powerup 驱动刷新」，而是**「刷新触发 powerup」** |
| `pending_cnt` | `=2` 占 3.2~3.7% 采样，与推进段逐段对齐，末期回 0 | ❌ **不是"堆积"**，是双缓冲流水线正常态 |
| idle vs 操作 | 纯 idle 60s 内 3 组自发刷新（`+19~22` 帧/0.3s）；swipe 后 1 组**幅度相同** | ❌ 二者**无差别** |
| 空档期 | `epdc_active_luts` 归零，两组间零 reset / power error | **EPDC 完全空闲** |

⇒ **真正的双刷机制未定**。每组 `+17~22` 帧 = 一个 **DU 波形**（不是 GC16 的 38）。
⇒ **新假设（未验证）**：**上层（SF / Onyx 框架）周期性自发提交同幅刷新**。
   下一步应查：EAC 的 `gcInterval`（输入计数触发，§9.3.20）/ debouncer / 系统时钟重绘源。

#### 现象 B：偶发重启

- `bootreason = reboot`（**软件主动重启，非 panic**）
- dropbox 有 **944 个 `system_server_wtf`**，栈恒定：
  ```
  Wakeable tag:WindowManager from [android] is forbidden
      at WindowManagerService.setHoldScreenLocked
      at RootWindowContainer.performSurfacePlacementNoTrace
  ```
- **★ 本次推翻了 ref §12.7 的两处旧结论**：
  1. ❌ "WTF 刷屏 → Watchdog 60s → reboot" —— WTF 时间线**无中断**（重启后继续累积），不符合 Watchdog 特征
  2. ❌ "根因是切档批量写 EAC" —— 爆发窗口（23:50/00:10）**launcher 无任何切档记录**，且已改 scope-only
- **⇒ 触发源未定**（候选：USB 投屏切换、SystemUI 窗口动画）

---

## 5. 代码现状

### 5.1 launcher（`E-Ink-Launcher/app/src/main/java/cn/modificator/launcher/`）

**`RefreshModeHelper.java`**

```java
private static final int[] SCOPE_VALUES = {-1, -1, 1, 2, 4};          // 5 档（DU4 已移除）
public static final String[] MODE_NAMES = {None, NORMAL, DU, GC16, A2};
// LABELS: None / NORMAL / DU—纯黑白·22帧·1bpp / GC16—16级灰·38帧·4bpp / A2—最快·5帧·无灰阶
public static final int UI_GC16_FULL = 98;                            // 整屏清残影用
public static final String CLEAR_GHOSTING_LABEL = "清残影 — 整屏全刷（GC16·38帧）";
```

- `apply(int)` = 切档主入口：`byPass(10)` → 设 scope → `byPass(0)` → `fullRefreshScreen()`
- `applyPerApp(pkg, idx)` — per-app 走 scope 通道
- `fullRefreshScreen()` — 反射调**无参** `repaintEverything()`（只重画变化区域，带不上 FULL 位）
- `clearGhosting()`（2026-10-08 新增）— 反射调 `repaintEverything(98)` = GC16|WAIT|FULL **整屏清残影**
  （与通知栏磁贴 / `applyGCOnce()` / gcInterval 同一条路；内核修好后实测 0 reset）
- ~~`applyFixedEac()`~~ — **2026-10-09 已移除**（见下）

**`Launcher.java`**
- `onCreate` 里：`RefreshModeHelper.init` → `apply(mode)`（**只走 scope**；原先还会另起线程 `applyFixedEac()` 再补一次 `apply(mode)`）

**`SettingFragment.java`**
- 刷新模式弹窗 = `LABELS`（5 档）+ 末尾追加 `CLEAR_GHOSTING_LABEL` 一项；
  动作项**不放进 `LABELS`**，因为该数组同时被长按图标的 per-app 菜单复用

**关键设计决策**：
1. **切档只走 scope**，不批量写 EAC（避免窗口重建风暴 → system_server WTF → Watchdog）
2. ★ **EAC 不再定死（2026-10-09 移除 `applyFixedEac`）**：原三条理由两条失效、一条冗余 ——
   子路径波形恒为 `toEpdMode(0)`=AUTO【与 EAC mode 无关，§9.3.6②】；出厂默认 `updateMode=0`
   本就在白名单 `{0,3,5}` 内（§9.3.29⑤ 实测 920 份全是 0）⇒ 防抖/周期 GC 默认即启用；
   定死 3 的唯一净增量只是多开"滚动/触摸瞬态更新"。代价却是：每次启动要 root、
   持久写系统 MMKV（top app + fallback）、且会覆盖全局 scope 需补救。
   **`GlobalEacRefreshHelper` 本体保留为手动工具**；设备侧建议一次性跑 `official 0` 恢复出厂值。
3. EAC 实测**不影响**是否出全屏 reset（§9.3.17 实验 1 vs 2 同结果）
4. ★ **DU4(2312) 已从档位表移除**（2026-10-08，仅删末尾项 ⇒ 已保存的档位 index 0~4 语义不变）
5. ★★ **2026-10-09 修正 ref §9.3.6②「EAC mode 决定防抖/周期 GC 开关」的表述** —— 源码实测
   （`res/eink-framework/.../optimization/`）：**有三条路，判据各不相同**，别再用"某个字段"一概而论：
   | 路径 | 判据来自 | 受 per-app `updateMode` 字段影响？ |
   |---|---|---|
   | `AccessibilityHelper.handleMotionWithSFDebouncer:132,148`（**主输入路径**）| **`EInkHelper.getAppScopeRefreshMode()`**（设备级，本机读数恒为 **2**）| ❌ **不受影响** |
   | `TabletEACRefreshImpl:188` / `OnyxBypassManager:192` | `caculateRefreshConfig(rc).getMode()` | ⚠️ **被 `refreshModeIndex` 覆盖**（`EACBaseRefreshImpl:61-73`：idx≠NONE 且系统档存在时直接返回系统数据，忽略字段）|
   | `EACBaseRefreshImpl.increaseRepaintCount:103` | **原始 `rc.getUpdateMode()`** | ✅ 受影响 |
   ⇒ 结论：`updateMode` 字段只在第三条路是硬判据；第一条路看**设备级**的 scope 读数。
   ⇒ 而把 theme 写成 **`refreshModeIndex=NONE` + `updateMode=0`** 能同时让第二、三条路落回白名单
   （NONE ⇒ 直通字段 ⇒ mode 0 ∈ {0,3,5}）。**改字段但不改 idx 只修第三条路。**
6. ★★ **2026-10-09 一次性清理：20 个 app 的 EAC theme 恢复为 NONE + updateMode=0**
   起因：老版本 launcher「逐档写 EAC」留下 18 个 app `updateMode=2`（=逻辑 A2，非白名单）
   + `com.bilibili.comic` 的 `3`（applyFixedEac 残留）+ `com.legado.app` 的 `33554436`（UI 标志位误入字段）。
   工具：`GlobalEacRefreshHelper set <pkgCsv> 0`（save-only，自动备份到 `/data/local/tmp/eac_bak/<pkg>.json`）；
   **`com.qidian.QDReader`（用户自设 um=5）刻意保留**。核验方式：**必须用系统 API 读回**（`dth.dex` =
   `io.onyx.DumpThemes <pkg>`），**不要用 `strings`+「最后一次出现」**—— 实测那是假象（见 §5.1-5 的教训）。
   ⚠️ 生效需 OECService 重载 = **重启一次**。
7. ★★ **2026-10-09 更正（并撤销本文档先前"fallback key 是惰性的"结论）**：
   **只写 theme 不持久。** 实测：`set <20 pkgs> 0` 后**重启前**用系统 API 读回确认三个 theme 都是
   `NONE/0`；**重启（OECService 重载）后 themeType 3 又回到旧值**（legado/canta `refresh_mode_3 um=2`、
   bilibili `um=3`），themeType 1/2 保持 NONE/0 ⇒ **净变化 ≈ 0**。
   **原因（源码）**：`EACAppThemeManager.DEFAULT_ACTIVE_THEME = 3`，且
   `getActiveThemeType(pkg) = EACAppTheme.getInt(prefix+pkg, 3)` ⇒ **生效的是 themeType 3**；
   `getTheme()` 走 `EACThemeFactory.loadThemeOrCreate(...)`（"or create" —— 缺失/无效时
   **由默认 app 配置重建**），而 `eac_app_<pkg>` / `eac_default_app_config<pkg>` 里仍是旧值。
   ⇒ 即那两个 key **不是惰性的，而是重载时重建 active theme 的来源**。
   ⇒ **要持久修复必须写默认 app 配置**（现有 `GlobalEacRefreshHelper` 只写 theme，需扩展）。
   ⇒ 本次清理**未产生持久效果**，但**也没有副作用**（净状态 = 清理前），无需回滚。
8. ★★ **`appScopeRefreshMode` 恒读 2 的真正原因（2026-10-09 定论）**：不是"读数不可信"，而是
   `OECService.getAppScopeRefreshMode()` → `getRefreshConfig(getCurrentTopComponent())`；
   在 **shell / 无前台**时 `getCurrentTopComponent()` 为 `null` → `ensureAppConfig(null)` →
   **`EACAppConfig.createDummyConfig()`** ⇒ 读的是**占位配置**，故恒为 2。
   ⇒ 判读实际生效档位**不要依赖这个 getter**，用 `fastModeIndex` + `dumpsys activity` 看真实前台。
   ⇒ 也因此：`setAppScopeRefreshMode(0)`（= `official 0`）**只写 updateMode 字段**，而
   `caculateRefreshConfig` 在 idx≠NONE 时会**忽略该字段**并返回系统档数据 —— 想真正落回白名单，
   **必须写 `refreshModeIndex=NONE`**（本次 20 个 app 的清理正是这么做的）。
9. ★★★ **`refresh_mode_N` → 有效 mode 实测映射（2026-10-09，`SetDefCfg sysidx` 读 `SysUIConfig`）**：
   | idx | 有效 mode | 在白名单 `{0,3,5}`？ |
   |---|---|---|
   | `refresh_mode_1` | 5 (REGAL_PLUS) | ✅ |
   | `refresh_mode_2` | **3 (REGAL)** | ✅ |
   | `refresh_mode_3` | **2 (A2)** | ❌ |
   | `refresh_mode_4` | 0 (NORMAL) | ✅ |
   ⇒ **老版本 launcher 逐档写 EAC 留下的"`um=2`"只是表象**：真正把那些 app 挡在白名单外的是
   **`refresh_mode_3`（→ mode 2）**。判据必须按 **idx** 算，不能只看 `updateMode` 字段。
10. ★★★ **2026-10-09 清理结果（19 个第三方 app；已完成并实测）**：
   - 工具：`SetDefCfg`（`_scratch_gs/SetDefCfg.java`；反射 `EACAppConfig.loadSavedConfig/save` +
     `loadDefaultConfig/saveDefaultConfig`，**只改已存在的键**，先备份到 `eac_bak/<pkg>.{app,defcfg}.json`）
     —— 与 CI 版 `GlobalEacRefreshHelper defcfg` 等价。
   - ⚠️ **类名坑**：真实类名是 **`android.onyx.optimization.data.v2.EACAppConfig`**；反编译源码里的
     `data.p008v2` 是 jadx 为避免与 `data.v1` 重名而改的（证据：其内部匿名类注释
     "from class: ...data.v2.EACAppConfig.1"）。**用 `p008v2` 会 ClassNotFoundException**
     （首次运行时 `app_process` 被 SIGKILL=137，就是因此）。
   - **结果：18/19 的生效 theme（themeType 3）落到 `refresh_mode_2`（mode 3，白名单 ✅）**；
     仅 **`com.legado.app.release` 仍是 `refresh_mode_3`（mode 2，非白名单 ❌）**，待单独处理。
     `com.qidian.QDReader`（用户自设 mode 5）与 launcher 自身**未被扰动** ✓。
   - 生效需重启（OECService 重载）；备份共 48 份（theme + app/defcfg），可回滚。
11. ★★★ **2026-10-11 定论：旧版 launcher 把"波形/UI 域"的值写进了 EAC"逻辑域"字段 = 刷新引擎永久失效的根因。**
   - **时间线（git 实证）**：`605d356` … `c4bef37`（2026-08-30 ~ 09-05 发布）的
     `MODE_VALUES = { UI_NONE, …, **UI_REGAL_PLUS_MODE(9)**, … }`；这些值经 `GlobalEacRefreshHelper set`
     写进 `refreshConfig.updateMode`。09-10 的 `0bf8ed5` 已改用逻辑域 `LM_*`（新伤害止住），
     **但已落盘的 9 不会被任何机制清掉**。
   - **为什么是永久性**：固件 `SysUIConfig.getRefreshMigrateMap(第三方) = {0→3, 1→2, 2→2, 3→5, 4→2}`
     —— **9 不在表里** ⇒ `ValidateAppConfigAction.validateRefreshConfig` 永不改写它（它只迁移表内的值）。
     而 `caculateRefreshConfig` 返回 mode=9 ⇒ `setAppRefreshModeImpl` 走
     `dumpError("Incorrect target system refresh mode") + return`、`applyUpdateMode` 的 switch 无 case 9
     ⇒ **通知栏面板/全局档点了完全没反应**（`OnyxRefreshModeController.updateDialogCurrentSelection`
     只认 {0,1,2,4}，读到 9 时**一个单选钮都不勾**）。
   - **值域对照**：逻辑域 `Constant.UPDATE_MODE_*`（DU=1/A2=2/REGAL_PLUS=5）；
     波形/UI 域 `ViewUpdateHelper.EINK_WAVEFORM_MODE_*` 与 `UI_*_MODE`（REGAL_PLUS=**9**、GC16|FULL|WAIT=98）。
12. ★★★ **修复/还原方案（已实测于正常机 6C1BF7D9）**：把 config 写成
   **`refreshModeIndex="NONE"` + `updateMode=5` + `refreshModeAlias=""`**。
   - 为什么必须是这三个值：
     · `NONE` 是引擎能生效的**前提**——idx≠NONE 时 `caculateRefreshConfig` 忽略 `updateMode`
       （两次独立实测：`refresh_mode_3`/`refresh_mode_4` 下点 DU，`getFastModeIndex()` 纹丝不动）；
     · `5` 是合法逻辑域值且**不在迁移表**里 ⇒ 扛得住 `validateRefreshConfig`（写 0 会被改写为"等价索引"从而再次挡住引擎）；
     · alias 是旧版遗留的 UI 域串（`hd/du/regal_plus/smooth/new_speed`），本机这批 pkg 的 alias 映射表为空，留着无意义。
   - **实测证据**：重置后 → 面板点 DU `getFastModeIndex 0→2`、点 Normal `2→0` ✓；**重启后仍是 `NONE+5`** ✓。
   - **落地**：launcher 弹窗新增「还原为原始状态」（`RefreshModeHelper.RESET_LABEL/resetRefreshConfig` +
     `SettingFragment` 追加项 + `GlobalEacRefreshHelper reset`，后台 `nohup env … &` 跑，因 `SuHelper` 只有 8s 超时）；
     且 `set/defcfg` 已加**逻辑域校验（−1..5）**，此类事故不会再犯。
   - ⚠️ **残留的固件限制**：面板一旦写入 `um∈{0,1,2,3,4}`，**下次重启**会被迁移成"等价索引"，
     该 app 又会回到"被索引覆盖 ⇒ 切换无效" ⇒ 需再点一次还原，或用**优化引擎里的 per-app 下拉**（它写的就是索引）。
   - ⚠️ 教训：**给 EAC 写值前必须确认值域**；`9/98/33554436` 这类值一旦进 `updateMode` 就是不可逆的（除手动 reset）。
   - ⚠️ 工具坑：`su -c "nohup VAR=value cmd"` 会失败（nohup 把 `VAR=value` 当程序名）⇒ 用 `nohup env VAR=value cmd`；
     且 PowerShell 里 `tr "\n" ","` 的 `\n` 会被吃掉变成 `tr "n" ","`，把包名里的 n 全换成逗号（本次误写 13 组垃圾键，
     已用 `FixEac rm` 清除并复核：theme 键 166→127）。**构造包名清单一律走设备端脚本，不要在 PowerShell 里拼。**
13. ⚠️⚠️ **清理"写坏的值"必须按 MMKV 键枚举，不能用 `pm list packages -3`**：
   - 2026-10-11 全量重置时只用了 `pm list packages -3`（已安装的第三方包）⇒ **漏掉了已卸载 app 的残留**，
     事后扫描（`FixEac keys eac_app_` 枚举全部键 + `read` 复核）发现 **`com.example.piliplus` 与
     `moe.shizuku.privileged.api` 仍是 `um=9`**。按 MMKV 键重做后才清零（86 个 pkg 全 `NONE+5`，`um=9` 0 处）。
   - **正确姿势**：`PKGS=$(FixEac keys eac_app_ | grep '^eac_app_' | sed 's/^eac_app_//' | tr '\n' ',')`
     ⇒ 再 `FixEac fix "$PKGS" 5`；完成后**重启**再 `read` 复核（运行中的 OECService 会回写内存旧值，不重启复核会误判）。
   - ⚠️ 判定"还有没有坏值"要用**当前值**（`read` 逐 pkg 取 `eac_app_`/theme），不要用工具输出的字符串包含关系
     （本次 ② 段的 `um=9 x0` 这类文字会被误匹配成 12 处"命中"）。
   - 📌 **历史时间线（可复用于判断"哪个版本写的"）**：会写 UI/波形域值（含 `UI_REGAL_PLUS_MODE=9`）的是
     `605d356` … `c4bef37`（**2026-08-30 ~ 09-05** 发布的版本）；`0bf8ed5`（09-10）起改为逻辑域。
     正常机的 09-10 theme 备份（`eac_bak/*.json`，时间戳 09-10 01:01/11:57）里 `um=9` 出现 **0** 次 ⇒
     **`9` 是 09-10 之后写进去的**。app 卸载后 `dumpsys package` 的版本/安装时间与 `/data/data/<pkg>` 日志都会消失，
     且 Magisk 在这台机上**没有持久命令行日志**（`/data/adb/magisk.log`、`/cache/magisk.log` 均不存在）⇒
     **具体版本号与写入时刻不可追溯**，只能给上面两条边界。
14. ★★★ **2026-10-11 二次精修：写出 `9` 的版本窗口 + 取证方法（本节结论更精确，以本节为准）**：
   - **取证方法（可复用）**：APK 里 `META-INF/version-control-info.textproto` 记录 **构建时的 git revision** ⇒
     任何 CI 包都能反查精确提交（`[IO.Compression.ZipFile]` 直接读条目即可，无需解压）。配合
     `git cat-file -e <rev>:<path>` 与 `git show <rev>:…RefreshModeHelper.java` 即可判定"该构建有没有 EAC 写入能力 / 档位表属于哪个域"。
   - **实测三份归档包**：`/sdcard/app-release.apk`=`fb465a5`(2026-07-22，**无 EAC 代码**)；
     工作区 `app-release.apk`=`b78814c`(08-24，无 `RefreshModeHelper`)；
     `_scratch_gs/ci60/app-release.apk`=`c707645`(09-08 11:44，**UI 域表但 `GlobalEacRefreshHelper` 被 R8 删除**，仅 `PerAppRefreshHelper` 存活)；
     `_scratch_gs/ci_fix/app-release.apk`=`c35f860`(09-10 14:13，**已是逻辑域**，不可能写 9)。
   - ⇒ **真正能写出 `9` 的窗口 = `605d356`(09-10 00:56) ~ `0bf8ed5`(09-10 12:04) 这约 11 小时内的构建**
     （既满足"keep 规则已修 ⇒ helper 真的能跑"，又满足"档位表仍是 UI 域"）。结合 `eac_bak` 时间戳
     **09-10 11:57** 落在该窗口内 ⇒ **那次运行即写出 `9` 的元凶**；且当版是"统一写所有 app"的形态
     （`c707645` 的提交标题即 "per-app unified config"）⇒ **一次运行就把 9 写进所有 app**，与残留的广泛性吻合。
   - ⚠️ 该窗口内的 APK 本地**没有归档** ⇒ 无法给出精确 commit；能给的边界是上面那条 11 小时窗口 + 写入时刻 09-10 11:57。

### 5.1-15 ★★ 2026-11-06 第八会话：故障机「还原后」刷新引擎切换失效 —— 真根因是 EAC 残留跨恢复出厂存活

> ⚠️ **本节初版（本会话早些时候）把残留误判成「固件出厂默认形态」，已推翻**。那些
> `eac_app_*` 键里全是微信/QQ/WPS/Kindle/B站漫画等用户 app，出厂固件不可能预置它们的
> EAC 配置 ⇒ 只能是 launcher 历史批量写入、又没被卸载/恢复出厂清掉的**残留**。

**症状**：故障机 `6C7F0E64` 还原/恢复出厂后，系统自带刷新引擎（通知栏面板 / 全局档）点任何
模式都无反应。全量扫描 78 个 `eac_app_*` 键：`um=9` **0 处**（不是 §5.1-11 那次旧版 launcher
写坏值的事故），但 `refreshModeIndex` 全是 `refresh_mode_3 / refresh_mode_4`（非 `NONE`），
全局默认 `eac_default_app_config` = `idx=refresh_mode_3 um=0`。

**近因**：`caculateRefreshConfig` 在 idx≠NONE 时忽略 `updateMode`、直接返回系统档 mode
⇒ 面板写入全被丢弃。`getFastModeIndex()` 在模拟点击（`EInkHelper.setAppScopeRefreshMode(2)`）
前后恒为 0 ⇒ 复现成立。这些 `refresh_mode_*` 是**固件迁移表** `{0→3,1→2,2→2,3→5,4→2}`
把旧 `updateMode` 折成「等价索引」的结果（§5.1-9 的 `refresh_mode_N`→mode 映射）。

**★★ 真正的结构性根因（用户指出后取证）**：

| 证据 | 数据 |
|---|---|
| `/onyxconfig` 是**独立 ext4 分区** | `/dev/block/bootdevice/by-name/onyxconfig` → `mmcblk0p80`，**不在 `/data`** |
| 恢复出厂不擦它 | 分区内仍有 `2024-08-26 init_completed`、`2025-01-11 DEVICE_SERIAL_NUMBER_KEY`、`2026-08-30 test_result_info` |
| 卸载 app 不清 EAC | 用完整 `pm list packages`（158 个）比对：78 个 `eac_app_*` 中 **44 个属于真正已卸载的 app**（初版误用 `-3` 报成 71） |
| 早期脚本还造了垃圾键 | `eac_theme_pkg_set@theme_type_1/2/3`（把系统内部键 `eac_app_pkg_set` 误当 pkg） |

⇒ **「卸载 / 恢复出厂都有残留」是分区级结构问题**：`d7e0e42`（2026-10-11，用户记得的那次
改动）只做了两件事 —— ① `set/defcfg` 加 `−1..5` 值域校验（止住新伤害）；② 设置页加
「还原为原始状态」`reset` 命令（**手动**修复）。它**从未在卸载路径清理，也从不删除键**，
而 `reset` 写的 `NONE+5` 本身只是另一组垫片值，继续留在分区里。⇒ 那次改动没有触及这个根。

**本轮处置（已实测）**：
1. 设备侧：`gs_reset_all2.sh` 全量写 `NONE+5`（save-only，备份 `eac_bak/`）+ 重启 ⇒
   面板点 DU `getFastModeIndex 0→2`、点 Normal `2→0` ✓（修复前纹丝不动）。
2. 设备侧：按完整包列表删掉 **44 个已卸载 app 的 5 类键**（`eac_app_` / `default` / 3 个
   theme）+ 3 个 `pkg_set` 垃圾 theme 键，备份 `eac_residue_bak/`；重启后 `eac_app_*`
   78→34、引擎仍正常、内核健康全 0。
3. **代码补丁（本轮新增，弥补 `d7e0e42` 的缺口）**：
   - `GlobalEacRefreshHelper` 新增 `cleanup` 命令：按**完整** `pm list packages` 判定未安装
     pkg，删其 5 类键 + 3 个垃圾 theme 键；**保留**仍安装 app 的键（用户自设颜色/DPI）
     和系统内部键 `eac_app_pkg_set`；逐键备份到 `eac_cleanup_bak/`。
   - `enumeratePkgs()` 排除 `eac_app_pkg_set`（否则 fastjson JSONException 打断循环）。
   - `AdminReceiver.onDisableRequested/onDisabled` 在卸载/停用前用 `SuHelper` 后台
     `nohup … GlobalEacRefreshHelper cleanup` —— app 无卸载回调，DeviceAdmin 是唯一挂钩点
     （与既有「恢复 Onyx 桌面」自愈同一入口）。**静态编译 + dex 实机跑通**
     （`cleanup done: eacPkgs=34 installed=158`，不误删现存键）。
   - ⚠️ **仍覆盖不到的场景**：用户直接 `adb uninstall` / 在设置里卸 app 而**未停用
     DeviceAdmin**、`fastboot -w` 之外的刷机、以及 `d7e0e42` 之前已产生的历史残留。
     这些只能靠设置页「还原为原始状态」或 `tools/refresh/` 工具手动清 —— 文档已明示。

**本轮踩的坑（已修进脚本/代码）**：
1. `FixEac keys eac_app_` 会枚举出系统内部键 **`eac_app_pkg_set`**（值 = 裸包名列表，非 JSON）。
   传入 `FixEac` 会抛 `fastjson JSONException`，并打断 `fix` 循环 ⇒ `com.onyx.mail` 漏写。
   `gs_reset_all2.sh` / `gs_scan_only.sh` 已加 `grep -v '^eac_app_pkg_set$'`；
   launcher 的 `enumeratePkgs()` 也加了排除。
2. **验证时反复点面板会把刚验证过的配置写脏**：面板写入 `um∈{0,1,2,3,4}` 后，下次重启
   被迁移表折回「等价索引」，该 app 又回到被索引覆盖状态（§5.1-12 明载）。故最终复核
   **只做只读扫描 + `setmode` 功能判据，判完不再重启**。
3. 判定「已卸载」**必须用 `pm list packages` 全量**，不能用 `-3`（会把 100+ 系统 app 全
   误判成未安装 ⇒ 误删系统 app 的 EAC 配置）。
4. 故障机固件与正常机有差异：全局默认 `eac_default_app_config` 与 `com.onyx.mail` 等 Onyx
   内置 app **每次重启都被重建回 `refresh_mode_*`**；但第三方 app 的生效 theme
   （themeType 3）稳定保持 `NONE+5`，面板切换实测恢复 ⇒ 不影响使用。

### 5.2 内核 patch

| 脚本（已收进 `tools/kernel/`） | 作用 |
|---|---|
| `patch_kernel_v6.py <mode>` | 9 处 patch（v5 的 7 处 + 重排队 2 处）。`mode`: 1=DU / 4=A2 / 8=DU4 |
| `rebuild_boot_v6.py <out>` | 重打包 boot 镜像 |
| `patch_kernel_v7.py --variant <A..H>` | v7 系列（PG 判据旁路 + `all_lut_free` 超时） |
| `gs_kernel_tool.py` / `gs_verify_v7.py` | 拆包（已验证能复现 v6-A2）+ 结构校验/word diff |
| `gs_flash.sh` | 设备端写 + 回读 + 校验 |

**重排队 patch 位置**：`0x542B0C` 与 `0x5431AC`（`movz w0,#N`）
**当前设备**：`N=4`（A2）→ reset 后重排队走 `[6]/5` 帧

#### 已归档的 boot 镜像（2026-10-22 核验 md5）

| 镜像 | 大小 | md5 | 用途 |
|---|---|---|---|
| `boot_v7H.img` | 16.2 MB | `39ec0cb43213803d9ac4b79e7c987fc7` | **推荐长期版**（v7F 保留 PG 读数 + `all_lut_free` 恢复 stock 5000 ms） |
| `boot_v7G.img` | 16.2 MB | `96e2aec0b67a7c23b4d5ec3d4b52eb9c` | 回退版（2000 ms；出现长冻结时用） |

- 存放：本机工作区根目录 `C:\Users\root\Documents\eink\`；故障机 `/sdcard/` 另有 v7A–v7F、v5/v6 等历史镜像。
- ⚠️ **不要 `git add`**：16 MB 二进制会**永久**撑大仓库历史；需要对外发布请挂 **GitHub Release 资产**。
- 刷写（脚本自带写 + 回读 + 校验）：
  ```
  adb -s 6C7F0E64 push boot_v7H.img /data/local/tmp/
  adb -s 6C7F0E64 shell 'su -c "sh /data/local/tmp/gs_flash.sh /data/local/tmp/boot_v7H.img 39ec0cb43213803d9ac4b79e7c987fc7"'
  adb -s 6C7F0E64 reboot
  ```
  ⚠️ 切勿用内联复合 `su -c "…;…"`（会丢 root）；脚本须单独 `adb push` 后以 `su -c "sh …"` 执行。

---

## 6. ★ 下一步候选任务（按价值排序）

> **★ 2026-09-25 第二会话已执行 §6.1~§6.5** —— 结果速览（详见 `ref.md` §9.3.24）：
>
> | 项 | 结果 |
> |---|---|
> | §6.1 双刷机制 | ❌ **机制 A 证伪**（因果方向反了）；真正来源未定，疑上层周期重绘 |
> | §6.2 dt 属性 | ❌ **17 个属性全不存在**（仅 `epdc-waveform-load-delay`）⇒ 无此缓解点 |
> | §6.3 重启触发源 | ⚠️ 确认**全部为软件 reboot、无崩溃/ANR/watchdog**；**发起者仍未定位** |
> | §6.4 清残影入口 | ✅ **FULL 位在带参 `repaintEverything` 上生效**，但**全屏必然 reset** ⇒ 不可取 |
> | §6.5 可写节点 | ✅ 4 个全可写（**含 `debug_level`**，修正旧记录）|

### 6.1 ① 双刷的真正来源（★ 优先级最高，已有明确方向）

**已排除**：launcher / 刷新模式 / 用户操作 / powerup 重试（机制 A 证伪）。
**新假设**：上层（SF / Onyx 框架）**周期性自发提交同幅刷新**（每组 `+17~22` 帧 = 一个 DU 波形，间隔 ~32s）。

**方法**：
1. 用 `logcat -d | grep update_to_display`（**新手段，不受 dmesg 淹没影响**）统计 idle 期间的
   `waveform_mode` / `update_mode` 分布与**时间间隔**
2. 结合 `/sys/class/sepdc/debug/status` 的 `frame[a:b:c]` 对齐（注意 `K ≈ 27687.6` 基准标定）
3. 判据：idle 期间是否**周期性**出现同幅 `update_to_display`
4. 若成立 → 查 EAC `gcInterval`（§9.3.20 输入计数触发）/ debouncer / 时钟重绘

### 6.2 ~~验证 `epdc-power-fail-dont-update`~~ —— ❌ **已作废**

该属性**在设备树中不存在**（连同其余 16 个），内核 `of_property_read_*` 全部返回失败。
**无「现成开关」可改**。若仍要尝试，需在 `dtbo` 中**新增**属性并确认内核有对应字段存储 ——
**优先级应大幅下调**（它是未实现的接口，不是关掉的开关）。

### 6.3 查重启触发源（⚠️ 仍未定位，需部署常驻监听）

**已确认**：`bootreason` 全部为 `reboot`（软件），**无** crash / ANR / native_crash / watchdog。
`persist.sys.boot.reason.history` 显示 2 次 `shell` 发起 + 2 次无前缀（= system_server/init）。

**问题**：`logcat -b events` buffer 仅约 40 分钟，**不足以覆盖重启时刻**。
**方法**：
1. **部署常驻落盘**：`logcat -b events -v time > /data/local/tmp/events.log &`（建议开机自启）
2. 重启后查 `reboot_requested` / `boot_progress` 的**发起进程**
3. 同时对齐 WTF 时间戳（WTF 与重启**无因果**已确认，但可作旁证）

### 6.4 ~~评估「手动全屏清残影」入口~~ —— ✅ **已定论，不可取（故障机）**

| 方案 | 裁决 |
|---|---|
| 调**无参** `repaintEverything()` | ✅ 安全但**无效**（实测不带 FULL 位，清不了整屏残影）|
| 调**带参** `repaintEverything(98)` | ⚠️ **有效但会 reset**（实测 5 次中 3 次）；**正常机可考虑** |
| 档位表加 98/108 | ❌ 无效（**scope 通道** FULL 位不生效）|
| 周期性自动全刷 | ❌ 周期性闪烁 **+ 周期性 reset** |

```
⇒ ★ 净结论：故障机上「整屏清残影」与「避免 reset」物理不可兼得 ——
   整屏清残影必须 update[1] 全屏，而全屏必然 wait all_lut_free 超时。
⇒ launcher 现状（无参 repaint，局部重画）恰是故障机唯一安全选择，【不应改动】。
```

### 6.5 ~~试其余可写节点~~ —— ✅ **已完成**

`cut_frame_num` / `update_disable` / `time_test_level` / **`debug_level`** 四个节点**均可写**（写回原值 rc=0）。

> ⚠️ **`debug_level` 可写是重要新发现**（旧记录称"写入被拒"）：写 1 可恢复
> `SET_EBC_SEND_UPDATE` 等被抑制的 printk，**便于诊断**。但会加剧 dmesg 压力
> （当前已被 `dump_lcdc_state` 淹没）—— 建议在**专门诊断会话**中启用，勿长期打开。

### 6.6 正常机验证（`6C1BF7D9`）—— **优先级提升（因 §6.4 结论）**

- **A2 / 全屏 GC16 在正常机是否无 reset**（§6.4 已证全屏 GC16 在故障机必然 reset；
  正常机应无此问题 ⇒ 可安全提供"清残影"入口）
- `scrollingRefreshMode` 混合来源（未验证）
- 验证**无参 vs 带参** `repaintEverything` 在正常机的差异（应都干净）

---

## 7. 关键地址与命令速查

### 7.1 内核逆向地址

| 地址 | 内容 |
|---|---|
| `0x532dbc` / `0x532dc0` | ioctl 结构 `+0x30`(update) → 内部结构 `+0x80` |
| `0x532cd0` / `0x532cd4` | 内部结构 `+0x80` → LUT 描述符 `+0x30` |
| `0x534400` | `dump_lut_list` 打印点 |
| `0x534410`~`0x534428` | 打印字段偏移：`+0x38`magic `+0x10`lut `+0x2c`waveform `+0x30`update `+0x35`frame_cur `+0x34`frame_total |
| `0x550ab0` | `get_waveform_mode_index` |
| `0x1f69240` | 波形模式映射表（步长 `0x4c`，mode 0~4 → 槽 0~4，其余 → -1 回落）|
| `0x542b0c` / `0x5431ac` | reset 重排队波形号（v6 patch 处）|
| `0x53f7c0` | `onyx_epdc_parse_dt`（dt 属性解析）|
| `0x1991522` | `dump_lut_list` 格式串 |
| `0x1995002` | `reset_test trigger!` 格式串 |

### 7.2 设备诊断命令

```bash
# --- 状态 ---
adb shell su -c "cat /sys/class/sepdc/debug/status"
#   → ... epdc_active_luts[a][b][c][d] all_frames_completed[N] frame[a:b:c]
#     a==b==c = 无活动 ; a>b>c = 推进中

adb shell su -c "cat /sys/class/sepdc/debug/dump_list"    # cat 即触发全量 dump
adb shell su -c "cat /sys/class/sepdc/debug/update_err"   # 恒 1

# --- 波形实测 ---
adb shell su -c "CLASSPATH=/data/local/tmp/sscope.dex app_process /system/bin io.onyx.SetScope <值>"
adb shell su -c "CLASSPATH=/data/local/tmp/tw.dex app_process /system/bin io.onyx.TestWaveform <值> 2"
adb shell su -c "cat /sys/class/sepdc/debug/dump_list"

# --- 供电故障 ---
adb shell su -c "dmesg | grep -E 'TPS6518x|epdc power error|all_lut_free timeout|reset cause'"

# --- 重启排查 ---
adb shell su -c "getprop ro.boot.bootreason; cat /proc/uptime"
adb shell su -c "ls /data/system/dropbox/system_server_wtf* | wc -l"
adb shell su -c "grep -m1 TerribleFailure /data/system/dropbox/system_server_wtf@<ts>.txt"
```

### 7.3 关键文件路径

| 项 | 路径 |
|---|---|
| wbf 波形文件（本地）| `C:\Users\root\Documents\eink\eink_waveform.wbf`（md5 `f463661b158b9dc394f2a8e78d9322f8`）|
| 内核镜像（本地）| `C:\Users\root\Documents\eink\kernel_extracted.img`（34800128 B）|
| launcher 日志 | `/data/data/cn.modificator.launcher/files/refresh_mode.log` |
| launcher prefs | `/data/data/cn.modificator.launcher/shared_prefs/*.xml` |
| dropbox | `/data/system/dropbox/` |

---

## 8. 数据可信度声明

| 数据 | 验证方式 | 可信度 |
|---|---|---|
| 8×14 帧数矩阵 | 独立解析 wbf（md5 校验）+ 与 ref 逐值比对 | **高** |
| 波形物理规格（覆盖/驱动率/同色态）| 独立解析 + 全 8 项吻合 | **高** |
| 模式→槽→update 映射 | 设备受控实测，**6/6 复现** | **高** |
| "可区分灰阶" | **自建指标**（定义见 WAVEFORM_TO_DISPLAY §2.1）| ⚠️ **仅横向对比** |
| 双刷机制 | 三组对照实验 + 时序对齐（含 force-stop launcher）| **高**（机制 A 已**证伪**，见 §4.6；真正来源未定）|
| §6.4 FULL 位 | SDM `update_to_display` 日志逐项对照 + 5 次重复 | **高**（每条单独触发、`logcat -c` 后观测）|
| §6.2 dt 属性 | 全树遍历 `/sys/firmware/devicetree/base` | **高**（穷举 17 个名字 + 宽通配复核）|
| §6.3 重启类型 | `persist.sys.boot.reason.history` + dropbox 分类计数 | **高**（类型确定）；**低**（触发源未定）|
| 重启触发源 | WTF 时间线分析（否定 Watchdog 假说）| **低**（触发源未定）|

### ★ 已知数据陷阱（勿重复踩）

1. **wbf 解析 39 帧 vs 设备 38** —— 差 1，原因未明，**以设备 `frame_total` 为准**
2. **mode7 在 43°C 段读数 91 是伪值** —— 该段指针延伸到文件尾，混入了厂商元数据表（`01 02 03 04...` 递增序列），**应为 24**
3. **`SET_EBC_SEND_UPDATE = 0` 不代表无帧提交** —— 该打印仅 `debug_level≥1` 输出。判据应用 `frame[]` 计数
4. **`appScopeRefreshMode` 读数不可信** —— 软重启后 native pipe 不恢复，`currentTop` 恒 null，该值恒返回默认 `2`
5. **`dump_lut_list()` 仅在 reset 内部打印** —— 用它做"reset 前后"统计是**循环论证**
6. **`frame_cur` 不能判断完成度** —— 只是 dump 抓取时机的快照，应看 `reset` 计数与 `pending`

---

## 9. 会话内已修正的错误结论（避免重复）

| 曾经的错误结论 | 修正 | 出处 |
|---|---|---|
| "A2 出现在 reset 后 3ms ⇒ A2 是 reset 产物" | ❌ 循环论证 | ref §9.3.18⑤ |
| "`appScopeRefreshMode=2` 是 GC16" | ❌ 那是 **EAC 逻辑域的 A2**；scope 值域的 2 才是 GC16 | ref §9.3.18⑤ |
| "改重排队波形号可消除循环" | ❌ update 与波形号独立 | ref §9.3.18④ |
| "改重排队坐标可根治" | ❌ 重排队非触发源（其 update 本就是 0 局部）| ref §9.3.19⑦ |
| "`SET_EBC_SEND_UPDATE=0` ⇒ 零帧提交" | ❌ 日志级别问题，frame 一直在增长 | ref §9.3.23⑮ |
| "停滞 = EPDC 收不到帧" | ❌ 是**帧执行被供电重试拖慢** | ref §9.3.23⑮ |
| "WTF 刷屏 → Watchdog → reboot"（§12.7 旧结论）| ❌ WTF 时间线无中断，不符合 Watchdog | ref §9.3.23③ |
| "根因是切档批量写 EAC"（§12.7 旧结论）| ❌ 爆发窗口 launcher 无操作 | ref §9.3.23④ |
| "REGAL = 5 帧" | ❌ 实为 **GC16 38 帧**（mode4 列被填成 GC16 副本）| ref §9.3.11 |
| "冲击残影可修好" | ❌ `panel_clean` 等节点只读，非命令节点 | ref §9.3.23⑨ |
| "双刷 = powerup 重试周期"（机制 A）| ❌ 因果方向反了（`Reg Enable` 在 frame 推进**之后**）| ref §9.3.24① |
| "§6 的 17 个 dt 属性可调" | ❌ **全部不存在**（仅 `epdc-waveform-load-delay`）| ref §9.3.24② |
| "FULL 位在 scope 通道不生效" | ⚠️ 仅 scope 通道成立；**带参 `repaintEverything(值)` 的 FULL 生效** | ref §9.3.24④ |
| "降低操作频率可缓解"（第三会话）| ❌ 间隔扫描：**无系统性差异**（0.35s 反而最好）| ref §9.3.25⑫ |
| "scope 通道拿不到 DU4" | ❌ `scope=2312` **确实落 DU4**（但 reset +14，死亡谷）| ref §9.3.28① |
| "A2 必走全屏 `update[1]`" | ❌ `scope=2308` 实测 `update_mode=0` 局部 | ref §9.3.28① |
| "清残影必然 = 全屏 GC16" | ❌ 应为「全屏 + **全摆动类**」（`108`/DEEP_GC16 亦可）| ref §9.3.28⑨ |
| **"launcher `byPass(0)` 是硬清零隐患"**（本会话初判）| ❌ **实测：`byPass` 是【设置】语义非累加，launcher 用法正确** | ref §9.3.29⑨ |

---

## 10. 快速上手（复制粘贴）

```powershell
# 0. 环境
cd C:\Users\root\Documents\eink
.\adb.exe devices -l                    # 确认 6C7F0E64 在线
.\adb.exe -s 6C7F0E64 shell "getprop ro.product.model"   # 必须返回 Poke6！

# 1. 设备当前状态
.\adb.exe -s 6C7F0E64 shell 'su -c "cat /sys/class/sepdc/debug/status"'
.\adb.exe -s 6C7F0E64 shell 'su -c "CLASSPATH=/data/local/tmp/checkmode.dex app_process /system/bin io.onyx.CheckMode"'

# 2. 恢复安全档位（GC16）
.\adb.exe -s 6C7F0E64 shell 'su -c "CLASSPATH=/data/local/tmp/sscope.dex app_process /system/bin io.onyx.SetScope 2"'

# 3. 读文档
#    HANDOFF.md（本文）→ WAVEFORM_TO_DISPLAY.md → ref.md §9.3（导读框起）
```

**★ 动手前必做**：确认设备是 **Poke6**（曾出现 adb daemon 错认设备导致整批假阴性数据）
```powershell
.\adb.exe -s 6C7F0E64 shell "getprop ro.product.model"   # 必须是 Poke6
.\adb.exe -s 6C7F0E64 shell 'su -c "ls /sys/class/sepdc/debug/dump_list"'  # 必须存在
```

---

## 11. 联系点 / 未决问题

| # | 问题 | 状态 |
|---|---|---|
| 1 | **「重叠」能否从 scope / EAC / A2 三维度解决** | ✅ **已定论：全部无效**（§9.3.25②③④）。「重叠」= 提交速率 > EPDC 执行速率；根因是**供电成功率（硬件随机）**。**「降低操作频率」同日亦被否证**（§9.3.25⑫：间隔 0.35~5.0s 无系统性差异，0.35s 反而最好）⇒ **软件层没有稳定解法**，只能修硬件 |
| 2 | ~~`epdc-power-fail-dont-update` 当前值~~ | ✅ **已查清：属性不存在**（连同其余 16 个）⇒ 此路不通（§6.2）|
| 3 | 重启真正触发源 | ⚠️ 已确认全部为软件 reboot、无崩溃/watchdog，**但发起者未定位**（需常驻 events log，§6.3）|
| 4 | 长停滞 27.5s 的解释 | ✅ **机制已明**：`powerup` 连续失败导致的积压窗口（随机）；`setUpdListSize` 复验证明**积压深度不可控**（§9.3.25⑤⑥）|
| 5 | idle 为何持续自发刷新 | ✅ **已找到来源**：**状态栏时钟** —— 实测每分钟 `:00.0x` 秒准点触发一次 `waveform_mode=1`（§9.3.25⑦）|
| 6 | ~~`repaintEverything(带参)` 是否绕过 FULL 位~~ | ✅ **已定论：带参生效、无参不生效**；但全屏必然 reset ⇒ 故障机不可用（§6.4）|
| 7 | 是否有"清残影"的 dt 开关 | ✅ **已查清：`a2-clean-mode` 等均不存在**（§6.2）|
| 8 | A2（及全屏 GC16）在正常机的表现 | 未测 —— **优先级已提升**（§6.6）：决定能否为正常机提供清残影入口 |
| 9 | `debug_level=1` 能否恢复被抑制的诊断 printk | 未测（节点**可写**已确认，§6.5）；建议专门会话验证 |
| 10 | `gcInterval` 触发时插什么 | ✅ **源码确证 = `repaintEverything(98)` 全屏 GC16**（§9.3.27③，推翻"局部"旧记录）。⚠️ 但实现类 `EpdcUpdateDebounceWithDelay` 是**孤儿**；且 root 注入**无法复现**（§9.3.25③）|
| 11 | 通知栏「刷新屏幕」磁贴是否 = `repaintEverything(98)` | ⚠️ **源码推断**（`UpdateMode.GC ≡ UI_GC_MODE = 98` 已确证）；**实测未完成**（Poke6 掉线）。验证：`am broadcast -a onyx.android.intent.action.REFRESH_SCREEN` 后看 `waveform_mode/update_mode`（§9.3.26）|
| 12 | launcher 注释「等同通知栏磁贴」错误 | ⚠️ 待修：`RefreshModeHelper.fullRefreshScreen()` 用的是**无参**版（局部重画），**不等于**磁贴的全屏 GC16（§9.3.26④）|
| 13 | **gcInterval 是否为卡顿/reset 触发源** | ⚠️ **未验证** —— 建议把 UI「Full-refresh Frequency」设 **0**（= 阈值 MAX_VALUE = 永不触发）做 A/B 对照（§9.3.27⑥，**零风险**）|
| 14 | 「快 + 有灰阶」是否存在 | ✅ **已定论：不存在**。A2(5)/DU(22) 无灰阶；**DU4(24帧/4级灰) 可用但 reset +14/轮**（死亡谷）；`cut_frame_num` 对 slot-7 无效（§9.3.28①②③）|
| 15 | 综合调理建议 | ⚠️ 用户需要：**DU（快）** 或 **GC16（灰阶+清残影）**二选一；清残影仅 GC16，且整屏清残影（磁贴/`applyGCOnce`/gcInterval）在故障机**必 reset** |
| 16 | FULL(32) 能与哪些波形组合 | ✅ **已定论**：只被**全摆动类**接受 —— `98`(GC16) / `108`(DEEP_GC16) 能全屏；`33/97/99/100/104`（DU/GC4/A2/DU4 + FULL）**返回 OK 但零全屏**（两次复现）。差分模式 state 覆盖不足 ⇒ 物理无法整屏（§9.3.28⑨）|
| 17 | EAC「刷新」页各项是否影响卡顿 | ✅ **对照表已建**（§9.3.29②）：7 项含 `gcInterval`(20) / `gcAfterScrolling`(true) / `useGCForNewSurface`(false) 等。**权威源 = `/onyxconfig/mmkv/onyx_config`** |
| 18 | 「页面拖动停止后全刷」是否清残影 | ✅ **实测：A2 模式下不清**（只调 `clearTransientUpdate`，零 reset）。⚠️ 但**需拖动式交互**（网页/翻页=滑动）才触发，`input swipe` 测不到（§9.3.29③）|
| 19 | 「把动画都过滤」能否全部无动画 | ✅ **已定论：eink 侧做不到**。`animationDuration`=debouncer 下限、`byPassAnimation()`=**冻结刷新**（非跳过）；动画帧由 app 渲染，eink 只能改"如何显示"（§9.3.29⑦）|
| 20 | ★ **reset 的主因是什么**（第五会话最大发现）| ✅ **86% 的 reset 来自全屏 `update[1]`**（130 次中 112 次），其中 **83 次是 A2 全屏**（`waveform[6] update[1] total[5]`）；**不经 scope 通道** ⇒ 解释了 §9.3.25「三维度无效」。来源 = **`scrollingRefreshMode=2` 滚动特判**（§9.3.29⑧）|
| 21 | 能否关掉 A2 滚动特判 | ⚠️ **API 改不动**（`setScrollingRefreshMode(0)` 读回仍 2，复现 §9.3.14③）⇒ **只能在 UI 层试**。★ **下一个会话优先验证此项** |
| 22 | `byPass` 语义（我曾误判为隐患）| ✅ **已纠正**：`byPass(count)` 是**【设置】语义非累加**，阈值 **>=10** 冻结；launcher `byPass(10)`/`byPass(0)` **正确，无需修改**（§9.3.29⑨）|

> ★ **磁贴的实用结论**（§9.3.26⑤）：磁贴是**故障机上唯一能「整屏」清残影的用户入口**，
> 但 `update[1]` 全屏 ⇒ 必然 `wait all_lut_free` ⇒ 实测 **5 次中 3 次 reset**。
> ⇒ 「整屏清残影」与「避免 reset」**物理不可兼得**，磁贴取前者。
>
> ★ **三条「清残影」路径殊途同归**（§9.3.27/§9.3.28）：
> 通知栏磁贴 / `applyGCOnce()` / gcInterval 全刷 —— **都是全屏 GC16 `repaintEverything(98)`**，
> 在故障机上都必然 reset。**不存在**「局部清残影」这条路。

---

## 12. ★★★ 第五会话交接（2026-09-29，最新）

> **一句话**：找到了 reset 的主因 —— **86% 来自全屏 `update[1]`，其中 83 次是 A2 全屏**，
> 来源是 **`scrollingRefreshMode=2` 的滚动特判**，且**不经 scope 通道**（解释了为何改 scope 无效）。

### 12.1 本轮最大发现（优先验证这项）

| # | 发现 | 证据 | 下一步 |
|---|---|---|---|
| **1** | **86% reset 来自全屏 `update[1]`**（130 次中 112 次）| reset 时卡住的波形统计 | — |
| **2** | **其中 83 次是 A2 全屏**（`waveform[6] update[1] total[5]`）| 同上 | — |
| **3** | ★ **这些全屏不经 scope 通道** | 同期 `logcat` 的 `waveform_mode` **只有 1(DU)**、`update_mode=1` 为 0 | — |
| **4** | 来源 = **`scrollingRefreshMode=2`** | `EInkHelper.getScrollingRefreshMode()` = 2 | ★ **验证项** |
| **5** | **API 改不动它**：`setScrollingRefreshMode(0)` 读回仍 2 | 实测（复现 §9.3.14③）| ★ **只能走 UI** |

**★ 建议下一个会话优先做**：在**设备 UI** 里找「滚动刷新模式」类选项，改成非 A2，
然后抓 `waveform[6] update[1]` 计数是否下降。**这是目前唯一有明确指向、且可控的软件因素。**

```bash
# 判据命令（改前/改后对比）
adb shell su -c "dmesg | grep -c 'waveform\[6\] update\[1\]'"
adb shell su -c "dmesg | grep -c 'reset cause'"
```

### 12.2 已澄清/纠正的（避免重复劳动）

| 项 | 结论 |
|---|---|
| **`byPass(count)` 语义** | ✅ **【设置】语义非累加**；阈值 **>=10** 才冻结；`count<=0` 释放。**launcher `byPass(10)`/`byPass(0)` 正确，无需改** |
| ⚠️ 我曾误判 | ❌ "`byPass(0)` 硬清零是隐患" —— **实测推翻**，已在 §9.3.29⑤ 标注作废 |
| **「无动画」** | ✅ **eink 侧做不到**：`animationDuration`=debouncer 下限、`byPassAnimation()`=冻结（非跳过）；动画帧由 app 渲染 |
| **EAC「刷新」页 7 项** | ✅ 完整对照表已建（§9.3.29②），权威源 = `/onyxconfig/mmkv/onyx_config` |
| **「页面拖动停止后全刷」** | ⚠️ **A2 模式下名不副实**（只调 `clearTransientUpdate`，不清残影）；需**拖动式交互**才触发 |

### 12.3 新增设备端工具（`_scratch_gs/probe_6/session5/`）

| 工具 | 用途 |
|---|---|
| `vu.dex`（`io.onyx.VU`）| **通用反射调用器**：可调 `ViewUpdateHelper` 任意静态方法。`VU list` 列全部 API |
| `eh.dex`（`io.onyx.EH`）| 调 **`EInkHelper`**（服务层）：`EH getGcInterval` / `EH setAnimationDuration 200` |
| `gc.dex`（`io.onyx.GC`）| 一次性 dump 多个运行时值（gcInterval/animationDuration/antiFlicker/scrollingRefreshMode…）|

```bash
CLASSPATH=/data/local/tmp/vu.dex app_process /system/bin io.onyx.VU list
CLASSPATH=/data/local/tmp/vu.dex app_process /system/bin io.onyx.VU applyGCOnce
CLASSPATH=/data/local/tmp/eh.dex app_process /system/bin io.onyx.EH getScrollingRefreshMode
CLASSPATH=/data/local/tmp/gc.dex app_process /system/bin io.onyx.GC
```

### 12.4 本轮踩过的坑（务必避免）

| 坑 | 教训 |
|---|---|
| ★★ **`VAR="CLASSPATH=… app_process …"; $VAR`** | **第 4 次踩**！变量包裹整条命令必然失败 → **脚本里每处都写完整命令** |
| ★★ **读抓取文件前不核对时间戳** | 曾把 09-28 的旧日志当"刚才的重启证据"分析 → **先比 `ls -la` mtime 与设备 `date`** |
| ★★ **A/B 对照未控制变量** | dither 对比用「翻页后截图」→ 得出错误结论 → **同页面只改待测变量** |
| **仅凭源码静态阅读下"隐患"结论** | `byPass(0)` 误判即此因 → **先实测再断言** |
| **`app_process` 每次都是新进程** | static 状态（如 `byPassOwner`）**无法跨调用测试** |

### 12.5 设备当前状态（离开时）

```
scope = DU(1)          cut_frame_num = 0        update_disable = 0
scrollingRefreshMode = 2 (A2)     ← 未改动（API 改不动）
gcInterval = 20        animationDuration = 20   antiFlicker = 10
抓取: /data/local/tmp/live_all.log 与 live_kernel.log（★ 用前核对 mtime）
```

---

---

## 13. ★★★ 第六会话（2026-09-29）：根因定位 + 已修复（v7A 内核）

> **一句话**：主症状的根因不是"供电偶发失败"，而是**一个恒假的软件判据** ——
> `tps6518x` 用 **`power-good 寄存器（reg 0x0F）== 0xFA`** 判定上电成功，
> 而故障机硬件**恒回读 `0xBA`**（只差 bit6），判据**永远不可能成立**。
> 已用内核 patch（v7A）改这一处判据，**整条失败链路消失**。

### 13.1 精确定位（反汇编，Image 偏移 = 文件偏移）

DISPLAY regulator 的 enable 回调 = **`0x5F4198`**（历史上被记作 `onyx_epdc_powerup`）。
失败返回 `w20 = -0x6e = -110 = -ETIMEDOUT`。

```asm
5f4308: ldr  w8, [x21, #0x84]      ; max_wait（DT = 24）
5f430c: str  wzr, [sp, #4]         ; 预置 0：若跳过回读则恒 0
5f431c: bl   0x5f4768              ; 谓词①：GPIO 路径（DT gpio_pmic_pwrgood = gpio 85）
5f4324: cbnz w0, 0x5f443c          ; 成功 → w20 = 0
5f4344: ldr  w8, [x21, #0x50]
5f4348: cmp  w8, #0x500
5f434c: b.lo 0x5f4370
5f4350: bl   0x76e650              ; regmap_read(reg 1,   &sp[0])
5f4360: bl   0x76e650              ; regmap_read(reg 0xF, &sp[4])   ★
5f4370: ldr  w8, [sp, #4]
5f4374: cmp  w8, #0xfa             ; ★★★ 唯一有效的成功判据
5f4378: b.eq 0x5f443c              ; 相等 → 判成功
5f437c: bl   0xce928               ; printk "Reg Enable: [0x1] 0x%X"
5f4394: bl   0xce928               ; printk "Reg PowerGood: [0xf] 0x%X"
5f43a4: bl   0xce928               ; printk "ERROR TPS6518x waiting for power good!"
5f43ac: cmp  w24, #1               ; 重试上限（原 3，v5 改成 1）
5f43b0: b.eq 0x5f4440              ; 用尽 → 返回 w20 = -110
5f43b4: bl   0xce928               ; printk "Retry %d more times"
5f43c8: bl   0x5f4488              ; retry-prep（内含 msleep）
5f43d0: ...                        ; ★ regmap_write(reg 1, 0) = 拉低所有电源轨
5f43e0: bl   0x76e6c0
5f4410: mov  w0, #0x1e             ; msleep(30)（v5）
5f4418: bl   0x5f4678              ; 重新上电 + 写 reg 1
5f443c: mov  w20, wzr              ; ← 成功出口
```

谓词 `0x5F4768`：先走 **GPIO**（`chip+0x3c`，即 gpio 85，与 `chip+0x8c` 期望电平比较）；
GPIO 无效时走 regmap（`0x5F47EC`，**同一个魔数 `0xFA`**）。
⇒ 本次故障机上两条谓词同时为假。

**DT 佐证**（`/sys/firmware/devicetree/base/soc/i2c@4a88000/tps6518x@68`）：
`compatible="ti,tps6518x"`、`reg=0x68`（i2c-2）、`gpio_pmic_pwrgood=<17 85 0>`、
`gpio_pmic_v3p3=<17 99 0>`、`gpio_pmic_vcom_ctrl=<17 93 0>`、`gpio_pmic_wakeup=<17 83 0>`、
`max_wait=24`、`pwr_seq0/1/2=0xe1/0x30/0x33`、`upseq0/1=0xe4/0`、`dwnseq0/1=0x1e/0`、`vpos-mV=14250`。

### 13.2 现场证据（v6-A2，uptime 15510 s）

| 指标 | 值 |
|---|---|
| **`Reg PowerGood` 取值分布** | **{0xBA: 684}** ← 4 小时 18 分内**没有一次**不同 |
| `Reg Enable` 取值分布 | {0xAF: 684} |
| `ERROR TPS6518x waiting for power good!` | 684 |
| `Unable to enable DISPLAY regulator.err = 0xffffff92` | 342 |
| `wait all_lut_free timeout 500 ms` | 260 |
| `reset cause` | 130（突发时 0.69 s 一次） |
| `all_frames_completed[>0]` | **0 / 276** |
| 276 次 `dump_lut_list` 快照的 `frame_cur` | **全部 1~2**（total 5/10/14/22/24/38 皆然） |

### 13.3 v7 系列镜像（均在 v6-A2 基础上，只叠加少量 4 字节改动）

构建：`_scratch_gs/patch_kernel_v7.py <变体> <out.img>`；
校验：`_scratch_gs/gs_verify_v7.py A B C D E F`（结构 + 与 v6-A2 逐字比对）。

> **管线可信性已验证**：用 `patch_kernel_v6.py`(mode=4) 重建的内核与**当时已刷入**的
> `boot_patched_a2_v6.img` 内层内核 md5 完全一致（`a2ed97ff…`）。

| 变体 | 追加改动 | boot md5 | kernel md5 | 结果 |
|---|---|---|---|---|
| **v7A** | `0x5F4374`/`0x5F47EC`：`cmp #0xfa` → `cmp #0xba`（承认硬件真实回读） | `836dc759ccdb47b267136b554472de7e` | `524ea940d3d4e5c6597cbc0679ad6a25` | ✅ **已刷入，实测有效** |
| **v7F** | `0x5F43B0`：`b.eq 0x5F4440`(返回 -110) → `b 0x5F443C`(判成功)。**保留 printk 诊断** | `f76f8f578a5a9d29b3c5accd81743ee4` | `878801fdb097c4f96dcf8e0b9ca59c05` | 未刷；功能同 v7A，**建议长期版本** |
| v7B | `0x5F4378` 改无条件跳成功 + `0x5F47F0` `mov w0,#1`（完全绕开判据） | `6f7da25c651bc291d8358c7eedd2d454` | `cf994ee2feafc0f033d45d7ffc3da671` | 未刷（比 A 更激进） |
| v7C | `0x5F43DC`/`0x5F43E0` 置 `nop`（重试时不拉低电源轨，不改 PG 语义） | `c8b598d165dec891629ac33c6de63dbd` | `ba4e38693675f492b3c1a5e358dc1250` | 未刷 |
| v7D | `0x5F4508` 1.55 s→50 ms + `0x5F43AC` 重试 1→3 | `e17aa32594db4c9c3da8c1e59ccd92eb` | `8d8deee347d3f11c8c4170a3b15c570a` | ❌ **已刷并实测：无效/更差**，见 §13.4 |
| v7E | A + D | `9878fe7271e7f4268ee2c380dca68558` | `7782bd114f25e60d55b4865520d8f603` | 未刷 |
| （回滚基线）v6-A2 | — | `c62600dd4bb3a1abcba0a0c0caaf75c6` | `a2ed97ff8c5f64304cd784e9e3213180` | 设备 `/sdcard/` 上有 |

### 13.4 实测对照（同一受控负载剖面：6× `TestWaveform(2)` + dump_list + 20 s 静置）

| 指标（约 45 s 窗口） | v6-A2 | v7D | **v7A** |
|---|---|---|---|
| `Reg PowerGood` / `Reg Enable` | 4 | 7 | **0** |
| `Retry %d more times` | 2 | 6 | **0** |
| `Unable to enable DISPLAY` | 2 | 1 | **0** |
| `epdc power error` | 3 | 1 | **0** |
| `wait all_lut_free timeout` | 2 | 8 | **0** |
| `reset cause` | 1 | 4 | **0** |
| **卡住的 LUT** | 3 | 7 | **0** |

**v7D 的两条结论（都是有价值的否证）**：

1. **v7D 的提速 patch 打错了地方（新发现）**：把 `0x5F4508` 压到 50 ms 后
   `PG→PG` 间隔仍是 1.62~1.65 s ⇒ ref §11.8 的归因不完整。真正的 ~1.5 s 在
   **`0x5F4744`：`ldr w0,[x19,#0x88]` → `msleep(chip+0x88)`**（"上电成功"出口），
   v5/v7D 从未压过它。⚠️ 它在上电成功路径上，语义更像"电源轨稳定等待"，
   **不宜盲压**（v4 教训同源）。
2. **"提高重试频率"这条路已被实验否证**：重试 1→3 生效（PG/Retry 比 2.0→1.17），
   但受控负载下 reset 1→4、超时 2→8 ⇒ **更差**。与 §13.2 的"判据恒假"完全一致：
   重试节流只改变失败次数，不改变成功可能性。

**v7A 静置 soak**（uptime 307→458 s，每 30 s）：`PG=0 reset=0 timeout=0 null=0` 全程；
`frame[]` 持续增长（1282→1434）。唯一异常是 **t=22.79–23.46 s 的 2 次 reset + 3 次
`waveform_desc is NULL`**（开机动画/systemui 启动那一刻的一次性瞬态），此后为零。

### 13.5 安全性与副作用

| 项 | 观测 |
|---|---|
| PMIC 温度（`tps6518x-sns/temp_input`） | 36–37 °C，无异常 |
| 电池 | level 100 %，36.2 °C |
| 电源轨 | 上电窗口内 **`DISPLAY` / `VCOM` / `V3P3` 三者同时 enabled**，空闲同时 disabled（正常节电） |
| 内核日志 | reset/dump 风暴消失，dmesg 由上万行降到 **319 行**（少掉的 CPU/IO 开销是白赚的） |

> ★ **顺带纠正**：ref/HANDOFF 曾把 `VCOM state=disabled` 与 `Reg PowerGood 0xBA` 并置，
> 推断"4 轨中 2 轨坏 / 缺轨"。实测在 v7A 下 **VCOM 上电窗口里是 enabled 的** ——
> 之前的 `VCOM=disabled` 只是**采样恰好落在 powerup 失败后的断电瞬间**，
> 是"反复拉低电源轨"制造出来的假象。加之面板本来就能刷新，
> **"带缺轨驱动面板"的风险评估应显著下调**；"bit6 是慢爬升/粘滞状态位，代码却要求精确 `0xFA`"
> 更符合全部现象。

### 13.6 ★ 这个改动的边界（务必记住）

1. **v7A 让判据恒真 = 永久放弃 power-good 保护**。若日后 PMIC 或某轨真的损坏，
   驱动不会再报错、不会重试，只会"以为一切正常"。
   ⇒ **长期建议换 `boot_v7F.img`**：功能等价，但保留
   `Reg Enable: [0x1] 0xAF` / `Reg PowerGood: [0xf] 0xBA` 两行 printk，可用于判断 PMIC 真实状态。
2. **硬件仍是坏的**：TPS6518x 的 power-good 位确实不置起。v7A/v7F 只是让软件不再因此
   反复断电与放弃刷新，**不是修好了芯片**。若哪天出现"彻底不刷新且无任何报错"，
   先怀疑这个 patch 掩盖了真实硬件劣化。
3. **未验证的更"正统"方案**：reg1（ENABLE）写的是 `(v74 & 0x30) | 0x0F`
   （`0x5F42E0` = `0x32000D02` = `orr w2, w8, #0xf`），只置低 4 位；回读 `0xAF` 的 bit4 = 0。
   若第 5 轨 enable 就是 **bit4(0x10)**，把它一起置上（`#0xf`→`#0x1f`，编码 `0x32001102`）
   也许能让 PMIC **自己**把 PG 报成 `0xFA` —— 那才是"真修好"。
   ⚠️ 纯属假设，需 datasheet 或量电压佐证；**只能在 v7A/v7F 基础上做对照，不要单独刷**。
4. **仍需观察**：数小时~一天的长时间稳定性；以及人眼验收（刷新速度/残影/新瑕疵）。
   截至交接时用户反馈"看起来还行，先测试一段时间"。

### 13.7 刷入 / 回滚 / 验证（复制粘贴）

```powershell
# 刷入（已含回读校验的脚本在设备 /data/local/tmp/flash.sh）
.\adb.exe -s 6C7F0E64 push boot_v7A.img /sdcard/boot_v7A.img
.\adb.exe -s 6C7F0E64 shell 'su -c "sh /data/local/tmp/flash.sh boot_v7A.img 836dc759ccdb47b267136b554472de7e"'
.\adb.exe -s 6C7F0E64 reboot

# 回滚
.\adb.exe -s 6C7F0E64 shell 'su -c "dd if=/sdcard/boot_patched_a2_v6.img of=/dev/block/by-name/boot_b bs=4M"'
.\adb.exe -s 6C7F0E64 reboot

# 判据（刷入后 3 分钟内即可判定）
adb shell su -c "dmesg | grep -cE 'Reg PowerGood|Retry|Unable to enable DISPLAY|epdc power error|wait all_lut_free timeout|reset cause'"
#   → v7A/v7F 期望：0（除 v7F 会保留 Reg PowerGood/Reg Enable 的读数行）
```

### 13.8 本次新增工具（`_scratch_gs/`，供接力）

| 文件 | 用途 |
|---|---|
| `GS_FINDINGS_PMIC.md` | 本次完整报告（比本节更细，含全部反汇编与数据） |
| `patch_kernel_v7.py` / `gs_verify_v7.py` | v7 构建 / 校验（与已刷镜像逐字比对） |
| `gs_kernel_tool.py` | boot 镜像内核提取 + v6 复现验证 |
| `gs_metrics.py` | dmesg 指标统计（支持"最后 N 秒"窗口，用于**同口径**对比） |
| `gs_loadtest.sh` | 受控负载剖面（6× `TestWaveform(2)`），刷机前后对比用 |
| `gs_flash.sh` | 带回读校验的刷入脚本 |
| `push_sh.py` | **本地脚本 LF 化后 push 并执行** —— 一次绕开 CRLF 污染 + PowerShell 引号嵌套两个老坑 |

### 13.9 ★★★ 副产物：v7A 之后哪些刷新档位能用（逐档位实测，2026-09-29 15:3x–15:4x）

> 方法与坑规避：每组前 **静置 20 s + 清 scope**，再连发 n 次；每组单独 `dmesg -c` 后计数。
> 全程 `epdc power error = 0`、`Reg PowerGood = 0` —— **所以本节的 reset 都不是 §13 的电源故障**，
> 而是**第二套、与模式相关的 LUT 卡死机制**（全部卡在 `frame_cur = 2`）。

**① 逐档位（n=5，`TestWaveform <值>`）**

| 值 | 含义 | reset | reset/触发 | 卡住的 LUT |
|---|---|---|---|---|
| `1` | 裸 DU | **0** | 0 | — |
| **`98`** | **GC16\|WAIT\|FULL = 清残影** | **0** | 0 | — |
| `2` | 裸 GC16 | 1 | 0.2 | GC16 全屏 38 帧 cur2 ×1 |
| `2312` | DU4 | 5 | **1.0** | DU4 **局部** 24 帧 cur2 ×5 |
| `108` | DEEP_GC16\|WAIT\|FULL | 5 | **1.0** | DU 全屏 22 帧 cur2 ×4 |
| `4` | 裸 A2 | **10** | **2.0** | A2 全屏 5 帧 cur2 ×8 |
| `2308` | A2\|DITHER\|Y1 | 6 / 3 轮 | 2.0 | A2 全屏 5 帧 cur2 |

**② scope 档位 + 真实 tap/swipe（各 4 轮）**

| scope | 含义 | reset |
|---|---|---|
| `1` | DU | **0** |
| `2` | GC16 | **0** |
| `4` | A2 | **8**（仍 reset 循环） |
| `2312` | DU4 | **4** |

**③ ★ 清残影三条真实入口全部干净（各 ×3，另有 `98` ×5 与 ×3）**

| 入口 | reset |
|---|---|
| `TestWaveform 98`（= `repaintEverything(98)`，另测 5 次亦 0） | **0 / 8** |
| `VU applyGCOnce()` | **0 / 3** |
| `am broadcast -a onyx.android.intent.action.REFRESH_SCREEN`（通知栏磁贴） | **0 / 3** |
| 直接观察帧推进的那一次（屏幕唤醒 + 打开设置界面后触发 98） | ⚠️ **1 / 1** |

⇒ 合计 **11 次干净 + 第 12 次 1 次 reset** ⇒ **清残影已基本可用，但不是 100%**。
第 12 次卡住的**不是 GC16**，而是同批下发的 `waveform[1] update[1] frame_total[22]`（**DU 全屏**）：
`magic[1026] lut[1] waveform[1] update[1] frame_cur[1..4]` —— 说明 98 的链路里还会夹一个
DU 全屏子更新，**它才是残留的卡点**（与 §8.4 里 DU4/A2 的"差分模式 + 宽幅更新"同源）。
⇒ 若要把清残影做到完全干净，下一个靶点是**那条 DU 全屏子更新**，不是 GC16 本身。

★★ **直接证据：GC16 现在真的能跑完**（屏幕唤醒 + 设置界面在前台，触发 98，高频 `cat dump_list`）：
同一个 LUT `magic[1027] lut[0] waveform[2] update[0] frame_total[38]` 被连续采到
`frame_cur` = **2 → 3 → 4 → 5 … → 30 → 31 → 32 → 33 → 34 → 35 → 36 → 37**，然后从列表中消失（完成释放）。
v6-A2 时代同一波形**永远停在 2/38**。
（方法坑：`dump_list` 的活动 LUT 内容走 **printk → dmesg**，stdout 只回一个 `1`；
且**必须先唤醒屏幕**，`mWakefulness=Asleep` 时 80 次 dump 全是 `is Empty`。）

⇒ ★★★ **本条推翻 HANDOFF §6.4 与 ref §9.3.28⑤ 的核心结论**
「整屏清残影（全屏 GC16）在故障机必然 reset ⇒ 与避免 reset 物理不可兼得」——
当时实测「5 次中 3 次 reset」，那是 §13 的 **powerup 恒失败**造成的；
电源判据修好后，**全屏 GC16 清残影 11 次调用零 reset**。

**④ 结论：故障机现在的"可用档位"是**

| 档位 | 故障机 | 说明 |
|---|---|---|
| None / NORMAL | ✅ | 清 scope，交系统默认 |
| DU(1) | ✅ | 0/5；纯黑白 22 帧 |
| GC16(2) | ✅（偶发 1/5） | 全屏/局部都基本可用；全屏偶发卡住 |
| **清残影（98 全屏 GC16）** | ✅ **新解锁** | 磁贴 / `applyGCOnce()` / gcInterval 均走此路 |
| A2(4) / 2308 | ❌ | 2 次 reset/触发，仍不可用 |
| DU4(2312) | ❌ | 1 次 reset/触发（且是**局部** update，说明"全屏才 reset"的解释对 DU4 不成立） |
| 108 DEEP_GC16+FULL | ❌ | 1 次 reset/触发（内部先走 DU 全屏，卡在那里） |

⇒ **`RefreshModeHelper` 的档位表无需改动**（DU/GC16/NORMAL 照旧，A2/DU4 仍是"正常机专用"）；
但 **① 代码注释里"A2/DU4 / 全屏 GC16 都必然 reset"的机制归因需要改**（A2/DU4 的根因是另一套机制）；
**② 现在可以给故障机加"清残影"入口**（发广播最省事，无需反射、无需改 EAC）；
**③ EAC「全刷频率」(gcInterval) / 「切页自动全刷」(useGCForNewSurface) 在故障机上不再必然 reset**
（二者同走 `repaintEverything(98)` 路径，属推断，未单独实测），代价只是闪烁与变慢。

> ⚠️ **仍未闭环**：A2 / DU4 / 108 为什么会卡在 `frame_cur = 2`（无任何电源报错）。
> 候选方向：差分模式（A2 18/256、DU 42/256、DU4 84/256）在**全屏/宽幅更新**下的
> state 覆盖不足；以及 v6-A2 的 reset **重排队**仍是 `movz w0,#4`(A2) 全屏 ——
> 可能构成"卡死 → reset → 重排队 A2 全屏 → 又卡死"的自持环
> （⇒ 值得试 **v7G = v7A + 重排队改回 DU(1)**，见 ref §十六.8）。
> 另：裸 GC16(2) 的 1/5 偶发也需更长时间验证。
>
> **⇒ 上面这段已在 §13.10 被推翻并解决（2026-10-08）。**

### 13.10 ★★★ 第七次实测（2026-10-08）：第二层根因找到 —— `wait all_lut_free` 超时被 v5 压得太短

> **一句话**：`frame_cur = 2` **不是"卡住"** —— LUT 一直在正常推进。
> reset 的真因是 **`wait all_lut_free` 的超时只有 500 ms，而一个全屏 GC16 要跑 ~426 ms**；
> 两个全屏更新一排队（2×426≈850 ms）就必然超时 → reset → 重排队又一个全屏 → 级联。
> **v5 当初把该超时从 5 s 压到 500 ms 是为了让"故障机的失败循环"更快 ——
> 那个理由已随 §13 的 powerup 修复而消失，压短反而成了新的故障源。**

#### ① 推翻"卡在 cur=2"的误判（本轮最重要的一条）

用 `cat dump_list`（内容走 printk → dmesg）高频采样，实测**LUT 全程正常推进**：

| LUT | 类型 | 帧数 | 实测耗时 |
|---|---|---|---|
| `magic[1027]` | GC16 **局部** | 38 | **426 ms**（0→37） |
| `magic[1026]` | DU **全屏** | 22 | **358 ms**（1→…→21） |
| `magic[4]` | A2 **全屏** | 5 | **25–40 ms**（1→2→3→4） |

≈ **11 ms/帧**。而 reset 现场里那个"`frame_cur=2`"的 LUT，是**刚提交、才走了 2 帧**的那个 ——
`dump_lut_list()` 只在 reset 内部打印（ref §9.3.18⑤ 的老陷阱），
所以看到的永远是"超时那一刻恰好在飞的 LUT"，**不是卡住的 LUT**。

#### ② 最硬的一组对照：两个等待门的超时次数

| 等待门 | 语义 | 历史累计超时 |
|---|---|---|
| `0x542A70` `wait lut_free` | 等**某一个** LUT 空闲（局部更新走这条） | **0 次** |
| `0x543110` `wait all_lut_free` | 等**所有** LUT 空闲（全屏更新走这条） | **260+ 次** |

同一个设备、同一种波形，**只因为门更严就必然超时** ⇒ 指向"预算不够"，而不是"波形有问题"。

#### ③ 链表泄漏是**结果**不是原因（另一个被排除的假设）

`dump_full_marker_list` 在 reset 现场是 5→8→9→…→250 项，只增不减，一度像是根因。但**非 reset 时刻**的观测推翻了它：

| 动作 | full_marker 项数 | reset |
|---|---|---|
| 基线 | 113 | — |
| `98`(GC16\|FULL) ×4 | **110**（纹丝不动） | **0** |
| `A2(4)` ×1 | 114 | — |
| `A2(4)` ×2 | **242**（+128） | 有 |

⇒ **110 项时 98 照样成功** ⇒ marker 表不是阻塞条件；它是 **A2 + reset 级联的副产品**。
v7G 之后链表**自然排空到 0**（见 ⑤）。

#### ④ v7G / v7H 镜像（= v7F + 把 `wait all_lut_free` 恢复）

| 变体 | 改动 | boot md5 |
|---|---|---|
| **v7G** | `0x543110` `movz w1,#0x32`(50 jiffies=500 ms) → `#0xC8`(200=**2000 ms**)；`0x54314C` 日志参数 500→2000 | `96e2aec0b67a7c23b4d5ec3d4b52eb9c` |
| **v7H** | 同上两处**直接回到 stock 值**：`#0x1F4`(500 jiffies=**5000 ms**) / 日志 `5000` | `39ec0cb43213803d9ac4b79e7c987fc7` |

（单位 jiffies，HZ=100。stock 原值 500=**5 s**，v3 改 1 s、v5 改 500 ms；
**v7H 等价于把 v3/v5 对这个等待的全部压缩整体撤销**。）
v7H 与 v6-A2 逐字比对只有 **3 处不同**（v7F 的 1 处 + 超时 2 处），已用 `gs_verify_v7.py` 校验。

#### ⑤ ★★★ 实测结果：**六个档位全部零 reset**

同一脚本 `gs_v7g_test.sh`、同参数（每组前静置 20 s + 清 scope，n=5，含 swipe）：

| 档位 | v6-A2 | v7A/v7F | **v7G**(2000ms) | **v7H**(stock 5000ms) |
|---|---|---|---|---|
| DU(1) | 0/5 | 0/5 | **0/5** | **0/5** |
| GC16(2) | — | 1/5 | **0/5** | **0/5** |
| `98` 清残影(GC16\|FULL) | 必 reset | 0/5 | **0/5** | **0/5** |
| **A2(4)** | reset 循环 | **10/5** | **0/5** ✅ | **0/5** ✅ |
| **DU4(2312)** | 死亡谷 | **5/5** | **0/5** ✅ | **0/5** ✅ |
| **108** DEEP_GC16\|FULL | reset | **5/5** | **0/5** ✅ | **0/5** ✅ |

**混合负载 soak（各 13.5 min，180 次刷新跨全档位 + 180 次滑动）—— 两个镜像结果一致**：

```
                      v7G          v7H
frame            2053 → 8875   1905 → 8723
reset cause           0             0
wait all_lut_free     0             0
epdc power error      0             0
waveform NULL         0             0
卡住 LUT            （空）        （空）
WTF 增量             +7            +3
marker/pending/lut    全 0          全 0
update_err            0             0
```

> **v7G 与 v7H 在所有可测指标上等价。** 两者的差别只在**极端情况下**才体现：
> 等待超时是"上界"而非固定延时（`wait_event` 条件满足即返回），
> 所以 **5000 ms 不影响正常刷新的响应速度**，只在真出现卡死时把恢复时间从 2 s 拉到 5 s；
> 反过来 2000 ms 对更深的排队余量略小。实测排队深度 `pending` 仅 2~6 项、`lut` 1~2 项，
> 两个值都绰绰有余。
> **⇒ 长期建议 v7H（stock 值，与所有正常 Poke6 的 EPDC 时序完全一致，最少"自创值"风险）；
> 若哪天真的观察到长冻结，再换回 v7G。**

#### ⑥ 两层根因（完整版）

```
第 1 层（§13，v7A/v7F 已修）：PMIC powerup 判据恒假 reg0x0F==0xFA(实读0xBA)
  → 每次更新中途 regmap_write(reg1,0) 拉低电源轨 → 波形中止 → reset
第 2 层（本节，v7G 新修）：wait all_lut_free 超时 500ms < 全屏 GC16 的 ~426ms（余量仅 15%）
  → 全屏更新一排队（2×426≈850ms）必超时 → reset → 重排队全屏 → 级联
  → v5 压短它的理由（加快故障机失败循环）已随第 1 层修复而消失
```

#### ⑦ 对代码/使用的影响

| 项 | 变化 |
|---|---|
| **`RefreshModeHelper` 档位表** | **A2 / DU4 现在在故障机上也可用了** —— 注释里"A2/DU4 故障机必然 reset / 为正常机准备"**已过时**；DU4 是"4 级灰 + 24 帧"，是快与灰阶的折中，值得重估 |
| 清残影 | 磁贴 / `applyGCOnce()` / gcInterval 全部安全 |
| EAC「全刷频率」「切页自动全刷」 | 不再必然 reset（同走 98 路径） |
| **建议长期版本** | **v7H**（stock 5000 ms，已刷入并实测）；备选 v7G（2000 ms，实测等价） |

> ⚠️ **仍未闭环（诚实记录）**：`Reg PowerGood` 依旧**恒 0xBA**（本层与 PMIC 判据无关，
> 只是不再让它决定成败）；PG 判据为何恒假仍需硬件侧（datasheet / 量电压）才能定论。
> ~~把超时恢复成 stock 5000 ms 是否更好~~ → **已测（v7H），与 v7G 等价，见 ④⑤**。
> 另：温度变量**未有效排除**（CPU 满载只把 PMIC 从 27→28 °C，需热风枪/环境箱）；
> v7D 遗留的 `0x5F4744`（真正的 ~1.5 s msleep）**不建议盲压**。

---

## 14. ★★★ 第九会话（2026-11-06）：卡开机 = `onyxconfig` 的 MMKV 故障，与 boot/BCB 无关

> **一句话**：设备「开机动画跑很久 → 无显示 → adb unauthorized」不是卡 kernel，
> 而是**落进了原厂 recovery**；而反复进 recovery 的根因是
> `/onyxconfig/mmkv/onyx_config` 里 `eac_app_pkg_set` 被清到只剩 `com.onyx`，
> 且该包的三条 EAC 键被删除 ⇒ 启动时无配置可用 ⇒ 卡死。
> **恢复出厂擦 `/data` 但不擦 `/onyxconfig`**，所以恢复到出厂也修不好。

### 14.1 现场判据（先分清「卡开机」与「在 recovery」）

| 证据 | 含义 |
|---|---|
| USB PID = `18D1:D001`（稳定不掉线） | **在原厂 recovery**，不是卡 boot |
| `adb devices` → `unauthorized`（且无授权框） | recovery 里 `ro.adb.secure=1` 且无 UI ⇒ 永久 unauthorized |
| 有 bootanimation 但跑得久 | ABL 走完 boot 阶段后跳去 recovery |
| 之后无显示 | 本机 recovery 是**盲的**：`librecovery_ui_ext.so` 的 `.text` 仅 88 B，无 EPD 初始化/波形（§3.6） |
| 「无授权」+「无显示」 | **同一原因的两面**，不是两个故障 |

> ⚠️ 教训：把 `unauthorized` 当成「adb 授权丢了」去折腾密钥是**方向错误**。
> 先看 USB PID —— `D001` = recovery，`4EE0` = fastbootd，`4EE2` = ABL fastboot（5 秒即死），
> `05C6:9008` = EDL。

### 14.2 两次全分区回读的 md5 对比（102 个文件，78 同 / 24 异）

24 个差异里，**只有 3 个与故障相关**，其余全是正常变化：

| 类别 | 分区 | 说明 |
|---|---|---|
| **A/B 槽互换**（10 个） | `abl`/`dtbo`/`recovery`/`vbmeta`/`vbmeta_system` 的 a/b | 7-17 备份在 **B 槽**，当前在 **A 槽**，a/b 内容互为镜像 ⇒ **正常** |
| **会随使用变**（8 个） | `gpt_*`/`frp`/`metadata`/`persist`/`uefivarstore`/`modemst1,2`/`ddr` | 恢复出厂必改 ⇒ 正常 |
| **需关注**（3 个） | `onyxconfig` / `misc` / `super` | misc 只是 BCB（重启正常写）；super 见 §14.5 |

### 14.3 ★★ 每机专属数据（**绝不可跨机覆盖**）

对比两台机的 `onyxconfig` 时发现的 5 处身份数据（这是本会话最重要的安全结论）：

| 文件 | 内容 | 两台机实测差异 |
|---|---|---|
| `DEVICE_SERIAL_NUMBER_KEY` | 14 位设备序列号 | `126525225B0481` vs `121524A30B0399` |
| `mac_address` | 蓝牙/WiFi MAC | `00:0a:25:1d:bd:e6` vs `00:0a:be:24:f8:07` |
| `test_result_info` | 产测记录（含 fingerprint + mac + 各项结果） | QA 日期 `2024-11-22` vs `2024-08-27` |
| `ONYX_SYSTEM_CONFIG_KEY` | 系统设置 JSON（`deviceStatus`、笔刷新参数） | 263 B vs 189 B，字段数不同 |
| **`com.android.vcom`** | **VCOM 电压校准**（面板专属） | 内容不同 |

⇒ **整盘用备份覆盖 `onyxconfig` = 改掉序列号 + MAC + 产测 + 面板校准。禁止。**
⇒ 而 `eac_theme_*` 的 JSON **不含任何机器专属信息**（已抽样 10 个验证 clean），
   所以可以安全地拿别的机器的健康 theme 值做模板。

### 14.4 ★★★ 根因：`eac_app_pkg_set` 被清空到只剩 1 个成员

`eac_app_pkg_set` 是 OECService 启动时**遍历哪些 app 的 EAC 配置**的依据
（`EACDeviceConfig.load()` 读它 → 逐个 `EACAppConfig.load()`）。

| | 7-17 健康机 | 11-06 当前机 |
|---|---|---|
| `eac_app_pkg_set` | **1507 B**（几十个包：`com.zxunity… com.tencent.mobileqq com.amazon.kindle com.onyx.aiassistant …`） | **10 B（只有 `com.onyx`）** |
| `eac_theme_com.onyx@theme_type_3` | ✅ 2832 B | ❌ **墓碑（vlen=0）** |
| `eac_app_com.onyx` | ✅ 1952 B | ❌ **墓碑** |
| `eac_default_app_configcom.onyx` | ✅ 1948 B | ❌ **墓碑** |

**因果链**：

```
启动 → OECService.EACDeviceConfig.load()
  → 读 eac_app_pkg_set = {"com.onyx"}          ← 集合只剩 1 个
  → loadThemeOrCreate(com.onyx, theme_type_3)  ← 唯一成员
  → theme_type_3 已删 → 6 级回退
  → 回退依赖 eac_app_com.onyx / eac_default_app_configcom.onyx  ← 也全删了
  → 全部落空 → createTheme()
  → com.onyx 是【唯一桌面】⇒ 启动链卡死/超时 → 落 recovery
```

其余 45 个被删的包**不在 pkg_set 里**（因为 pkg_set 只剩 com.onyx），所以删它们无害。
**真正致命的是「pkg_set 唯一的成员恰好配置全被删」这个组合。**

### 14.5 MMKV 的四个认知修正（都是本会话踩过的）

| 曾经的误判 | 实测真相 |
|---|---|
| 「46 个 theme 被写成 0 字节 = 数据损坏」 | ❌ `vlen==0` 是 **MMKV `remove()` 的墓碑**，是正常语义 |
| 「MMKV 在 2MB 边界扩容时写坏」 | ❌ 文件结构完好；`actual_size` 一致、crc 一致、记录可完整解析 |
| 「恢复出厂后所有残留都被清」 | ❌ `/onyxconfig` 是**独立 ext4 分区**（`mmcblk0p80`），恢复出厂**不擦** |
| 「`eac_theme_` 出现次数 ×10 = 键 ×10」 | ❌ append-only 文件里历史记录都在，字符串计数 ≠ 有效 key 数 |

**MMKV 正确语义**（详见 `tools/einkimg/README.md`）：
- append-only：写同 key 是追加新记录，读取时**后写覆盖先写**
- `remove()` 只追加 `vlen=0` 墓碑，**不回收空间**，运行期文件只涨不缩
- 判断 key 状态必须取**最后一次出现**的记录
- ⚠️ **但「只涨不缩」只在运行时单进程 append 前提下成立**（§14.12 实测修正）：
  重启/开机后系统会做 **full write-back**，把墓碑与历史旧记录全部回收、有效键压回干净文件。
  所以「堆积隐患会无限累积」对**开机这条路径**是过虑的；运行期逼近容量边界才是真正风险点。

### 14.6 super.img 对比：系统版本完全一致

**LP metadata 解不出来**（Poke6 回读镜像的 geometry magic 后字段是随机值，
疑似被混淆）。绕开它、**暴力扫 ext4 超级块**成功定位 5 个分区：

| 分区 | 起点 | 大小 | volname |
|---|---|---|---|
| system | `0x0100000` | 2268 MB | `/` |
| system_ext | `0x07E400000` | 282 MB | `system_ext` |
| product | `0x08F500000` | 526 MB | `product` |
| vendor | `0x0AED00000` | 499 MB | `vendor` |
| （B 槽 system 副本） | `0x0D896F000` | 2268 MB | `/` |

**逐 4 MB 块 diff**（1024 块）：342 块不同，**全部集中在 2118 MB 之后**：

```
0    ~ 2118 MB   系统分区（system/vendor/product/system_ext/odm）  逐字节一致 ✅
2118 ~ 3549 MB   userdata（app/数据/设置）                        全变（恢复出厂所致）
3549 MB 之后     少量零散块（日志/元数据）
```

⇒ **没做过 OTA，系统本体一字未改。** 变的全是用户数据 + `onyxconfig`。

### 14.7 OTA 接口逆向（结果：服务器无包）

反编译 `ksync-release.apk`（83MB，含 `com.onyx.android.sdk.firmware` 完整 SDK）与
`OnyxOtaService.apk`（389KB，只有 UpdateManager/PackageFiles，无网络逻辑）。

**① 查询接口（两套，等价，均 `GET firmware/update`）**

```java
// 新 SDK：com.onyx.android.sdk.firmware.api.OnyxOTAService（classes6.dex）
@GET("firmware/update")
Call<Firmware> firmwareUpdate(@Query("where") String json);
// base = ClusterHost.DEFAULT_CHINESE_DATA_HOST + "/api/" = https://data.boox.com/api/

// 老 SDK：com.onyx.android.sdk.data.v1.OnyxOTAService（classes5.dex）
// base = cloudConf.getApiBase()（同 ClusterHost，见下）
```

`where` 参数由 `FirmwareBean.currentFirmware()` 生成，字段：
`{model, fingerprint, buildNumber, buildType, fwType, deviceMAC, submodel, lang}`
- `model` = `Build.MODEL`（Poke6）
- `fingerprint` = `SystemPropertiesUtil.getFingerprint()`（`ONYX/BOOX/BOOX:11/RKQ1.210614.002/200:userdebug/release-keys`）
- `buildNumber` = `FirmwareUtils.getBuildIdFromFingerprint()`：取 fingerprint 倒数第 2 段 `200:userdebug` 的 `:` 前部分 = **200**
- `buildType` = 同上取 `:` 后部分 = **userdebug**
- `deviceMAC` = `NetworkUtil.getMacAddress()`
- `submodel` = `ro.onyx.sub_model` 系统属性
- `fwType` = `release`（或 `test`）
- `lang` = 当前语言（如 `zh_CN`）

**② 下载方式（★ 关键：地址是服务器动态返回，非本地拼装）**

响应 `Firmware` 的 `downloadUrlList[]`（`List<String>`）就是固件包下载地址列表；
`getUrl()` 取 `downloadUrlList.get(0)`。**客户端没有本地拼装下载 URL 的逻辑**，
完全依赖服务器下发的 `downloadUrlList`。落地下载用系统 `DownloadManager`（`DownloadUtils`）
或 `OnyxDownloadManager`（liulishuo FileDownloader 库）。

响应其它字段：`md5` / `size` / `force` / `wipeData` / `changeList[]` / `buildDisplayId` / `guid` 等。

**③ ClusterHost 完整 host 常量（`com.onyx.android.sdk.data.ClusterHost`）**

| 常量 | 值 | 用途 |
|---|---|---|
| `DEFAULT_CHINESE_DATA_HOST` | `https://data.boox.com` | 国区数据/OTA 查询 |
| `DEFAULT_AMERICAN_DATA_HOST` | `https://en-data.boox.com` | 美区数据/OTA 查询 |
| `DEFAULT_EUR_DATA_HOST` | `https://en-data.boox.com` | 欧区（同美区） |
| `DEFAULT_CHINESE_BOOX_HOST` | `https://send2boox.com` | 国区主站 |
| `DEFAULT_AMERICAN_BOOX_HOST` | `https://push.boox.com` | 美区主站 |
| `DEFAULT_EUR_BOOX_HOST` | `https://eur.boox.com` | 欧区主站 |
| `DEFAULT_CHINESE_MATERIAL_HOST` | `https://client.send2boox.com` | 国区资源 |
| `DEFAULT_AMERICAN_MATERIAL_HOST` | `https://en-client.boox.com` | 美区资源 |
| `DEFAULT_CHINESE_LOG_HOST` | `https://log.boox.com` | 国区日志 |
| `DEFAULT_TEST_HOST` | `https://dev.send2boox.com` | 测试 |

⇒ **OTA 固件下载链路**：`GET {data-host}/api/firmware/update?where=<JSON>` → 服务器判定
→ 有包则 `downloadUrlList[]` 返回 CDN 直链 → `DownloadManager`/FileDownloader 下载。
**下载地址的域名由服务器决定，不在客户端代码里。**

**④ v1 接口里的其它 OTA 相关端点（非固件主链路）**

`com.onyx.android.sdk.data.v1.OnyxOTAService` 还有：
- `POST firmware/downloadFirmware` → `reportFirmwareUpdate`（**上报**固件下载记录，非下载）
- `POST TpFirmwares/getUpdate` → 触控面板(TP)固件查询
- `POST betaGroups/applyBeta` / `cancelBeta` / `queryBeta` → beta 测试组
- `GET app/list` / `app/batchUpdate` / `app` → 应用商店的 App 更新（非系统固件）
- `GET apks/*` / `categories/app` / `deviceconfigs/query` → 应用商店

**⑤ 实测结果（`.diag/ota_probe*.py` 早期尝试，已作废见 §14.20）**

- 缺 `deviceMAC` → HTTP 500（报错暴露服务端路径 `firmware.model.js:418`）
- 补齐 → **HTTP 204（无更新）**
- buildNumber 0~201 / 5 种 model 写法 / 3 种 submodel / 2 个 host **全部 204**
- ⚠️ 服务器弱 DH：OpenSSL 3.x 需 `ctx.set_ciphers("DEFAULT@SECLEVEL=1")`

~~⇒ 当前拿不到 Poke6 固件包。~~ **【作废】**——§14.20 实测：正确的查询法是
`buildNumber:0` + 空 `fingerprint` + 空 `deviceMAC` + 正确 model 名，且要加
第三个服务器 `data.onyx-international.cn`。用真实 `buildNumber:200` 查询只会 204
（服务器判定「已是最新」）。Poke6 固件包已实测拿到。

### 14.8 修复方案与实测产物

**`out/onyxconfig_fixed.img`**（25,165,824 B，md5 `fcca658d4cf46f86b52041e21bc87250`）

做法（`tools/einkimg/repair.py`，可复现）：

1. **底 = 当前机镜像**（`poke6_9008_new`）⇒ 25 个文件里 23 个逐字节不动
2. 只往 MMKV **append** 3 条记录（+6818 B，`actual_size` 1962798 → 1969616，未超 2 MB）
3. 同步重算 `onyx_config.crc`（`crc[0] = CRC32(mmkv[4:4+actual])`）
4. 自检：除 `mmkv/onyx_config` 与 `.crc` 外**零变更**

```
python tools/einkimg/repair.py \
  --base  poke6_9008_new/images/onyxconfig.img \
  --donor poke6_9008/images/onyxconfig.img \
  --out   out/onyxconfig_fixed.img \
  --restore 'eac_theme_com.onyx@theme_type_3,eac_app_com.onyx,eac_default_app_configcom.onyx'
```

**刷写**（EDL，只写一个分区）：

```powershell
edl --loader=<QCM6125 loader> --memory=ufs printgpt        # 先确认分区名/大小
edl --loader=<QCM6125 loader> --memory=ufs w onyxconfig out/onyxconfig_fixed.img
edl --loader=<QCM6125 loader> --memory=ufs w misc      out/misc_zero_1mb.img   # 顺带清 BCB
edl --loader=<QCM6125 loader> --memory=ufs reset
```

> `out/misc_zero_1mb.img` = 1 MiB 全零，md5 `b6d81b360a5672d80c27430f39153e2c`
> **与文档记录的官方备份完全一致**，可放心自造。

### 14.9 环境坑（本会话新踩）

| 坑 | 说明 |
|---|---|
| `bkerler/edl` 在 Windows 缺 libusb 驱动 | 报 `Please first install libusb_win32 driver from Zadig`；本项目用**高通工具箱**的 `QSaharaServer.exe` + `fh_loader.exe` 更稳 |
| pip 装 `edl` 失败 | Python 3.13 下 `setup.py` 缺 `timecode`；改用 `git clone` + 直接跑 `edl.py` |
| `qctool.bat edlreadall <端口> <目录> [loader]` | 高通工具箱的全分区回读入口，产出 `images/` + `for_ROM_maker/` |
| Python 控制台 GBK | 打印中文/二进制会 `UnicodeEncodeError`，用 `$env:PYTHONIOENCODING='utf-8'` |
| PowerShell 吃引号 | `python -c @"..."@` 里含 `"` 就会坏；**一律写成 .py 文件再跑** |
| `$pid` 是只读变量 | PowerShell 脚本里不能赋值，否则刷屏报错 |

### 14.10 launcher 的责任边界（launcher 是「容量膨胀」的累犯）

`GlobalEacRefreshHelper.applySet/applyReset` 都是 `for (String t : themes)` **遍历全部
theme 重写**并 `saveEACAppThemes`，把每个 app 从「只有 theme_type_3」写成「三套齐全」：

```
出厂形态: theme_type_1=3, theme_type_2=3, theme_type_3=74   （74 个 app 只写一份）
当前形态: theme_type_1=79, theme_type_2=79, theme_type_3=79 （79 个 app 三套全写满）
```

⇒ 数据量膨胀 3 倍，把 2 MB 的 MMKV 推向容量边界。**这个设计缺陷至今仍在代码里。**

本次崩机的直接触发器是 `FixEac rm`（删 44 个已卸载 app × 5 键 = 220 次 remove，
产生 185 条墓碑），但**launcher 长期全量重写是把 MMKV 撑到边界的结构性原因**。

**待办（未实施）**：
1. `applySet` 只写 **theme_type_3**（active theme），不动 1/2 ⇒ 写入量降到 1/3
2. 写前 diff：值没变就不写 ⇒ 幂等操作变零写入
3. 容量守护：接近 2 MB 时拒绝写 / 先清理
4. launcher 自身被卸载时清理它写下的 EAC（现有 DeviceAdmin 钩子只清「已卸载 app 的残留」）
5. ⚠️ **`GlobalEacRefreshHelper cleanup`（本轮新增）有与本次事故同源的隐患**：
   `runCleanup()` 逐个 `mmkvRemove()` 删除「已卸载 app」的键 ⇒ 继续制造墓碑 ⇒ 继续把
   MMKV 推向容量边界。且它的判定用 `pm list packages`（installed），**不看 `eac_app_pkg_set`**，
   理论上可能删掉「已从系统卸载、但名字仍留在 pkg_set 里」的 pkg 的配置。
   **改进方向**：清理前先读 `eac_app_pkg_set`，凡仍在其成员列表里的 pkg 一律跳过并告警；
   长期看应改为「重建一个干净的 MMKV」（dump 有效 key → 全新文件）而非逐个 remove。

### 14.11 未闭环 / 待验证

| 项 | 状态 |
|---|---|
| 修复镜像实机刷入后能否正常开机 | ✅ **已刷入并正常开机**（见 §14.12） |
| `pkg_set` 为何从 1507 B 缩到 10 B | ⚠️ 推测是 `FixEac rm` 的连锁影响，未直接验证 |
| 是否还有其它分区被改（除已比对项） | ⚠️ 只比了 102 个命名分区，`Unalloc_*` 未逐个比对 |
| 「只写 theme_type_3」是否会被 `loadThemeOrCreate` 重建覆盖 | ✅ **已反编译 framework 定论**（见 §14.13） |

### 14.12 ★ 实机刷入验证 + 「堆积」自愈（2026-11-06 第十会话）

**修复镜像 `out/onyxconfig_fixed.img` 已实机刷入并正常开机**（设备 `6C7F0E64`，
adb `device` 状态，非 recovery）。拉取实机 `/onyxconfig/mmkv/onyx_config`（shell 可直读，文件 777）
与刷入前 fixed 镜像、donor 健康机逐项对比，结论是**反转**：

| 指标 | 刷入前 fixed 镜像 | **实机现在** | donor 健康机 |
|---|---|---|---|
| actual_size | 1,969,616（逼近 2MB） | **1,440,561**（余量 656KB） | 852,375 |
| 记录总数 | 1260 | **707** | 415 |
| 有效 key | 252 | **298** | 273 |
| **墓碑** | **182** | **0** | 0 |
| crc | OK | **OK** | — |

**核心发现：墓碑 182→0、actual 1.97MB→1.44MB。** MMKV 是 append-only，运行期单靠写入只会涨不会缩；
墓碑被物理清零、体积下降只有一个可能——**开机/启动阶段系统对 MMKV 做了一次 full write-back
（compaction）**，把墓碑和几十轮累积的旧记录一次性回收。

**`eac_app_pkg_set` 也被系统自己修好了**（正确的 varint 解码后）：

| | 内容 |
|---|---|
| 刷入时 | 10 B，只剩 `com.onyx`（卡开机根因） |
| 实机现在 | **1523 B，73 个包** |
| donor | 1507 B，72 个包 |

差异全部解释得通：donor 有而实机无的 `com.x1y9.probe`（测试包已卸）、`com.android.permissioncontroller`
（现走 apex）；实机多出的 `com.bilibili.comic`、`com.tencent.weread.eink`、`com.topjohnwu.magisk`
（`pm path` 已确认 magisk 真装）。**每个 pkg_set 成员都有完整 EAC 配置，无一缺失**；三个当初恢复的
关键键（`eac_theme_com.onyx@theme_type_3`/`eac_app_com.onyx`/`eac_default_app_configcom.onyx`）均在。

⇒ **结论**：刷入的 fixed 镜像只是给了系统一个能起跑的起点；真正把 MMKV 从「堆积+墓碑」状态清干净、
并把 pkg_set 重建回完整形态的，是系统启动时自己的逻辑。**当前 onyxconfig 状态干净、健康、crc 正确，
无需再做任何刷写或清理动作。**

### 14.13 ★ framework 反编译：`saveEACAppThemes` 按 JSON 内容映射 key（待办 1 可行性定论）

从设备拉 `framework.jar`（33.5MB，4 个 dex）用 jadx 反编译，把 `applySet` 的写入链封死：

```
EInkHelper.saveEACAppThemes(List)                    [framework.jar: EInkHelper.java]
→ OECService.saveEACAppThemes(List)                  [OECService.java:1872]
→ new SaveEACAppThemesAction(List).execute()
→ 逐条 FastJSONUtils.parseObject(json, EACAppTheme)  [SaveEACAppThemesAction:44-50]
→ theme.save()                                       [:26]
→ saveValue(myThemeKey(), …)                          [EACAppTheme.java:126-132]
→ myThemeKey() = themeKey(getPkg(), getThemeType())   [EACAppTheme.java:139-141]
→ "eac_theme_<pkg>@theme_type_<type>"                [EACThemeUtil.java:30-32]
```

**三个源码级定论**：
1. **映射与 list 顺序/长度无关**：`save()` 用 JSON 反序列化后对象的 `pkg`+`themeType` 字段生成 key。
   ⇒ 只传 1 个 type_3 的 JSON，就只写 type_3 一个 key。旧版把 `loadThemes` 返回的 `[1,2,3]`
   （`createThemeKeys` 固定三项，每项走 `loadThemeOrCreate` 兜底生成内存对象）全改全存，
   就是把出厂「仅 type3」形态（donor 71/74 包只有 type_3）膨胀成「三套齐全」≈3 倍写入量。
2. **生效的只有 type_3**：`EACAppThemeManager.DEFAULT_ACTIVE_THEME = 3`，`getActiveThemeType(pkg)`
   默认 3，launcher 从不改 ⇒ **只写 type_3 安全且正确**（待办 1 可行）。
3. **`getInt/optInt` 读 `themeType` 字段**：theme JSON 末尾有 `"themeType":3`（实测确认），
   用它判定 active theme 比按 list 下标更可靠。

### 14.14 ★ launcher 堆积根因修复（§14.10 待办 1–5 收敛补全，已改代码）

`app/src/main/java/cn/modificator/launcher/GlobalEacRefreshHelper.java`：

| 待办 | 改动 |
|---|---|
| 1 只写 theme_type_3 | `applySet`/`applyReset` 循环内 `if (root.optInt("themeType", -1) != 3) continue;`，type 1/2 不再写 |
| 2 写前 diff | `applySet`/`applyDefaultCfg`（`writeDefaultCfgKey`）/`applyReset`（`writeResetKey`+`resetThemeJson`）都做写前 diff，目标值已等于现值就跳过 ⇒ 幂等操作零写入；全跳过返回 `no-change … save skipped` |
| 3 容量守护 | `hasMmkvCapacity()`：读 `/onyxconfig/mmkv/onyx_config` 头 4 字节 LE 的 actual_size，剩余 `< 256KB` 时拒绝写入（提示重启让系统 full write-back 回收）。挂到 `main()` 所有写命令入口（`set`/`defcfg`/`reset`/`restore` 的 `init()` 之后、以及 `cleanup` 分支内），不足时 `System.exit(4)` |
| 5 cleanup 不看 pkg_set 的隐患 | `runCleanup()` 新增 `readPkgSetMembers()`：读系统内部键 `eac_app_pkg_set`（StringSet 语义），凡仍在其成员列表里的 pkg 一律跳过并告警，避免删掉「已卸载但名字仍留 pkg_set」的 pkg 配置（重蹈卡开机） |

**语法校验**：`javac -source 17 -target 17 -cp android.jar + HiddenApiBypass stub` 编译通过（退出码 0，
仅剩原有的 unchecked 警告）。**未跑完整 gradle 构建**（本机无 gradle 9.1.0 / compileSdk 36 /
hiddenapibypass 依赖缓存，完整构建需联网耗时）。

### 14.16 ★★★ 路线调整：默认纯 scope，EAC 降级为独立手动工具（2026-11-06 第十一会话）

**用户决定**：切换刷新模式**不能再给 onyxconfig 遗留问题**，即便牺牲便捷性。落地为——

1. **默认走纯 scope 通道**（`RefreshModeHelper`）：byPass + applyAppScopeUpdate + repaint，
   **不写 /onyxconfig、不落盘、重启即回系统默认 GC16**。
2. **EAC 方案（`GlobalEacRefreshHelper`）不删除**，而是「收敛补全 + 独立出来 + 备注」，**平时不自动触发**，
   仅在需要持久改第三方 app 翻页/滚动波形、或修复历史残留时由用户手动调用。

**代价（必须接受的 trade-off）**：scope 通道**不改第三方 app 的合成翻页/滚动波形**（恒 GC16，
ref.md §6282 实测），只影响显式全刷/repaint。这是「零遗留」的物理必然——改真实翻页波形只能走
EAC 写 onyxconfig（落盘、append-only、有累积风险），两者不可兼得。

**代码定位标注**：`RefreshModeHelper` 类头、`GlobalEacRefreshHelper` 类头、
`RefreshModeHelper.resetRefreshConfig()` 均已加「默认 scope / EAC 是手动工具 / 平时不启用」的定位注释。

### 14.17 ★ 官方「净清/标准/极速」三档对应（实测 `poke6_systemui.json`）

来源：`framework-res.apk` 的 `res/raw/poke6_systemui.json`（`refreshConfigMap` + `refreshConfig` 顺序）
+ SystemUI `strings.xml` 中文名：

| 官方中文名 | refreshModeIndex | 逻辑 mode | title | Poke6 实际物理波形 |
|---|---|---|---|---|
| **净清** | `refresh_mode_1` | 5 = REGAL_PLUS | REGAL_PLUS | GC16 38帧（槽4=GC16副本） |
| **标准**（高清/HD） | `refresh_mode_4` | 0 = NORMAL | HD | GC16 38帧（系统 hook 代理） |
| **极速** | `refresh_mode_3` | 2 + turbo=5 | NEW_SPEED | GC16 38帧（槽2） |

`refreshMigrateMap = {0→3, 1→2, 2→2, 3→5, 4→2}`，`refreshMigrateMapForSystemApp = {0→0, 1→2, 2→2, 4→2}`。

**关键结论**：官方这三档在 Poke6 上**物理全是 GC16 38 帧**——Poke6 的 wbf 里没有独立 REGAL 短波形
（REGAL 列被填成 GC16 副本，ref.md §6297 已修正「极速=A2」的早期误记）。真正的 A2 数据被错位放到
mode6。**launcher 要的 DU(22帧)/A2(5帧) 短帧官方档位里根本没有**，只能走
`refreshModeIndex=NONE + updateMode 直通`（ref.md §6305-6306、§6321 已实测 DU 直通通过）。

### 14.18 遗留（第十一会话追加）

| 项 | 状态 |
|---|---|
| 完整 gradle 构建 + 实机 dex 验证改动生效 | ⏳ 未做（环境缺 gradle/SDK 36，见 §14.14） |
| `applyRestore`（None 档）也收敛为「只写 type_3 + diff」 | ⏳ 未做（restore 是「从备份还原原始三套 theme」语义，与 set/reset 不同，暂保留） |
| `pkg_set` 缩水根因直接验证 | ⏳ 未做（实机已自愈，无从复现） |
| 设置页「还原为原始状态」入口 | ✅ **已隐藏**（2026-11-06 第十二会话：从日常弹窗移除，仅靠 `GlobalEacRefreshHelper reset` 手动命令触发） |
| LSP 替换官方三档为 GC16/DU/A2 | ⏸️ **暂停**（用户决定到此为止，见 §14.16 权衡与 §14.17 的「翻页恒 GC16」物理约束） |
| 把正确 OTA 查询法（buildNumber:0 + 三服务器）固化进 `.diag/ota_query.py` | ✅ **已完成**（2026-11-06：`.diag/ota_query.py` 已改为 buildNumber:0 查询法 + 三服务器 + 打印 downloadUrlList） |

### 14.19 ★ 系统更新组件反编译：OTA 包下载方式（2026-11-06 第十二会话）

从设备拉取并反编译三个系统更新组件：

| APK | 大小 | 反编译关键发现 |
|---|---|---|
| `OnyxOtaService.apk`（`/system/priv-app/OnyxOtaService/`） | 389 KB | 仅 `UpdateManager`（进度）+ `PackageFiles`，**无网络逻辑**，实际下载不在它 |
| `ksync-release.apk`（`/system/app/ksync-release/`） | 83 MB | 含 `com.onyx.android.sdk.firmware` 完整 SDK：`OnyxOTAService` / `FirmwareUpdateRequest` / `Firmware` / `FirmwareUtils` |
| `app-market-release.apk`（`/system/app/app-market-release/`） | 21 MB | 应用商店，含老版 `data.v1.OnyxOTAService` |

**完整 OTA 下载链路**（源码级确证）：

```
用户点「检查更新」→ FirmwareUpdateAction → FirmwareUpdateRequest.execute()
  → FirmwareBean.currentFirmware()            // 组装 where 参数
  → GET {data-host}/api/firmware/update?where=<JSON>
  → 服务器返回 Firmware.downloadUrlList[]     // ★ 下载地址由服务器动态下发
  → Firmware.getUrl() = downloadUrlList[0]     // 取第一个
  → DownloadUtils / OnyxDownloadManager        // 系统 DownloadManager 或 FileDownloader 库
```

**★ 核心结论（用于比对用户发现的网址）**：
1. **下载地址不在客户端代码里硬编码**，是 `downloadUrlList[0]` 动态返回——服务器会根据
   `model/fingerprint/buildNumber` 匹配后返回对应 CDN 直链。
2. 客户端唯一硬编码的是**查询 host**（`data.boox.com` / `en-data.boox.com`），不是下载 host。
3. 服务器当前对 Poke6 全部返回 204（无包），所以 `downloadUrlList` 从未被真正拿到过——
   **无法从本机验证下载 URL 的实际域名**。
4. 若用户发现的网址是 `*.boox.com` / `*.send2boox.com` / OSS/CDN 之类，需与
   `downloadUrlList` 的域名比对；若 `downloadUrlList` 为空（当前 204），则用户看到的
   网址要么来自其它机型/其它 buildNumber，要么来自服务器后端的另一套分发逻辑。

**可复现脚本**：`.diag/ota_query.py`（单次查询，含 where 组装）、`.diag/ota_probe.py`
（buildNumber 枚举）、`.diag/ota_probe2.py`（model/submodel/buildType 组合枚举）。
反编译产物：`out/jadx_ksync/sources/com/onyx/android/sdk/firmware/`、
`out/jadx_ota_otg/sources/com/onyx/android/onyxotaservice/`。

### 14.20 ★★★ 用户发现：正确查询法实测拿到 Poke6 固件包（2026-11-06，推翻 §14.7 旧结论）

**用户提供的完整 upx 固件获取链接**（两套服务器 + `buildNumber:0` 查询法）：

```
国内版：http://data.onyx-international.cn/api/firmware/update?where={"buildNumber":0,"buildType":"user","deviceMAC":"","lang":"zh_CN","model":"P6","submodel":"","fingerprint":""}
国外版：http://data.boox.com/api/firmware/update?where={"buildNumber":0,"buildType":"user","deviceMAC":"","lang":"en_US","model":"Palma","submodel":"","fingerprint":""}
```

**★ 三个关键点（都是之前 §14.7 漏掉的）**：

1. **第三个查询服务器** `data.onyx-international.cn` —— 反编译代码里**没有**（`ClusterHost`
   只列了 `data.boox.com` / `en-data.boox.com` / `push.boox.com` 等），是国区的**新/镜像服务器**。
2. **查询法是 `buildNumber:0` + 空 `fingerprint` + 空 `deviceMAC`**，不是本机真实值。
   `buildNumber:0` 语义 = 「查最新版」；用本机真实 `buildNumber:200` 会被判「已最新 → 204」。
3. **`model` 用 `P6` 或 `Poke6` 都能查到**（`P6` 是老代号，`Poke6` 是新名）。

**实测结果（`out/_ota_verify2.py` + 修正版 `.diag/ota_query.py`，2026-11-06 全部验证通过）**：

| 服务器 | model | 结果 | buildNumber | size | md5 | 下载 URL |
|---|---|---|---|---|---|---|
| `data.onyx-international.cn` | P6 | ✅ 200 | 1649 | 1,780,276,767 | `e007253b…` | `http://firmware.boox.com/e007253b…/update.upx` |
| `data.onyx-international.cn` | **Poke6** | ✅ **200** | **47589** | **1,774,182,700** | `c063d8e9…` | `http://firmware.boox.com/c063d8e9…/update.upx` |
| `data.boox.com`（国区旧） | **Poke6** | ✅ **200** | **51326** | **1,719,778,827** | `71db2e56…` | `http://firmware.boox.com/71db2e56…/update.upx` |
| `data.boox.com` | Palma | ✅ 200 | 45135 | 1,657,375,093 | `08a88fc4…` | `http://firmware-us.boox.com/08a88fc4…/update.upx` |
| `en-data.boox.com`（美区） | **Poke6** | ✅ **200** | **47589** | 1,774,182,700 | `c063d8e9…` | `http://firmware.boox.com/c063d8e9…/update.upx` |

⚠️ **Poke6 固件在服务器上是动态更新的**：同一天内 `data.boox.com`（国区旧）返回的
buildNumber 从 47589 涨到 **51326**（最新版），`en-data.boox.com`/`data.onyx-international.cn`
仍是 47589。**要拿最新包用 `data.boox.com`（国区旧服务器）**；三个服务器返回的
`model` 名写法（`P6` vs `Poke6`）会影响匹配到的包。

**下载域名规律（★ 关键）**：
- 国区（`data.onyx-international.cn` / `data.boox.com` 中文）→ `http://firmware.boox.com/{md5}/update.upx`
- 美区（`data.boox.com` 英文）→ `http://firmware-us.boox.com/{md5}/update.upx`
- **下载 URL = `{firmware-host}/{md5}/update.upx`**，路径就是固件包 md5。
- 下载链接已验证可通（HTTP 206 分片下载正常）。

**`.upx` 文件格式**：文件头是**密文**（前 14 字节是固定 magic `694acd494f615a78a39af123df79`，
之后是 AES-CFB 密文流，无 zip/ELF 签名）。⚠️ 早期误判「前 32 字节 = md5」已更正——实为
14 字节固定头 + 密文。

**★ `.upx` 加密/解密机制（2026-11-06 反编译 + 开源工具双验证，已实测解密成功）**：

`.upx` = **AES-128-CFB(segment=128) 加密的 `update.zip`**（A/B OTA 包）。算法与开源项目
[Hagb/decryptBooxUpdateUpx](https://github.com/Hagb/decryptBooxUpdateUpx) 完全一致。

**Poke6 的 Key/IV（`BooxKeys.csv` 第 94 行）**：
```
Key = DFA1B679E1089011FEB9C8455AD7E1D5   (16 字节 hex)
IV  = 83BF5F0F69A76D42D742E044EA1BDACF   (16 字节 hex)
算法 = AES.MODE_CFB, segment_size=128    (= AES/CFB/NoPadding)
分块 = 4 KiB
解密后前 4 字节必须 == PK\x03\x04 (zip magic)
```

解密链（`OnyxOtaService.UpdateManager.decryptPackage()` + `libota_jni.so` 反汇编）：
```
update.upx ──AES-128-CFB(key, iv)──> update.zip
  key/iv 由 libota_jni.so 的 native 方法返回：
    nativeGetSecretKey()/nativeGetIvParameter() 内部做 reorganizeSecretKey/... 混淆重组
    最终返回的 16 字节 hex 就是上面 BooxKeys.csv 里的 Key/IV
  混淆算法（RsaUtil.java）：DES-CFB 解 base64 → 得 hex key/iv
    DES key = generateKey(Build.MODEL + Build.MODEL) = MD5("Poke6Poke6")[:16] = b97485d8657caf35
    DES IV  = FFFFFFFFFFFFFFFF...
```

**★ 实测（2026-11-06）**：用 `DeBooxUpx.py Poke6 updateXXX.upx updateXXX.zip` 成功解密两个包，
产物是标准 A/B OTA（`payload.bin` + `payload_properties.txt` + `META-INF/com/android/metadata`），
`post-build` 均为 `...200:userdebug/release-keys`，`ota-type=AB`。

**两个包的差异（payload 时间戳）**：
| | 47589 | 51326 |
|---|---|---|
| post-timestamp | **1729171272** = 2024-10-17 | **1779700567** = 2026-05-25 |
| post-security-patch | 2024-02-01 | 2024-02-01 |
| payload.bin 大小 | 1,774,178,851 | 1,719,774,978 |
| METADATA_SIZE | 102,412 | 105,952 |

⇒ **51326 是 2026-05-25 的新构建，47589 是 2024-10-17 的旧构建**（两者同为 build 200 指纹，
但 51326 时间戳新 1.5 年、payload 反而小 54MB，内容有增删）。提取分区后需逐分区 diff 才能定位差异。

**★ 版本号实测（2026-11-06，vendor/build.prop 提取）**：

| | 47589 | 51326（用户手上） |
|---|---|---|
| **OS 版本号** | **3.5.4** | **4.2** |
| `ro.vendor.build.onyxid` | `2024-10-17_21-21_3.5.4_1c47fc30b9` | `2026-05-25_17-16_4.2-rel_0525_310591b52c` |
| `ro.build.version.onyxincremental` | 1046 | 1604 |

⇒ **47589 = OS 3.5.4（2024-10），51326 = OS 4.2（2026-05）**，跨两个大版本。
- **Onyx 固件版本号看 `ro.vendor.build.onyxid`**（`日期_时间_版本号-rel_...`），**不是** AOSP 的
  `build 号 200`（那个只是 AOSP build number，两包相同）。
- 官网公告的「OS4.1.1 领航版」是**旧公告**：`data.boox.com`（国区旧服务器）现在返回的 51326
  已经是 **4.2 稳定版**（2026-05-25），高于 4.1.1。`data.onyx-international.cn`/`en-data.boox.com`
  返回的 47589 是旧版 3.5.4。

**解 payload.bin**：`payload_dumper`（cyxx/extract_android_ota_payload 或 ius/`payload-dumper-go`）。
仓库 README Note3 亦指向 `cyxx/extract_android_ota_payload`。实测：
```
payload_dumper --partitions vendor --out <dir> payload47589.bin
# vendor.img 是 ext4，用 tools/einkimg/ext4fs.py 读 /build.prop
```

**★ 修正 §14.7 的结论**：Poke6 固件包**能拿到**（`http://firmware.boox.com/c063d8e924c94e582d80ebec3d365482/update.upx`，
1.77GB，buildNumber 47589）。之前「全部 204」是因为用了错误的查询参数（真实 buildNumber + 完整 fingerprint）。
`P6` 和 `Poke6` 是两个不同 buildNumber 的包（1649 vs 47589），需确认哪个对应这台设备。

**可复现脚本**：`.diag/ota_query.py`（已固化正确查询法，`python .diag/ota_query.py Poke6 zh_CN`）。

**★★ 三服务器「获取 upx 包」的访问规则对比（2026-11-07 实测，三服务器规则一致、仅固件库不同步）**：

**结论：查询规则完全一样，唯一区别是返回的固件版本不同。**

| 维度 | `data.boox.com`（国区主库） | `data.onyx-international.cn`（国区镜像） | `en-data.boox.com`（美区） |
|---|---|---|---|
| 查询接口 | 相同 `GET /api/firmware/update?where=<JSON>` | 同左 | 同左 |
| 参数规则 | 相同 | 同左 | 同左 |
| CDN 选择逻辑 | 相同（按 `lang`） | 同左 | 同左 |
| **Poke6 返回包** | **51326 = OS 4.2（最新）** | 47589 = 3.5.4（旧） | 47589 = 3.5.4（旧） |

⇒ 三个服务器「规则一模一样，只是固件数据库同步进度不同」：`data.boox.com` 是国区主库
（有 Poke6 最新 4.2），另两个停留在 3.5.4。

**参数规则（三服务器通用，实测）**：

| 参数 | 规则 |
|---|---|
| `model` | **严格匹配机型名**：`Poke6` ≠ `P6`（两个不同包）。`Poke6`→4.2/3.5.4，`P6`→老代号包 |
| `lang` | **决定区域 + 下载 CDN**：`zh_CN`=国区、`en_US`=美区 |
| `buildNumber` | **「≥该值的包」，0/更小值=返回最新**；传当前版本号=204（无更新）。实测 0/1/47589 都→51326，传 51326→204 |
| `buildType` | 必须匹配：Poke6 只有 `user` 有包，`userdebug`→204 |
| `deviceMAC` | **不影响返回结果**（空/假/真都一样） |
| `fingerprint` | 可空，不影响 |
| `submodel` | 必须空，非空→204 |

**下载 CDN 域名规则（由 `lang` 决定，不是由查询服务器决定）**：

| 条件 | 下载域名 |
|---|---|
| `lang=zh_CN`（国区） | `http://firmware.boox.com/{md5}/update.upx` |
| `lang=en_US`（美区） | `http://firmware-us.boox.com/{md5}/update.upx` |

**机型有区域归属**：
- **Poke6 只有国区有包**：`lang=zh_CN` 返回，`lang=en_US` → 204（美区无 Poke6）
- **Palma 只有美区有包**：`lang=en_US` 返回，`lang=zh_CN` → 204

**一句话**：拿最新国区固件 = `data.boox.com + lang=zh_CN`；拿美区机型固件（如 Palma）=
任意服务器 + `lang=en_US`。要拿 Poke6 最新版必须用 `data.boox.com`（国区主库）。

### 14.21 ★ 领航版（OTA Beta Testing）接收机制反编译（2026-11-06 第十二会话）

反编译 `kcb-release.apk`（72MB，`com.onyx` 桌面/设置中心）定位到领航版完整链路。

**① UI 入口**：`设置 → 系统更新 → 领航版` 由 `com.onyx` 内 `SettingsDataBundle` 判定
`!getIsHideBetaTestingEntry()` 时显示；Fragment 为 `OTABetaTestingFragment`（`fragment_ota_beta_testing`），
按钮文案「加入领航版 / 退出领航版」。

**② 三个核心接口**（`com.onyx.android.sdk.data.v1.OnyxOTAService`，base = `cloudConf.getApiBase()`）：

```java
@POST("betaGroups/applyBeta")   Call<ResponseBody> applyBeta(BetaTestingModel)
@POST("betaGroups/cancelBeta")  Call<ResponseBody> cancelBeta(BetaTestingModel)
@POST("betaGroups/queryBeta")   Call<ResponseBody> queryBeta(BetaTestingModel)
// BetaTestingModel { String model; String mac; }   ← 只有这两个字段！
```

**③ 加入/退出流程**（`ToggleOTABetaTestingAction.toggle()`）：
```
更新 ClusterInfo → GetSecurityVerifyDataAction（滑动验证，见④）
  → 验证通过拿到 verifyData
  → 若已在 beta：POST betaGroups/cancelBeta({model, mac})
  → 若不在 beta：POST betaGroups/applyBeta({model, mac})
  → 成功则 OTAInfoUtils.updateBetaTesting(...) 更新本地状态
```
**注意**：`verifyData`（滑动验证结果）**没有**传给 `applyBeta`/`cancelBeta`，body 始终只有
`{model, mac}`。滑动验证是**前置的人机校验**，验证通过才放行后续请求。

**④ 滑动验证**（`SecurityVerificationDialog`）：
- 是一个 **WebView 弹窗**，加载 `{OnyxSend2BooxHostBaseUrl}/deviceNc.html?client=Device&lang=<locale>`
- host = `ClusterHost.getOnyxSend2BooxHostBaseUrl()` = `https://send2boox.com`（国区）/ `https://push.boox.com`（美区）
- 网页滑块验证完成后，通过 JS 接口 `Android.getSlideData(verifyData)` 回传验证结果
- `verifyData` 非空即算验证通过（`checkValid`：非空即可）

**⑤ beta 申请后的固件查询**（`CheckCloudOTAAction` → `OTAManager.cloudFirmwareCheckRequest`）：
- 仍是标准 `GET firmware/update`（`FirmwareUpdateRequest`）
- `getCurrentFirmware()` 组装：`lang = clusterInfo.getLocale()`、`deviceMAC = 真实 mac`、
  `widthPixels/heightPixels = 屏幕分辨率`、其余用 `Firmware.currentFirmware()`
- **关键**：beta 版固件**不是通过 `fwType=beta` 区分**，而是服务器按 `deviceMAC` 匹配
  「该 mac 是否在 beta 组」→ 是则返回 beta 固件，否则返回稳定版/204。
- 检查频率过滤：`CheckCloudOTAAction` 有 `21600000ms`（6 小时）节流，除非 `forceCheck`。

**⑥ 反编译佐证**：
- `OTAInfoUtils`：`isInBetaTesting()` / `updateBetaTesting(value)` 本地状态（MMKV）
- `OTAPrefFragment.gotoBetaTesting()` → `GotoOTABetaTestingEvent` → 跳 `OTABetaTestingFragment`
- `fragment_ota_beta_testing` / `ota_beta_testing_title/desc` / `ota_join/exit_beta_testing_*` 字符串
- 下载后刷入：`OTAManager.startFirmwareUpdate()` → `onyx.intent.action.OTA_START` →
  `OnyxOtaService`（§14.7 已述：解密 upx → UpdateEngine.applyPayload）

**★ 结论（回答「能否反编译接收领航版推送」）**：
1. **能**。领航版 = `POST betaGroups/applyBeta {model, mac}` + 前置 WebView 滑块验证
   （`send2boox.com/deviceNc.html`）。申请成功后，固件查询走标准 `GET firmware/update`，
   服务器按 mac 返回 beta 包。
2. **无需绕过**：只要设备 mac 在 beta 组，`firmware/update` 直接返回 beta 固件；若要知道
   领航版到底推送了哪个 buildNumber，直接对申请过 beta 的 mac 用 `.diag/ota_query.py` 查询即可。
3. **滑动验证可脚本化**：`deviceNc.html` 是人机验证网页，纯脚本绕过需逆向其 JS（captcha 类型未知，
   可能是滑块/点选），但这不属于「接收推送」的必要条件——`applyBeta` 接口本身只需 `{model, mac}`。

### 14.22 ★★★ 第十三会话：EInk 残留再排查 —— 两个入口机制不同 + OTA 不含 onyxconfig（2026-11-07）

**① OTA 固件包里没有 `onyxconfig` 分区**（51326 与 47589 都验证过）：

A/B OTA payload 只含 11 个分区：`boot / dtbo / vbmeta / vbmeta_system / recovery /
system / system_ext / product / vendor / modem / xbl`。
**没有 `onyxconfig` / `misc` / `userdata` / `persist` / `frp`** —— 官方**从不把 onyxconfig 打进 OTA**
（它是每机专属：序列号/MAC/VCOM 校准）。⇒ 「从 upx 挖原始 onyxconfig」这条路不通；
原始 onyxconfig 只有 `poke6_9008/`（健康机全分区回读）这个来源。

**② 「优化引擎切档」两个入口，机制不同（勿混）**：

| 入口 | 改什么 | 能否切档 |
|---|---|---|
| **EInk Center 面板**（通知栏 EinkWise 磁贴点出，净清/标准/极速） | `eac_theme_<当前app>` 的 **`refreshModeIndex`**（`SelectRefreshModeAction.setRefreshModeIndex`） | **能切**，切成功当前 app 会退出重建（用户确认） |
| **通知栏「刷新模式」磁贴** | `fallbackRefreshConfig.updateMode`（`setAppScopeRefreshMode`） | **切不动**：`caculateRefreshConfig` 里 idx≠NONE 时用映射表 mode 覆盖 updateMode（文档 §2254 早证） |

**③ 「残留」的真相**（用户指出后实测取证）：

- 实机 onyxconfig 当前 `eac_app_*` / `eac_default_app_config*` **0 残留**（已清干净）。
- 残留全在 **74 个 `eac_theme_*`**：`refresh_mode_3`（49）+ `refresh_mode_4`（25）。
- 健康机 donor 的 `eac_theme_*` 也是 `refresh_mode_3`(50) + `refresh_mode_4`(28)，**没有 NONE**。
  ⇒ 这些 `refresh_mode_3/4` 是**官方云端下发的默认 EAC 配置**，不是 launcher 残留。
- 故障机多出的 69 个 `NONE` 才是 launcher 历史写入（`set/reset`），无害。

**④ 关键待验证（留给下一会话）**：用户称「原始可切刷新模式」，但**当前故障机 EInk Center
切档是否真的失效、切的是哪个 app（第三方 vs com.onyx 系统 app）、切完会不会退出**，本轮未实机确认。
判定方向：
- 若 EInk Center 切第三方 app **不退出/波形不变** → 真故障，追 `eac_theme_<app>` 的 refreshModeIndex
  是否被 `loadThemeOrCreate` 重建覆盖（§14.11 遗留项）；
- 若切**能退出**只是通知栏全局磁贴切不动 → 那是官方限制（§④ 表格第二行），不是残留。

**⑤ 遗留**：`libota_jni.so` 的 `.rodata` 已 dump（16 字节重复段 `692b9bd3b4d2a55a`），
`reorganizeSecretKey/IvParameter` 的二次混淆未完全还原；但 Poke6 的最终 Key/IV 已由
`BooxKeys.csv` 直接给出（`DFA1B679E1089011FEB9C8455AD7E1D5` / `83BF5F0F69A76D42D742E044EA1BDACF`），
无需再逆向 native。

### 14.23 ★★★ 第十四会话：残留 →「切档不生效」已实机闭环（根因 = `SelectRefreshModeAction` 过滤顺序 bug + 引擎总开关）（2026-11-07）

**① 实机复现：§14.22 遗留的唯一待验证项已闭环**

Legado 在前台 → 开 EInk Center 面板 → 点「极速」：
- 面板圆点会移到「极速」（**UI 有反应**）
- 但 onyxconfig **一个字节都没写**（切换前后 `actual_size` 与记录数完全相同：1,034,647 / 499）
- app 不重启，`getAppScopeRefreshMode()` 不变 ⇒ **真故障，不是误判**
- 每次开面板必现：`EacCommonUtils.getRefreshModeTitle NPE: RefreshModeData.getTitle() on null`

对照组：`com.onyx`（theme idx = `refresh_mode_4`）同操作 → **立即写入**
（`eac_theme_com.onyx@theme_type_3` 与 `eac_app_com.onyx` 同步改，附 `refreshModeAlias=new_speed` / `turbo=5`）。

⇒ **不是面板坏，是「当前 idx=NONE」让面板静默空转。**

**② 源码级根因（SystemUI 反编译 `SelectRefreshModeAction.java`）**

```java
private String realRefreshModeIndex;            // 初始 null
create() = observableJust(this)
    .filter(o -> isRefreshModeIndexChanged())   // ← 先跑
    .doOnNext(o -> loadRealRefreshModeIndex()) // ← 后赋值 realRefreshModeIndex
...
isRefreshModeIndexChanged() {
    return RefreshModeIndex.toEnum(getTheme().refreshConfig(topComponent()).getRefreshModeIndex())
        != RefreshModeIndex.toEnum(this.realRefreshModeIndex);   // ← 此时恒为 null
}
```

`RefreshModeIndex.toEnum(null)` = `NONE`（`isNullOrEmpty → NONE`，已反编译定论）。所以：

| 当前 idx | filter 结果 | 后果 |
|---|---|---|
| `NONE` | `toEnum("NONE") == toEnum(null)` ⇒ **false** | **整个动作被跳过**（`loadRealRefreshModeIndex` 都不执行 ⇒ 不写盘、不 apply） |
| `refresh_mode_N` | true | 正常写入 + apply |

这是 Onyx 自己的**过滤顺序 bug**。健康机永远不会出现 NONE，所以没人发现；launcher 历史写下的 NONE 才把它踩出来。
同一个 NONE 也是 `getRefreshModeAlias` NPE 的来源（面板标题拿到 null `RefreshModeData`）。

**③ 修复（数据层，不 patch SystemUI）**

把 4 个已安装第三方 app 的 NONE 残留整组删掉（`eac_theme_*@theme_type_1/2/3` + `eac_app_*` + `eac_default_app_config*`），
让系统按出厂默认（`default_config_eac_app_theme_3` = `refresh_mode_3`/0）重建：

| pkg | 删前 | 删后 |
|---|---|---|
| `com.legado.app.release` | NONE/5 ×3 | refresh_mode_3/0 |
| `org.koreader.launcher` | NONE/5 ×3 | refresh_mode_3/0 |
| `mark.via` | NONE/5 ×3 | refresh_mode_3/0 |
| `web1n.stopapp` | NONE/5 ×3 | refresh_mode_3/0 |

工具：`FixEac rm <pkgCsv>`（框架自己的 `BaseMMKV.remove`，走官方 MMKV，不手改文件）。
测试中改过的 `com.onyx` theme/app 已用**备份 JSON 原样写回**（`FixEac write`，与 pre 快照逐字节一致）。

**④ 修复后实测（同一台机，前后对照）**

| 操作 | `theme_type_3` | `eac_app_` | `getAppScopeRefreshMode` |
|---|---|---|---|
| 点「标准」 | `refresh_mode_4`/0 | 同步重建 | 5 → **0** |
| 点「极速」 | `refresh_mode_3`/2 | 同步重建 | **2**（`getFastModeIndex` 也 2） |

- `EacCommonUtils` NPE 消失（修复后开面板 0 条）
- 重启后配置保留、切换仍生效；MMKV 开机 compaction **1,807,874 → 973,090**（-835KB），crc OK

**⑤ 第二层根因：EAC「优化引擎」总开关是关的**

对照健康机（`.diag/ORIG_mmkv_onyx_config`，852,375 B）：

| key | 健康机 | 故障机（修前） |
|---|---|---|
| `eac_extra_config_enable` | **01** | **00** |
| `eac_extra_gc_after_scrolling` | 01 | 00 |

`EInkHelper.isEnable()` 为 false ⇒ `getAppConfigFromService()` 返回 dummy ⇒ **各 app 进程不读 per-app EAC 配置**，
面板写成功 app 也不会按新档位重建。已用 `EACDeviceExtraConfig.save()` + `EInkHelper.setEACServiceConfig(true)` 恢复为 01；
恢复后同一次切换在 Legado 进程里出现 `EInkHelper: Suspicious time-consuming fetch config for pkg: com.legado.app.release`
（= app 真的重取了配置）。

⚠️ `OECService.setEnable()` **只改内存 AtomicBoolean、不落盘**；落盘在 `EACDeviceExtraConfig.save()`。
OECService 开机从 `false` 起跑，靠上游把 01 传进来 —— 观察过一次开机后又变 00，**下次开机请用 `FixEac read` 复核 `isEnable()`**。

**⑥ 工具改动（`tools/refresh/FixEac.java`）**

| 命令 | 作用 |
|---|---|
| `rm <pkgCsv>` | 已有，本次用它清 NONE |
| `write <jsonFile>` | **新增**：theme JSON 原样写回（`saveEACAppThemes` + `applyEACAppTheme(…,3)`），用于恢复备份 |
| `active <pkg> <type>` | **新增**：设 `eac_active_theme_<pkg>` |
| `enable <0\|1>` | **新增**：`EACDeviceExtraConfig.save()` + `setEACServiceConfig`，切引擎总开关 |
| `read` | 增打印 `EInkHelper.isEnable()` |

编译：`javac -source 8 -target 8 -cp android.jar` → `d8 --min-api 30` →
`CLASSPATH=/data/local/tmp/fixeac2.dex app_process /system/bin io.onyx.FixEac …`

**⑦ 仍留着的**

- 59 个 NONE theme（都在**已卸载** app：`com.dragon.read` / `com.qidian.QDReader` 等），对切换无影响，只占 ~180KB；要清用 `FixEac rm`。
- 每次面板保存会重写 ~86 条 `eac_app_*`（≈+150KB 追加），靠开机 compaction 回收 —— **一次开机内别连切几十次**，否则 2MB 会满。
- `eac_extra_gc_after_scrolling` 仍是 0（健康机 1），这是用户偏好，未擅自改。

---

*本文档由 2026-09-25 会话生成；§12 由 2026-09-29 第五会话追加；§13 由 2026-09-29 第六会话追加，
§13.10 由 2026-10-08 第七次实测追加；**§14 由 2026-11-06 第九会话追加；§14.12–14.15 由
2026-11-06 第十会话追加；§14.16–14.18 由 2026-11-06 第十一会话追加；§14.19–14.21 由
2026-11-06 第十二会话追加；§14.22 由 2026-11-07 第十三会话追加；§14.23 由 2026-11-07 第十四会话追加**。*
*ref.md 的 §9.3 开头有导读框，列出最优结论与已作废判据 —— 建议从那里进入详细内容。*
*★ 第六会话另在 ref.md 追加了 `# 十六、故障机根因定位与修复` 一节。*
