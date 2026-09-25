# Poke6 墨水屏：从波形到显示（完整链路）

> 2026-09-25 编写。数据来源三重互证：**wbf 文件独立解析**（`eink_waveform.wbf`，md5
> `f463661b158b9dc394f2a8e78d9322f8`，256003 B）+ **设备在线受控实测**（Poke6 `6C7F0E64`，
> `repaintEverything` 触发 + `dump_lut_list` 抓取）+ **内核反汇编**（`kernel_extracted.img`）。
> 推导过程见 `ref.md` 第 9.3 节。

## 0. 一句话结论

**Poke6 的波形库里只有 5 种物理波形**（其中 3 种是同一份 GC16 数据），
**17 个 SDK 模式最终只落到 5 个 sg 槽**；而**灰阶、帧数、残影三者由同一个物理量
（bpp/state 覆盖）单调绑定** —— 要灰阶就必须全屏摆动，不存在"高灰阶 + 不闪"的组合。

---

## 1. 四层编号系统（务必区分，勿混用）

同一个"模式"在四层里有四个不同的数字，**混淆是历史多次误判的根源**。

| 层 | 来源 | 取值 | 例（GC16） |
|---|---|---|---|
| **L1 wbf 标准表** | E Ink `waveform_data_header` | `0x0`–`0xB`（12 列）| `mode2` = GC16 |
| **L2 SDK 波形号** | `ViewUpdateHelper.EINK_WAVEFORM_MODE_*` | `0`–`15`（低 4 位）| `2` = GC16 |
| **L3 sg 槽号** | 驱动 `dump_lut_list` 的 `waveform[N]` | `0`–`7`（Poke6 实有）| 实际落 `[2]` |
| **L4 UI/EPD 值** | 低 4 位波形号 + 高位标志位 OR | 任意 int | `2`、`98`、`257`… |

**L2 → L3 不是恒等映射**：Poke6 厂商把列内容重排了（见 §4）。

---

## 2. 基础波形：5 种物理波形

从 wbf 文件独立解析（22~24°C 段实测）：

| sg 槽 | L1 标准名 | bpp | 帧数 | state 覆盖 | 驱动率 | **同色态驱动**（闪烁度）| 可区分灰阶 |
|---|---|---|---|---|---|---|---|
| `[0]` | INIT | — | 113 | 193/256 | 0.743 | **0.750** | 3 |
| `[1]` | DU | 1bpp | **22** | 42/256 | 0.026 | 0.023 | 5 |
| `[2]` | GC16 | 4bpp | 39* | 243/256 | 0.219 | 0.229 | **16** |
| `[3]` | GC16_FAST | 4bpp | 39* | 244/256 | 0.221 | 0.240 | 16 |
| `[4]` | A2（名）| — | 39* | 244/256 | 0.221 | 0.240 | 16 |
| `[5]` | GL16 | 4bpp | 39* | 244/256 | 0.221 | 0.240 | 16 |
| `[6]` | GL16_FAST（名）| 4bpp | **10** | 18/256 | 0.004 | 0.009 | 3 |
| `[7]` | DU4 | 2bpp | **24** | 84/256 | 0.056 | 0.062 | 7 |

\* 设备实测 `frame_total` = **38**（wbf 解析 39，差 1，原因未明，**以设备值为准**）。

> **★ `mode3` / `mode4` / `mode5` 指针完全相同 ⇒ 逐字节同一份数据**（厂商把 GC16 复制给了
> GC16_FAST / A2 / GL16 三列）。**名字与内容错位**。
> **★ `mode8`–`modeB`（REAGL / REAGLD / GL4 / GL16_INV）在 Poke6 中【不存在】。**

### 2.1 三个物理量的含义

| 指标 | 定义 | 物理意义 |
|---|---|---|
| **bpp** | 每像素位数（L1 标准定义）| 灰阶级数上限 |
| **state 覆盖** | 波形库里有定义的 `(from,to)` 组合数 / 256 | 能表达的转换种类广度 |
| **驱动率** | 驱动 subframe 数 / 总 subframe 数 | 平均摆动强度 |
| **同色态驱动率** | `from == to`（**颜色没变**）时仍驱动的比例 | **闪烁度** = **清残影能力** |
| **可区分灰阶** | 固定 `from` 时，16 个 `to` 能区分出的不同粒子终点数 | 实际灰度级数 |

### 2.2 ★ 三者单调绑定（这是 E Ink 的物理必然）

