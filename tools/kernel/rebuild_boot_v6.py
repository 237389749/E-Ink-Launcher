"""v6 重打包 boot: gzip 新内核 -> 重建 boot header(v2) + 保留原 ramdisk。

用法: python patch_kernel_v6.py [mode] && python rebuild_boot_v6.py
产物: ../boot_patched_du_v6.img (16965632 B)
"""
import struct, zlib, hashlib, os, sys, tempfile

BOOT_ORIG = '../boot_extracted.img'
KERNEL_V6 = os.path.join(tempfile.gettempdir(), 'kernel_v6.img')
OUT = sys.argv[1] if len(sys.argv) > 1 else '../boot_patched_v6.img'

boot = open(BOOT_ORIG, 'rb').read()
kernel = open(KERNEL_V6, 'rb').read()

assert boot[:8] == b'ANDROID!'
page_size = struct.unpack_from('<I', boot, 36)[0]
orig_kernel_size = struct.unpack_from('<I', boot, 8)[0]
kernel_start = page_size
ramdisk_start = kernel_start + ((orig_kernel_size + page_size - 1) // page_size) * page_size
ramdisk_size = struct.unpack_from('<I', boot, 16)[0]
ramdisk = boot[ramdisk_start:ramdisk_start + ramdisk_size]

c = zlib.compressobj(9, zlib.DEFLATED, 31)
new_gz = c.compress(kernel) + c.flush()

new_header = bytearray(boot[:page_size])
struct.pack_into('<I', new_header, 8, len(new_gz))
kp = new_gz + b'\x00' * ((-len(new_gz)) % page_size)
rp = ramdisk + b'\x00' * ((-len(ramdisk)) % page_size)
out = bytes(new_header) + kp + rp
open(OUT, 'wb').write(out)

print(f'{OUT}: {len(out)} bytes')
print(f'  kernel gz={len(new_gz)}  page={page_size}  ramdisk={len(ramdisk)}')
print(f'  boot md5   = {hashlib.md5(out).hexdigest()}')
print(f'  kernel md5 = {hashlib.md5(kernel).hexdigest()}')
