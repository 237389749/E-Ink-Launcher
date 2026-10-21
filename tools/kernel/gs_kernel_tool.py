"""Boot 镜像内核提取 / patch 应用 / 校验（v7 工具链）。

用法:
  python gs_kernel_tool.py extract <boot.img> <out_kernel.img>
  python gs_kernel_tool.py verify  <boot.img>          # 与 kernel_extracted.img + v6 patch 逐字节比对
"""
import hashlib, os, struct, sys, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(HERE, '..', 'kernel_extracted.img')


def movz_w(rd, imm16):
    return 0x52800000 | (imm16 << 5) | rd


def cmp_w(rd, imm):
    """cmp w<rd>,#imm  ==  subs wzr, w<rd>, #imm  (rd==31 -> cmp wN)"""
    return 0x71000000 | (imm << 10) | (rd << 5) | 0x1F


def extract_kernel(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'ANDROID!', path
    page = struct.unpack_from('<I', d, 36)[0]
    ksz = struct.unpack_from('<I', d, 8)[0]
    dec = zlib.decompressobj(16 + zlib.MAX_WBITS)
    return dec.decompress(d[page:page + ksz], 128 << 20)


# v6 = v5 的 7 处 + 2 处重排队；重排队模式由参数决定
V5_7 = [
    (0x542A70, movz_w(1, 0x1F4), movz_w(1, 0x32), 'wait lut_free 5s->0.5s'),
    (0x543110, movz_w(1, 0x1F4), movz_w(1, 0x32), 'wait all_lut_free 5s->0.5s'),
    (0x54314C, movz_w(2, 0x1388), movz_w(2, 0x1F4), 'log param 5000->500'),
    (0x5454A0, movz_w(0, 0x64), movz_w(0, 0x14), 'reset msleep 100->20'),
    (0x5F43AC, cmp_w(24, 3), cmp_w(24, 1), 'outer retry 3->1'),
    (0x5F46C0, cmp_w(24, 5), cmp_w(24, 1), 'inner retry 5->1'),
    (0x5F4410, movz_w(0, 0x12C), movz_w(0, 0x1E), 'retry-prep sleep 300->30'),
]


def v6_patches(mode):
    return V5_7 + [
        (0x542B0C, movz_w(0, 2), movz_w(0, mode), f'requeue-A -> mode{mode}'),
        (0x5431AC, movz_w(0, 2), movz_w(0, mode), f'requeue-B -> mode{mode}'),
    ]


def apply(base, patches, verbose=True):
    data = bytearray(base)
    ok = True
    for off, expect, new, name in patches:
        cur = struct.unpack_from('<I', data, off)[0]
        if cur == expect:
            struct.pack_into('<I', data, off, new)
            if verbose:
                print(f'  OK   0x{off:06x}: 0x{cur:08x} -> 0x{new:08x}  {name}')
        else:
            print(f'  FAIL 0x{off:06x}: cur 0x{cur:08x} expect 0x{expect:08x}  {name}')
            ok = False
    return bytes(data), ok


def main():
    base = open(BASE, 'rb').read()
    if sys.argv[1] == 'extract':
        k = extract_kernel(sys.argv[2])
        open(sys.argv[3], 'wb').write(k)
        print(f'{sys.argv[3]}: {len(k)} bytes  md5={hashlib.md5(k).hexdigest()}')
        return
    if sys.argv[1] == 'verify':
        for path, mode in [(sys.argv[2], 4)]:
            k = extract_kernel(path)
            print(f'{path}: kernel {len(k)}B md5={hashlib.md5(k).hexdigest()}')
            patched, ok = apply(base, v6_patches(mode))
            print(f'  rebuilt v6(mode{mode}) md5={hashlib.md5(patched).hexdigest()}  match={patched == k}')
            if patched != k:
                diff = [i for i in range(0, min(len(k), len(patched))) if k[i] != patched[i]]
                print(f'  first diffs: {[hex(i) for i in diff[:20]]} (total {len(diff)})')
        return
    print(__doc__)


if __name__ == '__main__':
    main()
