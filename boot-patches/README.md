# Poke6 EPDC 故障 · 内核 wait 超时 patch 包

> 2026-08-30 实测。针对 **BOOX Poke6 墨水屏面板电源芯片（TPS6518x）供电故障**导致
> EPDC 刷新卡死循环的**缓解性内核补丁**（治标不治本，硬件根因仍需维修）。

## 背景

- 症状：TPS6518x `waiting for power good!` 失败（364 次/3.7 天）、`DISPLAY regulator enable -110` 超时、
  EPDC `wait all_lut_free timeout` → `onyx_epdc_reset` 循环（活跃时每 16~19s 一次）
- 机制：刷新请求 → LUT 卡死（waveform 只执行 2/22 帧）→ 5s 超时 → reset → powerup 失败 → 循环。
  普通刷新永远无法真正完成，**唯一显示更新路径 = reset 后短暂窗口执行积压帧**
- 内核：`Linux 4.19.157-perf`（clang 10），**HZ=100**（1s = 100 jiffies = 0x64）
- 思路（用户定调）：不再期望普通刷新，把 reset 周期压到最短，让 reset 当"伪刷新"用

## 文件清单

| 文件 | 说明 | 大小 |
|---|---|---|
| `boot_extracted.img` | **原版 boot**（从 boot_b 分区 dd 提取，96MB，含未用空间） | 96MB |
| `kernel_extracted.img` | 解压后的 ARM64 内核（供分析/反编译） | 34MB |
| `boot_patched_1s_v2.img` | **安全版**：仅 wait 超时 5s→1s（3 处 patch） | 17MB |
| `boot_patched_1s_v3.img` | v2 + powerup 重试次数 3/5→1（5 处 patch） | 17MB |
| `boot_patched_1s_v4.img` | v3 + powerup 等待压短（**10 处 patch，故障机当前使用**） | 17MB |

## 各版本 patch 内容

### v2：wait 超时 5s → 1s（安全，推荐正常机可用）
| 内核文件偏移 | 原 4 字节 | 新 4 字节 | 含义 |
|---|---|---|---|
| 0x543110 | 52 80 3E 81 (`mov w1,#0x1f4` 500j=5s) | 52 80 0C 81 (`mov w1,#0x64` 100j=1s) | wait all_lut_free 超时 |
| 0x542A70 | 52 80 3E 81 | 52 80 0C 81 | wait lut_free 超时 |
| 0x54314C | 52 82 71 02 (`mov w2,#0x1388`=5000) | 52 80 7D 02 (`mov w2,#0x3e8`=1000) | 日志 %d 参数（硬编码） |

### v3：+ powerup 重试次数（正常机零副作用，不进重试路径）
| 偏移 | 原 | 新 | 含义 |
|---|---|---|---|
| 0x5F43AC | 71 00 0F 1F (`cmp w24,#3`) | 71 00 07 1F (`cmp w24,#1`) | 外层重试 3→1 |
| 0x5F46C0 | 71 00 17 1F (`cmp w24,#5`) | 71 00 07 1F (`cmp w24,#1`) | 内层重试 5→1 |

### v4：+ powerup 等待压短（⚠️ 正常机未验证，仅建议故障机使用）
| 偏移 | 原 | 新 | 含义 |
|---|---|---|---|
| 0x5F46E8 | AA 14 03 E0 (`mov x0,x20`≈17s) | D2 80 7D 00 (`mov x0,#0x3e8`=1ms) | ⚠️ 等 PowerGood 超时上限 1ms |
| 0x5F4408 | 52 80 02 A0 (`mov w0,#0x15`) | 52 80 00 20 (`mov w0,#0x1`) | 外层 sleep 21→1 |
| 0x5F4410 | 52 80 25 80 (`mov w0,#0x12c`) | 52 80 01 40 (`mov w0,#0xA`) | 外层 sleep 300→10 |
| 0x5F46B4 | 2A 16 03 E0 (`mov w0,w22`) | 52 80 00 20 (`mov w0,#0x1`) | 内层 sleep 21→1 |
| 0x5F4718 | 2A 15 03 E0 (`mov w0,w21`) | 52 80 00 20 (`mov w0,#0x1`) | 失败后 sleep 5→1 |

> 编码要点：`cmp w24,#imm` = `subs w31,w24,#imm`（rd 恒为 31，不是 24）——按 rd=24 计算会失败。

## 实测效果（故障机）

| 指标 | 原版 | v4 |
|---|---|---|
| reset 间隔（活跃时） | 16~19s | ~1.2s |
| 翻页响应 | ~8s | ~3-4s |
| 日志 | timeout 5000 ms | timeout 1000 ms |
| powerup 失败耗时 | ~5.7s（Retry 2/1/0 ×1.9s） | ~1.6s（i2c 硬件超时，未继续压） |

## 刷入方法（需 root / 已解锁 bootloader，`ro.boot.verifiedbootstate=orange`）

```bash
# 确认当前槽位（A/B 设备）
adb shell getprop ro.boot.slot_suffix   # 例: _b

# 推送 + 刷入（替换为对应槽位）
adb push boot_patched_1s_v4.img /sdcard/
adb shell su -c "dd if=/sdcard/boot_patched_1s_v4.img of=/dev/block/by-name/boot_b bs=4M"

# 刷前备份原 boot（回滚用）
adb shell su -c "dd if=/dev/block/by-name/boot_b of=/sdcard/boot_b_backup.img bs=4M"
adb reboot
```

## 回滚

```bash
adb shell su -c "dd if=/sdcard/boot_b_backup.img of=/dev/block/by-name/boot_b bs=4M"
adb reboot
# 或刷 v2（安全版）/ v3 任意版本
```

## ⚠️ 风险与说明

1. **根治**仍是硬件维修（TPS6518x 供电链 / 屏幕排线 / 面板），本补丁只是缓解（"让故障机能用"）；
2. **v4 的正常机副作用未验证**（1ms 超时上限 + sleep 压短，理论上可能影响正常机上电稳定性）：
   - 故障机：安全（反正 powerup 失败，压短只是少等）
   - 正常机：建议先实测 v4 无异常再用；保守用 v2（3 处安全 patch）
3. wait 5s→1s 会**减少 EPDC 完成波形的等待窗口**：正常机上若波形执行 >1s 可能被提前 reset（一般波形 <1s，风险低）；
4. 剩余 1.6s 是 regmap/i2c 写 TPS6518x 无响应的硬件超时（通用 i2c 层），**不建议继续压**（影响所有 i2c 设备）。

## 诊断结论（为什么会有这个补丁）

- `TPS6518x waiting for power good!`（364 次）→ EPDC 上电失败 → 刷新 LUT 卡死
  → `wait all_lut_free timeout` → `onyx_epdc_reset`（80 次/133min，平均 19s 一次）→ 循环
- 正常机（同版本固件、3.2 天运行）同类错误 **0 次** → 确认是故障机硬件本体问题
- VCOM 通道测试正常（写入 274/回读/写回 284 全通过）→ 排除 VCOM；锁定 PowerGood 供电路径
- 修复方向：重插屏幕排线 → 无效则换屏模组（重校 VCOM）或修主板 TPS6518x 供电链

## 相关文件（仓库其他位置）

- `ref.md` §九：完整诊断与 patch 过程记录
- `_scratch_gs/`：解包/扫描/patch 脚本（capstone 定位、rebuild_boot、verify_boot 等）
