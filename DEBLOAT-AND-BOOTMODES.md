# Poke6：预装软件该不该清、rec/fastboot 到底阉了什么、能修到什么程度

> 2026-10-29。基于 `ota/images/` 里 51326 完整固件的静态拆解 + 反编译，不是凭印象。
> 设备当前未连接（`adb devices` 为空），所以凡是需要真机才能落锤的结论都标了「待验证」，
> 并配了 `tools/diag-boot.ps1` 一次跑完。
>
> 相关文档：`OTA-FIRMWARE-AND-DISPLAY.md`（OTA/波形）、`LEGADO-EINK.md`（阅读器）、
> `ref.md`（内核实测与刷机记录）。

---

## 0. 一句话结论

| 问题 | 结论 |
|---|---|
| 预装软件要不要清？ | **要清，但只做「用户级禁用」，不要动系统镜像。** 空间和内存都不是瓶颈；清的价值在后台唤醒、通知、商店/云同步自更新和隐私。默认 `t1` 七个包约 430 MB 的 APK 面，禁用后可随时一键回滚。 |
| recovery 被阉割了吗？ | **镜像本身是完整的 AOSP recovery（连 fastbootd、sideload、UI 图片、波形文件都在），阉的是「入口」：** ① `init.rc` 里 `adbd` 是 `disabled`，进 recovery 没有 adb；② 整机只有电源键，菜单要音量键才能选 —— 于是「能进、不能动」。 |
| fastboot 被阉割了吗？ | **协议在，命令表少了 3 类：`flashing`（解锁）、`set_active`（切槽）、`oem edl`。** `flash`/`erase`/`boot`/`getvar`/`download`/`reboot-*`/`snapshot-update` 都在。设备已是 `orange`（已解锁），所以「不能 `flashing unlock`」对你没有实际影响。 |
| 能修吗？ | **recovery：能，已经做好 `out/recovery_adb.img`（kernel/dtbo/dtb 与官方逐字节一致，只改 ramdisk 的 init.rc），刷进去就有 root adb，可 dd 备份/刷写，再切 fastbootd 刷动态分区。** fastboot：**不建议动 ABL**（UEFI FV + LZMA + hash segment，XBL 侧有 `Segments hash check`，改错只能 EDL 救），用 fastbootd / dd / EDL 绕。 |
| 要不要改 `system.img` 删 APK？ | **不要。** A/B + dynamic partitions + AVB，改镜像要重签 vbmeta、关 verity、重做 super，换来的只是 1.6 GB 里的一部分，而 OTA 会全部作废。 |

---

## 0.1 2026-10-29 真机实测修正（比上面的静态结论更重要）

设备接上后实测，推翻了本文两处判断，也踩了一个自己造成的坑：

| 项 | 静态结论 | 真机实测 |
|---|---|---|
| ABL fastboot | 命令表齐全，`flash/erase/boot` 都能用 | **整个模式是死的。** `adb reboot bootloader` 后只枚举 `18D1:4EE2` 约 5 秒就消失，随后卡 logo、USB 全无；120 秒内不再出现，`fastboot devices` 永远看不到。**不能用于刷机/救援。** |
| fastbootd | 组件在，需要入口 | **可用。** `adb reboot fastboot` → `18D1:4EE0`、`is-userspace:yes`、`slot-count:2`、`unlocked:yes`；可 `flash system/product/vendor/system_ext`、`set_active`、`snapshot-update`。**不能刷 boot/recovery**（物理分区）。 |
| recovery 镜像 | 完整 AOSP | 设备原 `recovery_b` 的 md5 与 OTA `recovery.img` **完全一致**（`bd6c7973…`），确认是原厂 stock，没有触摸定制。 |
| 改 recovery 的坑 | — | 只换 ramdisk 也必须重算 `recovery_dtbo_offset`（0x664）。第一次构建没算，ABL 按旧偏移取到垃圾 dtbo，recovery 早期挂掉；而 `adb reboot recovery` 写进 BCB 的 `boot-recovery` 清不掉 → 每次开机都重试坏 recovery → 卡 logo、USB 不枚举。**详见 `RECOVERY-EDL.md`。** |

补充两条实测结论：

- **电源灯不是模式灯**：`qcom,tri-led`（red/green/blue）+ `lights.bengal.so`/充电驱动控制，
  蓝色和粉（红+蓝）都是充电/电量状态。灯亮只说明 PMIC 有电，与 adb/fastboot 是否起来无关。