```
bpp ↑  →  state 覆盖 ↑  →  同色态驱动 ↑  →  越闪、帧数越多
1bpp      42/256          0.023            不闪、快
4bpp      243/256         0.229            闪、慢
```

⇒ **"高灰阶 + 不闪烁"在 Poke6 上物理不可得**。
   REAGL 族（E Ink 标准里"不闪的灰阶波形"）**四列全部缺失**，所以无解。

---

## 3. 8×14 帧数矩阵（完整）

单位：帧；行 = 温度段，列 = wbf `mode0..7`。

| 温度 | mode0 | mode1 | mode2 | mode3 | mode4 | mode5 | mode6 | mode7 |
|---|---|---|---|---|---|---|---|---|
| 0°C | 152 | 73 | 131 | 131 | 131 | 131 | 35 | 84 |
| 3°C | 136 | 65 | 116 | 116 | 116 | 116 | 31 | 75 |
| 6°C | 120 | 58 | 102 | 102 | 102 | 102 | 28 | 65 |
| 9°C | 108 | 51 | 90 | 90 | 90 | 90 | 24 | 58 |
| 12°C | 92 | 43 | 77 | 77 | 77 | 77 | 21 | 50 |
| 15°C | 163 | 37 | 66 | 66 | 66 | 66 | 17 | 42 |
| 18°C | 139 | 31 | 55 | 56 | 56 | 56 | 15 | 35 |
| 21°C | 125 | 26 | 46 | 46 | 46 | 46 | 12 | 29 |
| **24°C** | 113 | **22** | **39** | 39 | 39 | 39 | **10** | **24** |
| **27°C** | 87 | 19 | 39 | **38** | **38** | **38** | 10 | 24 |
| **30°C** | 79 | 17 | **38** | 38 | 38 | 38 | 10 | 24 |
| **33°C** | 71 | 15 | **38** | 38 | 38 | 38 | 10 | 24 |
| **38°C** | 63 | 13 | **38** | 38 | 38 | 38 | 10 | 24 |
| **43°C** | 59 | 12 | **38** | 38 | 38 | 38 | 10 | 91⚠️ |

- **温度点数组** = `[0, 3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 38, 43]`（14 段，从 header `0x30` 读）
- **正常工作温度（24~38°C）**：GC16 族 **38**、A2 **10**、DU4 **24** —— 恒定
- **低温帧数翻倍**（0°C 时 GC16 = 131）⇒ 冷环境下刷新更慢
- ⚠️ **43°C 行 mode7=91 是伪值**：该段指针延伸到文件尾，尾部混入了厂商元数据表
  （`01 02 03 04...` 递增序列）。**mode7 在 43°C 应为 24**（与 38°C 同）

---

## 4. L2 波形号 → L3 sg 槽 映射（设备实测）

设备实测（`repaintEverything(值)` 受控触发 + `dump_lut_list` 抓取）：

| L2 波形号 | 含义 | → **sg 槽** | = wbf 列 | 该列实际装的内容 | `frame_total` | `update` |
|---|---|---|---|---|---|---|
| **1** | DU | **`[1]`** | mode1 | DU ✅ | **22** | `update[0]` 局部 |
| **2** | GC16 | **`[2]`** | mode2 | GC16 ✅ | **38** | `update[0]` 局部 |
| **3** | GC4 | `[2]` | mode2 | GC16（GC4 无独立列）| 38 | `update[0]` |
| **4** | **A2 / ANIM** | **`[6]`** | mode6 | **A2 式 5~10 帧** | **5** | **`update[1]` 全屏** |
| **6** | REAGL | **`[2]`**⚠️ | mode2 | **GC16** | 38 | `update[0]` |
| **8** | DU4 | **`[2]`**⚠️ | mode2 | **GC16（回落！）** | 38 | `update[0]` |
| **9** | REAGL_PLUS | **`[2]`**⚠️ | mode2 | **GC16** | 38 | `update[0]` |
| 11 | GCC16 | `[2]` | — | 超出列范围，回落 | 38 | `update[0]` |
| 12 | DEEP_GC16 | `[2]` | — | 超出列范围，回落 | 38 | `update[0]` |

### ★ 三个关键错位

1. **`A2` 名义列（mode4）装的是 GC16 数据** —— 所以 `mode4` 是 38 帧 GC16，**不是** A2。
2. **真正的 A2 数据在 `mode6`（标准名 GL16_FAST）** —— L2 波形号 `4` 落这里，实测 5 帧。
3. **`REAGL(6)` / `REAGL_PLUS(9)` 落 `mode4` = GC16 副本** ⇒ **表现为 GC16**。
   这就是历史悬案「REGAL 全刷实际是 GC16 38 帧」的答案：**目标列被厂商填成了 GC16**。

