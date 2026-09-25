# HANDOFF — Poke6 墨水屏刷新链路调查交接

> 生成时间：2026-09-25 · 面向下一个会话/接力的 LLM 或工程师
> **先读本文，再按需查 `ref.md`（5684 行完整日志）与 `WAVEFORM_TO_DISPLAY.md`（15 KB 提炼版）**

---

## 0. 30 秒速览

| 项 | 内容 |
|---|---|
| **项目** | BOOX Poke6 墨水屏刷新机制逆向 + launcher 刷新档位改造 |
| **设备** | 故障机 `6C7F0E64`（Poke6，TPS6518x 供电故障）；正常机 `6C1BF7D9` |
| **仓库** | `E-Ink-Launcher`，分支 `v0.x`，root = `C:\Users\root\Documents\eink\E-Ink-Launcher` |
| **当前提交** | `fc516ca`（已推送）|
| **设备当前档位** | `launcherRefreshMode=2` = **GC16**（局部族，安全）|
| **设备当前内核** | v6-A2 patched（`#79 Mon Mar 16 18:22:03`），刷在 `boot_b`，槽位 `_b` |
| **工作区根** | `C:\Users\root\Documents\eink`（`ref.md` 在此，**不在 git 仓库内**）|

**一句话现状**：黑屏问题已解决（v6 内核 patch 有效）；A2 档在故障机必然 reset（已知并规避）；
**当前未解的两件事是硬件供电故障的两个表现** —— ① 刷新"停滞→突增"（双刷），② 偶发重启。

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
| **session temp 目录会变** | `$env:TEMP\eink_diag` 路径失效 | 用绝对路径 `C:\Users\root\AppData\Local\Temp\eink_diag` |

### 1.4 ★ Git 推送

```powershell
cd C:\Users\root\Documents\eink\E-Ink-Launcher
git push origin v0.x        # 代理可用时（当前 127.0.0.1:7890 在运行）
# 若代理挂掉、直连可用时：git -c http.proxy= -c https.proxy= push origin v0.x
```

- ⚠️ `origin` URL 里**硬编码了 GitHub token**（`ghp_WgZ...`）。它只在 `.git/config`（不提交）。
  **建议改用凭据管理器**：`git remote set-url origin https://github.com/237389749/E-Ink-Launcher.git`
- 网络状态会变：曾出现"直连通、代理不通"，也出现"代理通、直连不通"。**先测再推**：
  ```powershell
  Test-NetConnection github.com -Port 443 -InformationLevel Quiet
  Test-NetConnection 127.0.0.1 -Port 7890 -InformationLevel Quiet
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
| `launcherRefreshMode` | **2** → 新表 index 2 = **DU** ⚠️ 见下方更正 |
| `EAC` 逻辑值 | 定死为 **3 (REGAL)**，由 `applyFixedEac()` 在 launcher 启动时写入 |
| scope（运行时） | 由 launcher `apply()` 设置 |

> ⚠️ **注意索引语义**：`SCOPE_VALUES = {-1, -1, 1, 2, 4, 2312}`
> `MODE_NAMES = {None, NORMAL, DU, GC16, A2, DU4}`
> ⇒ **index 2 = DU，index 3 = GC16**。prefs 最后写入的是 `globalScope DU -> ui=1`（见 refresh_mode.log），
> 故**实际生效的是 DU(1)**。判断实际生效请用 `fastModeIndex` + 实测波形，**不要只看 prefs**。

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

### 3.4 内核与镜像

| 项 | 值 |
|---|---|
| 当前内核 | **v6-A2**（判定法：触发 reset 后重排队落 `waveform[6]/5`）|
| 仓库镜像 | `boot_patched_a2_v6.img` md5 `c62600dd4bb3a1abcba0a0c0caaf75c6` |
| | `boot_patched_du_v6.img` md5 `43a4e312aa97e0fa6febd96f7ff00e87` |
| 设备备份 | `/sdcard/boot_b_pre_v5.img`、`/sdcard/boot_b_pre_v6.img`、`/sdcard/boot_b_du_v6.img`、`/sdcard/boot_patched_a2_v6.img` |

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

#### 现象 A：刷新"停滞 → 突增"（用户称"一次性刷新两次/双刷"）

**实测数据**（20 ms 采样 `frame[]`）：
```
单次翻页:  0~200ms +36帧 → 停 2.0s → 2240ms +109帧
不操作:    0~380ms +38帧 → 停 2.0s → 2380ms +36帧
停 launcher: 仍有同样模式（已 force-stop 验证）
```
**已排除**：launcher、刷新模式、用户操作 —— **全部无关**。
**机制推断（A，倾向成立）**：`powerup 重试周期`驱动
```
一个 GC16 需 38 帧 → 需 1 次成功 powerup
失败→重试 2 次（各 1.7s）→ 轮间停 4~5s
成功的轮次 = 执行 38 帧 = frame +36~38
```
**待验证**：前后两份数据时间尺度吻合，但**尚未抓到最后一步直接对应**（需在同一窗口同时抓
 `Reg Enable` 时间戳与 frame 跃变）。

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
private static final int[] SCOPE_VALUES = {-1, -1, 1, 2, 4, 2312};   // L63
public static final String[] MODE_NAMES = {None, NORMAL, DU, GC16, A2, DU4};
private static final int FIXED_EAC_LOGIC = 3;                        // L181
// LABELS: None / NORMAL / DU—纯黑白·22帧·1bpp / GC16—16级灰·38帧·4bpp
//         / A2—最快·5帧·无灰阶 / DU4—4级灰·24帧·2bpp
```