- **`adb reboot bootloader` 是陷阱**：它会往 `misc` 写 `bootonce-bootloader`，ABL 进那个坏掉的
  fastboot 后卡住；BCB 不清掉，下一次重启还会再进。用之前先想好怎么出来。

> 结论修正：**这台机器的救援链只有两条** —— ① `adb reboot fastboot`（Android 活着时可用，
> 只能刷逻辑分区）；② EDL（只能短接/工程线，或 Android 活着时 `adb reboot edl`）。
> `fastboot`（ABL）不构成救援路径。
>
> 完整实测、根因、检查清单和「为什么放弃 patched recovery」见 **`BOOT-MODE-POSTMORTEM.md`**。

---

## 1. 这次分析用到的原料与工具

| 原料 | 位置 | 说明 |
|---|---|---|
| 12 个分区镜像 | `ota/images/` | payload 拆出来的原始 ext4 / boot image |
| APK 清单与包名 | `_scratch_gs/fs_inventory.json`、`_scratch_gs/apk_packages.json` | 自己写的 ext4 walker（`ext4` Python 库）+ `aapt2 dump badging` 跑完 152 个 APK |
| ABL | `ota/images/abl.img` → 解出 `_scratch_gs/abl_u.bin` | UEFI FV 里的 LZMA guided section，434,376 B |
| recovery ramdisk | `_scratch_gs/recroot/` | 348 个 cpio 条目全部解出 |
| kernel | `kernel_extracted.img` | 4.19.157-perf，`onyx_epdc_fb` / `sepdc` |
| 设备包清单 | `_scratch_gs/installed.txt` | 161 个已安装包，用来区分「预装」与「用户自己装的」 |

工具：`ext4`（纯 Python 读 ext4）、`aapt2`（Android SDK 36.1.0）、`jadx`（kcb 反编译 16k 文件）、
`capstone`/`pyelftools`（`librecovery_ui_ext.so`）、Python `lzma`（ABL）。

---

## 2. 预装软件：先把账算清楚

### 2.1 事实

- 设备上 161 个包，其中 **11 个不是镜像预装**（用户自己装的）：
  `org.koreader.launcher`、`com.legado.app.release`、`com.qidian.QDReader`、`com.dragon.read`、
  `com.tencent.weread.eink`、`mark.via`、`com.sohu.inputmethod.sogou.vivocar`、`bin.mt.plus.canary`、
  `web1n.stopapp`、`io.github.samolego.canta`、`com.topjohnwu.magisk`。
  → **「预装」= 150 个**，其中 AOSP/Qualcomm 基础件约 100 个，真正可谈的第三方/Onyx 增值件约 20 个。
- 镜像内 APK 总量 **1.68 GB**：system 1113 MB / product 476 MB / system_ext 85 MB / vendor 1 MB。
  其中 Onyx 自家 app ≈ 891 MB，Google ≈ 210 MB，NeoBrowser+Trichrome ≈ 349 MB。
- 空间不是问题：`system`/`product`/`vendor` 在 super 里只读，APK 再大也不占用户可用空间；
  内存也不是问题：`OTA-FIRMWARE-AND-DISPLAY.md` §5 实测 MemTotal 1.74 GiB、可用 879 MB、PSI 全 0、无 `am_kill`。

### 2.2 所以「清」的意义在哪

不是省空间，是省**后台**和**干扰**：

1. **商店/云同步会自己醒来**：`com.onyx.igetshop`（155 MB，应用商店/书城）、`com.onyx.appmarket`、
   `com.onyx.android.ksync`（云同步）、`com.onyx.easytransfer`（BOOXDrop 局域网服务）。
   这类东西的特点是「有网就干活」，墨水屏待机最怕这个。
2. **推送/更新会改掉你在用的东西**：Onyx 应用市场会把自家 app 更新到 `/data`，版本一变，
   你之前基于旧版做的 EAC/刷新配置、`allowUseRegalModePackage` 之类实验就可能对不上。
3. **麦克风/语音常驻**：`com.onyx.aiassistant`、`com.qualcomm.qti.sva`（Voice Activation，39 MB）。
4. **第三方预装/首启安装的输入法**（这台机器上是搜狗 `com.sohu.inputmethod.sogou.vivocar`，
   不在镜像里，属于首启/商店安装）——输入法是最大的隐私面之一。