> **`DU4(8)` 实测落 `[2]` 而非 `[7]`** —— 说明 **scope 通道拿不到 DU4**。
> 用 UI 值 `2312`（= `DU4(8) | DITHER(256) | Y1(2048)`）能触发 `[7]/24`，但带混合态。

---

## 5. L4 UI 值 = 波形号 | 标志位（完整分解）

来源 `ViewUpdateHelper.java`，低位掩码 `EINK_WAVEFORM_MODE_MASK = 15`。

| UI 值 | 模式名 | 分解 | 生效波形 |
|---|---|---|---|
| 1 | DU | `DU(1)` | DU |
| 2 | GU | `GC16(2)` | GC16 |
| 3 | GC4 | `GC4(3)` | GC16（回落）|
| 4 | ANIMATION / A2 | `ANIM(4)` | **A2（全屏）** |
| 5 | DEFAULT / AUTO | `AUTO(5)` | GC16 |
| 6 | REGAL | `REAGL(6)` | GC16（列被占）|
| 9 | REGAL_PLUS | `REAGL_PLUS(9)` | GC16（列被占）|
| **98** | **GC** | `GC16(2) \| WAIT(64) \| FULL(32)` | GC16 |
| **107** | **GCC** | `GCC16(11) \| WAIT \| FULL` | GC16（超列回落）|
| **108** | **DEEP_GC** | `DEEP_GC16(12) \| WAIT \| FULL` | GC16（超列回落）|
| 257 | DU+DITHER | `DU(1) \| DITHER(256)` | GC16 |
| 2049 | DU+Y1 | `DU(1) \| DITHER_COLOR_Y1(2048)` | GC16 |
| 2305 | DU_QUALITY | `DU(1) \| DITHER \| Y1` | 混合 |
| **2308** | A2_QUALITY | `ANIM(4) \| DITHER \| Y1` | **A2（全屏）** |
| 2312 | DU4 | `DU4(8) \| DITHER \| Y1` | DU4 + 混合 |
| 4102 | REGAL_D | `REAGL(6) \| REAGL_D(4096)` | GC16 |
| 524290 | HW_REPAINT | `GC16(2) \| HANDWRITE_GU(524288)` | GC16 |
| 5242886 | REGAL_SHUTDOWN | `REAGL(6) \| SHUTDOWN(5242880)` | GC16 |
| 5242978 | GC_SHUTDOWN | `GC16(2) \| WAIT \| FULL \| SHUTDOWN` | GC16 |
| 16777217 | X_DU | `DU(1) \| ONYX_AUTO(16777216)` | DU |
| 16777220 | X_A2 | `ANIM(4) \| ONYX_AUTO` | **A2（全屏）** |
| 33554436 | MONO_A2 | `ANIM(4) \| ONYX_GC(33554432)` | **A2（全屏）** |

### 可用标志位全集

| 位 | 常量 | 语义 |
|---|---|---|
| 1–15 | `EINK_WAVEFORM_MODE_*` | ★ **基础波形号** |
| 16 | `EINK_AUTO_MODE_AUTOMATIC` | 自动模式（逐区域）|
| **32** | `EINK_UPDATE_MODE_FULL` | 全屏（0 = PARTIAL 局部）|
| 64 | `EINK_WAIT_MODE_WAIT` | 等待完成 |
| 128 | `EINK_COMBINE_MODE_COMBINE` | 合并更新 |
| **256** | `EINK_DITHER_MODE_DITHER` | ★ 抖动 |
| 512 | `EINK_INVERT_MODE_INVERT` | 反色 |
| 1024 | `EINK_CONVERT_MODE_CONVERT` | 颜色转换 |
| **2048** | `EINK_DITHER_COLOR_Y1` | ★ 抖动色阶 |
| 4096 | `EINK_REAGL_MODE_REAGLD` | Reagl-D 变体 |
| 524288 | `EPDC_FLAG_HANDWRITE_GU` | 手写 GU 重绘 |
| 2097152 | （未命名）| MERGE |
| 5242880 | `EINK_FLAG_SHUTDOWN` | 关机刷新 |
| 16777216 | `EINK_ONYX_AUTO_MASK` = `EINK_DITHER_X` | Onyx 自动决策 / X 位 |
| 33554432 | `EINK_ONYX_GC_MASK` = `EINK_APPLY_MONO` | GC / 单色 |

