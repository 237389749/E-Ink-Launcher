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
7. ⚠️ **未动 `applyFixedEac` 的设备残留**：只清了 theme（决策源）；`eac_app_<pkg>` 与
   `eac_default_app_config<pkg>` 两个 **fallback** 键仍是旧值 —— 有 theme 时它们不参与决策，属惰性残留。
   未跑 `official 0`（它会清全局 scope + 改写"当前前台 app"的配置，副作用大于收益）。

### 5.2 内核 patch

| 脚本 | 作用 |
|---|---|
| `_scratch_gs/patch_kernel_v6.py <mode>` | 9 处 patch（v5 的 7 处 + 重排队 2 处）。`mode`: 1=DU / 4=A2 / 8=DU4 |
| `_scratch_gs/rebuild_boot_v6.py <out>` | 重打包 boot 镜像 |

**重排队 patch 位置**：`0x542B0C` 与 `0x5431AC`（`movz w0,#N`）
**当前设备**：`N=4`（A2）→ reset 后重排队走 `[6]/5` 帧

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

*本文档由 2026-09-25 会话生成；§12 由 2026-09-29 第五会话追加；§13 由 2026-09-29 第六会话追加，
§13.10 由 2026-10-08 第七次实测追加。*
*ref.md 的 §9.3 开头有导读框，列出最优结论与已作废判据 —— 建议从那里进入详细内容。*
*★ 第六会话另在 ref.md 追加了 `# 十六、故障机根因定位与修复` 一节。*