5. **桌面干净**：E-Ink Launcher 的 app 列表直接少一屏。

### 2.3 建议清单（详见 `tools/debloat-list.json`）

| 层 | 内容 | 体积 | 默认 |
|---|---|---|---|
| **t1** | 商店/云同步/传输/产线测试/AI：`igetshop`、`appmarket`、`ksync`、`easytransfer`、`mail`、`aiassistant`、`production.test` | ≈ 430 MB | **开** |
| **t2** | Onyx 小工具与媒体 + NeoBrowser：`gallery`、`musicplayer`、`voicerecorder`、`clock`、`calculator`、`dict`、`latinime`、`org.chromium.chrome` | ≈ 342 MB | 可选 |
| **t3** | Google/高通：`gms`、`gsf`、`tts`、`apps.restore`、`configupdater`、同步适配器、`qti.sva` | ≈ 249 MB | 可选 |
| **t4** | AOSP 杂项：打印三件套、屏保、壁纸裁剪、小区广播、彩信等（单个都 < 4 MB） | ≈ 7 MB | 可选 |

**明确不动（写进 `keep`）**：
`com.onyx`（设置/OTA 入口）、`com.onyx.kreader`（NeoReader）、`com.onyx.kime`（输入法留一个）、
`com.onyx.floatingbutton`（NaviBall）、`com.onyx.android.onyxotaservice`（OTA 服务）、
`com.android.webview`（Legado 书源必需）、`com.android.systemui`、`com.android.settings`、
`com.qti.service.colorservice`、`com.qualcomm.qti.dynamicddsservice`（与 EPD 显示相关）。

> 二选一提醒：`com.onyx.kime`（Onyx 键盘 85 MB）和 `com.onyx.latinime`（AOSP 键盘 27 MB）
> 至少留一个，否则搜索框/书源输入会没有输入法。当前默认输入法在真机上用
> `settings get secure default_input_method` 确认后再决定。

### 2.4 为什么不做「镜像级删除」

| 做法 | 代价 |
|---|---|
| 改 `system.img` / `product.img` 删 APK | 要重做 `super`（`lpmake`）、改 `vbmeta`（`avbtool`）、关 `dm-verity`；A/B 槽位要两边都做；**下一次 OTA 会被 AVB 拒收或直接覆盖**。 |
| Magisk 模块「覆盖删除」 | Magisk 只能 overlay 替换文件，不能真正删除；用空文件替换 APK 会让 `PackageManager` 报解析失败，比禁用更脏。 |
| `pm uninstall --user 0` | 只对当前用户移除，APK 仍在 `/system`；**不释放分区空间**，但等于「这个用户看不见也跑不了」，可 `install-existing` 回滚。 |
| `pm disable-user --user 0` | 最温和：包还在、数据还在、`pm enable` 立即恢复；系统服务/广播不再启动。**推荐默认用这个。** |

### 2.5 操作

```powershell
# 只读：列出清单（不需要设备）
.\tools\debloat.ps1 list

# 看当前状态（需要设备 + Magisk 授权 su）
.\tools\debloat.ps1 status

# 先空跑，确认要动哪些
.\tools\debloat.ps1 apply -Tier t1 -WhatIf

# 真正禁用 t1
.\tools\debloat.ps1 apply -Tier t1

# 更彻底（对当前用户卸载，仍可回滚）
.\tools\debloat.ps1 apply -Tier t1,t2 -Uninstall

# 一键回滚
.\tools\debloat.ps1 rollback -Tier all
```

脚本行为：先校验 `su`、打印 `ro.vendor.build.onyxfp` 确认是 Poke6；逐包执行并把
`pm` 输出写进 `out/debloat-*.log`；失败项（多为 `protected package`，如 systemui）会标 FAIL 但继续。

> 注意：`pm disable-user` 对 `com.android.systemui`、`com.android.settings` 这类 protected 包会失败，
> 这是设计如此，不要试图 `--user 0` 绕过。

### 2.6 我的判断

**值得做，但预期要放对。** 这不是「清出 1 GB」的操作——`/system` 一个字节都不会少；
它是「把一台阅读器的后台收敛到只剩系统 + 阅读器 + 输入法」。
如果只想要一台能看书的机器，`t1` 就够了；如果连 Google/高通语音都不需要，再上 `t3`。
`t2` 里的 `dict`/`gallery` 建议先用一周再决定，NeoReader 的划词和图片查看会用到它们。

