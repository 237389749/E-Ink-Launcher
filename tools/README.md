# tools — 可复现的最小工具集

配套文档：`HANDOFF.md` §5.1（刷新引擎/EAC 全部结论）、§5.2（内核 patch 表）、§13（设备状态）、`ref.md` §九（机理细节）。

> ⚠️ **两台 Poke6，务必区分**：故障机 `6C7F0E64` / 正常机 `6C1BF7D9`。
> 所有脚本都把设备序列号当参数或环境变量（`push_sh.py` 用 `EINK_DEV`，默认故障机）。

## 0. 前置

- root（Magisk）+ adb；本项目**不本地跑 gradle**（证书吊销问题），改代码后推 CI 构建。
- 编译 `.java` → `.dex`：

```powershell
javac -encoding UTF-8 --release 8 -d classes src\io\onyx\FixEac.java
# d8 来自 Android SDK build-tools
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\d8.bat" --min-api 24 --output . classes\io\onyx\FixEac.class
adb -s <serial> push classes.dex /data/local/tmp/fixeac.dex
```

- 这些脚本原本都放在「与 `adb.exe` 同级的目录」下运行；挪位置时请改掉脚本内的相对/绝对路径
  （尤其 `lib/gs_lint_java.py`、`lib/gs_import_check.py` 里硬编码了 launcher 源码路径）。

---

## 1. `refresh/` — 刷新引擎 / EAC（per-app 刷新模式）

**症状**：通知栏「优化引擎」→ 应用优化 → 刷新模式，切换后**毫无反应**。
**根因**：EAC 的 `updateMode` 被写成了**波形/UI 域**的值（`UI_REGAL_PLUS_MODE = 9`），
而 `updateMode` 属**逻辑域**（−1..5）。`9` 不在固件迁移表里 ⇒ 永不被修正 ⇒
`setAppRefreshModeImpl` 直接 `dumpError("Incorrect target system refresh mode") + return`。

**修复值（已实测）**：`refreshModeIndex = "NONE"` + `updateMode = 5` + `refreshModeAlias = ""`

- `NONE`：唯一能让引擎写入被采纳的状态（`idx≠NONE` 时 `caculateRefreshConfig` 忽略 `updateMode`）
- `5`：合法逻辑域值，且**不在**迁移表 `{0→3,1→2,2→2,3→5,4→2}` 里 ⇒ 扛得住 `validateRefreshConfig`
- 生效**需重启**（运行中的 OECService 会把内存旧值回写覆盖）

**工具**

| 文件 | 作用 |
|---|---|
| `EacInfo.java` | 只读诊断：设备级读数、`refresh_mode_N`→mode 映射、每 pkg 的 idx/um + theme 明细 |
| `FixEac.java` | 主工具：`read` / `setmode` / `fix` / `keys` / `rm` / `migrate` / `sysmap` / `aliasmap` |
| `SetDefCfg.java` | 写 `eac_app_<pkg>` + `eac_default_app_config<pkg>`（`NONE` + 指定 um） |
| `DumpThemes.java` | dump 某 pkg 全部 theme（走 `EInkHelper.loadThemes`，**权威读法**） |
| `CheckMode.java` / `SetScope.java` / `ClearScope.java` | scope 通道读数 / 设 / 清 |
| `gs_scan_only.sh` | **只读**全量扫描（找 `um=9` 残留、统计分布） |
| `gs_reset_all2.sh` | **按 MMKV 键**全量重置为 `NONE+5`（含已卸载 app 的残留） |
| `gs_health.sh` | 设备健康：reset / `wait all_lut_free` / `update_err` / vcom / scope / 前台 |

**用法示例**

```sh
adb -s <serial> shell 'su -c "CLASSPATH=/data/local/tmp/fixeac.dex app_process /system/bin io.onyx.FixEac read com.legado.app.release"'
adb -s <serial> shell 'su -c "CLASSPATH=/data/local/tmp/fixeac.dex app_process /system/bin io.onyx.FixEac fix com.legado.app.release 5"'
adb -s <serial> shell 'su -c "CLASSPATH=/data/local/tmp/fixeac.dex app_process /system/bin io.onyx.FixEac sysmap"'   # 固件全部刷新档
```

