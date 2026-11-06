# einkimg —— Poke6 `onyxconfig` 分区诊断与修复工具

纯 Python 3 标准库，**不需要 root / mount / WSL / lpunpack**。
用于解析 EDL 回读出来的分区镜像，从字节层面查清「刷新引擎失效」「卡开机」这类
数据层故障，并生成**只改必要文件**的修复镜像。

> 配套结论见 `HANDOFF.md` §5.1-16 与 §14。本文只讲怎么用。

---

## 0. 为什么需要这套工具

`/onyxconfig` 是 Poke6 上一个**独立的 ext4 分区**（`mmcblk0p80`）：

- **恢复出厂设置不擦它**（分区里能翻到 2024-08 的 `init_completed`、2025-01 的序列号文件）
- 卸载 app 也不清它在里面的 EAC 配置
- 装的是 MMKV 文件 `mmkv/onyx_config`（2 MB 容量上限，append-only）

⇒ 「恢复出厂也修不好刷新引擎 / 卡开机」的故障，根因常常藏在这个分区里，
而常规手段（清数据、重刷 boot、恢复出厂）都碰不到它。

---

## 1. 快速开始

```bash
# 列出镜像目录
python tools/einkimg/einkimg.py ls poke6_9008_new/images/onyxconfig.img
python tools/einkimg/einkimg.py ls poke6_9008_new/images/onyxconfig.img mmkv

# 导出 MMKV 文件
python tools/einkimg/einkimg.py cat poke6_9008_new/images/onyxconfig.img \
       mmkv/onyx_config out/cur_onyx_config

# 解析 MMKV（有效 key / 墓碑 / 单个 key 的值）
python tools/einkimg/einkimg.py mmkv out/cur_onyx_config list
python tools/einkimg/einkimg.py mmkv out/cur_onyx_config keys
python tools/einkimg/einkimg.py mmkv out/cur_onyx_config deleted
python tools/einkimg/einkimg.py mmkv out/cur_onyx_config get eac_app_pkg_set

# 两份镜像全面对比（文件级 + MMKV 逐 key 级）
python tools/einkimg/einkimg.py diff poke6_9008/images/onyxconfig.img \
                                    poke6_9008_new/images/onyxconfig.img
```

---

## 2. 生成修复镜像

```bash
python tools/einkimg/repair.py \
    --base  poke6_9008_new/images/onyxconfig.img \
    --donor poke6_9008/images/onyxconfig.img \
    --out   out/onyxconfig_fixed.img \
    --restore 'eac_theme_com.onyx@theme_type_3,eac_app_com.onyx,eac_default_app_configcom.onyx'
```

**三条铁律**（`repair.py` 已内置校验，违反会报错）：

1. **`--base` 必须是本机镜像**。`onyxconfig` 里至少 5 处每机专属数据，
   整盘用别的机器的镜像覆盖会改掉：
   | 文件 | 内容 |
   |---|---|
   | `DEVICE_SERIAL_NUMBER_KEY` | 14 位设备序列号 |
   | `mac_address` | 蓝牙/WiFi MAC |
   | `test_result_info` | 产测记录（含 fingerprint + mac） |
   | `ONYX_SYSTEM_CONFIG_KEY` | 系统设置（含 deviceStatus） |
   | `com.android.vcom` | **VCOM 电压校准**（面板专属） |
2. **只允许 `mmkv/onyx_config` 与 `mmkv/onyx_config.crc` 变化**。脚本最后会复核，
   出现其它变更直接判失败。
3. **修复方式 = append**，不重写、不删墓碑。MMKV 是 append-only，追加新记录 =
   后写覆盖先写，语义等价于「重新写入该 key」。

`--dry-run` 只打印计划不写盘。

---

## 3. MMKV 格式（实测反推，本项目核心）

```
[0:4]      u32 actual_size (LE)     有效数据长度（不含这 4 字节）
[4:8]      ff ff ff 07              头标记 0x7FFFFFFF
[8:8+N]    记录序列，逐条：
             varint(klen) key(UTF-8)
             varint(vlen) value(vlen B)
[尾部]     未使用空间（清零）
```

### ★ 三个必须记住的语义

1. **`remove()` 不是真删除**，而是追加一条 `vlen==0` 的记录当**墓碑**。
   ⇒ 「已删除」的 key 依然占文件空间，文件只涨不缩。
2. **`vlen==0` 不是"数据损坏"**，而是"该 key 当前不存在"。
   本项目一度把这 182 条墓碑误判为"写坏成 0 字节"，浪费了一轮排查。