---

## 3. rec / fastboot：把「阉割」拆成可验证的几条

### 3.1 设备形态（先对齐前提）

- SoC：**SDM662**（ABL 里的构建路径 `/target/product/Poke6/obj/ABL_OBJ/.../AARCH64/Qcom`，
  XBL 里 `SDM662-REL-1`、`Kamorta`）。
- **A/B + dynamic partitions**：payload 里是 `boot`/`recovery`（不带槽位），
  但 `recovery.fstab` 全是 `slotselect,logical,first_stage_mount`，manifest 里也有
  `dynamic_partition_metadata`；设备上 `boot_a.img`/`boot_b.img` 都在。
- 已解锁：`ro.boot.verifiedbootstate=orange`，ABL 字符串里有
  `State: Unlocked, AvbSlotVerify returned %a, continue boot`。

### 3.2 ABL：151 KB 不是「加密」，是 UEFI FV + LZMA

`ota/images/abl.img` 只有 151,552 B，一开始看像加密（熵 7.99）。实际结构是：

```
0x0000  ELF32 (EM_ARM) 头 + 3 个 phdr
0x1000  hash segment（0x1a38，含 Qualcomm 测试证书）
0x3000  UEFI Firmware Volume
        0x00  ZeroVector[16]
        0x10  FileSystemGuid
        0x20  FvLength = 0x22000
        0x28  "_FVH"
        0x30  HeaderLength = 0x48
0x3064  EFI_LZMA_CUSTOM_DECOMPRESS_GUID (98584eee-1439-5942-9d6e-dc7bd79403cf)
0x3078  LZMA-Alone 流（props 0x5d, dict 16 MB, 解压后 434,376 B）
```

用 `lzma.FORMAT_ALONE` 直接解出 434 KB 的 ABL，里面就是完整的 fastboot 命令分发、
AVB、slot、按键检测代码。**所以 ABL 没有被加密，只是被 LZMA 压进了 FV**。

复现：`_scratch_gs/abl_lzma2.py`（找 GUID → 从 `+0x14` 起解 LZMA）。

### 3.3 ABL 里到底有哪些 fastboot 命令

从解压后的字符串池（`_scratch_gs/abl_strings.txt`）可以直接读出分发用的命令字：

| 有 | 证据 |
|---|---|
| `getvar:` / `getvar:partition-type` | 发布 `max-download-size`、`is-userspace`、`snapshot-update-status`、`product`、`serialno`、`secure`、`variant`、`logical-block-size`、`erase-block-size`、`version-bootloader`、`version-baseband`、`battery-voltage`、`battery-soc-ok`、`charger-screen-enabled`、`hw-revision`、`slot-count`、`current-slot`、`has-slot:boot/system/modem`、`partition-size:`、`partition-type:` |
| `download:` | `max-download-size`、`Requested download size is more than max allowed` |
| `flash:` | 0x4640d；附近有 `Error battery voltage ... can flash` 电量门限检查 |
| `erase` | 0x4aafa（`continue` 紧邻其后的 0x4ac0d） |
| `boot:` | 0x4636f |
| `continue` | 0x4ac0d |
| `reboot-bootloader` / `reboot-recovery` / `reboot-fastboot` | 0x4ac16 / 0x4ac26 / 0x4ac46 |
| `snapshot-update` | 0x4ac36 |
| `oem ...` | `enable-charger-screen`、`disable-charger-screen`、`off-mode-charge`、`select-display-panel`、`device-info`、`rsn`、`wsn`、`cpu` |

| 没有（字符串 0 命中） | 影响 |
|---|---|
| `flashing` | 不能 `fastboot flashing unlock / unlock_critical`；解锁走 ABL 的按键组合（`IsAllowUnlock`、`Creating unlock keys detect event`） |
| `set_active` | 不能在 bootloader 里切槽；用 fastbootd 或 `bootctl` 代替 |
| `format:` / `fetch:` / `ucmd` / `oem edl` | 无关紧要；EDL 用 `adb reboot edl` |

**这解释了「fastboot 被阉割」的真实体感**：不是不能刷，而是**少了解锁和切槽**；
再加上 ABL 的 fastboot 菜单提示还是「Press volume key to select」（见 3.4），
在没有音量键的机器上，菜单看起来就像死的。

