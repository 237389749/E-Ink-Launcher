# tools — 可复现的最小工具集

配套文档：`HANDOFF.md` §5.1（刷新引擎/EAC 全部结论）、§5.2（内核 patch 表）、§13（设备状态）、
**§14（卡开机的 MMKV 根因 + 每机专属数据清单）**、`ref.md` §九（机理细节）。

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

## 1. `einkimg/` — `onyxconfig` 分区诊断与修复（卡开机 / 刷新引擎失效）

**症状**：开机动画跑很久 → 无显示 → `adb unauthorized`（USB PID `18D1:D001` = 在 recovery）；
或系统能进但「优化引擎」切刷新模式点了没反应。

**根因方向**：`/onyxconfig` 是**独立 ext4 分区**（`mmcblk0p80`），
**恢复出厂不擦、卸载 app 不清**。里面 `mmkv/onyx_config`（2 MB，MMKV append-only）
存着 `eac_app_pkg_set`（启动遍历哪些 app 的 EAC）+ 各 app 的 theme/fallback。
`pkg_set` 被清到只剩 `com.onyx`、又恰好删了它的 theme+fallback ⇒ 启动无配置可用 ⇒ 卡死。

> 详细复盘见 `HANDOFF.md` §14；格式细节与踩坑见 `einkimg/README.md`。

| 文件 | 作用 |
|---|---|
| `einkimg.py` | `ls` / `cat` / `mmkv`（list/keys/deleted/get）/ `diff`（文件级 + MMKV 逐 key） |
| `repair.py` | 生成修复镜像：本机镜像为底，只 append 缺失的 key，自动重算 crc，复核「只 2 个文件变」 |
| `ext4fs.py` | ext4 只读解析（含 32/64 desc、extents、inline data、symlink、`SubFile` 切片） |
| `mmkv.py` | MMKV 解析 + append + crc 计算（`crc[0]=CRC32(mmkv[4:4+actual])`） |
| `find_ext4.py` | 暴力扫 ext4 超级块（绕开解不出的 LP metadata），从 super.img 里定位 system/vendor/product |

**典型用法**

```bash
# 两份镜像差在哪（含「每机专属」5 处身份数据 + MMKV key 级差异）
python tools/einkimg/einkimg.py diff A/images/onyxconfig.img B/images/onyxconfig.img

# 看 MMKV：有效 key、墓碑、单个 key
python tools/einkimg/einkimg.py cat B/images/onyxconfig.img mmkv/onyx_config out/cur
python tools/einkimg/einkimg.py mmkv out/cur list
python tools/einkimg/einkimg.py mmkv out/cur get eac_app_pkg_set

# 生成修复镜像（--base 必须是本机！）
python tools/einkimg/repair.py --base B/images/onyxconfig.img --donor A/images/onyxconfig.img \
  --out out/onyxconfig_fixed.img \
  --restore 'eac_theme_com.onyx@theme_type_3,eac_app_com.onyx,eac_default_app_configcom.onyx'

# super.img 里找 ext4 分区
python tools/einkimg/find_ext4.py super.img
```

**⚠️ 三条铁律**

1. **绝不用别的机器的 `onyxconfig` 整盘覆盖** —— 会改掉
   `DEVICE_SERIAL_NUMBER_KEY` / `mac_address` / `test_result_info` /
   `ONYX_SYSTEM_CONFIG_KEY` / **`com.android.vcom`（VCOM 电压校准）**。
   `repair.py` 的 `--base` 必须指向本机镜像。
2. **只允许 `mmkv/onyx_config` 与 `.crc` 变化**（脚本末尾会自动复核）。
3. **修复 = append**，不重写、不删墓碑（MMKV 后写覆盖先写，语义等价）。

---

## 2. `refresh/` — 刷新引擎 / EAC（per-app 刷新模式）