> ⚠️ **`FULL(32)` 位在 scope 通道【不生效】**：实测 `98`（含 FULL）仍是 `update[0]` 局部。
> `update[0]/[1]` 由 **native 依 waveform mode 判定**（内核仅透传，见 §7），用户态改不了。

---

## 6. 模式 → 灰阶 / 帧数 / 残影（速查）

**三维总表**（设备实测 + wbf 解析）：

| 模式 | UI 值 | 生效波形 | 灰阶级数 | 帧数 | 闪烁（同色态驱动）| `update` | 故障机 |
|---|---|---|---|---|---|---|---|
| DU | 1 | DU | **5** | **22** | 0.023 | `[0]` 局部 | ✅ |
| GC16 / GU | 2 | GC16 | **16** | **38** | 0.229 | `[0]` 局部 | ✅ |
| GC / GCC / DEEP_GC | 98/107/108 | GC16 | 16 | 38 | 0.229 | `[0]` 局部 | ✅ |
| REGAL / REGAL_PLUS | 6 / 9 | GC16 | 16 | 38 | 0.229 | `[0]` 局部 | ✅ |
| **A2 / ANIMATION** | 4 / 2308 | **A2** | **3** | **5** | 0.009 | **`[1]` 全屏** | ❌ reset 循环 |
| X_A2 / MONO_A2 | 16777220 / 33554436 | A2 | 3 | 5 | 0.009 | **`[1]` 全屏** | ❌ |
| DU4 | 2312 | DU4（回落 GC16）| 7 | 24 / 38 | 0.062 | 混合 | ⚠️ |
| DU_QUALITY | 2305 | 混合 | — | 5+22/38 | — | 混合 | ⚠️ |

**读法**：
- **要灰阶** → 只能 GC16 族（16 级），代价是 **38 帧 + 全是闪烁 0.229**
- **要快** → DU（22 帧）或 A2（5 帧），代价是 **无灰阶**（5 级 / 3 级）
- **要清残影** → 只有 GC16 族（同色态驱动 0.229）；DU/A2/DU4 都是差分模式，**不清残影**
- **故障机禁用 A2 族**（`update[1]` 全屏 → `wait all_lut_free` 超时 → reset 循环）

---

## 7. 完整链路（从 App 到面板）

```
① App 请求刷新
      ↓
② EAC / launcher 决定 UI 值（波形号 | 标志位）
      ↓
③ scope 通道: ViewUpdateHelper.applyAppScopeUpdate(pkg, ..., ui值, ...)
      ↓  （EAC 通道则经 EACUtils.toEpdMode 归一化，非 0-5 全变 5）
④ SurfaceFlinger (native) 解析 → EPDC ioctl (SET_EBC_SEND_UPDATE + flags)
      ↓
⑤ 内核: 纯透传（0x532dbc ldr [x1,#0x30] → str [x0,#0x80] → str [x0,#0x30]）
      ↓  ★ 无决策、无改写
⑥ epdc 驱动: get_waveform_mode_index(mode) 查表 → sg 槽号
      ↓  表 @0x1f69240, 步长 0x4c, mode 0~4 → 槽 0~4, 其余 → -1 (回落)
⑦ sg 波形库: 从 wbf 取该槽该温度段的波形数据
      ↓
⑧ EPDC 硬件: 按 subframe 逐帧驱动面板
      ↓
⑨ 面板: 粒子移动到位 → 显示
```

### 关键节点说明

| 节点 | 决定什么 | 可否改 |
|---|---|---|
| ② UI 值 | 波形号 + 标志位 | ✅ launcher/EAC 层可改 |
| ③ scope vs EAC | scope 接受任意值；EAC 被 `toEpdMode` 归一化 | ✅ 用 scope 通道 |
| ④ flags 的 `update` 位 | `update[0]/[1]` | ❌ **由 native 依 waveform mode 决定** |
| ⑤-⑥ 内核 | 查表得槽号；**不改 update** | ⚠️ 可 patch 波形号立即数 |
| ⑦ wbf 数据 | 帧数、灰阶、闪烁度 | ❌ 需改 wbf 文件（厂商签名）|

### 内核逆向关键地址（供参考）