### 3.4 recovery：镜像完整，缺的是「能用的入口」

`recovery.img` 是 96 MB 分区镜像，里面是 **25.7 MB 的 boot image**：
kernel 15.6 MB + ramdisk 8.3 MB + recovery_dtbo 1.36 MB + dtb 0.33 MB。
ramdisk 解出 348 个条目，关键件一个不少：

| 文件 | 大小 | 说明 |
|---|---|---|
| `system/bin/recovery` | 409,824 | AOSP recovery 主程序 |
| `system/lib64/librecovery_ui.so` | 379,520 | minui + `ScreenRecoveryUI`（用 `/dev/graphics/fb0`） |
| `system/lib64/librecovery_ui_ext.so` | 10,904 | **OEM 扩展点**，见下 |
| `system/bin/fastbootd` | 298,560 | 用户态 fastboot（刷逻辑分区） |
| `system/bin/minadbd` / `update_engine_sideload` | — | `adb sideload` |
| `res/images/*` | — | `no_command_text.png`、`fastbootd.png`、进度条、字体都在 |
| `system/etc/recovery.fstab` | 3,106 | `slotselect,logical,first_stage_mount` |
| `waveform/eink_waveform.wbf` | 256,003 | 内核 EPD 驱动按名字加载的波形 |

反汇编 `librecovery_ui_ext.so`（`_scratch_gs/recui_dis.py`）：整个 `.text` 只有 88 B，
导出的只有 `make_device` 一个函数，逻辑就是

```
make_device(): 68 B  ->  new Device(new ScreenRecoveryUI)
```

**没有任何 Onyx 定制**：没有 EPD 刷新、没有按键重映射、没有单键导航。
所以 recovery 用的是 AOSP 原生交互——菜单要靠**音量键**上下选。

而按键这件事有硬证据：`recovery.img` 里那份 **base DTB**（328,469 B）中，
按键节点只有 `qcom,qpnp-power-on` / `kpdpwr`，**没有音量键的 gpio-keys 节点**；
ABL 的恢复模式入口也全是电源键时序：

```
======> GetBootIntoModeRecovery detect begin. Power key Down [%u ms]
... BootIntoRecovery detected. [%u ms]
```

**结论**：recovery 能进（ABL 认 `boot-recovery` BCB，也认电源键时序），
UI 也许能画（内核有 `onyx_epdc_fb_probe` / `register_framebuffer`，ramdisk 也带了波形），
但**没有音量键 → 菜单不能导航**；同时 `init.rc` 里

```
service adbd /system/bin/adbd --root_seclabel=u:r:su:s0 --device_banner=recovery
    disabled
```

`adbd` 默认不启动，只有用户选「Apply update from ADB」才会走 minadbd（且只支持 sideload）。
于是：**能看到菜单、选不了、也没有 adb = 事实上的「阉割」**。

### 3.5 一句话总结这张表

| 能力 | 现状 | 原因 |
|---|---|---|
| `fastboot flash/erase/boot` | ✅ | ABL 有 |
| `fastboot getvar all` | ✅ | ABL 有 |
| `fastboot flashing unlock` | ❌ | ABL 没有该命令；但设备已 orange |
| `fastboot set_active` | ❌ | ABL 没有；fastbootd 可替代 |
| `fastboot reboot recovery/fastboot` | ✅ | ABL 有 |
| 进 recovery | ✅ | BCB / 电源键时序 |
| recovery 菜单可操作 | ❌ | 无音量键，stock UI |
| recovery 里有 adb shell | ❌（stock） | `adbd` disabled |
| recovery 里 fastbootd | ✅ | 组件在，但要有入口触发 |

---

## 4. 修复方案（按性价比排序）

### R1（已实现）recovery 默认开 root adb

只改 ramdisk 的 `system/etc/init/hw/init.rc`，末尾追加：

```
on boot
    setprop service.adb.root 1
    setprop sys.usb.config adb

# recovery 启动时会把 usb 关掉；这里重新拉起
on property:sys.usb.config=none
    setprop sys.usb.config adb

# 兜底：recovery 服务已起来，再确认一次
on property:init.svc.recovery=running
    setprop sys.usb.config adb
```

产物：`out/recovery_adb.img`（25,636,864 B，md5 `61fbbe3f2f1b12efb9f9c2db7c822daa`；
第一版是 `1b3cc5bd…`，因为没算 `recovery_dtbo_offset` 而会卡 logo，已废弃）。

