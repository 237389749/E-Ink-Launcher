"""MMKV 只读解析 + 定点修复（Onyx /onyxconfig/mmkv/onyx_config）。

★ 文件布局（实测反推，两代样本均吻合）：
    [0:4]      u32 actual_size (LE)         有效数据长度（不含本 4 字节）
    [4:8]      ff ff ff 07                  头标记 0x7FFFFFFF
    [8:8+N]    记录序列，逐条：
                 varint(klen) key(UTF-8)
                 varint(vlen) value(vlen 字节)
    [尾部]     未使用空间（清零）

★ 语义要点（这是本项目的核心教训）：
    · MMKV 是 append-only：写同一 key 时追加新记录，读取时"后写覆盖先写"。
    · **remove() 不是真删除**，而是追加一条 vlen==0 的记录当墓碑。
      所以「已删除」的 key 依然占着文件空间，文件只涨不缩。
    · 因此：vlen==0 不等于"数据损坏"，而是"该 key 当前不存在"。
    · 判断一个 key 的最终状态，必须取它【最后一次出现】的那条记录。

★ crc 文件（onyx_config.crc，4096 B）实测格式：
    crc[0:4]  = CRC32( mmkv[4 : 4+actual_size] )   ← 标准 zlib CRC32
    crc[4:8]  = 4
    crc[8:12] = 版本/计数（ONYX 各代不同：127 / 17 ...），不参与校验
    crc[12:28]= 0
    crc[28:32]= actual_size（与 mmkv 头一致）
    其余保留
  ⇒ 修改 mmkv 后必须同步重算 crc[0] 与 crc[28]，否则 MMKV 判定校验失败。
"""
import struct
import zlib


def read_varint(buf, i):
    v = 0
    shift = 0
    while i < len(buf):
        b = buf[i]
        i += 1
        v |= (b & 0x7F) << shift
        if not (b & 0x80):
            return v, i
        shift += 7
        if shift > 64:
            return None, i
    return None, i


def encode_varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def walk(path_or_bytes):
    """逐记录扫描。返回 (buf, actual_size, records, consumed)。

    records: [(key:str, vlen:int, value:bytes)]  按文件顺序，含墓碑。
    """
    buf = path_or_bytes if isinstance(path_or_bytes, (bytes, bytearray)) \
        else open(path_or_bytes, 'rb').read()
    if len(buf) < 8:
        return buf, 0, [], 0
    actual = struct.unpack_from('<I', buf, 0)[0]
    if actual == 0 or 4 + actual > len(buf):
        actual = len(buf) - 4
    end = 4 + actual
    i = 4
    if buf[i:i + 4] == b'\xff\xff\xff\x07':
        i += 4
    recs = []
    while i < end:
        klen, j = read_varint(buf, i)
        if not klen or klen > 1024 or j + klen > end:
            break
        try:
            key = buf[j:j + klen].decode('utf-8')
        except UnicodeDecodeError:
            break
        if not key or any(ord(c) < 0x20 for c in key):
            break
        i = j + klen
        vlen, k = read_varint(buf, i)
        if vlen is None or k + vlen > end:
            break
        recs.append((key, vlen, bytes(buf[k:k + vlen])))
        i = k + vlen
    return buf, actual, recs, i


def final_state(recs):
    """按 MMKV 覆盖语义求每个 key 的最终记录（后写覆盖先写）。"""
    final = {}
    for r in recs:
        final[r[0]] = r
    return final


def alive_keys(recs):
    """当前"存在"的 key（最终记录 vlen>0）。"""
    return {k: r for k, r in final_state(recs).items() if r[1] > 0}


def deleted_keys(recs):
    """墓碑中的 key（最终记录 vlen==0）。"""
    return {k: r for k, r in final_state(recs).items() if r[1] == 0}


def append_records(mmkv_bytes, items):
    """在 MMKV 末尾追加若干 (key, value_bytes) 记录，返回 (new_bytes, new_actual)。

    value_bytes 必须是 MMKV 编码后的完整 value 块（含类型头），
    与 walk() 里读出的 value 字节一致。
    """
    buf = bytearray(mmkv_bytes)
    actual = struct.unpack_from('<I', buf, 0)[0]
    payload = bytearray(buf[4:4 + actual])
    for key, vbytes in items:
        kb = key.encode('utf-8')
        payload += encode_varint(len(kb)) + kb
        payload += encode_varint(len(vbytes)) + vbytes
    new_actual = len(payload)
    if 4 + new_actual > len(buf):
        raise ValueError('MMKV 容量不足: need %d, have %d' % (4 + new_actual, len(buf)))
    struct.pack_into('<I', buf, 0, new_actual)
    buf[4:4 + new_actual] = payload
    for i in range(4 + new_actual, len(buf)):
        buf[i] = 0
    return bytes(buf), new_actual


def fix_crc(crc_bytes, mmkv_bytes):
    """按实测格式重算 crc 文件。返回新的 crc bytes。"""
    c = bytearray(crc_bytes)
    actual = struct.unpack_from('<I', mmkv_bytes, 0)[0]
    newcrc = zlib.crc32(bytes(mmkv_bytes[4:4 + actual])) & 0xFFFFFFFF
    struct.pack_into('<I', c, 0, newcrc)
    struct.pack_into('<I', c, 28, actual)
    return bytes(c)


def verify_crc(crc_bytes, mmkv_bytes):
    actual = struct.unpack_from('<I', mmkv_bytes, 0)[0]
    want = struct.unpack_from('<I', crc_bytes, 0)[0]
    got = zlib.crc32(bytes(mmkv_bytes[4:4 + actual])) & 0xFFFFFFFF
    return want == got, want, got