> **★ 2026-11-07 修正（先读这条，否则会把设备再写坏）**
> 下面 2026-10-11 的「修复值 `idx=NONE + um=5`」**已经作废，不要再写**。
> 实机 + 反编译定论（`HANDOFF.md` §14.23/§14.24）：
> `NONE` 会让官方 EInk Center 的 `SelectRefreshModeAction` **静默空转** ——
> 它先跑 `filter(isRefreshModeIndexChanged())`、后赋值 `realRefreshModeIndex`，
> 而 `RefreshModeIndex.toEnum(null) == NONE`，所以「当前 idx 是 NONE」时 filter 恒 false，
> 整个动作被跳过（不写盘、不 apply）。表现为**点净清/标准/极速完全没反应**（这正是一线症状）。
> 正确做法：**idx 必须是合法 `refresh_mode_N`**；有 NONE 残留就删掉让它回出厂默认
> （`FixEac rmnone`），而不是再写 NONE。历史值域事故（um=9）的修复值 `um=5` 仍然有效，
> 但要配合法 idx 或直接删键。

**症状**：通知栏「优化引擎」→ 应用优化 → 刷新模式，切换后**毫无反应**。
**根因**：EAC 的 `updateMode` 被写成了**波形/UI 域**的值（`UI_REGAL_PLUS_MODE = 9`），
而 `updateMode` 属**逻辑域**（−1..5）。`9` 不在固件迁移表里 ⇒ 永不被修正 ⇒
`setAppRefreshModeImpl` 直接 `dumpError("Incorrect target system refresh mode") + return`。

**旧修复值（2026-10-11，已被上面的 11-07 结论取代）**：`refreshModeIndex = "NONE"` + `updateMode = 5` + `refreshModeAlias = ""`

- `NONE`：唯一能让引擎写入被采纳的状态（`idx≠NONE` 时 `caculateRefreshConfig` 忽略 `updateMode`）
- `5`：合法逻辑域值，且**不在**迁移表 `{0→3,1→2,2→2,3→5,4→2}` 里 ⇒ 扛得住 `validateRefreshConfig`
- 生效**需重启**（运行中的 OECService 会把内存旧值回写覆盖）
- `alias=""`：旧版遗留的 UI 域串（`hd/du/regal_plus/...`）会在加载/校验时把 `updateMode` 重新推导回错域值，必须一并清空

**★ 另一种「还原后失效」形态（2026-11-06 故障机 `6C7F0E64` 实测）**：设备还原/恢复出厂后
MMKV 里**没有 `um=9` 这类写坏的值**，但所有 `eac_app_*` 与全局默认 `eac_default_app_config`
都是 `refreshModeIndex=refresh_mode_3/_4`、非 `NONE` ⇒ `caculateRefreshConfig` 忽略 `updateMode`
⇒ 面板/全局档点击后 `getFastModeIndex()` 纹丝不动。**同样用 `NONE+5` 修复**（`gs_reset_all2.sh`）。
- ⚠️ **这些键不是出厂默认值，是残留**。`/onyxconfig` 是独立 ext4 分区（`mmcblk0p80`），
  **恢复出厂不擦**；卸载 app 也不清它的 EAC 配置。用完整 `pm list packages` 比对，
  78 个 `eac_app_*` 里 44 个属于已卸载 app。`d7e0e42`（2026-10-11 那次改动）只加了
  值域校验和**手动** `reset` 入口，从未在卸载路径清理 ⇒ 本轮补了
  `GlobalEacRefreshHelper cleanup` + `AdminReceiver` 卸载自愈（见 `HANDOFF.md` §5.1-15）。
- ⚠️ `keys eac_app_` 会枚举出系统内部键 **`eac_app_pkg_set`**（值为裸包名列表，**不是 JSON 配置**），
  传入 `FixEac` 会抛 `fastjson JSONException` 并打断循环 ⇒ 已在这两个 `.sh` 里用 `grep -v` 排除。