> ⚠️ **没有「不写盘的试用」办法**：`fastboot boot` 需要 ABL fastboot，而它在这台机器上是死的；
> fastbootd 不支持 `boot`。所以只能写进 `recovery_b` 再 `adb reboot recovery` 验证，
> 而一旦验证失败，BCB 里的 `boot-recovery` 清不掉，就只能 EDL。
> **先确认你能进 9008 再刷。**

生成器：`tools/mk-recovery-adb.py`，它做了三件事：
1. 解析 boot header v2，按 `mkbootimg` 规则**重算 `id`**，
   并先用「原 kernel + 原 ramdisk」重建一遍做**逐字节回归**（结果是 `rebuild-identical: True`）；
2. 解 gzip → 解析 cpio（newc）→ 只改 `init.rc` → 重建 cpio → 重新 gzip；
3. 自检：kernel / recovery_dtbo / dtb 必须与官方**逐字节一致**，新 ramdisk 能回读且含补丁。

已验证：348 个条目全部保留，除 `init.rc`（6060 → 6450 B）外数据零差异。

写入（**唯一可行路径**，因为 ABL fastboot 是死的）：

```powershell
# 0) 先备份当前 recovery（96 MB）并核对 md5
adb shell su -c "dd if=/dev/block/by-name/recovery$(adb shell getprop ro.boot.slot_suffix) of=/sdcard/recovery_backup.img bs=4M"
adb shell su -c "md5sum /sdcard/recovery_backup.img"

# 1) 写（脚本会先备份、再写、再回读校验；-WhatIf 先空跑）
.\tools\flash-recovery-adb.ps1 -WhatIf
.\tools\flash-recovery-adb.ps1 -Yes

# 2) 验证
adb reboot recovery
adb devices                        # 期望 state=recovery
adb shell id                       # 期望 uid=0(root)
adb shell getprop init.svc.adbd    # 期望 running
```

> 如果 `adb devices` 不出现：recovery 没起来或没拉起 adbd，BCB 里的 `boot-recovery`
> 大概率清不掉，**只能 EDL**（`RECOVERY-EDL.md`）。所以刷之前先确认 9008 能进。

进 recovery 后（**recovery 里 adbd 本身就是 root，不需要 su**）：

```bash
# 备份任意物理分区（boot/recovery/vbmeta/…）
adb shell dd if=/dev/block/by-name/boot_b of=/sdcard/boot_b.img bs=4M

# 切到 fastbootd，刷动态分区（system/product/vendor/system_ext）
adb shell setprop sys.usb.config fastboot
fastboot devices
fastboot flash system system.img
```

回滚（回到 Android 后执行，那里才需要 su；recovery 里不需要）：

```bash
adb shell su -c 'dd if=/sdcard/recovery_b_backup.img of=/dev/block/by-name/recovery_b bs=4M'
# 或
fastboot flash recovery ota/images/recovery.img
```

> 为什么可以刷未签名镜像：已解锁时 ABL 明确走
> `State: Unlocked, AvbSlotVerify returned %a, continue boot`，AVB 失败不阻断启动。
> 这也是之前 `boot_patched_*.img` 能直接 `dd` 进 `boot_b` 的原因。

### R2（无需改动）用 fastbootd 解决「切槽 / 动态分区」

fastbootd 在 recovery ramdisk 里（`system/bin/fastbootd` + `init.rc` 的 `service fastbootd ... disabled`），
只要把 `sys.usb.config` 设成 `fastboot` 就会起来。它支持：

- `fastboot flash system/product/vendor/system_ext`（逻辑分区）
- `fastboot set_active <slot>`（fastbootd 走 bootctl HAL，不受 ABL 命令表限制）
- `fastboot getvar is-userspace` → `yes`
- `fastboot wipe-super`（慎用）

所以「fastboot 缺 `set_active`」在实际操作里不构成障碍。

### R3（不推荐）patch ABL 加命令

能做的前提是过 XBL 的校验：ABL 的 ELF 里有 hash segment，XBL 里有
`bl_elf_segs_hash_verify_timestamp` / `Segments hash check`；
即使设备 `orange`，这条链通常也不放开。改错的结果是**开机直接掉 EDL**，
而 Poke6 的 EDL 需要 firehose loader（`bkerler/Loaders` 里有 SDM662 的
`0014d0e100000000_d40eee56f3194665_FHPRG.bin`，Page 上验证可用，Poke6 未验证）。
**收益（补 `flashing`/`set_active`）远小于风险，不建议。**

