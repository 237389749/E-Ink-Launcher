"""校验 v7 boot 镜像：(1) 结构完整 (2) 与已刷入的 v6-A2 内核逐字节比对，只应有预期差异。"""
import hashlib, os, struct, sys, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from gs_kernel_tool import extract_kernel


def decode(u32):
    """粗解码常用 ARM64 指令，便于审计差异（仅覆盖本工程用到的族）。"""
    if u32 == 0xD503201F:
        return 'nop'
    top = u32 >> 24
    if top == 0x52:                       # movz wN,#imm16
        return f'movz w{u32 & 31}, #{((u32 >> 5) & 0xFFFF)}'
    if top == 0x71:                       # cmp wN,#imm12 （subs wzr,wN,#imm）
        return f'cmp  w{(u32 >> 5) & 31}, #{((u32 >> 10) & 0xFFF)}'
    if top == 0xF1:
        return f'cmp  x{(u32 >> 5) & 31}, #{((u32 >> 10) & 0xFFF)}'
    if top in (0x14, 0x17):
        imm = u32 & 0x03FFFFFF
        if imm & 0x02000000:
            imm -= 0x04000000
        return f'{"b" if top == 0x14 else "bl"} <pc{imm * 4:+d}>'
    if top == 0x54:                       # b.cond
        cc = ['eq', 'ne', 'cs', 'cc', 'mi', 'pl', 'vs', 'vc',
              'hi', 'ls', 'ge', 'lt', 'gt', 'le', 'al', 'nv'][u32 & 15]
        imm = (u32 >> 5) & 0x7FFFF
        if imm & 0x40000:
            imm -= 0x80000
        return f'b.{cc} <pc{imm * 4:+d}>'
    if top == 0x1A:                       # cset wN,cond
        cc = ['eq', 'ne', 'cs', 'cc', 'mi', 'pl', 'vs', 'vc',
              'hi', 'ls', 'ge', 'lt', 'gt', 'le', 'al', 'nv'][(u32 >> 12) & 15]
        return f'cset w{u32 & 31}, {cc}'
    return ''


def check_boot(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'ANDROID!', 'bad magic'
    page = struct.unpack_from('<I', d, 36)[0]
    ksz = struct.unpack_from('<I', d, 8)[0]
    rsz = struct.unpack_from('<I', d, 16)[0]
    dec = zlib.decompressobj(16 + zlib.MAX_WBITS)
    k = dec.decompress(d[page:page + ksz], 128 << 20)
    rd_start = page + ((ksz + page - 1) // page) * page
    rd = d[rd_start:rd_start + rsz]
    return dict(page=page, ksz=ksz, rsz=rsz, kernel=k, boot_md5=hashlib.md5(d).hexdigest(),
                rd_md5=hashlib.md5(rd).hexdigest(), size=len(d))


ref = check_boot(os.path.join(HERE, '..', 'boot_patched_a2_v6.img'))
print(f'REF v6-A2 : size={ref["size"]} page={ref["page"]} kernel={len(ref["kernel"])} '
      f'rd={ref["rsz"]} rd_md5={ref["rd_md5"]}')
print()

for tag in sys.argv[1:]:
    p = os.path.join(HERE, '..', f'boot_v7{tag}.img')
    c = check_boot(p)
    same_struct = (c['size'] == ref['size'] and c['page'] == ref['page']
                   and c['rsz'] == ref['rsz'] and c['rd_md5'] == ref['rd_md5']
                   and len(c['kernel']) == len(ref['kernel']))
    diff = [i for i in range(len(ref['kernel'])) if ref['kernel'][i] != c['kernel'][i]]
    words = sorted({i & ~3 for i in diff})
    print(f'v7{tag}: struct_ok={same_struct} boot_md5={c["boot_md5"]} '
          f'kernel_md5={hashlib.md5(c["kernel"]).hexdigest()}')
    for w in words:
        a = struct.unpack_from('<I', ref['kernel'], w)[0]
        b = struct.unpack_from('<I', c['kernel'], w)[0]
        print(f'    0x{w:06x}: {a:08x} ({decode(a)}) -> {b:08x} ({decode(b)})')
    print()
