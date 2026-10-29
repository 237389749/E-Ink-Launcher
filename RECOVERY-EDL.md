# Poke6 卡 logo 救砖（EDL）：只写 2 个分区

> 完整复盘、四种启动模式实测、根因与检查清单见 **`BOOT-MODE-POSTMORTEM.md`**。
> 本文只保留操作步骤。

> **2026-10-29 22:5x 已按本流程恢复过一次**：`misc` 清零、`recovery_b` 回官方
> （`bd6c7973…`），设备正常开机，`su` 正常，BCB 前 32 字节为 0。

> 适用：`adb reboot recovery` 之后卡在开机 logo、USB 不枚举、`adb`/`fastboot` 都看不到。
> 2026-10-29 的这次事故就是它。

## 0. 为什么会卡（根因）

`adb reboot recovery` 会把 BCB 写成 `boot-recovery`，ABL 于是去引导 `recovery_b`。
我们刷进 `recovery_b` 的镜像是「原 kernel + 改过的 ramdisk」，但**没有更新 boot header
里的 `recovery_dtbo_offset`（0x664）**：ramdisk 变小后 dtbo 从 `0x16e0000` 移到了
`0x16d6000`，字段还指着旧地址，ABL 按旧地址取 dtbo 得到的是垃圾数据
（`00000d48…` 而不是 `d7b7ab1e…`），recovery 在早期就挂掉。

而 recovery 没跑起来 → BCB 里的 `boot-recovery` 不会被清 → **每次开机都重试同一个坏 recovery**
→ 停在 logo、USB 不初始化。这不是硬件坏，是镜像坏了 + BCB 卡住。

**已修复**：`tools/mk-recovery-adb.py` 现在会按实际布局重算 `recovery_dtbo_offset`，
新镜像 `out/recovery_adb.img` 的该字段 = `0x16d6000`，字段处读出的就是真 dtbo。

## 1. EDL 要写的两个分区

| 分区 | 文件 | 大小 / md5 | 作用 |
|---|---|---|---|
| `misc` | `out/misc_zero_1mb.img` | 1 MiB / `b6d81b36…` | 清掉 BCB 里的 `boot-recovery`，让下次正常开机 |
| `recovery_b` | `out/recovery_stock.img` | 96 MiB / `bd6c7973…` | 回官方 recovery（与设备原备份 md5 完全一致） |

> 先恢复再谈优化。`boot_b` 完全不动，Android 数据不动。

## 2. 进 9008

- 短接触点（或工程线）→ 设备管理器出现 `Qualcomm HS-USB QDLoader 9008 (COMx)`；
- 你工作区里两个 loader 都是 SDM662 的 devprg，公版可用：
  `XiaomiCommon662_devprg.bin` / `HuaweiCommon_662_devprg.bin`。

## 3. 写入

### 方式 A：`edl`（bkerler）

```powershell
# 只读校验（可选）
edl --loader=XiaomiCommon662_devprg.bin --memory=ufs printgpt

edl --loader=XiaomiCommon662_devprg.bin --memory=ufs w misc        out/misc_zero_1mb.img
edl --loader=XiaomiCommon662_devprg.bin --memory=ufs w recovery_b  out/recovery_stock.img
edl --loader=XiaomiCommon662_devprg.bin --memory=ufs reset
```

### 方式 B：QFIL

1. Configuration → Flat Build，Programmer 选 `XiaomiCommon662_devprg.bin`；
2. Tools → Partition Manager → 按名字写：
   - `misc` ← `out/misc_zero_1mb.img`
   - `recovery_b` ← `out/recovery_stock.img`
3. 写完后 `Reset` 或长按电源重启。

## 4. 验证

```powershell
adb devices                 # 应该回到 Android
adb shell getprop ro.boot.slot_suffix      # _b
adb shell 'su -c "dd if=/dev/block/by-name/misc bs=32 count=1 | xxd"'   # 前 32 字节应为 0
adb shell 'su -c "md5sum /dev/block/by-name/recovery_b"'                 # 应为 bd6c7973…
```

## 5. 这次事故的教训（写进流程）

1. **改 boot image 的任何一段，都必须重算 `recovery_dtbo_offset`**；只改 ramdisk 也要。
   回归方法：拿原 kernel + 原 ramdisk 重建，必须与官方**逐字节一致**；再用改后的镜像
   验证「字段指向的偏移处 = 原 dtbo 字节」。
2. **不要在没有物理兜底的情况下把 `boot-recovery` 留在一个未验证的 recovery 上。**
   `adb reboot recovery` 是「一次性把 BCB 写成 boot-recovery」，recovery 起不来就清不掉。
3. 这台机器的 **ABL fastboot 是死的**（见下），所以出事只能 EDL；先确认兜底再刷。

## 6. 附：本次实测的三种“模式”

| 入口 | 结果 |
|---|---|
| `adb reboot fastboot` | ✅ `18D1:4EE0`，`is-userspace:yes`，能刷逻辑分区（system/product/vendor/system_ext）、`set_active`；**不能**刷 boot/recovery |
| `adb reboot bootloader` | ❌ 只枚举 `18D1:4EE2` 约 5 秒后消失，随后卡 logo、USB 全无；`fastboot devices` 永远看不到 |
| `adb reboot edl` | ✅ 进 9008（需 loader），但**只能从已开机的 Android 触发**；开机挂了就只能短接 |

**电源灯**：`qcom,tri-led`（red/green/blue）由 `lights.bengal.so` / 充电驱动控制，
蓝色、粉（红+蓝）都是**充电/电量状态**，不是启动模式指示；灯亮只说明 PMIC 有电，
不代表 USB 功能已经起来。所以「灯亮但没模式」是正常的——两者本来就不相干。