### R4（可选，工作量大）让 recovery 菜单能用

两条路：
1. 写一个真正的 `librecovery_ui_ext.so`，用单键（电源短按=下移、长按=确认）替换
   `Device::HandleKey`，或者直接驱动 `/dev/ebc` 做 EPD 刷新；
2. 干脆不用菜单：R1 之后 recovery 已经是「无头可用」的，菜单只是锦上添花。

现阶段建议停在 R1 + R2。

---

## 5. 待真机验证的清单

设备接上后先跑：

```powershell
.\tools\diag-boot.ps1 -Stage all
```

它会依次：读系统属性 → `adb reboot bootloader` → `fastboot getvar all`（抓
`is-userspace`/`slot-count`/`unlocked`/`battery-voltage`）→ `adb reboot recovery`
（看有没有 adb、`init.svc.adbd`）→ `adb reboot fastboot`（看 fastbootd 是否枚举）→ 回系统。
日志在 `out/diag-boot-*.log`。

需要落锤的三个点：

1. `fastboot devices` 在 bootloader 下是否出现（如果不出，是 USB 驱动/线，不是 ABL）；
2. stock recovery 是否**有画面但选不动**（验证 3.4 的结论）；
3. `adb reboot fastboot` 是否直接进 fastbootd（如果进，R2 连 R1 都不用）。

---

## 6. 风险与边界

| 项 | 说明 |
|---|---|
| 刷 recovery 失败 | 不影响正常启动（recovery 分区只在 `reboot recovery` 时用）；最坏是进 recovery 黑屏，`fastboot flash recovery` 或 `dd` 刷回备份即可。 |
| `dd` 写错分区 | 脚本会先打印 `recovery$slot`、备份并校验 md5，才允许写；`-WhatIf` 可空跑。 |
| 禁用过头 | `rollback -Tier all` 一条命令；`pm disable-user` 不删数据。 |
| OTA | 刷过 recovery 后 OTA 仍可装（A/B 会写另一个槽），但 OTA 后 recovery 会被官方镜像覆盖 → 需要重刷 R1。 |
| 改 ABL | 不做。见 R3。 |

---

## 7. 产物清单

| 文件 | 说明 |
|---|---|
| `out/recovery_adb.img` | 默认开 root adb 的 recovery（kernel/dtbo/dtb 与官方一致） |
| `tools/mk-recovery-adb.py` | 生成器 + 逐字节回归/自检 |
| `tools/flash-recovery-adb.ps1` | dd 备份→写入→回读校验，带 `-WhatIf` |
| `tools/debloat.ps1` + `tools/debloat-list.json` | 用户级禁用/回滚，按 t1–t4 分层 |
| `tools/diag-boot.ps1` | 一次性探测 fastboot / recovery / fastbootd 的真实能力 |
| `_scratch_gs/abl_u.bin` | 解压后的 434 KB ABL（可继续用 Ghidra/rizin 分析） |
| `_scratch_gs/recroot/` | 完整 recovery ramdisk 解包（348 项） |
| `_scratch_gs/apk_packages.json` | 152 个 APK 的路径/包名/大小/标签 |

---

## 8. 附：本次反编译的几个可复用结论

1. **boot image v2 的 `id`**：`sha1(kernel||len, ramdisk||len, second||len, dtbo||len, dtb||len)`，
   写到 0x240 的 32 字节区（20 字节 SHA1 + 12 字节 0）。用原图回归可以验证到逐字节一致。
2. **Qualcomm ABL 的「加密」多半是 UEFI FV + LZMA**：先找 `_FVH`，再找
   `98584eee-1439-5942-9d6e-dc7bd79403cf`，从 GUID 后第 20 字节起是 LZMA-Alone。
3. **AOSP recovery 的 OEM 扩展点**就是 `librecovery_ui_ext.so` 的 `make_device()`；
   这台机器上它是空壳，说明厂商没做 EPD/按键适配，而不是「删了 recovery」。
4. **没音量键的机器**，stock recovery 的菜单天然不可用；判断依据看 DTB 里有没有
   `gpio-keys` 的 volume 节点，以及 ABL 的 `GetBootIntoModeRecovery` 是否只轮询电源键。