- `apply(int)` = 切档主入口：`byPass(10)` → 设 scope → `byPass(0)` → `fullRefreshScreen()`
- `applyFixedEac()` — 启动时一次性把 EAC 定死 3（REGAL）
- `applyPerApp(pkg, idx)` — per-app 走 scope 通道
- `fullRefreshScreen()` — 反射调 `ViewUpdateHelper.repaintEverything()`

**`Launcher.java`**
- `onCreate` 里：`RefreshModeHelper.init` → `apply(mode)` → 后台线程 `applyFixedEac()` → 再 `apply(mode)`

**关键设计决策（勿轻易改）**：
1. **切档只走 scope**，不批量写 EAC（避免窗口重建风暴 → system_server WTF → Watchdog）
2. **EAC 定死 3(REGAL)**：白名单 `{0,3,5}` 才启用周期 GC + 滚动瞬态 + dither；`toEpdMode(3)=6` 走局部
3. EAC 实测**不影响**是否出全屏 reset（§9.3.17 实验 1 vs 2 同结果）

### 5.2 内核 patch

| 脚本 | 作用 |
|---|---|
| `_scratch_gs/patch_kernel_v6.py <mode>` | 9 处 patch（v5 的 7 处 + 重排队 2 处）。`mode`: 1=DU / 4=A2 / 8=DU4 |
| `_scratch_gs/rebuild_boot_v6.py <out>` | 重打包 boot 镜像 |

**重排队 patch 位置**：`0x542B0C` 与 `0x5431AC`（`movz w0,#N`）
**当前设备**：`N=4`（A2）→ reset 后重排队走 `[6]/5` 帧

---

## 6. ★ 下一步候选任务（按价值排序）

### 6.1 验证现象 A 的机制（推荐先做，成本低）

**目标**：确认"双刷"= powerup 重试周期
**方法**：
1. 先导 dmesg：`adb shell su -c "dmesg > /data/local/tmp/dm.txt"`
2. 同时后台采样 frame：`sh /data/local/tmp/fts.sh 1200`（带 `/proc/uptime` 时间戳）
3. 离线对齐两份数据（同一 `kt` 时钟）
4. **判据**：每次 frame 跃变前 ~1.7s 应有 `Reg Enable`

**注意**：脚本内 `dmesg` 会失败，必须用 adb 先导出。

### 6.2 验证 `epdc-power-fail-dont-update`（★ 最有价值的软件缓解点）

**发现**：内核 `onyx_epdc_parse_dt` 解析以下 dt 属性（反汇编 `0x53f7c0` 区域挖出）：