| 地址 | 内容 |
|---|---|
| `0x532dbc` / `0x532dc0` | ioctl 结构 `+0x30` (update) → 内部结构 `+0x80` |
| `0x532cd0` / `0x532cd4` | 内部结构 `+0x80` → LUT 描述符 `+0x30` |
| `0x534400` | `dump_lut_list` 打印点 |
| `0x534410`~`0x534428` | 打印字段：`+0x38`=magic, `+0x10`=lut, `+0x2c`=waveform, `+0x30`=update, `+0x35`=frame_cur, `+0x34`=frame_total |
| `0x550ab0` | `get_waveform_mode_index` |
| `0x1f69240` | 波形模式映射表（步长 0x4c）|
| `0x542b0c` / `0x5431ac` | reset 重排队波形号（v6 patch 改此处）|

---

## 8. 设备端验证命令

```bash
# --- 波形库 / 帧数 ---
adb shell su -c "cat /sys/class/sepdc/debug/status"
#   → epdctask_status[...] frame[a:b:c]   (a/b/c 相等=无活动; a>b>c=推进中)

adb shell su -c "cat /sys/class/sepdc/debug/dump_list"   # cat 即触发全量 dump
#   → dump_lut_list(): magic[N] lut[N] waveform[N] update[N] frame_cur[N] frame_total[N]

# --- 受控触发某波形（观察落哪个槽）---
adb shell su -c "CLASSPATH=/data/local/tmp/sscope.dex app_process /system/bin io.onyx.SetScope <UI值>"
adb shell su -c "CLASSPATH=/data/local/tmp/tw.dex app_process /system/bin io.onyx.TestWaveform <UI值> 2"

# --- 供电故障指标（本机为故障机）---
adb shell su -c "dmesg | grep -E 'TPS6518x|epdc power error|all_lut_free timeout|reset cause'"
adb shell su -c "cat /sys/class/sepdc/debug/update_err"   # 恒 1 = 上次失败未恢复

# --- wbf 文件 ---
adb shell su -c "md5sum /vendor/firmware/eink_waveform.wbf"
#   期望: f463661b158b9dc394f2a8e78d9322f8
```

---

## 9. 复现方法（解析脚本）

| 脚本 | 作用 |
|---|---|
| `wbf_final.py` | 解析 wbf → 8×14 帧数矩阵 |
| `wbf_defb.py` | 计算 state 覆盖 / 驱动率 / 同色态驱动率 |
| `wbf_gray4.py` | 提取"可区分灰阶级数" |
| `wbf_ptr.py` | dump 全部指针 + 校验和验证 |

**wbf 格式要点**（标准 E Ink `waveform_data_header`）：

```
mc @0x25       = mode 数 - 1        (Poke6: 7 → 8 个 mode)
trc @0x26      = 温度段数 - 1       (Poke6: 13 → 14 段)
温度点数组 @0x30 = NTEMP 个字节      (Poke6: 0,3,6,...,43)
表指针 @0x1c    = u24 → +1+len+1 = mode 表起点
mode 表项       = 3B 偏移 + 1B 校验和, 步长 4
temp 指针       = 每 mode 内 NTEMP 个 u24
段数据          = 0xfc 切换 literal/RLE; RLE = 2B (值 + count-1)
                 解码后长度 = phases × 256, 每字节含 4 个 2-bit subframe
subframe 值: 0=不驱动, 1=黑脉冲, 2=白脉冲, 3=?
```

⚠️ **拉取 wbf 必须二进制安全**：`adb pull` 或设备端 `base64 < 文件`；
`adb shell cat > 文件` 会被 Windows CRLF 污染（插入 `0x0d`）导致解析错乱。

---

## 10. 已知限制 / 未解项

| # | 项 | 说明 |
|---|---|---|
| 1 | wbf 解析 39 帧 vs 设备 38 | 差 1，原因未明；**以设备 `frame_total` 为准** |
| 2 | mode7 (DU4) 43°C 段读数 91 | **伪值** —— 该段指针延伸到文件尾，混入厂商元数据表。应为 24 |
| 3 | subframe 值 `3` 的语义 | 未确定（0/1/2 已知）|
| 4 | "可区分灰阶"为自建指标 | 定义见 §2.1；非官方规范，仅用于横向对比，**勿单独引用** |
| 5 | Poke6 无 REAGL 族 | `mode8`–`modeB` 缺失 ⇒ "不闪的灰阶"物理不可得 |
| 6 | 环境温度影响帧数 | 0°C 时 GC16 = 131 帧（vs 24°C 的 39）；低温刷新显著变慢 |