**（重要）修为纪律**：`updateMode` 只接受 **−1..5**。`9 / 98 / 33554436` 全是波形/UI 域值，
一旦写进 `updateMode` 就是**不可逆**的（只能手工 reset）。
`GlobalEacRefreshHelper set/defcfg` 已在代码层加了这条校验（见 launcher 源码）。

---

## 2. `kernel/` — 内核（EPDC 超时 / regulator 判据）

**问题**：① `tps6518x` 的 powerup 判据是 `regmap_read(0x0F) == 0xFA`，硬件恒返回 `0xBA`
⇒ `regulator_enable(DISPLAY)` 永远 `-ETIMEDOUT`；② `wait all_lut_free` 超时被缩短，
全屏 GC16（~426 ms）与流水线全屏（~850 ms）必然超时 ⇒ reset 级联。

| 文件 | 作用 |
|---|---|
| `unpack_boot.py` | 拆 boot.img（magiskboot 风格） |
| `gs_kernel_tool.py` | `extract_kernel` / `v6_patches(mode)`（v6-A2 已验证可复现） |
| `patch_kernel_v7.py` | v7A…v7H 变体（`--variant H`） |
| `gs_verify_v7.py` | 结构校验 + 与 v6-A2 的 word diff |
| `gs_flash.sh` | 设备端写 + 回读 + 校验：`su -c "sh /data/local/tmp/flash.sh <img> <md5>"` |

**推荐镜像：v7H**（= v7F 保留 PG 读数 + `all_lut_free` 恢复 stock 5000 ms）；
若出现长冻结，回退 **v7G**（2000 ms）。两者在 6 模式混合负载下均 0 reset。

---

## 3. `lib/`

| 文件 | 作用 |
|---|---|
| `push_sh.py` | CRLF→LF 后推送并执行（`EINK_DEV=<serial>` 指定设备） |
| `gs_lint_java.py` | 括号配平 + 数组长度一致性（本地编译的替代检查） |
| `gs_import_check.py` | 未用 import 检查 |

---

## 4. `boot/` — 启动模式实测 / recovery / 救砖

**结论**：ABL fastboot 是死的（`18D1:4EE2` 只活 ~5 秒）；fastbootd（`adb reboot fastboot`，
`18D1:4EE0`）可用，但只能刷逻辑分区；原厂 recovery 无 e-ink UI、adb 默认 `unauthorized`；
唯一硬兜底是 EDL（短接/工程线）。详见 `BOOT-MODE-POSTMORTEM.md`、`RECOVERY-EDL.md`。

| 文件 | 作用 |
|---|---|
| `mk-recovery-adb.py` | 从官方 `recovery.img` 生成「默认开 adb」的 recovery（init.rc + `ro.adb.secure=0` + `/adb_keys`），并**重算 `recovery_dtbo_offset`** |
| `flash-recovery-adb.ps1` | dd 备份→写→整分区回读校验（带 `-WhatIf`） |
| `diag-boot.ps1` | 依次探测 fastboot / recovery / fastbootd 的真实能力 |

**硬规则**：改 boot image 后必须重算 `recovery_dtbo_offset`（0x664），否则 ABL 按旧偏移取到
垃圾 dtbo，recovery 直接挂、BCB 里的 `boot-recovery` 清不掉 → 卡 logo 循环。
`mk-recovery-adb.py` 自带「原 kernel+原 ramdisk 逐字节回归」和偏移自检。

> 脚本按「仓库根有 adb.exe/fastboot.exe，否则查 PATH」找工具；`mk-recovery-adb.py`
> 默认读 `ota/images/recovery.img`，不在固件目录下运行时用 `--src` / `--out` 指定。

---

## 5. `debloat/` — 预装用户级禁用

只做 `pm disable-user` / `pm uninstall --user 0`，随时 `pm enable` / `install-existing` 回滚；
不动 system/super/AVB，不影响 OTA 签名。包清单与理由见 `debloat-list.json`（t1–t4 分层），
判断依据见 `DEBLOAT-AND-BOOTMODES.md`。

| 文件 | 作用 |
|---|---|
| `debloat.ps1` | `list` / `status` / `apply -Tier t1` / `rollback -Tier all`，带 `-WhatIf` 和日志 |
| `debloat-list.json` | 包名、层级、体积、`keep` 列表（不该动的包） |

> 需要设备 root（Magisk su）。`pm disable-user` 对 protected package（systemui/settings）会失败，属预期。
