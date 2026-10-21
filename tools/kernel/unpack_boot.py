import zlib, struct

data = open('/tmp/boot.img', 'rb').read()
d = zlib.decompressobj(16 + zlib.MAX_WBITS)
out = d.decompress(data[4096:], 64 * 1024 * 1024)
open('/tmp/kernel.img', 'wb').write(out)
magic = struct.unpack('<I', out[0x38:0x3c])[0]
print('arm64 image magic at 0x38:', hex(magic))
print('text_offset:', hex(struct.unpack('<Q', out[0x8:0x10])[0]))
print('decompressed:', len(out))
