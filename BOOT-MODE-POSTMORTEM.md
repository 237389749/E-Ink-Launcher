# Poke6 启动模式与救援：实测复盘（2026-10-29）

> 一次「想把 recovery 改成默认带 adb」的尝试，最后以 EDL 救回、放弃方案收尾。
> 这份文档记录：实测到的四种启动模式、两个真正的根因、一次自己造成的变砖、
> 以及以后改 boot 镜像必须遵守的检查清单。
>
> 相关：`RECOVERY-EDL.md`（救砖步骤）、`DEBLOAT-AND-BOOTMODES.md`（静态分析 + 预装清理）。

---

## 0. 结论（先看这个）

| 问题 | 答案 |
|---|---|
| ABL fastboot 能用吗？ | **不能。** `adb reboot bootloader` 后只枚举 `18D1:4EE2` 约 5 秒就消失，之后 USB 全无；120 秒内不再出现。 |
| fastbootd 能用吗？ | **能。** `adb reboot fastboot` → `18D1:4EE0`、`is-userspace:yes`；可刷 `system/product/vendor/system_ext`、`set_active`。**不能刷 boot/recovery**。 |
| recovery 能改成带 adb 吗？ | **能起，但默认 unauthorized。** 修好 dtbo 后 recovery 正常启动、adbd 也起来了（`adb devices` 显示 `unauthorized`）；把 `ro.adb.secure=0` 打进去的 v3 已构建，但没再刷。 |
| 救援靠什么？ | **EDL。** 短接/工程线进 9008，写 `misc` + `recovery_b` 两个分区即可。 |
| 最终决策 | **不再投入 patched recovery。** 收益低（入口本身不稳）、试错成本高（每次拆后盖）。保留官方 recovery，关掉 OTA，稳定使用。 |

---

## 1. 四种启动模式实测矩阵

| 入口 | USB 表现 | 能力 | 结论 |
|---|---|---|---|
| `adb reboot fastboot`（fastbootd） | `18D1:4EE0`，`fastboot devices` 可见 | `is-userspace:yes`、`slot-count:2`、`unlocked:yes`、`max-download-size=0x10000000`；可 `flash` 逻辑分区、`set_active`、`snapshot-update` | ✅ 可用，但**只能从已开机的 Android 触发** |
| `adb reboot bootloader`（ABL fastboot） | `18D1:4EE2` 约 **5 秒**后消失，此后无任何枚举 | 无 | ❌ 死路；还会把 `bootonce-bootloader` 留在 BCB 里 |
| `adb reboot recovery`（recovery） | 修好 dtbo 后：`18D1:D001`，adb 出现在 **31 秒**，状态 `unauthorized` | recovery 起来了、adbd 也在跑，但没有授权通道 | ⚠️ 可起，默认不可控 |
| `adb reboot edl`（9008） | `05C6:9008`（需 loader） | 全分区读写 | ✅ 硬兜底，但**只能从 Android 触发或短接** |

> 关键点：**四种模式里，只有 fastbootd 和 EDL 是「确定可用」的，而两者都要求 Android 先起来。**
> 一旦 boot 挂了，软件层没有入口——这就是这台机器的天花板。

---

## 2. 时间线（做了什么、看到什么）

1. `adb reboot bootloader` → 卡 logo、USB 全无；120 秒监听无枚举。
2. 监听抓到：该命令后设备曾以 `18D1:4EE2` 出现约 5 秒（带 adb 复合接口），随后消失。
3. `adb reboot fastboot` → `4EE0` 正常，`getvar all` 完整（`is-userspace:yes`）。
4. 刷入第一版 patched recovery（只改 init.rc）→ `adb reboot recovery` 后 75 秒无 adb →
   电源循环仍回 logo。
5. EDL 救回：`misc` 清零 + `recovery_b` 回官方（md5 `bd6c7973…`，与设备原备份一致）。
6. 修复 `recovery_dtbo_offset` 后刷第二版 → recovery 起来，31 秒时 `adb` 出现但 `unauthorized`。
7. 构建第三版（`ro.adb.secure=0` + `/adb_keys`），未刷；决定收手。

---

## 3. 根因

### 3.1 卡 logo 的真因：`recovery_dtbo_offset` 没重算（自己造成的）

修改 ramdisk 后 gzip 体积变化，dtbo 在镜像里的位置随之移动，但 boot header 的
`recovery_dtbo_offset`（0x664，u64）还是旧值：

| | 原图 | 第一版 patched |
|---|---|---|
| ramdisk | 8,335,461 | 8,296,991 |
| 实际 dtbo 偏移 | `0x16e0000` | `0x16d6000` |
| header 字段 | `0x16e0000` ✅ | `0x16e0000` ❌ |
| 字段处读到的头 8 字节 | `d7b7ab1e…`（dtbo magic） | `00000d48…`（垃圾） |

ABL 会解析这个字段（字符串里有 `RecoveryDtboOffset`、`RecoveryDtboSize` 的校验和报错），
按旧偏移取到垃圾 dtbo，recovery 早期挂掉。