3. 判断某 key 的最终状态，**必须取它最后一次出现的那条记录**（后写覆盖先写）。

### `.crc` 文件（`onyx_config.crc`，4096 B）

```
crc[0:4]   = CRC32( mmkv[4 : 4+actual_size] )   ← 标准 zlib crc32
crc[4:8]   = 4
crc[8:12]  = 版本/计数（各代不同：127 / 17 …），不参与校验
crc[12:28] = 0
crc[28:32] = actual_size（与 mmkv 头一致）
```

⇒ **改完 `onyx_config` 必须同步重算 `crc[0]` 和 `crc[28]`**，否则 MMKV 判校验失败。
`mmkv.fix_crc()` / `mmkv.verify_crc()` 已封装。

---

## 4. ext4 解析的几个坑（已在 `ext4fs.py` 里处理）

| 坑 | 后果 | 正确做法 |
|---|---|---|
| group descriptor 尺寸**无条件取 64** | 读错 inode 表 → 目录项变垃圾（`size` 巨大、`mode=0xFFFF`） | 只有 `s_feature_incompat & 0x80` 置位才是 64，否则 32 |
| 把 `EXTENTS_FL(0x80000)` 误判成 inline data | 文件全部读出 0 字节 | 先看 `i_block` 开头 2 字节 == `0xF30A`；inline 是 `0x10000000` 且**非** extents |
| extent 树 index 节点用 leaf 布局解析 | 深度≥1 的文件读出 0 块 | leaf: `ee_block/ee_len/ee_start_hi/ee_start_lo`；index: `ei_block/ei_leaf_lo/ei_leaf_hi/ei_unused` |
| 大目录（htree）rec_len 等于整块 | 解析出垃圾项 | 按 `name_len>0` 过滤即可 |
| `system/system` 是符号链接 | 递归找不到 priv-app | `resolve()` 已支持跟随 symlink |

---

## 5. super.img（动态分区）里找 ext4

`super.img` 的 LP metadata 在部分机型上被混淆过（本项目的 Poke6 回读镜像
`geometry magic` 后字段是随机值，解不出分区表）。**绕开 metadata，直接暴力扫 ext4 超级块**更可靠：

```bash
# 扫描所有超级块（4KB 对齐，magic 0xEF53 在 sb+56，并校验 log_block_size/inode_size 等）
python tools/einkimg/find_ext4.py <super.img>
```

实测 Poke6 结果：

| 分区 | 分区起点 | 大小 | volname |
|---|---|---|---|
| system | `0x0100000` | 2268 MB | `/` |
| system_ext | `0x07E400000` | 282 MB | `system_ext` |
| product | `0x08F500000` | 526 MB | `product` |
| vendor | `0x0AED00000` | 499 MB | `vendor` |
| （B 槽 system 副本） | `0x0D896F000` | 2268 MB | `/` |

然后用 `ext4fs.SubFile(img, base=0x0100000, length=2268MB)` 挂成 ext4 读即可，
不必切出 2 GB 临时文件。

---

## 6. OTA 接口（逆向结果，供参考）

反编译 system 分区里的 `app-market-release.apk` / `ksync-release.apk` 得到：

```java
// com.onyx.android.sdk.firmware.api.OnyxOTAService
@GET("firmware/update")
Call<Firmware> firmwareUpdate(@Query("where") String json);

// FirmwareUpdateRequest: base = ClusterHost.DEFAULT_CHINESE_DATA_HOST + "/api/"
//   -> https://data.boox.com/api/firmware/update?where=<json>
// where 字段: model, fingerprint, buildNumber, buildType, fwType, deviceMAC, submodel
// 响应 Firmware.downloadUrlList[] 即固件包地址
```

实测要点：

- 缺 `deviceMAC` → 服务器 500（`TypeError: toLowerCase of undefined`，
  报错里直接暴露了 `firmware.model.js:418` 的路径）
- 补齐 `deviceMAC` → 返回 **204 No Content**（= 无可用更新）
- 本机 `fingerprint = ONYX/BOOX/BOOX:11/RKQ1.210614.002/200:userdebug/release-keys`
- **服务器一律 204**（buildNumber 0~201、多个 model 名、两个 host 都试过）
  ⇒ 当前没有可下载的 Poke6 固件

> 注意：服务器用弱 DH 参数，Python/OpenSSL 3.x 需
> `ctx.set_ciphers("DEFAULT@SECLEVEL=1")` 才能握手。
