"""v7: 在 v6-A2 基础上，针对【本次新定位的 powerup 失败判据】做候选 patch。

背景（本次逆向，见 GS_FINDINGS_PMIC.md）:
  tps6518x 的 DISPLAY regulator enable 回调（Image 偏移 0x5F4198）在失败重试后
  用 `regmap_read(reg 0x0F)` 判定电源是否 OK，判据是 **精确相等 0xFA**
  （0x5F4374 与 0x5F47EC 两处 `cmp w8,#0xfa`）。
  故障机实测 684/684 次回读恒为 **0xBA**（bit6 缺失）→ 判据**永不可满足**
  → regulator_enable(DISPLAY) 恒返回 -ETIMEDOUT(0xffffff92)
  → onyx_epdc 记 epdc power error → 更新停滞 → wait all_lut_free 超时 → reset。

变体:
  A  pg-accept   0x5F4374/0x5F47EC  cmp #0xFA -> cmp #0xBA   （承认硬件实际回读值）
  B  pg-bypass   0x5F4378 改无条件跳成功 + 0x5F47F0 mov w0,#1 （完全绕开 PG 判据）
  C  keep-rails  0x5F43DC/0x5F43E0 置 nop                     （重试时不拉低电源轨；不改 PG 语义）
  D  fast-retry  0x5F4508 1.55s->50ms + 0x5F43AC 重试 1->3     （安全提速，对照实验）
  E  pg+fast     A + D                                        （推荐首选）

用法: python patch_kernel_v7.py <A|B|C|D|E> <out_boot.img>
"""
import hashlib, os, struct, sys, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
BASE_KERNEL = os.path.join(HERE, '..', 'kernel_extracted.img')
BOOT_ORIG = os.path.join(HERE, '..', 'boot_extracted.img')

NOP = 0xD503201F
REQUEUE_MODE = 4          # v6-A2（保持现有已刷入的 A2 重排队行为）


def movz_w(rd, imm16):
    return 0x52800000 | (imm16 << 5) | rd


def cmp_w(rd, imm):
    return 0x71000000 | (imm << 10) | (rd << 5) | 0x1F


V5_7 = [
    (0x542A70, movz_w(1, 0x1F4), movz_w(1, 0x32), 'wait lut_free 5s->0.5s'),
    (0x543110, movz_w(1, 0x1F4), movz_w(1, 0x32), 'wait all_lut_free 5s->0.5s'),
    (0x54314C, movz_w(2, 0x1388), movz_w(2, 0x1F4), 'log param 5000->500'),
    (0x5454A0, movz_w(0, 0x64), movz_w(0, 0x14), 'reset msleep 100->20'),
    (0x5F43AC, cmp_w(24, 3), cmp_w(24, 1), 'outer retry 3->1'),
    (0x5F46C0, cmp_w(24, 5), cmp_w(24, 1), 'inner retry 5->1'),
    (0x5F4410, movz_w(0, 0x12C), movz_w(0, 0x1E), 'retry-prep sleep 300->30'),
    (0x542B0C, movz_w(0, 2), movz_w(0, REQUEUE_MODE), f'requeue-A -> mode{REQUEUE_MODE}'),
    (0x5431AC, movz_w(0, 2), movz_w(0, REQUEUE_MODE), f'requeue-B -> mode{REQUEUE_MODE}'),
]

VARIANTS = {
    # A: 承认硬件真实回读值 0xBA（判据从"精确 0xFA"改为"精确 0xBA"）
    'A': V5_7 + [
        (0x5F4374, cmp_w(8, 0xFA), cmp_w(8, 0xBA), 'PG accept 0xBA (main gate)'),
        (0x5F47EC, cmp_w(8, 0xFA), cmp_w(8, 0xBA), 'PG accept 0xBA (gpio-path twin/dead)'),
    ],
    # B: 完全绕开 PG——命中即判成功
    'B': V5_7 + [
        (0x5F4378, 0x54000620, 0x14000031, 'force b 0x5F443C (unconditional success)'),
        (0x5F47F0, 0x1A9F17E0, movz_w(0, 1), 'force w0=1 in PG predicate'),
    ],
    # C: 不改 PG 语义——只是重试时不要把电源轨拉低
    'C': V5_7 + [
        (0x5F43DC, 0xB90076BF, NOP, 'nop  str wzr,[x21,#0x74]'),
        (0x5F43E0, 0x9405E8B8, NOP, 'nop  regmap_write(reg1, 0)  <- rail kill'),
    ],
    # D: 安全提速（对照；不改变"成功"判据）
    'D': V5_7 + [
        (0x5F4508, 0x0B080120, movz_w(0, 50), 'retry-prep msleep 1.55s -> 50ms'),
        (0x5F43AC, cmp_w(24, 1), cmp_w(24, 3), 'outer retry 1->3'),
    ],
}
# E: A + D（承认 0xBA 并同时把重试路径提速/加深）
VARIANTS['E'] = VARIANTS['A'] + VARIANTS['D'][len(V5_7):]
# F: 保留诊断的版本 —— 不动 `cmp w8,#0xfa`，只把"重试耗尽即返回 -110"改成"直接判成功"。
#    效果与 A 相同（不重试、不拉低电源轨、不 sleep 1.6s），但 Reg Enable / Reg PowerGood
#    两行 printk 仍然打印，便于日后判断 PMIC 真实状态。
VARIANTS['F'] = V5_7 + [
    (0x5F43B0, 0x54000480, 0x14000023,
     'b.eq 0x5F4440(ret -110) -> b 0x5F443C(ret 0): 保留 printk，仅去掉致命后果'),
]