**修复**：`tools/mk-recovery-adb.py` 现在按实际布局重算：

```python
dtbo_off = ps + align(len(kernel)) + align(len(ramdisk)) + align(len(second))
struct.pack_into("<Q", hdr, 0x664, dtbo_off)
```

**验证**：字段处读出的 8 字节必须是 `d7b7ab1e…`，且 `img[off:off+size] == 原 dtbo`。
修好后第二版确实起来了（只差授权）。

### 3.2 变砖循环：BCB `boot-recovery` 清不掉

`adb reboot recovery` 会往 `misc` 写 BCB 命令 `boot-recovery`；ABL 读到就引导 recovery。
recovery 若在启动早期挂掉，就执行不到 `finish_recovery()` 里的 `clear_bootloader_message()`，
BCB 一直留着 → **每次开机都重试同一个坏 recovery**。

所以「卡 logo + 无 USB + 重启无效」不是硬件坏，是 **镜像坏 + BCB 卡住**。
唯一解法是 EDL 把 `misc` 清零（或写回可用的 recovery）。

### 3.3 adb `unauthorized`：`ro.adb.secure=1` 且 recovery 没有确认 UI

recovery ramdisk 里 `default.prop` 是 `prop.default` 的软链，其中：

```
ro.secure=1
ro.adb.secure=1      # 出现两次（system / vendor 段）
ro.debuggable=0
```

`ro.adb.secure=1` 时 adbd 要求 RSA 授权，而 recovery 没有「允许 USB 调试」弹窗，
于是永远停在 `unauthorized`。PC 侧看到的就是「设备在，但什么命令都执行不了」。

解决（v3 已实现，未刷）：

- `ro.adb.secure=1` → `0`；
- 可选再加 `/adb_keys`（把 `%USERPROFILE%\.android\adbkey.pub` 放进 ramdisk）。

### 3.4 电源灯：tri-LED，不是模式灯

DTB 里是 `qcom,leds@d000` / `qcom,tri-led`（red / green / blue 三色 PMIC LED），
由 `lights.bengal.so` 和充电驱动控制。蓝色、粉（红+蓝）都是**充电/电量状态**。

> 「灯亮但检测不到模式」是正常的：LED 只说明 PMIC 有电，和 USB 功能是否启动无关。
> 本机卡 logo 时 USB 死掉，灯照样亮。

### 3.5 `4EE2` 只活 5 秒

现象：`adb reboot bootloader` 后设备以 `18D1:4EE2` 出现约 5 秒（带 adb 复合接口），
然后从 USB 上消失，屏幕停在 logo，按键无响应。

判断：**ABL 的 fastboot app 起来一半就死了**（USB 枚举完成后又掉线），不是「命令表缺东西」。
`4EE0/4EE2/0xD001` 这些 PID 不是写在 `init.rc` 里的（rc 只写 `0x4EE0` 和 `0xD001`），
说明 `4EE2` 来自 ABL 自己的 fastboot USB 描述符。

能不能修：**不能从 userspace 修**。ABL 在 `abl` 分区，ELF + UEFI FV + LZMA，
外面还有 hash segment，XBL 侧有 `Segments hash check`；改它风险远大于收益。

### 3.6 recovery 为什么连菜单都画不出来

实测：recovery 起来后（`adbd` 在跑、`adb devices` 能看到 `unauthorized`），
**屏幕上看不到任何菜单/选项**。原因链：

1. stock recovery 的 UI 是 `librecovery_ui.so` → minui，打开 `/dev/graphics/fb0` 画 RGB 位图
   （二进制里 `cannot open fb0`、`/dev/graphics/fb0` 都在）；
2. 这块屏是 EPD：内核走 `onyx_epdc_fb_probe` + `onyx_epdc_*`，刷新要波形和
   `SET_EBC_SEND_BUFFER` 一类的 ioctl，**不是写 framebuffer 就会显示**；
3. 厂商的扩展点 `librecovery_ui_ext.so` 整个 `.text` 只有 88 B，只导出 `make_device()`
   → `new Device(new ScreenRecoveryUI)`，**没有任何 EPD 初始化、波形加载、按键/触摸映射**。

所以 recovery 在这台机器上是「盲的」：菜单画不出来、也没有触摸/音量键可操作。
它唯一可用的通道是 adb —— 而默认 `ro.adb.secure=1`（见 3.3），于是 `unauthorized`。

**这解释了为什么「进 recovery 也没用」**：不是 recovery 没启动，是它没有可交互的界面；
想让 recovery 有用，只能靠 adb，而 adb 又必须 `ro.adb.secure=0`（v3 已做，未再刷）。

---

## 4. 恢复流程（已实测）

只写两个分区，`boot_b` 和数据都不动：

| 分区 | 文件 | md5 |
|---|---|---|
| `misc` | `out/misc_zero_1mb.img` | `b6d81b36…` |
| `recovery_b` | `out/recovery_stock.img`（= OTA 官方 recovery） | `bd6c7973…` |

