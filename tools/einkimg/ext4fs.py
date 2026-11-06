"""ext4 只读解析核心（纯标准库，无依赖）。

用途：解析 EDL 回读出来的分区镜像（如 onyxconfig.img / super.img 切片），
提取文件、列出目录，不需要 root / mount / WSL。

★ 关键坑（本项目踩过，务必保留）：
  1. group descriptor 尺寸不能无条件取 64 字节。
     只有 s_feature_incompat & 0x80 (EXT4_FEATURE_INCOMPAT_64BIT) 置位时才是 64，
     否则是 32。本机的 onyxconfig / system 分区都【没有】开 64bit，
     误用 64 会读错 inode 表 → 目录项解析出垃圾（表现为 size 巨大 / mode=0xFFFF）。
  2. s_desc_size @254 只是"建议值"，与 feature 位冲突时以 feature 位为准。
  3. extents：i_block 开头 2 字节 == 0xF30A 才是 extent 树，与 EXT4_EXTENTS_FL
     (0x00080000) 同时置位；不要把它误判成 inline data (0x10000000)。
  4. extent 树的 index 节点条目布局与 leaf 不同：
        leaf : ee_block(u32) ee_len(u16) ee_start_hi(u16) ee_start_lo(u32)
        index: ei_block(u32) ei_leaf_lo(u32) ei_leaf_hi(u16) ei_unused(u16)
     用 leaf 的解析去解 index 会得到 0 个块（深度 1 的文件全部读空）。
  5. 大目录（htree）里 rec_len 可能等于整块且 inode=0，按 name_len>0 过滤即可。
"""
import struct

EXT4_FEATURE_INCOMPAT_64BIT = 0x0080
EXT4_EXTENTS_FL = 0x00080000
EXT4_INLINE_DATA_FL = 0x10000000
S_IFDIR = 0x4000
S_IFREG = 0x8000
S_IFLNK = 0xA000


def parse_superblock(sb):
    if sb[56:58] != b'\x53\xef':
        raise ValueError('not ext4: bad magic at sb+56')
    d = {}
    d['inodes_count'] = struct.unpack_from('<I', sb, 0)[0]
    d['blocks_count'] = (struct.unpack_from('<I', sb, 4)[0]
                         | (struct.unpack_from('<I', sb, 0x150)[0] << 32))
    d['free_blocks'] = struct.unpack_from('<I', sb, 12)[0]
    d['free_inodes'] = struct.unpack_from('<I', sb, 16)[0]
    d['first_data_block'] = struct.unpack_from('<I', sb, 20)[0]
    d['log_block_size'] = struct.unpack_from('<I', sb, 24)[0]
    d['blocks_per_group'] = struct.unpack_from('<I', sb, 32)[0]
    d['inodes_per_group'] = struct.unpack_from('<I', sb, 40)[0]
    d['mtime'] = struct.unpack_from('<I', sb, 44)[0]
    d['wtime'] = struct.unpack_from('<I', sb, 48)[0]
    d['state'] = struct.unpack_from('<H', sb, 58)[0]
    d['rev_level'] = struct.unpack_from('<I', sb, 76)[0]
    d['inode_size'] = struct.unpack_from('<H', sb, 88)[0]
    d['feature_incompat'] = struct.unpack_from('<I', sb, 96)[0]
    d['volname'] = sb[120:136].split(b'\x00')[0].decode('utf-8', 'replace')
    d['last_mounted'] = sb[136:200].split(b'\x00')[0].decode('utf-8', 'replace')
    d['is_64bit'] = bool(d['feature_incompat'] & EXT4_FEATURE_INCOMPAT_64BIT)
    raw_desc = struct.unpack_from('<H', sb, 254)[0]
    d['desc_size'] = raw_desc if (d['is_64bit'] and raw_desc in (32, 64)) else 32
    d['block_size'] = 1024 << d['log_block_size']
    d['ngroups'] = (d['blocks_count'] - d['first_data_block'] - 1) // d['blocks_per_group'] + 1
    return d