# G: F + 把 wait all_lut_free 超时从 500ms 恢复到 2000ms。
# 依据（2026-10-08 实测）：全屏 GC16 ≈ 426ms、DU 全屏 ≈ 358ms（≈11ms/帧）。
#   v5 把该超时从 5s 压到 500ms，本意是让"故障机的失败循环"更快 —— 该理由已随 powerup 修复消失；
#   而余量只剩 15%，两个全屏更新排队时（2×426≈850ms）必然超时 → reset → 重排队又一个全屏 → 级联。
#   单位 jiffies(HZ=100)：500ms=50=0x32，2000ms=200=0xC8。
#   同时把 0x54314C 的日志参数 500→2000，使日志与实际一致。
VARIANTS['G'] = VARIANTS['F'] + [
    (0x543110, movz_w(1, 50), movz_w(1, 200), 'wait all_lut_free 500ms -> 2000ms'),
    (0x54314C, movz_w(2, 500), movz_w(2, 2000), 'log param 500 -> 2000'),
]

# H: F + 把 wait all_lut_free 超时**整体恢复到 stock 的 5000ms**
#    （等价于撤销 v5/v3 对该等待的全部压缩：0x543110 与 0x54314C 都回到出厂值）
VARIANTS['H'] = VARIANTS['F'] + [
    (0x543110, movz_w(1, 50), movz_w(1, 500), 'wait all_lut_free -> stock 5000ms(500 jiffies)'),
    (0x54314C, movz_w(2, 500), movz_w(2, 5000), 'log param -> stock 5000'),
]


def build(variant):
    patches = VARIANTS[variant] if variant in VARIANTS else VARIANTS['E']
    data = bytearray(open(BASE_KERNEL, 'rb').read())
    ok = True
    for off, expect, new, name in patches:
        cur = struct.unpack_from('<I', data, off)[0]
        if cur == expect:
            struct.pack_into('<I', data, off, new)
            print(f'  OK   0x{off:06x}: 0x{cur:08x} -> 0x{new:08x}  {name}')
        else:
            print(f'  FAIL 0x{off:06x}: cur 0x{cur:08x} expect 0x{expect:08x}  {name}')
            ok = False
    if not ok:
        sys.exit(1)
    return bytes(data)


def repack(kernel, out):
    boot = open(BOOT_ORIG, 'rb').read()
    page = struct.unpack_from('<I', boot, 36)[0]
    ksz = struct.unpack_from('<I', boot, 8)[0]
    rd_start = page + ((ksz + page - 1) // page) * page
    rd_size = struct.unpack_from('<I', boot, 16)[0]
    ramdisk = boot[rd_start:rd_start + rd_size]
    c = zlib.compressobj(9, zlib.DEFLATED, 31)
    gz = c.compress(kernel) + c.flush()
    hdr = bytearray(boot[:page])
    struct.pack_into('<I', hdr, 8, len(gz))
    out_bytes = bytes(hdr) + gz + b'\x00' * ((-len(gz)) % page) \
        + ramdisk + b'\x00' * ((-len(ramdisk)) % page)
    open(out, 'wb').write(out_bytes)
    print(f'  -> {out}  {len(out_bytes)} B  boot_md5={hashlib.md5(out_bytes).hexdigest()}')
    print(f'     kernel_md5={hashlib.md5(kernel).hexdigest()}')


if __name__ == '__main__':
    v = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, '..', f'boot_patched_v7{v}.img')
    print(f'=== v7{v} ===')
    repack(build(v), out)