| 属性名 | 推测作用 |
|---|---|
| **`epdc-power-fail-dont-update`** | ★ **供电失败时不更新**（若为 0 → 失败也照刷，**可能正是双刷来源**）|
| `epdc-init-mode-force-disable` | 强制禁用 init 模式 |
| `epdc-panel-v3p3-always-on` | 面板 v3p3 常开 |
| `panel-pwrdown-delay` / `panel-pwrdown-wait` | 掉电延迟/等待 |
| **`a2-clean-mode` / `a2_frame_num`** | ★ A2 清残影模式与帧数 |
| `cut-frame-fill-zero` / `power-timeout-deteck` | 截帧填充 / 供电超时检测 |
| `temp-deteck-enable` / `temp-deteck-from-pmic` | 温度检测 |
| `dither-set-disable` / `epdc-gu-regal-enable` | dither / GU REGAL |
| `cfa_mode` / `sf-rotation` / `temp-diff` | CFA/旋转/温差阈值 |

**关键**：这些由内核从**设备树**读取（`bl #0xaecf58` = `of_property_read_*`），
**属性不存在则功能关闭** ⇒ **要改需改 dtb，不是 sysfs**。

**任务**：找到 dtb 中 EPDC 节点 → 确认这些属性的当前值 → 评估改 `epdc-power-fail-dont-update` 的效果。

### 6.3 查重启触发源（现象 B）

**方法**：
1. `logcat -b events -d | grep -iE "watchdog|reboot|boot_progress"`
2. `/data/system/dropbox/` 里 WTF 时间戳与 `am_`/`wm_` 事件对齐
3. 盯下一次 WTF 爆发窗口（历史：09-24 23:50 / 09-25 00:10）

### 6.4 评估"手动全屏清残影"入口

**背景**：`repaintEverything()` 是唯一整屏清残影手段，launcher 仅切档时调一次。
**可选**：加手动入口（低风险）或周期性自动全刷（会周期性闪烁，中风险）。
⚠️ `repaintEverything(98)` 带参版是否绕过 FULL 位限制**未验证**。

### 6.5 试其余可写节点（低优先级）

| 节点 | 权限 | 状态 |
|---|---|---|
| `cut_frame_num` | `-rw-r--r--` | 未测（当前 0）|
| `update_disable` | `-rw-r--r--` | 未测（当前 0）|
| `submit_upd_work` | `--w-------` | 未测 |
| `night_mode` | `--w-------` | 未测 |
| `debug_level` | `-rw-r--r--` | ⚠️ 实测写入被拒 |
| `reset_test` | `--w-------` | ⚠️ **实测被 SELinux 拒**，不可用 |
| `panel_clean` / `panel_init` / `panel_last` | `-r--r--r--` | **只读**（值恒 `ok`），**非命令节点** |

### 6.6 正常机验证（`6C1BF7D9`）

- A2/DU4 在正常机的表现（应无 reset 循环）
- `scrollingRefreshMode` 混合来源（未验证）

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
| 双刷机制 | 三组对照实验（含 force-stop launcher）| **中**（机制未最终闭环）|
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
| 1 | 双刷机制是否确为 powerup 重试 | 待最后一步验证（§6.1）|
| 2 | `epdc-power-fail-dont-update` 当前值 | 未查（§6.2）★ 最有价值 |
| 3 | 重启真正触发源 | 未定（§6.3）|
| 4 | 长停滞 27.5s 的解释 | 未解（可能多轮连续失败）|
| 5 | idle 为何持续刷 38 帧 | 未解（疑系统周期性重绘）|
| 6 | `repaintEverything(带参)` 是否绕过 FULL 位 | 未验证 |
| 7 | 是否有"清残影"的 dt 开关 | `a2-clean-mode` 待查 |
| 8 | A2/DU4 在正常机的表现 | 未测（正常机 `6C1BF7D9`）|

---

*本文档由 2026-09-25 会话生成。所有数据均经独立复现或标注可信度。*
*ref.md 的 §9.3 开头有导读框，列出最优结论与已作废判据 —— 建议从那里进入详细内容。*