```powershell
edl --loader=XiaomiCommon662_devprg.bin --memory=ufs w misc       out/misc_zero_1mb.img
edl --loader=XiaomiCommon662_devprg.bin --memory=ufs w recovery_b out/recovery_stock.img
edl --loader=XiaomiCommon662_devprg.bin --memory=ufs reset
```

验证：

```powershell
adb devices                                   # device
adb shell 'su -c "dd if=/dev/block/by-name/misc bs=32 count=1 | xxd"'   # 前 32 字节为 0
adb shell 'su -c "md5sum /dev/block/by-name/recovery_b"'                # bd6c7973…
```

> 官方 recovery 的 md5 与设备原备份完全一致，可以放心写回；`misc` 除 BCB 外没有别的内容。

---

## 5. 检查清单（下次改 boot 镜像前逐条过）

### A. 构建阶段

- [ ] 改 ramdisk/内核后，重算 **`id`**（0x240）和 **`recovery_dtbo_offset`**（0x664）；
- [ ] 用「原 kernel + 原 ramdisk」重建一遍，必须与官方**逐字节一致**（回归）；
- [ ] 产物自检：字段指向的偏移处 == 原 dtbo 字节；`dtb` 位置 == 原 dtb；
- [ ] 解包产物再读一次 ramdisk，确认补丁在、文件数/权限没变。

### B. 刷机前

- [ ] 确认 **9008 能进**（后盖/工程线/工具就绪）；
- [ ] 备份 `recovery_<slot>` 和 `misc`（至少记 md5）；
- [ ] 明确 BCB 当前内容（`boot-recovery` 是危险状态）；
- [ ] 知道失败后怎么清 BCB（EDL / 可用的 recovery）。

### C. 日常

- [ ] **不要用 `adb reboot bootloader`**：它会写 `bootonce-bootloader`，而 fastboot 是死的；
- [ ] 刷逻辑分区用 `adb reboot fastboot` + `fastboot flash`；
- [ ] 刷 boot/recovery 用 root `dd`；
- [ ] OTA 会覆盖**非当前槽**的 boot/recovery/vbmeta，改过的 recovery 最多活一轮；不想被覆盖就关 OTA。

### D. 救援优先级

1. 能进 recovery → 用 recovery 的 adb（如果做了 secure=0）`dd` 修；
2. 能进 Android → `adb reboot fastboot` 刷逻辑分区、`adb reboot edl` 进 9008；
3. 都不行 → 短接 9008，写 `misc`/`recovery` 回官方。

---

## 6. 产物与状态

| 文件 | 说明 |
|---|---|
| `tools/mk-recovery-adb.py` | 生成器：init.rc + `ro.adb.secure` + `/adb_keys`，dtbo 偏移已修正 |
| `out/recovery_adb.img` | v3，md5 `3471001f…`（secure=0 + adbkey，**未刷**） |
| `out/recovery_stock.img` | 官方 recovery，救砖用（md5 `bd6c7973…`） |
| `out/misc_zero_1mb.img` | 清 BCB |
| `out/misc_force_recovery.img` | 写 `boot-recovery`（强制下次进 recovery，测试用） |
| `RECOVERY-EDL.md` | 救砖操作手册 |

设备状态（2026-10-29 23:35 收尾）：
`_b` 槽、`orange`、`sys.boot_completed=1`、`su` 正常；
`recovery_b` 已写回官方 `bd6c7973cef71eaa9b2aeac879ecfadb`；
`misc` 前 32 字节为 0（BCB 已清）；重启验证正常进入 Android。
`/sdcard` 保留 `misc_backup.img`、`recovery_b_backup.img`（官方原备份）、
`recovery_b_before_stock.img`（还原前那个未知 `5c0aa9ea…` 镜像，未删）。

---

## 7. 为什么放弃，以及以后还值得做什么

**收益评估**：

- patched recovery 的价值 = 「能进 recovery 时多一个 adb 控制台」；
- 但 recovery 的入口本身靠按键组合（ABL 检测：按住 ≥3 秒 + 连按 5 次，每段 <2 秒），成功率低；
- 而 fastboot（ABL）是死的，EDL 又要拆机；
- 所以：**能进 recovery 时你本来就能用触摸/按键操作，进不去时这个 adb 也帮不上**。

**风险**：每次试错都要拆后盖进 9008，时间成本远大于收益。

**如果以后还想做**，唯一有价值的方向是 **A/B slot retry/fallback**：

- `android.hardware.boot@1.1-service` 在跑，`misc` 偏移 2048 是 AOSP `slot_metadata`（目前全 0）；
- 用 fastbootd 的 `set_active` 让 HAL 写一次，读 `misc` 确认格式；
- 若 ABL 认 `tries_remaining=0` 为 unbootable，就能做「A 槽救援系统 + B 槽实验，失败自动回 A」；
- 这是这台机器唯一可能做到「按键无关」的软救援，但需要先有 EDL 兜底再试。