class Ext4FS:
    """reader 需要提供 seek(off) / read(n)，坐标系以分区起点为 0。"""

    def __init__(self, reader):
        self.f = reader
        self.f.seek(1024)
        sb = self.f.read(1024)
        self.__dict__.update(parse_superblock(sb))
        self.gdt_off = (self.first_data_block + 1) * self.block_size

    # ---------- 底层 ----------
    def read_block(self, blk):
        self.f.seek(blk * self.block_size)
        return self.f.read(self.block_size)

    def inode(self, ino):
        g = (ino - 1) // self.inodes_per_group
        i = (ino - 1) % self.inodes_per_group
        self.f.seek(self.gdt_off + g * self.desc_size)
        d = self.f.read(self.desc_size)
        if len(d) < 32:
            raise IOError('short group desc for ino %d' % ino)
        itab = struct.unpack_from('<I', d, 8)[0]
        if self.desc_size >= 64:
            itab |= struct.unpack_from('<I', d, 40)[0] << 32
        self.f.seek(itab * self.block_size + i * self.inode_size)
        n = self.f.read(self.inode_size)
        if len(n) < self.inode_size:
            raise IOError('short inode %d' % ino)
        return n

    def inode_info(self, ino):
        n = self.inode(ino)
        return dict(
            mode=struct.unpack_from('<H', n, 0)[0],
            size=struct.unpack_from('<I', n, 4)[0] | (struct.unpack_from('<I', n, 108)[0] << 32),
            flags=struct.unpack_from('<I', n, 32)[0],
            links=struct.unpack_from('<H', n, 26)[0],
            mtime=struct.unpack_from('<I', n, 16)[0],
            raw=n,
        )

    # ---------- extent / 间接块 ----------
    def _extents(self, buf, out):
        magic, entries, maxn, depth = struct.unpack_from('<HHHH', buf, 0)
        if magic != 0xF30A:
            return False
        for k in range(entries):
            off = 12 + k * 12
            if off + 12 > len(buf):
                break
            if depth == 0:
                _, ee_len, hi, lo = struct.unpack_from('<IHHI', buf, off)
                start = (hi << 32) | lo
                n = ee_len & 0x7FFF
                if n > 32768:
                    n = 32768
                for j in range(n):
                    out.append(start + j)
            else:
                _, leaf_lo, leaf_hi, _ = struct.unpack_from('<IIHH', buf, off)
                child = (leaf_hi << 32) | leaf_lo
                if child:
                    self._extents(self.read_block(child), out)
        return True

    def _indirect(self, blk, out, level):
        if not blk:
            return
        data = self.read_block(blk)
        for k in range(self.block_size // 4):
            p = struct.unpack_from('<I', data, k * 4)[0]
            if not p:
                continue
            if level == 1:
                out.append(p)
            else:
                self._indirect(p, out, level - 1)

    def file_blocks(self, ino):
        info = self.inode_info(ino)
        ib = info['raw'][40:100]
        out = []
        if struct.unpack_from('<H', ib, 0)[0] == 0xF30A:
            self._extents(ib, out)
        else:
            for k in range(15):
                p = struct.unpack_from('<I', ib, k * 4)[0]
                if not p:
                    continue
                if k < 12:
                    out.append(p)
                elif k == 12:
                    self._indirect(p, out, 1)
                elif k == 13:
                    self._indirect(p, out, 2)
                else:
                    self._indirect(p, out, 3)
        return out

    # ---------- 文件 / 目录 ----------
    def read_file(self, ino):
        info = self.inode_info(ino)
        size = info['size']
        if size == 0:
            return b''
        ib = info['raw'][40:100]
        is_ext = struct.unpack_from('<H', ib, 0)[0] == 0xF30A
        if (info['flags'] & EXT4_INLINE_DATA_FL) and not is_ext:
            extra = struct.unpack_from('<H', info['raw'], 128)[0] if self.inode_size > 128 else 0
            buf = info['raw'][40 + extra:100]
            return bytes(buf[:size]) if size <= len(buf) else bytes(buf)
        chunks = []
        remain = size
        for blk in self.file_blocks(ino):
            if remain <= 0:
                break
            d = self.read_block(blk)
            chunks.append(d[:remain] if remain < len(d) else d)
            remain -= len(d)
        return b''.join(chunks)[:size]

    def listdir(self, ino):
        info = self.inode_info(ino)
        data = self.read_file(ino)
        if info['flags'] & EXT4_INLINE_DATA_FL:
            data = data[4:]
        out = []
        off = 0
        n = len(data)
        while off + 8 <= n:
            i2 = struct.unpack_from('<I', data, off)[0]
            rec_len = struct.unpack_from('<H', data, off + 4)[0]
            name_len = data[off + 6]
            if rec_len < 8 or off + rec_len > n:
                off = (off // self.block_size + 1) * self.block_size
                continue
            if i2 and 0 < name_len <= min(rec_len - 8, 255):
                nm = data[off + 8:off + 8 + name_len]
                if nm not in (b'.', b'..'):
                    out.append((nm.decode('utf-8', 'replace'), i2))
            off += rec_len
        return out

    def resolve(self, path, follow=True):
        ino = 2
        parts = [p for p in path.split('/') if p]
        for part in parts:
            nxt = None
            for name, i2 in self.listdir(ino):
                if name == part:
                    nxt = i2
                    break
            if nxt is None:
                return None
            info = self.inode_info(nxt)
            if (info['mode'] & 0xF000) == S_IFLNK and follow:
                tgt = self.read_file(nxt).decode('utf-8', 'replace')
                ino = 2 if tgt.startswith('/') else ino
                for p2 in [x for x in tgt.split('/') if x]:
                    ino = self._child(ino, p2)
                    if ino is None:
                        return None
                continue
            ino = nxt
        return ino

    def _child(self, parent, name):
        for nm, i2 in self.listdir(parent):
            if nm == name:
                return i2
        return None

    def is_dir(self, ino):
        return (self.inode_info(ino)['mode'] & 0xF000) == S_IFDIR

    def walk(self, ino=2, prefix='', depth=0, maxdepth=6, out=None):
        out = [] if out is None else out
        if depth > maxdepth:
            return out
        try:
            entries = self.listdir(ino)
        except Exception:
            return out
        for name, i2 in entries:
            full = prefix + '/' + name if prefix else name
            try:
                info = self.inode_info(i2)
            except Exception:
                continue
            kind = info['mode'] & 0xF000
            out.append((full, i2, kind, info['size']))
            if kind == S_IFDIR:
                self.walk(i2, full, depth + 1, maxdepth, out)
        return out


class SubFile:
    """把大文件的一个区间包装成"独立文件"，供 Ext4FS 使用（避免切出几 GB 临时文件）。"""

    def __init__(self, path, base=0, length=None):
        self.fh = open(path, 'rb')
        self.base = base
        self.fh.seek(0, 2)
        total = self.fh.tell()
        self.length = (total - base) if length is None else min(length, total - base)
        self.pos = 0

    def seek(self, off, whence=0):
        if whence == 0:
            self.pos = off
        elif whence == 1:
            self.pos += off
        else:
            self.pos = self.length + off
        return self.pos

    def read(self, n=-1):
        if n < 0:
            n = self.length - self.pos
        n = max(0, min(n, self.length - self.pos))
        self.fh.seek(self.base + self.pos)
        d = self.fh.read(n)
        self.pos += len(d)
        return d

    def close(self):
        self.fh.close()