- ⚠️ **判定「已卸载」必须用 `pm list packages` 全量**，不能用 `-3`（会把系统 app 全误判成未安装）。
- ⚠️ **面板一旦写入 `um∈{0,1,2,3,4}`，下次重启会被迁移表折回「等价索引」**，该 app 又回到
  「被索引覆盖 ⇒ 切换无效」⇒ 需再点一次「还原为原始状态」，或用优化引擎里的 per-app 下拉
  （它写的就是索引）。**验证修复时不要反复点面板，否则会把刚验证过的配置又写脏。**
- ⚠️ 故障机固件与正常机有差异：全局默认 `eac_default_app_config` 与 `com.onyx.mail` 这类
  Onyx 内置 app 在**每次重启**都会被重建回 `refresh_mode_*`；但 77/78 个第三方 app 的
  生效 theme（themeType 3）稳定保持 `NONE+5`，且面板切换实测恢复正常，故不影响使用。

**工具**

| 文件 | 作用 |
|---|---|
| `EacInfo.java` | 只读诊断：设备级读数、`refresh_mode_N`→mode 映射、每 pkg 的 idx/um + theme 明细 |
| `FixEac.java` | 主工具：`read` / `setmode` / `fix` / `keys` / `rm` / `rmnone` / `write` / `active` / `enable` / `migrate` / `sysmap` / `aliasmap` |
| `eac_audit.py` | **★ 2026-11-07 新增（主机侧只读审计）**：拉取 onyxconfig → 判定每个已安装 app 是否会「点了没反应」+ 引擎开关 + 与健康机对照；退出码 0/1/2 可直接进检查清单 |
| `SetDefCfg.java` | 写 `eac_app_<pkg>` + `eac_default_app_config<pkg>`（`NONE` + 指定 um） |
| `DumpThemes.java` | dump 某 pkg 全部 theme（走 `EInkHelper.loadThemes`，**权威读法**） |
| `CheckMode.java` / `SetScope.java` / `ClearScope.java` | scope 通道读数 / 设 / 清 |
| `gs_scan_only.sh` | **只读**全量扫描（找 `um=9` 残留、统计分布） |
| `gs_reset_all2.sh` | **按 MMKV 键**全量重置为 `NONE+5`（含已卸载 app 的残留）— ⚠️ 已过时，见上方 11-07 修正 |
| `gs_health.sh` | 设备健康：reset / `wait all_lut_free` / `update_err` / vcom / scope / 前台 |

**审计（改机前后都跑同一条）**：

```powershell
$env:ADB='C:\...\platform-tools\adb.exe'
python tools/refresh/eac_audit.py --pull --serial <serial> --donor .diag/ORIG_mmkv_onyx_config
# 历史快照复现故障态：
python tools/refresh/eac_audit.py --mmkv .diag/NEW_mmkv_onyx_config --installed .diag/live/installed.txt
```

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

## 3. `kernel/` — 内核（EPDC 超时 / regulator 判据）

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

## 4. `lib/`

| 文件 | 作用 |
|---|---|
| `push_sh.py` | CRLF→LF 后推送并执行（`EINK_DEV=<serial>` 指定设备） |
| `gs_lint_java.py` | 括号配平 + 数组长度一致性（本地编译的替代检查） |
| `gs_import_check.py` | 未用 import 检查 |

---

## 5. `boot/` — 启动模式实测 / recovery / 救砖

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

## 6. `debloat/` — 预装用户级禁用

只做 `pm disable-user` / `pm uninstall --user 0`，随时 `pm enable` / `install-existing` 回滚；
不动 system/super/AVB，不影响 OTA 签名。包清单与理由见 `debloat-list.json`（t1–t4 分层），
判断依据见 `DEBLOAT-AND-BOOTMODES.md`。

| 文件 | 作用 |
|---|---|
| `debloat.ps1` | `list` / `status` / `apply -Tier t1` / `rollback -Tier all`，带 `-WhatIf` 和日志 |
| `debloat-list.json` | 包名、层级、体积、`keep` 列表（不该动的包） |

> 需要设备 root（Magisk su）。`pm disable-user` 对 protected package（systemui/settings）会失败，属预期。
